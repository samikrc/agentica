package agentica.misctests

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 *  Scores VLM-generated Markdown against a PDFBox reference text extraction.
 *
 *  Metrics:
 *  - Word count ratio   : VLM word count ÷ reference word count (capped at 1.0)
 *  - Section coverage   : number of Markdown `#` headings (non-zero is good)
 *  - Table detection    : number of `|` table rows found
 *  - Non-empty page ratio: pages with ≥ 50 chars of output ÷ total pages
 *
 *  Per-page text fidelity ([[scoreTextPages]]), compared against each page's PDF text layer:
 *  - Word recall / precision / F1, counting repeated words (dropped vs. invented or duplicated text)
 *  - Signed-number recall / precision (e.g. `-0.43%` vs `0.43%` is a mismatch)
 *  - Order similarity: 1 − word error rate, so garbled, shuffled, or repeated passages score lower
 *
 *  Order similarity is only meaningful for prose: PDFBox can emit table cells and multi-column
 *  layouts in a different order from a correct transcription. Compare it with word F1 —
 *  high F1 with low order similarity indicates reordering rather than missing text.
 *
 *  Expected facts ([[checkFacts]]) check tables, charts, and diagrams against a hand-verified
 *  `<stem>.facts.json`: a value must sit in its row/column cell (strict) or on its row's line
 *  (lenient); misreadings, misplaced values, and missing rows are reported separately.
 */
object MarkdownScorer
{
    /** Minimum character count for a page to be considered non-empty. */
    private val MinPageChars = 50

    /** Word token: a run of letters or digits. */
    private val WordToken = """[\p{L}\p{N}]+""".r

    /**
     *  Number token. A leading `-` counts as a sign only at the start or after whitespace or
     *  markup (so `10-15%` and `Mar-20` are not negative). Thousands separators and the space
     *  before `%` are removed during normalisation.
     */
    private val NumberToken = """(?:(?<![^\s(\[|:*_"'/=])-)?\d[\d,]*(?:\.\d+)?(?:\s?%)?""".r

    /** HTML tags emitted by some VLMs inside Markdown tables, e.g. `<br>`. */
    private val HtmlTag = """</?[A-Za-z][^>]*>""".r

    /**
     *  Extracts plain text separately for each page of a PDF using PDFBox.
     *
     *  @param pdfPath  Absolute path to the PDF file.
     *  @return         Page texts in page order.
     *  @throws java.io.IOException if the PDF cannot be read.
     */
    def extractPDFPageTexts(pdfPath: Path): List[String] =
    {
        val doc = Loader.loadPDF(pdfPath.toFile)
        try
        {
            val stripper = PDFTextStripper()
            (1 to doc.getNumberOfPages).map { page =>
                stripper.setStartPage(page)
                stripper.setEndPage(page)
                stripper.getText(doc)
            }.toList
        }
        finally { doc.close() }
    }

