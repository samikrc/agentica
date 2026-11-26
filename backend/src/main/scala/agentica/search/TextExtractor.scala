package agentica.search

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.poi.xslf.usermodel.{XMLSlideShow, XSLFTextShape}
import org.apache.poi.xwpf.usermodel.{XWPFParagraph, XWPFTable, XWPFDocument}
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 *  Extracts plain text from workspace files for lexical indexing.
 *
 *  Raw office documents are read from their **text layer** only (PDFBox for
 *  PDF, POI for DOCX/PPTX) — fast and VLM-free. Vision-only content (charts,
 *  scanned pages) contributes nothing until the agent converts the document to
 *  Markdown, which then supersedes the raw rows.
 */
object TextExtractor
{
    /** Maximum file size considered for indexing (bytes). */
    val MaxFileBytes: Long = 2L * 1024 * 1024

    /** Number of leading bytes inspected for binary detection. */
    val SniffBytes: Int = 8192

    /** Upper bound on extracted characters per file, guarding pathological documents. */
    private val MaxExtractedChars: Int = 5_000_000

    /** Extensions read directly as UTF-8 text. */
    private val TextExtensions: Set[String] = Set(
        "md", "markdown", "txt", "text", "csv", "tsv", "json", "yaml", "yml",
        "xml", "html", "htm", "log", "sql", "ini", "cfg", "conf", "properties",
        "rst", "tex", "py", "js", "ts", "java", "scala", "sh", "css"
    )

    /** Extensions read through a document text extractor. */
    private val DocumentExtensions: Set[String] = Set("pdf", "docx", "pptx")

    /** Directory names never crawled. */
    private val SkipDirs: Set[String] = Set(
        ".git", "node_modules", "target", "build", "dist", ".venv", "venv",
        "__pycache__", ".idea", ".gradle", ".scala-build", ".metals", ".bloop", ".bsp"
    )

