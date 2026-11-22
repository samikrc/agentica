package agentica.shell.compression

import scala.util.matching.Regex

/**
 *  Compresses delimited tables — CSV, TSV, and Markdown tables — by eliding
 *  rows that carry no distinctive signal.
 *
 *  Kept rows: the header, the first and last few data rows, rows matching
 *  [[Elision.ImportantRe]], rows holding a numeric column's extrema,
 *  query-relevant rows, and a representative of every distinct row shape
 *  ([[Elision.keepNonRedundant]]). Dropped runs collapse into in-table marker
 *  rows carrying [[Elision.summarizeFields]] facts about exactly those rows,
 *  so the output stays a valid table of the same kind.
 *
 *  Fail-closed: strict parsing only (uniform field counts, a real Markdown
 *  separator row, no quoted fields spanning lines), and the whole output must
 *  be smaller than the input or the original passes through.
 */
object TabularCompressor extends Compressor
{
    /** Minimum data rows before a table is worth eliding. */
    private val MinRows = 12

    /** Data rows kept from the front and back of the table. */
    private val KeepFirst = 3
    private val KeepLast  = 2

    /** Rows a query-aware pass may additionally keep. */
    private val QueryLimit = 24

    private val SeparatorCellRe: Regex = ":?-{3,}:?".r

    /** Tables nested inside prose compress with a lower row threshold. */
    private[compression] val EmbeddedMinRows = 6

    /** Content type reported in result metadata. */
    def contentType: String = "tabular"

    /**
     *  Compresses a standalone table document.
     *  @inheritdoc Compressor.compress
     */
    def compress(text: String, query: String = ""): Option[String] =
        compressTable(text, query, MinRows)

    /**
     *  Compresses a table appearing as one section inside a larger document —
     *  the same selection with a lower row threshold.
     *
     *  @param text  Section text expected to be entirely a table.
     *  @param query Optional relevance query.
     *  @return      The compressed table text, or `None` when not compressible.
     */
    private[compression] def compressSection(text: String, query: String): Option[String] =
        compressTable(text, query, EmbeddedMinRows)

    // ── Core ────────────────────────────────────────────────────────────────

    /**
     *  Parses and compresses a table document, failing closed on any parse
     *  problem or when the compressed form is not smaller.
     *  @param text     Table text.
     *  @param query    Optional relevance query.
     *  @param minRows  Minimum data rows required before eliding.
     *  @return         The compressed table, or `None` to pass through.
     */
    private def compressTable(text: String, query: String, minRows: Int): Option[String] =
    {
        if (Elision.MarkerRe.findFirstIn(text).isDefined) return None
        parse(text) match
        {
            case None => None
            case Some(table) =>
                val rows = table.rows
                if (rows.size < minRows || rows.size <= KeepFirst + KeepLast + 1) return None

                val docs = rows.map(_.mkString(" "))
                val keep = Array.fill(rows.size)(false)
                for (i <- 0 until KeepFirst.min(rows.size)) keep(i) = true
                for (i <- (rows.size - KeepLast) until rows.size) keep(i) = true
                for (i <- rows.indices if Elision.ImportantRe.findFirstIn(docs(i)).isDefined) keep(i) = true
                Elision.keepNumericExtrema(rows, keep)
                Elision.keepQueryRelevant(keep, docs, query, QueryLimit)
                Elision.keepNonRedundant(docs, keep)

                val shaped = Elision.shapedRuns(rows.size, keep)

                val rendered = shaped.map {
                    case Elision.Shaped.Kept(i)    => table.renderRow(rows(i))
                    case Elision.Shaped.Marker(run) =>
                        val summary = Elision.summarizeFields(
                            run.map(i => rows(i).zip(table.header).map(_.swap)),
                            run.map(i => rows(i).mkString(",").length + 1).sum)
                        table.renderMarker(Elision.markerText(run, "rows", summary))
                }
                val headerText = table.renderHeader
                val bodyText   = rendered.mkString(table.lineSep)
                val out        = headerText + table.lineSep + bodyText +
                    (if (text.endsWith("\n")) table.lineSep else "")
                if (out.length >= text.length) None else Some(out)
        }
    }

    // ── Parsing ─────────────────────────────────────────────────────────────

    private enum Kind { case Markdown, Delimited }