    /**
     *  Normalises text for comparison: Unicode compatibility forms (e.g. ligatures), dash and
     *  minus variants to `-`, HTML tags and non-breaking spaces to spaces, and lowercase.
     *
     *  @param text  Raw text or Markdown.
     *  @return      Normalised text.
     */
    def normaliseForComparison(text: String): String =
        HtmlTag.replaceAllIn(java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC), " ")
            .replaceAll("[\u2212\u2010\u2011\u2012\u2013]", "-")
            .replaceAll("[\u2014\u00a0]", " ")
            .toLowerCase

    /**
     *  Splits text into ordered word tokens after [[normaliseForComparison]].
     *
     *  @param text  Raw text or Markdown.
     *  @return      Word tokens in reading order, including repeats.
     */
    def wordTokens(text: String): Vector[String] =
        WordToken.findAllIn(normaliseForComparison(text)).toVector

    /**
     *  Extracts normalised signed numbers, e.g. `"-2.50 %"` → `-2.50%`, `"13,433"` → `13433`.
     *
     *  @param text  Raw text or Markdown.
     *  @return      Number tokens in reading order, including repeats.
     */
    def numberTokens(text: String): Vector[String] =
        NumberToken.findAllIn(normaliseForComparison(text)).map(_.replace(",", "").replace(" ", "")).toVector

    /**
     *  Scores each transcribed page against the matching PDF text-layer page.
     *
     *  @param pages           Transcribed Markdown, one entry per page.
     *  @param referencePages  PDFBox text, one entry per page (see [[extractPDFPageTexts]]).
     *  @return                [[TextScore]] with per-page and aggregate counts.
     *  @throws IllegalArgumentException if the page counts differ.
     */
    def scoreTextPages(pages: List[String], referencePages: List[String]): TextScore =
    {
        require(pages.size == referencePages.size,
            s"Page count mismatch: ${pages.size} transcribed vs ${referencePages.size} reference")
        TextScore(pages.zip(referencePages).zipWithIndex.map { case ((markdown, reference), i) =>
            scoreTextPage(i + 1, markdown, reference)
        })
    }

    /**
     *  Scores one transcribed page against its reference text.
     *
     *  @param page       1-based page number.
     *  @param markdown   Transcribed Markdown for the page.
     *  @param reference  PDF text-layer content for the page.
     *  @return           [[PageTextScore]] including missing and extra numbers.
     */
    def scoreTextPage(page: Int, markdown: String, reference: String): PageTextScore =
    {
        val (refWords, outWords) = (wordTokens(reference), wordTokens(markdown))
        val (refNumbers, outNumbers) = (numberTokens(reference), numberTokens(markdown))
        val (matchedNumbers, missing, extra) = multisetOverlap(refNumbers, outNumbers)
        PageTextScore(page,
            TextCounts(refWords.size, outWords.size, multisetOverlap(refWords, outWords)._1,
                refNumbers.size, outNumbers.size, matchedNumbers, editDistance(refWords, outWords)),
            missing, extra)
    }

    /**
     *  Counts matches between two token multisets.
     *
     *  @param reference  Reference tokens.
     *  @param output     Output tokens.
     *  @return           (matched count, reference tokens missing from output, output tokens not in reference),
     *                    with repeats listed once per unmatched occurrence.
     */
    private def multisetOverlap(reference: Seq[String], output: Seq[String]): (Int, List[String], List[String]) =
    {
        val ref = reference.groupMapReduce(identity)(_ => 1)(_ + _)
        val out = output.groupMapReduce(identity)(_ => 1)(_ + _)
        def surplus(a: Map[String, Int], b: Map[String, Int]): List[String] =
            a.toList.sorted.flatMap { case (token, n) => List.fill((n - b.getOrElse(token, 0)).max(0))(token) }
        (ref.map { case (token, n) => n.min(out.getOrElse(token, 0)) }.sum, surplus(ref, out), surplus(out, ref))
    }

    /**
     *  Word-level Levenshtein distance (insertions, deletions, substitutions).
     *
     *  @param a  Reference tokens.
     *  @param b  Output tokens.
     *  @return   Minimum number of edits turning `a` into `b`.
     */
    private def editDistance(a: IndexedSeq[String], b: IndexedSeq[String]): Int =
    {
        var prev = Array.tabulate(b.length + 1)(identity)
        var cur  = Array.ofDim[Int](b.length + 1)
        for (i <- 1 to a.length)
        {
            cur(0) = i
            for (j <- 1 to b.length)
            {
                cur(j) = (prev(j) + 1).min(cur(j - 1) + 1).min(prev(j - 1) + (if (a(i - 1) == b(j - 1)) 0 else 1))
            }
            val swap = prev
            prev = cur
            cur = swap
        }
        prev(b.length)
    }

    // ─── Expected facts (tables, charts, diagrams) ───

    /** Label token: words, digits, and comparison symbols, so `<0% CAGR` differs from `>=0% CAGR`. */
    private val LabelToken = """[\p{L}\p{N}]+|[<>]=?""".r

    /** Markdown table separator cell, e.g. `---`, `:--:`. */
    private val SeparatorCell = """:?-{3,}:?""".r

    /** Minimum token F1 for a header to count as matching a fact's column label. */
    private val MinColumnMatch = 0.5

    /**
     *  Loads expected facts from a `<stem>.facts.json` file.
     *
     *  @param path  Facts file path.
     *  @return      Facts in file order.
     *  @throws java.io.IOException if the file cannot be read.
     *  @throws IllegalArgumentException if a fact has neither `value` nor `terms`, or ids repeat.
     */
    def loadFacts(path: Path): List[ExpectedFact] =
    {
        def strings(o: collection.Map[String, ujson.Value], key: String): List[String] =
            o.get(key).map(_.arr.map(_.str).toList).getOrElse(Nil)
        val facts = ujson.read(Files.readString(path))("facts").arr.toList.map { v =>
            val o = v.obj
            ExpectedFact(o("id").str, o("page").num.toInt, o.get("exhibit").map(_.str).getOrElse(""),
                o.get("type").map(_.str).getOrElse(""), o.get("row").map(_.str), o.get("column").map(_.str),
                o.get("value").map(_.str), strings(o, "terms"), strings(o, "wrong"), o.get("tests").map(_.str).getOrElse(""))
        }
        facts.find(f => f.value.isEmpty && f.terms.isEmpty).foreach(f =>
            throw IllegalArgumentException(s"Fact ${f.id} needs a value or terms"))
        require(facts.map(_.id).distinct.size == facts.size, s"Duplicate fact ids in $path")
        facts
    }

    /**
     *  Checks each expected fact against the transcription of its own page.
     *
     *  @param pages  Transcribed Markdown, one entry per page.
     *  @param facts  Expected facts (see [[loadFacts]]).
     *  @return       [[FactReport]] with one [[FactResult]] per fact, in fact order.
     */
    def checkFacts(pages: List[String], facts: List[ExpectedFact]): FactReport =
        FactReport(facts.map { f =>
            pages.lift(f.page - 1) match
            {
                case None           => FactResult(f, FactOutcome.Missing, s"page ${f.page} not transcribed")
                case Some(markdown) => checkFact(f, markdown)
            }
        })

    /**
     *  Checks one fact against one page of Markdown.
     *
     *  @param f         Expected fact.
     *  @param markdown  Transcription of the fact's page.
     *  @return          Outcome with detail and the observed cell or line, when found.
     */
    def checkFact(f: ExpectedFact, markdown: String): FactResult =
    {
        val tables = parseTables(markdown)
        val lines  = markdown.linesIterator.map(_.trim).filter(_.nonEmpty).toList
        (f.value, f.row) match
        {
            case (Some(value), Some(row)) => checkValue(f, value, row, tables, lines, markdown)
            case (None, Some(row))        => checkRowTerms(f, row, tables, lines, markdown)
            case _                        => checkPageTerms(f, markdown)
        }
    }

    /**
     *  A parsed Markdown table.
     *
     *  @param header  Column header text, joining the header row with any following non-data rows.
     *  @param rows    Data rows as cell lists.
     */
    private case class MarkdownTable(header: Vector[String], rows: List[Vector[String]])

    /**
     *  Parses the Markdown tables on a page. Rows after the header with no numeric or dash cell
     *  beyond the first are treated as further header rows, which keeps flattened grouped
     *  headers attached to their columns.
     *
     *  @param markdown  Page Markdown.
     *  @return          Tables in page order.
     */
    private def parseTables(markdown: String): List[MarkdownTable] =
    {
        def cells(line: String): Vector[String] =
            line.trim.stripPrefix("|").stripSuffix("|").split("\\|", -1).map(_.trim).toVector
        def isSeparator(row: Vector[String]): Boolean =
            row.exists(c => SeparatorCell.matches(c.replace(" ", ""))) &&
                row.forall(c => c.isEmpty || SeparatorCell.matches(c.replace(" ", "")))
        def valueLike(cell: String): Boolean =
            cell.nonEmpty && WordToken.findAllIn(normaliseForComparison(NumberToken.replaceAllIn(cell, " ")))
                .forall(t => t.forall(_.isDigit))
        def isData(row: Vector[String]): Boolean =
            row.drop(1).exists(c => valueLike(c) || isDash(c))
        val blocks = markdown.linesIterator.foldLeft(List(List.empty[String])) { (acc, line) =>
            if (line.count(_ == '|') >= 2) (line :: acc.head) :: acc.tail
            else if (acc.head.isEmpty) acc else Nil :: acc
        }.map(_.reverse).reverse.filter(_.nonEmpty)
        blocks.flatMap { block =>
            block.map(cells).filterNot(isSeparator) match
            {
                case Nil => None
                case first :: rest =>
                    val (extraHeader, data) = rest.span(r => !isData(r))
                    val width = (first :: rest).map(_.size).max
                    Some(MarkdownTable(
                        Vector.tabulate(width)(j => (first :: extraHeader).flatMap(_.lift(j)).mkString(" ").trim),
                        data))
            }
        }
    }

    /**
     *  Normalised label tokens.
     *
     *  @param text  Label or cell text.
     *  @return      Lowercase tokens including comparison symbols.
     */
    private def labelTokens(text: String): Vector[String] =
        LabelToken.findAllIn(normaliseForComparison(
            text.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replaceAll("[\u2018\u2019]", "'")))
            .toVector

    /**
     *  Whether `text` contains the label's tokens as a contiguous sequence.
     *
     *  @param text   Cell, line, or page text.
     *  @param label  Row label or term.
     *  @return       True when every label token appears, in order and adjacent.
     */
    private def containsLabel(text: String, label: String): Boolean =
    {
        val needle = labelTokens(label)
        needle.nonEmpty && labelTokens(text).containsSlice(needle)
    }

    /**
     *  Token F1 between a column label and a header.
     *
     *  @param label   Fact column label.
     *  @param header  Table header text.
     *  @return        F1 in [0, 1].
     */
    private def headerMatch(label: String, header: String): Double =
    {
        val (l, h) = (labelTokens(label).toSet, labelTokens(header).toSet)
        val common = l.intersect(h).size.toDouble
        if (common == 0) 0.0 else 2 * common / (l.size + h.size)
    }

    /**
     *  Index of the uniquely best-matching header, if it clears [[MinColumnMatch]].
     *
     *  @param label    Fact column label.
     *  @param headers  Candidate header texts.
     *  @return         Column index, or None when no header matches or the best match is tied.
     */
    private def bestColumn(label: String, headers: Seq[String]): Option[Int] =
    {
        val scored = headers.zipWithIndex.map { case (h, i) => (headerMatch(label, h), i) }.sortBy(-_._1)
        scored.headOption.filter { case (s, _) =>
            s >= MinColumnMatch && !scored.lift(1).exists(other => other._1 >= s - 1e-9)
        }.map(_._2)
    }

    /**
     *  Parses a number token, ignoring `%` and trailing zeros but keeping the sign.
     *
     *  @param token  Output of [[numberTokens]].
     *  @return       Numeric value, or None if not a number.
     */
    private def numericValue(token: String): Option[BigDecimal] =
        scala.util.Try(BigDecimal(token.stripSuffix("%"))).toOption

    /**
     *  Whether a cell consists only of a dash (hyphen, en/em dash, or minus), ignoring emphasis markers.
     *
     *  @param cell  Table cell text.
     *  @return      True for a dash cell.
     */
    private def isDash(cell: String): Boolean =
        normaliseForComparison(cell.replace('\u2014', '-')).replaceAll("[*_`\\s]", "") == "-"

    /**
     *  Whether a cell or line contains the expected value. A dash must be the whole cell.
     *
     *  @param text    Cell or line text.
     *  @param value   Expected value as printed.
     *  @param isCell  True when `text` is a single table cell.
     *  @return        True on a match.
     */
    private def containsValue(text: String, value: String, isCell: Boolean): Boolean =
        if (value.trim == "-") isCell && isDash(text)
        else
        {
            val expected = numberTokens(value).headOption.flatMap(numericValue)
            expected.exists(e => numberTokens(text).flatMap(numericValue).contains(e))
        }

    /**
     *  Finds table cells for a row and optional column, in normal and transposed orientation.
     *
     *  @param tables  Parsed page tables.
     *  @param row     Row (category) label.
     *  @param column  Optional column (series) label.
     *  @return        Candidate cells with the full row they came from.
     */
    private def candidateCells(tables: List[MarkdownTable], row: String, column: Option[String]): List[(String, String)] =
        tables.flatMap { t =>
            val normal = t.rows.filter(_.exists(containsLabel(_, row))).flatMap { r =>
                val context = r.mkString(" | ")
                column match
                {
                    case Some(c) => bestColumn(c, t.header).toList.flatMap { j =>
                        // A table-title header merged into column 0 resolves to the row-label
                        // cell itself; then the value can only be in the row's other cells.
                        if (r.lift(j).exists(containsLabel(_, row))) r.filterNot(containsLabel(_, row)).map(_ -> context)
                        else r.lift(j).map(_ -> context).toList
                    }
                    case None    => r.filterNot(containsLabel(_, row)).map(_ -> context).toList
                }
            }
            val transposed = t.header.zipWithIndex.filter { case (h, _) => containsLabel(h, row) }.flatMap { case (_, j) =>
                column match
                {
                    case Some(c) => bestColumn(c, t.rows.map(_.headOption.getOrElse("")))
                                      .flatMap(t.rows.lift).flatMap(r => r.lift(j).map(_ -> r.mkString(" | "))).toList
                    case None    => t.rows.flatMap(r => r.lift(j).map(_ -> r.mkString(" | ")))
                }
            }
            normal ++ transposed
        }

    /**
     *  Grades a value fact: strict cell match, then same-line match, then wrong/misplaced/missing.
     *
     *  @param f         Fact being checked.
     *  @param value     Expected value.
     *  @param row       Row label.
     *  @param tables    Parsed page tables.
     *  @param lines     Non-empty page lines.
     *  @param markdown  Full page Markdown.
     *  @return          Graded result.
     */
    private def checkValue(f: ExpectedFact, value: String, row: String, tables: List[MarkdownTable],
                           lines: List[String], markdown: String): FactResult =
    {
        def termsOK(context: String): Boolean = f.terms.forall(containsLabel(context, _))
        def wrongIn(text: String, isCell: Boolean): Option[String] = f.wrong.find(containsValue(text, _, isCell))
        val cells = candidateCells(tables, row, f.column)
        cells.find { case (cell, context) => containsValue(cell, value, isCell = true) && termsOK(context) } match
        {
            case Some((cell, _)) => FactResult(f, FactOutcome.PassCell, "", Some(cell))
            case None if cells.nonEmpty =>
                val (cell, context) = cells.head
                val named = wrongIn(cell, isCell = true).map(w => s"matches known misreading $w").getOrElse("unexpected value")
                val elsewhere = if (containsValue(context, value, isCell = false)) "; expected value is elsewhere in the row" else ""
                val terms = if (containsValue(cell, value, isCell = true)) "row is missing terms " + f.terms.mkString(", ") else named
                FactResult(f, FactOutcome.Wrong, terms + elsewhere, Some(s"cell '$cell' in row '$context'"))
            case None =>
                val rowLines = lines.filter(containsLabel(_, row))
                rowLines.find(l => containsValue(l, value, isCell = false) && termsOK(l)) match
                {
                    case Some(line) => FactResult(f, FactOutcome.PassLine, "", Some(line))
                    case None =>
                        rowLines.flatMap(l => wrongIn(l, isCell = false).map(_ -> l)).headOption match
                        {
                            case Some((w, line)) => FactResult(f, FactOutcome.Wrong, s"matches known misreading $w", Some(line))
                            case None if value.trim != "-" && containsValue(markdown, value, isCell = false) =>
                                FactResult(f, FactOutcome.Misplaced,
                                    if (rowLines.isEmpty) "value on page but row label not found" else "value on page but not with its row",
                                    rowLines.headOption)
                            case None =>
                                FactResult(f, FactOutcome.Missing,
                                    if (rowLines.isEmpty) "row label not found" else "row found without the value", rowLines.headOption)
                        }
                }
        }
    }

    /**
     *  Grades a fact whose terms must appear together with a row label (e.g. years within a histogram bin).
     *
     *  @param f         Fact being checked.
     *  @param row       Row label.
     *  @param tables    Parsed page tables.
     *  @param lines     Non-empty page lines.
     *  @param markdown  Full page Markdown.
     *  @return          Graded result.
     */
    private def checkRowTerms(f: ExpectedFact, row: String, tables: List[MarkdownTable],
                              lines: List[String], markdown: String): FactResult =
    {
        val columnContexts = tables.flatMap { t =>
            t.header.zipWithIndex.filter { case (h, _) => containsLabel(h, row) }
                .map { case (h, j) => (h :: t.rows.flatMap(_.lift(j))).mkString(" | ") }
        }
        val rowContexts = tables.flatMap(_.rows.filter(_.exists(containsLabel(_, row))).map(_.mkString(" | ")))
        def all(context: String): Boolean = f.terms.forall(containsLabel(context, _))
        (rowContexts ++ columnContexts).find(all) match
        {
            case Some(c) => FactResult(f, FactOutcome.PassCell, "", Some(c))
            case None => lines.filter(containsLabel(_, row)).find(all) match
            {
                case Some(l) => FactResult(f, FactOutcome.PassLine, "", Some(l))
                case None =>
                    val found = f.terms.count(containsLabel(markdown, _))
                    val outcome = if (found == f.terms.size) FactOutcome.Misplaced
                                  else if (found == 0) FactOutcome.Missing else FactOutcome.Partial
                    FactResult(f, outcome, s"$found/${f.terms.size} terms on page, not grouped with '$row'")
            }
        }
    }

    /**
     *  Grades a fact whose terms only need to appear somewhere on the page.
     *
     *  @param f         Fact being checked.
     *  @param markdown  Full page Markdown.
     *  @return          Graded result.
     */
    private def checkPageTerms(f: ExpectedFact, markdown: String): FactResult =
    {
        val missing = f.terms.filterNot(containsLabel(markdown, _))
        val outcome = if (missing.isEmpty) FactOutcome.PassTerms
                      else if (missing.size == f.terms.size) FactOutcome.Missing else FactOutcome.Partial
        FactResult(f, outcome, if (missing.isEmpty) "" else s"missing: ${missing.mkString(", ")}")
    }

    /**
     *  Extracts plain text from all pages of a PDF using PDFBox.
     *
     *  @param pdfPath  Absolute path to the PDF file.
     *  @return         Raw text content of the entire document.
     */
    def extractPDFText(pdfPath: Path): String =
    {
        val doc = Loader.loadPDF(pdfPath.toFile)
        try
        {
            val stripper = PDFTextStripper()
            stripper.getText(doc)
        }
        finally { doc.close() }
    }

    /**
     *  Tokenises text into lowercase words, stripping punctuation and Markdown syntax.
     *
     *  @param text  Raw text or Markdown string.
     *  @return      Set of normalised word tokens.
     */
    private def tokenise(text: String): Set[String] =
        text
            .toLowerCase
            .replaceAll("[#|*`>_~\\[\\]()!]", " ")
            .replaceAll("[^a-z0-9 ]", " ")
            .split("\\s+")
            .filter(_.length > 1)
            .toSet

    /**
     *  Counts words (whitespace-separated tokens, length > 1) in a string.
     *
     *  @param text  Input string.
     *  @return      Word count.
     */
    private def wordCount(text: String): Int =
        text.split("\\s+").count(_.length > 1)

    /**
     *  Counts Markdown heading lines (lines starting with one or more `#`).
     *
     *  @param markdown  Markdown string.
     *  @return          Number of heading lines.
     */
    private def headingCount(markdown: String): Int =
        markdown.linesIterator.count(_.startsWith("#"))

    /**
     *  Counts Markdown table rows (lines containing at least two `|` characters).
     *
     *  @param markdown  Markdown string.
     *  @return          Number of table rows.
     */
    private def tableRowCount(markdown: String): Int =
        markdown.linesIterator.count(line => line.count(_ == '|') >= 2)

    /**
     *  Standalone entry point: scores all `*.md` files in the given directory
     *  against the first PDF found there, then re-scores any `*.pages.json` files
     *  (written by [[VLMCompareTest]]) page by page without calling a model. When a
     *  `*.facts.json` file is present, each page list is also fact-checked.
     *
     *  Usage: `java ... agentica.misctests.MarkdownScorer /tmp/vlm-compare-xxx`
     *
     *  @param args  args(0) is the directory path to scan.
     */
    def main(args: Array[String]): Unit =
    {
        if (args.isEmpty)
        {
            println("Usage: MarkdownScorer <directory>")
            sys.exit(1)
        }

        val dir = Paths.get(args(0))

        val pdfs = Files.list(dir).iterator().asScala
            .filter(_.getFileName.toString.toLowerCase.endsWith(".pdf"))
            .toList
        if (pdfs.isEmpty)
        {
            println(s"No PDF found in $dir")
            sys.exit(1)
        }

        val pdfPath       = pdfs.head
        val referenceText = extractPDFText(pdfPath)
        val refWordCount  = referenceText.split("\\s+").count(_.length > 1)
        println(s"PDF            : $pdfPath")
        println(s"Reference words: $refWordCount")

        // Infer page count from separator count in any markdown, or default to 1
        val mdFiles = Files.list(dir).iterator().asScala
            .filter(_.getFileName.toString.toLowerCase.endsWith(".md"))
            .toList
            .sortBy(_.getFileName.toString)

        if (mdFiles.isEmpty)
        {
            println("No markdown files found.")
            sys.exit(0)
        }

        // Use first md to estimate page count
        val firstMd    = Files.readString(mdFiles.head)
        val pageCount  = firstMd.split("\n\n---\n\n").length

        println(s"Pages (est.)   : $pageCount")
        println(s"Markdown files : ${mdFiles.size}\n")

        for (mdFile <- mdFiles)
        {
            val label    = mdFile.getFileName.toString
            val markdown = Files.readString(mdFile)
            println(s"=== $label ===")
            println(score(markdown, referenceText, pageCount).pretty)
            println()
        }

        val referencePages = extractPDFPageTexts(pdfPath)
        val pageFiles = Files.list(dir).iterator().asScala
            .filter(_.getFileName.toString.endsWith(".pages.json"))
            .toList
            .sortBy(_.getFileName.toString)
        val facts = Files.list(dir).iterator().asScala
            .find(_.getFileName.toString.endsWith(".facts.json")).map(loadFacts)
        for (pageFile <- pageFiles)
        {
            val pages = ujson.read(Files.readString(pageFile)).arr.map(_.str).toList
            println(s"=== ${pageFile.getFileName} ===")
            println(scoreTextPages(pages, referencePages).pretty)
            facts.foreach(fs => println(checkFacts(pages, fs).pretty))
            println()
        }
    }

    /**
     *  Computes a [[MarkdownScore]] for a VLM-generated Markdown string against
     *  the reference text extracted from the source PDF.
     *
     *  @param markdown      VLM-generated Markdown for the full document.
     *  @param referenceText Plain text extracted from the PDF via PDFBox.
     *  @param pageCount     Total number of pages in the document.
     *  @return              [[MarkdownScore]] with all sub-metrics populated.
     */
    def score(markdown: String, referenceText: String, pageCount: Int): MarkdownScore =
    {
        val refWords = wordCount(referenceText)
        val vlmWords = wordCount(markdown)

        val wordRatio = if (refWords == 0) 0.0
                        else (vlmWords.toDouble / refWords).min(1.0)

        val refTokens = tokenise(referenceText)
        val vlmTokens = tokenise(markdown)
        val overlap   = if (refTokens.isEmpty) 0.0
                        else vlmTokens.intersect(refTokens).size.toDouble / refTokens.size

        val headings  = headingCount(markdown)
        val tableRows = tableRowCount(markdown)

        val pages     = markdown.split("\n\n---\n\n")
        val nonEmptyPages = pages.count(_.trim.length >= MinPageChars)
        val nonEmptyRatio = if (pageCount == 0) 0.0
                            else nonEmptyPages.toDouble / pageCount

        MarkdownScore(
            wordCountRatio  = wordRatio,
            tokenOverlap    = overlap,
            headingCount    = headings,
            tableRowCount   = tableRows,
            nonEmptyPages   = nonEmptyPages,
            totalPages      = pageCount,
            nonEmptyRatio   = nonEmptyRatio,
            refWordCount    = refWords,
            vlmWordCount    = vlmWords
        )
    }
}

