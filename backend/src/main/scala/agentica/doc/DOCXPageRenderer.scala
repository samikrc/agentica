package agentica.doc

import agentica.observability.TraceLogger
import agentica.util.ProcessUtils

import java.nio.file.{Files, Path, Paths}
import java.util.Comparator
import scala.jdk.CollectionConverters.*

/**
 *  Renders DOCX pages to PNG images using LibreOffice headless.
 *
 *  LibreOffice is invoked with:
 *  `soffice --headless --convert-to png --outdir <tmp> <input.docx>`
 *
 *  This produces per-page PNG files in the temp directory. We read them in
 *  page order (sorted by filename) and return the byte arrays.
 *
 *  Requirements:
 *  - LibreOffice must be installed (`soffice` on PATH)
 *  - [[DocToolDetector.available]] should be checked before calling; if false,
 *    this method will throw an exception with install instructions.
 */
object DOCXPageRenderer extends PageRenderer
{

    /**
     *  Renders each page of the DOCX to a PNG image via LibreOffice.
     *
     *  @param path  Absolute path to the DOCX file.
     *  @return      List of PNG byte arrays, one per page, in page order.
     *  @throws DocToolUnavailableException if LibreOffice is not available.
     *  @throws Exception on conversion failure or I/O errors.
     */
    def renderToImages(path: Path): List[Array[Byte]] =
    {
        // Verify LibreOffice is available
        if (!DocToolDetector.available)
        {
            throw DocToolUnavailableException(
                "LibreOffice is not available. " + DocToolDetector.installInstructions
            )
        }

        val sofficePath = DocToolDetector.status.path.getOrElse("soffice")
        val tempDir     = Files.createTempDirectory("agentica-docx-render-")

        try
        {
            // Build the LibreOffice command
            val cmd = List(
                sofficePath,
                "--headless",
                "--convert-to", "png",
                "--outdir", tempDir.toString,
                path.toString
            )

            TraceLogger.info("-", "docx_page_renderer",
                Map("action" -> "converting", "path" -> path.toString, "temp" -> tempDir.toString))

            // Execute the process with 60 second timeout
            val result = ProcessUtils.runCaptured(cmd, timeoutSec = 60)

            if (result.isEmpty)
            {
                throw RuntimeException(s"LibreOffice conversion failed or timed out after 60s: $path")
            }

            // Find all PNG files in temp directory, sorted by name (page order)
            val pngFiles = Files.list(tempDir)
                .filter(p => p.toString.toLowerCase.endsWith(".png"))
                .sorted(Comparator.naturalOrder())
                .toList
                .asScala
                .toList

            if (pngFiles.isEmpty)
            {
                throw RuntimeException(s"LibreOffice produced no PNG files for: $path")
            }

            TraceLogger.info("-", "docx_page_renderer",
                Map("action" -> "converted", "pages" -> pngFiles.size.toString))

            // Read all PNG files into byte arrays
            pngFiles.map(p => Files.readAllBytes(p))
        }
        finally
        {
            // Clean up temp directory
            try
            {
                Files.walk(tempDir, java.nio.file.FileVisitOption.FOLLOW_LINKS)
                    .sorted(Comparator.reverseOrder())
                    .forEach(Files.delete(_))
            }
            catch
            {
                case _: Exception => // Best effort cleanup
            }
        }
    }

    /**
     *  Returns the number of pages in the DOCX by converting it and counting PNGs.
     *  Note: LibreOffice always converts the whole document; there is no cheaper
     *  way to count DOCX pages without rendering.
     *
     *  @param path  Absolute path to the DOCX file.
     *  @return      Total page count.
     *  @throws Exception if LibreOffice is unavailable or conversion fails.
     */
    def pageCount(path: Path): Int = renderToImages(path).size

    /**
     *  Renders a contiguous slice of pages from the DOCX.
     *  LibreOffice converts the whole document; this method returns the requested slice.
     *
     *  @param path      Absolute path to the DOCX file.
     *  @param fromPage  First page index (0-based, inclusive).
     *  @param toPage    One past the last page index (exclusive).
     *  @return          PNG byte arrays in page order.
     *  @throws Exception if LibreOffice is unavailable or conversion fails.
     */
    def renderBatch(path: Path, fromPage: Int, toPage: Int): List[Array[Byte]] =
        renderToImages(path).slice(fromPage, toPage)
}

/**
 *  Exception thrown when a document tool's external dependency is missing.
 */
case class DocToolUnavailableException(message: String) extends Exception(message)
