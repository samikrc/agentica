package agentica.misctests

import agentica.doc.{DocFontLoader, PDFPageRenderer, PageVisionTranscriber}
import agentica.doc.PageVisionTranscriber.PageTimeoutException
import agentica.llm.OpenAIProvider
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path, StandardCopyOption}

/**
 *  Standalone comparison test: renders a PDF and runs each configured VLM
 *  provider against every page, writing one Markdown output file per provider.
 *
 *  Edit [[pdfPath]] and [[Providers]] before running.
 *
 *  Run with:
 *    mvn test -pl backend -Dtest=VLMCompareTest -Dsurefire.failIfNoSpecifiedTests=false
 *
 *  Output files are written next to the source PDF, named `<stem>_<label>.md`.
 */
object VLMCompareTest
{
    /** Base URL of the local LM Studio server. Update this if the server's IP address changes. */
    val lmStudioServerURL: String = "http://192.168.2.126:1234"

    /**
     *  Maximum longest-side pixel dimension for page images sent to any VLM.
     *  Rendered pages (at [[PDFPageRenderer.RenderDPI]]) are downscaled to this
     *  size before transcription — keeps vision-encoder preprocessing fast for
     *  dynamic/native-resolution models (e.g. Qwen2.5-VL) without materially
     *  hurting OCR quality for fixed-resolution encoders.
     */
    val maxImageDimension: Int = 1024

    /** Paths to the PDFs to transcribe, resolved from test resources. */
    val pdfPaths: List[java.nio.file.Path] = List(
        java.nio.file.Paths.get(getClass.getResource("/files/IT Support Analyst - India.pdf").toURI),
        java.nio.file.Paths.get(getClass.getResource("/files/Even OPD Policy Document.pdf").toURI)
    )

    /**
     *  List of VLM providers to compare.
     *  Each entry is (label, serverURL, modelName, apiKey).
     *  Label is used in the output filename, e.g. "gemini" → <stem>_gemini.md
     */
    val providers: List[(String, String, String, String)] = List(
        // Local — LM Studio on port 1234
        ("gemma-4-12b", lmStudioServerURL, "google/gemma-4-12b", "lm-studio"),
        ("gemma-4-e4b", lmStudioServerURL, "google/gemma-4-e4b", "lm-studio"),
        ("gemma-4-12b-qat", lmStudioServerURL, "google/gemma-4-12b-qat", "lm-studio"),
        ("glm-4.6v-flash", lmStudioServerURL, "zai-org/glm-4.6v-flash", "lm-studio"),
        ("ministral-3-14b", lmStudioServerURL, "mistralai/ministral-3-14b-reasoning", "lm-studio"),
        ("qwen3.5-9b-mtp", lmStudioServerURL, "qwen3.5-9b-mtp", "lm-studio"),
        ("qwen2.5-vl-7b-instruct", lmStudioServerURL, "qwen2.5-vl-7b-instruct", "lm-studio"),
        ("lfm2.5-vl-3b", lmStudioServerURL, "lfm2.5-vl-3b", "lm-studio")
    )

    // ─── Sweep-mode constants ───

    /** Set to `true` to run the DPI×maxDim parameter sweep, `false` for the fixed single-pass run. */
    val runSweepMode: Boolean = true

    /** DPI values to sweep in the parameter search. */
    val dpiValues: List[Int] = List(50, 100, 150)

    /** Max image dimension values to sweep in the parameter search. */
    val maxDimValues: List[Int] = List(1024, 1200, 1400, 1650)

    /** Per-page timeout for sweep mode (3 minutes). */
    val sweepPageTimeoutMs: Long = 180000L

    /**
     *  Returns true if the server URL targets a local LM Studio instance.
     *  Detects by presence of `192.` or `172.` or `localhost` / `127.` in the host part.
     *
     *  @param serverURL  Full server URL string.
     *  @return           `true` if the host is considered local.
     */
    private def isLocal(serverURL: String): Boolean =
    {
        val host = try { URI.create(serverURL).getHost } catch { case _: Throwable => "" }
        host != null && (
            host.startsWith("192.") ||
            host.startsWith("172.") ||
            host == "localhost"      ||
            host.startsWith("127.")
        )
    }

