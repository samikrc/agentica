package agentica.misctests

import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.Paths

/** Offline tests for the text-fidelity metrics and the expected-fact checker in [[MarkdownScorer]]. */
class MarkdownScorerTest extends AnyFunSuite
{
    private val reference =
        """The policy covers outpatient consultations up to Rs. 13,433 per year.
          |Claims must be filed within 30 days. The co-payment is 10% of each bill.
          |Net return was -2.50% while total return was -0.43%.""".stripMargin

    /**
     *  Scores one page of Markdown against a reference.
     *  @param markdown  Transcription under test.
     *  @param ref       Reference text, defaulting to the shared fixture.
     *  @return          Token counts for the page.
     */
    private def score(markdown: String, ref: String = reference): TextCounts =
        MarkdownScorer.scoreTextPage(1, markdown, ref).counts

    test("an identical transcription scores perfectly") {
        val c = score(reference)
        assert(c.wordF1.contains(1.0) && c.numberRecall.contains(1.0) && c.numberPrecision.contains(1.0))
        assert(c.orderSimilarity.contains(1.0))
    }

    test("Markdown formatting, HTML breaks, and Unicode dashes are not penalised") {
        val markdown =
            """## The policy covers **outpatient consultations** up to Rs. 13,433 per year.
              |
              |- Claims must be filed within *30* days.<br>The co-payment is 10 % of each bill.
              |
              || Net return was | −2.50% | while total return was | –0.43% |
              ||---|---|---|---|""".stripMargin
        val c = score(markdown)
        assert(c.wordF1.contains(1.0), c)
        assert(c.numberRecall.contains(1.0) && c.numberPrecision.contains(1.0), c)
        assert(c.orderSimilarity.contains(1.0), c)
    }

    test("a dropped sentence lowers recall but not precision") {
        val c = score(reference.linesIterator.toList.patch(1, Nil, 1).mkString("\n"))
        assert(c.wordRecall.exists(_ < 0.8) && c.wordPrecision.contains(1.0))
        assert(c.numberRecall.exists(_ < 1.0) && c.numberPrecision.contains(1.0))
    }

    test("an invented sentence lowers precision but not recall") {
        val c = score(reference + "\nDental treatment is fully reimbursed without any limit of 50000.")
        assert(c.wordRecall.contains(1.0) && c.wordPrecision.exists(_ < 0.8))
        assert(c.numberRecall.contains(1.0) && c.numberPrecision.exists(_ < 1.0))
    }

    test("a lost minus sign is a number mismatch even though every word matches") {
        val page = MarkdownScorer.scoreTextPage(1, reference.replace("-0.43%", "0.43%"), reference)
        assert(page.counts.wordF1.contains(1.0))
        assert(page.counts.numberRecall.exists(_ < 1.0))
        assert(page.missingNumbers == List("-0.43%") && page.extraNumbers == List("0.43%"))
    }

    test("ranges and hyphenated labels are not read as negative numbers") {
        assert(MarkdownScorer.numberTokens("returns of 10-15% in Mar-20, (-3) and | -4.5 |") ==
            Vector("10", "15%", "20", "-3", "-4.5"))
        assert(MarkdownScorer.numberTokens("Rs. 13,433 and **-1.03%**") == Vector("13433", "-1.03%"))
    }

    test("reordered paragraphs keep word F1 but lower order similarity") {
        val lines = reference.linesIterator.toList
        val c = score((lines.last :: lines.init).mkString("\n"))
        assert(c.wordF1.contains(1.0) && c.numberRecall.contains(1.0))
        assert(c.orderSimilarity.exists(_ < 0.9))
    }

    test("repeated output is caught by precision and order similarity") {
        val c = score(reference + "\n" + reference + "\n" + reference)
        assert(c.wordRecall.contains(1.0) && c.wordPrecision.exists(_ < 0.4))
        assert(c.orderSimilarity.contains(0.0))
    }

