package agentica.eval

import agentica.misctests.MarkdownScorer
import agentica.testutil.{EvalHarness, EvalProviderConfig, EvalQuestion, EvalResult, EvalSuite}
import agentica.llm.LLMProvider
import agentica.settings.AppSettings
import org.scalatest.DoNotDiscover
import java.nio.file.{Files, Path, Paths}

/**
 *  End-to-end PDF question-answering evaluation.
 *
 *  Converts a test PDF to Markdown using the real `files.read_pdf_to_markdown`
 *  tool path, scores the Markdown with [[MarkdownScorer]], asks 10 grounded
 *  questions (5 direct + 5 rephrase), and judges the answers with an LLM-as-judge.
 *
 *  This suite extends [[EvalSuite]] and is annotated `@DoNotDiscover`, so it
 *  will not run automatically with `mvn test`. It will not hang on unavailable
 *  servers unless explicitly invoked.
 *
 *  Run it explicitly via the scalatest-maven-plugin suite selector:
 *    mvn test -pl backend -Dsuites=agentica.eval.PDFEvalTest -DPDF_EVAL_CONFIG=/path/to/eval-config.json
 *
 *  The provider sweep is read from a JSON config file:
 *    - `PDF_EVAL_CONFIG` system property / env var, or
 *    - `/eval-config.json` on the test classpath.
 */
@DoNotDiscover
class PDFEvalTest extends EvalSuite
{

    /**
     *  Base path for evaluation question sets under test resources.
     */
    private val evalResourceDir: Path =
        Paths.get(getClass.getResource("/files").toURI)

    /**
     *  PDF documents to evaluate, resolved from test resources.
     *  The matching question file is expected at `<stem>.eval.json` where `<stem>`
     *  is the PDF filename without the `.pdf` extension.
     */
    private val pdfDocs: List[String] = List(
        "IT Support Analyst - India.pdf",
        //"Even OPD Policy Document.pdf"
    )

    /**
     *  Loads a JSON question set from the classpath resources.
     *
     *  @param resourceName  Name of the `.eval.json` file under `/files`.
     *  @return                List of EvalQuestion entries.
     */
    private def loadQuestions(resourceName: String): List[EvalQuestion] =
    {
        val path = evalResourceDir.resolve(resourceName)
        val json = ujson.read(Files.readString(path))
        json.obj("qset").arr.toList.map { q =>
            EvalQuestion(
                question        = q.obj("question").str,
                referenceAnswer = q.obj("answer").str,
                category        = q.obj.get("category").map(_.str).getOrElse("direct")
            )
        }
    }