    /**
     *  A parsed table plus its own re-encoder, so kept rows and marker rows
     *  render in the input's dialect.
     *
     *  @param header    Header cells (empty for a headerless delimited table).
     *  @param rows      Uniform-width data rows.
     *  @param kind      Detected table kind.
     *  @param delimiter Field delimiter for [[Kind.Delimited]].
     *  @param lineSep   Line separator matching the input's majority ending.
     */
    private case class Table(
        header:    Vector[String],
        rows:      Vector[Vector[String]],
        kind:      Kind,
        delimiter: Char,
        lineSep:   String
    )
    {
        /** Renders the header block (including a Markdown separator row). */
        def renderHeader: String =
        {
            if (kind == Kind.Markdown)
            {
                val headerLine  = "| " + header.mkString(" | ") + " |"
                val sepLine     = "| " + header.map(_ => "---").mkString(" | ") + " |"
                headerLine + lineSep + sepLine
            }
            else joinFields(header)
        }

        /** Renders one kept data row in the table's dialect. */
        def renderRow(row: Vector[String]): String =
        {
            if (kind == Kind.Markdown) "| " + row.mkString(" | ") + " |"
            else joinFields(row)
        }

        /** Renders an elision marker as a table row: text in the first cell, empties after. */
        def renderMarker(text: String): String =
            renderRow(Vector(text) ++ Vector.fill(math.max(0, width - 1))(""))

        private def width: Int = header.size.max(rows.map(_.size).max)

        /** Joins delimited fields, quoting only when the delimiter, a quote, or a newline demands it. */
        private def joinFields(row: Vector[String]): String =
            row.map { f =>
                if (f.contains(delimiter) || f.contains('"') || f.contains('\n'))
                    "\"" + f.replace("\"", "\"\"") + "\""
                else f
            }.mkString(delimiter.toString)
    }

    /**
     *  Strictly parses a table: every line a Markdown table row, or a
     *  uniform-width CSV/TSV document.
     *  @param text  Document text.
     *  @return      The parsed table, or `None` when not recognisably tabular.
     */
    private def parse(text: String): Option[Table] =
    {
        val (lines, _) = Elision.splitLines(text)
        if (lines.isEmpty) return None
        val lineSep = if (Elision.majorityCRLF(text)) "\r\n" else "\n"
        val clean   = lines.map(_.stripSuffix("\r"))
        if (clean.forall(l => l.trim.startsWith("|") && l.trim.endsWith("|") && l.trim.length > 1))
            parseMarkdown(clean, lineSep)
        else parseDelimited(clean, lineSep)
    }

    /** Splits a `| a | b |` line into trimmed cells; `\|` escapes stay inside their cell. */
    private def splitMarkdownRow(line: String): Option[Vector[String]] =
    {
        val inner = line.trim.stripPrefix("|")
        val cells = Vector.newBuilder[String]
        val start = new StringBuilder
        var closed = false
        var i = 0
        while (i < inner.length)
        {
            inner(i) match
            {
                case '\\' => i += 1; if (i < inner.length) start.append(inner(i))
                case '|'  =>
                    cells += start.result().trim
                    start.clear()
                    closed = (i == inner.length - 1)
                case c => start.append(c)
            }
            i += 1
        }
        if (closed) Some(cells.result()) else None
    }

    /**
     *  Parses a Markdown table: ≥3 `|…|` lines with a real separator row and
     *  uniform column counts.
     *  @param lines    Document lines.
     *  @param lineSep  Line separator matching the input's majority ending.
     *  @return         The parsed table, or `None`.
     */
    private def parseMarkdown(lines: Vector[String], lineSep: String): Option[Table] =
    {
        if (lines.size < 3) return None
        val parsed = lines.map(splitMarkdownRow)
        if (parsed.exists(_.isEmpty)) return None
        val rows   = parsed.flatten
        if (rows.head.size < 2) return None
        if (!rows(1).forall(c => SeparatorCellRe.matches(c))) return None
        if (rows.drop(2).exists(_.size != rows.head.size)) return None
        Some(Table(header = rows.head, rows = rows.drop(2),
            kind = Kind.Markdown, delimiter = '|', lineSep = lineSep))
    }

    /** Splits one delimited line honouring `"` quoting; unclosed quotes fail. */
    private def splitDelimited(line: String, delimiter: Char): Option[Vector[String]] =
    {
        val fields = Vector.newBuilder[String]
        val cur    = new StringBuilder
        var quoted = false
        var i      = 0
        while (i < line.length)
        {
            val c = line(i)
            if (quoted)
            {
                if (c == '"')
                {
                    if (i + 1 < line.length && line(i + 1) == '"') { cur.append('"'); i += 1 }
                    else quoted = false
                }
                else cur.append(c)
            }
            else if (c == '"') quoted = true
            else if (c == delimiter) { fields += cur.result(); cur.clear() }
            else cur.append(c)
            i += 1
        }
        if (quoted) None
        else { fields += cur.result(); Some(fields.result()) }
    }

    /**
     *  Parses a CSV/TSV document: a first-line delimiter, 2–128 fields per
     *  row, uniform field counts, no unclosed quotes.
     *  @param lines    Document lines.
     *  @param lineSep  Line separator matching the input's majority ending.
     *  @return         The parsed table, or `None`.
     */
    private def parseDelimited(lines: Vector[String], lineSep: String): Option[Table] =
    {
        if (lines.exists(_.isBlank)) return None
        val first     = lines.head
        val delimiter = if (first.count(_ == '\t') > first.count(_ == ',')) '\t' else ','
        if (!first.contains(delimiter)) return None
        val parsed = lines.map(l => splitDelimited(l, delimiter))
        if (parsed.exists(_.isEmpty)) return None
        val rows   = parsed.flatten
        val cols = rows.head.size
        if (cols < 2 || cols > 128) return None
        if (rows.exists(_.size != cols)) return None
        Some(Table(header = rows.head, rows = rows.drop(1),
            kind = Kind.Delimited, delimiter = delimiter, lineSep = lineSep))
    }
}
