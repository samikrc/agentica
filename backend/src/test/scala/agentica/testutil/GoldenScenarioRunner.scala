package agentica.testutil

import agentica.agent.{AgentEvent, AgentLoop}
import agentica.observability.TokenAccounting
import agentica.session.{AgentTurnStore, Message, MessageRole, MessageStore, RunStore, Session, ToolRun}
import agentica.settings.AppSettings
import agentica.shell.{CommandRegistry, SessionScratchpad, VirtualShell}
import agentica.tools.ExecutionContext
import agentica.tools.files.{FilesRead, FilesWrite, FilesList, FilesSearch, FilesStat}
import agentica.tools.memory.{MemorySet, MemoryGet, MemoryList}
import agentica.permissions.{GrantDecision, GrantTTL, ScopeStore}
import java.nio.file.{Files, Path}
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.mutable

/**
 *  Replay test scaffolding for golden scenarios.
 *  Creates a temporary workspace, runs the agent loop with a scripted provider,
 *  and captures the tool call sequence and final answer for assertions.
 *
 *  @param scenarioPath  Path to the JSON scenario file containing responses.
 *  @param workspaceFiles  Optional map of relative path -> content to pre-populate in workspace.
 */
class GoldenScenarioRunner(scenarioPath: Path, workspaceFiles: Map[String, String] = Map.empty)
{

    private val tempDir       = Files.createTempDirectory("golden-scenario-")
    private val events        = mutable.ListBuffer.empty[AgentEvent]
    private val toolCalls     = mutable.ListBuffer.empty[String] // rawCommand strings
    private val toolRuns      = mutable.ListBuffer.empty[ToolRun]
    private var finalAnswer   = Option.empty[String]
    private var finalMsgId    = Option.empty[String]

    /**
     *  In-memory RunStore that captures all tool runs for assertions.
     */
    private class CapturingRunStore extends RunStore(() => null)
    {
        override def insertRun(run: ToolRun): Unit =
        {
            toolRuns.append(run)
        }
    }

    /**
     *  In-memory MessageStore that captures all messages for assertions.
     */
    private class CapturingMessageStore extends MessageStore(() => null)
    {
        val appended: mutable.ListBuffer[Message] = mutable.ListBuffer.empty

        override def append(sessionId: String, role: MessageRole, content: String): Message =
        {
            val m = Message(
                id        = s"msg-${appended.size}",
                sessionId = sessionId,
                role      = role,
                content   = content,
                timestamp = ""
            )
            appended.append(m)
            m
        }

        override def listForSession(sessionId: String): List[Message] = appended.toList
    }

    /**
     *  In-memory ScopeStore that auto-grants all permissions (for testing).
     */
    private class AutoGrantScopeStore extends ScopeStore
    {
        def hasGrant(sessionId: String, toolName: String, resolvedPath: String): Boolean = true
        def addGrant(sessionId: String, toolName: String, decision: GrantDecision.Granted): Unit = ()
        def consumeOnce(sessionId: String, toolName: String, resolvedPath: String): Unit = ()
        def deleteForSession(sessionId: String): Unit = ()
    }

    /**
     *  TokenAccounting stub that counts calls without persisting.
     */
    private class StubTokenAccounting extends TokenAccounting(null.asInstanceOf[RunStore])
    {
        var recordCount = 0
        override def record(traceId: String, sessionId: String, llmResponse: agentica.llm.LLMResponse): Unit =
        {
            recordCount += 1
        }
    }

    // Write workspace files to temp directory
    workspaceFiles.foreach { entry =>
        val relPath = entry._1
        val content = entry._2
        val file    = tempDir.resolve(relPath)
        Files.createDirectories(file.getParent)
        Files.writeString(file, content)
    }

