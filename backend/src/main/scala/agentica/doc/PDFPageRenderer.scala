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
        val document = Loader.loadPDF(path.toFile)
        try
        {
            val renderer = PDFRenderer(document)
            val total    = document.getNumberOfPages
            (0 until total).map { i => renderPage(renderer, i, RenderDPI) }.toList
        }
        finally { document.close() }
    }

    /**
     *  Returns the number of pages in the PDF without rendering.
     *
     *  @param path  Absolute path to the PDF file.
     *  @return      Total page count.
     *  @throws Exception if the file is malformed or unreadable.
     */
    def pageCount(path: Path): Int =
    {
        val document = Loader.loadPDF(path.toFile)
        try { document.getNumberOfPages }
        finally { document.close() }
    }

    /**
     *  Renders a contiguous slice of pages from the PDF.
     *  The range `[fromPage, toPage)` is 0-based with exclusive end.
     *
     *  @param path      Absolute path to the PDF file.
     *  @param fromPage  First page index (0-based, inclusive).
     *  @param toPage    One past the last page index (exclusive).
     *  @return          PNG byte arrays in page order.
     *  @throws Exception if the file is malformed or an I/O error occurs.
     */
    def renderBatch(path: Path, fromPage: Int, toPage: Int): List[Array[Byte]] =
        renderBatch(path, fromPage, toPage, RenderDPI)

    /**
     *  Renders a contiguous slice of pages at a custom DPI.
     *  The range `[fromPage, toPage)` is 0-based with exclusive end.
     *
     *  @param path      Absolute path to the PDF file.
     *  @param fromPage  First page index (0-based, inclusive).
     *  @param toPage    One past the last page index (exclusive).
     *  @param dpi       Rendering DPI (e.g. 50, 100, 150).
     *  @return          PNG byte arrays in page order.
     *  @throws Exception if the file is malformed or an I/O error occurs.
     */
    def renderBatch(path: Path, fromPage: Int, toPage: Int, dpi: Float): List[Array[Byte]] =
    {
        val document = Loader.loadPDF(path.toFile)
        try
        {
            val renderer = PDFRenderer(document)
            (fromPage until toPage).map { i => renderPage(renderer, i, dpi) }.toList
        }
        finally { document.close() }
    }

    /**
     *  Renders a single page to a PNG byte array.
     *
     *  @param renderer   PDFBox renderer bound to an open document.
     *  @param pageIndex  0-based page index.
     *  @return           PNG byte array for the page.
     */
    private def renderPage(renderer: PDFRenderer, pageIndex: Int, dpi: Float): Array[Byte] =
    {
        val image = renderer.renderImageWithDPI(pageIndex, dpi)
        val baos  = ByteArrayOutputStream()
        ImageIO.write(image, "PNG", baos)
        baos.toByteArray
    }

    /**
     *  Returns the longest side (width or height) of a PNG image in pixels.
     *
     *  @param pngBytes  PNG image bytes.
     *  @return          Longest dimension in pixels.
     */
    def imageLongestSide(pngBytes: Array[Byte]): Int =
    {
        val img = ImageIO.read(new java.io.ByteArrayInputStream(pngBytes))
        img.getWidth.max(img.getHeight)
    }

    /**
     *  Downscales a PNG image so its longest side does not exceed `maxDimension`,
     *  preserving aspect ratio. Used to reduce vision-encoder token count for VLMs
     *  with dynamic/native-resolution encoders (e.g. Qwen2.5-VL), where CPU-bound
     *  image preprocessing can otherwise take minutes on a high-DPI page render.
     *  Images already within the limit are returned unchanged.
     *
     *  @param pngBytes      Source PNG image bytes.
     *  @param maxDimension  Maximum allowed width/height in pixels.
     *  @return              Resized PNG image bytes, or the original if already small enough.
     */
    def resizeToMaxDimension(pngBytes: Array[Byte], maxDimension: Int): Array[Byte] =
    {
        val original = ImageIO.read(new java.io.ByteArrayInputStream(pngBytes))
        val (w, h)   = (original.getWidth, original.getHeight)
        val longest  = w.max(h)

        if (longest <= maxDimension) { pngBytes }
        else
        {
            val scale     = maxDimension.toDouble / longest
            val newWidth  = (w * scale).round.toInt.max(1)
            val newHeight = (h * scale).round.toInt.max(1)

            val resized = java.awt.image.BufferedImage(newWidth, newHeight, java.awt.image.BufferedImage.TYPE_INT_RGB)
            val g2d     = resized.createGraphics()
            g2d.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2d.drawImage(original, 0, 0, newWidth, newHeight, null)
            g2d.dispose()

            val baos = ByteArrayOutputStream()
            ImageIO.write(resized, "PNG", baos)
            baos.toByteArray
        }
    }
}
