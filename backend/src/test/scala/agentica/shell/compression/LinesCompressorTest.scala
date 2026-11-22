package agentica.shell.compression

import org.scalatest.funsuite.AnyFunSuite

/**
 *  Tests for [[LinesCompressor]]: run elision on line-oriented payloads,
 *  scattered-duplicate collapse via the non-redundant guard, kept signal
 *  lines, marker summaries, and fail-closed behaviour.
 */
class LinesCompressorTest extends AnyFunSuite
{
    test("repeated identical lines collapse to one occurrence plus markers") {
        val boilerplate = "Acme Corp — Confidential — Page Footer"
        val input = (Seq("doc start line") ++ Vector.fill(6)(boilerplate) ++
            Seq("middle content alpha bravo charlie delta echo foxtrot") ++
            Vector.fill(5)(boilerplate) ++ Seq("end line one", "end line two")).mkString("\n")
        val out   = LinesCompressor.compress(input).get
        assert(out.linesIterator.count(_ == boilerplate) == 1,
            s"boilerplate should appear once: $out")
        assert(out.contains("lines elided"), out)
    }

    test("consecutive duplicate runs collapse") {
        val input = ("start" +: Vector.fill(10)("ok") :+ "end").mkString("\n")
        val out   = LinesCompressor.compress(input).get
        assert(out.count(_ == '\n') < 6, out)
        assert(out.contains("elided"), out)
    }

    test("error lines are force-kept") {
        val lines = (1 to 20).map(i => s"info line $i processing batch")
        val input = (lines.take(10) ++ Seq("ERROR disk failure at sector 9") ++ lines.drop(10)).mkString("\n")
        val out   = LinesCompressor.compress(input).get
        assert(out.contains("ERROR disk failure"), out)
    }

    test("marker summary reports extracted field facts") {
        val lines = (1 to 10).map(i => s"worker=$i status=idle result=ok")
        val out   = LinesCompressor.compress(lines.mkString("\n")).get
        assert(out.contains("status=idle") || out.contains("result=ok"), out)
    }

    test("all-distinct content passes through as a document") {
        // Every line carries unique letter vocabulary → keepNonRedundant
        // promotes them all → nothing elides → honest zero.
        val pool  = Vector("alpha","bravo","charlie","delta","echo","foxtrot","golf","hotel",
                           "india","juliet","kilo","lima","mike","november","oscar","papa",
                           "quebec","romeo","sierra","tango","uniform","victor","whiskey",
                           "xray","yankee","zulu","aurora","beacon","cobalt","drift")
        val lines = (0 until 30).map(i =>
            s"record ${pool(i)} entry ${pool((i + 7) % 30)} detail ${pool((i + 13) % 30)}")
        assert(LinesCompressor.compress(lines.mkString("\n")).isEmpty)
    }

    test("too few lines passes through") {
        assert(LinesCompressor.compress("a\nb\nc").isEmpty)
    }

    test("CRLF input keeps CRLF line endings in the output") {
        val input = (Vector.fill(15)("repeat me") ++ Seq("a", "b")).mkString("\r\n")
        val out   = LinesCompressor.compress(input).get
        assert(out.contains("\r\n"), s"expected CRLF joins: ${out.take(200)}")
        assert(!out.replace("\r\n", "").contains("\n"), "a CRLF document must not gain bare LF joins")
    }

    test("idempotent — own markers are not re-compressed") {
        val once = LinesCompressor.compress((Vector.fill(15)("repeat me") ++ Seq("a","b")).mkString("\n")).get
        assert(LinesCompressor.compress(once).isEmpty)
    }
}
