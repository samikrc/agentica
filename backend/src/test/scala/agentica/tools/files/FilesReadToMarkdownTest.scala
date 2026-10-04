package agentica.tools.files

import agentica.agent.AgentEvent
import agentica.doc.{DocToolDetector, PDFPageRenderer}
import agentica.llm.{LLMProvider, LLMResponse, ToolSpec}
import agentica.permissions.{GrantDecision, GrantTTL, PermissionCoordinator, ScopeStore}
import agentica.session.{MemoryStore, Session}
import agentica.shell.SessionScratchpad
import agentica.tools.{ErrorCode, ExecutionContext, FilesError, ToolStatus}
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.{Files, Path, Paths}
import java.nio.file.attribute.FileTime
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ListBuffer

/**
 *  Unit tests for the `files_read_*_to_markdown` tools (Stage B).
 *
 *  Covers `.md` generation, cache hit, staleness re-generation, small-cache
 *  rejection, `enrich_images=false`, permission denied/granted, VLM-over-LLM
 *  preference, vision-unsupported error, and `render` — using the PDF/DOCX/PPTX
 *  fixtures in `src/test/resources/files/`.
 */
class FilesReadToMarkdownTest extends AnyFunSuite
{

    /** Stub LLM that returns a fixed >150-char Markdown string per vision call and counts calls. */
    private class StubVisionProvider(override val supportsVision: Boolean = true) extends LLMProvider
    {
        val modelName: String = "stub-vision"
        val callCount         = AtomicInteger(0)

        // Must exceed 100 bytes so the written .md passes the cache-freshness size check.
        override def completeVision(base64Image: String, prompt: String): String =
        {
            callCount.incrementAndGet()
            "## Stub page\n\nThis is stub Markdown returned by the test double for a rendered document " +
                "page. It is deliberately padded well beyond one hundred bytes so that the assembled " +
                "output file satisfies the cache-freshness minimum size check."
        }

        def streamChatCompletions(messages: List[agentica.session.Message], onToken: String => Unit,
                                  tools: List[ToolSpec] = Nil): LLMResponse =
            throw UnsupportedOperationException("not used in vision tests")

        override def streamResponses(
            input:              List[agentica.session.Message],
            onToken:            String => Unit,
            previousResponseId: Option[String],
            tools:              List[ToolSpec]
        ): LLMResponse =
            throw UnsupportedOperationException("not used in vision tests")
    }

    /** ScopeStore stub granting every permission check. */
    private val allGrants: ScopeStore = new ScopeStore
    {
        def hasGrant(sessionId: String, toolName: String, resolvedPath: String): Boolean = true
        def addGrant(sessionId: String, toolName: String, decision: GrantDecision.Granted): Unit = ()
        def consumeOnce(sessionId: String, toolName: String, resolvedPath: String): Unit = ()
        def deleteForSession(sessionId: String): Unit = ()
    }

    /** ScopeStore stub granting nothing; records `addGrant` invocations. */
    private class RecordingScopeStore extends ScopeStore
    {
        var addGrantCalls = 0
        def hasGrant(sessionId: String, toolName: String, resolvedPath: String): Boolean = false
        def addGrant(sessionId: String, toolName: String, decision: GrantDecision.Granted): Unit =
            addGrantCalls += 1
        def consumeOnce(sessionId: String, toolName: String, resolvedPath: String): Unit = ()
        def deleteForSession(sessionId: String): Unit = ()
    }

    /** Builds an ExecutionContext rooted at `root` with the given collaborators. */
    private def ctx(
        root:       Path,
        scopeStore: ScopeStore,
        provider:   LLMProvider,
        vlm:        Option[LLMProvider]       = None,
        coordinator: PermissionCoordinator    = new PermissionCoordinator("test-run"),
        events:     ListBuffer[AgentEvent]    = ListBuffer.empty,
        decision:   Option[GrantDecision]     = None
    ): ExecutionContext =
        ExecutionContext(
            session         = Session("s1", "Test", "", "", "test-model", Some(root.toString)),
            traceId         = "read-to-md-test",
            scopeStore      = scopeStore,
            scratchpad      = SessionScratchpad(),
            memoryStore     = null,
            llmProvider     = provider,
            vlmProvider     = vlm,
            onEvent         = e =>
            {
                events += e
                e match
                {
                    case AgentEvent.PermissionRequired(requestId, _, _, _) =>
                        decision.foreach(coordinator.resolve(requestId, _))
                    case _ => ()
                }
            },
            permissionCoordinator = coordinator,
            debugMode       = false,
            vlmParallelism  = 1
        )

