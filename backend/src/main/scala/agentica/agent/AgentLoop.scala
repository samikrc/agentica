package agentica.agent

import agentica.agent.AgentEvent
import agentica.llm.{LLMProvider, LLMResponse}
import agentica.observability.{TokenAccounting, TraceLogger}
import agentica.permissions.{GrantDecision, ScopeStore}
import agentica.session.{AgentTurn, AgentTurnStep, AgentTurnStore, MemoryStore, Message, MessageRole, MessageStore, RunStatus, RunStore, Session, SessionStore, ToolRun}
import agentica.settings.{APIMode, AppSettings}
import agentica.shell.{SessionScratchpad, VirtualShell}
import agentica.tools.ExecutionContext
import java.time.Instant
import java.util.UUID
import java.util.concurrent.{SynchronousQueue}
import java.util.concurrent.atomic.AtomicBoolean

/**
 *  Full Phase 2 plan→act→observe agent loop.
 *  Iterates up to `settings.maxIterations` times, dispatching all `run(command="...")` calls
 *  found in each LLM response through [[VirtualShell]], injecting results as `user`-role
 *  `[TOOL RESULT]` turns, until the model emits `<done>` or no further tool calls.
 *  @param llm                     LLM provider for streaming.
 *  @param messageStore             Persistence layer for chat messages.
 *  @param runStore                 Persistence layer for tool runs.
 *  @param tokenAccounting          Records LLM token usage per call.
 *  @param virtualShell             Dispatches tool calls through the command registry.
 *  @param settings                 Application settings (maxIterations, etc.).
 *  @param scopeStore               Permission grant store for sensitive tools.
 *  @param memoryStore              Session-scoped key-value memory store.
 *  @param sessionStore             Persistence layer for session records; used to persist response IDs.
 *  @param permissionLatchFactory   Produces the per-run [[SynchronousQueue]] for permission handoff.
 */
