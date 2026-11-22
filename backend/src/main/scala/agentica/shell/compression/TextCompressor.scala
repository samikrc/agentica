package agentica.shell.compression

import scala.util.matching.Regex

/**
 *  Compresses long prose documents by eliding whole sections while retaining
 *  headings, the opening/closing context, paragraphs matching
 *  [[Elision.ImportantRE]], and a representative of every distinct section
 *  kind ([[Elision.keepNonRedundant]]).
 *
 *  A section is a blank-line-separated block, a fenced code block (always one
 *  unit however many blank lines it holds), or a single line when the
 *  document has no blank lines at all. Sections that are themselves tables —
 *  the dominant shape in converted business documents — delegate their rows
 *  to [[TabularCompressor]], so tables embedded in prose get row-level
 *  elision rather than surviving verbatim or eliding whole.
 *
 *  Fail-closed: anything without enough sections to profit from elision, or
 *  whose compressed form is not smaller, passes through unchanged.
 */
object TextCompressor extends Compressor
{
    /** Sections kept from the front and back of the document. */
    private val KeepHead = 2
    private val KeepTail = 2

    /** Sections a query-aware pass may additionally keep. */
    private val QueryLimit = 12

    private val FenceRE: Regex = "^\\s{0,3}(`{3,}|~{3,})".r

    /** Content type reported in result metadata. */
    def contentType: String = "text"

    /**
     *  Compresses a prose document by section-level elision.
     *  @inheritdoc Compressor.compress
     */
    def compress(text: String, query: String = ""): Option[String] =
    {
        if (Elision.MarkerRE.findFirstIn(text).isDefined) return None
        val sections = splitSections(text)
        if (sections.size <= KeepHead + KeepTail + 2) return None

        val keep = Array.fill(sections.size)(false)
        for (i <- sections.indices)
        {
            if (i < KeepHead || i >= sections.size - KeepTail ||
                isHeading(sections(i)) ||
                Elision.ImportantRE.findFirstIn(sections(i)).isDefined)
            {
                keep(i) = true
            }
        }
        Elision.keepQueryRelevant(keep, sections, query, QueryLimit)
        Elision.keepNonRedundant(sections, keep)

        val shaped = Elision.shapedRuns(sections.size, keep)

        val rendered = shaped.map {
            case Elision.Shaped.Kept(i)    => compressTableSection(sections(i), query).getOrElse(sections(i))
            case Elision.Shaped.Marker(run) =>
                val summary = Elision.summarizeFields(
                    run.map(i => Elision.fieldsOfLine(sections(i))),
                    run.map(i => sections(i).length + 1).sum)
                Elision.markerText(run, "sections", summary)
        }
        // Rejoin with the input's own spacing: paragraph-separated docs get a
        // blank line between sections, line-mode docs a single separator —
        // each in the input's majority line ending.
        val crlf = Elision.majorityCRLF(text)
        val sep  =
            if (text.contains("\n\n") || text.contains("\r\n\r\n"))
                (if (crlf) "\r\n\r\n" else "\n\n")
            else (if (crlf) "\r\n" else "\n")
        val out  = rendered.mkString(sep)
        if (out.length >= text.length) None else Some(out)
    }

    /**
     *  Delegates a table-shaped section to the tabular selection so rows
     *  inside embedded tables elide individually.
     *
     *  @param section  One kept section.
     *  @param query    Optional relevance query.
     *  @return         The compressed section, or `None` when the section is
     *                  not a compressible table.
     */
    private def compressTableSection(section: String, query: String): Option[String] =
    {
        val (lines, _) = Elision.splitLines(section)
        if (lines.size < TabularCompressor.EmbeddedMinRows + 2) return None
        if (!lines.forall(l => l.trim.startsWith("|") && l.trim.endsWith("|") && l.trim.length > 1)) return None
        TabularCompressor.compressSection(section, query)
    }

    /**
     *  Cuts input into independently droppable units. A fenced code block is
     *  one unit however many blank lines it contains; otherwise sections are
     *  blank-line-separated paragraphs, or single lines when the document
     *  contains no blank lines (line-oriented payloads).
     */
    private def splitSections(text: String): Vector[String] =
    {
        val paragraphMode = text.contains("\n\n") || text.contains("\r\n\r\n")
        val (lines, _)    = Elision.splitLines(text)
        val sections      = Vector.newBuilder[String]
        val buf           = Vector.newBuilder[String]
        var openFence     = ""
        def flush(): Unit =
        {
            val joined = buf.result().mkString("\n").trim
            if (joined.nonEmpty) sections += joined
            buf.clear()
        }
        for (line <- lines)
        {
            val fence = FenceRE.findFirstIn(line).map(_.trim)
            if (openFence.nonEmpty)
            {
                buf += line
                if (fence.exists(f => f.head == openFence.head && f.length >= openFence.length))
                {
                    openFence = ""
                    flush()
                }
            }
            else if (fence.isDefined)
            {
                flush()
                openFence = fence.get
                buf += line
            }
            else if (line.trim.isEmpty) flush()
            else
            {
                buf += line
                if (!paragraphMode) flush()
            }
        }
        flush() // unterminated fence: the rest of the document is one unit
        sections.result()
    }

    /**
     *  A section is a heading when it starts with `#`, or is short and
     *  sentence-shaped without sentence punctuation.
     */
    private def isHeading(section: String): Boolean =
    {
        val t = section.trim
        if (t.startsWith("#")) return true
        if (t.length > 96 || t.exists(c => c == '.' || c == '!' || c == '?')) return false
        val words = t.split("\\s+").filter(_.nonEmpty)
        words.nonEmpty && words.length <= 8
    }
}
