package agentica.agent

import org.scalatest.funsuite.AnyFunSuite

/**
 *  Unit tests for [[ToolCallParser]].
 *  Covers happy-path extraction, multiple calls in one response,
 *  single-quoted commands, escaped quotes inside the command string,
 *  and all malformed-input cases that must be skipped gracefully.
 */
class ToolCallParserTest extends AnyFunSuite
{

    private val traceId = "test-trace"

    // ── Happy path ────────────────────────────────────────────────────────────

    test("single run() call: double-quoted command") {
        val text   = """run(command="files.read path=foo.txt")"""
        val result = ToolCallParser.parse(text, traceId)
        assert(result.length == 1)
        val tc = result.head.asInstanceOf[ToolCallResult.Success].call
        assert(tc.rawCommand  == "files.read path=foo.txt")
        assert(tc.startOffset == 0)
        assert(tc.endOffset   == text.length)
    }

    test("single run() call: single-quoted command") {
        val text   = "run(command='memory.get key=task')"
        val result = ToolCallParser.parse(text, traceId)
        assert(result.length == 1)
        assert(result.head.asInstanceOf[ToolCallResult.Success].call.rawCommand == "memory.get key=task")
    }

    test("multiple run() calls in document order") {
        val text =
            """First I will list: run(command="files.list path=src")
              |Then read: run(command="files.read path=src/Main.scala")""".stripMargin
        val result = ToolCallParser.parse(text, traceId)
        assert(result.length == 2)
        val tc1 = result(0).asInstanceOf[ToolCallResult.Success].call
        val tc2 = result(1).asInstanceOf[ToolCallResult.Success].call
        assert(tc1.rawCommand == "files.list path=src")
        assert(tc2.rawCommand == "files.read path=src/Main.scala")
    }

    test("run() call with escaped quote inside command value") {
        val text   = """run(command="files.search query=\"hello world\" path=src")"""
        val result = ToolCallParser.parse(text, traceId)
        assert(result.length == 1)
        val tc = result.head.asInstanceOf[ToolCallResult.Success].call
        assert(tc.rawCommand == """files.search query="hello world" path=src""")
    }

    test("run() call with escaped backslash inside command value") {
        val text   = """run(command="files.read path=C:\\Users\\foo")"""
        val result = ToolCallParser.parse(text, traceId)
        assert(result.length == 1)
        val tc = result.head.asInstanceOf[ToolCallResult.Success].call
        assert(tc.rawCommand == """files.read path=C:\Users\foo""")
    }

    test("narrative text before and after run() call is ignored") {
        val text   = """Sure, let me look at that. run(command="files.stat path=README.md") Done."""
        val result = ToolCallParser.parse(text, traceId)
        assert(result.length == 1)
        val tc = result.head.asInstanceOf[ToolCallResult.Success].call
        assert(tc.rawCommand == "files.stat path=README.md")
    }

    test("empty text returns empty list") {
        assert(ToolCallParser.parse("", traceId).isEmpty)
    }

    test("text with no run() calls returns empty list") {
        assert(ToolCallParser.parse("Just a plain answer.\n<done>", traceId).isEmpty)
    }

    // ── Offset correctness ────────────────────────────────────────────────────

    test("startOffset and endOffset span the entire run(...) expression") {
        val prefix = "Some text. "
        val call   = """run(command="files.list path=.")"""
        val text   = prefix + call + " more text"
        val result = ToolCallParser.parse(text, traceId)
        assert(result.length == 1)
        val tc = result.head.asInstanceOf[ToolCallResult.Success].call
        assert(tc.startOffset == prefix.length)
        assert(tc.endOffset   == prefix.length + call.length)
        assert(text.substring(tc.startOffset, tc.endOffset) == call)
    }

    // ── Malformed input: must yield Failure, never throw ─────────────────────

    test("run(command= with no opening quote yields Failure") {
        val text   = "run(command=files.read path=foo)"
        val result = ToolCallParser.parse(text, traceId)
        assert(result.length == 1)
        assert(result.head.isInstanceOf[ToolCallResult.Failure])
        val f = result.head.asInstanceOf[ToolCallResult.Failure].err
        assert(f.reason.contains("expected opening quote"))
    }

    test("run(command=\"... with no closing quote yields Failure") {
        val text   = """run(command="files.read path=foo"""
        val result = ToolCallParser.parse(text, traceId)
        assert(result.length == 1)
        assert(result.head.isInstanceOf[ToolCallResult.Failure])
        val f = result.head.asInstanceOf[ToolCallResult.Failure].err
        assert(f.reason.contains("unclosed"))
    }

