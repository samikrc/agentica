package agentica.shell.compression

import agentica.agent.AgentEvent
import agentica.llm.{LLMProvider, LLMResponse, ToolSpec}
import agentica.permissions.{GrantDecision, PermissionCoordinator, ScopeStore}
import agentica.session.{MemoryStore, Message, Session}
import agentica.shell.{Command, CommandRegistry, ScratchEntry, SessionScratchpad, ToolCall, ToolMiddleware, VirtualShell}
import agentica.tools.{ArgError, ArgSpec, CommandSchema, ExecutionContext, Tool, ToolBody, ToolResult, ToolStatus}
import org.scalatest.funsuite.AnyFunSuite
import scala.collection.immutable.ListMap
import scala.collection.mutable.ListBuffer

/**
 *  Tests for [[CompressionMiddleware]]: compressed previews for both
 *  [[ToolBody]] variants with scratchpad-backed recovery, the size and
 *  reduction gates, fail-closed pass-through, query forwarding, and
 *  end-to-end wiring through [[VirtualShell]]'s base chain.
 */
class CompressionMiddlewareTest extends AnyFunSuite
{
    /** ~18 KB of compressible line-oriented payload. */
    private val bigLines: String =
        (1 to 600).map(i => s"GET /api/orders 200 ok worker=${i % 4}").mkString("\n")

    /** Small incompressible body. */
    private val smallBody: String = "short result"

    private val allGrants: ScopeStore = new ScopeStore
    {
        def hasGrant(sessionId: String, toolName: String, resolvedPath: String): Boolean = true
        def addGrant(sessionId: String, toolName: String, decision: GrantDecision.Granted): Unit = ()
        def consumeOnce(sessionId: String, toolName: String, resolvedPath: String): Unit = ()
        def deleteForSession(sessionId: String): Unit = ()
    }

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

    /** Builds an [[ExecutionContext]] with the grant-all store and the given scratchpad. */
    private def ctx(scratchpad: SessionScratchpad = SessionScratchpad()): ExecutionContext =
        ExecutionContext(
            session         = Session("s1", "Test", "", "", "test-model", Some("/tmp")),
            traceId         = "compression-test",
            scopeStore      = allGrants,
            scratchpad      = scratchpad,
            memoryStore     = null.asInstanceOf[MemoryStore],
            llmProvider     = nullProvider,
            vlmProvider     = None,
            onEvent         = (_: AgentEvent) => (),
            permissionCoordinator = new PermissionCoordinator("compression-test"),
            debugMode       = false,
            vlmParallelism  = 1
        )

    /** Builds a [[ToolCall]] for a `test_echo` command with the given args. */
    private def call(ctx: ExecutionContext, args: Map[String, String] = Map.empty): ToolCall =
        ToolCall(Command("test", "echo", args), ctx)

    /** Stores `text` in the scratchpad and returns a [[ToolResult]] carrying its ref. */
    private def refResult(ctx: ExecutionContext, text: String): ToolResult =
    {
        val entry = ScratchEntry(text, text.length.toLong, text.count(_ == '\n') + 1,
            "source.txt", 0L, System.currentTimeMillis())
        val ref = ctx.scratchpad.store("source.txt", entry)
        ToolResult(ToolStatus.Ok, body = Some(ToolBody.ScratchRef(ref, "source.txt", text.length, entry.lineCount)))
    }

    /** A compressor that records queries and returns a fixed result. */
    private class StubCompressor(seen: ListBuffer[String], ret: Option[String]) extends Compressor
    {
        val contentType: String = "stub"
        def compress(text: String, query: String = ""): Option[String] = { seen += query; ret }
    }

    test("ScratchRef body becomes a compressed inline preview with the recovery ref") {
        val c      = ctx()
        val result = new CompressionMiddleware().after(call(c), refResult(c, bigLines))
        result.body match
        {
            case Some(ToolBody.Inline(text)) =>
                assert(text.length < agentica.shell.Presentation.BODY_BUDGET_CHARS)
                assert(text.contains("elided"), s"expected compressed view: ${text.take(300)}")
            case other => fail(s"expected compressed inline, got $other")
        }
        assert(result.metadata("stored") == "$scratch/source.txt")
        assert(result.metadata("compressed").startsWith("lines"))
    }

    test("uncompressible ScratchRef passes through unchanged") {
        val c      = ctx()
        val result = new CompressionMiddleware().after(call(c), refResult(c, "plain prose without structure " * 400))
        result.body match
        {
            case Some(_: ToolBody.ScratchRef) => ()
            case other => fail(s"expected passthrough: $other")
        }
    }