    /**
     *  Builds an aggregated summary per file across all providers and session types,
     *  showing average scores and score distributions by category (direct/derived),
     *  plus zero-score question details.  Then calls the judge LLM to generate an
     *  analysis and writes everything to `result_summary.txt` in the work directory.
     *
     *  @param pdfName        PDF filename.
     *  @param results        All EvalResults for this file.
     *  @param judgeProvider  LLM provider to use for commentary generation.
     *  @param workDir        Root work directory for this eval run.
     */
    private def writeFileSummary(
        pdfName:       String,
        results:       List[EvalResult],
        judgeProvider: LLMProvider,
        workDir:       Path
    ): Unit =
    {
        println(s"\n${"-" * 72}")
        println(s"SUMMARY: $pdfName")
        println("-" * 72)

        val summaryBuf = new StringBuilder()
        summaryBuf.append(s"File: $pdfName\n")
        summaryBuf.append(s"Work directory: $workDir\n\n")

        // Group by (provider, sessionType) — one group per EvalResult.
        results.foreach { r =>
            val answers   = r.answers
            val avgScore  = if (answers.isEmpty) 0.0 else answers.map(_.judgeScore).sum / answers.length

            // Score distribution by category.
            val byCategory = answers.groupBy(_.category)
            val categoryLines = byCategory.toList.sortBy(_._1).map { case (cat, as) =>
                val count0   = as.count(_.judgeScore == 0.0)
                val count05  = as.count(_.judgeScore == 0.5)
                val count1   = as.count(_.judgeScore == 1.0)
                val catAvg   = if (as.isEmpty) 0.0 else as.map(_.judgeScore).sum / as.length
                f"    $cat%-12s  avg=$catAvg%.2f  (0.0:$count0%d  0.5:$count05%d  1.0:$count1%d  n=${as.length}%d)"
            }

            val header = f"  ${r.providerLabel} / ${r.sessionType}  —  avg=${avgScore}%.2f  (${answers.length} questions)"
            println(header)
            categoryLines.foreach(println)

            summaryBuf.append(s"$header\n")
            categoryLines.foreach(l => summaryBuf.append(s"$l\n"))

            // Include per-question details for zero-score answers so the
            // commentary LLM can explain why they failed.
            val zeros = answers.filter(_.judgeScore == 0.0)
            if zeros.nonEmpty then
            {
                summaryBuf.append("    Questions scoring 0.0:\n")
                zeros.foreach { a =>
                    summaryBuf.append(f"      [${a.judgeScore}%.1f] (${a.category}) ${a.question}\n")
                    summaryBuf.append(s"        Rationale: ${a.judgeRationale}\n")
                }
            }
            summaryBuf.append("\n")
        }

        // Call judge LLM for analysis.
        println(s"\n  Generating commentary via judge LLM...")
        val commentary = EvalHarness.generateCommentary(summaryBuf.toString(), judgeProvider)
        println(s"\n  === Analysis & Failure Summary ===")
        println(commentary)
        println("-" * 72)

        // Write the full summary + commentary to result_summary.txt.
        val output = new StringBuilder()
        output.append(s"${"-" * 72}\n")
        output.append(s"SUMMARY: $pdfName\n")
        output.append(s"${"-" * 72}\n\n")
        output.append(summaryBuf.toString())
        output.append(s"${"-" * 72}\n")
        output.append("Analysis & Failure Summary\n")
        output.append(s"${"-" * 72}\n")
        output.append(commentary)
        output.append(s"\n${"-" * 72}\n")

        val summaryPath = workDir.resolve("result_summary.txt")
        Files.writeString(summaryPath, output.toString())
        println(s"  Summary written to: $summaryPath")
    }

    /**
     *  Runs the full PDF-to-Markdown + QA evaluation for every document in [[pdfDocs]].
     *
     *  If a PDF or its corresponding `.eval.json` question file is missing, that
     *  document is reported and skipped.
     */
    test("PDF-to-Markdown + QA evaluation for all configured documents") {
        val configs  = loadProviderConfigs()
        val settings = AppSettings()

        println(s"Providers: ${configs.map(c => s"${c.label} [${c.sessionTypes.map(_.label).mkString(",")}]").mkString(", ")}")

        // Use the first provider's judge for commentary generation.
        val judgeProvider =
        {
            import EvalHarness.EvalProviderConfigOps
            configs.head.judgeProvider
        }

        pdfDocs.foreach { pdfName =>
            val pdfPath      = evalResourceDir.resolve(pdfName)
            val evalJsonName = pdfName.stripSuffix(".pdf") + ".eval.json"
            if !pdfPath.toFile.exists() then
            {
                println(s"\n[SKIP] Missing PDF: $pdfPath")
            }
            else if !evalResourceDir.resolve(evalJsonName).toFile.exists() then
            {
                println(s"\n[SKIP] Missing question file: $evalJsonName for PDF: $pdfName")
            }
            else
            {
                val questions = loadQuestions(evalJsonName)

                println(s"\nRunning PDFEvalTest on: $pdfPath")

                val (workDir, results) = EvalHarness.runEval(
                    pdfPath           = pdfPath,
                    questions         = questions,
                    providerConfigs   = configs,
                    settings          = settings,
                    pdfTimeoutMs      = 600000L,
                    questionTimeoutMs = 120000L
                )

                // Soft sanity checks: every provider must produce answers for all questions.
                results.foreach { r =>
                    assert(r.answers.length == questions.length,
                        s"${r.providerLabel}/${r.sessionType}: expected ${questions.length} answers, got ${r.answers.length}")
                }

                // Per-file summary with score distributions and LLM commentary.
                if results.nonEmpty then
                {
                    writeFileSummary(pdfName, results, judgeProvider, workDir)
                }
            }
        }
    }
}