    test("empty transcriptions and empty references are handled explicitly") {
        val empty = score("")
        assert(empty.wordRecall.contains(0.0) && empty.wordPrecision.isEmpty && empty.wordF1.contains(0.0))
        val blankRef = score("anything", ref = "   ")
        assert(blankRef.wordRecall.isEmpty && blankRef.wordF1.isEmpty && blankRef.orderSimilarity.isEmpty)
    }

    test("document totals are weighted by reference size, not averaged per page") {
        val doc = MarkdownScorer.scoreTextPages(List("one two three four", ""), List("one two three four", "five six"))
        assert(doc.pages.map(_.page) == List(1, 2))
        assert(doc.total.wordRecall.contains(4.0 / 6))
        intercept[IllegalArgumentException](MarkdownScorer.scoreTextPages(List("a"), List("a", "b")))
    }

    test("per-page PDF extraction matches the page count of each fixture") {
        for ((file, pages) <- List("IT Support Analyst - India.pdf" -> 2, "Even OPD Policy Document.pdf" -> 8,
                                  "ind_nifty50.pdf" -> 2))
        {
            val texts = MarkdownScorer.extractPDFPageTexts(Paths.get(getClass.getResource(s"/files/$file").toURI))
            assert(texts.size == pages, file)
            assert(texts.forall(_.trim.nonEmpty), file)
        }
    }

    // ─── Expected facts ───

    /**
     *  Builds a fact on page 1 with optional fields defaulted.
     *  @param row     Row label.
     *  @param value   Expected value.
     *  @param column  Column label.
     *  @param terms   Required terms.
     *  @param wrong   Known misreadings.
     *  @return        Fact for checking.
     */
    private def fact(row: Option[String] = None, value: Option[String] = None, column: Option[String] = None,
                     terms: List[String] = Nil, wrong: List[String] = Nil): ExpectedFact =
        ExpectedFact("f", 1, "Exhibit", "table", row, column, value, terms, wrong, "")

    /**
     *  Checks one fact against one page.
     *  @param f         Fact.
     *  @param markdown  Page Markdown.
     *  @return          Check result.
     */
    private def check(f: ExpectedFact, markdown: String): FactResult = MarkdownScorer.checkFact(f, markdown)

    private val rollingReturns =
        """| Investment Horizon | Percentage of total instances | | | Return Analysis | | |
          ||---|---|---|---|---|---|---|
          || | Negative returns | Positive returns | >15% CAGR | Return Attributes | | |
          || | <0% CAGR | >=0% CAGR | 10-15% CAGR | Min CAGR | Max CAGR | Median CAGR |
          || 10 years | 0.0% | 100.0% | 41.5% | 5.13% | 22.39% | 14.24% |
          || 5 years | 0.1% | 99.9% | 36.3% | 1.03% | 47.21% | 13.34% |""".stripMargin

    test("a flattened grouped header still identifies the column; a lost sign is a named misreading") {
        val neg = check(fact(Some("5 years"), Some("0.1%"), Some("Negative returns <0% CAGR")), rollingReturns)
        assert(neg.outcome == FactOutcome.PassCell, neg)
        val min = check(fact(Some("5 years"), Some("-1.03%"), Some("Min CAGR"), wrong = List("1.03%")), rollingReturns)
        assert(min.outcome == FactOutcome.Wrong && min.detail.contains("1.03%"), min)
        val max = check(fact(Some("10 years"), Some("22.39%"), Some("Max CAGR")), rollingReturns)
        assert(max.outcome == FactOutcome.PassCell, max)
    }

    test("similar column labels resolve to the closest header") {
        val table =
            """| Horizon | 10-15% CAGR | >15% CAGR | >=0% CAGR | <0% CAGR |
              ||---|---|---|---|---|
              || 3 years | 23.8% | 42.3% | 93.2% | 6.8% |""".stripMargin
        assert(check(fact(Some("3 years"), Some("42.3%"), Some(">15% CAGR")), table).outcome == FactOutcome.PassCell)
        assert(check(fact(Some("3 years"), Some("6.8%"), Some("Negative returns <0% CAGR")), table).outcome == FactOutcome.PassCell)
        assert(check(fact(Some("3 years"), Some("6.8%"), Some("&lt;0% CAGR")), table).outcome == FactOutcome.PassCell)
    }

