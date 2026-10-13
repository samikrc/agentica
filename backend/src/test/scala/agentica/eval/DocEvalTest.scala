package agentica.eval

import agentica.settings.AppSettings
import org.scalatest.DoNotDiscover
import java.nio.file.{Path, Paths}

/**
 *  End-to-end document question-answering evaluation.
 *
 *  Converts each test document to Markdown using the appropriate vision-first
 *  ingestion tool, scores the Markdown, asks grounded questions (direct + rephrase),
 *  and judges the answers with an LLM-as-judge.
 *
 *  This suite extends [[EvalSuite]] and is annotated `@DoNotDiscover`, so it
 *  will not run automatically with `mvn test`.
 *
 *  Run it explicitly via the scalatest-maven-plugin suite selector:
 *    mvn test -pl backend -Dsuites=agentica.eval.DocEvalTest -DDOC_EVAL_CONFIG=/path/to/eval-config.json
 *
 *  The provider sweep is read from a JSON config file:
 *    - `DOC_EVAL_CONFIG` system property / env var, or
 *    - `/eval-config.json` on the test classpath.
 */
@DoNotDiscover
class DocEvalTest extends EvalSuite
{
    /** Base path for evaluation question sets under test resources. */
    private val evalResourceDir: Path =
        Paths.get(getClass.getResource("/files").toURI)

    /**
     *  Documents to evaluate, resolved from test resources.
     *  The matching question file is expected at `<stem>.eval.json` where `<stem>`
     *  is the document filename without the extension.
     */
    private val evalDocs: List[String] = List(
        //"IT Support Analyst - India.pdf",
        "Even OPD Policy Document.pdf"
    )

    /**
     *  Runs the full document-to-Markdown + QA evaluation for every document in [[evalDocs]].
     *
     *  If a document or its corresponding `.eval.json` question file is missing, that
     *  document is reported and skipped.
     */
    test("Document-to-Markdown + QA evaluation for all configured documents") {
        val configs  = loadProviderConfigs()
        val settings = AppSettings()

        println(s"Providers: ${configs.map(c => s"${c.label} [${c.sessionTypes.map(_.label).mkString(",")}]").mkString(", ")}")

        evalDocs.foreach { docName =>
            val docPath      = evalResourceDir.resolve(docName)
            val stem         = docName.lastIndexOf('.') match { case -1 => docName; case i => docName.substring(0, i) }
            val evalJsonName = s"$stem.eval.json"

            if !docPath.toFile.exists() then
            {
                println(s"\n[SKIP] Missing document: $docPath")
            }
            else if !evalResourceDir.resolve(evalJsonName).toFile.exists() then
            {
                println(s"\n[SKIP] Missing question file: $evalJsonName for: $docName")
            }
            else
            {
                val questions = EvalHarness.loadQuestions(evalResourceDir.resolve(evalJsonName))

                println(s"\nRunning evaluation on: $docPath")

                val (workDir, results) = EvalHarness.runEval(
                    docPath             = docPath,
                    questions           = questions,
                    providerConfigs     = configs,
                    settings            = settings,
                    conversionTimeoutMs = 600000L,
                    questionTimeoutMs   = 150000L
                )

                // Soft sanity checks: every successful sweep must produce answers for all questions.
                results.filter(_.status == SweepStatus.Ok).foreach { r =>
                    assert(r.answers.length == questions.length,
                        s"${r.providerLabel}/${r.sessionType}: expected ${questions.length} answers, got ${r.answers.length}")
                }

                if results.nonEmpty then
                {
                    EvalHarness.writeResultSummary(docName, results, workDir)
                }
            }
        }
    }
}
