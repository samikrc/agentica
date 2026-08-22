package agentica.doc

import agentica.llm.LLMProvider
import agentica.observability.TraceLogger
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.{Executors, Future => JFuture, TimeUnit, TimeoutException}
import scala.util.{Try, Success, Failure}

/**
 *  Transcribes page/slide images to Markdown using a Vision-capable LLM.
 *
 *  Pipeline:
 *  1. For each PNG byte array: base64-encode → call LLM with transcription prompt
 *  2. Collect per-page Markdown strings
 *  3. Assemble with `---` separators (horizontal rule in Markdown)
 *
 *  The transcription prompt instructs the model to transcribe only what is visually
 *  present on the page — no inferred headings or added structure. Pages from
 *  multi-page documents may start mid-list or mid-section without a heading.
 */
object PageVisionTranscriber
{

    /**
     *  Default vision transcription prompt. Instructs the model to convert a
     *  document page image to clean Markdown while preserving structure.
     */
    val defaultPrompt: String =
        """Transcribe the text visible in this document page image into Markdown. This is one page from a multi-page document — it may start mid-sentence, mid-list, or mid-section with no heading at the top. That is normal.
          |
          |Rules:
          |- Output ONLY what is visible on this page. Do not infer, invent, or add headings, titles, or structure that is not present.
          |- Transcribe lists as Markdown lists (- for bullets, 1. for numbered). Continue the list exactly as it appears.
          |- Transcribe headings that are visually present (bold, larger text, underlined titles) using # ## ### as appropriate.
          |- Transcribe tables as Markdown tables.
          |- Preserve bold and italic formatting where visually present. If uncertain, use plain text — do not guess.
          |- Preserve code blocks and inline code where present.
          |- Do NOT add explanatory text, commentary, reasoning, or "Page X of Y" markers.
          |- Do NOT infer or recall text from external sources. Only transcribe what is directly visible.
          |- Read each region ONCE. Commit to your first reading. Do not re-examine or second-guess.
          |- If the page contains no meaningful text, output only: [Empty page]
          |
          |Begin the Markdown output immediately with the first visible content on the page. DO NOT THINK (thinking mode = off).
          |""".stripMargin.trim

    /**
     *  Transcribes document pages to Markdown using batch streaming and parallel VLM calls.
     *
     *  Pages are rendered in batches of `parallelism` via `renderBatch`, then VLM calls for
     *  each batch run concurrently on a fixed thread pool. Results are collected in page order.
     *  Failed VLM calls are retried up to 3 times with exponential back-off (1 s, 2 s, 4 s);
     *  pages that still fail after all retries receive a `[Page N: transcription failed — …]`
     *  placeholder so the rest of the document is not lost.
     *
     *  @param totalPages     Total number of pages/slides in the document.
     *  @param renderBatch    Function `(fromPage, toPage) => List[Array[Byte]]` that renders a
     *                        slice of pages on demand. Indices are 0-based, range is exclusive end.
     *  @param llmProvider    Vision-capable LLM provider.
     *  @param traceId        Trace identifier for logging.
     *  @param prompt         Transcription prompt (defaults to [[defaultPrompt]]).
     *  @param parallelism    Number of concurrent VLM calls per batch (default 1 = sequential).
     *  @param debugImageDir  When `Some(dir)`, each page PNG is saved to `dir` before the VLM call.
     *  @param debugStem      Filename stem for debug image names (e.g. `"IT Support Analyst - India"`).
     *  @param onProgress     Callback `(completedPages, totalPages)` invoked after each page.
     *  @param pageTimeoutMs  Per-page VLM call timeout in milliseconds. 0 = no timeout.
     *                        If any page exceeds this, a [[PageTimeoutException]] is thrown.
     *  @return               Markdown string with `---` separators between pages.
     *  @throws PageTimeoutException if any page VLM call exceeds `pageTimeoutMs`.
     */
    def transcribe(
        totalPages:    Int,
        renderBatch:   (Int, Int) => List[Array[Byte]],
        llmProvider:   LLMProvider,
        traceId:       String,
        prompt:        String           = defaultPrompt,
        parallelism:   Int              = 1,
        debugImageDir: Option[Path]     = None,
        debugStem:     String           = "",
        onProgress:    (Int, Int) => Unit = (_, _) => (),
        pageTimeoutMs: Long             = 0L
    ): String =
    {
        if (totalPages <= 0)
        {
            return "[No pages found in document]"
        }

        val effectiveParallelism = parallelism.max(1)
        TraceLogger.info(traceId, "page_vision_transcriber_start",
            Map("pages" -> totalPages.toString, "model" -> llmProvider.modelName,
                "parallelism" -> effectiveParallelism.toString))

        debugImageDir.foreach { dir =>
            try { java.nio.file.Files.createDirectories(dir) }
            catch
            {
                case t: Throwable =>
                    TraceLogger.warn(traceId, "page_vision_transcriber_debug_dir_failed",
                        Map("error" -> t.getMessage))
            }
        }

        val pool = Executors.newFixedThreadPool(effectiveParallelism)
        val completedPages = new java.util.concurrent.atomic.AtomicInteger(0)

        try
        {
            val allMarkdowns = Array.ofDim[String](totalPages)

            (0 until totalPages).grouped(effectiveParallelism).foreach { batchSeq =>
                val batchList = batchSeq.toList
                val fromPage  = batchList.head
                val toPage    = batchList.last + 1

                TraceLogger.info(traceId, "page_vision_transcriber_batch_render",
                    Map("from" -> fromPage.toString, "to" -> toPage.toString))

                val batchImages: List[Array[Byte]] = renderBatch(fromPage, toPage)

                // Submit parallel VLM calls for this batch
                val futures: List[(Int, JFuture[String])] = batchList.zip(batchImages).map {
                    case (pageIdx: Int, imageBytes: Array[Byte]) =>
                        val future = pool.submit[String](() => {
                            saveDebugImage(imageBytes, pageIdx + 1, debugImageDir, debugStem, traceId)
                            callWithRetry(imageBytes, pageIdx + 1, llmProvider, prompt, traceId)
                        })
                        (pageIdx, future)
                }

                // Collect results in page order (with optional per-page timeout)
                futures.foreach { case (pageIdx: Int, future: JFuture[String]) =>
                    val markdown =
                    {
                        if (pageTimeoutMs > 0)
                        {
                            try { future.get(pageTimeoutMs, TimeUnit.MILLISECONDS) }
                            catch
                            {
                                case _: TimeoutException =>
                                    future.cancel(true)
                                    TraceLogger.error(traceId, "page_vision_transcriber_page_timeout",
                                        Map("page" -> (pageIdx + 1).toString, "timeout_ms" -> pageTimeoutMs.toString))
                                    throw PageTimeoutException(pageIdx + 1, pageTimeoutMs)
                            }
                        }
                        else { future.get() }
                    }
                    allMarkdowns(pageIdx) = markdown
                    val done = completedPages.incrementAndGet()
                    onProgress(done, totalPages)
                }
            }

            val result = allMarkdowns.mkString("\n\n---\n\n")
            TraceLogger.info(traceId, "page_vision_transcriber_complete",
                Map("pages" -> totalPages.toString, "total_chars" -> result.length.toString))
            result
        }
        finally
        {
            pool.shutdownNow()
        }
    }