/**
 *  Scored result for a single VLM Markdown output.
 *
 *  @param wordCountRatio  VLM word count ÷ reference word count (0–1, higher is better).
 *  @param tokenOverlap    Fraction of reference vocabulary tokens present in VLM output (0–1).
 *  @param headingCount    Number of Markdown `#` headings found (0 = no structure).
 *  @param tableRowCount   Number of `|` table rows found.
 *  @param nonEmptyPages   Pages with ≥ 50 chars of output.
 *  @param totalPages      Total pages in the document.
 *  @param nonEmptyRatio   `nonEmptyPages / totalPages` (0–1).
 *  @param refWordCount    Word count of the PDFBox reference text.
 *  @param vlmWordCount    Word count of the VLM Markdown output.
 */
case class MarkdownScore(
    wordCountRatio: Double,
    tokenOverlap:   Double,
    headingCount:   Int,
    tableRowCount:  Int,
    nonEmptyPages:  Int,
    totalPages:     Int,
    nonEmptyRatio:  Double,
    refWordCount:   Int,
    vlmWordCount:   Int
)
{
    /**
     *  Formats the score as a human-readable multi-line string.
     *
     *  @return  Formatted score summary.
     */
    def pretty: String =
        f"""  Score summary:
  ├ Word count ratio  : $wordCountRatio%.2f  (VLM $vlmWordCount%d words vs ref $refWordCount%d words)
  ├ Token overlap     : $tokenOverlap%.2f  (shared vocabulary fraction)
  ├ Headings found    : $headingCount%d
  ├ Table rows found  : $tableRowCount%d
  └ Non-empty pages   : $nonEmptyPages%d / $totalPages%d  (${"%.0f".format(nonEmptyRatio * 100)}%%)"""
}

