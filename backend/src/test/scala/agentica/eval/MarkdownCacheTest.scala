package agentica.eval

import agentica.llm.{LLMProvider, LLMResponse, ToolSpec}
import agentica.session.Message
import agentica.settings.AppSettings
import agentica.testutil.{ScriptedLLMProvider, ScriptedResponse}
import org.apache.pdfbox.pdmodel.{PDDocument, PDPage, PDPageContentStream}
import org.apache.pdfbox.pdmodel.font.{PDType1Font, Standard14Fonts}
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicInteger

/**
 *  Regression tests for the content-addressed Markdown conversion cache.
 *
 *  Verifies that `reuseMarkdownCache` controls whether a previously converted
 *  PDF skips VLM transcription, that the cache key is invalidated by PDF
 *  content and converter identity, and that reused Markdown is still copied
 *  into every session-type workspace.
 */
class MarkdownCacheTest extends AnyFunSuite
{
    /** Counts vision calls so tests can prove conversion was skipped or ran. */
    private class CountingVisionProvider(markdown: String) extends LLMProvider
    {
        val modelName: String = "counting-vision"
        val calls = new AtomicInteger(0)

        override def supportsVision: Boolean = true
        override def completeVision(base64Image: String, prompt: String): String =
        {
            calls.incrementAndGet()
            markdown
        }

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

    private val settings = AppSettings(
        apiMode        = agentica.settings.APIMode.ChatCompletions,
        maxIterations  = 5,
        vlmParallelism = 1
    )

    private val questions = List(EvalQuestion("What is the answer?", "42", "direct"))

    /** Scripted QA phase: read the generated Markdown then answer. */
    private def qaLLM: ScriptedLLMProvider = new ScriptedLLMProvider(List(
        ScriptedResponse.toolCall("files_read", """{"path":"smoke.md"}"""),
        ScriptedResponse("The answer is 42.")
    ))

    /** Redirects the Markdown cache into a test directory for the duration of `body`. */
    private def withCacheDir[T](dir: Path)(body: => T): T =
    {
        sys.props("AGENTICA_MARKDOWN_CACHE_DIR") = dir.toString
        try body
        finally sys.props.remove("AGENTICA_MARKDOWN_CACHE_DIR")
    }

    /** Creates a one-page PDF whose text makes its bytes (and therefore cache key) unique. */
    private def createPDF(dir: Path, text: String): Path =
    {
        val path = dir.resolve("smoke.pdf")
        val doc  = new PDDocument()
        val page = new PDPage()
        doc.addPage(page)
        val stream = new PDPageContentStream(doc, page)
        stream.beginText()
        stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12)
        stream.newLineAtOffset(100, 700)
        stream.showText(text)
        stream.endText()
        stream.close()
        doc.save(path.toFile)
        doc.close()
        path
    }

    /** Runs one single-session sweep and returns (rootDir, results). */
    private def runSweep(
        pdfPath:  Path,
        vlm:      CountingVisionProvider,
        reuse:    Boolean,
        identity: String
    ): (Path, List[EvalResult]) =
        EvalHarness.runEvalBundles(
            pdfPath   = pdfPath,
            questions = questions,
            bundles   = List(EvalHarness.EvalProviderBundle(
                label                 = "cache-provider",
                llm                   = qaLLM,
                vlm                   = Some(vlm),
                judge                 = new ScriptedLLMProvider(List("""{"verdict": "correct", "rationale": "ok"}""")),
                sessionTypes          = List(EvalSessionType.OneQuestionPerSession),
                reuseMarkdownCache    = reuse,
                markdownCacheIdentity = identity
            )),
            settings          = settings,
            pdfTimeoutMs      = 30000L,
            questionTimeoutMs = 30000L
        )

    test("Cache miss converts the PDF and writes a cache entry") {
        val workDir  = Files.createTempDirectory("md-cache-miss-")
        val cacheDir = Files.createTempDirectory("md-cache-store-")
        val pdfPath  = createPDF(workDir, "cache miss document")

        withCacheDir(cacheDir) {
            val vlm = new CountingVisionProvider("# Cached\n\nThe answer is 42.\n")
            val (rootDir, results) = runSweep(pdfPath, vlm, reuse = true, identity = "vlm-a")

            assert(vlm.calls.get() > 0, "expected VLM conversion to run on a cache miss")
            assert(results.head.status == SweepStatus.Ok)

            val (cachePath, _, _) = EvalHarness.markdownCacheEntry(pdfPath, "vlm-a")
            assert(Files.exists(cachePath), s"expected cache entry at $cachePath")

            val sweepMd = results.head.markdownPath
            assert(sweepMd.getParent.getFileName.toString == "one-question-per-session")
            assert(Files.readString(sweepMd) == Files.readString(cachePath),
                "sweep Markdown must match what was written to the cache")
        }
    }