    test("run(command=\"...\" with no closing paren yields Failure") {
        val text   = """run(command="files.read path=foo" more stuff"""
        val result = ToolCallParser.parse(text, traceId)
        assert(result.length == 1)
        assert(result.head.isInstanceOf[ToolCallResult.Failure])
        val f = result.head.asInstanceOf[ToolCallResult.Failure].err
        assert(f.reason.contains("missing closing )"))
    }

    test("truncated run( prefix at end of text yields Failure") {
        val text   = "some text run(command="
        val result = ToolCallParser.parse(text, traceId)
        assert(result.length == 1)
        assert(result.head.isInstanceOf[ToolCallResult.Failure])
        val f = result.head.asInstanceOf[ToolCallResult.Failure].err
        assert(f.reason.contains("truncated"))
    }

    test("valid run() after a malformed one: Failure then Success") {
        // The bad call has no opening quote — yields a Failure in-position.
        // The parser continues scanning and extracts the valid call as a Success.
        // Both are returned so AgentLoop can inject the error AND dispatch the good call.
        val bad  = "run(command=NOSTRING) "
        val good = """run(command="files.stat path=x")"""
        val result = ToolCallParser.parse(bad + good, traceId)
        assert(result.length == 2)
        assert(result(0).isInstanceOf[ToolCallResult.Failure])
        assert(result(1).asInstanceOf[ToolCallResult.Success].call.rawCommand == "files.stat path=x")
    }

    // ── Additional parse-failure edge cases ──────────────────────────────────

    test("whitespace between command= and opening quote yields Failure") {
        // A space before the quote is a plausible model formatting slip.
        val text   = """run(command= "files.stat path=x")"""
        val result = ToolCallParser.parse(text, traceId)
        assert(result.length == 1)
        assert(result.head.isInstanceOf[ToolCallResult.Failure])
        val f = result.head.asInstanceOf[ToolCallResult.Failure].err
        assert(f.reason.contains("expected opening quote"))
    }

    test("empty command value is a Success (downstream tokenizer rejects it)") {
        // ToolCallParser's job is extraction, not semantic validation.
        // An empty command string is structurally valid; VirtualShell/Tokenizer rejects it.
        val text   = """run(command="")"""
        val result = ToolCallParser.parse(text, traceId)
        assert(result.length == 1)
        assert(result.head.isInstanceOf[ToolCallResult.Success])
        assert(result.head.asInstanceOf[ToolCallResult.Success].call.rawCommand == "")
    }

    test("nested run( inside quoted command value is not double-counted") {
        // The inner run(command= is inside the string and must not be re-scanned.
        val text   = """run(command="files.search query=run(command=foo) path=src")"""
        val result = ToolCallParser.parse(text, traceId)
        assert(result.length == 1)
        val tc = result.head.asInstanceOf[ToolCallResult.Success].call
        assert(tc.rawCommand == "files.search query=run(command=foo) path=src")
    }

    test("CRLF inside quoted command value is preserved in rawCommand") {
        val text   = "run(command=\"files.read path=foo\r\nlines=1-5\")"
        val result = ToolCallParser.parse(text, traceId)
        assert(result.length == 1)
        val tc = result.head.asInstanceOf[ToolCallResult.Success].call
        assert(tc.rawCommand.contains("\r\n"))
    }

    test("whitespace-only command value is a Success (VirtualShell boundary)") {
        val text   = """run(command="   ")"""
        val result = ToolCallParser.parse(text, traceId)
        assert(result.length == 1)
        assert(result.head.isInstanceOf[ToolCallResult.Success])
        assert(result.head.asInstanceOf[ToolCallResult.Success].call.rawCommand == "   ")
    }

    test("all-failures response returns all Failure entries, no Success") {
        // All three calls are malformed. The loop must receive all three Failures
        // and inject errors for each — it must not terminate or throw.
        val text =
            "run(command=A) " +
            """run(command="unclosed """ +
            "run(command=C)"
        val result = ToolCallParser.parse(text, traceId)
        assert(result.nonEmpty)
        assert(result.forall(_.isInstanceOf[ToolCallResult.Failure]))
    }

    test("parse never throws on arbitrary text") {
        val text = "run(command= \" \" \" broken \\\\ \" )) run(  run(command=\"ok\")"
        val result = ToolCallParser.parse(text, traceId)
        // At minimum must not throw; any well-formed call may or may not be extracted
        assert(result != null)
    }
}
