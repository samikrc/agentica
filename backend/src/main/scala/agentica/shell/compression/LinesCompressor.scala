package agentica.shell.compression

/**
 *  Compresses line-oriented payloads — logs, listings, repeated boilerplate
 *  like converted-document page headers — by eliding runs of unremarkable
 *  lines into markers.
 *
 *  Kept lines: the first and last few, lines matching [[Elision.ImportantRE]],
 *  query-relevant lines, and a representative of every distinct line shape
 *  ([[Elision.keepNonRedundant]] — which also makes scattered duplicate lines
 *  collapse against their first occurrence, not just consecutive runs).
 *  Dropped runs collapse into `… N lines elided …` markers summarised by
 *  [[Elision.summarizeFields]] over extracted `name=value` pairs.
 *
 *  Fail-closed: too few lines, or output not smaller than the input, passes
 *  the original through.
 */
object LinesCompressor extends Compressor
{
    /** Lines kept from the front and back. */
    private val KeepHead = 2
    private val KeepTail = 2

    /** Fewer lines than this is not worth compressing. */
    private val MinLines = 8

    /** Lines a query-aware pass may additionally keep. */
    private val QueryLimit = 16

    /** Content type reported in result metadata. */
    def contentType: String = "lines"

    /**
     *  Compresses a line-oriented payload by run elision.
     *  @inheritdoc Compressor.compress
     */
    def compress(text: String, query: String = ""): Option[String] =
    {
        if (Elision.MarkerRE.findFirstIn(text).isDefined) return None
        val (raw, trailing) = Elision.splitLines(text)
        if (raw.size < MinLines) return None
        val lines   = raw.map(_.stripSuffix("\r"))
        val lineSep = if (Elision.majorityCRLF(text)) "\r\n" else "\n"

        val keep = Array.fill(lines.size)(false)
        for (i <- lines.indices)
        {
            if (i < KeepHead || i >= lines.size - KeepTail ||
                Elision.ImportantRE.findFirstIn(lines(i)).isDefined)
            {
                keep(i) = true
            }
        }
        Elision.keepQueryRelevant(keep, lines, query, QueryLimit)
        Elision.keepNonRedundant(lines, keep)

        val shaped = Elision.shapedRuns(lines.size, keep)

        val rendered = shaped.map {
            case Elision.Shaped.Kept(i)    => lines(i)
            case Elision.Shaped.Marker(run) =>
                val summary = Elision.summarizeFields(
                    run.map(i => Elision.fieldsOfLine(lines(i))),
                    run.map(i => lines(i).length + 1).sum)
                Elision.markerText(run, "lines", summary)
        }
        val out = rendered.mkString(lineSep) + (if (trailing) lineSep else "")
        if (out.length >= text.length) None else Some(out)
    }
}