    /** Copies a classpath fixture into `ws` under `name` and returns the new path. */
    private def copyFixture(resource: String, ws: Path, name: String): Path =
    {
        val src = Paths.get(getClass.getResource(resource).toURI)
        Files.copy(src, ws.resolve(name))
    }

    /** Runs `f` with a fresh temp workspace that is deleted recursively afterwards. */
    private def withWorkspace[A](f: Path => A): A =
    {
        val ws = Files.createTempDirectory("agentica-md-test")
        try f(ws)
        finally
            if (Files.exists(ws))
                Files.walk(ws).sorted(java.util.Comparator.reverseOrder())
                    .forEach(p => Files.deleteIfExists(p))
    }

    private val pdfFixture  = "/files/IT Support Analyst - India.pdf"
    private val pptxFixture = "/files/sample.pptx"
    private val docxFixture = "/files/sample.docx"

    // ── PDF ───────────────────────────────────────────────────────────────────

    test("files_read_pdf_to_markdown: generates .md on first call") {
        withWorkspace { ws =>
            val pdf      = copyFixture(pdfFixture, ws, "doc.pdf")
            val provider = StubVisionProvider()
            val out      = FilesReadPDFToMarkdown.execute(
                FilesReadPDFToMarkdownInput(Paths.get("doc.pdf"), enrichImages = true),
                ctx(ws, allGrants, provider))

            assert(out.error.isEmpty, s"unexpected error: ${out.error}")
            assert(out.wasRegenerated)
            assert(out.markdownPath == "doc.md")

            val md       = ws.resolve("doc.md")
            val pageCount = PDFPageRenderer.pageCount(pdf)
            assert(Files.exists(md))
            assert(Files.readString(md).contains("## Stub page"))
            assert(out.pageCount == pageCount)
            assert(provider.callCount.get() == pageCount)
        }
    }

    test("files_read_pdf_to_markdown: cache hit on second call") {
        withWorkspace { ws =>
            copyFixture(pdfFixture, ws, "doc.pdf")
            val provider = StubVisionProvider()
            val context  = ctx(ws, allGrants, provider)
            val input    = FilesReadPDFToMarkdownInput(Paths.get("doc.pdf"), enrichImages = true)

            val first = FilesReadPDFToMarkdown.execute(input, context)
            assert(first.wasRegenerated)
            val calls = provider.callCount.get()

            val second = FilesReadPDFToMarkdown.execute(input, context)
            assert(second.error.isEmpty)
            assert(!second.wasRegenerated)
            assert(second.markdownPath == "doc.md")
            assert(provider.callCount.get() == calls, "cache hit must not invoke the VLM")
        }
    }

    test("files_read_pdf_to_markdown: stale .md regenerated when source is newer") {
        withWorkspace { ws =>
            val pdf      = copyFixture(pdfFixture, ws, "doc.pdf")
            val provider = StubVisionProvider()
            val context  = ctx(ws, allGrants, provider)
            val input    = FilesReadPDFToMarkdownInput(Paths.get("doc.pdf"), enrichImages = true)

            FilesReadPDFToMarkdown.execute(input, context)
            val calls = provider.callCount.get()
            val now   = System.currentTimeMillis()

            Files.setLastModifiedTime(ws.resolve("doc.md"), FileTime.fromMillis(now - 60000))
            Files.setLastModifiedTime(pdf, FileTime.fromMillis(now))

            val second = FilesReadPDFToMarkdown.execute(input, context)
            assert(second.error.isEmpty)
            assert(second.wasRegenerated)
            assert(provider.callCount.get() == calls * 2)
        }
    }

