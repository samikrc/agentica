package agentica.search

import org.scalatest.funsuite.AnyFunSuite

/**
 *  Unit tests for [[Chunker]]: chunk sizing, overlap, blank-line boundaries,
 *  line numbering, and degenerate inputs.
 */
class ChunkerTest extends AnyFunSuite
{

    /** Builds `n` numbered lines joined by newlines. */
    private def numbered(n: Int): String = (1 to n).map(i => s"line $i").mkString("\n")

    test("empty and blank input produce no chunks") {
        assert(Chunker.chunk("") == Nil)
    }

    test("short document is a single chunk with 1-based line bounds") {
        val chunks = Chunker.chunk(numbered(5))
        assert(chunks.length == 1)
        assert(chunks.head.startLine == 1)
        assert(chunks.head.endLine == 5)
        assert(chunks.head.index == 0)
        assert(chunks.head.text == numbered(5))
    }

    test("exactly one window of lines is a single chunk") {
        val chunks = Chunker.chunk(numbered(Chunker.ChunkLines))
        assert(chunks.length == 1)
        assert(chunks.head.endLine == Chunker.ChunkLines)
    }

    test("long document splits into overlapping chunks covering all lines") {
        val total  = Chunker.ChunkLines * 3
        val chunks = Chunker.chunk(numbered(total))

        assert(chunks.length >= 3)
        assert(chunks.head.startLine == 1)
        assert(chunks.last.endLine == total)
        assert(chunks.map(_.index) == chunks.indices.toList)

        // Consecutive chunks overlap by at most OverlapLines and never gap.
        chunks.sliding(2).foreach { case List(a, b) =>
            assert(b.startLine <= a.endLine + 1, s"gap between ${a.endLine} and ${b.startLine}")
            assert(a.endLine - b.startLine + 1 <= Chunker.OverlapLines,
                s"overlap ${a.endLine - b.startLine + 1} exceeds ${Chunker.OverlapLines}")
        }
    }

    test("chunks break at a blank line when one falls in the trailing quarter") {
        // Two windows' worth of lines so a boundary split is actually needed.
        val lines = (1 to Chunker.ChunkLines * 2).map(i => if (i == 50) "" else s"line $i")
        val chunks = Chunker.chunk(lines.mkString("\n"))

        assert(chunks.length >= 2)
        // The blank line at index 49 (line 50) ends the first chunk.
        assert(chunks.head.endLine == 49, s"expected break before blank line, got ${chunks.head.endLine}")
    }

    test("trailing newline is a terminator, not an extra line") {
        val chunks = Chunker.chunk(numbered(3) + "\n")
        assert(chunks.length == 1)
        assert(chunks.head.endLine == 3)
    }

    test("single-line document is one chunk") {
        val chunks = Chunker.chunk("only line")
        assert(chunks.length == 1)
        assert(chunks.head.text == "only line")
    }

    test("chunk text round-trips to the source line range") {
        val source = numbered(Chunker.ChunkLines * 2)
        val lines  = source.split("\n")
        for (ch <- Chunker.chunk(source))
        {
            val expected = lines.slice(ch.startLine - 1, ch.endLine).mkString("\n")
            assert(ch.text == expected)
        }
    }
}