class AgentLoop(
    llm:                    LLMProvider,
    messageStore:           MessageStore,
    runStore:               RunStore,
    agentTurnStore:         AgentTurnStore,
    tokenAccounting:        TokenAccounting,
    virtualShell:           VirtualShell,
    settings:               AppSettings,
    scopeStore:             ScopeStore,
    memoryStore:            MemoryStore,
    sessionStore:           SessionStore,
    permissionLatchFactory: () => SynchronousQueue[GrantDecision]
) extends AgentEngine
{

    /**
     *  Runs the full plan→act→observe loop for one user turn.
     *  Emits exactly one terminal event: [[AgentEvent.Final]], [[AgentEvent.Cancelled]],
     *  or [[AgentEvent.AgentError]].
     *  @param session     Active session metadata.
     *  @param history     Prior message history (assembled by caller).
     *  @param userMsg     New user message appended to the history.
     *  @param traceId     Trace ID for this run.
     *  @param cancelFlag  Polled between iterations and tool calls for external cancellation.
     *  @param emitToken   Called to emit each streamed text token from the LLM.
     *  @param emitEvent   Called to emit structured lifecycle SSE events.
     */
    def run(
        session:    Session,
        history:    List[Message],
        userMsg:    Message,
        traceId:    String,
        cancelFlag: AtomicBoolean,
        emitToken:  String => Unit,
        emitEvent:  AgentEvent => Unit
    ): Unit =
    {
        TraceLogger.info(traceId, "agent_loop_start",
            Map("sessionId" -> session.id, "model" -> session.model))

        // Accumulates assistant + [TOOL RESULT] turn pairs added during the current run.
        // These are appended after the budget-windowed history on every buildContext() call
        // so the model always sees the full in-flight tool exchange, regardless of budget.
        val toolResultTurns = scala.collection.mutable.ListBuffer.empty[Message]
        // Accumulates ordered trajectory steps for AgentTurn persistence.
        val turnSteps       = scala.collection.mutable.ListBuffer.empty[AgentTurnStep]
        // Shared context for all tool calls within this run
        val sharedScratchpad = SessionScratchpad()
        val permLatch          = permissionLatchFactory()
        val sharedCtx = ExecutionContext(
            session         = session,
            traceId         = traceId,
            scopeStore      = scopeStore,
            scratchpad      = sharedScratchpad,
            memoryStore     = memoryStore,
            onEvent         = emitEvent,
            permissionLatch = permLatch
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
                // Hard cap: prevents runaway loops on models that never emit <done>.
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
                emitEvent(AgentEvent.LLMCallStart(iteration, llm.modelName, context.length))
                TraceLogger.info(traceId, "llm_call_start", Map(
                    "iteration" -> iteration.toString,
                    "model"     -> llm.modelName,
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
                //    response — send only the tool result block as a new user message.
                val llmInput: List[Message] = lastResponseId match
                {
                    case None    => context
                    case Some(_) =>
                        if (iteration == 1)
                        {
                            List(userMsg)
                        }
                        else
                        {
                            List(Message(
                                id        = "",
                                sessionId = session.id,
                                role      = MessageRole.User,
                                content   = toolResultTurns.last.content,
                                timestamp = ""
                            ))
                        }
                }

                val llmResponseOpt: Option[LLMResponse] = try
                {
                    val response = settings.apiMode match
                        case APIMode.Responses =>
                            llm.streamResponses(llmInput, wrappedEmitToken, lastResponseId)
                        case APIMode.ChatCompletions =>
                            llm.streamChatCompletions(context, wrappedEmitToken)
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

                    // --- ACT: scan the full response for tool calls ---
                    // Deduplicate by rawCommand to guard against models repeating the same call.
                    val toolCalls = ToolCallParser.parse(responseText, traceId)
                        .distinctBy {
                            case ToolCallResult.Success(tc) => tc.rawCommand
                            case ToolCallResult.Failure(e)  => e.reason
                        }

                    if (toolCalls.nonEmpty)
                    {
                        val calls = toolCalls.collect {
                            case ToolCallResult.Success(tc) => tc.rawCommand
                            case ToolCallResult.Failure(e)  => s"[parse_failed: ${e.reason}]"
                        }.mkString(" | ")
                        TraceLogger.info(traceId, "tool_calls_parsed", Map(
                            "iteration" -> iteration.toString,
                            "count"     -> toolCalls.length.toString,
                            "calls"     -> calls
                        ))
                    }

                    if (toolCalls.isEmpty)
                    {
                        // No run() calls → the model is done. Look for the <done> marker;
                        // accept the response regardless (soft fallback if marker is absent).
                        val hasDone = responseText.contains("<done>")
                        if (!hasDone)
                        {
                            TraceLogger.warn(traceId, "missing_terminator",
                                Map("iteration" -> iteration.toString))
                        }
                        val finalText    = responseText.replace("<done>", "").trim
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
                        // --- OBSERVE: dispatch each tool call and collect results ---

                        // All results for this iteration are concatenated into one [TOOL RESULT] block
                        // and injected as a user-role turn so the model can observe them together.
                        val resultLines = StringBuilder()
                        resultLines.append("[TOOL RESULT]\n")

                        var cancelled = false
                        val callIter  = toolCalls.iterator
                        while (callIter.hasNext && !cancelled)
                        {
                            callIter.next() match
                            {
                                case ToolCallResult.Failure(err) =>
                                    // Inject a structured error so the model can observe and self-correct.
                                    // Silently dropping would corrupt the model's reasoning chain.
                                    // Note: cancelFlag is not checked here — Failure injection is an
                                    // instant in-memory operation with no I/O, so the overhead is negligible.
                                    TraceLogger.warn(traceId, "tool_parse_failure_injected",
                                        Map("iteration" -> iteration.toString, "parseError" -> err.reason))
                                    val errText =
                                        s"$$ ${err.rawSnippet}\n" +
                                        s"error: parse_failed\n" +
                                        s"─ message: ${err.reason}\n" +
                                        s"─ hint: check quoting — command= value must be a double-quoted string\n"
                                    resultLines.append(errText)

                                case ToolCallResult.Success(tc) =>
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
                                        emitEvent(AgentEvent.ToolCallStart(tc.rawCommand, ""))
                                        val t0       = System.currentTimeMillis()
                                        // Dispatch through VirtualShell: Tokenizer → CommandRegistry → Presentation.
                                        val response = virtualShell.execute(tc.rawCommand, sharedCtx)
                                        val durMs    = System.currentTimeMillis() - t0
                                        emitEvent(AgentEvent.ToolCallResult(tc.rawCommand, response.text, durMs))
                                        resultLines.append(response.text)
                                        resultLines.append("\n")
                                        // Persist the tool run immediately (per-call, not end-of-run)
                                        // so partial runs survive cancellation or JVM crash.
                                        val toolName = tc.rawCommand.split(' ').headOption.getOrElse(tc.rawCommand)
                                        val isErr    = response.text.contains("\nerror:")
                                        runStore.insertRun(ToolRun(
                                            id         = UUID.randomUUID().toString,
                                            sessionId  = session.id,
                                            tool       = toolName,
                                            input      = s"""${tc.rawCommand}""",
                                            output     = response.text,
                                            status     = if isErr then RunStatus.Error else RunStatus.Success,
                                            traceId    = traceId,
                                            durationMs = durMs
                                        ))
                                        turnSteps.append(AgentTurnStep(
                                            stepType   = agentica.session.StepType.ToolCall,
                                            iteration  = iteration,
                                            content    = "",
                                            command    = tc.rawCommand,
                                            result     = response.text,
                                            durationMs = durMs
                                        ))
                                        TraceLogger.info(traceId, agentica.session.StepType.ToolCall.value, Map(
                                            "iteration"  -> iteration.toString,
                                            "command"    -> tc.rawCommand,
                                            "durationMs" -> durMs.toString
                                        ))
                                    }
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
                            // Inject the assistant turn and the tool results as a user turn
                            // so the next buildContext() includes them after the budget window.
                            toolResultTurns.append(
                                Message(id = "", sessionId = session.id, role = MessageRole.Assistant,
                                        content = responseText, timestamp = "")
                            )
                            toolResultTurns.append(
                                Message(id = "", sessionId = session.id, role = MessageRole.User,
                                        content = resultLines.toString, timestamp = "")
                            )
                            iteration += 1
                            // Loop back to PLAN: the model will read the results and decide next action.
                        }
                    }
                }
            }
        }
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
            .replace("<done>", "")
            .replaceAll("`[^`]*`", "")
            .replaceAll("run\\([^)]*\\)", "")
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
