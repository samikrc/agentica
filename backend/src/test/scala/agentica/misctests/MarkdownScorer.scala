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
 */
object MarkdownScorer
{
    /** Minimum character count for a page to be considered non-empty. */
    private val MinPageChars = 50

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
     *  against the first PDF found there.
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
