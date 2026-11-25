package agentica.misctests

import agentica.search.{IndexKind, IndexScope, SearchIndexImpl, SourceKey, TextExtractor}
import java.nio.file.{Files, Paths}
import java.sql.DriverManager

/**
 *  Throwaway benchmark for AG-31 index throughput and PDF text extraction.
 *  Run with:
 *  {{{
 *  mvn test-compile exec:exec -Dexec.classpathScope=test \
 *    -Dexec.args="-classpath %classpath agentica.misctests.SearchBenchmark"
 *  }}}
 */
object SearchBenchmark
{
    /**
     *  Measures chunk+FTS insert throughput and PDF text extraction latency.
     *  @param args  Unused.
     */
    def main(args: Array[String]): Unit =
    {
        Class.forName("org.sqlite.JDBC")
        val dbFile = Files.createTempFile("agentica-search-bench", ".db")
        val index  = new SearchIndexImpl(() => DriverManager.getConnection(s"jdbc:sqlite:$dbFile"))
        index.init()

        val linesPerFile = 20000
        val files        = 5
        val text         = (1 to linesPerFile)
            .map(i => s"line $i revenue growth commentary token$i with some filler words")
            .mkString("\n")
        val bytesPerFile = text.getBytes("UTF-8").length

        val t0 = System.currentTimeMillis()
        for (f <- 1 to files)
            index.index(SourceKey(IndexKind.Workspace, "/bench", None, None, s"f$f.md"),
                text, f.toLong, bytesPerFile.toLong)
        val dt = System.currentTimeMillis() - t0
        val kb = files * bytesPerFile / 1024.0
        println(f"FTS index: $files files x ${bytesPerFile / 1024} KB = ${kb}%.0f KB in $dt ms " +
            f"(${kb / (math.max(dt, 1) / 1000.0)}%.0f KB/s)")

        val t1    = System.currentTimeMillis()
        val hits  = index.search(IndexScope(IndexKind.Workspace, "/bench", None, None), "revenue", 10)
        println(s"FTS search: ${hits.size} hits in ${System.currentTimeMillis() - t1} ms")

        val pdfPath = Paths.get(getClass.getResource("/files/IT Support Analyst - India.pdf").toURI)
        val pages   = agentica.doc.PDFPageRenderer.pageCount(pdfPath)
        TextExtractor.extract(pdfPath) // warm-up (PDFBox font/loader initialisation)
        val t2        = System.currentTimeMillis()
        val extracted = TextExtractor.extract(pdfPath)
        val dt2       = System.currentTimeMillis() - t2
        println(s"PDF extract (warm): $pages pages, ${extracted.map(_.length).getOrElse(0)} chars in $dt2 ms")

        val t3 = System.currentTimeMillis()
        val hits2 = index.search(IndexScope(IndexKind.Workspace, "/bench", None, None), "growth", 10)
        println(s"FTS search (warm): ${hits2.size} hits in ${System.currentTimeMillis() - t3} ms")

        // Compare PDFBox sorting modes: sortByPosition is the expensive part.
        for (sorted <- List(true, false))
        {
            val doc = org.apache.pdfbox.Loader.loadPDF(pdfPath.toFile)
            try
            {
                val stripper = new org.apache.pdfbox.text.PDFTextStripper()
                stripper.setSortByPosition(sorted)
                val t = System.currentTimeMillis()
                val text = stripper.getText(doc)
                println(s"PDF stripper sortByPosition=$sorted: $pages pages, ${text.length} chars " +
                    s"in ${System.currentTimeMillis() - t} ms")
            }
            finally doc.close()
        }

        index.deleteSource(SourceKey(IndexKind.Workspace, "/bench", None, None, "f1.md"))
        Files.deleteIfExists(dbFile)
    }
}