    /**
     *  Asks LM Studio to unload the given model instance.
     *  Silently swallows all errors — if LM Studio is not running or the
     *  endpoint is unavailable this becomes a no-op.
     *
     *  @param serverURL  Base URL of the LM Studio server (e.g. `http://localhost:1234/v1`).
     *  @param modelName  Model identifier to unload (used as `instance_id`).
     *  @param apiKey     Bearer token for authentication.
     */
    private def unloadLocalModel(serverURL: String, modelName: String, apiKey: String): Unit =
    {
        try
        {
            // Derive the unload endpoint: strip trailing /v1 if present, append /api/v1/models/unload
            val base       = serverURL.stripSuffix("/v1").stripSuffix("/")
            val unloadURL  = s"$base/api/v1/models/unload"
            val body       = s"""{"instance_id": "$modelName"}"""
            val request    = HttpRequest.newBuilder()
                .uri(URI.create(unloadURL))
                .header("Authorization", s"Bearer $apiKey")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val response   = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString())
            println(s"  [unload] $modelName → HTTP ${response.statusCode()}")
        }
        catch
        {
            case t: Throwable =>
                println(s"  [unload] no-op (${t.getClass.getSimpleName}: ${t.getMessage})")
        }
    }

    /**
     *  Queries the LM Studio `/v1/models` endpoint for all loaded models and
     *  unloads each one.  Used as a pre-run cleanup so we start from a clean
     *  state regardless of what was loaded by a previous run or external
     *  activity.  Silently swallows all errors.
     *
     *  @param serverURL  Base URL of the LM Studio server.
     *  @param apiKey     Bearer token for authentication.
     */
    private def unloadAllLocalModels(serverURL: String, apiKey: String): Unit =
    {
        try
        {
            val base      = serverURL.stripSuffix("/v1").stripSuffix("/")
            val modelsURL = s"$base/v1/models"
            val request   = HttpRequest.newBuilder()
                .uri(URI.create(modelsURL))
                .header("Authorization", s"Bearer $apiKey")
                .GET()
                .build()
            val response  = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200)
            {
                // Parse model IDs from the JSON response (simple regex — avoids a JSON lib dependency)
                val modelIds = """"id"\s*:\s*"([^"]+)"""".r.findAllMatchIn(response.body()).map(_.group(1)).toList
                if (modelIds.isEmpty)
                {
                    println(s"  [unload-all] No models currently loaded on $serverURL")
                }
                else
                {
                    println(s"  [unload-all] Found ${modelIds.size} loaded model(s): ${modelIds.mkString(", ")}")
                    for (id <- modelIds)
                    {
                        unloadLocalModel(serverURL, id, apiKey)
                    }
                }
            }
            else
            {
                println(s"  [unload-all] /v1/models returned HTTP ${response.statusCode()} — skipping")
            }
        }
        catch
        {
            case t: Throwable =>
                println(s"  [unload-all] no-op (${t.getClass.getSimpleName}: ${t.getMessage})")
        }
    }

    /**
     *  Entry point: dispatches to either the fixed single-pass run or the
     *  DPI×maxDim parameter sweep based on [[runSweepMode]].
     *
     *  @param args  Unused command-line arguments.
     */
    def main(args: Array[String]): Unit =
    {
        System.setProperty("java.awt.headless", "true")
        DocFontLoader.init()

        if (runSweepMode) { runSweep() }
        else              { runFixed() }
    }

    // ─── Fixed mode (original single-pass) ───

    /**
     *  Runs each configured VLM provider once per PDF at the default DPI and
     *  max image dimension, writing one Markdown file per provider per PDF.
     *  Providers are the outer loop so each local model loads once, processes
     *  all PDFs, then unloads — minimising load/unload cycles.
     */
    private def runFixed(): Unit =
    {
        // Single root temp directory shared by all PDFs, one subfolder per PDF
        val rootWorkDir = Files.createTempDirectory("vlm-compare-")
        println(s"Root work directory : $rootWorkDir")

        // Pre-copy all PDFs into subfolders and cache their metadata
        val pdfInfos = pdfPaths.map { pdfPath =>
            val fileName = pdfPath.getFileName.toString
            val stem     = if (fileName.toLowerCase.endsWith(".pdf")) fileName.dropRight(4) else fileName
            val workDir  = Files.createDirectory(rootWorkDir.resolve(stem))
            val workPDF  = workDir.resolve(fileName)
            Files.copy(pdfPath, workPDF, StandardCopyOption.REPLACE_EXISTING)
            val totalPages   = PDFPageRenderer.pageCount(workPDF)
            val referenceText = MarkdownScorer.extractPDFText(workPDF)
            (pdfPath, stem, workDir, workPDF, totalPages, referenceText)
        }

        // Pre-run cleanup: unload any models already loaded on local servers
        for ((_, serverURL, _, apiKey) <- providers if isLocal(serverURL))
        {
            println(s"\nPre-run cleanup: unloading any loaded models on $serverURL...")
            unloadAllLocalModels(serverURL, apiKey)
        }
        println("Waiting 10s for cleanup to settle...")
        Thread.sleep(10000)

        for ((label, serverURL, modelName, apiKey) <- providers)
        {
            println(s"\n=== Running provider: $label ($modelName @ $serverURL) ===")

            var providerTimedOut = false

            try
            {
                val provider = OpenAIProvider(baseURL = serverURL, modelName = modelName, apiKey = apiKey)
                if (!provider.supportsVision)
                {
                    println(s"  SKIP — $modelName does not report vision support")
                }
                else
                {
                    for ((pdfPath, stem, workDir, workPDF, totalPages, referenceText) <- pdfInfos if !providerTimedOut)
                    {
                        println(s"\n  --- PDF: ${pdfPath.getFileName} ---")

                        val outPath   = workDir.resolve(s"${stem}_${label}.md")
                        val pageTimes = scala.collection.mutable.ArrayBuffer.empty[Long]

                        try
                        {
                            val markdown = PageVisionTranscriber.transcribe(
                                totalPages    = totalPages,
                                renderBatch   = (from, to) => PDFPageRenderer.renderBatch(workPDF, from, to),
                                llmProvider   = provider,
                                traceId       = s"vlm-compare-$label",
                                debugImageDir = Some(workDir),
                                debugStem     = label,
                                pageTimeoutMs = 180000L, // 3 minutes per page
                                onProgress    =
                                {
                                    var pageStartMs = System.currentTimeMillis()
                                    (cur, total) =>
                                    {
                                        val elapsed = System.currentTimeMillis() - pageStartMs
                                        pageTimes += elapsed
                                        println(f"    Page $cur / $total — ${elapsed / 1000.0}%.1f s")
                                        pageStartMs = System.currentTimeMillis()
                                    }
                                }
                            )
                            Files.writeString(outPath, markdown)

                            val totalMs = pageTimes.sum
                            val avgMs   = if (pageTimes.nonEmpty) totalMs / pageTimes.size else 0L
                            println(s"    Written      : $outPath")
                            println(s"    Output size  : ${markdown.length} chars")
                            println(f"    VLM total    : ${totalMs / 1000.0}%.1f s  (avg ${avgMs / 1000.0}%.1f s/page over ${pageTimes.size} pages)")
                            println(s"    ${MarkdownScorer.score(markdown, referenceText, totalPages).pretty}")
                        }
                        catch
                        {
                            case _: PageTimeoutException =>
                                println(s"    TIMEOUT — $label exceeded 3 min on a page. Skipping remaining PDFs for this model.")
                                providerTimedOut = true

                            case t: Throwable =>
                                println(s"    ERROR for $label: ${t.getClass.getSimpleName}: ${t.getMessage}")
                        }
                    }
                }
            }
            catch
            {
                case t: Throwable =>
                    println(s"  ERROR creating provider $label: ${t.getClass.getSimpleName}: ${t.getMessage}")
            }

            // Unload local model after all PDFs for this provider are done (or timeout)
            if (isLocal(serverURL))
            {
                println(s"  Unloading local model: $modelName")
                unloadLocalModel(serverURL, modelName, apiKey)
                println(s"  Waiting 15s for model to fully unload...")
                Thread.sleep(15000)
                println(s"  Done waiting.")
            }
        }

        println("\nDone.")
    }

    // ─── Sweep mode (DPI × maxImageDimension) ───

    /**
     *  Runs the DPI×maxDim parameter sweep for all providers across all PDFs.
     *  Providers are the outer loop so each local model loads once, processes
     *  all PDFs across all DPI/maxDim combinations, then unloads.
     *  Results are collected in memory and a summary table is printed at the end.
     */
    private def runSweep(): Unit =
    {
        // Single root temp directory shared by all PDFs, one subfolder per PDF
        val rootWorkDir = Files.createTempDirectory("vlm-sweep-")
        println(s"Root work directory : $rootWorkDir")
        println(s"DPI values          : ${dpiValues.mkString(", ")}")
        println(s"MaxDim values       : ${maxDimValues.mkString(", ")}")
        println(s"Providers           : ${providers.map(_._1).mkString(", ")}")

        // Pre-copy all PDFs into subfolders and cache their metadata
        val pdfInfos = pdfPaths.map { pdfPath =>
            val fileName = pdfPath.getFileName.toString
            val stem     = if (fileName.toLowerCase.endsWith(".pdf")) fileName.dropRight(4) else fileName
            val workDir  = Files.createDirectory(rootWorkDir.resolve(stem))
            val workPDF  = workDir.resolve(fileName)
            Files.copy(pdfPath, workPDF, StandardCopyOption.REPLACE_EXISTING)
            val totalPages   = PDFPageRenderer.pageCount(workPDF)
            val referenceText = MarkdownScorer.extractPDFText(workPDF)
            (pdfPath, stem, workDir, workPDF, totalPages, referenceText)
        }

        // Collects one SweepResult per (PDF, provider, DPI, maxDim) combination
        val allResults = scala.collection.mutable.ArrayBuffer.empty[SweepResult]

        // Pre-run cleanup: unload any models already loaded on local servers
        for ((_, serverURL, _, apiKey) <- providers if isLocal(serverURL))
        {
            println(s"\nPre-run cleanup: unloading any loaded models on $serverURL...")
            unloadAllLocalModels(serverURL, apiKey)
        }
        println("Waiting 10s for cleanup to settle...")
        Thread.sleep(10000)

        for ((label, serverURL, modelName, apiKey) <- providers)
        {
            println(s"\n=== Sweep provider: $label ($modelName @ $serverURL) ===")

            // Create the provider and check vision support; record N/A for all
            // combos if the model doesn't support vision or fails to initialise
            val provider = try
            {
                val p = OpenAIProvider(baseURL = serverURL, modelName = modelName, apiKey = apiKey)
                if (!p.supportsVision)
                {
                    println(s"  SKIP — $modelName does not report vision support")
                    // Record N/A for all PDFs × all combos
                    for ((_, stem, _, _, _, _) <- pdfInfos; dpi <- dpiValues; maxDim <- maxDimValues)
                    {
                        allResults += SweepResult(stem, label, dpi, maxDim, timedOut = false,
                            NotApplicable, NotApplicable, NotApplicable, NotApplicable,
                            NotApplicable, NotApplicable, NotApplicable, NotApplicable, NotApplicable,
                            "no vision support")
                    }
                    None
                }
                else { Some(p) }
            }
            catch
            {
                case t: Throwable =>
                    println(s"  ERROR creating provider: ${t.getClass.getSimpleName}: ${t.getMessage}")
                    for ((_, stem, _, _, _, _) <- pdfInfos; dpi <- dpiValues; maxDim <- maxDimValues)
                    {
                        allResults += SweepResult(stem, label, dpi, maxDim, timedOut = false,
                            NotApplicable, NotApplicable, NotApplicable, NotApplicable,
                            NotApplicable, NotApplicable, NotApplicable, NotApplicable, NotApplicable,
                            s"provider error: ${t.getMessage}")
                    }
                    None
            }

            provider.foreach { p =>
                // Once a model times out on any combo for any PDF, skip all
                // remaining PDFs and combos for this model
                var modelTimedOut = false

                for ((pdfPath, stem, workDir, workPDF, totalPages, referenceText) <- pdfInfos if !modelTimedOut)
                {
                    println(s"\n  --- PDF: ${pdfPath.getFileName} ---")

                    // Cache: maps DPI → rendered page images at this DPI (before resize).
                    // Per-PDF cache since each PDF has different page dimensions.
                    val renderCache = scala.collection.mutable.Map.empty[Int, List[Array[Byte]]]

                    // Per-PDF no-op tracking: previous successful result at each DPI
                    // to reuse when a larger maxDim would be a no-op resize
                    var prevResultPerDpi = scala.collection.mutable.Map.empty[Int, SweepResult]

                    // Iterate DPI values for this provider (model stays loaded across all DPIs)
                    for (dpi <- dpiValues if !modelTimedOut)
                    {
                        // Render (or get from cache) all pages at this DPI
                        val renderedPages = renderCache.getOrElseUpdate(dpi,
                        {
                            println(s"    Rendering at $dpi DPI...")
                            PDFPageRenderer.renderBatch(workPDF, 0, totalPages, dpi.toFloat)
                        })

                        // Determine the longest side of the first page at this DPI
                        val renderedLongest = if (renderedPages.nonEmpty)
                            PDFPageRenderer.imageLongestSide(renderedPages.head)
                        else 0

                        // Iterate maxDim values; skip no-op combos that reuse prior results
                        for (maxDim <- maxDimValues if !modelTimedOut)
                        {
                            // No-op: maxDim ≥ rendered longest side means resizeToMaxDimension
                            // returns the original image, so the VLM sees identical input
                            val isNoOp = maxDim >= renderedLongest

                            if (isNoOp && prevResultPerDpi.contains(dpi))
                            {
                                // Reuse previous result — same image goes to the VLM
                                val prev = prevResultPerDpi(dpi)
                                println(s"    [DPI=$dpi, maxDim=$maxDim] no-op (rendered longest=$renderedLongest px ≤ $maxDim) — reusing previous result")
                                allResults += prev.copy(maxImageDimension = maxDim)
                            }
                            else
                            {
                                println(s"    [DPI=$dpi, maxDim=$maxDim] running...")

                                // Per-page timing for this (DPI, maxDim) combo
                                val pageTimes = scala.collection.mutable.ArrayBuffer.empty[Long]

                                try
                                {
                                    // Transcribe using cached renders resized to maxDim;
                                    // no debug images written — scoring is done in memory
                                    val markdown = PageVisionTranscriber.transcribe(
                                        totalPages    = totalPages,
                                        renderBatch   = (from, to) =>
                                        {
                                            // Slice cached renders for this DPI and resize to maxDim
                                            val cached = renderCache(dpi)
                                            cached.slice(from, to)
                                                .map(PDFPageRenderer.resizeToMaxDimension(_, maxDim))
                                        },
                                        llmProvider   = p,
                                        traceId       = s"vlm-sweep-$label-dpi$dpi-maxdim$maxDim",
                                        debugImageDir = None,
                                        debugStem     = s"$label-dpi$dpi-maxdim$maxDim",
                                        pageTimeoutMs = sweepPageTimeoutMs,
                                        onProgress    =
                                        {
                                            var pageStartMs = System.currentTimeMillis()
                                            (cur, total) =>
                                            {
                                                val elapsed = System.currentTimeMillis() - pageStartMs
                                                pageTimes += elapsed
                                                println(f"      Page $cur / $total — ${elapsed / 1000.0}%.1f s")
                                                pageStartMs = System.currentTimeMillis()
                                            }
                                        }
                                    )

                                    // Collect statistics and score in memory (no .md files written)
                                    val totalMs = pageTimes.sum
                                    val avgMs   = if (pageTimes.nonEmpty) totalMs / pageTimes.size else 0L
                                    val score   = MarkdownScorer.score(markdown, referenceText, totalPages)
                                    val words   = markdown.split("\\s+").count(_.length > 1)

                                    println(s"      Output: ${markdown.length} chars, $words words, ${totalMs / 1000.0}%.1f s total")
                                    println(s"      ${score.pretty}")

                                    val result = SweepResult(
                                        pdfStem          = stem,
                                        providerLabel    = label,
                                        dpi              = dpi,
                                        maxImageDimension = maxDim,
                                        timedOut         = false,
                                        totalChars       = markdown.length,
                                        totalWords       = words,
                                        totalTimeMs      = totalMs,
                                        avgTimePerPageMs = avgMs,
                                        wordCountRatio   = score.wordCountRatio,
                                        tokenOverlap     = score.tokenOverlap,
                                        headingCount     = score.headingCount,
                                        tableRowCount    = score.tableRowCount,
                                        nonEmptyRatio    = score.nonEmptyRatio,
                                        notes            = ""
                                    )
                                    allResults += result
                                    prevResultPerDpi(dpi) = result
                                }
                                catch
                                {
                                    case _: PageTimeoutException =>
                                        println(s"      TIMEOUT — $label at DPI=$dpi, maxDim=$maxDim. Skipping remaining combos for this model.")
                                        allResults += SweepResult(stem, label, dpi, maxDim, timedOut = true,
                                            NotApplicable, NotApplicable, NotApplicable, NotApplicable,
                                            NotApplicable, NotApplicable, NotApplicable, NotApplicable, NotApplicable,
                                            "timeout")
                                        modelTimedOut = true

                                    case t: Throwable =>
                                        println(s"      ERROR: ${t.getClass.getSimpleName}: ${t.getMessage}")
                                        allResults += SweepResult(stem, label, dpi, maxDim, timedOut = false,
                                            NotApplicable, NotApplicable, NotApplicable, NotApplicable,
                                            NotApplicable, NotApplicable, NotApplicable, NotApplicable, NotApplicable,
                                            s"error: ${t.getMessage}")
                                        // Don't set modelTimedOut — try next combo
                                }
                            }
                        }
                    }
                }

                // Unload local model after all PDFs and DPI/maxDim combos for this provider are done
                if (isLocal(serverURL))
                {
                    println(s"  Unloading local model: $modelName")
                    unloadLocalModel(serverURL, modelName, apiKey)
                    println(s"  Waiting 15s for model to fully unload...")
                    Thread.sleep(15000)
                    println(s"  Done waiting.")
                }
            }
        }

        println("\n\n========== SWEEP RESULTS ==========\n")
        printSummaryTable(allResults.toList)
        println("\nDone.")
    }

    /**
     *  Prints a CSV-like summary table of all sweep results.
     *  One row per (PDF, provider, DPI, maxDim) combination.
     *
     *  @param results  All collected sweep results.
     */
    private def printSummaryTable(results: List[SweepResult]): Unit =
    {
        // Header
        val header = List(
            "PDF", "Provider", "DPI", "MaxDim",
            "Chars", "Words", "TotalS", "AvgPageS",
            "WordRatio", "TokenOvl", "Headings", "TableRows", "NonEmpty%",
            "Notes"
        )
        println(header.mkString(" | "))
        println("-" * 120)

        for (r <- results)
        {
            def fmtTime(v: Any): String = v match
            {
                case ms: Long  => f"${ms.toDouble / 1000.0}%.1f"
                case ms: Int   => f"${ms.toDouble / 1000.0}%.1f"
                case _         => "N/A"
            }
            def fmtNum(v: Any): String = v match
            {
                case d: Double => f"$d%.2f"
                case i: Int    => i.toString
                case l: Long   => l.toString
                case _         => "N/A"
            }
            def fmtPct(v: Any): String = v match
            {
                case d: Double => f"${d * 100}%.0f%%"
                case _         => "N/A"
            }
            def fmtStr(v: Any): String = v match
            {
                case NotApplicable => "N/A"
                case s             => s.toString
            }

            val row = List(
                r.pdfStem, r.providerLabel, r.dpi.toString, r.maxImageDimension.toString,
                fmtStr(r.totalChars), fmtStr(r.totalWords), fmtTime(r.totalTimeMs), fmtTime(r.avgTimePerPageMs),
                fmtNum(r.wordCountRatio), fmtNum(r.tokenOverlap), fmtNum(r.headingCount), fmtNum(r.tableRowCount), fmtPct(r.nonEmptyRatio),
                r.notes
            )
            println(row.mkString(" | "))
        }
    }
}