    /**
     *  Calls the VLM for one page image with exponential back-off retry on failure.
     *  Retries up to 3 times with delays of 1 s, 2 s, and 4 s.
     *  Returns a placeholder string if all attempts fail.
     *
     *  @param imageBytes  Raw PNG bytes for the page.
     *  @param pageNum     1-based page number (for logging and placeholder text).
     *  @param provider    Vision LLM provider.
     *  @param prompt      Transcription prompt.
     *  @param traceId     Trace identifier for logging.
     *  @return            Markdown string from the VLM, or a failure placeholder.
     */
    private def callWithRetry(
        imageBytes: Array[Byte],
        pageNum:    Int,
        provider:   LLMProvider,
        prompt:     String,
        traceId:    String
    ): String =
    {
        val base64  = Base64.getEncoder.encodeToString(imageBytes)
        val dataURI = s"data:image/png;base64,$base64"
        val delays  = List(1000, 2000, 4000)

        def attempt(retriesLeft: List[Int]): String =
        {
            val startMs = System.currentTimeMillis()
            Try(provider.completeVision(dataURI, prompt)) match
            {
                case Success(markdown) =>
                    val elapsedMs = System.currentTimeMillis() - startMs
                    TraceLogger.info(traceId, "page_vision_transcriber_page_complete",
                        Map("page" -> pageNum.toString, "elapsed_ms" -> elapsedMs.toString,
                            "chars" -> markdown.length.toString))
                    markdown

                case Failure(t) if retriesLeft.nonEmpty =>
                    val delay = retriesLeft.head
                    TraceLogger.warn(traceId, "page_vision_transcriber_page_retry",
                        Map("page" -> pageNum.toString, "error" -> t.getMessage,
                            "delay_ms" -> delay.toString, "retries_left" -> retriesLeft.tail.size.toString))
                    Thread.sleep(delay)
                    attempt(retriesLeft.tail)

                case Failure(t) =>
                    TraceLogger.error(traceId, "page_vision_transcriber_page_failed",
                        Map("page" -> pageNum.toString, "error" -> t.getMessage))
                    s"[Page $pageNum: transcription failed — ${t.getMessage}]"
            }
        }

        TraceLogger.info(traceId, "page_vision_transcriber_page",
            Map("page" -> pageNum.toString, "bytes" -> imageBytes.length.toString))
        attempt(delays)
    }

    /**
     *  Saves a page image PNG to the debug directory if debug mode is active.
     *
     *  @param imageBytes    Raw PNG bytes for the page.
     *  @param pageNum       1-based page number used in the filename.
     *  @param debugImageDir Optional debug directory path.
     *  @param debugStem     Filename stem prefix.
     *  @param traceId       Trace identifier for logging.
     */
    private def saveDebugImage(
        imageBytes:    Array[Byte],
        pageNum:       Int,
        debugImageDir: Option[Path],
        debugStem:     String,
        traceId:       String
    ): Unit =
    {
        debugImageDir.foreach { dir =>
            try
            {
                val imgName = if (debugStem.nonEmpty) f"${debugStem}_$pageNum%03d.png" else f"page_$pageNum%03d.png"
                val imgPath = dir.resolve(imgName)
                java.nio.file.Files.write(imgPath, imageBytes)
                TraceLogger.info(traceId, "page_vision_transcriber_debug_image_saved",
                    Map("page" -> pageNum.toString, "path" -> imgPath.toString))
            }
            catch
            {
                case t: Throwable =>
                    TraceLogger.warn(traceId, "page_vision_transcriber_debug_image_failed",
                        Map("page" -> pageNum.toString, "error" -> t.getMessage))
            }
        }
    }

    /**
     *  Exception thrown when a single page VLM call exceeds the configured timeout.
     *
     *  @param pageNum       1-based page number that timed out.
     *  @param timeoutMs     Timeout threshold in milliseconds.
     */
    case class PageTimeoutException(pageNum: Int, timeoutMs: Long)
        extends RuntimeException(s"Page $pageNum exceeded ${timeoutMs / 1000.0}s timeout")

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