/**
 *  Raw token counts behind the text-fidelity metrics. Counts add across pages and documents,
 *  so aggregate ratios are weighted by reference size rather than averaged per page.
 *
 *  @param refWords        Word tokens in the reference.
 *  @param outWords        Word tokens in the transcription.
 *  @param matchedWords    Word tokens present in both, counting repeats.
 *  @param refNumbers      Number tokens in the reference.
 *  @param outNumbers      Number tokens in the transcription.
 *  @param matchedNumbers  Number tokens present in both, counting repeats.
 *  @param wordEdits       Word-level edit distance from reference to transcription.
 */
case class TextCounts(
    refWords:       Int,
    outWords:       Int,
    matchedWords:   Int,
    refNumbers:     Int,
    outNumbers:     Int,
    matchedNumbers: Int,
    wordEdits:      Int
)
{
    /**
     *  Divides two counts, leaving the ratio undefined when the denominator is zero.
     *
     *  @param n  Numerator.
     *  @param d  Denominator.
     *  @return   `n / d`, or None when `d` is zero.
     */
    private def ratio(n: Int, d: Int): Option[Double] = Option.when(d > 0)(n.toDouble / d)

    /**
     *  Fraction of reference words reproduced in the transcription.
     *
     *  @return  Recall in [0, 1], or None when the reference has no words.
     */
    def wordRecall: Option[Double] = ratio(matchedWords, refWords)

    /**
     *  Fraction of transcribed words found in the reference.
     *
     *  @return  Precision in [0, 1], or None when the transcription has no words.
     */
    def wordPrecision: Option[Double] = ratio(matchedWords, outWords)

    /**
     *  Harmonic mean of word recall and precision; an empty transcription scores 0.
     *
     *  @return  F1 in [0, 1], or None when the reference has no words.
     */
    def wordF1: Option[Double] = wordRecall.map { r =>
        val p = wordPrecision.getOrElse(0.0)
        if (r + p == 0) 0.0 else 2 * r * p / (r + p)
    }

    /**
     *  Fraction of reference numbers reproduced exactly, including sign and percent.
     *
     *  @return  Recall in [0, 1], or None when the reference has no numbers.
     */
    def numberRecall: Option[Double] = ratio(matchedNumbers, refNumbers)

    /**
     *  Fraction of transcribed numbers found in the reference.
     *
     *  @return  Precision in [0, 1], or None when the transcription has no numbers.
     */
    def numberPrecision: Option[Double] = ratio(matchedNumbers, outNumbers)

    /**
     *  One minus word error rate, floored at zero.
     *
     *  @return  Similarity in [0, 1], or None when the reference has no words.
     */
    def orderSimilarity: Option[Double] = ratio(wordEdits, refWords).map(wer => (1.0 - wer).max(0.0))

    /**
     *  Sums two sets of counts.
     *
     *  @param o  Counts to add.
     *  @return   Combined counts.
     */
    def +(o: TextCounts): TextCounts = TextCounts(refWords + o.refWords, outWords + o.outWords,
        matchedWords + o.matchedWords, refNumbers + o.refNumbers, outNumbers + o.outNumbers,
        matchedNumbers + o.matchedNumbers, wordEdits + o.wordEdits)
}