    /**
     *  Lower-case extension of a path, without the dot.
     *  @param path  File path.
     *  @return      Extension such as `"md"`, or the empty string when absent.
     */
    def extensionOf(path: Path): String =
    {
        val name = path.getFileName.toString
        val dot  = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) "" else name.substring(dot + 1).toLowerCase
    }

    /**
     *  Whether a file's extension is a known text or document format.
     *
     *  Fast classification only — never the authority on whether a file
     *  contains text. Unknown and extensionless files are still indexable when
     *  they sniff as UTF-8; see [[mayContainText]].
     *
     *  @param path  File path.
     *  @return      `true` for whitelisted text files and supported office documents.
     */
    def isSupported(path: Path): Boolean =
    {
        val ext = extensionOf(path)
        TextExtensions.contains(ext) || DocumentExtensions.contains(ext)
    }

    /**
     *  Whether a file may contain indexable text.
     *
     *  Known text extensions and office documents pass outright. Any other
     *  file — unknown extension or none, e.g. `.rs`, `.toml`, `Dockerfile` —
     *  is decided by content sniffing: NUL bytes in the leading window mark it
     *  binary. The extension list is a fast path, never an exclusion mechanism.
     *
     *  @param path  File path.
     *  @return      `true` when the file should be read as text.
     */
    def mayContainText(path: Path): Boolean =
    {
        if (isSupported(path)) true
        else !looksBinary(path)
    }

    /**
     *  Whether a path is an office document requiring text extraction.
     *  @param path  File path.
     *  @return      `true` for `.pdf`, `.docx`, and `.pptx`.
     */
    def isDocument(path: Path): Boolean =
        DocumentExtensions.contains(extensionOf(path))

    /**
     *  Whether a directory name is excluded from crawling.
     *  @param name  Directory name.
     *  @return      `true` when the directory should be skipped.
     */
    def isSkippedDir(name: String): Boolean =
        SkipDirs.contains(name) || name.endsWith("_debug")

    /**
     *  Detects binary content by scanning the leading bytes for NUL.
     *  @param path  File path.
     *  @return      `true` when the file appears to be binary or cannot be read.
     */
    def looksBinary(path: Path): Boolean =
    {
        try
        {
            val in    = Files.newInputStream(path)
            try
            {
                val buf   = new Array[Byte](math.min(SniffBytes.toLong, Files.size(path)).toInt)
                var read  = 0
                var chunk = 0
                while (read < buf.length && chunk >= 0)
                {
                    chunk = in.read(buf, read, buf.length - read)
                    if (chunk > 0) read += chunk
                }
                buf.take(read).contains(0.toByte)
            }
            finally in.close()
        }
        catch
        {
            case _: Throwable => true
        }
    }

    /**
     *  Extracts indexable text from a file.
     *
     *  PDF/DOCX/PPTX are read from their text layer; every other file is read
     *  as UTF-8 after a binary sniff, regardless of extension — unknown and
     *  extensionless files (`.rs`, `Dockerfile`, …) are included. Extraction
     *  failures, malformed UTF-8, and binary content yield `None` so the
     *  caller can count the file as skipped.
     *
     *  @param path  File path inside the workspace.
     *  @return      Extracted text, or `None` when the file is binary or unreadable.
     */
    def extract(path: Path): Option[String] =
    {
        try
        {
            if (Files.size(path) > MaxFileBytes) return None
            val text =
                if (isDocument(path)) extractDocument(path)
                else
                {
                    if (looksBinary(path)) return None
                    Files.readString(path, StandardCharsets.UTF_8)
                }
            Some(if (text.length > MaxExtractedChars) text.take(MaxExtractedChars) else text)
        }
        catch
        {
            case _: Throwable => None
        }
    }

    /**
     *  Dispatches to the format-specific extractor.
     *  @param path  Supported document path.
     *  @return      Extracted text, empty for an unrecognised extension.
     */
    private def extractDocument(path: Path): String =
        extensionOf(path) match
        {
            case "pdf"  => extractPDF(path)
            case "docx" => extractDOCX(path)
            case "pptx" => extractPPTX(path)
            case _      => ""
        }

    /**
     *  Extracts the PDF text layer, one line per layout-sorted text fragment.
     *  @param path  PDF file path.
     *  @return      Extracted text with page breaks flattened to newlines.
     */
    private def extractPDF(path: Path): String =
    {
        val doc = Loader.loadPDF(path.toFile)
        try
        {
            val stripper = new PDFTextStripper()
            stripper.setSortByPosition(true)
            stripper.getText(doc).replace("\f", "\n")
        }
        finally
        {
            doc.close()
        }
    }

    /**
     *  Extracts DOCX paragraph and table text in body order, with table cells
     *  joined by ` | ` so rows stay readable as single lines.
     *  @param path  DOCX file path.
     *  @return      Extracted text.
     */
    private def extractDOCX(path: Path): String =
    {
        val in  = Files.newInputStream(path)
        val doc = new XWPFDocument(in)
        try
        {
            val sb = new StringBuilder()
            for (element <- doc.getBodyElements.asScala)
            {
                element match
                {
                    case p: XWPFParagraph => sb.append(p.getText).append('\n')
                    case t: XWPFTable     =>
                        for (row <- t.getRows.asScala)
                            sb.append(row.getTableCells.asScala.map(_.getText).mkString(" | ")).append('\n')
                    case _ => ()
                }
            }
            sb.toString
        }
        finally
        {
            doc.close()
            in.close()
        }
    }

    /**
     *  Extracts PPTX slide text with a slide header per slide.
     *  @param path  PPTX file path.
     *  @return      Extracted text with `--- slide N ---` headers.
     */
    private def extractPPTX(path: Path): String =
    {
        val in        = Files.newInputStream(path)
        val slideShow = new XMLSlideShow(in)
        try
        {
            val sb = new StringBuilder()
            for ((slide, idx) <- slideShow.getSlides.asScala.zipWithIndex)
            {
                sb.append(s"--- slide ${idx + 1} ---\n")
                for (shape <- slide.getShapes.asScala)
                {
                    shape match
                    {
                        case ts: XSLFTextShape =>
                            val text = ts.getText
                            if (text != null && text.trim.nonEmpty) sb.append(text).append('\n')
                        case _ => ()
                    }
                }
                sb.append('\n')
            }
            sb.toString
        }
        finally
        {
            slideShow.close()
            in.close()
        }
    }
}
