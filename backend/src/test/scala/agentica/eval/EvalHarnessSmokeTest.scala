package agentica.eval

import agentica.llm.{LLMProvider, LLMResponse, ToolSpec}
import agentica.session.Message
import agentica.settings.AppSettings
import agentica.testutil.{ScriptedLLMProvider, ScriptedResponse}
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

        def streamChatCompletions(messages: List[Message], onToken: String => Unit,
                                  tools: List[ToolSpec] = Nil): LLMResponse =
            LLMResponse(modelName, 0, 0, 0L)

        override def streamResponses(
            input:              List[Message],
            onToken:            String => Unit,
            previousResponseId: Option[String],
            tools:              List[ToolSpec]
        ): LLMResponse = streamChatCompletions(input, onToken, tools)
    }

    /** Builds the scripted LLM responses for the QA phase. */
    private def qaResponses(mdRelPath: String, questions: List[EvalQuestion]): List[ScriptedResponse] =
    {
        questions.flatMap { _ =>
            val args: ujson.Obj = ujson.Obj("path" -> mdRelPath)
            List(
                ScriptedResponse.toolCall("files_read", ujson.write(args)),
                ScriptedResponse("The answer is 42.")
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
                llm          = new ScriptedLLMProvider(qaResponses("smoke.md", questions)),
                vlm          = Some(new StubVisionProvider(markdown)),
                judge        = new ScriptedLLMProvider(
                    questions.map(_ => """{"verdict": "correct", "rationale": "ok"}""")
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
            assert(Files.exists(r.markdownPath), s"expected Markdown inside sweep workspace: ${r.markdownPath}")
            assert(r.markdownPath.getParent.getFileName.toString == r.sessionType,
                s"Markdown parent must be the session-type workspace for ${r.providerLabel}/${r.sessionType}")
            assert(r.answers.length == questions.length,
                s"${r.providerLabel}/${r.sessionType}: expected ${questions.length} answers, got ${r.answers.length}")
            r.answers.foreach { a =>
                assert(a.judgeScore == 1.0,
                    s"${r.providerLabel}/${r.sessionType}: expected score 1.0, got ${a.judgeScore}")
                assert(a.judgeVerdict.contains(JudgeVerdict.Correct),
                    s"${r.providerLabel}/${r.sessionType}: expected verdict correct, got ${a.judgeVerdict}")
                assert(a.status == AnswerStatus.Answered,
                    s"${r.providerLabel}/${r.sessionType}: expected answered, got ${a.status}")
                assert(a.judgeStatus == JudgeStatus.Judged,
                    s"${r.providerLabel}/${r.sessionType}: expected judged, got ${a.judgeStatus}")
                assert(a.toolCounts.getOrElse("files_read", 0) >= 1,
                    s"${r.providerLabel}/${r.sessionType}: expected at least one files_read call")
            }
        }
    }

    /** Provider that blocks until the calling thread is interrupted — simulates a hung endpoint. */
    private class HangingLLMProvider extends LLMProvider
    {
        val modelName: String = "hanging-model"

        def streamChatCompletions(messages: List[Message], onToken: String => Unit,
                                  tools: List[ToolSpec] = Nil): LLMResponse =
        {
            try
            {
                Thread.sleep(3600000L)
            }
            catch
            {
                case _: InterruptedException => Thread.currentThread().interrupt()
            }
            LLMResponse(modelName, 0, 0, 0L)
        }

        override def streamResponses(
            input:              List[Message],
            onToken:            String => Unit,
            previousResponseId: Option[String],
            tools:              List[ToolSpec]
        ): LLMResponse = streamChatCompletions(input, onToken, tools)
    }

    private val smokeSettings = AppSettings(
        apiMode        = agentica.settings.APIMode.ChatCompletions,
        maxIterations  = 5,
        vlmParallelism = 1
    )

    test("Timed-out agent turns are marked timeout and skipped by the judge") {
        val questions = List(EvalQuestion("What is the answer?", "42", "direct"))
        val workDir   = Files.createTempDirectory("eval-harness-timeout-")
        val pdfPath   = createSmokePDF(workDir)

        val judge  = new ScriptedLLMProvider(List("""{"verdict": "correct", "rationale": "ok"}"""))
        val bundle = EvalHarness.EvalProviderBundle(
            label        = "hanging",
            llm          = new HangingLLMProvider(),
            vlm          = Some(new StubVisionProvider("# Smoke\n\nThe answer is 42.\n")),
            judge        = judge,
            sessionTypes = List(EvalSessionType.OneQuestionPerSession)
        )

        val (rootDir, results) = EvalHarness.runEvalBundles(
            pdfPath           = pdfPath,
            questions         = questions,
            bundles           = List(bundle),
            settings          = smokeSettings,
            pdfTimeoutMs      = 30000L,
            questionTimeoutMs = 1000L,
            retryDelayMs      = Some(50L)
        )

        assert(results.length == 1)
        val r = results.head
        assert(r.status == SweepStatus.Ok, s"expected sweep ok, got ${r.status}")
        assert(r.answers.length == 1)
        assert(r.answers.head.status == AnswerStatus.Timeout,
            s"expected timeout, got ${r.answers.head.status}")
        assert(r.answers.head.attempts == 2,
            s"expected 2 attempts (original + retry), got ${r.answers.head.attempts}")
        assert(r.answers.head.judgeStatus == JudgeStatus.Skipped,
            s"expected skipped, got ${r.answers.head.judgeStatus}")
        assert(judge.remainingResponses == 1, "judge should not have been invoked for a timed-out answer")

        // The sweep summary on disk must record the timeout distinctly.
        val summaryPath = rootDir.resolve("smoke").resolve("hanging").resolve("one-question-per-session").resolve("summary.json")
        val summary = ujson.read(Files.readString(summaryPath))
        assert(summary.obj("timeoutCount").num == 1.0, s"expected timeoutCount=1 in $summaryPath")
        assert(summary.obj("judgedCount").num == 0.0, s"expected judgedCount=0 in $summaryPath")
        assert(summary.obj("retryCount").num == 1.0, s"expected retryCount=1 in $summaryPath")
    }

    test("Judge timeouts are reported distinctly from answer failures") {
        val questions = List(EvalQuestion("What is the answer?", "42", "direct"))
        val workDir   = Files.createTempDirectory("eval-harness-jtimeout-")
        val pdfPath   = createSmokePDF(workDir)

        val bundle = EvalHarness.EvalProviderBundle(
            label        = "judge-hangs",
            llm          = new ScriptedLLMProvider(qaResponses("smoke.md", questions)),
            vlm          = Some(new StubVisionProvider("# Smoke\n\nThe answer is 42.\n")),
            judge        = new HangingLLMProvider(),
            sessionTypes = List(EvalSessionType.OneQuestionPerSession)
        )

        val (_, results) = EvalHarness.runEvalBundles(
            pdfPath           = pdfPath,
            questions         = questions,
            bundles           = List(bundle),
            settings          = smokeSettings,
            pdfTimeoutMs      = 30000L,
            questionTimeoutMs = 1000L,
            retryDelayMs      = Some(50L)
        )

        assert(results.length == 1)
        val r = results.head
        assert(r.status == SweepStatus.Ok, s"expected sweep ok, got ${r.status}")
        assert(r.answers.length == 1)
        assert(r.answers.head.status == AnswerStatus.Answered,
            s"expected answered, got ${r.answers.head.status}")
        assert(r.answers.head.judgeStatus == JudgeStatus.JudgeTimeout,
            s"expected judge-timeout, got ${r.answers.head.judgeStatus}")
    }

    /** Judge that throws a transient engine error once, then serves scripted responses. */
    private class ThrowOnceLLMProvider(responses: List[ScriptedResponse]) extends ScriptedLLMProvider(responses)
    {
        private val firstCall = new java.util.concurrent.atomic.AtomicBoolean(true)

        override def streamChatCompletions(messages: List[Message], onToken: String => Unit,
                                           tools: List[ToolSpec] = Nil): LLMResponse =
        {
            if firstCall.getAndSet(false) then
                throw new IllegalStateException(
                    "OpenAI-compatible endpoint returned HTTP 400: peg-native format")
            else super.streamChatCompletions(messages, onToken, tools)
        }
    }

    /** Judge that throws on every call; counts invocations to prove bounded retry. */
    private class AlwaysThrowingLLMProvider extends LLMProvider
    {
        val modelName: String = "always-throwing"
        val calls = new java.util.concurrent.atomic.AtomicInteger(0)

        def streamChatCompletions(messages: List[Message], onToken: String => Unit,
                                  tools: List[ToolSpec] = Nil): LLMResponse =
        {
            calls.incrementAndGet()
            throw new IllegalStateException("simulated judge engine failure")
        }

        override def streamResponses(
            input:              List[Message],
            onToken:            String => Unit,
            previousResponseId: Option[String],
            tools:              List[ToolSpec]
        ): LLMResponse = streamChatCompletions(input, onToken, tools)
    }

    test("Transient judge engine errors are retried once and recover") {
        val questions = List(EvalQuestion("What is the answer?", "42", "direct"))
        val workDir   = Files.createTempDirectory("eval-harness-jretry-")
        val pdfPath   = createSmokePDF(workDir)

        val bundle = EvalHarness.EvalProviderBundle(
            label        = "judge-flaky",
            llm          = new ScriptedLLMProvider(qaResponses("smoke.md", questions)),
            vlm          = Some(new StubVisionProvider("# Smoke\n\nThe answer is 42.\n")),
            judge        = new ThrowOnceLLMProvider(List(ScriptedResponse("""{"verdict": "correct", "rationale": "ok"}"""))),
            sessionTypes = List(EvalSessionType.OneQuestionPerSession)
        )

        val (_, results) = EvalHarness.runEvalBundles(
            pdfPath           = pdfPath,
            questions         = questions,
            bundles           = List(bundle),
            settings          = smokeSettings,
            pdfTimeoutMs      = 30000L,
            questionTimeoutMs = 30000L,
            retryDelayMs      = Some(50L)
        )

        assert(results.length == 1)
        val a = results.head.answers.head
        assert(a.status == AnswerStatus.Answered)
        assert(a.judgeStatus == JudgeStatus.Judged,
            s"expected judged after retry, got ${a.judgeStatus}: ${a.judgeRationale}")
        assert(a.judgeVerdict.contains(JudgeVerdict.Correct))
    }

    test("Persistent judge errors are marked judge-error after bounded attempts") {
        val questions = List(EvalQuestion("What is the answer?", "42", "direct"))
        val workDir   = Files.createTempDirectory("eval-harness-jfail-")
        val pdfPath   = createSmokePDF(workDir)
        val judge     = new AlwaysThrowingLLMProvider()

        val bundle = EvalHarness.EvalProviderBundle(
            label        = "judge-down",
            llm          = new ScriptedLLMProvider(qaResponses("smoke.md", questions)),
            vlm          = Some(new StubVisionProvider("# Smoke\n\nThe answer is 42.\n")),
            judge        = judge,
            sessionTypes = List(EvalSessionType.OneQuestionPerSession)
        )

        val (_, results) = EvalHarness.runEvalBundles(
            pdfPath           = pdfPath,
            questions         = questions,
            bundles           = List(bundle),
            settings          = smokeSettings,
            pdfTimeoutMs      = 30000L,
            questionTimeoutMs = 30000L,
            retryDelayMs      = Some(50L)
        )

        assert(results.length == 1)
        val a = results.head.answers.head
        assert(a.status == AnswerStatus.Answered)
        assert(a.judgeStatus == JudgeStatus.JudgeError,
            s"expected judge-error after retries exhausted, got ${a.judgeStatus}")
        assert(judge.calls.get() == 2,
            s"expected exactly 2 judge attempts, got ${judge.calls.get()}")
    }

    test("Empty post-tool response is an answer error and is not judged") {
        val questions = List(EvalQuestion("What is the answer?", "42", "direct"))
        val workDir   = Files.createTempDirectory("eval-harness-empty-final-")
        val pdfPath   = createSmokePDF(workDir)
        val judge = new ScriptedLLMProvider(List("""{"verdict": "correct", "rationale": "unused"}"""))
        val llm = new ScriptedLLMProvider(List(
            ScriptedResponse.toolCall("files_read", "{\"path\":\"smoke.md\"}"),
            ScriptedResponse("")
        ))
        val bundle = EvalHarness.EvalProviderBundle(
            label = "empty-final",
            llm = llm,
            vlm = Some(new StubVisionProvider("# Smoke\n\nThe answer is 42.\n")),
            judge = judge,
            sessionTypes = List(EvalSessionType.OneQuestionPerSession)
        )

        val (_, results) = EvalHarness.runEvalBundles(
            pdfPath = pdfPath,
            questions = questions,
            bundles = List(bundle),
            settings = smokeSettings,
            pdfTimeoutMs = 30000L,
            questionTimeoutMs = 30000L,
            retryDelayMs = Some(50L)
        )

        assert(results.head.answers.head.status == AnswerStatus.Error)
        assert(results.head.answers.head.judgeStatus == JudgeStatus.Skipped)
        assert(results.head.answers.head.actualAnswer.contains("empty_llm_response"))
        assert(judge.remainingResponses == 1)
    }

    /** First call blocks until interrupted (times out); subsequent calls serve scripted responses. */
    private class FailOnceLLMProvider(responses: List[ScriptedResponse]) extends ScriptedLLMProvider(responses)
    {
        private val firstCall = new java.util.concurrent.atomic.AtomicBoolean(true)

        override def streamChatCompletions(messages: List[Message], onToken: String => Unit,
                                           tools: List[ToolSpec] = Nil): LLMResponse =
        {
            if firstCall.getAndSet(false) then
            {
                try
                {
                    Thread.sleep(3600000L)
                }
                catch
                {
                    case _: InterruptedException => Thread.currentThread().interrupt()
                }
                LLMResponse(modelName, 0, 0, 0L)
            }
            else super.streamChatCompletions(messages, onToken, tools)
        }
    }

    test("Timed-out questions are retried once and recover in place") {
        val questions = List(
            EvalQuestion("What is the answer?", "42", "direct"),
            EvalQuestion("What number does the document mention?", "42", "rephrase")
        )
        val workDir = Files.createTempDirectory("eval-harness-retry-")
        val pdfPath = createSmokePDF(workDir)

        val bundle = EvalHarness.EvalProviderBundle(
            label        = "fail-once",
            llm          = new FailOnceLLMProvider(qaResponses("smoke.md", questions)),
            vlm          = Some(new StubVisionProvider("# Smoke\n\nThe answer is 42.\n")),
            judge        = new ScriptedLLMProvider(questions.map(_ => """{"verdict": "correct", "rationale": "ok"}""")),
            sessionTypes = List(EvalSessionType.AllQuestionsPerSession)
        )

        val (_, results) = EvalHarness.runEvalBundles(
            pdfPath           = pdfPath,
            questions         = questions,
            bundles           = List(bundle),
            settings          = smokeSettings,
            pdfTimeoutMs      = 30000L,
            questionTimeoutMs = 1000L,
            retryDelayMs      = Some(50L)
        )

        assert(results.length == 1)
        val r = results.head
        assert(r.status == SweepStatus.Ok, s"expected sweep ok, got ${r.status}")
        assert(r.answers.length == questions.length)

        // Q1: first attempt timed out, retry recovered — shared-session order/context preserved.
        assert(r.answers(0).attempts == 2, s"expected 2 attempts for retried question, got ${r.answers(0).attempts}")
        assert(r.answers(0).status == AnswerStatus.Answered)
        assert(r.answers(0).judgeStatus == JudgeStatus.Judged)
        assert(r.answers(0).judgeVerdict.contains(JudgeVerdict.Correct))

        // Q2: single attempt, unaffected by the earlier retry.
        assert(r.answers(1).attempts == 1, s"expected 1 attempt for normal question, got ${r.answers(1).attempts}")
        assert(r.answers(1).status == AnswerStatus.Answered)
        assert(r.answers(1).judgeStatus == JudgeStatus.Judged)
    }
}