    test("Cache hit reuses stored Markdown and skips the VLM") {
        val workDir  = Files.createTempDirectory("md-cache-hit-")
        val cacheDir = Files.createTempDirectory("md-cache-store-")
        val pdfPath  = createPDF(workDir, "cache hit document")

        withCacheDir(cacheDir) {
            val sentinel = ("# Cached sentinel\n\n" + "The answer is 42.\n") * 10
            val (cachePath, _, _) = EvalHarness.markdownCacheEntry(pdfPath, "vlm-a")
            Files.writeString(cachePath, sentinel)

            val vlm = new CountingVisionProvider("# Fresh conversion\n\nshould not run\n")
            val (_, results) = runSweep(pdfPath, vlm, reuse = true, identity = "vlm-a")

            assert(vlm.calls.get() == 0, s"expected no VLM calls on a cache hit, got ${vlm.calls.get()}")
            assert(results.head.status == SweepStatus.Ok)
            assert(Files.readString(results.head.markdownPath) == sentinel,
                "cached Markdown must be copied into the session workspace")
        }
    }

    test("reuseMarkdownCache = false forces conversion and refreshes the entry") {
        val workDir  = Files.createTempDirectory("md-cache-off-")
        val cacheDir = Files.createTempDirectory("md-cache-store-")
        val pdfPath  = createPDF(workDir, "cache disabled document")

        withCacheDir(cacheDir) {
            val sentinel = ("# Stale sentinel\n\n" + "should be replaced.\n") * 10
            val (cachePath, _, _) = EvalHarness.markdownCacheEntry(pdfPath, "vlm-a")
            Files.writeString(cachePath, sentinel)

            val freshMarkdown = "# Fresh conversion\n\nThe answer is 42.\n"
            val vlm = new CountingVisionProvider(freshMarkdown)
            val (_, results) = runSweep(pdfPath, vlm, reuse = false, identity = "vlm-a")

            assert(vlm.calls.get() > 0, "expected VLM conversion despite existing cache entry")
            assert(results.head.status == SweepStatus.Ok)
            assert(Files.readString(cachePath) != sentinel,
                "disabled run must refresh the cache entry with fresh Markdown")
        }
    }

    test("Different converter identities produce different cache keys") {
        val workDir  = Files.createTempDirectory("md-cache-id-")
        val cacheDir = Files.createTempDirectory("md-cache-store-")
        val pdfPath  = createPDF(workDir, "identity document")

        withCacheDir(cacheDir) {
            val (pathA, keyA, hashA) = EvalHarness.markdownCacheEntry(pdfPath, "vlm-a")
            val (pathB, keyB, hashB) = EvalHarness.markdownCacheEntry(pdfPath, "vlm-b")

            assert(hashA == hashB, "PDF hash must be identical for the same document")
            assert(keyA != keyB, "different VLM identities must produce different cache keys")
            assert(pathA != pathB)

            // Seed only identity A; running as identity B must still convert.
            val sentinel = ("# Identity A cache\n\n" + "The answer is 42.\n") * 10
            Files.writeString(pathA, sentinel)

            val vlm = new CountingVisionProvider("# Fresh\n\nThe answer is 42.\n")
            val (_, results) = runSweep(pdfPath, vlm, reuse = true, identity = "vlm-b")

            assert(vlm.calls.get() > 0, "identity B must not reuse identity A's cache entry")
            assert(results.head.status == SweepStatus.Ok)
            assert(Files.exists(pathB), "identity B conversion must write its own cache entry")
        }
    }

    test("Different PDF contents produce different cache keys") {
        val workDir  = Files.createTempDirectory("md-cache-pdf-")
        val cacheDir = Files.createTempDirectory("md-cache-store-")
        val pdfA     = createPDF(workDir, "document alpha")
        // Same filename in a different directory, different contents.
        val otherDir = Files.createDirectory(workDir.resolve("other"))
        val pdfB     = createPDF(otherDir, "document beta with different bytes")

        withCacheDir(cacheDir) {
            val (_, keyA, hashA) = EvalHarness.markdownCacheEntry(pdfA, "vlm-a")
            val (_, keyB, hashB) = EvalHarness.markdownCacheEntry(pdfB, "vlm-a")

            assert(hashA != hashB, "different PDF bytes must produce different content hashes")
            assert(keyA != keyB, "different PDF contents must not share a cache entry")
        }
    }
}
