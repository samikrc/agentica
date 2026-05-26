package agentica.doc

import agentica.observability.TraceLogger
import java.io.InputStream

/**
 *  Loads and registers bundled fonts with the rendering libraries used by the
 *  document ingestion pipeline.
 *
 *  == Bundled fonts ==
 *  We bundle the [Liberation font family](https://github.com/liberationfonts/liberation-fonts)
 *  (SIL Open Font Licence, freely redistributable) as open-source substitutes for the
 *  most common Microsoft Office fonts:
 *
 *  | Bundled font          | Substitutes for  |
 *  |-----------------------|------------------|
 *  | Liberation Sans       | Arial            |
 *  | Liberation Serif      | Times New Roman  |
 *  | Liberation Mono       | Courier New      |
 *
 *  == Fonts NOT bundled (proprietary / non-redistributable) ==
 *  The following fonts are proprietary and cannot be distributed with the application.
 *  When they are absent a warning is logged but no exception is thrown — rendering
 *  will fall back to the nearest available substitute:
 *
 *  - Calibri (Microsoft Office default body font)
 *  - Aptos (modern Office default since Office 2024)
 *  - Segoe UI (Microsoft UI font)
 *
 *  == PDFBox registration ==
 *  PDFBox 3.x does not have a global font registry.  Fonts are embedded per-document at
 *  the time a [[PDDocument]] is created.  [[DocFontLoader]] therefore keeps the raw font
 *  bytes in memory so [[PdfPageRenderer]] (Stage B) can embed them when opening a PDF that
 *  references one of these families.  The `loadedFonts` map is the runtime cache.
 *
 *  == POI XSLF registration ==
 *  POI XSLF font registration is handled at slide-render time in [[PptxSlideRenderer]]
 *  (Stage B) by setting the AWT `GraphicsEnvironment` before the first draw call.  The
 *  raw font bytes stored here are reused for that registration.
 *
 *  Call [[DocFontLoader.init()]] once at application startup (e.g. from [[BackendServer]]).
 */
object DocFontLoader
{

    /**
     *  Descriptor for a single bundled font file.
     *
     *  @param resourcePath  Classpath resource path relative to the JAR root.
     *  @param familyName    CSS/PostScript family name used in log messages.
     *  @param substituteFor The Microsoft font this substitutes, for the warning log.
     */
    private case class BundledFont(
        resourcePath: String,
        familyName:   String,
        substituteFor: Option[String] = None
    )

    private val bundledFonts: List[BundledFont] = List(
        BundledFont("fonts/liberation/LiberationSans-Regular.ttf",   "Liberation Sans",        Some("Arial")),
        BundledFont("fonts/liberation/LiberationSans-Bold.ttf",      "Liberation Sans Bold",   Some("Arial Bold")),
        BundledFont("fonts/liberation/LiberationSerif-Regular.ttf",  "Liberation Serif",       Some("Times New Roman")),
        BundledFont("fonts/liberation/LiberationSerif-Bold.ttf",     "Liberation Serif Bold",  Some("Times New Roman Bold")),
        BundledFont("fonts/liberation/LiberationMono-Regular.ttf",   "Liberation Mono",        Some("Courier New")),
    )

    private val proprietaryFontNames: List[String] = List("Calibri", "Aptos", "Segoe UI")

    /**
     *  Raw font bytes keyed by family name, populated by [[init()]].
     *  Consumed by PDFBox and POI XSLF at render time (Stage B).
     */
    val loadedFonts: java.util.concurrent.ConcurrentHashMap[String, Array[Byte]] =
        java.util.concurrent.ConcurrentHashMap()

    /**
     *  Registers bundled fonts with the AWT [[java.awt.GraphicsEnvironment]] (for POI XSLF)
     *  and caches raw bytes for PDFBox embedding.  Logs results at INFO level; logs warnings
     *  for missing proprietary fonts without throwing.
     *
     *  Idempotent: safe to call multiple times (subsequent calls are no-ops if fonts are
     *  already loaded).
     */
    def init(): Unit =
    {
        if (!loadedFonts.isEmpty) return  // already initialised

        val ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment

        bundledFonts.foreach { descriptor =>
            loadFont(descriptor) match
            {
                case Some(bytes) =>
                    loadedFonts.put(descriptor.familyName, bytes)
                    try
                    {
                        val awtFont = java.awt.Font.createFont(
                            java.awt.Font.TRUETYPE_FONT,
                            java.io.ByteArrayInputStream(bytes)
                        )
                        ge.registerFont(awtFont)
                        TraceLogger.info("-", "doc_font_loader",
                            Map(
                                "status" -> "registered",
                                "font"   -> descriptor.familyName,
                                "sub_for" -> descriptor.substituteFor.getOrElse("-")
                            )
                        )
                    }
                    catch
                    {
                        case ex: Exception =>
                            TraceLogger.warn("-", "doc_font_loader",
                                Map("status" -> "awt_register_failed", "font" -> descriptor.familyName,
                                    "error" -> ex.getMessage))
                    }

                case None =>
                    TraceLogger.warn("-", "doc_font_loader",
                        Map("status" -> "resource_missing", "font" -> descriptor.familyName,
                            "path" -> descriptor.resourcePath))
            }
        }

        proprietaryFontNames.foreach { name =>
            TraceLogger.warn("-", "doc_font_loader",
                Map(
                    "status" -> "proprietary_not_bundled",
                    "font"   -> name,
                    "hint"   -> s"'$name' is proprietary and cannot be bundled. Install it on the system for full rendering fidelity."
                )
            )
        }
    }

    /**
     *  Loads the raw bytes of a bundled font from the classpath.
     *  Returns `None` if the resource is not found (missing from JAR / resources).
     */
    private def loadFont(descriptor: BundledFont): Option[Array[Byte]] =
    {
        val stream: InputStream = getClass.getClassLoader.getResourceAsStream(descriptor.resourcePath)
        if (stream == null) None
        else
            try Some(stream.readAllBytes())
            finally stream.close()
    }
}
