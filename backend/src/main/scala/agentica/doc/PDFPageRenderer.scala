package agentica.doc

import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.imageio.ImageIO
import agentica.observability.TraceLogger


/**
 *  Renders PDF pages to PNG images using Apache PDFBox.
 *
 *  Rendering parameters:
 *  - DPI: 150 (sufficient for vision LLM OCR; balances quality vs. payload size)
 *  - Format: PNG (lossless, widely supported)
 *
 *  Thread safety: PDFBox's [[PDDocument]] and [[PDFRenderer]] are not thread-safe;
 *  each call to [[renderToImages]] creates a fresh document instance.
 */
object PDFPageRenderer extends PageRenderer
{

    /** Target DPI for page rendering — 150 provides good OCR quality. */
    val RenderDPI: Float = 150.0f

    /**
     *  Renders each page of the PDF to a PNG image.
     *
     *  @param path  Absolute path to the PDF file.
     *  @return      List of PNG byte arrays, one per page, in page order.
     *  @throws Exception if the file is malformed, password-protected, or an I/O error occurs.
     */
    def renderToImages(path: Path): List[Array[Byte]] =
    {
        TraceLogger.debug("PDFPageRenderer", s"Trying to load PDF from ${path.toString}")
        val document = Loader.loadPDF(path.toFile)
        TraceLogger.debug("PDFPageRenderer", s"Loaded PDF: ${document.toString} at ${path.toString}")
        try
        {
            val renderer  = PDFRenderer(document)
            val pageCount = document.getNumberOfPages

            (0 until pageCount).map { pageIndex =>
                val image = renderer.renderImageWithDPI(pageIndex, RenderDPI)
                val baos  = ByteArrayOutputStream()
                ImageIO.write(image, "PNG", baos)
                baos.toByteArray
            }.toList
        }
        finally
        {
            document.close()
        }
    }
}
