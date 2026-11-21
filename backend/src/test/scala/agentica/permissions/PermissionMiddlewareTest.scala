package agentica.permissions

import agentica.agent.AgentEvent
import agentica.llm.{LLMProvider, LLMResponse, ToolSpec}
import agentica.session.{MemoryStore, Message, Session}
import agentica.shell.{CommandRegistry, SessionScratchpad, VirtualShell}
import agentica.tools.{ArgError, ArgSpec, CommandSchema, ExecutionContext, PermissionTarget, Tool, ToolResult, ToolStatus}
import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable.ListBuffer

/**
 *  Gating tests for [[PermissionMiddleware]] in the [[VirtualShell]] chain.
 *
 *  Covers: pass-through when a grant exists, prompt → grant → dispatch on
 *  approval, prompt → `permission_denied` short-circuit on denial, no gating
 *  for non-sensitive tools, and `permissionTarget` not being consulted when
 *  arguments fail validation.
 */
class PermissionMiddlewareTest extends AnyFunSuite
{

    /** Tool stub whose `permissionTarget` may be fixed to a sensitive target; records usage. */
    private class GatedTool(sensitive: Boolean) extends Tool[Map[String, String], String]
    {
        var executeCalls = 0
        var targetCalls  = 0

        val name: String = "test_gated"
        val schema: CommandSchema = CommandSchema(
            fullName = name,
            summary  = "Gate stub for permission tests",
            args     = List(ArgSpec("x", "optional arg", required = false)),
            example  = """test_gated x=1"""
        )

        def validate(args: Map[String, String]): Either[ArgError, Map[String, String]] =
            if (args.contains("bad")) Left(ArgError("bad args", Some("bad"))) else Right(args)

        override def permissionTarget(input: Map[String, String], ctx: ExecutionContext): Option[PermissionTarget] =
        {
            targetCalls += 1
            if (sensitive) Some(PermissionTarget(grantKey = "grant://key", displayPath = Some("target/path")))
            else None
        }

        def execute(input: Map[String, String], ctx: ExecutionContext): String =
        {
            executeCalls += 1
            "executed"
        }

        def render(output: String, ctx: ExecutionContext): ToolResult = ToolResult(ToolStatus.Ok)
    }

    /** ScopeStore stub with a controllable grant result and an `addGrant` counter. */
    private class GrantableStore(granted: Boolean) extends ScopeStore
    {
        var addGrantCalls = 0
        def hasGrant(sessionId: String, toolName: String, resolvedPath: String): Boolean = granted
        def addGrant(sessionId: String, toolName: String, decision: GrantDecision.Granted): Unit =
            addGrantCalls += 1
        def consumeOnce(sessionId: String, toolName: String, resolvedPath: String): Unit = ()
        def deleteForSession(sessionId: String): Unit = ()
    }

    /** Provider stub; never invoked because tools dispatch, not the model. */
    private val nullProvider: LLMProvider = new LLMProvider
    {
        val modelName: String = "null"
        def streamChatCompletions(messages: List[Message], onToken: String => Unit,
                                  tools: List[ToolSpec] = Nil): LLMResponse =
            throw UnsupportedOperationException("not used")
        override def streamResponses(input: List[Message], onToken: String => Unit,
                                     previousResponseId: Option[String],
                                     tools: List[ToolSpec]): LLMResponse =
            throw UnsupportedOperationException("not used")
    }

    /**
     *  Builds an ExecutionContext whose event handler records all events and
     *  auto-resolves `PermissionRequired` prompts with `decision` when present.
     */
    private def ctx(
        store:       ScopeStore,
        coordinator: PermissionCoordinator,
        events:      ListBuffer[AgentEvent],
        decision:    Option[GrantDecision]
    ): ExecutionContext =
        ExecutionContext(
            session         = Session("s1", "Test", "", "", "test-model", Some("/tmp")),
            traceId         = "perm-middleware-test",
            scopeStore      = store,
            scratchpad      = SessionScratchpad(),
            memoryStore     = null.asInstanceOf[MemoryStore],
            llmProvider     = nullProvider,
            vlmProvider     = None,
            onEvent         = e =>
            {
                events += e
                e match
                {
                    case AgentEvent.PermissionRequired(requestId, _, _, _) =>
                        decision.foreach(coordinator.resolve(requestId, _))
                    case _ => ()
                }
            },
            permissionCoordinator = coordinator,
            debugMode       = false,
            vlmParallelism  = 1
        )