    test("ScratchRef misses the scratchpad → pass through") {
        val c      = ctx()
        val result = ToolResult(ToolStatus.Ok,
            body = Some(ToolBody.ScratchRef("$scratch/nonexistent.txt", "x.txt", 10, 1)))
        assert(new CompressionMiddleware().after(call(c), result) eq result)
    }

    test("large Inline compresses and stores the original when no ref exists") {
        val c      = ctx()
        val result = new CompressionMiddleware().after(call(c),
            ToolResult(ToolStatus.Ok, body = Some(ToolBody.Inline(bigLines))))
        result.body match
        {
            case Some(ToolBody.Inline(text)) => assert(text.contains("elided"), text.take(200))
            case other => fail(s"expected compressed inline, got $other")
        }
        val ref = result.metadata("stored")
        assert(ref.startsWith("$scratch/__result_"), s"expected computed-key ref: $ref")
        assert(c.scratchpad.get(ref).exists(_.content == bigLines), "original must be recoverable")
    }

    test("Inline above threshold reuses an existing stored ref") {
        val c      = ctx()
        val result = new CompressionMiddleware().after(call(c), ToolResult(ToolStatus.Ok,
            metadata = ListMap("stored" -> "$scratch/existing.txt"),
            body     = Some(ToolBody.Inline(bigLines))))
        assert(result.metadata("stored") == "$scratch/existing.txt")
    }

    test("Inline below the threshold is untouched") {
        val c      = ctx()
        val result = ToolResult(ToolStatus.Ok, body = Some(ToolBody.Inline(smallBody)))
        assert(new CompressionMiddleware().after(call(c), result) eq result)
    }

    test("Inline without enough reduction is untouched") {
        val c      = ctx()
        val stub   = new StubCompressor(ListBuffer.empty, Some("x" * (bigLines.length - 100)))
        val result = new CompressionMiddleware(List(stub)).after(call(c),
            ToolResult(ToolStatus.Ok, body = Some(ToolBody.Inline(bigLines))))
        assert(result.body == Some(ToolBody.Inline(bigLines)), "sub-30% reduction must pass through")
    }

    test("bodies above the size cap pass through unchanged") {
        val c    = ctx()
        // 1 char over the middleware's 1 MB compression cap.
        val huge   = "x" * 1_000_001
        val inline = ToolResult(ToolStatus.Ok, body = Some(ToolBody.Inline(huge)))
        assert(new CompressionMiddleware().after(call(c), inline) eq inline,
            "oversized inline body must not be compressed")
        val ref = refResult(c, huge)
        assert(new CompressionMiddleware().after(call(c), ref) eq ref,
            "oversized scratch ref must not be compressed")
    }

    test("Err results are untouched") {
        val c      = ctx()
        val result = ToolResult(ToolStatus.Err(code = "internal_error", message = "boom"),
            body = Some(ToolBody.Inline(bigLines)))
        assert(new CompressionMiddleware().after(call(c), result) eq result)
    }

    test("the call's query argument reaches the compressor") {
        val c    = ctx()
        val seen = ListBuffer.empty[String]
        new CompressionMiddleware(List(new StubCompressor(seen, Some("tiny")))).after(
            call(c, Map("query" -> "revenue")),
            ToolResult(ToolStatus.Ok, body = Some(ToolBody.Inline(bigLines))))
        assert(seen == ListBuffer("revenue"))
    }

    // ── End-to-end through VirtualShell ──────────────────────────────────────

    /** A stub tool that renders a large result as a scratchpad ref. */
    private class BigRefTool extends Tool[Map[String, String], String]
    {
        val name: String = "test_bigref"
        val schema: CommandSchema = CommandSchema(
            fullName = name, summary = "Big-result stub", args = Nil, example = "test_bigref")
        def validate(args: Map[String, String]): Either[ArgError, Map[String, String]] = Right(args)
        def execute(input: Map[String, String], ctx: ExecutionContext): String = bigLines
        def render(output: String, ctx: ExecutionContext): ToolResult = refResult(ctx, output)
    }

    test("end-to-end: a large tool result arrives compressed with the stored ref") {
        val reg = new CommandRegistry()
        reg.register(new BigRefTool)
        val shell = new VirtualShell(reg)
        val (_, resp) = shell.executeNative("test_bigref", "{}", ctx())
        assert(resp.text.contains("─ compressed:"), s"expected compression metadata: ${resp.text.take(400)}")
        assert(resp.text.contains("─ stored: $scratch/source.txt"), resp.text.take(400))
        assert(resp.text.contains("lines elided"), s"expected compressed preview body: ${resp.text.take(500)}")
    }
}
