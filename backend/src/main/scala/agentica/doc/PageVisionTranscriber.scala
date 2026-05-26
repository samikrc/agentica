package agentica.doc

import agentica.llm.LLMProvider
import agentica.observability.TraceLogger
import java.util.Base64

/**
 *  Transcribes page/slide images to Markdown using a Vision-capable LLM.
 *
 *  Pipeline:
 *  1. For each PNG byte array: base64-encode → call LLM with transcription prompt
 *  2. Collect per-page Markdown strings
 *  3. Assemble with `---` separators (horizontal rule in Markdown)
 *
 *  The transcription prompt instructs the model to preserve:
 *  - Headings and hierarchy
 *  - Lists (bulleted and numbered)
 *  - Tables (as Markdown tables)
 *  - Inline code and code blocks
 *  - Captions and alt-text
 */
object PageVisionTranscriber
{

    /**
     *  Default vision transcription prompt. Instructs the model to convert a
     *  document page image to clean Markdown while preserving structure.
     */
    val DefaultPrompt: String =
        """Convert this document page to Markdown. Preserve the following:
          |
          |1. **Headings**: Use # for titles, ## for sections, etc.
          |2. **Lists**: Use - for bullets and 1. 2. 3. for numbered lists.
          |3. **Tables**: Format as Markdown tables with | columns and |- separators.
          |4. **Code**: Use `inline code` and ```fenced blocks``` for code.
          |5. **Formatting**: Use **bold** and *italic* where present.
          |6. **Captions**: Preserve figure and table captions.
          |
          |Output ONLY the Markdown content. Do not add explanatory text, page numbers, or "Page X of Y" markers.
          |If the page is mostly empty or contains no meaningful text, output a brief note like "[Empty page]".
          |""".stripMargin.trim

    /**
     *  Transcribes a sequence of page images and returns combined Markdown.
     *
     *  @param images      List of PNG byte arrays, one per page/slide.
     *  @param llm         Vision-capable LLM provider.
     *  @param traceId     Trace identifier for logging.
     *  @param prompt      Optional custom prompt (defaults to [[DefaultPrompt]]).
     *  @param onProgress  Optional callback invoked after each page with `(current, total)` for UI progress.
     *  @return            Markdown string with `---` separators between pages.
     */
    def transcribe(
        images:     List[Array[Byte]],
        llm:        LLMProvider,
        traceId:    String,
        prompt:     String = DefaultPrompt,
        onProgress: (Int, Int) => Unit = (_, _) => ()
    ): String =
    {
        if (images.isEmpty)
        {
            return "[No pages found in document]"
        }

        TraceLogger.info(traceId, "page_vision_transcriber_start",
            Map("pages" -> images.size.toString, "model" -> llm.modelName))

        val pageMarkdowns = images.zipWithIndex.map { case (imageBytes, index) =>
            val pageNum = index + 1

            // Base64 encode with data URI prefix
            val base64  = Base64.getEncoder.encodeToString(imageBytes)
            val dataUri = s"data:image/png;base64,$base64"

            TraceLogger.info(traceId, "page_vision_transcriber_page",
                Map("page" -> pageNum.toString, "bytes" -> imageBytes.length.toString))

            // Call vision LLM
            val startMs   = System.currentTimeMillis()
            val markdown  = llm.completeVision(dataUri, prompt)
            val elapsedMs = System.currentTimeMillis() - startMs

            TraceLogger.info(traceId, "page_vision_transcriber_page_complete",
                Map("page" -> pageNum.toString, "elapsed_ms" -> elapsedMs.toString, "chars" -> markdown.length.toString))

            onProgress(pageNum, images.size)
            markdown
        }

        // Assemble with --- separators
        val result = pageMarkdowns.mkString("\n\n---\n\n")

        TraceLogger.info(traceId, "page_vision_transcriber_complete",
            Map("pages" -> images.size.toString, "total_chars" -> result.length.toString))

        result
    }

    /**
     *  Returns a stub Markdown document for when vision is disabled.
     *  Contains placeholders like "[page N: vision enrichment skipped]".
     *
     *  @param pageCount  Number of pages/slides in the document.
     *  @return           Stub Markdown string.
     */
    def stubMarkdown(pageCount: Int): String =
    {
        (1 to pageCount).map(n => s"[page $n: vision enrichment skipped]").mkString("\n\n---\n\n")
    }
}
