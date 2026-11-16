package agentica.misctests

import agentica.doc.{DocFontLoader, PDFPageRenderer, PageVisionTranscriber}
import agentica.doc.PageVisionTranscriber.PageTimeoutException
import agentica.llm.OpenAIProvider
import agentica.testutil.LMStudioClient
import java.nio.file.{Files, Path, StandardCopyOption}

/**
 *  Standalone comparison test: renders a PDF and runs each configured VLM
 *  provider against every page, writing one Markdown output file per provider.
 *
 *  Edit [[pdfPaths]] and [[providers]] before running.
 *
 *  Run from backend/ with:
 *    mvn test-compile exec:exec -Dexec.classpathScope=test \
 *      -Dexec.args="-Djava.awt.headless=true -Dorg.jboss.logging.provider=jdk -classpath %classpath agentica.misctests.VLMCompareTest"
 *  Append --sweep inside exec.args for the default grid, or
 *  --dpi 150 --max-dimension 1650 for a fixed override.
 *  Sweep overrides: --dpi-values 50,100,150 --max-dimensions 1024,1200,1400,1650.
 *  Select models by label with --providers lfm2.5-vl-3b,lfm3-vl-2b (default: all of [[providers]]).
 *  Select PDFs by case-insensitive filename substring with --pdfs whitepaper,nifty50 (default: all of [[pdfPaths]]).
 *  PDFs with a `<stem>.facts.json` next to them are also fact-checked (see [[MarkdownScorer.checkFacts]]).
 *
 *  Markdown and results.json are saved under the printed temporary work directory.
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
    val maxImageDimension: Int = PDFPageRenderer.MaxImageDimension

    /** Paths to the PDFs to transcribe, resolved from test resources. */
    val pdfPaths: List[java.nio.file.Path] = List(
        java.nio.file.Paths.get(getClass.getResource("/files/IT Support Analyst - India.pdf").toURI),
        java.nio.file.Paths.get(getClass.getResource("/files/Even OPD Policy Document.pdf").toURI),
        java.nio.file.Paths.get(getClass.getResource("/files/ind_nifty50.pdf").toURI),
        java.nio.file.Paths.get(getClass.getResource("/files/nifty-50-whitepaper-2024.pdf").toURI)
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
        ("lfm2.5-vl-3b", lmStudioServerURL, "lfm2.5-vl-3b", "lm-studio"),
        ("lfm3-vl-2b", lmStudioServerURL, "lfm3-vl-2b", "lm-studio"),
        ("lfm3-vl-2b-nothink", lmStudioServerURL, "lfm3-vl-2b", "lm-studio")
    )

    /**
     *  Per-label `reasoning_effort` request values. LM Studio's per-model thinking toggle does not
     *  apply to API requests; `"none"` disables LFM3 reasoning per request.
     */
    val providerReasoningEffort: Map[String, String] = Map("lfm3-vl-2b-nothink" -> "none")

    // ─── Sweep-mode constants ───

    /** DPI values to sweep in the parameter search. */
    val dpiValues: List[Int] = List(50, 100, 150)

    /** Max image dimension values to sweep in the parameter search. */
    val maxDimValues: List[Int] = List(1024, 1200, 1400, 1650)

    /** Per-page timeout for sweep mode (3 minutes). */
    val sweepPageTimeoutMs: Long = 180000L

    /**
     *  Validated image configuration for a comparison run.
     *  @param sweep Whether to evaluate a grid instead of one fixed configuration.
     *  @param dpis Positive rendering DPI values.
     *  @param maxDimensions Positive longest-side caps in pixels.
     *  @param providerLabels Labels from [[providers]] to run, in configured order.
     *  @param pdfNames File names from [[pdfPaths]] to run, in configured order.
     */
    case class CompareOptions(sweep: Boolean, dpis: List[Int], maxDimensions: List[Int],
                              providerLabels: List[String] = providers.map(_._1),
                              pdfNames: List[String] = pdfPaths.map(_.getFileName.toString))

    /**
     *  Parses image settings, provider selection, and PDF selection before any model calls.
     *  @param args Command-line flags and values.
     *  @return Validated options using production image defaults for fixed runs and all providers and PDFs by default.
     *  @throws IllegalArgumentException if flags conflict, values are invalid, a provider label is unknown,
     *                                   or a PDF filter matches no file.
     */
    def parseOptions(args: List[String]): CompareOptions =
    {
        val values = scala.collection.mutable.Map.empty[String, String]
        var sweep = false
        var rest = args
        val flags = Set("--dpi", "--max-dimension", "--dpi-values", "--max-dimensions", "--providers", "--pdfs")
        while (rest.nonEmpty)
        {
            rest match
            {
                case "--sweep" :: tail =>
                    require(!sweep, "Duplicate --sweep")
                    sweep = true
                    rest = tail
                case flag :: value :: tail if flags.contains(flag) =>
                    require(!values.contains(flag), s"Duplicate $flag")
                    values(flag) = value
                    rest = tail
                case _ => throw IllegalArgumentException(s"Unknown or incomplete option: ${rest.head}")
            }
        }
        require(!sweep || !values.keys.exists(Set("--dpi", "--max-dimension")),
            "Fixed overrides cannot be used with --sweep")
        require(sweep || !values.keys.exists(Set("--dpi-values", "--max-dimensions")),
            "Grid overrides require --sweep")
        def numbers(flag: String, fallback: List[Int]): List[Int] =
        {
            val result = values.get(flag).map(_.split(",", -1).toList.map { token =>
                token.trim.toIntOption.filter(_ > 0).getOrElse(
                    throw IllegalArgumentException(s"$flag requires positive integers"))
            }).getOrElse(fallback)
            require(result.nonEmpty && result.distinct.size == result.size, s"Invalid or duplicate values for $flag")
            require(sweep || result.size == 1, s"$flag requires one value")
            result
        }
        val known = providers.map(_._1)
        val labels = values.get("--providers").map(_.split(",", -1).toList.map(_.trim)).getOrElse(known)
        require(labels.nonEmpty && labels.forall(_.nonEmpty) && labels.distinct.size == labels.size,
            "--providers requires distinct, non-empty labels")
        labels.find(l => !known.contains(l)).foreach { l =>
            throw IllegalArgumentException(s"Unknown provider label '$l'; known: ${known.mkString(", ")}")
        }
        val allPDFs = pdfPaths.map(_.getFileName.toString)
        val pdfNames = values.get("--pdfs").map { spec =>
            val filters = spec.split(",", -1).toList.map(_.trim.toLowerCase)
            require(filters.forall(_.nonEmpty), "--pdfs requires non-empty filename filters")
            filters.find(f => !allPDFs.exists(_.toLowerCase.contains(f))).foreach { f =>
                throw IllegalArgumentException(s"No PDF matches '$f'; known: ${allPDFs.mkString(", ")}")
            }
            allPDFs.filter(name => filters.exists(name.toLowerCase.contains))
        }.getOrElse(allPDFs)
        CompareOptions(sweep,
            numbers(if (sweep) "--dpi-values" else "--dpi", if (sweep) dpiValues else List(PDFPageRenderer.RenderDPI.toInt)),
            numbers(if (sweep) "--max-dimensions" else "--max-dimension", if (sweep) maxDimValues else List(PDFPageRenderer.MaxImageDimension)),
            labels, pdfNames)
    }

    /**
     *  Determines whether every page remains at original resolution under a size cap.
     *  @param longestSides Longest pixel dimensions of all rendered pages.
     *  @param maxDimension Requested pixel cap.
     *  @return True only for a non-empty document with no page requiring resizing.
     */
    def originalResolution(longestSides: List[Int], maxDimension: Int): Boolean =
        longestSides.nonEmpty && longestSides.forall(_ <= maxDimension)

    /**
     *  Applies the production resizing operation to all page images.
     *  @param pages Rendered PNG page images.
     *  @param maxDimension Longest-side cap, defaulting to the production setting.
     *  @return Resized PNG images in page order, without upscaling.
     */
    def prepareImages(pages: List[Array[Byte]], maxDimension: Int = PDFPageRenderer.MaxImageDimension): List[Array[Byte]] =
        pages.map(PDFPageRenderer.resizeToMaxDimension(_, maxDimension))

    /**
     *  Selects a successful original-resolution result only when all page inputs are unchanged.
     *  @param previous Previously measured result at the same DPI for this model and document.
     *  @param longestSides Longest dimensions of all rendered pages.
     *  @param maxDimension Requested size cap.
     *  @return The reusable result, or None when either configuration resizes a page.
     */
    def reusableResult(previous: Option[SweepResult], longestSides: List[Int], maxDimension: Int): Option[SweepResult] =
        previous.filter(r => r.status == "completed" && r.failedPages == 0 &&
            originalResolution(longestSides, r.maxImageDimension) && originalResolution(longestSides, maxDimension))

    /**
     *  Runs the shared comparison pipeline using fixed production defaults or an opt-in sweep.
     *
     *  @param args Image configuration flags accepted by [[parseOptions]].
     *  @throws IllegalArgumentException if the requested configuration is invalid.
     */
    def main(args: Array[String]): Unit =
    {
        val options = parseOptions(args.toList)
        System.setProperty("java.awt.headless", "true")
        DocFontLoader.init()

        runSweep(options)
    }

    // ─── Comparison run (fixed or DPI × maxImageDimension sweep) ───

    /**
     *  Runs the DPI×maxDim parameter sweep for all providers across all PDFs.
     *  Providers are the outer loop so each local model loads once, processes
     *  all PDFs across all DPI/maxDim combinations, then unloads.
     *  Results are collected in memory and a summary table is printed at the end.
     */
    private def runSweep(options: CompareOptions): Unit =
    {
        val dpiValues = options.dpis
        val maxDimValues = options.maxDimensions
        val providers = options.providerLabels.flatMap(l => VLMCompareTest.providers.find(_._1 == l))
        // Single root temp directory shared by all PDFs, one subfolder per PDF
        val rootWorkDir = Files.createTempDirectory("vlm-compare-")
        println(s"Mode                : ${if (options.sweep) "sweep" else "fixed"} (PNG)")
        println(s"Root work directory : $rootWorkDir")
        println(s"DPI values          : ${dpiValues.mkString(", ")}")
        println(s"MaxDim values       : ${maxDimValues.mkString(", ")}")
        println(s"Providers           : ${providers.map(_._1).mkString(", ")}")
        println(s"PDFs                : ${options.pdfNames.mkString(", ")}")

        // Pre-copy selected PDFs (and any facts file) into subfolders and cache their metadata
        val factsByStem = scala.collection.mutable.Map.empty[String, List[ExpectedFact]]
        val pdfInfos = pdfPaths.filter(p => options.pdfNames.contains(p.getFileName.toString)).map { pdfPath =>
            val fileName = pdfPath.getFileName.toString
            val stem     = if (fileName.toLowerCase.endsWith(".pdf")) fileName.dropRight(4) else fileName
            val workDir  = Files.createDirectory(rootWorkDir.resolve(stem))
            val workPDF  = workDir.resolve(fileName)
            Files.copy(pdfPath, workPDF, StandardCopyOption.REPLACE_EXISTING)
            val factsFile = pdfPath.resolveSibling(s"$stem.facts.json")
            if (Files.isRegularFile(factsFile))
            {
                factsByStem(stem) = MarkdownScorer.loadFacts(factsFile)
                Files.copy(factsFile, workDir.resolve(factsFile.getFileName), StandardCopyOption.REPLACE_EXISTING)
                println(s"Facts               : ${factsByStem(stem).size} for $stem")
            }
            val totalPages     = PDFPageRenderer.pageCount(workPDF)
            val referencePages = MarkdownScorer.extractPDFPageTexts(workPDF)
            (pdfPath, stem, workDir, workPDF, totalPages, referencePages)
        }

        // Collects one SweepResult per (PDF, provider, DPI, maxDim) combination
        val allResults = scala.collection.mutable.ArrayBuffer.empty[SweepResult]

        // Pre-run cleanup: unload any models already loaded on local servers
        for ((_, serverURL, _, apiKey) <- providers.distinctBy(_._2) if LMStudioClient.isLocal(serverURL))
        {
            println(s"\nPre-run cleanup: unloading any loaded models on $serverURL...")
            LMStudioClient.unloadAllLocalModels(serverURL, apiKey)
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
                val p = OpenAIProvider(baseURL = serverURL, modelName = modelName, apiKey = apiKey,
                    reasoningEffort = providerReasoningEffort.get(label))
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

                for ((pdfPath, stem, workDir, workPDF, totalPages, referencePages) <- pdfInfos if !modelTimedOut)
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

                        // Determine the longest sides of every page at this DPI
                        val longestSides = renderedPages.map(PDFPageRenderer.imageLongestSide)
                        val renderedLongest = longestSides.maxOption.getOrElse(0)

                        // Iterate maxDim values; skip no-op combos that reuse prior results
                        for (maxDim <- maxDimValues if !modelTimedOut)
                        {
                            // No-op: maxDim ≥ rendered longest side means resizeToMaxDimension
                            // returns the original image, so the VLM sees identical input
                            val isNoOp = originalResolution(longestSides, maxDim)

                            val reusable = reusableResult(prevResultPerDpi.get(dpi), longestSides, maxDim)
                            if (reusable.nonEmpty)
                            {
                                // Reuse previous result — same image goes to the VLM
                                val prev = reusable.get
                                println(s"    [DPI=$dpi, maxDim=$maxDim] no-op (rendered longest=$renderedLongest px ≤ $maxDim) — reusing previous result")
                                allResults += prev.copy(maxImageDimension = maxDim,
                                    status = "reused", reusedFrom = Some(prev.maxImageDimension),
                                    notes = s"Reused original-resolution input from maxDim=${prev.maxImageDimension}")
                                saveResults(rootWorkDir, options, allResults.toList)
                            }
                            else
                            {
                                println(s"    [DPI=$dpi, maxDim=$maxDim] running...")

                                // Per-page timing for this (DPI, maxDim) combo
                                val pageTimes = scala.collection.mutable.ArrayBuffer.empty[Long]
                                var dimensions = List.empty[(Int, Int)]
                                var started = System.nanoTime()

                                try
                                {
                                    // Transcribe using cached renders resized to maxDim;
                                    // no debug images written — scoring is done in memory
                                    val inputPages = prepareImages(renderCache(dpi), maxDim)
                                    dimensions = inputPages.map { bytes =>
                                        val image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes))
                                        (image.getWidth, image.getHeight)
                                    }
                                    started = System.nanoTime()
                                    val pages = PageVisionTranscriber.transcribePages(
                                        totalPages    = totalPages,
                                        renderBatch   = (from, to) =>
                                        {
                                            // Slice cached renders for this DPI and resize to maxDim
                                            inputPages.slice(from, to)
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

                                    // Collect statistics and persist Markdown for inspection
                                    val totalMs = (System.nanoTime() - started) / 1000000L
                                    val avgMs   = if (totalPages > 0) totalMs / totalPages else 0L
                                    val markdown = pages.mkString(PageVisionTranscriber.PageSeparator)
                                    val baseName = s"${stem}_${label}_dpi${dpi}_max${maxDim}"
                                    val outPath = workDir.resolve(s"$baseName.md")
                                    Files.writeString(outPath, markdown)
                                    Files.writeString(workDir.resolve(s"$baseName.pages.json"),
                                        ujson.write(ujson.Arr.from(pages.map(ujson.Str(_))), indent = 2))
                                    val failedPages = pages.count(page =>
                                        page.startsWith("[Page ") && page.contains(": transcription failed"))
                                    val score   = MarkdownScorer.score(markdown, referencePages.mkString("\n"), totalPages)
                                    val textScore = MarkdownScorer.scoreTextPages(pages, referencePages)
                                    val factReport = factsByStem.get(stem).map(MarkdownScorer.checkFacts(pages, _))
                                    val words   = markdown.split("\\s+").count(_.length > 1)

                                    println(f"      Output: ${markdown.length} chars, $words words, ${totalMs / 1000.0}%.1f s total")
                                    println(s"      ${score.pretty}")
                                    println(textScore.pretty)
                                    factReport.foreach(r => println(r.pretty))

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
                                        notes            = "",
                                        status           = if (failedPages > 0) "partial-error" else "completed",
                                        model            = modelName,
                                        pageDimensions   = dimensions,
                                        markdownPath     = Some(outPath.toString),
                                        failedPages      = failedPages,
                                        textScore        = Some(textScore),
                                        factReport       = factReport
                                    )
                                    allResults += result
                                    saveResults(rootWorkDir, options, allResults.toList)
                                    if (isNoOp && failedPages == 0) { prevResultPerDpi(dpi) = result }
                                }
                                catch
                                {
                                    case _: PageTimeoutException =>
                                        println(s"      TIMEOUT — $label at DPI=$dpi, maxDim=$maxDim. Skipping remaining combos for this model.")
                                        allResults += SweepResult(stem, label, dpi, maxDim, timedOut = true,
                                            NotApplicable, NotApplicable, NotApplicable, NotApplicable,
                                            NotApplicable, NotApplicable, NotApplicable, NotApplicable, NotApplicable,
                                            "timeout", status = "timeout", model = modelName, pageDimensions = dimensions)
                                            .copy(totalTimeMs = (System.nanoTime() - started) / 1000000L)
                                        modelTimedOut = true

                                    case t: Throwable =>
                                        println(s"      ERROR: ${t.getClass.getSimpleName}: ${t.getMessage}")
                                        allResults += SweepResult(stem, label, dpi, maxDim, timedOut = false,
                                            NotApplicable, NotApplicable, NotApplicable, NotApplicable,
                                            NotApplicable, NotApplicable, NotApplicable, NotApplicable, NotApplicable,
                                            s"error: ${t.getMessage}", status = "error", model = modelName, pageDimensions = dimensions)
                                            .copy(totalTimeMs = (System.nanoTime() - started) / 1000000L)
                                        // Don't set modelTimedOut — try next combo
                                }
                                saveResults(rootWorkDir, options, allResults.toList)
                            }
                        }
                    }
                }

                for ((_, stem, _, _, _, _) <- pdfInfos; dpi <- dpiValues; maxDim <- maxDimValues
                     if !allResults.exists(r => r.pdfStem == stem && r.providerLabel == label && r.dpi == dpi && r.maxImageDimension == maxDim))
                {
                    allResults += SweepResult(stem, label, dpi, maxDim, timedOut = false,
                        NotApplicable, NotApplicable, NotApplicable, NotApplicable,
                        NotApplicable, NotApplicable, NotApplicable, NotApplicable, NotApplicable,
                        "skipped after model timeout", status = "skipped", model = modelName)
                }
                saveResults(rootWorkDir, options, allResults.toList)

                // Unload local model after all PDFs and DPI/maxDim combos for this provider are done
                if (LMStudioClient.isLocal(serverURL))
                {
                    println(s"  Unloading local model: $modelName")
                    LMStudioClient.unloadLocalModel(serverURL, modelName, apiKey)
                    println(s"  Waiting 15s for model to fully unload...")
                    Thread.sleep(15000)
                    println(s"  Done waiting.")
                }
            }
        }

        println("\n\n========== SWEEP RESULTS ==========\n")
        saveResults(rootWorkDir, options, allResults.toList)
        printSummaryTable(allResults.toList)
        println(s"\nArtifacts: $rootWorkDir")
        println("\nDone.")
    }

    /**
     *  Saves a structured snapshot after completed configurations, without credentials.
     *  @param directory Run artifact directory.
     *  @param options Requested image configuration and providers.
     *  @param results Results collected so far.
     */
    def saveResults(directory: Path, options: CompareOptions, results: List[SweepResult]): Unit =
    {
        def number(value: Any): ujson.Value = value match
        {
            case n: Int => ujson.Num(n.toDouble)
            case n: Long => ujson.Num(n.toDouble)
            case n: Double => ujson.Num(n)
            case _ => ujson.Null
        }
        def ratio(value: Option[Double]): ujson.Value = value.map(ujson.Num(_)).getOrElse(ujson.Null)
        def counts(c: TextCounts): ujson.Obj = ujson.Obj(
            "wordF1" -> ratio(c.wordF1), "wordRecall" -> ratio(c.wordRecall), "wordPrecision" -> ratio(c.wordPrecision),
            "numberRecall" -> ratio(c.numberRecall), "numberPrecision" -> ratio(c.numberPrecision),
            "orderSimilarity" -> ratio(c.orderSimilarity),
            "refWords" -> c.refWords, "outWords" -> c.outWords, "matchedWords" -> c.matchedWords,
            "refNumbers" -> c.refNumbers, "outNumbers" -> c.outNumbers, "matchedNumbers" -> c.matchedNumbers,
            "wordEdits" -> c.wordEdits)
        def text(score: TextScore): ujson.Obj =
        {
            val json = counts(score.total)
            json("pages") = ujson.Arr.from(score.pages.map { p =>
                val page = counts(p.counts)
                page("page") = p.page
                page("missingNumbers") = ujson.Arr.from(p.missingNumbers.map(ujson.Str(_)))
                page("extraNumbers") = ujson.Arr.from(p.extraNumbers.map(ujson.Str(_)))
                page
            })
            json
        }
        def facts(report: FactReport): ujson.Obj = ujson.Obj(
            "total" -> report.results.size, "strictPass" -> report.strictPasses, "lenientPass" -> report.lenientPasses,
            "outcomes" -> ujson.Obj.from(report.outcomeCounts.map { case (l, c) => l -> ujson.Num(c) }),
            "byType" -> ujson.Obj.from(report.byType.map { case (k, (s, l, t)) =>
                k -> ujson.Obj("strictPass" -> s, "lenientPass" -> l, "total" -> t) }),
            "results" -> ujson.Arr.from(report.results.map { r =>
                ujson.Obj("id" -> r.fact.id, "page" -> r.fact.page, "type" -> r.fact.kind, "outcome" -> r.outcome.label,
                    "detail" -> r.detail, "observed" -> r.observed.map(ujson.Str(_)).getOrElse(ujson.Null))
            }))
        val rows = results.map { r =>
            val status = if (r.status.nonEmpty) r.status
                else if (r.timedOut) "timeout"
                else if (r.notes == "no vision support") "unsupported"
                else "error"
            ujson.Obj(
                "pdf" -> r.pdfStem, "provider" -> r.providerLabel,
                "model" -> (if (r.model.nonEmpty) r.model else providers.find(_._1 == r.providerLabel).map(_._3).getOrElse("")),
                "dpi" -> r.dpi, "maxDimension" -> r.maxImageDimension,
                "status" -> status, "notes" -> r.notes,
                "pageDimensions" -> ujson.Arr.from(r.pageDimensions.map { case (w, h) => ujson.Obj("width" -> w, "height" -> h) }),
                "markdownPath" -> r.markdownPath.map(ujson.Str(_)).getOrElse(ujson.Null),
                "reusedFromMaxDimension" -> r.reusedFrom.map(n => ujson.Num(n.toDouble)).getOrElse(ujson.Null),
                "failedPages" -> (if (Set("completed", "partial-error", "reused").contains(status)) ujson.Num(r.failedPages.toDouble) else ujson.Null),
                "transcriptionWallTimeMs" -> (if (r.reusedFrom.nonEmpty) ujson.Null else number(r.totalTimeMs)),
                "averageWallTimePerPageMs" -> (if (r.reusedFrom.nonEmpty) ujson.Null else number(r.avgTimePerPageMs)),
                "chars" -> number(r.totalChars), "words" -> number(r.totalWords),
                "wordCountRatio" -> number(r.wordCountRatio), "tokenOverlap" -> number(r.tokenOverlap),
                "headings" -> number(r.headingCount), "tableRows" -> number(r.tableRowCount),
                "nonEmptyRatio" -> number(r.nonEmptyRatio),
                "text" -> r.textScore.map(text).getOrElse(ujson.Null),
                "facts" -> r.factReport.map(facts).getOrElse(ujson.Null))
        }
        val json = ujson.Obj("mode" -> (if (options.sweep) "sweep" else "fixed"),
            "providers" -> ujson.Arr.from(options.providerLabels), "pdfs" -> ujson.Arr.from(options.pdfNames),
            "format" -> "PNG", "dpis" -> ujson.Arr.from(options.dpis),
            "maxDimensions" -> ujson.Arr.from(options.maxDimensions), "results" -> ujson.Arr.from(rows))
        Files.writeString(directory.resolve("results.json"), ujson.write(json, indent = 2))
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
            "WordF1", "NumR", "NumP", "Order", "FactsStrict", "FactsLenient",
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
                fmtStr(r.totalChars), fmtStr(r.totalWords),
                (if (r.reusedFrom.nonEmpty) "reused" else fmtTime(r.totalTimeMs)),
                (if (r.reusedFrom.nonEmpty) "reused" else fmtTime(r.avgTimePerPageMs)),
                fmtNum(r.wordCountRatio), fmtNum(r.tokenOverlap), fmtNum(r.headingCount), fmtNum(r.tableRowCount), fmtPct(r.nonEmptyRatio),
                r.textScore.map(t => TextCounts.fmt(t.total.wordF1)).getOrElse("N/A"),
                r.textScore.map(t => TextCounts.fmt(t.total.numberRecall)).getOrElse("N/A"),
                r.textScore.map(t => TextCounts.fmt(t.total.numberPrecision)).getOrElse("N/A"),
                r.textScore.map(t => TextCounts.fmt(t.total.orderSimilarity)).getOrElse("N/A"),
                r.factReport.map(f => s"${f.strictPasses}/${f.results.size}").getOrElse("N/A"),
                r.factReport.map(f => s"${f.lenientPasses}/${f.results.size}").getOrElse("N/A"),
                r.notes
            )
            println(row.mkString(" | "))
        }

        // Totals per provider and configuration, weighted by reference size across PDFs
        println("\nTotals per provider (all PDFs; text metrics weighted by reference size):")
        println(List("Provider", "DPI", "MaxDim", "PDFs", "Pages", "FailedPages", "TotalS",
            "WordF1", "WordR", "WordP", "NumR", "NumP", "Order").mkString(" | "))
        println("-" * 120)
        for (((label, dpi, maxDim), group) <- results.groupBy(r => (r.providerLabel, r.dpi, r.maxImageDimension))
                 .toList.sortBy { case ((l, d, m), _) => (results.indexWhere(_.providerLabel == l), d, m) })
        {
            val scored = group.flatMap(_.textScore)
            val total  = scored.map(_.total).foldLeft(TextCounts.Zero)(_ + _)
            val timeMs = group.filter(_.reusedFrom.isEmpty).map(_.totalTimeMs).collect { case ms: Long => ms }.sum
            println(List(label, dpi.toString, maxDim.toString, s"${scored.size}/${group.size}",
                scored.map(_.pages.size).sum.toString, group.map(_.failedPages).sum.toString,
                f"${timeMs / 1000.0}%.1f",
                TextCounts.fmt(total.wordF1), TextCounts.fmt(total.wordRecall), TextCounts.fmt(total.wordPrecision),
                TextCounts.fmt(total.numberRecall), TextCounts.fmt(total.numberPrecision),
                TextCounts.fmt(total.orderSimilarity)).mkString(" | "))
        }

        val factRuns = results.filter(_.factReport.nonEmpty)
        if (factRuns.nonEmpty)
        {
            println("\nFact checks per provider (strict/lenient/total):")
            for (r <- factRuns; report <- r.factReport)
            {
                val types = report.byType.map { case (k, (s, l, t)) => s"$k $s/$l/$t" }.mkString(", ")
                println(s"${r.pdfStem} | ${r.providerLabel} | ${r.dpi} | ${r.maxImageDimension} | " +
                    s"all ${report.strictPasses}/${report.lenientPasses}/${report.results.size} | $types | " +
                    report.outcomeCounts.map { case (l, c) => s"$l $c" }.mkString(", "))
            }
            println("\nFacts with different outcomes across providers:")
            println(factDisagreements(factRuns).mkString("\n"))
        }
    }

    /**
     *  Lists facts whose outcome differs between results for the same PDF, with each result's outcome and observation.
     *
     *  @param results  Results that carry a [[FactReport]].
     *  @return         Report lines, or a single line saying all outcomes agree.
     */
    def factDisagreements(results: List[SweepResult]): List[String] =
    {
        val lines = results.groupBy(_.pdfStem).toList.sortBy(_._1).flatMap { case (stem, group) =>
            val reports = group.flatMap(r => r.factReport.map(rep => s"${r.providerLabel}@${r.dpi}/${r.maxImageDimension}" -> rep))
            reports.headOption.toList.flatMap(_._2.results.map(_.fact)).flatMap { fact =>
                val byRun = reports.map { case (run, rep) => run -> rep.results.find(_.fact.id == fact.id) }
                if (byRun.flatMap(_._2.map(_.outcome)).distinct.size <= 1) Nil
                else s"  $stem ${fact.id} (p${fact.page}, ${fact.kind})" :: byRun.map { case (run, res) =>
                    s"    $run: " + res.map(r => r.outcome.label + r.observed.map(o => s" — ${o.take(120)}").getOrElse("")).getOrElse("n/a")
                }
            }
        }
        if (lines.isEmpty) List("  (all outcomes agree)") else lines
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
 *  @param status            Completion, reuse, partial-error, or skip status.
 *  @param model             Model identifier without credentials.
 *  @param pageDimensions    Effective PNG width and height for each page.
 *  @param markdownPath      Saved transcription, or the original artifact for reused results.
 *  @param reusedFrom        Original measured configuration's maximum dimension.
 *  @param failedPages       Number of transcription failure placeholders.
 *  @param textScore         Per-page text fidelity against the PDF text layer, when transcribed.
 *  @param factReport        Expected-fact results, when the PDF has a facts file.
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
    notes:             String,
    status:            String = "",
    model:             String = "",
    pageDimensions:    List[(Int, Int)] = Nil,
    markdownPath:      Option[String] = None,
    reusedFrom:        Option[Int] = None,
    failedPages:       Int = 0,
    textScore:         Option[TextScore] = None,
    factReport:        Option[FactReport] = None
)

/**
 *  Sentinel value for fields that are not applicable (timeout, error, no vision).
 */
object NotApplicable

