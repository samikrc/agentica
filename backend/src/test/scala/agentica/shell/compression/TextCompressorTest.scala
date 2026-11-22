package agentica.shell.compression

import org.scalatest.funsuite.AnyFunSuite

/**
 *  Tests for [[TextCompressor]]: section-level elision on prose, kept
 *  headings and head/tail context, fenced-block unit handling, embedded
 *  table delegation, and fail-closed behaviour.
 */
class TextCompressorTest extends AnyFunSuite
{
    /** N prose paragraphs of roughly equal weight. */
    private def prose(n: Int): String =
        (1 to n).map { i =>
            s"Section paragraph $i discusses quarterly revenue details and operational " +
            s"notes for region ${'a' + i % 6} with several filler sentences. " +
            ("The committee reviewed the findings thoroughly. " * 3)
        }.mkString("\n\n") + "\n"

    test("middle prose sections elide behind a marker") {
        val out = TextCompressor.compress(prose(14)).get
        assert(out.contains("sections elided"), out)
        assert(out.contains("paragraph 1 "), "head kept")
        assert(out.contains(s"paragraph 14 "), "tail kept")
        assert(out.length < prose(14).length)
    }

    test("headings are kept") {
        val doc = prose(4) + "\n\n# Financial Summary\n\n" + prose(8)
        val out = TextCompressor.compress(doc).get
        assert(out.contains("# Financial Summary"), out)
    }

    test("embedded markdown tables compress at row level inside the section") {
        val table = "| id | status | amount |\n| --- | --- | --- |\n" +
            (1 to 12).map(i => s"| ord-${1000 + i} | paid | ${100 + i}.00 |").mkString("\n")
        val doc   = "Intro paragraph about the ledger below.\n\n" + table +
                    "\n\n" + prose(8)
        val out   = TextCompressor.compress(doc).get
        assert(out.contains("rows elided"), s"embedded table should get row markers: $out")
        assert(out.contains("| id | status | amount |"), "table header survives")
    }

    test("fenced code blocks stay a single unit") {
        val code = "```scala\nval x = 1\n\n\nval y = 2\n```"
        val doc  = prose(3) + "\n\n" + code + "\n\n" + prose(8)
        val out  = TextCompressor.compress(doc).get
        assert(out.contains("val x = 1\n\n\nval y = 2"), s"fenced block must not be split: $out")
    }

    test("important signal words are kept") {
        val doc = prose(5) + "\n\nA WARNING about data integrity in row seven.\n\n" + prose(8)
        val out = TextCompressor.compress(doc).get
        assert(out.contains("WARNING"), out)
    }

    test("too few sections passes through") {
        assert(TextCompressor.compress("one\n\ntwo\n\nthree").isEmpty)
    }

    test("CRLF paragraph documents keep CRLF section separators") {
        val doc = prose(14).replace("\n", "\r\n")
        val out = TextCompressor.compress(doc).get
        assert(out.contains("\r\n\r\n"), s"section separators must stay CRLF: ${out.take(300)}")
        assert(!out.replace("\r\n", "").contains("\n"), "a CRLF document must not gain bare LF joins")
    }

    test("line-mode documents rejoin with single newlines, not blank lines") {
        // No blank lines → each line is a section; rejoining must not insert
        // blank lines (long, punctuated lines so heading detection keeps none).
        val input = (1 to 40).map(i =>
            s"entry $i describes the batch processing outcome. details follow here for review.").mkString("\n")
        val out = TextCompressor.compress(input).get
        assert(!out.contains("\n\n"), s"line-mode output must not gain blank lines: ${out.take(300)}")
        assert(out.contains("sections elided"), out)
    }

    test("already-compressed output is not re-compressed") {
        val once = TextCompressor.compress(prose(14)).get
        assert(TextCompressor.compress(once).isEmpty)
    }
}
