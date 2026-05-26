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
        val inputStream = Files.newInputStream(path)
        try
        {
            val slideshow = XMLSlideShow(inputStream)
            try
            {
                val slides = slideshow.getSlides
                slides.asScala.map { slide =>
                    // Get slide dimensions in points (1 point = 1/72 inch)
                    val pageSize      = slideshow.getPageSize
                    val slideWidthPt  = pageSize.getWidth
                    val slideHeightPt = pageSize.getHeight

                    // Calculate target dimensions preserving aspect ratio
                    val scale    = TargetWidth / slideWidthPt
                    val widthPx  = TargetWidth
                    val heightPx = (slideHeightPt * scale).toInt

                    // Create buffered image with white background
                    val image    = BufferedImage(widthPx, heightPx, BufferedImage.TYPE_INT_RGB)
                    val graphics = image.createGraphics()
                    try
                    {
                        // White background (some slides may have transparent backgrounds)
                        graphics.setColor(java.awt.Color.WHITE)
                        graphics.fillRect(0, 0, widthPx, heightPx)

                        // High-quality rendering hints
                        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

                        // Scale graphics to match target resolution
                        graphics.scale(scale.toDouble, scale.toDouble)

                        // Render slide
                        slide.draw(graphics)
                    }
                    finally
                    {
                        graphics.dispose()
                    }

                    // Encode to PNG
                    val baos = ByteArrayOutputStream()
                    ImageIO.write(image, "PNG", baos)
                    baos.toByteArray
                }.toList
            }
            finally
            {
                slideshow.close()
            }
        }
        finally
        {
            inputStream.close()
        }
    }
}
