package agentica.agent

import agentica.agent.AgentEvent
import agentica.llm.{LLMProvider, LLMResponse, NativeToolCall}
import agentica.observability.{TokenAccounting, TraceLogger}
import agentica.permissions.{PermissionCoordinator, ScopeStore}
import agentica.session.{AgentTurn, AgentTurnStep, AgentTurnStore, MemoryStore, Message, MessageRole, MessageStore, RunStatus, RunStore, Session, SessionStore, ToolRun}
import agentica.settings.{APIMode, AppSettings}
import agentica.shell.{SessionScratchpad, VirtualShell}
import agentica.tools.ExecutionContext
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 *  Full Phase 2 plan→act→observe agent loop.
 *  Iterates up to `settings.maxIterations` times, dispatching the native function
 *  calls (`tool_calls`) in each LLM response through [[VirtualShell]], injecting
 *  results as `tool`-role messages correlated by `tool_call_id`, until the model
 *  responds with no further tool calls.
 *  @param initialLLMProvider      Initial LLM provider for streaming; replaceable via [[updateProviders]].
 *  @param initialVLMProvider      Initial optional Vision LLM provider; replaceable via [[updateProviders]].
 *  @param messageStore             Persistence layer for chat messages.
 *  @param runStore                 Persistence layer for tool runs.
 *  @param tokenAccounting          Records LLM token usage per call.
 *  @param virtualShell             Dispatches tool calls through the command registry.
 *  @param settings                 Application settings (maxIterations, etc.).
 *  @param scopeStore               Permission grant store for sensitive tools.
 *  @param memoryStore              Session-scoped key-value memory store.
 *  @param sessionStore             Persistence layer for session records; used to persist response IDs.
 */