    test("files_read_pdf_to_markdown: cache ignored when .md is too small") {
        withWorkspace { ws =>
            copyFixture(pdfFixture, ws, "doc.pdf")
            val provider = StubVisionProvider()
            // A fresh-but-tiny .md (<=100 bytes) must not count as a cache hit.
            Files.writeString(ws.resolve("doc.md"), "01234567890123456789")
            Files.setLastModifiedTime(ws.resolve("doc.md"),
                FileTime.fromMillis(System.currentTimeMillis() + 60000))

            val out = FilesReadPDFToMarkdown.execute(
                FilesReadPDFToMarkdownInput(Paths.get("doc.pdf"), enrichImages = true),
                ctx(ws, allGrants, provider))
            assert(out.error.isEmpty)
            assert(out.wasRegenerated)
        }
    }

    test("files_read_pdf_to_markdown: enrich_images=false writes stub markdown without VLM calls") {
        withWorkspace { ws =>
            val pdf      = copyFixture(pdfFixture, ws, "doc.pdf")
            val provider = StubVisionProvider()

            val input = FilesReadPDFToMarkdown.validate(
                Map("path" -> "doc.pdf", "enrich_images" -> "false"))
            assert(input.exists(_.enrichImages == false))

            val out = FilesReadPDFToMarkdown.execute(input.toOption.get, ctx(ws, allGrants, provider))
            assert(out.error.isEmpty)
            assert(provider.callCount.get() == 0)

            val content   = Files.readString(ws.resolve("doc.md"))
            val pageCount = PDFPageRenderer.pageCount(pdf)
            assert(content.contains("[page 1: vision enrichment skipped]"))
            assert(content.split("---", -1).length - 1 == pageCount - 1)
        }
    }

    test("files_read_pdf_to_markdown: validate parses enrich_images variants") {
        val base = Map("path" -> "doc.pdf")
        for (v <- List("false", "0", "no"))
            assert(FilesReadPDFToMarkdown.validate(base + ("enrich_images" -> v))
                .exists(_.enrichImages == false), s"enrich_images=$v must be false")
        for (v <- List("true", "yes", "1"))
            assert(FilesReadPDFToMarkdown.validate(base + ("enrich_images" -> v))
                .exists(_.enrichImages == true), s"enrich_images=$v must be true")
        assert(FilesReadPDFToMarkdown.validate(base).exists(_.enrichImages == true))

        FilesReadPDFToMarkdown.validate(Map.empty) match
        {
            case Left(err) => assert(err.arg.contains("path"))
            case Right(_)  => fail("missing path must fail validation")
        }
    }

    test("files_read_pdf_to_markdown: permission denied returns error and no .md") {
        withWorkspace { ws =>
            copyFixture(pdfFixture, ws, "doc.pdf")
            val provider = StubVisionProvider()
            val scope    = RecordingScopeStore()
            val coordinator = new PermissionCoordinator("pdf-denied")
            val events      = ListBuffer.empty[AgentEvent]

            val out = FilesReadPDFToMarkdown.execute(
                FilesReadPDFToMarkdownInput(Paths.get("doc.pdf"), enrichImages = true),
                ctx(ws, scope, provider, coordinator = coordinator, events = events,
                    decision = Some(GrantDecision.Denied)))

            out.error match
            {
                case Some(FilesError.IoError(msg)) => assert(msg.contains("Permission denied"))
                case other                         => fail(s"expected IoError(Permission denied), got $other")
            }
            val perms = events.collect { case e: AgentEvent.PermissionRequired => e }
            assert(perms.length == 1)
            assert(perms.head.requestId.nonEmpty)
            assert(perms.head.tool == "files_read_pdf_to_markdown")
            assert(perms.head.path.contains(""))
            assert(!Files.exists(ws.resolve("doc.md")))
            assert(provider.callCount.get() == 0)
        }
    }

    test("files_read_pdf_to_markdown: permission granted stores grant and proceeds") {
        withWorkspace { ws =>
            copyFixture(pdfFixture, ws, "doc.pdf")
            val provider = StubVisionProvider()
            val scope    = RecordingScopeStore()
            val coordinator = new PermissionCoordinator("pdf-granted")

            val out = FilesReadPDFToMarkdown.execute(
                FilesReadPDFToMarkdownInput(Paths.get("doc.pdf"), enrichImages = true),
                ctx(ws, scope, provider, coordinator = coordinator,
                    decision = Some(GrantDecision.Granted(GrantTTL.ForSession, None))))

            assert(out.error.isEmpty)
            assert(Files.exists(ws.resolve("doc.md")))
            assert(scope.addGrantCalls == 1)
        }
    }

