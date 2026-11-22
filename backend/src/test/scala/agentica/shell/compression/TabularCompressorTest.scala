package agentica.shell.compression

import org.scalatest.funsuite.AnyFunSuite

/**
 *  Tests for [[TabularCompressor]]: strict parsing, row elision with kept
 *  header/extrema/important rows, marker summaries, and fail-closed
 *  pass-through on anything unparseable or not worth compressing.
 */
class TabularCompressorTest extends AnyFunSuite
{
    /** A markdown table with `paid` everywhere except the overridden rows. */
    private def mdTable(rows: Int, statusAt: Map[Int, String] = Map.empty,
                        amountAt: Map[Int, String] = Map.empty): String =
    {
        val header = "| id | status | amount |\n| --- | --- | --- |\n"
        header + (1 to rows).map { i =>
            s"| ord-${1000 + i} | ${statusAt.getOrElse(i, "paid")} | ${amountAt.getOrElse(i, s"${100 + i}.00")} |"
        }.mkString("\n") + "\n"
    }

    /** A CSV with uniform status values. */
    private def csvTable(rows: Int): String =
        "id,status,amount\n" + (1 to rows).map(i => s"ord-${1000 + i},paid,${100 + i}.00").mkString("\n")

    test("markdown table: header, extrema, and a marker row with summary survive") {
        val out = TabularCompressor.compress(mdTable(20)).get
        assert(out.contains("| id | status | amount |"), out)
        assert(out.contains("elided"), s"expected elision marker: $out")
        assert(out.contains("ord-1001"), "first rows kept")
        assert(out.contains(s"ord-${1020}"), "last rows kept")
        assert(out.length < mdTable(20).length)
    }

    test("important rows and numeric extrema are force-kept") {
        // Row 12 carries the column maximum mid-table — outside the kept
        // head/tail and not "important" — so only extrema-keeping saves it.
        val input = mdTable(20, Map(8 -> "ERROR"), Map(12 -> "9999.00"))
        val out   = TabularCompressor.compress(input).get
        assert(out.contains("ERROR"), "error row must survive")
        assert(out.contains("9999.00"), s"mid-table extremum row must survive: $out")
    }

    test("marker summary states verified facts about the elided rows") {
        val out = TabularCompressor.compress(csvTable(30)).get
        assert(out.contains("status: paid×") || out.contains("status=paid"), out)
    }

    test("csv parses, compresses, and re-emits delimited rows") {
        val out = TabularCompressor.compress(csvTable(30)).get
        assert(out.startsWith("id,status,amount\n"), out)
        assert(out.contains("ord-1001,paid,101.00"), out)
        assert(out.length < csvTable(30).length)
    }

    test("query relevance keeps matching rows") {
        val input = mdTable(20, Map(10 -> "refunded"))
        val out   = TabularCompressor.compress(input, "refunded").get
        assert(out.contains("refunded"), "query-relevant row must be kept")
    }

    test("non-uniform markdown rows fail closed") {
        val bad = "| a | b |\n| --- | --- |\n| 1 | 2 |\n| 1 | 2 | 3 |\n" +
            (1 to 15).map(_ => "| x | y |").mkString("\n")
        assert(TabularCompressor.compress(bad).isEmpty)
    }

    test("non-table input fails closed") {
        assert(TabularCompressor.compress("just some prose\nwith several lines\nof text\n" * 5).isEmpty)
    }

    test("small tables pass through") {
        assert(TabularCompressor.compress(mdTable(8)).isEmpty)
    }

    test("every row a distinct shape keeps wholesale and fails closed") {
        // Each row carries unique vocabulary → keepNonRedundant promotes them
        // all → the re-rendered table is not smaller → honest zero.
        val rows = (1 to 15).map { i =>
            val words = (0 to 4).map(j => s"term${('a' + i * 5 + j).toChar}${j}").mkString(" ")
            s"| row${i} | $words |"
        }.mkString("\n")
        val input = "| a | b |\n| --- | --- |\n" + rows + "\n"
        assert(TabularCompressor.compress(input).isEmpty)
    }

    test("already-compressed output is not re-compressed") {
        val once = TabularCompressor.compress(mdTable(20)).get
        assert(TabularCompressor.compress(once).isEmpty, "marker detection must make it idempotent")
    }
}
