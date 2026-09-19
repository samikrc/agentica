package agentica.eval

import agentica.llm.{LLMProvider, LLMResponse}
import agentica.session.Message
import agentica.settings.AppSettings
import agentica.testutil.{EvalHarness, EvalQuestion, EvalSessionType}
import org.apache.pdfbox.pdmodel.{PDDocument, PDPage, PDPageContentStream}
import org.apache.pdfbox.pdmodel.font.{PDType1Font, Standard14Fonts}
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path}

/**
 *  Fast, network-free smoke test for the evaluation harness.
 *
 *  Generates a one-page PDF, stubs out the LLM/VLM/judge with scripted responses,
 *  and runs all three session types with very short timeouts.  This catches
 *  regressions in the harness itself (session logic, tool counting, scoring,
 *  file output) without requiring a live model server.
 */
class EvalHarnessSmokeTest extends AnyFunSuite
{
    /** Stubs the vision call used during PDF-to-Markdown conversion. */
    private class StubVisionProvider(markdown: String) extends LLMProvider
    {
        val modelName: String = "stub-vision"
        override def supportsVision: Boolean = true
        override def completeVision(base64Image: String, prompt: String): String = markdown

        def streamChatCompletions(messages: List[Message], onToken: String => Unit): LLMResponse =
            LLMResponse(modelName, 0, 0, 0L)

        override def streamResponses(
            input:              List[Message],
            onToken:            String => Unit,
            previousResponseId: Option[String] = None
        ): LLMResponse = streamChatCompletions(input, onToken)
    }

    /** Builds the scripted LLM responses for the QA phase. */
    private def qaResponses(mdRelPath: String, questions: List[EvalQuestion]): List[String] =
    {
        val readCall = s"""run(command="files.read path=\\"$mdRelPath\\"")"""
        questions.flatMap { _ =>
            List(
                readCall,
                "The answer is 42. <done>"
            )
        }
    }

    /** Creates a one-page PDF with the answer text baked in. */
    private def createSmokePDF(dir: Path): Path =
    {
        val path = dir.resolve("smoke.pdf")
        val doc  = new PDDocument()
        val page = new PDPage()
        doc.addPage(page)
        val stream = new PDPageContentStream(doc, page)
        stream.beginText()
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12)
        stream.newLineAtOffset(100, 700)
        stream.showText("The answer is 42.")
        stream.endText()
        stream.close()
        doc.save(path.toFile)
        doc.close()
        path
    }

    test("EvalHarness runs all session types end-to-end with stub providers") {
        val questions = List(
            EvalQuestion("What is the answer?", "42", "direct"),
            EvalQuestion("What number does the document mention?", "42", "rephrase")
        )

        val markdown =
            """# Smoke Test Document
              |
              |The answer is 42.
              |""".stripMargin

        val workDir = Files.createTempDirectory("eval-harness-smoke-")
        val pdfPath = createSmokePDF(workDir)

        def makeBundle(sessionTypes: List[EvalSessionType], label: String) =
            EvalHarness.EvalProviderBundle(
                label        = label,
                llm          = new agentica.testutil.ScriptedLLMProvider(qaResponses("smoke.md", questions)),
                vlm          = Some(new StubVisionProvider(markdown)),
                judge        = new agentica.testutil.ScriptedLLMProvider(
                    questions.map(_ => """{"score": 1.0, "rationale": "ok"}""")
                ),
                sessionTypes = sessionTypes
            )

        val bundles = List(
            makeBundle(List(EvalSessionType.AllQuestionsPerSession),         "all-questions"),
            makeBundle(List(EvalSessionType.AllQuestionsPerSessionShuffled), "all-questions-shuffled"),
            makeBundle(List(EvalSessionType.OneQuestionPerSession),           "one-question")
        )

        val settings = AppSettings(
            apiMode        = agentica.settings.APIMode.ChatCompletions,
            maxIterations  = 5,
            vlmParallelism = 1
        )

        val (rootDir, results) = EvalHarness.runEvalBundles(
            pdfPath           = pdfPath,
            questions         = questions,
            bundles           = bundles,
            settings          = settings,
            pdfTimeoutMs      = 30000L,
            questionTimeoutMs = 30000L
        )

        assert(results.length == 3, s"expected 3 session-type results, got ${results.length}")

        results.foreach { r =>
            assert(r.answers.length == questions.length,
                s"${r.providerLabel}/${r.sessionType}: expected ${questions.length} answers, got ${r.answers.length}")
            r.answers.foreach { a =>
                assert(a.judgeScore == 1.0,
                    s"${r.providerLabel}/${r.sessionType}: expected score 1.0, got ${a.judgeScore}")
                assert(a.toolCounts.getOrElse("files.read", 0) >= 1,
                    s"${r.providerLabel}/${r.sessionType}: expected at least one files.read call")
            }
        }
    }
}
