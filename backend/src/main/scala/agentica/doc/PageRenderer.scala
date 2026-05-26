package agentica.doc

import java.nio.file.Path

/**
 *  Abstraction for rendering a document to a sequence of page/slide images.
 *
 *  Implementations are format-specific:
 *  - [[PdfPageRenderer]] — Apache PDFBox for PDF files
 *  - [[PptxSlideRenderer]] — Apache POI XSLF for PPTX files
 *  - [[DocxPageRenderer]] — LibreOffice headless for DOCX files
 *
 *  Each implementation returns ordered PNG byte arrays (one per page or slide).
 *  The DPI/target resolution is implementation-defined but should be sufficient
 *  for OCR/vision LLM readability (typically 150 DPI minimum).
 */
trait PageRenderer:
    /**
     *  Renders the document at the given path to a sequence of PNG images.
     *
     *  @param path  Absolute path to the document file.
     *  @return      List of PNG byte arrays, one per page/slide, in order.
     *  @throws Exception on I/O errors, malformed files, or rendering failures.
     */
    def renderToImages(path: Path): List[Array[Byte]]
