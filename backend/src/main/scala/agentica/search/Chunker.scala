package agentica.search

import scala.collection.mutable.ListBuffer

/**
 *  One chunk of a source document's text with inclusive 1-based line bounds.
 *  @param index      Zero-based chunk ordinal within the source.
 *  @param startLine  First source line in this chunk (1-based, inclusive).
 *  @param endLine    Last source line in this chunk (1-based, inclusive).
 *  @param text       Chunk text, source lines joined by `\n`.
 */
case class Chunk(index: Int, startLine: Int, endLine: Int, text: String)

/**
 *  Splits document text into overlapping line-based chunks for indexing.
 *
 *  Chunks target [[ChunkLines]] lines with [[OverlapLines]] lines of overlap so
 *  a passage straddling a boundary remains retrievable from either side. When a
 *  blank line falls in the trailing quarter of a window, the chunk ends there
 *  so paragraphs and table rows stay together.
 */
object Chunker
{
    /** Target maximum number of lines per chunk. */
    val ChunkLines: Int = 60

    /** Number of trailing lines repeated at the start of the next chunk. */
    val OverlapLines: Int = 10

    /**
     *  Splits `text` into chunks.
     *  @param text  Full source text.
     *  @return      Chunks in document order; `Nil` for blank input.
     */
    def chunk(text: String): List[Chunk] =
    {
        if (text.isEmpty) return Nil
        val raw = text.split("\n", -1).toVector
        // A single trailing newline is a terminator, not an extra empty line.
        val lines = if (raw.nonEmpty && raw.last.isEmpty) raw.dropRight(1) else raw
        if (lines.isEmpty) return Nil

        val out   = ListBuffer.empty[Chunk]
        var start = 0
        while (start < lines.length)
        {
            val hardEnd = math.min(start + ChunkLines, lines.length)
            val end     = if (hardEnd >= lines.length) hardEnd else splitBoundary(lines, start, hardEnd)
            out += Chunk(
                index     = out.length,
                startLine = start + 1,
                endLine   = end,
                text      = lines.slice(start, end).mkString("\n")
            )
            if (end >= lines.length)
            {
                start = lines.length
            }
            else
            {
                val next = end - OverlapLines
                start = if (next > start) next else end
            }
        }
        out.toList
    }

    /**
     *  Chooses a chunk end index, preferring a blank line in the trailing
     *  quarter of the window so chunks break on paragraph boundaries.
     *  @param lines    Source lines.
     *  @param start    Window start index (0-based, inclusive).
     *  @param hardEnd  Window end index (0-based, exclusive).
     *  @return         Preferred end index (exclusive), always greater than `start`.
     */
    private def splitBoundary(lines: Vector[String], start: Int, hardEnd: Int): Int =
    {
        val floor = start + (ChunkLines * 3) / 4
        var i     = hardEnd - 1
        while (i > floor && lines(i).trim.nonEmpty) i -= 1
        if (i > floor && lines(i).trim.isEmpty) i else hardEnd
    }
}
