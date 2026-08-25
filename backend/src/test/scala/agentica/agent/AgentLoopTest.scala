package agentica.agent

import agentica.llm.LLMResponse
import agentica.observability.TokenAccounting
import agentica.permissions.{GrantDecision, ScopeStore}
import agentica.session.{AgentTurn, AgentTurnStore, MemoryEntry, MemoryStore, Message, MessageRole, MessageStore, RunStatus, RunStore, Session, ToolRun}
import agentica.settings.{APIMode, AppSettings}
import agentica.shell.{CommandRegistry, SessionScratchpad, VirtualShell}
import agentica.testutil.ScriptedLLMProvider
import agentica.tools.ExecutionContext
import org.scalatest.funsuite.AnyFunSuite
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable

/**
 *  Unit tests for [[AgentLoop]].
 *  Uses [[ScriptedLLMProvider]] for deterministic LLM responses and
 *  in-memory stubs for [[MessageStore]], [[TokenAccounting]], and [[VirtualShell]].
 */
class AgentLoopTest extends AnyFunSuite
{

    // ── Stubs ─────────────────────────────────────────────────────────────────

    /**
     *  In-memory MessageStore stub backed by a mutable list.
     *  Does not require a database connection.
     */
    private class StubMessageStore extends MessageStore(() => null)
    {
        val appended: mutable.ListBuffer[Message] =
            mutable.ListBuffer.empty

        override def append(sessionId: String, role: MessageRole, content: String): Message =
        {
            val m = Message(id = s"msg-${appended.size}", sessionId = sessionId,
                            role = role, content = content, timestamp = "")
            appended.append(m)
            m
        }

        override def listForSession(sessionId: String): List[Message] = appended.toList
    }

    /**
     *  In-memory RunStore stub that records every [[ToolRun]] inserted.
     *  Does not require a database connection.
     */
    private class StubRunStore extends RunStore(() => null)
    {
        val runs: mutable.ListBuffer[ToolRun] = mutable.ListBuffer.empty

        override def insertRun(run: ToolRun): Unit =
        {
            runs.append(run)
        }
    }

    /**
     *  SessionStore stub that accepts `updateLastResponseId` calls without touching a database.
     */
    private class StubSessionStore extends agentica.session.SessionStore(() => null)
    {
        val lastResponseIds: mutable.ListBuffer[(String, String)] = mutable.ListBuffer.empty
        override def updateLastResponseId(id: String, responseId: String): Unit =
        {
            lastResponseIds += ((id, responseId))
        }
    }

    /**
     *  TokenAccounting stub that counts calls without touching a database.
     */
    private class StubTokenAccounting extends TokenAccounting(null.asInstanceOf[RunStore])
    {
        var recordCount = 0
        override def record(traceId: String, sessionId: String, llmResponse: LLMResponse): Unit =
        {
            recordCount += 1
        }
    }

    /**
     *  VirtualShell stub that echoes the raw command back without real tool dispatch.
     *  Used to verify the loop wires tool results correctly.
     */
    private class EchoVirtualShell extends VirtualShell(CommandRegistry())
    {
        var dispatchCount = 0
        override def execute(rawCommand: String, ctx: agentica.tools.ExecutionContext)
            : agentica.tools.AgentResponse =
        {
            dispatchCount += 1
            agentica.tools.AgentResponse(
                text       = s"$$ $rawCommand\nok\n─────\necho result",
                durationMs = 0L
            )
        }
    }

    private val session = Session(
        id        = "s1",
        title     = "Test",
        createdAt = "",
        updatedAt = "",
        model     = "test-model",
        rootPath  = Some("/tmp/workspace")
    )

    private val userMsg = Message(id = "u1", sessionId = "s1", role = MessageRole.User,
                                  content = "Do something", timestamp = "")

    private val defaultSettings = AppSettings(maxIterations = 5, contextBudgetTokens = 8000)

    /**
     *  AgentLoop subclass that overrides the Phase 2B buildCtx placeholder
     *  with a stub ExecutionContext so tool-dispatch tests can run without
     *  a real database or permission store.
     */
    private class TestableAgentLoop(
        llm:          agentica.llm.LLMProvider,
        messageStore: MessageStore,
        runStore:     RunStore,
        accounting:   TokenAccounting,
        shell:        VirtualShell,
        settings:     AppSettings
    ) extends AgentLoop(
        llm,
        None,
        messageStore,
        runStore,
        new AgentTurnStore(null) { override def insert(t: AgentTurn): Unit = () },
        accounting,
        shell,
        settings,
        null.asInstanceOf[ScopeStore],
        null.asInstanceOf[MemoryStore],
        null.asInstanceOf[agentica.session.SessionStore]
    )
    {
        // Note: buildCtx override removed since AgentLoop now uses shared context
        // Test dependencies are injected through the constructor parameters
    }

    private def makeLoop(
        llmResponses: List[String],
        shell:        EchoVirtualShell    = new EchoVirtualShell(),
        store:        StubMessageStore    = new StubMessageStore(),
        runStore:     StubRunStore        = new StubRunStore(),
        accounting:   StubTokenAccounting = new StubTokenAccounting(),
        settings:     AppSettings         = defaultSettings
    ): (AgentLoop, StubMessageStore, StubTokenAccounting, EchoVirtualShell, StubRunStore) =
    {
        val llm  = ScriptedLLMProvider(llmResponses)
        val loop = new TestableAgentLoop(llm, store, runStore, accounting, shell, settings)
        (loop, store, accounting, shell, runStore)
    }

    // ── Final answer: <done> marker ───────────────────────────────────────────

    test("emits Final event when model responds with <done>") {
        val (loop, store, _, _, _) = makeLoop(List("Here is my answer.\n<done>"))
        var finalId: Option[String] = None

        loop.run(session, Nil, userMsg, "t1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = {
                case AgentEvent.Final(id, _) => finalId = Some(id)
                case _                   => ()
            }
        )