    /** Registers a `GatedTool` and returns `(shell, tool)`. */
    private def shellWith(sensitive: Boolean): (VirtualShell, GatedTool) =
    {
        val tool = new GatedTool(sensitive)
        val reg  = new CommandRegistry()
        reg.register(tool)
        (new VirtualShell(reg), tool)
    }

    test("existing grant dispatches without prompting") {
        val (shell, tool) = shellWith(sensitive = true)
        val coordinator   = new PermissionCoordinator("run-granted")
        val events        = ListBuffer.empty[AgentEvent]

        shell.executeNative("test_gated", "{}", ctx(GrantableStore(granted = true), coordinator, events, None))

        assert(tool.executeCalls == 1)
        assert(tool.targetCalls == 1)
        assert(events.collect { case e: AgentEvent.PermissionRequired => e }.isEmpty,
            "no prompt should fire when a grant covers the target")
    }

    test("granted prompt stores the grant and dispatches") {
        val (shell, tool) = shellWith(sensitive = true)
        val store         = GrantableStore(granted = false)
        val coordinator   = new PermissionCoordinator("run-prompt-granted")
        val events        = ListBuffer.empty[AgentEvent]

        val (_, resp) = shell.executeNative("test_gated", "{}",
            ctx(store, coordinator, events, Some(GrantDecision.Granted(GrantTTL.ForSession, None))))

        assert(!resp.text.contains("error:"), s"expected success, got: ${resp.text}")
        assert(events.collect { case e: AgentEvent.PermissionRequired => e }.length == 1)
        assert(store.addGrantCalls == 1)
        assert(tool.executeCalls == 1)
    }

    test("denied prompt short-circuits with permission_denied and skips dispatch") {
        val (shell, tool) = shellWith(sensitive = true)
        val coordinator   = new PermissionCoordinator("run-denied")
        val events        = ListBuffer.empty[AgentEvent]

        val (_, resp) = shell.executeNative("test_gated", "{}",
            ctx(GrantableStore(granted = false), coordinator, events, Some(GrantDecision.Denied)))

        assert(resp.text.contains("error: permission_denied"),
            s"expected permission_denied, got: ${resp.text}")
        assert(events.collect { case e: AgentEvent.PermissionRequired => e }.length == 1)
        assert(tool.executeCalls == 0, "dispatch must not run on denial")
    }

    test("non-sensitive tool passes through with no target lookup and no prompt") {
        val (shell, tool) = shellWith(sensitive = false)
        val coordinator   = new PermissionCoordinator("run-nonsensitive")
        val events        = ListBuffer.empty[AgentEvent]

        shell.executeNative("test_gated", "{}",
            ctx(GrantableStore(granted = false), coordinator, events, None))

        assert(tool.executeCalls == 1)
        assert(tool.targetCalls == 1, "permissionTarget is still consulted; its None means ungated")
        assert(events.collect { case e: AgentEvent.PermissionRequired => e }.isEmpty)
    }

    test("permissionTarget is not consulted when arguments fail validation") {
        val (shell, tool) = shellWith(sensitive = true)
        val coordinator   = new PermissionCoordinator("run-badargs")
        val events        = ListBuffer.empty[AgentEvent]

        val (_, resp) = shell.executeNative("test_gated", """{"bad":"x"}""",
            ctx(GrantableStore(granted = false), coordinator, events, Some(GrantDecision.Denied)))

        assert(resp.text.contains("error:"), s"expected an error result, got: ${resp.text}")
        assert(tool.targetCalls == 0, "permissionTarget must not run on invalid args")
        assert(events.collect { case e: AgentEvent.PermissionRequired => e }.isEmpty)
    }
}