    test("files_read_pdf_to_markdown: vision-unsupported provider returns structured error") {
        withWorkspace { ws =>
            copyFixture(pdfFixture, ws, "doc.pdf")
            val provider = StubVisionProvider(supportsVision = false)
            val out      = FilesReadPDFToMarkdown.execute(
                FilesReadPDFToMarkdownInput(Paths.get("doc.pdf"), enrichImages = true),
                ctx(ws, allGrants, provider))

            out.error match
            {
                case Some(FilesError.IoError(msg)) => assert(msg.contains("Vision LLM required"))
                case other                         => fail(s"expected IoError(Vision LLM required), got $other")
            }
            assert(!Files.exists(ws.resolve("doc.md")))
        }
    }

    test("files_read_pdf_to_markdown: render maps success and not-found correctly") {
        withWorkspace { ws =>
            copyFixture(pdfFixture, ws, "doc.pdf")
            val provider = StubVisionProvider()
            val context  = ctx(ws, allGrants, provider)
            val input    = FilesReadPDFToMarkdownInput(Paths.get("doc.pdf"), enrichImages = true)

            val generated = FilesReadPDFToMarkdown.render(
                FilesReadPDFToMarkdown.execute(input, context), context)
            assert(generated.status == ToolStatus.Ok)
            assert(generated.metadata("cache_status") == "generated")
            assert(generated.metadata("markdown_path") == "doc.md")

            val cached = FilesReadPDFToMarkdown.render(
                FilesReadPDFToMarkdown.execute(input, context), context)
            assert(cached.metadata("cache_status") == "cached")

            val missing = FilesReadPDFToMarkdown.render(
                FilesReadPDFToMarkdown.execute(
                    FilesReadPDFToMarkdownInput(Paths.get("missing.pdf"), enrichImages = true),
                    context),
                context)
            missing.status match
            {
                case ToolStatus.Err(code, _, _, _) => assert(code == ErrorCode.NotFound)
                case other                         => fail(s"expected Err, got $other")
            }
        }
    }

    test("files_read_pdf_to_markdown: VLM provider preferred over primary LLM") {
        withWorkspace { ws =>
            val pdf       = copyFixture(pdfFixture, ws, "doc.pdf")
            val providerA = StubVisionProvider()
            val providerB = StubVisionProvider()

            val out = FilesReadPDFToMarkdown.execute(
                FilesReadPDFToMarkdownInput(Paths.get("doc.pdf"), enrichImages = true),
                ctx(ws, allGrants, providerA, vlm = Some(providerB)))
            assert(out.error.isEmpty)
            assert(providerB.callCount.get() == PDFPageRenderer.pageCount(pdf))
            assert(providerA.callCount.get() == 0)
        }
    }

    // ── PPTX ──────────────────────────────────────────────────────────────────

    test("files_read_pptx_to_markdown: generates .md with one section per slide") {
        withWorkspace { ws =>
            copyFixture(pptxFixture, ws, "sample.pptx")
            val provider = StubVisionProvider()
            val out      = FilesReadPPTXToMarkdown.execute(
                FilesReadPPTXToMarkdownInput(Paths.get("sample.pptx"), enrichImages = true),
                ctx(ws, allGrants, provider))

            assert(out.error.isEmpty, s"unexpected error: ${out.error}")
            assert(out.wasRegenerated)
            assert(out.slideCount == 2)
            assert(provider.callCount.get() == 2)
            assert(Files.exists(ws.resolve("sample.md")))
        }
    }

    test("files_read_pptx_to_markdown: cache hit on second call") {
        withWorkspace { ws =>
            copyFixture(pptxFixture, ws, "sample.pptx")
            val provider = StubVisionProvider()
            val context  = ctx(ws, allGrants, provider)
            val input    = FilesReadPPTXToMarkdownInput(Paths.get("sample.pptx"), enrichImages = true)

            FilesReadPPTXToMarkdown.execute(input, context)
            val calls  = provider.callCount.get()
            val second = FilesReadPPTXToMarkdown.execute(input, context)
            assert(second.error.isEmpty)
            assert(!second.wasRegenerated)
            assert(provider.callCount.get() == calls)
        }
    }