        assert(finalId.isDefined, "Final event must be emitted")
        val saved = store.appended.find(_.role == MessageRole.Assistant)
        assert(saved.isDefined)
        assert(!saved.get.content.contains("<done>"), "<done> must be stripped from persisted text")
        assert(saved.get.content.trim == "Here is my answer.")
    }

    test("<done> adjacent to answer text (no newline) is still stripped") {
        // System prompt says <done> on its own line, but models may omit the newline.
        // replace+trim must still strip it cleanly.
        val store = new StubMessageStore()
        val (loop, _, _, _, _) = makeLoop(List("Here is my answer.<done>"), store = store)

        loop.run(session, Nil, userMsg, "t1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = _ => ()
        )

        val saved = store.appended.find(_.role == MessageRole.Assistant)
        assert(saved.isDefined)
        assert(saved.get.content == "Here is my answer.")
    }

    test("<done> appearing multiple times is fully stripped") {
        val store = new StubMessageStore()
        val (loop, _, _, _, _) = makeLoop(List("Answer.\n<done>\n<done>"), store = store)

        loop.run(session, Nil, userMsg, "t1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = _ => ()
        )

        val saved = store.appended.find(_.role == MessageRole.Assistant)
        assert(saved.isDefined)
        assert(!saved.get.content.contains("<done>"))
    }

    test("soft fallback: emits Final even when <done> is absent") {
        val (loop, _, _, _, _) = makeLoop(List("Plain answer with no marker."))
        var finalEmitted = false

        loop.run(session, Nil, userMsg, "t1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = {
                case AgentEvent.Final(_, _) => finalEmitted = true
                case _                      => ()
            }
        )

        assert(finalEmitted, "Final must be emitted even without <done>")
    }

    test("tokens are streamed to emitToken during LLM call") {
        val (loop, _, _, _, _) = makeLoop(List("token-by-token\n<done>"))
        val received = mutable.ListBuffer.empty[String]

        loop.run(session, Nil, userMsg, "t1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = tok => received.append(tok),
            emitEvent = _ => ()
        )

        assert(received.nonEmpty, "emitToken must have been called at least once")
        assert(received.mkString == "token-by-token\n<done>")
    }

    // ── Token accounting ──────────────────────────────────────────────────────

    test("token accounting is recorded once per LLM call") {
        val (loop, _, accounting, _, _) = makeLoop(List(
            """run(command="files.stat path=x")""",
            "Done.\n<done>"
        ))

        loop.run(session, Nil, userMsg, "t1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = _ => ()
        )

        assert(accounting.recordCount == 2, "one record per LLM call (2 iterations)")
    }

    // ── Tool dispatch ─────────────────────────────────────────────────────────

    test("dispatches tool call then uses final answer on next iteration") {
        val shell = new EchoVirtualShell()
        val (loop, _, _, _, _) = makeLoop(
            llmResponses = List(
                """I'll check the file. run(command="files.stat path=foo.txt")""",
                "The file exists.\n<done>"
            ),
            shell = shell
        )
        var finalEmitted    = false
        var toolStartCount  = 0
        var toolResultCount = 0

        loop.run(session, Nil, userMsg, "t1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = {
                case AgentEvent.Final(_, _)             => finalEmitted    = true
                case AgentEvent.ToolCallStart(_, _)     => toolStartCount  += 1
                case AgentEvent.ToolCallResult(_, _, _) => toolResultCount += 1
                case _                                  => ()
            }
        )

        assert(shell.dispatchCount == 1, "exactly one tool call dispatched")
        assert(toolStartCount      == 1, "ToolCallStart emitted once")
        assert(toolResultCount     == 1, "ToolCallResult emitted once")
        assert(finalEmitted,             "Final emitted after second LLM call")
    }

    test("dispatches multiple tool calls in a single LLM response") {
        val shell = new EchoVirtualShell()
        val (loop, _, _, _, _) = makeLoop(
            llmResponses = List(
                """run(command="files.list path=src") run(command="files.stat path=README.md")""",
                "Done.\n<done>"
            ),
            shell = shell
        )

        loop.run(session, Nil, userMsg, "t1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = _ => ()
        )

        assert(shell.dispatchCount == 2, "both tool calls in the response must be dispatched")
    }

    // ── Cancellation ─────────────────────────────────────────────────────────

    test("respects cancelFlag set before first iteration") {
        val (loop, _, _, shell, _) = makeLoop(List("Should never be called.\n<done>"))
        var cancelledEmitted = false

        loop.run(session, Nil, userMsg, "t1", new AtomicBoolean(true), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = {
                case AgentEvent.Cancelled => cancelledEmitted = true
                case _                   => ()
            }
        )

        assert(cancelledEmitted,       "Cancelled event must be emitted")
        assert(shell.dispatchCount == 0, "no tool calls must be made when already cancelled")
    }

    // ── Max iterations cap ────────────────────────────────────────────────────

    test("stops with AgentError after maxIterations with infinite tool calls") {
        // Every LLM response contains a tool call — loop should hit the cap.
        val infiniteToolCalls = List.fill(10)("""run(command="files.stat path=x")""")
        val settings          = AppSettings(maxIterations = 3, contextBudgetTokens = 8000)
        val (loop, _, _, shell, _) = makeLoop(infiniteToolCalls, settings = settings)

        var errorEmitted = false
        loop.run(session, Nil, userMsg, "t1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = {
                case AgentEvent.AgentError("max_iterations_exceeded") => errorEmitted = true
                case _                                                => ()
            }
        )

        assert(errorEmitted,               "AgentError(max_iterations_exceeded) must be emitted")
        assert(shell.dispatchCount == 3,   "tool calls dispatched for exactly maxIterations iterations")
    }

    // ── Parse failure injection ───────────────────────────────────────────────

    test("malformed run() call injects parse_failed error into tool result turn") {
        // The LLM emits a bad call (no opening quote) followed by a final answer.
        // The loop must NOT silently skip the failure — it must inject a parse_failed
        // error into the [TOOL RESULT] block so the model can observe and self-correct.
        val store     = new StubMessageStore()
        val (loop, _, _, _, _) = makeLoop(
            llmResponses = List(
                "run(command=NOSTRING)",   // malformed — no opening quote
                "Self-corrected.\n<done>"
            ),
            store = store
        )

        loop.run(session, Nil, userMsg, "t1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = _ => ()
        )

        // The tool result turn injected into context (ephemeral, not persisted) should
        // contain "parse_failed". We verify via the final assistant message that the
        // loop completed two iterations (second LLM call produced the final answer).
        val assistantMsgs = store.appended.filter(_.role == MessageRole.Assistant)
        assert(assistantMsgs.length == 1, "exactly one final assistant message persisted")
        assert(assistantMsgs.head.content == "Self-corrected.")
    }

    // ── Additional AgentLoop edge cases ──────────────────────────────────────

    test("empty LLM response triggers soft-fallback Final with empty content") {
        // Empty string: no run() calls, no <done>. Loop should soft-fallback to Final
        // and persist an empty (or blank) assistant message rather than hanging.
        val store = new StubMessageStore()
        val (loop, _, _, _, _) = makeLoop(List(""), store = store)
        var finalEmitted = false

        loop.run(session, Nil, userMsg, "t1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = {
                case AgentEvent.Final(_, _) => finalEmitted = true
                case _                      => ()
            }
        )

        assert(finalEmitted, "Final must be emitted even for empty response")
        val saved = store.appended.find(_.role == MessageRole.Assistant)
        assert(saved.isDefined, "Empty assistant message must still be persisted")
    }

    test("<done> inside tool output does not terminate the loop early") {
        // EchoVirtualShell echoes the command back; we extend it here to return <done>
        // in the output. The loop must NOT treat this as a final-answer signal —
        // <done> is only checked when toolCalls.isEmpty (no run() calls in the response).
        val doneInOutputShell = new EchoVirtualShell()
        {
            override def execute(rawCommand: String, ctx: agentica.tools.ExecutionContext)
                : agentica.tools.AgentResponse =
                agentica.tools.AgentResponse(
                    text       = s"$$ $rawCommand\nok\n─────\nresult contains <done> marker",
                    durationMs = 0L
                )
        }
        val (loop, _, _, _, _) = makeLoop(
            llmResponses = List(
                """run(command="files.stat path=x")""",
                "All done.\n<done>"
            ),
            shell = doneInOutputShell
        )
        var finalEmitted    = false
        var iterationCount  = 0

        loop.run(session, Nil, userMsg, "t1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = {
                case AgentEvent.Final(_, _)          => finalEmitted   = true
                case AgentEvent.IterationBoundary(_) => iterationCount += 1
                case _                               => ()
            }
        )

        assert(iterationCount == 2, "loop must complete both iterations, not short-circuit on <done> in tool output")
        assert(finalEmitted,        "Final must be emitted after the second LLM response")
    }

    test("LLM error on second iteration emits AgentError after first tool dispatch") {
        // First iteration succeeds and dispatches a tool.
        // Second LLM call throws — loop must emit AgentError, not hang or throw.
        val shell = new EchoVirtualShell()
        val store = new StubMessageStore()
        val llm   = new agentica.llm.LLMProvider
        {
            private var calls = 0
            val modelName = "error-on-2nd"
            def streamChatCompletions(messages: List[agentica.session.Message], onToken: String => Unit): agentica.llm.LLMResponse =
                throw UnsupportedOperationException()
            override def streamResponses(input: List[agentica.session.Message], onToken: String => Unit, previousResponseId: Option[String] = None): agentica.llm.LLMResponse =
            {
                calls += 1
                if (calls == 1)
                {
                    val r = """run(command="files.stat path=x")"""
                    r.foreach(c => onToken(c.toString))
                    agentica.llm.LLMResponse(modelName, 0, 0, 0)
                }
                else
                {
                    throw RuntimeException("simulated LLM failure on iteration 2")
                }
            }
        }
        val loop = new TestableAgentLoop(llm, store, new StubRunStore(), new StubTokenAccounting(), shell, responsesSettings)

        var errorEmitted = false
        loop.run(session, Nil, userMsg, "t1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = {
                case AgentEvent.AgentError(_) => errorEmitted = true
                case _                        => ()
            }
        )

        assert(shell.dispatchCount == 1, "tool dispatched on first iteration before error")
        assert(errorEmitted,             "AgentError emitted when second LLM call throws")
    }

    test("all parser failures still inject observations and continue") {
        // All calls in the first response are malformed → no Success dispatches.
        // The loop still injects a [TOOL RESULT] with parse errors and calls the LLM again.
        // The second response provides a final answer — verifying the loop recovered.
        val store = new StubMessageStore()
        val (loop, _, _, _, _) = makeLoop(
            llmResponses = List(
                "run(command=BAD1) run(command=BAD2)",  // all failures
                "Recovered.\n<done>"
            ),
            store = store
        )
        var finalEmitted = false

        loop.run(session, Nil, userMsg, "t1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = {
                case AgentEvent.Final(_, _) => finalEmitted = true
                case _                      => ()
            }
        )

        assert(finalEmitted, "loop must recover from all-failures iteration and reach Final")
        val saved = store.appended.find(_.role == MessageRole.Assistant)
        assert(saved.exists(_.content == "Recovered."))
    }

    // ── IterationBoundary events ──────────────────────────────────────────────

    test("emits IterationBoundary(1) on single-iteration run") {
        val (loop, _, _, _, _) = makeLoop(List("Answer.\n<done>"))
        val boundaries = mutable.ListBuffer.empty[Int]

        loop.run(session, Nil, userMsg, "t1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = {
                case AgentEvent.IterationBoundary(i) => boundaries.append(i)
                case _                               => ()
            }
        )

        assert(boundaries.toList == List(1))
    }

    test("emits IterationBoundary(1) and IterationBoundary(2) on two-iteration run") {
        val (loop, _, _, _, _) = makeLoop(List(
            """run(command="files.stat path=x")""",
            "Done.\n<done>"
        ))
        val boundaries = mutable.ListBuffer.empty[Int]

        loop.run(session, Nil, userMsg, "t1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = {
                case AgentEvent.IterationBoundary(i) => boundaries.append(i)
                case _                               => ()
            }
        )

        assert(boundaries.toList == List(1, 2))
    }

    // ── RunStore persistence ──────────────────────────────────────────────────

    test("each successful tool call is persisted to RunStore immediately") {
        val rs = new StubRunStore()
        val (loop, _, _, _, _) = makeLoop(
            llmResponses = List(
                """run(command="files.stat path=foo.txt")""",
                "Done.\n<done>"
            ),
            runStore = rs
        )

        loop.run(session, Nil, userMsg, "t1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = _ => ()
        )

        assert(rs.runs.size == 1, "exactly one ToolRun record persisted")
        val run = rs.runs.head
        assert(run.sessionId == session.id)
        assert(run.tool      == "files.stat")
        assert(run.traceId   == "t1")
        assert(run.status    == RunStatus.Success)
        assert(run.input.contains("files.stat path=foo.txt"))
    }

    test("multiple tool calls in one response each produce a separate RunStore record") {
        val rs = new StubRunStore()
        val (loop, _, _, _, _) = makeLoop(
            llmResponses = List(
                """run(command="files.list path=src") run(command="files.stat path=README.md")""",
                "Done.\n<done>"
            ),
            runStore = rs
        )

        loop.run(session, Nil, userMsg, "t1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = _ => ()
        )

        assert(rs.runs.size == 2, "one record per dispatched tool call")
        assert(rs.runs(0).tool == "files.list")
        assert(rs.runs(1).tool == "files.stat")
    }

    test("tool calls across two iterations produce cumulative RunStore records") {
        val rs = new StubRunStore()
        val (loop, _, _, _, _) = makeLoop(
            llmResponses = List(
                """run(command="files.stat path=a.txt")""",
                """run(command="files.stat path=b.txt")""",
                "Done.\n<done>"
            ),
            runStore = rs
        )

        loop.run(session, Nil, userMsg, "t1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = _ => ()
        )

        assert(rs.runs.size == 2, "one record per call across all iterations")
    }

    test("parse failures do not produce RunStore records") {
        // Malformed run() calls are never dispatched so must not appear in RunStore.
        val rs = new StubRunStore()
        val (loop, _, _, _, _) = makeLoop(
            llmResponses = List(
                "run(command=NOSTRING)",
                "Recovered.\n<done>"
            ),
            runStore = rs
        )

        loop.run(session, Nil, userMsg, "t1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = _ => ()
        )

        assert(rs.runs.isEmpty, "parse failures must not produce ToolRun records")
    }

    // ── Duplicate tool call deduplication ────────────────────────────────────

    test("duplicate successful tool calls in one response are dispatched only once - v1") {
        // The model emits the same run() call twice in one response.
        // Only one dispatch must occur and only one RunStore record must be created.
        val rs    = new StubRunStore()
        val shell = new EchoVirtualShell()
        val (loop, _, _, _, _) = makeLoop(
            llmResponses = List(
                """run(command="files.stat path=x") run(command="files.stat path=x")""",
                "Done.\n<done>"
            ),
            runStore = rs,
            shell    = shell
        )

        loop.run(session, Nil, userMsg, "t1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = _ => ()
        )

        assert(shell.dispatchCount == 1, "duplicate tool call must be dispatched only once")
        assert(rs.runs.size == 1,        "duplicate tool call must produce only one RunStore record")
    }

    test("duplicate successful tool calls in one response are dispatched only once - v2") {
        // The model emits the same run() call twice in one response.
        // Only one dispatch must occur and only one RunStore record must be created.
        val rs    = new StubRunStore()
        val shell = new EchoVirtualShell()
        val (loop, _, _, _, _) = makeLoop(
            llmResponses = List(
                """<thinking>Let me think</thinking> run(command="files.stat path=a") <thinking>Let me think again</thinking> run(command="files.stat path=a")""",
                "Done.\n<done>"
            ),
            runStore = rs,
            shell    = shell
        )

        loop.run(session, Nil, userMsg, "t1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = _ => ()
        )

        assert(shell.dispatchCount == 1, "duplicate tool call must be dispatched only once")
        assert(rs.runs.size == 1,        "duplicate tool call must produce only one RunStore record")
    }

    test("distinct tool calls in one response are all dispatched") {
        // Two different commands must both be dispatched even though deduplication is active.
        val rs    = new StubRunStore()
        val shell = new EchoVirtualShell()
        val (loop, _, _, _, _) = makeLoop(
            llmResponses = List(
                """run(command="files.stat path=a") run(command="files.stat path=b")""",
                "Done.\n<done>"
            ),
            runStore = rs,
            shell    = shell
        )

        loop.run(session, Nil, userMsg, "t1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = _ => ()
        )

        assert(shell.dispatchCount == 2, "two distinct tool calls must each be dispatched")
        assert(rs.runs.size == 2,        "two distinct tool calls must each produce a RunStore record")
    }

    test("duplicate malformed tool calls in one response inject only one parse error") {
        // The model emits the same malformed call twice.
        // Only one parse_failed error must be injected into the tool result block,
        // which the loop injects as context for the next LLM call.
        // We verify via the second call's input: parse_failed appears exactly once.
        val provider = CapturingResponsesProvider(
            responses = List("run(command=BAD) run(command=BAD)", "Recovered.\n<done>")
        )
        val loop = makeLoopWithCapturing(provider)

        loop.run(session, Nil, userMsg, "t1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = _ => ()
        )

        assert(provider.capturedInputs.size == 2, "two LLM calls expected")
        val secondMsgs   = provider.capturedInputs(1)
        val secondContent = secondMsgs.map(_.content).mkString
        val occurrences  = secondContent.split("parse_failed", -1).length - 1
        assert(occurrences == 1, s"parse_failed must appear exactly once in second call input, found $occurrences")
    }

    test("same tool call repeated across multiple iterations is dispatched each time") {
        // Deduplication is per-iteration only — the same command in a later iteration is valid.
        val rs = new StubRunStore()
        val (loop, _, _, _, _) = makeLoop(
            llmResponses = List(
                """run(command="files.stat path=x")""",
                """run(command="files.stat path=x")""",
                "Done.\n<done>"
            ),
            runStore = rs
        )

        loop.run(session, Nil, userMsg, "t1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = _ => ()
        )

        assert(rs.runs.size == 2, "same command in different iterations must each be dispatched")
    }

    // ── Scratchpad lifecycle (Bug #2) ─────────────────────────────────────────

    // Stubs needed for RealBuildCtxLoop (must allow all operations without a DB).
    private val allGrantsScopeStore: ScopeStore = new ScopeStore
    {
        def hasGrant(s: String, t: String, p: String): Boolean = true
        def addGrant(s: String, t: String, d: GrantDecision.Granted): Unit = ()
        def consumeOnce(s: String, t: String, p: String): Unit = ()
        def deleteForSession(s: String): Unit = ()
    }

    private val noopMemoryStore: MemoryStore = new MemoryStore
    {
        def init(): Unit = ()
        def set(s: String, k: String, v: String): MemoryEntry = MemoryEntry(s, k, v, "")
        def get(s: String, k: String): Option[MemoryEntry] = None
        def list(s: String): List[MemoryEntry] = Nil
        def deleteForSession(s: String): Unit = ()
    }

    /**
     *  AgentLoop subclass that does NOT override [[AgentLoop.buildCtx]].
     *  Used to exercise the real implementation — which is where Bug #2 lives.
     *  Contrast with [[TestableAgentLoop]] which overrides buildCtx with a stub.
     */
    private class RealBuildCtxLoop(
        llm:      agentica.llm.LLMProvider,
        msgStore: MessageStore,
        rsStore:  RunStore,
        acct:     TokenAccounting,
        shell:    VirtualShell,
        settings: AppSettings
    ) extends AgentLoop(
        llm,
        None,
        msgStore,
        rsStore,
        new AgentTurnStore(null) { override def insert(t: AgentTurn): Unit = () },
        acct,
        shell,
        settings,
        allGrantsScopeStore,
        noopMemoryStore,
        null.asInstanceOf[agentica.session.SessionStore]
    )
    // Intentionally no buildCtx override — exercises the real implementation.

    test("AgentLoop shares same SessionScratchpad across tool calls") {
        // Tests that all tool calls within a single run share the same scratchpad instance
        
        val capturedScratchpads = mutable.ListBuffer.empty[SessionScratchpad]

        val capturingShell = new EchoVirtualShell()
        {
            override def execute(rawCommand: String, ctx: ExecutionContext): agentica.tools.AgentResponse =
            {
                capturedScratchpads.append(ctx.scratchpad)
                super.execute(rawCommand, ctx)
            }
        }

        val llm  = ScriptedLLMProvider(List(
            """run(command="files.stat path=a.txt")""",
            """run(command="files.stat path=b.txt")""",
            "Done.\n<done>"
        ))
        val loop = new RealBuildCtxLoop(
            llm, new StubMessageStore(), new StubRunStore(),
            new StubTokenAccounting(), capturingShell, defaultSettings
        )

        loop.run(session, Nil, userMsg, "t1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = _ => ()
        )

        assert(capturedScratchpads.size == 2,
            "two tool dispatches must each capture a scratchpad reference")

        // Verify both tool calls received the same scratchpad instance (reference equality)
        assert(
            capturedScratchpads(0) eq capturedScratchpads(1),
            "all tool calls in one run must receive the same SessionScratchpad instance"
        )
    }

    // ── AgentTurn trajectory persistence ─────────────────────────────────────

    /**
     *  AgentTurnStore stub that records every inserted [[AgentTurn]] in memory.
     */
    private class StubAgentTurnStore extends AgentTurnStore(null)
    {
        val inserted: mutable.ListBuffer[AgentTurn] = mutable.ListBuffer.empty

        override def insert(turn: AgentTurn): Unit =
        {
            inserted.append(turn)
        }
    }

    /**
     *  Like [[makeLoop]] but returns the [[StubAgentTurnStore]] alongside the loop
     *  so tests can inspect the persisted [[AgentTurn]].
     */
    private def makeLoopWithTurns(
        llmResponses: List[String],
        shell:        EchoVirtualShell    = new EchoVirtualShell(),
        store:        StubMessageStore    = new StubMessageStore(),
        runStore:     StubRunStore        = new StubRunStore(),
        accounting:   StubTokenAccounting = new StubTokenAccounting(),
        settings:     AppSettings         = defaultSettings
    ): (AgentLoop, StubMessageStore, StubAgentTurnStore, EchoVirtualShell) =
    {
        val llm       = ScriptedLLMProvider(llmResponses)
        val turnStore = new StubAgentTurnStore()
        val loop = new AgentLoop(
            llm,
            None,
            store,
            runStore,
            turnStore,
            accounting,
            shell,
            settings,
            null.asInstanceOf[ScopeStore],
            null.asInstanceOf[MemoryStore],
            null.asInstanceOf[agentica.session.SessionStore]
        ) {
            // Note: buildCtx override removed since AgentLoop now uses shared context
            // Test dependencies are injected through the constructor parameters
        }
        (loop, store, turnStore, shell)
    }

    test("single-shot response: AgentTurn is persisted with empty steps") {
        val (loop, msgStore, turnStore, _) = makeLoopWithTurns(List("Direct answer.\n<done>"))

        loop.run(session, Nil, userMsg, "trace-1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = _ => ()
        )

        assert(turnStore.inserted.size == 1, "exactly one AgentTurn must be persisted")
        val turn = turnStore.inserted.head
        assert(turn.steps.isEmpty, "no tool calls means no steps")
        assert(turn.sessionId == session.id)
        assert(turn.userMsgId == userMsg.id)
        val asstMsg = msgStore.appended.find(_.role == MessageRole.Assistant)
        assert(asstMsg.isDefined)
        assert(turn.assistantMsgId == asstMsg.get.id,
            "assistantMsgId must match the persisted assistant message id")
    }

    test("one tool call: AgentTurn contains one thinking step and one tool_call step") {
        val (loop, _, turnStore, _) = makeLoopWithTurns(
            llmResponses = List(
                """Let me check.\nrun(command="files.stat path=a.txt")""",
                "Done looking.\n<done>"
            )
        )

        loop.run(session, Nil, userMsg, "trace-2", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = _ => ()
        )

        assert(turnStore.inserted.size == 1)
        val steps = turnStore.inserted.head.steps
        // Iteration 1: thinking + tool_call
        val thinkingSteps = steps.filter(_.stepType == agentica.session.StepType.Thinking)
        val toolSteps     = steps.filter(_.stepType == agentica.session.StepType.ToolCall)
        assert(thinkingSteps.nonEmpty, "thinking step must be recorded for iteration 1")
        assert(toolSteps.size == 1, "exactly one tool_call step expected")
        assert(toolSteps.head.iteration == 1)
        assert(toolSteps.head.command.contains("files.stat"))
        assert(toolSteps.head.durationMs >= 0L)
    }

    test("two tool calls in one iteration: both appear as tool_call steps") {
        val (loop, _, turnStore, _) = makeLoopWithTurns(
            llmResponses = List(
                """run(command="files.stat path=a.txt") run(command="files.stat path=b.txt")""",
                "All done.\n<done>"
            )
        )

        loop.run(session, Nil, userMsg, "trace-3", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = _ => ()
        )

        val steps    = turnStore.inserted.head.steps
        val toolSteps = steps.filter(_.stepType == agentica.session.StepType.ToolCall)
        assert(toolSteps.size == 2, "two tool calls must produce two tool_call steps")
        assert(toolSteps.forall(_.iteration == 1))
        val commands = toolSteps.map(_.command)
        assert(commands.exists(_.contains("a.txt")))
        assert(commands.exists(_.contains("b.txt")))
    }

    test("multi-iteration run: each iteration gets its own thinking step") {
        val (loop, _, turnStore, _) = makeLoopWithTurns(
            llmResponses = List(
                """Iter 1 thinking.\nrun(command="files.stat path=a.txt")""",
                """Iter 2 thinking.\nrun(command="files.stat path=b.txt")""",
                "Final answer.\n<done>"
            )
        )

        loop.run(session, Nil, userMsg, "trace-4", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = _ => ()
        )

        val steps = turnStore.inserted.head.steps
        val thinkingByIter = steps.filter(_.stepType == agentica.session.StepType.Thinking).groupBy(_.iteration)
        val toolByIter     = steps.filter(_.stepType == agentica.session.StepType.ToolCall).groupBy(_.iteration)
        assert(thinkingByIter.contains(1), "thinking step for iteration 1")
        assert(thinkingByIter.contains(2), "thinking step for iteration 2")
        assert(toolByIter.getOrElse(1, Nil).size == 1, "one tool call in iteration 1")
        assert(toolByIter.getOrElse(2, Nil).size == 1, "one tool call in iteration 2")
        assert(thinkingByIter(1).head.content.contains("Iter 1"))
        assert(thinkingByIter(2).head.content.contains("Iter 2"))
    }

    test("tool_call step result matches what the shell returned") {
        val fixedResult = "$ files.stat path=a.txt\nok\n─────\nsize: 42"
        val fixedShell = new EchoVirtualShell()
        {
            override def execute(rawCommand: String, ctx: agentica.tools.ExecutionContext)
                : agentica.tools.AgentResponse =
            {
                agentica.tools.AgentResponse(text = fixedResult, durationMs = 55L)
            }
        }
        val (loop, _, turnStore, _) = makeLoopWithTurns(
            llmResponses = List(
                """run(command="files.stat path=a.txt")""",
                "Done.\n<done>"
            ),
            shell = fixedShell
        )

        loop.run(session, Nil, userMsg, "trace-5", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = _ => ()
        )

        val toolStep = turnStore.inserted.head.steps.find(_.stepType == agentica.session.StepType.ToolCall).get
        assert(toolStep.result == fixedResult,
            "tool_call step result must exactly match the shell response text")
        assert(toolStep.durationMs >= 0L,
            "tool_call step durationMs must be a non-negative wall-clock measurement")
    }

    test("cancelled run does not persist an AgentTurn") {
        val cancelFlag = new AtomicBoolean(true)  // already cancelled before run starts
        val (loop, _, turnStore, _) = makeLoopWithTurns(
            llmResponses = List("This won't reach Final.\n<done>")
        )

        loop.run(session, Nil, userMsg, "trace-6", cancelFlag, new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = _ => ()
        )

        assert(turnStore.inserted.isEmpty,
            "a cancelled run must not insert an AgentTurn")
    }

    test("tool calls before cancellation are persisted; calls after are not") {
        // cancelFlag is set to true after first tool dispatch via a counting shell.
        val cancelFlag = new AtomicBoolean(false)
        val countingShell = new EchoVirtualShell()
        {
            override def execute(rawCommand: String, ctx: agentica.tools.ExecutionContext)
                : agentica.tools.AgentResponse =
            {
                val result = super.execute(rawCommand, ctx)
                // Cancel after the first tool runs so the second is never dispatched.
                if (dispatchCount == 1) cancelFlag.set(true)
                result
            }
        }
        val rs = new StubRunStore()
        val (loop, _, _, _, _) = makeLoop(
            llmResponses = List(
                """run(command="files.stat path=a.txt") run(command="files.stat path=b.txt")"""
            ),
            shell    = countingShell,
            runStore = rs
        )

        loop.run(session, Nil, userMsg, "t1", cancelFlag, new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = _ => ()
        )

        assert(rs.runs.size == 1, "only the completed tool call before cancel is persisted")
        assert(rs.runs.head.tool == "files.stat")
    }

    // ── Session title generation ───────────────────────────────────────────────

    test("isDefaultSessionTitle returns true for default-named sessions") {
        // This test uses reflection to access the private helper function.
        // In production code, consider making the function package-private or public for testing.
        val loop = new AgentLoop(
            ScriptedLLMProvider(List("")),
            None,
            new StubMessageStore(),
            new StubRunStore(),
            new StubAgentTurnStore(),
            new StubTokenAccounting(),
            new EchoVirtualShell(),
            defaultSettings,
            null.asInstanceOf[ScopeStore],
            null.asInstanceOf[MemoryStore],
            null.asInstanceOf[agentica.session.SessionStore]
        )

        // Use reflection to access the private method
        val isDefaultMethod = loop.getClass.getDeclaredMethod("isDefaultSessionTitle", classOf[String])
        isDefaultMethod.setAccessible(true)

        assert(isDefaultMethod.invoke(loop, "New Session") == true)
        assert(isDefaultMethod.invoke(loop, "Session 2024-01-01 12:00:00") == true)
        assert(isDefaultMethod.invoke(loop, "Session 123") == true)
        assert(isDefaultMethod.invoke(loop, "Custom Title") == false)
        assert(isDefaultMethod.invoke(loop, "Session") == false) // No timestamp
        assert(isDefaultMethod.invoke(loop, "") == false)
        assert(isDefaultMethod.invoke(loop, "  Session 2024-01-01 12:00:00  ") == true) // Trimmed
    }

    test("generateSessionTitle extracts meaningful title from first turn") {
        val loop = new AgentLoop(
            ScriptedLLMProvider(List("")),
            None,
            new StubMessageStore(),
            new StubRunStore(),
            new StubAgentTurnStore(),
            new StubTokenAccounting(),
            new EchoVirtualShell(),
            defaultSettings,
            null.asInstanceOf[ScopeStore],
            null.asInstanceOf[MemoryStore],
            null.asInstanceOf[agentica.session.SessionStore]
        )

        val generateMethod = loop.getClass.getDeclaredMethod("generateSessionTitle", classOf[String], classOf[String])
        generateMethod.setAccessible(true)

        // Test with assistant heading
        val result1 = generateMethod.invoke(loop, "Help me with files", "## File Summary\nHere's the summary.")
        assert(result1.toString.contains("File Summary"))

        // Test without heading (falls back to user message)
        val result2 = generateMethod.invoke(loop, "Analyze the data", "Here is the analysis.")
        assert(result2.toString.contains("Analyze"))

        // Test with markdown heading in user message
        val result3 = generateMethod.invoke(loop, "# Create a report", "Done.")
        assert(result3.toString.contains("Create"))
    }

    test("AgentEvent.Final includes sessionTitle on first turn with default title") {
        val store = new StubMessageStore()
        val sessionWithDefaultTitle = Session(
            id        = "s1",
            title     = "Session 2024-01-01 12:00:00",
            createdAt = "",
            updatedAt = "",
            model     = "test-model",
            rootPath  = Some("/tmp/workspace")
        )

        var capturedTitle: Option[String] = None
        val (loop, _, _, _, _) = makeLoop(
            llmResponses = List("Final answer here"),
            store        = store
        )

        loop.run(
            sessionWithDefaultTitle,
            Nil, // Empty history = first turn
            userMsg,
            "t1",
            new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = {
                case AgentEvent.Final(_, sessionTitle) =>
                    capturedTitle = sessionTitle
                case _ => ()
            }
        )

        assert(capturedTitle.isDefined, "sessionTitle should be generated on first turn")
        assert(capturedTitle.get.nonEmpty, "generated title should not be empty")
    }

    test("AgentEvent.Final does NOT include sessionTitle on subsequent turns") {
        val store = new StubMessageStore()
        val sessionWithDefaultTitle = Session(
            id        = "s1",
            title     = "Session 2024-01-01 12:00:00",
            createdAt = "",
            updatedAt = "",
            model     = "test-model",
            rootPath  = Some("/tmp/workspace")
        )

        // Construct a non-empty history to simulate subsequent turn
        val priorUser = store.append("s1", MessageRole.User, "Previous question")
        val priorAsst = store.append("s1", MessageRole.Assistant, "Previous answer")
        val history = List(priorUser, priorAsst)

        var capturedTitle: Option[String] = None
        val (loop, _, _, _, _) = makeLoop(
            llmResponses = List("Another answer"),
            store        = store
        )

        loop.run(
            sessionWithDefaultTitle,
            history, // Non-empty history = subsequent turn
            userMsg,
            "t2",
            new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = {
                case AgentEvent.Final(_, sessionTitle) =>
                    capturedTitle = sessionTitle
                case _ => ()
            }
        )

        assert(capturedTitle.isEmpty, "sessionTitle should NOT be generated on subsequent turns")
    }

    test("AgentEvent.Final does NOT include sessionTitle when title is already custom") {
        val store = new StubMessageStore()
        val sessionWithCustomTitle = Session(
            id        = "s1",
            title     = "My Custom Session",
            createdAt = "",
            updatedAt = "",
            model     = "test-model",
            rootPath  = Some("/tmp/workspace")
        )

        var capturedTitle: Option[String] = None
        val (loop, _, _, _, _) = makeLoop(
            llmResponses = List("Final answer"),
            store        = store
        )

        loop.run(
            sessionWithCustomTitle,
            Nil, // First turn
            userMsg,
            "t1",
            new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = {
                case AgentEvent.Final(_, sessionTitle) =>
                    capturedTitle = sessionTitle
                case _ => ()
            }
        )

        assert(capturedTitle.isEmpty, "sessionTitle should NOT be generated when title is custom")
    }

    // ── streamResponses input serialisation ───────────────────────────────────

    /**
     *  [[agentica.llm.LLMProvider]] stub that captures every `streamResponses` call
     *  so tests can assert on the `input` messages and `previousResponseId` passed by
     *  [[AgentLoop]].  Each call dequeues the next scripted response.
     *  @param responses          Pre-scripted assistant response strings, in order.
     *  @param responseIdToReturn Optional response ID to include in every [[LLMResponse]],
     *                            simulating a stateful Responses API server.
     */
    private class CapturingResponsesProvider(
        responses:          List[String],
        responseIdToReturn: Option[String] = None
    ) extends agentica.llm.LLMProvider
    {
        val modelName: String = "capturing-responses-model"

        val capturedInputs:  mutable.ListBuffer[List[Message]]      = mutable.ListBuffer.empty
        val capturedPrevIds: mutable.ListBuffer[Option[String]]      = mutable.ListBuffer.empty

        private val queue = mutable.Queue(responses*)

        /**
         *  Not used by [[AgentLoop]] when `streamResponses` is available.
         *  @param messages  Ignored.
         *  @param onToken   Ignored.
         *  @return          Stub [[LLMResponse]].
         */
        def streamChatCompletions(messages: List[Message], onToken: String => Unit): LLMResponse =
            throw UnsupportedOperationException("CapturingResponsesProvider only supports streamResponses")

        /**
         *  Records `input` messages and `previousResponseId`, then emits the next scripted response.
         *  @param input               The message list passed by [[AgentLoop]].
         *  @param onToken             Callback invoked per token.
         *  @param previousResponseId  The response ID from the prior turn, if any.
         *  @return                    [[LLMResponse]] with [[responseIdToReturn]] when set.
         */
        override def streamResponses(
            input:              List[Message],
            onToken:            String => Unit,
            previousResponseId: Option[String] = None
        ): LLMResponse =
        {
            capturedInputs  += input
            capturedPrevIds += previousResponseId
            val response = if queue.nonEmpty then queue.dequeue() else ""
            response.split("(?<=\\n)|(?=\\n)").foreach(onToken)
            LLMResponse(
                model            = modelName,
                promptTokens     = 0,
                completionTokens = response.length / 4,
                latencyMs        = 0,
                responseId       = responseIdToReturn
            )
        }
    }

    /**
     *  Builds an [[AgentLoop]] wired with a [[CapturingResponsesProvider]].
     *  @param provider  The capturing provider to use.
     *  @param store     Optional message store (defaults to a fresh [[StubMessageStore]]).
     *  @return          The constructed loop.
     */
    private val responsesSettings = defaultSettings.copy(apiMode = APIMode.Responses)

    private def makeLoopWithCapturing(
        provider:     CapturingResponsesProvider,
        store:        StubMessageStore  = new StubMessageStore(),
        sessionStore: StubSessionStore  = new StubSessionStore()
    ): AgentLoop =
    {
        new AgentLoop(
            provider,
            None,
            store,
            new StubRunStore(),
            new AgentTurnStore(null) { override def insert(t: AgentTurn): Unit = () },
            new StubTokenAccounting(),
            new EchoVirtualShell(),
            responsesSettings,
            null.asInstanceOf[ScopeStore],
            null.asInstanceOf[agentica.session.MemoryStore],
            sessionStore
        )
    }

    test("streamResponses: cold start sends full context as message list") {
        val provider = CapturingResponsesProvider(List("Answer.\n<done>"))
        val loop     = makeLoopWithCapturing(provider)

        loop.run(session, Nil, userMsg, "t1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = _ => ()
        )

        assert(provider.capturedInputs.size == 1, "exactly one LLM call")
        val msgs = provider.capturedInputs.head
        assert(msgs.exists(_.role == MessageRole.System), "cold start must include system message")
        assert(msgs.exists(_.role == MessageRole.User),   "cold start must include user message")
        assert(msgs.last.content == userMsg.content,      "last message must be the user message")
    }

    test("streamResponses: cold start includes full history in message list") {
        val provider    = CapturingResponsesProvider(List("Answer.\n<done>"))
        val loop        = makeLoopWithCapturing(provider)
        val priorAssist = Message("prior-1", session.id, MessageRole.Assistant, "Prior answer.", "")

        loop.run(session, List(priorAssist), userMsg, "t1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = _ => ()
        )

        val msgs = provider.capturedInputs.head
        assert(msgs.exists(m => m.role == MessageRole.Assistant && m.content == "Prior answer."),
            "cold start must include prior assistant message from history")
    }

    test("streamResponses: subsequent iteration in same run sends single tool-result message") {
        // Two iterations: first response has a tool call, second is the final answer.
        // On the second call the server retains state — only the tool result block is sent.
        val provider = CapturingResponsesProvider(
            responses          = List(
                """run(command="files.stat path=x")""",
                "Done.\n<done>"
            ),
            responseIdToReturn = Some("resp-001")
        )
        val loop = makeLoopWithCapturing(provider)

        loop.run(session, Nil, userMsg, "t1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = _ => ()
        )

        assert(provider.capturedInputs.size == 2, "two LLM calls expected")
        val secondMsgs = provider.capturedInputs(1)
        assert(secondMsgs.size == 1,                          "second call must send exactly one message")
        assert(secondMsgs.head.role == MessageRole.User,      "second call message must be user role")
        assert(secondMsgs.head.content.contains("[TOOL RESULT]"), "second call must contain tool result block")
    }

    test("streamResponses: previousResponseId is None on cold start") {
        val provider = CapturingResponsesProvider(List("Answer.\n<done>"))
        val loop     = makeLoopWithCapturing(provider)

        loop.run(session, Nil, userMsg, "t1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = _ => ()
        )

        assert(provider.capturedPrevIds.head.isEmpty, "previousResponseId must be None on cold start")
    }

    test("streamResponses: responseId from first call is threaded as previousResponseId on second call") {
        val provider = CapturingResponsesProvider(
            responses          = List(
                """run(command="files.stat path=x")""",
                "Done.\n<done>"
            ),
            responseIdToReturn = Some("resp-abc")
        )
        val loop = makeLoopWithCapturing(provider)

        loop.run(session, Nil, userMsg, "t1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = _ => ()
        )

        assert(provider.capturedPrevIds.size == 2,               "two LLM calls expected")
        assert(provider.capturedPrevIds(0).isEmpty,              "first call: previousResponseId must be None")
        assert(provider.capturedPrevIds(1) == Some("resp-abc"),  "second call: previousResponseId must be the ID from first response")
    }

    test("streamResponses: session with existing lastResponseId threads it into first call") {
        val sessionWithPriorId = session.copy(lastResponseId = Some("prior-resp-xyz"))
        val provider           = CapturingResponsesProvider(List("Answer.\n<done>"))
        val loop               = makeLoopWithCapturing(provider)

        loop.run(sessionWithPriorId, Nil, userMsg, "t1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = _ => ()
        )

        assert(provider.capturedPrevIds.head == Some("prior-resp-xyz"),
            "existing session lastResponseId must be passed as previousResponseId on the first call")
    }

    test("streamResponses: warm continuation first call sends only user message") {
        // When a prior response ID exists, the server already has context.
        // Only the new user message should be sent as a single-element list.
        val sessionWithPriorId = session.copy(lastResponseId = Some("prior-resp-xyz"))
        val provider           = CapturingResponsesProvider(List("Answer.\n<done>"))
        val loop               = makeLoopWithCapturing(provider)

        loop.run(sessionWithPriorId, Nil, userMsg, "t1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = _ => ()
        )

        val msgs = provider.capturedInputs.head
        assert(msgs.size == 1,                       "warm continuation must send exactly one message")
        assert(msgs.head.role == MessageRole.User,   "warm continuation message must be user role")
        assert(msgs.head.content == userMsg.content, "warm continuation message must be the user message content")
    }

    // ── apiMode routing ───────────────────────────────────────────────────────

    test("apiMode=Responses routes to streamResponses") {
        val responsesSettings = defaultSettings.copy(apiMode = APIMode.Responses)
        val provider          = CapturingResponsesProvider(List("Answer.\n<done>"))
        val loop = new AgentLoop(
            provider,
            None,
            new StubMessageStore(),
            new StubRunStore(),
            new AgentTurnStore(null) { override def insert(t: AgentTurn): Unit = () },
            new StubTokenAccounting(),
            new EchoVirtualShell(),
            responsesSettings,
            null.asInstanceOf[ScopeStore],
            null.asInstanceOf[agentica.session.MemoryStore],
            new StubSessionStore()
        )

        loop.run(session, Nil, userMsg, "t1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = _ => ()
        )

        assert(provider.capturedInputs.nonEmpty, "streamResponses must have been called")
    }

    test("apiMode=ChatCompletions routes to streamChatCompletions and not streamResponses") {
        val chatSettings  = defaultSettings.copy(apiMode = APIMode.ChatCompletions)
        var chatCallCount = 0
        val trackingLLM   = new agentica.llm.LLMProvider
        {
            val modelName = "tracking-model"
            override def streamChatCompletions(messages: List[Message], onToken: String => Unit): LLMResponse =
            {
                chatCallCount += 1
                onToken("Answer.\n<done>")
                LLMResponse(model = modelName, promptTokens = 0, completionTokens = 0, latencyMs = 0)
            }
        }
        val loop = new AgentLoop(
            trackingLLM,
            None,
            new StubMessageStore(),
            new StubRunStore(),
            new AgentTurnStore(null) { override def insert(t: AgentTurn): Unit = () },
            new StubTokenAccounting(),
            new EchoVirtualShell(),
            chatSettings,
            null.asInstanceOf[ScopeStore],
            null.asInstanceOf[agentica.session.MemoryStore],
            new StubSessionStore()
        )

        loop.run(session, Nil, userMsg, "t1", new AtomicBoolean(false), new SynchronousQueue[GrantDecision](),
            emitToken = _ => (),
            emitEvent = _ => ()
        )

        assert(chatCallCount == 1, "streamChatCompletions must have been called exactly once")
    }
}