    test("an empty header row is not mistaken for a separator") {
        val table =
            """|  |  |
              ||---|---|
              || Avg. Full Market Cap | 47.36% |
              || Avg. Daily Turnover | 28.35% |
              || Avg. Free-Float Market Cap | 57.35% |""".stripMargin
        assert(check(fact(Some("Avg. Full Market Cap"), Some("47.36%")), table).outcome == FactOutcome.PassCell)
        assert(check(fact(Some("Avg. Daily Turnover"), Some("28.35%")), table).outcome == FactOutcome.PassCell)
    }

    test("a table title merged into the header does not resolve the column to the label cell") {
        val table =
            """|| Nifty 50 Index Futures |
              ||---|
              || Average daily turnover in Rs. Cr |
              || FY 2024 - Q2 | 13,908 |
              || FY 2024 - Q3 | 14,705 |""".stripMargin
        val f = fact(Some("FY 2024 - Q3"), Some("14,705"), Some("Nifty 50 Index Futures"), wrong = List("13,908"))
        assert(check(f, table).outcome == FactOutcome.PassCell)
        assert(check(fact(Some("FY 2024 - Q3"), Some("6,796"), Some("Nifty 50 Index Options")), table).outcome == FactOutcome.Missing)
    }

    test("a chart transcribed as a transposed table passes the cell check") {
        val chart =
            """| Year | 2000 | 2001 | 2002 |
              ||---|---|---|---|
              || Return (%) | -13.36% | −15.05% | 5.34% |""".stripMargin
        assert(check(fact(Some("2001"), Some("-15.05%")), chart).outcome == FactOutcome.PassCell)
        val wrong = check(fact(Some("2001"), Some("-15.05%"), wrong = List("-13.36%")), chart.replace("−15.05%", "-13.36%"))
        assert(wrong.outcome == FactOutcome.Wrong, wrong)
    }

    test("a chart transcribed as a list passes the line check") {
        val md = "Calendar year returns:\n- 2000: -13.36%\n- 2001: -15.05%\n- 2002: 5.34%"
        assert(check(fact(Some("2001"), Some("-15.05%")), md).outcome == FactOutcome.PassLine)
        assert(check(fact(Some("2001"), Some("-15.05%")), md.replace("2001: -15.05%", "2001: 15.05%"))
            .outcome == FactOutcome.Missing)
    }

    test("dashes and zeros are not interchangeable, and a dash only counts inside a cell") {
        val table =
            """| Mutual Fund | ETFs AUM in Rs. Billion | Index Fund AUM in Rs. Billion |
              ||---|---|---|
              || Mirae Asset Mutual Fund | 23 | – |
              || Navi Mutual Fund | 0 | 17 |
              || Taurus Mutual Fund | - | **—** |""".stripMargin
        val col = Some("Index Fund AUM in Rs. Billion")
        assert(check(fact(Some("Mirae Asset Mutual Fund"), Some("-"), col), table).outcome == FactOutcome.PassCell)
        assert(check(fact(Some("Navi Mutual Fund"), Some("0"), Some("ETFs AUM in Rs. Billion")), table).outcome == FactOutcome.PassCell)
        val taurus = check(fact(Some("Taurus Mutual Fund"), Some("0"), col, wrong = List("-")), table)
        assert(taurus.outcome == FactOutcome.Wrong && taurus.detail.contains("misreading -"), taurus)
        val navi = check(fact(Some("Navi Mutual Fund"), Some("-"), Some("ETFs AUM in Rs. Billion"), wrong = List("0")), table)
        assert(navi.outcome == FactOutcome.Wrong, navi)
        assert(check(fact(Some("Mirae Asset Mutual Fund"), Some("-")), "Mirae Asset Mutual Fund: -").outcome == FactOutcome.Missing)
    }