    test("files_read_pptx_to_markdown: enrich_images=false writes stub markdown") {
        withWorkspace { ws =>
            copyFixture(pptxFixture, ws, "sample.pptx")
            val provider = StubVisionProvider()
            val out      = FilesReadPPTXToMarkdown.execute(
                FilesReadPPTXToMarkdownInput(Paths.get("sample.pptx"), enrichImages = false),
                ctx(ws, allGrants, provider))

            assert(out.error.isEmpty)
            assert(provider.callCount.get() == 0)
            assert(Files.readString(ws.resolve("sample.md"))
                .contains("[page 1: vision enrichment skipped]"))
        }
    }

    test("files_read_pptx_to_markdown: permission denied returns error and no .md") {
        withWorkspace { ws =>
            copyFixture(pptxFixture, ws, "sample.pptx")
            val provider = StubVisionProvider()
            val coordinator = new PermissionCoordinator("pptx-denied")

            val out = FilesReadPPTXToMarkdown.execute(
                FilesReadPPTXToMarkdownInput(Paths.get("sample.pptx"), enrichImages = true),
                ctx(ws, RecordingScopeStore(), provider, coordinator = coordinator,
                    decision = Some(GrantDecision.Denied)))

            out.error match
            {
                case Some(FilesError.IoError(msg)) => assert(msg.contains("Permission denied"))
                case other                         => fail(s"expected IoError(Permission denied), got $other")
            }
            assert(!Files.exists(ws.resolve("sample.md")))
        }
    }

    // ── DOCX ──────────────────────────────────────────────────────────────────

    test("files_read_docx_to_markdown: cache hit returns cached path without LibreOffice") {
        withWorkspace { ws =>
            copyFixture(docxFixture, ws, "sample.docx")
            val provider = StubVisionProvider()
            // Pre-write a fresh, non-trivial .md — the cache path short-circuits
            // before the LibreOffice availability check.
            Files.writeString(ws.resolve("sample.md"),
                "## Cached\n\n" + ("cached content ".repeat(20)))
            Files.setLastModifiedTime(ws.resolve("sample.docx"),
                FileTime.fromMillis(System.currentTimeMillis() - 60000))

            val out = FilesReadDOCXToMarkdown.execute(
                FilesReadDOCXToMarkdownInput(Paths.get("sample.docx"), enrichImages = true),
                ctx(ws, allGrants, provider))
            assert(out.error.isEmpty)
            assert(!out.wasRegenerated)
            assert(out.markdownPath == "sample.md")
            assert(provider.callCount.get() == 0)
        }
    }

    test("files_read_docx_to_markdown: path escape yields path_escaped") {
        withWorkspace { ws =>
            val out = FilesReadDOCXToMarkdown.execute(
                FilesReadDOCXToMarkdownInput(Paths.get("../../etc/passwd.docx"), enrichImages = true),
                ctx(ws, allGrants, StubVisionProvider()))
            assert(out.error.contains(FilesError.PathEscaped))
        }
    }

    test("files_read_docx_to_markdown: missing file yields not_found") {
        withWorkspace { ws =>
            val out = FilesReadDOCXToMarkdown.execute(
                FilesReadDOCXToMarkdownInput(Paths.get("missing.docx"), enrichImages = true),
                ctx(ws, allGrants, StubVisionProvider()))
            assert(out.error.contains(FilesError.NotFound))
        }
    }

    test("files_read_docx_to_markdown: missing LibreOffice yields structured error") {
        assume(!DocToolDetector.available, "LibreOffice is installed — nothing to test")
        withWorkspace { ws =>
            copyFixture(docxFixture, ws, "sample.docx")
            val out = FilesReadDOCXToMarkdown.execute(
                FilesReadDOCXToMarkdownInput(Paths.get("sample.docx"), enrichImages = true),
                ctx(ws, allGrants, StubVisionProvider()))
            out.error match
            {
                case Some(FilesError.IoError(msg)) => assert(msg.contains("LibreOffice"))
                case other                         => fail(s"expected IoError(LibreOffice), got $other")
            }
        }
    }
}
