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
     *  Loads explicit document selection and providers from the active run configuration.
     *
     *  @return Configuration shared by all document sweeps.
     *  @throws IllegalArgumentException if no evaluation configuration exists.
     */
    private def loadRunConfig(): EvalRunConfig =
        EvalConfig.resolveRun().getOrElse(throw new IllegalArgumentException(
            "No eval config found. Set DOC_EVAL_CONFIG or create /eval-config.json on the test classpath."))

    /**
     *  Runs the full document-to-Markdown + QA evaluation for every configured document.
     *
     *  All document and matching `.eval.json` inputs are validated before model calls.
     */
    test("Document-to-Markdown + QA evaluation for all configured documents") {
        val runConfig = loadRunConfig()
        val inputs = runConfig.documentInputs(evalResourceDir)
        val configs = runConfig.providers
        val settings = AppSettings()

        println(s"Providers: ${configs.map(c => s"${c.label} [${c.sessionTypes.map(_.label).mkString(",")}]").mkString(", ")}")

        inputs.foreach { case (docPath, questions) =>
            val docName = docPath.getFileName.toString
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