object TextCounts
{
    /** Counts for an empty set of pages. */
    val Zero: TextCounts = TextCounts(0, 0, 0, 0, 0, 0, 0)

    /**
     *  Formats an optional ratio with two decimals.
     *
     *  @param value  Ratio to format.
     *  @return       Formatted value, or `n/a`.
     */
    def fmt(value: Option[Double]): String = value.map(v => f"$v%.2f").getOrElse("n/a")
}

/**
 *  Text-fidelity score for one page.
 *
 *  @param page            1-based page number.
 *  @param counts          Token counts for the page.
 *  @param missingNumbers  Reference numbers absent from the transcription (one entry per occurrence).
 *  @param extraNumbers    Transcribed numbers absent from the reference (one entry per occurrence).
 */
case class PageTextScore(page: Int, counts: TextCounts, missingNumbers: List[String], extraNumbers: List[String])

/**
 *  Text-fidelity score for a document.
 *
 *  @param pages  Per-page scores in page order.
 */
case class TextScore(pages: List[PageTextScore])
{
    /**
     *  Aggregates counts over all pages.
     *
     *  @return  Summed [[TextCounts]].
     */
    def total: TextCounts = pages.map(_.counts).foldLeft(TextCounts.Zero)(_ + _)

    /**
     *  Formats aggregate and per-page metrics, listing up to eight missing/extra numbers per page.
     *
     *  @return  Multi-line summary.
     */
    def pretty: String =
    {
        import TextCounts.fmt
        def line(label: String, c: TextCounts): String =
            s"$label word F1 ${fmt(c.wordF1)} (R ${fmt(c.wordRecall)} P ${fmt(c.wordPrecision)})" +
            s" | numbers R ${fmt(c.numberRecall)} P ${fmt(c.numberPrecision)} (${c.matchedNumbers}/${c.refNumbers})" +
            s" | order ${fmt(c.orderSimilarity)}"
        def sample(xs: List[String]): String =
            if (xs.isEmpty) "-" else xs.take(8).mkString(", ") + (if (xs.size > 8) s" … (+${xs.size - 8})" else "")
        (line("  Text fidelity:", total) :: pages.flatMap { p =>
            List(line(f"    p${p.page}%-3d", p.counts),
                s"         missing numbers: ${sample(p.missingNumbers)} | extra numbers: ${sample(p.extraNumbers)}")
        }).mkString("\n")
    }
}