class AgentLoop(
    initialLLMProvider: LLMProvider,
    initialVLMProvider: Option[LLMProvider],
    messageStore:       MessageStore,
    runStore:           RunStore,
    agentTurnStore:     AgentTurnStore,
    tokenAccounting:    TokenAccounting,
    virtualShell:       VirtualShell,
    settings:           AppSettings,
    scopeStore:         ScopeStore,
    memoryStore:        MemoryStore,
    sessionStore:       SessionStore
) extends AgentEngine
{

    @volatile private var llmProvider: LLMProvider         = initialLLMProvider
    @volatile private var vlmProvider: Option[LLMProvider] = initialVLMProvider

    /**
     *  Replaces the LLM and VLM providers used for all future runs.
     *  Thread-safe via `@volatile`; runs already in progress are unaffected.
     *  @param llm  New primary LLM provider.
     *  @param vlm  New optional VLM provider.
     */
    def updateProviders(llm: LLMProvider, vlm: Option[LLMProvider]): Unit =
    {
        llmProvider = llm
        vlmProvider = vlm
        val vlmName = vlm.map(_.modelName).getOrElse("(none; falling back to primary LLM)")
        println(s"[AgentLoop] Providers updated — LLM: ${llm.modelName}, VLM: $vlmName")
        TraceLogger.info("-", "providers_updated",
            Map("model" -> llm.modelName, "vlm" -> vlmName))
    }

    /**
     *  Runs the full plan→act→observe loop for one user turn.
     *  Emits exactly one terminal event: [[AgentEvent.Final]], [[AgentEvent.Cancelled]],
     *  or [[AgentEvent.AgentError]].
     *  @param session     Active session metadata.
     *  @param history     Prior message history (assembled by caller).
     *  @param userMsg     New user message appended to the history.
     *  @param traceId     Trace ID for this run.
     *  @param cancelFlag       Polled between iterations and tool calls for external cancellation.
     *  @param permissionCoordinator  Coordinates one-shot UI permission requests for this run.
     *  @param emitToken        Called to emit each streamed text token from the LLM.
     *  @param emitEvent        Called to emit structured lifecycle SSE events.
     */
    def run(
        session:         Session,
        history:         List[Message],
        userMsg:         Message,
        traceId:         String,
        cancelFlag:      AtomicBoolean,
        permissionCoordinator: PermissionCoordinator,
        emitToken:       String => Unit,
        emitEvent:       AgentEvent => Unit
    ): Unit =
    {
        val vlmModel = vlmProvider.map(_.modelName).getOrElse("(none; falling back to primary LLM)")
        println(s"[AgentLoop] Using LLM model: ${llmProvider.modelName}")
        println(s"[AgentLoop] Using VLM model: $vlmModel")
        TraceLogger.info(traceId, "agent_loop_start",
            Map(
                "sessionId" -> session.id,
                "sessionModel" -> session.model,
                "llmModel" -> llmProvider.modelName,
                "vlmModel" -> vlmModel
            ))

        // Accumulates assistant tool-call turns + tool-result messages added during the
        // current run. These are appended after the budget-windowed history on every
        // buildContext() call so the model always sees the full in-flight tool exchange.
        val toolResultTurns = scala.collection.mutable.ListBuffer.empty[Message]
        // Messages added by the most recent tool-dispatch iteration — the delta sent
        // to the Responses API on warm continuations.
        var lastTurnDelta: List[Message] = Nil
        // Accumulates ordered trajectory steps for AgentTurn persistence.
        val turnSteps       = scala.collection.mutable.ListBuffer.empty[AgentTurnStep]
        // Shared context for all tool calls within this run
        val sharedScratchpad = SessionScratchpad()
        val sharedCtx = ExecutionContext(
            session         = session,
            traceId         = traceId,
            scopeStore      = scopeStore,
            scratchpad      = sharedScratchpad,
            memoryStore     = memoryStore,
            llmProvider     = llmProvider,
            vlmProvider     = vlmProvider,
            onEvent         = emitEvent,
            permissionCoordinator = permissionCoordinator,
            debugMode       = settings.debugMode,
            vlmParallelism  = settings.vlmParallelism
        )
        var iteration = 1
        // Tracks the last Responses API response ID across all iterations in this run.
        // Loaded from the session at the start of the run; updated after every LLM call.
        var lastResponseId: Option[String] = session.lastResponseId

        // Rebuilds the full message list for the next LLM call on every iteration.
        // ContextManager applies the token-budget window to the persistent history,
        // then toolResultTurns (always included) are appended after.
        def buildContext(): List[Message] =
        {
            val assembled = ContextManager.assemble(
                history             = history,
                userMsg             = userMsg,
                session             = session,
                contextBudgetTokens = settings.contextBudgetTokens,
                traceId             = traceId
            )
            assembled ++ toolResultTurns.toList
        }

        var running = true
        while (running)
        {
            // --- Safety guards (checked before every LLM call) ---

            if (iteration > settings.maxIterations)
            {
                // Hard cap: prevents runaway loops on models that keep requesting tools.
                TraceLogger.warn(traceId, "max_iterations_exceeded",
                    Map("maxIterations" -> settings.maxIterations.toString))
                emitEvent(AgentEvent.AgentError("max_iterations_exceeded"))
                running = false
            }
            else if (cancelFlag.get())
            {
                // The UI sent a cancel request (DELETE /runs/:runId sets this flag).
                TraceLogger.info(traceId, "agent_cancelled", Map("iteration" -> iteration.toString))
                emitEvent(AgentEvent.Cancelled)
                running = false
            }
            else
            {
                // --- PLAN: signal iteration start, then call the LLM ---

                emitEvent(AgentEvent.IterationBoundary(iteration))

                val buf = StringBuilder()
                // wrappedEmitToken captures each token into buf (for full-response parsing)
                // and simultaneously streams it to the SSE client via emitToken.
                val wrappedEmitToken = (tok: String) => { buf.append(tok); emitToken(tok) }

                val context = buildContext()
                emitEvent(AgentEvent.LLMCallStart(iteration, llmProvider.modelName, context.length))
                TraceLogger.info(traceId, "llm_call_start", Map(
                    "iteration" -> iteration.toString,
                    "model"     -> llmProvider.modelName,
                    "msgCount"  -> context.length.toString,
                    "context"   -> context.map(m =>
                        s"[${m.role.value}] ${m.content}"
                    ).mkString("\n---\n")
                ))

                // Select the input messages to send to the Responses API:
                //  - Cold start (no lastResponseId): send the full context so the server
                //    has the system prompt, history, and new user message.
                //  - Warm continuation, first iteration: the server already has all prior
                //    state — send only the new user message.
                //  - Warm continuation, subsequent iterations: the server retains the prior
                //    assistant function-call items — send only the correlated tool outputs.
                val llmInput: List[Message] = lastResponseId match
                {
                    case None    => context
                    case Some(_) =>
                        if (iteration == 1) List(userMsg)
                        else lastTurnDelta.filter(_.role == MessageRole.Tool)
                }

                val llmResponseOpt: Option[LLMResponse] = try
                {
                    val response = settings.apiMode match
                        case APIMode.Responses =>
                            llmProvider.streamResponses(llmInput, wrappedEmitToken, lastResponseId,
                                virtualShell.toolSchemas)
                        case APIMode.ChatCompletions =>
                            llmProvider.streamChatCompletions(context, wrappedEmitToken,
                                virtualShell.toolSchemas)
                    Some(response)
                }
                catch
                {
                    case ex: Exception =>
                        TraceLogger.error(traceId, "llm_stream_error",
                            Map("iteration" -> iteration.toString, "error" -> ex.getMessage))
                        emitEvent(AgentEvent.AgentError(ex.getMessage))
                        running = false
                        None
                }

                llmResponseOpt.foreach { llmResponse =>
                    // Persist the new response ID so the next iteration (and next run) can use it.
                    llmResponse.responseId.foreach { rid =>
                        lastResponseId = Some(rid)
                        sessionStore.updateLastResponseId(session.id, rid)
                    }
                    tokenAccounting.record(traceId, session.id, llmResponse)

                    val responseText = buf.toString

                    TraceLogger.info(traceId, "llm_response", Map(
                        "iteration" -> iteration.toString,
                        "response"  -> responseText
                    ))

                    // --- ACT: read native tool calls from the structured response ---
                    // Deduplicate to guard against models repeating the same call.
                    val toolCalls = llmResponse.toolCalls
                        .distinctBy(c => (c.name, c.argumentsJson))

                    if (toolCalls.nonEmpty)
                    {
                        TraceLogger.info(traceId, "tool_calls_received", Map(
                            "iteration" -> iteration.toString,
                            "count"     -> toolCalls.length.toString,
                            "calls"     -> toolCalls.map(c => s"${c.name}(${c.argumentsJson})").mkString(" | ")
                        ))
                    }

                    if (toolCalls.isEmpty && responseText.trim.isEmpty)
                    {
                        val diagnostic =
                            s"empty_llm_response finishReason=${llmResponse.finishReason.getOrElse("unknown")} " +
                            s"reasoningChars=${llmResponse.reasoningContent.fold(0)(_.length)} " +
                            s"completionTokens=${llmResponse.completionTokens}"
                        TraceLogger.error(traceId, "empty_llm_response", Map(
                            "iteration"        -> iteration.toString,
                            "finishReason"     -> llmResponse.finishReason.getOrElse("unknown"),
                            "reasoningChars"   -> llmResponse.reasoningContent.fold(0)(_.length).toString,
                            "completionTokens" -> llmResponse.completionTokens.toString
                        ))
                        emitEvent(AgentEvent.AgentError(diagnostic))
                        running = false
                    }
                    else if (toolCalls.isEmpty)
                    {
                        // No tool calls and non-empty content → the model is done.
                        val finalText    = responseText.trim
                        val assistantMsg = messageStore.append(session.id, MessageRole.Assistant, finalText)
                        agentTurnStore.insert(AgentTurn(
                            id             = java.util.UUID.randomUUID().toString,
                            sessionId      = session.id,
                            userMsgId      = userMsg.id,
                            assistantMsgId = assistantMsg.id,
                            steps          = turnSteps.toList,
                            traceId        = traceId,
                            timestamp      = Instant.now().toString
                        ))
                        TraceLogger.info(traceId, "agent_loop_complete", Map(
                            "sessionId"        -> session.id,
                            "assistantMsgId"   -> assistantMsg.id,
                            "iterations"       -> iteration.toString,
                            "promptTokens"     -> llmResponse.promptTokens.toString,
                            "completionTokens" -> llmResponse.completionTokens.toString
                        ))
                        val generatedTitle = if (history.isEmpty && isDefaultSessionTitle(session.title))
                            {
                                Some(generateSessionTitle(userMsg.content, finalText))
                            }
                            else
                            {
                                None
                            }
                        emitEvent(AgentEvent.Final(assistantMsg.id, generatedTitle))
                        running = false
                    }
                    else
                    {
                        // --- OBSERVE: dispatch each native tool call and emit tool results ---

                        // The assistant turn carries the model's verbatim tool_calls payload so
                        // providers can correlate each role=tool result with its tool_call_id.
                        val assistantTurnMsg = messageStore.appendMessage(Message(
                            id            = "",
                            sessionId     = session.id,
                            role          = MessageRole.Assistant,
                            content       = responseText,
                            timestamp     = "",
                            toolCallsJson = Some(nativeCallsJson(toolCalls))
                        ))
                        toolResultTurns.append(assistantTurnMsg)

                        val newTurns = scala.collection.mutable.ListBuffer(assistantTurnMsg)

                        var cancelled = false
                        val callIter  = toolCalls.iterator
                        while (callIter.hasNext && !cancelled)
                        {
                            val tc = callIter.next()
                            if (cancelFlag.get())
                            {
                                // Check for cancellation between individual tool dispatches.
                                TraceLogger.info(traceId, "agent_cancelled_in_tool",
                                    Map("iteration" -> iteration.toString))
                                emitEvent(AgentEvent.Cancelled)
                                cancelled = true
                                running   = false
                            }
                            else
                            {
                                val nativeDisplay = virtualShell.describeNative(tc.name, tc.argumentsJson)
                                emitEvent(AgentEvent.ToolCallStart(nativeDisplay, ""))
                                val t0 = System.currentTimeMillis()
                                // Dispatch: function name + JSON args → Command → registry → Presentation.
                                // Malformed names/args come back as an error AgentResponse so the
                                // model can observe and self-correct (never silently dropped).
                                val (displayCmd, response) = virtualShell.executeNative(
                                    tc.name, tc.argumentsJson, sharedCtx)
                                val durMs = System.currentTimeMillis() - t0
                                emitEvent(AgentEvent.ToolCallResult(displayCmd, response.text, durMs))

                                val toolMsg = messageStore.appendMessage(Message(
                                    id         = "",
                                    sessionId  = session.id,
                                    role       = MessageRole.Tool,
                                    content    = response.text,
                                    timestamp  = "",
                                    toolCallId = Some(tc.id)
                                ))
                                toolResultTurns.append(toolMsg)
                                newTurns.append(toolMsg)

                                // Persist the tool run immediately (per-call, not end-of-run)
                                // so partial runs survive cancellation or JVM crash.
                                val toolName = tc.name
                                val isErr    = response.text.contains("\nerror:")
                                runStore.insertRun(ToolRun(
                                    id         = UUID.randomUUID().toString,
                                    sessionId  = session.id,
                                    tool       = toolName,
                                    input      = displayCmd,
                                    output     = response.text,
                                    status     = if isErr then RunStatus.Error else RunStatus.Success,
                                    traceId    = traceId,
                                    durationMs = durMs
                                ))
                                turnSteps.append(AgentTurnStep(
                                    stepType   = agentica.session.StepType.ToolCall,
                                    iteration  = iteration,
                                    content    = "",
                                    command    = displayCmd,
                                    result     = response.text,
                                    durationMs = durMs
                                ))
                                TraceLogger.info(traceId, agentica.session.StepType.ToolCall.value, Map(
                                    "iteration"  -> iteration.toString,
                                    "command"    -> displayCmd,
                                    "durationMs" -> durMs.toString
                                ))
                            }
                        }

                        if (!cancelled)
                        {
                            // Record the thinking step for this iteration before injecting tool results.
                            turnSteps.append(AgentTurnStep(
                                stepType   = agentica.session.StepType.Thinking,
                                iteration  = iteration,
                                content    = responseText,
                                command    = "",
                                result     = "",
                                durationMs = 0L
                            ))
                            lastTurnDelta = newTurns.toList
                            iteration += 1
                            // Loop back to PLAN: the model will read the tool results and decide next action.
                        }
                    }
                }
            }
        }
    }

    /**
     *  Serialises native tool calls into the OpenAI `tool_calls` wire shape, stored on
     *  the assistant message so it can be echoed verbatim to providers on resend.
     */
    private def nativeCallsJson(calls: List[NativeToolCall]): String =
    {
        val arr: ujson.Arr = ujson.Arr.from(calls.map { c =>
            ujson.Obj(
                "id"       -> c.id,
                "type"     -> "function",
                "function" -> ujson.Obj("name" -> c.name, "arguments" -> c.argumentsJson)
            )
        })
        ujson.write(arr)
    }

    /**
     *  Checks whether a title is still the default generated session label.
     *  @param title  Current session title.
     *  @return       True when the title can be replaced by an auto-generated first-turn title.
     */
    private def isDefaultSessionTitle(title: String): Boolean =
    {
        val normalized = Option(title).getOrElse("").trim
        normalized == "New Session" || normalized.startsWith("Session ")
    }

    /**
     *  Builds a concise display title from the first completed user/assistant turn.
     *  @param userText       First user message.
     *  @param assistantText  First assistant answer.
     *  @return               A concise title suitable for the sidebar and chat header.
     */
    private def generateSessionTitle(userText: String, assistantText: String): String =
    {
        firstUsefulAssistantHeading(assistantText)
            .getOrElse(clampTitle(cleanTitleText(userText)))
    }

    /**
     *  Extracts the first useful heading-like label from an assistant answer.
     *  @param text  Assistant response text.
     *  @return      A useful heading if one is present.
     */
    private def firstUsefulAssistantHeading(text: String): Option[String] =
    {
        val generic = Set(
            "summary",
            "summary of findings",
            "final answer",
            "conclusion",
            "potential drivers",
            "potential risks to monitor"
        )
        text
            .split("\\R")
            .iterator
            .flatMap(extractHeadingLabel)
            .map(clampTitle)
            .filterNot(line => generic.contains(line.toLowerCase))
            .find(line => line.nonEmpty)
    }

    /**
     *  Extracts a heading-like label from a raw response line.
     *  @param rawLine  Raw assistant response line.
     *  @return         Heading text if the line contains a useful label.
     */
    private def extractHeadingLabel(rawLine: String): Option[String] =
    {
        val line = Option(rawLine).getOrElse("").trim
        val markdownHeading = "^#{1,3}\\s+(.+)$".r
        val boldLabel       = "^-?\\s*\\*\\*([^*]{4,80})\\*\\*:?.*$".r
        val plainLabel      = "^([A-Z][A-Za-z0-9 &'’/()\\-]{4,80}):.*$".r

        line match
        {
            case markdownHeading(label) => Some(cleanTitleText(label))
            case boldLabel(label)       => Some(cleanTitleText(label))
            case plainLabel(label)      => Some(cleanTitleText(label))
            case _                      => None
        }
    }

    /**
     *  Normalises model text into plain single-line title text.
     *  @param text  Raw text.
     *  @return      Plain single-line text.
     */
    private def cleanTitleText(text: String): String =
    {
        Option(text).getOrElse("")
            .replaceAll("`[^`]*`", "")
            .replaceAll("^[\\s#>*\\-]+", "")
            .replaceAll("\\*\\*", "")
            .replaceAll("\\s+", " ")
            .trim
            .stripSuffix(".")
            .stripSuffix("?")
    }

    /**
     *  Truncates title text to a compact display length.
     *  @param text  Plain title text.
     *  @return      Title text no longer than 64 characters.
     */
    private def clampTitle(text: String): String =
    {
        val cleaned = text.trim
        if (cleaned.length <= 64)
        {
            cleaned
        }
        else
        {
            cleaned.take(61).trim + "..."
        }
    }
}
