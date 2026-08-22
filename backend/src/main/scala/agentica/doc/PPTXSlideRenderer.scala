package agentica.doc

import org.apache.poi.xslf.usermodel.XMLSlideShow
import scala.jdk.CollectionConverters.*
import java.awt.{Graphics2D, RenderingHints}
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.{Files, Path}
import javax.imageio.ImageIO

/**
 *  Renders PPTX slides to PNG images using Apache POI XSLF.
 *
 *  Rendering parameters:
 *  - Target width: 1280 px (equivalent to ~150 DPI at standard slide dimensions)
 *  - Format: PNG (lossless)
 *
 *  The slide aspect ratio is preserved; height is calculated from the slide's
 *  native dimensions to maintain proportions.
 *
 *  Thread safety: POI's [[XMLSlideShow]] is not thread-safe; each call creates
 *  a fresh instance.
 */
object PPTXSlideRenderer extends PageRenderer
{

    /** Target width in pixels — 1280 provides good vision LLM readability. */
    val TargetWidth: Int = 1280

    /**
     *  Renders each slide of the PPTX to a PNG image.
     *
     *  @param path  Absolute path to the PPTX file.
     *  @return      List of PNG byte arrays, one per slide, in slide order.
     *  @throws Exception if the file is malformed or an I/O error occurs.
     */
    def renderToImages(path: Path): List[Array[Byte]] =
    {
        withSlideshow(path) { ss =>
            val total = ss.getSlides.size
            (0 until total).map { i => renderSlide(ss, i) }.toList
        }
    }

    /**
     *  Returns the number of slides in the PPTX without rendering.
     *
     *  @param path  Absolute path to the PPTX file.
     *  @return      Total slide count.
     *  @throws Exception if the file is malformed or unreadable.
     */
    def pageCount(path: Path): Int =
        withSlideshow(path) { ss => ss.getSlides.size }

    /**
     *  Renders a contiguous slice of slides from the PPTX.
     *  The range `[fromPage, toPage)` is 0-based with exclusive end.
     *
     *  @param path      Absolute path to the PPTX file.
     *  @param fromPage  First slide index (0-based, inclusive).
     *  @param toPage    One past the last slide index (exclusive).
     *  @return          PNG byte arrays in slide order.
     *  @throws Exception if the file is malformed or an I/O error occurs.
     */
    def renderBatch(path: Path, fromPage: Int, toPage: Int): List[Array[Byte]] =
        withSlideshow(path) { ss =>
            (fromPage until toPage).map { i => renderSlide(ss, i) }.toList
        }

    /**
     *  Opens the PPTX at `path`, applies `f`, then closes all resources.
     *
     *  @param path  Absolute path to the PPTX file.
     *  @param f     Function receiving the open [[XMLSlideShow]].
     *  @return      The value returned by `f`.
     */
    private def withSlideshow[A](path: Path)(f: XMLSlideShow => A): A =
    {
        val inputStream = Files.newInputStream(path)
        try
        {
            val slideshow = XMLSlideShow(inputStream)
            try { f(slideshow) }
            finally { slideshow.close() }
        }
        finally { inputStream.close() }
    }

    /**
     *  Renders a single slide to a PNG byte array.
     *
     *  @param slideshow  Open POI [[XMLSlideShow]] instance.
     *  @param index      0-based slide index.
     *  @return           PNG byte array for the slide.
     */
    private def renderSlide(slideshow: XMLSlideShow, index: Int): Array[Byte] =
    {
        val slide         = slideshow.getSlides.get(index)
        val pageSize      = slideshow.getPageSize
        val slideWidthPt  = pageSize.getWidth
        val slideHeightPt = pageSize.getHeight
        val scale         = TargetWidth / slideWidthPt
        val widthPx       = TargetWidth
        val heightPx      = (slideHeightPt * scale).toInt

        val image    = BufferedImage(widthPx, heightPx, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try
        {
            graphics.setColor(java.awt.Color.WHITE)
            graphics.fillRect(0, 0, widthPx, heightPx)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.scale(scale.toDouble, scale.toDouble)
            slide.draw(graphics)
        }
        finally { graphics.dispose() }

        val baos = ByteArrayOutputStream()
        ImageIO.write(image, "PNG", baos)
        baos.toByteArray
    }
}