/**
 *  One expected fact from a `<stem>.facts.json` file.
 *
 *  @param id       Unique identifier.
 *  @param page     1-based PDF page the fact must appear on.
 *  @param exhibit  Exhibit label, for reporting.
 *  @param kind     `table`, `chart`, `diagram`, or `footnote` (the JSON `type` field).
 *  @param row      Row or chart-category label, when the value belongs to one.
 *  @param column   Column or chart-series label, when the value belongs to one.
 *  @param value    Expected value as printed; `-` for a dash cell.
 *  @param terms    Terms that must all appear (on the page, or with the row when `row` is set).
 *  @param wrong    Values indicating a specific misreading.
 *  @param tests    What the fact is meant to test.
 */
case class ExpectedFact(
    id:      String,
    page:    Int,
    exhibit: String,
    kind:    String,
    row:     Option[String],
    column:  Option[String],
    value:   Option[String],
    terms:   List[String],
    wrong:   List[String],
    tests:   String
)

/** Outcome of checking one [[ExpectedFact]]. */
enum FactOutcome
{
    /** Value found in the expected table cell (or terms grouped with their row in a table). */
    case PassCell
    /** Value found on the same line as its row label, but not in an identifiable table cell. */
    case PassLine
    /** All page-level terms found. */
    case PassTerms
    /** Some, but not all, terms found. */
    case Partial
    /** The row or cell was found with a different value. */
    case Wrong
    /** The value or terms are on the page but not with their row. */
    case Misplaced
    /** Neither the value nor its row could be found. */
    case Missing

