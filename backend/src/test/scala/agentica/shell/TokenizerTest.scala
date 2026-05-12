package agentica.shell

import org.scalatest.funsuite.AnyFunSuite

class TokenizerTest extends AnyFunSuite
{

    // ── Happy-path: family.verb ───────────────────────────────────────────────

    test("parse: no-arg command") {
        val result = Tokenizer.parse("files.list")
        assert(result == Right(Command("files", "list", Map.empty)))
    }

    test("parse: single bare-word arg") {
        val result = Tokenizer.parse("files.read path=data/report.txt")
        assert(result == Right(Command("files", "read", Map("path" -> "data/report.txt"))))
    }

    test("parse: multiple bare-word args") {
        val result = Tokenizer.parse("files.search query=revenue path=reports/ ignore_case=true")
        assert(result == Right(Command("files", "search",
            Map("query" -> "revenue", "path" -> "reports/", "ignore_case" -> "true"))))
    }

    test("parse: single quoted arg") {
        val result = Tokenizer.parse("""files.search query="total revenue" path=reports/""")
        assert(result == Right(Command("files", "search",
            Map("query" -> "total revenue", "path" -> "reports/"))))
    }

    test("parse: quoted arg with embedded spaces") {
        val result = Tokenizer.parse("""llm.summarize text="some text with many spaces and words"""")
        assert(result == Right(Command("llm", "summarize",
            Map("text" -> "some text with many spaces and words"))))
    }

    test("parse: quoted arg with escaped quote") {
        // Input the tokenizer receives:  files.search query="he said \"hello\""
        // (backslash-quote is the escape sequence; the tokenizer must unescape it)
        val input  = "files.search query=\"he said \\\"hello\\\"\""
        val result = Tokenizer.parse(input)
        assert(result == Right(Command("files", "search",
            Map("query" -> "he said \"hello\""))))
    }

    test("parse: quoted arg with embedded newline") {
        val input  = "files.search query=\"line1\nline2\""
        val result = Tokenizer.parse(input)
        assert(result == Right(Command("files", "search",
            Map("query" -> "line1\nline2"))))
    }

    test("parse: leading and trailing whitespace ignored") {
        val result = Tokenizer.parse("  files.stat path=foo.txt  ")
        assert(result == Right(Command("files", "stat", Map("path" -> "foo.txt"))))
    }

    test("parse: arg with path containing dots") {
        val result = Tokenizer.parse("files.read path=src/main.scala")
        assert(result == Right(Command("files", "read", Map("path" -> "src/main.scala"))))
    }

    test("parse: memory.set with key and value args") {
        val result = Tokenizer.parse("""memory.set key=project_name value="Agentica v2"""")
        assert(result == Right(Command("memory", "set",
            Map("key" -> "project_name", "value" -> "Agentica v2"))))
    }

    test("parse: scratchpad ref as bare arg value") {
        val result = Tokenizer.parse("llm.summarize text=$scratch/data/report.txt")
        assert(result == Right(Command("llm", "summarize",
            Map("text" -> "$scratch/data/report.txt"))))
    }

    // ── Error cases ───────────────────────────────────────────────────────────

    test("parse error: empty string") {
        assert(Tokenizer.parse("").isLeft)
    }

    test("parse error: whitespace only") {
        assert(Tokenizer.parse("   ").isLeft)
    }

    test("parse error: missing dot in head") {
        val result = Tokenizer.parse("filesread path=foo.txt")
        assert(result.isLeft)
        assert(result.left.get.message.contains("expected family.verb"))
    }

    test("parse error: dot at start (empty family)") {
        assert(Tokenizer.parse(".read path=foo.txt").isLeft)
    }

    test("parse error: dot at end (empty verb)") {
        assert(Tokenizer.parse("files. path=foo.txt").isLeft)
    }

    test("parse error: missing = in arg") {
        val result = Tokenizer.parse("files.read path")
        assert(result.isLeft)
        assert(result.left.get.message.contains("expected key=value"))
    }

    test("parse error: empty value for arg") {
        val result = Tokenizer.parse("files.read path=")
        assert(result.isLeft)
        assert(result.left.get.message.contains("empty value"))
    }

    test("parse error: unclosed quote") {
        val result = Tokenizer.parse("""files.search query="unclosed""")
        assert(result.isLeft)
        assert(result.left.get.message.contains("unclosed quoted string"))
    }

    test("parse error: character after closing quote") {
        val result = Tokenizer.parse("""files.search query="value"x""")
        assert(result.isLeft)
        assert(result.left.get.message.contains("unexpected character"))
    }

    // ── fullName helper ───────────────────────────────────────────────────────

    test("Command.fullName returns family.verb") {
        val cmd = Command("files", "read", Map.empty)
        assert(cmd.fullName == "files.read")
    }
}