    test("a value away from its row is misplaced; an absent row and value is missing") {
        val md = "| Sector | Weight |\n|---|---|\n| Energy | 12.87 |\n\nThe figure 9.28 appears in a footnote."
        val misplaced = check(fact(Some("Energy"), Some("9.28"), Some("All listed")), md)
        assert(misplaced.outcome == FactOutcome.Misplaced, misplaced)
        assert(check(fact(Some("Utilities"), Some("2.35")), md).outcome == FactOutcome.Missing)
    }

    test("with two similar charts, the matching line is found even when another line has the row label") {
        val md = "Futures\n- FY 2024 - Q2: 13,908\n\nOptions\n- FY 2024 - Q2: 8,448"
        val f = fact(Some("FY 2024 - Q2"), Some("8,448"), Some("Nifty 50 Index Options"), wrong = List("13,908"))
        assert(check(f, md).outcome == FactOutcome.PassLine)
        assert(check(f, md.replace("8,448", "8,400")).outcome == FactOutcome.Wrong)
    }

    test("page terms pass, partially pass, or are missing") {
        val f = fact(terms = List("Dot Com Crisis", "Sub-Prime Crisis", "Pandemic Crisis"))
        assert(check(f, "Annotations: **Dot-Com Crisis**, Sub Prime Crisis and Pandemic Crisis.").outcome == FactOutcome.PassTerms)
        val partial = check(f, "Annotations: Dot Com Crisis only.")
        assert(partial.outcome == FactOutcome.Partial && partial.detail.contains("Pandemic"), partial)
        assert(check(f, "A rising line.").outcome == FactOutcome.Missing)
    }

    test("row terms must stay with their row, in a row, a column, or a line") {
        val f = fact(Some("0% to 10%"), terms = List("2002", "2013", "2022"))
        assert(check(f, "| Range | Years |\n|---|---|\n| -10% to 0% | 2015 |\n| 0% to 10% | 2002, 2013, 2022 |")
            .outcome == FactOutcome.PassCell)
        assert(check(f, "| -10% to 0% | 0% to 10% |\n|---|---|\n| 2015 | 2002 |\n| | 2013 |\n| | 2022 |")
            .outcome == FactOutcome.PassCell)
        assert(check(f, "- 0% to 10%: 2002, 2013, 2022").outcome == FactOutcome.PassLine)
        assert(check(f, "- 0% to 10%: 2015\n- 10% to 20%: 2002, 2013, 2022").outcome == FactOutcome.Misplaced)
    }

    test("a value fact with terms needs the terms in the same row") {
        val f = fact(Some("KSM Mutual Funds"), Some("22"), Some("AUM (USD Mn)"), terms = List("ETF"))
        val header = "| Issuer Name | ETF/Index Fund | AUM (USD Mn) |\n|---|---|---|\n"
        assert(check(f, header + "| KSM Mutual Funds Ltd. | ETF | 22 |").outcome == FactOutcome.PassCell)
        val r = check(f, header + "| KSM Mutual Funds Ltd. | Index Fund | 22 |")
        assert(r.outcome == FactOutcome.Wrong && r.detail.contains("terms"), r)
    }

    test("the whitepaper facts file loads, and untranscribed pages count as missing") {
        val facts = MarkdownScorer.loadFacts(
            Paths.get(getClass.getResource("/files/nifty-50-whitepaper-2024.facts.json").toURI))
        assert(facts.size == 50 && facts.map(_.id).distinct.size == 50)
        assert(facts.forall(f => f.page >= 2 && f.page <= 14))
        val report = MarkdownScorer.checkFacts(List("page one only"), facts)
        assert(report.results.forall(_.outcome == FactOutcome.Missing) && report.strictPasses == 0)
        assert(report.results.head.detail.contains("not transcribed"))
    }
}