/**
 *  Result of a single (PDF, provider, DPI, maxImageDimension) sweep combination.
 *
 *  @param pdfStem           PDF filename stem.
 *  @param providerLabel     VLM provider label.
 *  @param dpi               Render DPI used.
 *  @param maxImageDimension Max image dimension (longest side) after resize.
 *  @param timedOut          `true` if this combo timed out.
 *  @param totalChars        Total character count of VLM Markdown output.
 *  @param totalWords        Total word count of VLM Markdown output.
 *  @param totalTimeMs       Total VLM processing time in milliseconds.
 *  @param avgTimePerPageMs  Average time per page in milliseconds.
 *  @param wordCountRatio    VLM word count ÷ reference word count (0–1).
 *  @param tokenOverlap      Fraction of reference vocabulary tokens present in VLM output (0–1).
 *  @param headingCount      Number of Markdown headings found.
 *  @param tableRowCount     Number of Markdown table rows found.
 *  @param nonEmptyRatio     Ratio of non-empty pages to total pages (0–1).
 *  @param notes             Additional notes (e.g. "timeout", "no vision support", error message).
 */
case class SweepResult(
    pdfStem:           String,
    providerLabel:     String,
    dpi:               Int,
    maxImageDimension: Int,
    timedOut:          Boolean,
    totalChars:        Any,
    totalWords:        Any,
    totalTimeMs:       Any,
    avgTimePerPageMs:  Any,
    wordCountRatio:    Any,
    tokenOverlap:      Any,
    headingCount:      Any,
    tableRowCount:     Any,
    nonEmptyRatio:     Any,
    notes:             String
)

/**
 *  Sentinel value for fields that are not applicable (timeout, error, no vision).
 */
object NotApplicable