    /**
     *  Runs the golden scenario.
     *
     *  @return  This runner (for chaining assertions).
     */
    def run(): GoldenScenarioRunner =
    {
        val llm           = new JSONFileLLMProvider(scenarioPath)
        val messageStore  = new CapturingMessageStore()
        val runStore      = new CapturingRunStore()
        val accounting    = new StubTokenAccounting()
        val settings      = AppSettings(maxIterations = 20, contextBudgetTokens = 8000)
        val commandRegistry = CommandRegistry()
        // Register all standard tools
        List(FilesRead, FilesWrite, FilesList, FilesSearch, FilesStat).foreach(t => commandRegistry.register(t))
        List(MemorySet, MemoryGet, MemoryList).foreach(t => commandRegistry.register(t))

        val virtualShell  = new VirtualShell(commandRegistry)

        val session = Session(
            id        = "golden-session",
            title     = "Golden Scenario",
            createdAt = "",
            updatedAt = "",
            model     = llm.modelName,
            rootPath  = Some(tempDir.toString)
        )

        val userMsg = Message(
            id        = "user-1",
            sessionId = session.id,
            role      = MessageRole.User,
            content   = "Execute the scenario.",
            timestamp = ""
        )

        // Build AgentLoop with capturing dependencies
        val loop = new AgentLoop(
            initialLLMProvider = llm,
            initialVLMProvider = None,
            messageStore       = messageStore,
            runStore           = runStore,
            agentTurnStore     = new AgentTurnStore(null) { override def insert(t: agentica.session.AgentTurn): Unit = () },
            tokenAccounting    = accounting,
            virtualShell       = virtualShell,
            settings           = settings,
            scopeStore         = null.asInstanceOf[ScopeStore],
            memoryStore        = null.asInstanceOf[agentica.session.MemoryStore],
            sessionStore       = null.asInstanceOf[agentica.session.SessionStore]
        )
        {
            // Note: buildCtx override removed since AgentLoop now uses shared context
            // Test dependencies are injected through the constructor parameters
        }

        // Run the loop and capture events
        loop.run(
            session         = session,
            history         = Nil,
            userMsg         = userMsg,
            traceId         = "golden-trace",
            cancelFlag      = new AtomicBoolean(false),
            permissionLatch = new SynchronousQueue[GrantDecision](),
            emitToken       = _ => (),
            emitEvent  = evt =>
            {
                events.append(evt)
                evt match
                {
                    case AgentEvent.ToolCallStart(cmd, _) => toolCalls.append(cmd)
                    case AgentEvent.Final(msgId, _)       => finalMsgId = Some(msgId)
                    case _                                 => ()
                }
            }
        )

        // Extract final answer from message store
        finalMsgId.flatMap(id => messageStore.appended.find(_.id == id)).foreach(m => finalAnswer = Some(m.content))

        this
    }

    /**
     *  Returns the captured events for inspection.
     */
    def capturedEvents: List[AgentEvent] = events.toList

    /**
     *  Returns the captured tool calls (raw command strings) in order.
     */
    def capturedToolCalls: List[String] = toolCalls.toList

    /**
     *  Returns the captured ToolRun records in order.
     */
    def capturedToolRuns: List[ToolRun] = toolRuns.toList

    /**
     *  Returns the final answer text, if any.
     */
    def finalAnswerText: Option[String] = finalAnswer

    /**
     *  Asserts that the tool call sequence matches expected commands.
     *  Commands are matched by their string representation.
     *
     *  @param expected  Expected command strings in order.
     *  @throws AssertionError  if the sequence does not match.
     */
    def assertToolSequence(expected: String*): GoldenScenarioRunner =
    {
        val actual = capturedToolCalls
        assert(
            actual == expected.toList,
            s"Tool sequence mismatch.\nExpected: ${expected.mkString("[", ", ", "]")}\nActual:   ${actual.mkString("[", ", ", "]")}"
        )
        this
    }

    /**
     *  Asserts that the final answer contains the expected substring.
     *
     *  @param expected  Substring expected in the final answer.
     *  @throws AssertionError  if the final answer does not contain the substring.
     */
    def assertFinalAnswerContains(expected: String): GoldenScenarioRunner =
    {
        assert(
            finalAnswer.exists(_.contains(expected)),
            s"Final answer does not contain '$expected'.\nActual: ${finalAnswer.getOrElse("(none)")}"
        )
        this
    }

    /**
     *  Asserts that the final answer equals the expected string (trimmed).
     *
     *  @param expected  Expected final answer text.
     *  @throws AssertionError  if the final answer does not match.
     */
    def assertFinalAnswerEquals(expected: String): GoldenScenarioRunner =
    {
        assert(
            finalAnswer.map(_.trim) == Some(expected.trim),
            s"Final answer mismatch.\nExpected: $expected\nActual:   ${finalAnswer.getOrElse("(none)")}"
        )
        this
    }

    /**
     *  Cleans up the temporary workspace directory.
     *  Should be called in a finally block or afterEach.
     */
    def cleanup(): Unit =
    {
        def deleteRecursively(path: Path): Unit =
        {
            if (Files.isDirectory(path))
            {
                Files.list(path).forEach(deleteRecursively)
            }
            Files.deleteIfExists(path)
        }
        deleteRecursively(tempDir)
    }
}
