package agentica.shell

import agentica.agent.AgentEvent
import agentica.llm.{LLMProvider, LLMResponse, ToolSpec}
import agentica.permissions.{GrantDecision, PermissionCoordinator, ScopeStore}
import agentica.session.{MemoryStore, Message, Session}
import agentica.tools.{ArgError, ArgSpec, CommandSchema, ErrorCode, ExecutionContext, Tool, ToolResult, ToolStatus}
import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable.ListBuffer

/**
 *  Chain-semantics tests for [[ToolMiddleware]] dispatch in [[VirtualShell]].
 *
 *  Covers `before` ordering, short-circuit behaviour (later hooks and dispatch
 *  skipped, no `after`), `after` reverse ordering, per-call `extra` components
 *  appended after the base chain, and that the text `execute` path uses the
 *  same chain as `executeNative`.
 */
class ToolMiddlewareTest extends AnyFunSuite
{

    /** Minimal non-sensitive tool: records executions and echoes a fixed result. */
    private class EchoTool extends Tool[Map[String, String], String]
    {
        var executeCalls = 0

        val name: String = "test_echo"
        val schema: CommandSchema = CommandSchema(
            fullName = name,
            summary  = "Echo stub for middleware tests",
            args     = List(ArgSpec("x", "ignored", required = false)),
            example  = """test_echo x=1"""
        )

        def validate(args: Map[String, String]): Either[ArgError, Map[String, String]] = Right(args)

        def execute(input: Map[String, String], ctx: ExecutionContext): String =
        {
            executeCalls += 1
            "executed"
        }

        def render(output: String, ctx: ExecutionContext): ToolResult = ToolResult(ToolStatus.Ok)
    }

    /** Middleware that records hook invocations in `log` and can short-circuit `before`. */
    private class Recorder(id: String, log: ListBuffer[String], shortCircuit: Option[ToolResult] = None)
        extends ToolMiddleware
    {
        override def before(call: ToolCall): Option[ToolResult] =
        {
            log += s"before:$id"
            shortCircuit
        }

        override def after(call: ToolCall, result: ToolResult): ToolResult =
        {
            log += s"after:$id"
            result
        }
    }

    /** ScopeStore stub granting every check so the base permission slot stays quiet. */
    private val allGrants: ScopeStore = new ScopeStore
    {
        def hasGrant(sessionId: String, toolName: String, resolvedPath: String): Boolean = true
        def addGrant(sessionId: String, toolName: String, decision: GrantDecision.Granted): Unit = ()
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

    /** Builds an ExecutionContext with the grant-all store and a recording coordinator. */
    private def ctx(): ExecutionContext =
        ExecutionContext(
            session         = Session("s1", "Test", "", "", "test-model", Some("/tmp")),
            traceId         = "middleware-test",
            scopeStore      = allGrants,
            scratchpad      = SessionScratchpad(),
            memoryStore     = null.asInstanceOf[MemoryStore],
            llmProvider     = nullProvider,
            vlmProvider     = None,
            onEvent         = (_: AgentEvent) => (),
            permissionCoordinator = new PermissionCoordinator("middleware-test"),
            debugMode       = false,
            vlmParallelism  = 1
        )

    /** Registers `EchoTool` and returns `(shell, tool)` with the given base middleware. */
    private def shellWith(mw: List[ToolMiddleware]): (VirtualShell, EchoTool) =
    {
        val tool = new EchoTool()
        val reg  = new CommandRegistry()
        reg.register(tool)
        (new VirtualShell(reg, mw), tool)
    }

    test("before hooks run in chain order, after hooks in reverse order") {
        val log = ListBuffer.empty[String]
        val (shell, tool) = shellWith(List(
            new Recorder("a", log),
            new Recorder("b", log)))

        shell.executeNative("test_echo", "{}", ctx())

        assert(log.toList == List("before:a", "before:b", "after:b", "after:a"),
            s"unexpected hook order: ${log.toList}")
        assert(tool.executeCalls == 1)
    }

    test("a before short-circuit skips dispatch, later before hooks, and all after hooks") {
        val log = ListBuffer.empty[String]
        val denied = ToolResult(ToolStatus.Err(code = ErrorCode.PermissionDenied, message = "blocked by middleware"))
        val (shell, tool) = shellWith(List(
            new Recorder("a", log, Some(denied)),
            new Recorder("b", log)))

        val (_, resp) = shell.executeNative("test_echo", "{}", ctx())

        assert(log.toList == List("before:a"), s"unexpected hooks: ${log.toList}")
        assert(tool.executeCalls == 0, "dispatch must not run after a short-circuit")
        assert(resp.text.contains("error: permission_denied"), s"expected blocked result, got: ${resp.text}")
    }

    test("extra per-call middleware appends after the base chain") {
        val log = ListBuffer.empty[String]
        val (shell, tool) = shellWith(List(new Recorder("base", log)))

        shell.executeNative("test_echo", "{}", ctx(), extra = List(new Recorder("extra", log)))

        assert(log.toList == List("before:base", "before:extra", "after:extra", "after:base"),
            s"unexpected hook order: ${log.toList}")
        assert(tool.executeCalls == 1)
    }

    test("text execute path uses the same chain as executeNative") {
        val log = ListBuffer.empty[String]
        val denied = ToolResult(ToolStatus.Err(code = ErrorCode.PermissionDenied, message = "blocked"))
        val (shell, tool) = shellWith(List(new Recorder("a", log, Some(denied))))

        val resp = shell.execute("test_echo x=1", ctx())

        assert(log.toList == List("before:a"), s"unexpected hooks: ${log.toList}")
        assert(tool.executeCalls == 0)
        assert(resp.text.contains("error: permission_denied"), s"expected blocked result, got: ${resp.text}")
    }
}
