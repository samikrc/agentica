package agentica.eval

import agentica.misctests.MarkdownScorer
import agentica.llm.LLMProvider
import agentica.settings.AppSettings
import org.scalatest.DoNotDiscover
import java.nio.file.{Files, Path, Paths}

/**
 *  End-to-end PDF question-answering evaluation.
 *
 *  Converts a test PDF to Markdown using the real `files_read_pdf_to_markdown`
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
        summaryBuf.append("Verdicts are mapped to numeric scores for the average: correct=1.0, partial=0.5, wrong=0.0\n\n")

        // Group by (provider, sessionType) — one group per EvalResult.
        results.foreach { r =>
            if r.status != SweepStatus.Ok then
            {
                val header = s"  ${r.providerLabel} / ${r.sessionType}  —  FAILED (${r.status.label}): ${r.statusMessage}"
                println(header)
                summaryBuf.append(s"$header\n\n")
            }
            else
            {
                val answers   = r.answers
                val judged    = answers.filter(_.judgeStatus == JudgeStatus.Judged)
                val avgScore  = if (judged.isEmpty) 0.0 else judged.map(_.judgeScore).sum / judged.length

                val timeouts   = answers.count(_.status == AnswerStatus.Timeout)
                val errors     = answers.count(_.status == AnswerStatus.Error)
                val retried    = answers.count(_.attempts > 1)
                val jTimeouts  = answers.count(_.judgeStatus == JudgeStatus.JudgeTimeout)
                val jErrors    = answers.count(_.judgeStatus == JudgeStatus.JudgeError)
                val flags = List(
                    Option.when(retried > 0)(s"$retried retried"),
                    Option.when(timeouts > 0)(s"$timeouts timeout"),
                    Option.when(errors > 0)(s"$errors error"),
                    Option.when(jTimeouts > 0)(s"$jTimeouts judge-timeout"),
                    Option.when(jErrors > 0)(s"$jErrors judge-error")
                ).flatten
                val annot = if flags.isEmpty then "" else flags.mkString("; ", "; ", "")

                // Verdict distribution by category, over judged answers only.
                val byCategory = judged.groupBy(_.category)
                val categoryLines = byCategory.toList.sortBy(_._1).map { case (cat, as) =>
                    val correct  = as.count(_.judgeVerdict.contains(JudgeVerdict.Correct))
                    val partial  = as.count(_.judgeVerdict.contains(JudgeVerdict.PartiallyCorrect))
                    val wrong    = as.count(_.judgeVerdict.contains(JudgeVerdict.Wrong))
                    val catAvg   = if (as.isEmpty) 0.0 else as.map(_.judgeScore).sum / as.length
                    f"    $cat%-12s  avg=$catAvg%.2f  (correct:$correct%d  partial:$partial%d  wrong:$wrong%d  n=${as.length}%d)"
                }

                val header = f"  ${r.providerLabel} / ${r.sessionType}  —  avg=${avgScore}%.2f  (${answers.length} questions$annot)"
                println(header)
                categoryLines.foreach(println)

                summaryBuf.append(s"$header\n")
                categoryLines.foreach(l => summaryBuf.append(s"$l\n"))

                // Include per-question details for wrong answers so the
                // commentary LLM can explain why they failed.
                val wrong = judged.filter(_.judgeVerdict.contains(JudgeVerdict.Wrong))
                if wrong.nonEmpty then
                {
                    summaryBuf.append("    Questions judged wrong:\n")
                    wrong.foreach { a =>
                        summaryBuf.append(f"      [${a.judgeVerdict.map(_.label).getOrElse("?")}] (${a.category}) ${a.question}\n")
                        summaryBuf.append(s"        Rationale: ${a.judgeRationale}\n")
                    }
                }

                // List questions that did not complete normally so the commentary
                // can separate infrastructure timeouts from genuine failures.
                val notAnswered = answers.filter(_.status != AnswerStatus.Answered)
                if notAnswered.nonEmpty then
                {
                    summaryBuf.append("    Questions not answered:\n")
                    notAnswered.foreach { a =>
                        val tries = if a.attempts > 1 then s" after ${a.attempts} attempts" else ""
                        summaryBuf.append(s"      [${a.status.label}$tries] (${a.category}) ${a.question}\n")
                    }
                }
                summaryBuf.append("\n")
            }
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

                // Soft sanity checks: every successful sweep must produce answers for all questions.
                // Failed sweeps (timeout/error) are reported via EvalResult.status, not answers.
                results.filter(_.status == SweepStatus.Ok).foreach { r =>
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
