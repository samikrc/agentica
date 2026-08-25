package agentica.doc

import java.nio.file.Path

/**
 *  Abstraction for rendering a document to a sequence of page/slide images.
 *
 *  Implementations are format-specific:
 *  - [[PDFPageRenderer]] — Apache PDFBox for PDF files
 *  - [[PPTXSlideRenderer]] — Apache POI XSLF for PPTX files
 *  - [[DOCXPageRenderer]] — LibreOffice headless for DOCX files
 *
 *  Each implementation returns ordered PNG byte arrays (one per page or slide).
 *  The DPI/target resolution is implementation-defined but should be sufficient
 *  for OCR/vision LLM readability (typically 150 DPI minimum).
 */
trait PageRenderer
{
    /**
     *  Renders the document at the given path to a sequence of PNG images.
     *  Eagerly loads all pages — suitable for small documents or when the
     *  full image list is needed.
     *
     *  @param path  Absolute path to the document file.
     *  @return      List of PNG byte arrays, one per page/slide, in order.
     *  @throws Exception on I/O errors, malformed files, or rendering failures.
     */
    def renderToImages(path: Path): List[Array[Byte]]

    /**
     *  Returns the total number of pages/slides without rendering any images.
     *  Used by streaming callers to determine batch boundaries.
     *
     *  @param path  Absolute path to the document file.
     *  @return      Number of pages or slides in the document.
     *  @throws Exception on I/O errors or malformed files.
     */
    def pageCount(path: Path): Int

    /**
     *  Renders a contiguous slice of pages to PNG images.
     *  Page indices are 0-based and the range is `[fromPage, toPage)` (exclusive end).
     *  Used by streaming batch callers to bound peak memory to `batchSize` pages.
     *
     *  @param path      Absolute path to the document file.
     *  @param fromPage  First page index to render (0-based, inclusive).
     *  @param toPage    One past the last page index to render (exclusive).
     *  @return          List of PNG byte arrays in page order, length `toPage - fromPage`.
     *  @throws Exception on I/O errors, malformed files, or rendering failures.
     */
    def renderBatch(path: Path, fromPage: Int, toPage: Int): List[Array[Byte]]
}