    /**
     *  Whether this outcome passes the strict check (correct cell, or all page-level terms).
     *
     *  @return  True for [[PassCell]] and [[PassTerms]].
     */
    def strictPass: Boolean = this == PassCell || this == PassTerms

    /**
     *  Whether this outcome passes the lenient check (also accepts a same-line match).
     *
     *  @return  True for any pass outcome.
     */
    def lenientPass: Boolean = strictPass || this == PassLine

    /**
     *  Short label for reports.
     *
     *  @return  Kebab-case outcome name.
     */
    def label: String = this match
    {
        case PassCell  => "pass-cell"
        case PassLine  => "pass-line"
        case PassTerms => "pass-terms"
        case Partial   => "partial"
        case Wrong     => "wrong"
        case Misplaced => "misplaced"
        case Missing   => "missing"
    }
}

/**
 *  Result of checking one fact.
 *
 *  @param fact      The fact checked.
 *  @param outcome   Graded outcome.
 *  @param detail    Explanation for non-pass outcomes.
 *  @param observed  The cell, row, or line the checker based its decision on, when found.
 */
case class FactResult(fact: ExpectedFact, outcome: FactOutcome, detail: String = "", observed: Option[String] = None)

/**
 *  Fact-check results for one transcription.
 *
 *  @param results  One result per fact, in fact order.
 */
case class FactReport(results: List[FactResult])
{
    /**
     *  Number of facts passing the strict check.
     *
     *  @return  Strict pass count.
     */
    def strictPasses: Int = results.count(_.outcome.strictPass)

    /**
     *  Number of facts passing the lenient check.
     *
     *  @return  Lenient pass count.
     */
    def lenientPasses: Int = results.count(_.outcome.lenientPass)

    /**
     *  Strict and lenient pass counts per fact type.
     *
     *  @return  Map from type to (strict, lenient, total), in first-seen order.
     */
    def byType: List[(String, (Int, Int, Int))] =
        results.map(_.fact.kind).distinct.map { kind =>
            val rs = results.filter(_.fact.kind == kind)
            kind -> (rs.count(_.outcome.strictPass), rs.count(_.outcome.lenientPass), rs.size)
        }

    /**
     *  Counts of each outcome, omitting zero counts.
     *
     *  @return  Outcome label and count pairs, in outcome order.
     */
    def outcomeCounts: List[(String, Int)] =
        FactOutcome.values.toList.map(o => o.label -> results.count(_.outcome == o)).filter(_._2 > 0)

    /**
     *  Formats totals, per-type counts, and every result that is not a strict pass.
     *
     *  @return  Multi-line summary.
     */
    def pretty: String =
    {
        val n = results.size
        val header = s"  Facts: strict $strictPasses/$n, lenient $lenientPasses/$n | " +
            outcomeCounts.map { case (l, c) => s"$l $c" }.mkString(", ")
        val types = "    by type: " + byType.map { case (k, (s, l, t)) => s"$k $s/$l/$t" }.mkString(", ") +
            "  (strict/lenient/total)"
        val issues = results.filterNot(_.outcome.strictPass).map { r =>
            s"    ${r.outcome.label.padTo(10, ' ')} ${r.fact.id}" +
            (if (r.detail.nonEmpty) s" — ${r.detail}" else "") +
            r.observed.map(o => s"\n               observed: ${o.take(160)}").getOrElse("")
        }
        (header :: types :: issues).mkString("\n")
    }
}
