package agentica.tools.files

import agentica.agent.AgentEvent
import agentica.permissions.{GrantDecision, ScopeStore}
import agentica.session.{MemoryStore, Session}
import agentica.shell.{Presentation, SessionScratchpad}
import agentica.tools.{ExecutionContext, ToolBody}
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.{Files, Paths}
import java.util.concurrent.SynchronousQueue

/**
 *  Test suite for FilesRead and FilesSearch scratchpad functionality.
 *
 *  Verifies that large file content and search results are properly stored
 *  in the SessionScratchpad when they exceed the body budget, and that
 *  small files correctly bypass scratchpad storage.
 */
class FilesReadScratchpadTest extends AnyFunSuite
{

    private val noGrants: ScopeStore = new ScopeStore
    {
        def hasGrant(sessionId: String, toolName: String, resolvedPath: String): Boolean = false
        def addGrant(sessionId: String, toolName: String, decision: GrantDecision.Granted): Unit = ()
        def consumeOnce(sessionId: String, toolName: String, resolvedPath: String): Unit = ()
        def deleteForSession(sessionId: String): Unit = ()
    }

    private def mkCtx(rootPath: String, scratchpad: SessionScratchpad): ExecutionContext =
        ExecutionContext(
            session         = Session("s1", "Test", "", "", "test-model", Some(rootPath)),
            traceId         = "scratchpad-bug-test",
            scopeStore      = noGrants,
            scratchpad      = scratchpad,
            memoryStore     = null,
            llmProvider     = null,
            onEvent         = _ => (),
            permissionLatch = SynchronousQueue[GrantDecision]()
        )

    private def deleteTmpDir(dir: java.nio.file.Path): Unit =
        Files.walk(dir)
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(Files.delete(_))

    
    test("FilesRead stores large file content in scratchpad") {
        // Tests that FilesRead properly stores content in scratchpad when file exceeds body budget
        
        val tmpDir = Files.createTempDirectory("agentica-scratchpad-bug1-read")
        try
        {
            val bigContent = "a" * (Presentation.BODY_BUDGET_CHARS + 1000)
            val file       = tmpDir.resolve("report.txt")
            Files.writeString(file, bigContent)

            val scratchpad = SessionScratchpad()
            val ctx        = mkCtx(tmpDir.toString, scratchpad)

            val input  = FilesReadInput(Paths.get("report.txt"), None)
            val output = FilesRead.execute(input, ctx)

            // Verify file was read successfully and exceeds body budget
            assert(output.error.isEmpty, "file read must succeed")
            assert(
                output.content.length > Presentation.BODY_BUDGET_CHARS,
                "content must exceed BODY_BUDGET_CHARS to trigger scratchpad storage"
            )

            // Verify render() returns ScratchRef for oversized content
            val result = FilesRead.render(output, ctx)
            val body   = result.body
            assert(body.isDefined && body.get.isInstanceOf[ToolBody.ScratchRef],
                "render() must return a ScratchRef for oversized content")

            val ref = body.get.asInstanceOf[ToolBody.ScratchRef].ref
            assert(ref == "$scratch/report.txt")

            // Verify content was actually stored in scratchpad and is accessible
            val stored = scratchpad.get(ref)
            assert(
                stored.isDefined,
                "scratchpad must contain the stored content for the ref to resolve"
            )
            assert(stored.get.content == bigContent, "stored content must match original file content")
        }
        finally
        {
            deleteTmpDir(tmpDir)
        }
    }

    test("FilesRead small files return Inline body and are stored in scratchpad") {
        val tmpDir = Files.createTempDirectory("agentica-scratchpad-bug1-small")
        try
        {
            val smallContent = "hello world\n"
            val file         = tmpDir.resolve("small.txt")
            Files.writeString(file, smallContent)

            val scratchpad = SessionScratchpad()
            val ctx        = mkCtx(tmpDir.toString, scratchpad)

            val input  = FilesReadInput(Paths.get("small.txt"), None)
            val output = FilesRead.execute(input, ctx)
            val result = FilesRead.render(output, ctx)

            assert(result.body.exists(_.isInstanceOf[ToolBody.Inline]),
                "small file must return an Inline body with content")
            assert(scratchpad.size == 1,
                "small file must be stored in the scratchpad for downstream chaining")
            assert(result.metadata.get("stored").exists(_.startsWith("$scratch/")),
                "small file result must include a stored: ref in metadata")
            assert(result.metadata("stored") == "$scratch/small.txt",
                "stored ref must be path-keyed")
            val stored = scratchpad.get("$scratch/small.txt")
            assert(stored.isDefined && stored.get.content == smallContent.stripTrailing(),
                "scratchpad content must match the file (readAllLines strips trailing newline)")
        }
        finally
        {
            deleteTmpDir(tmpDir)
        }
    }

    test("FilesRead range reads return Inline body without scratchpad storage") {
        val tmpDir = Files.createTempDirectory("agentica-scratchpad-range-read")
        try
        {
            val content = (1 to 100).map(i => s"line $i").mkString("\n")
            Files.writeString(tmpDir.resolve("lines.txt"), content)

            val scratchpad = SessionScratchpad()
            val ctx        = mkCtx(tmpDir.toString, scratchpad)

            val input  = FilesReadInput(Paths.get("lines.txt"), Some((1, 10)))
            val output = FilesRead.execute(input, ctx)
            val result = FilesRead.render(output, ctx)

            assert(result.body.exists(_.isInstanceOf[ToolBody.Inline]),
                "range read must return Inline body")
            assert(scratchpad.size == 0,
                "range read must not store partial content in scratchpad")
            assert(!result.metadata.contains("stored"),
                "range read must not include a stored: ref")
        }
        finally
        {
            deleteTmpDir(tmpDir)
        }
    }

    test("FilesSearch small results include counter-keyed stored: ref") {
        val tmpDir = Files.createTempDirectory("agentica-scratchpad-search-small")
        try
        {
            Files.writeString(tmpDir.resolve("note.txt"), "needle here\n")

            val scratchpad = SessionScratchpad()
            val ctx        = mkCtx(tmpDir.toString, scratchpad)

            val input = FilesSearchInput(
                rawPath = "", query = "needle", recursive = true,
                ignoreCase = false, linesContext = 0, maxMatches = 50,
                include = None, useRegex = false
            )
            val output = FilesSearch.execute(input, ctx)
            val result = FilesSearch.render(output, ctx)

            assert(result.body.exists(_.isInstanceOf[ToolBody.Inline]),
                "small search result must return Inline body")
            assert(scratchpad.size == 1,
                "search result must be stored in scratchpad")
            assert(result.metadata.get("stored").exists(_.startsWith("$scratch/__result_")),
                "search result must include a counter-keyed stored: ref")
        }
        finally
        {
            deleteTmpDir(tmpDir)
        }
    }

    test("FilesSearch counter key increments across multiple render calls") {
        val tmpDir = Files.createTempDirectory("agentica-scratchpad-counter")
        try
        {
            Files.writeString(tmpDir.resolve("a.txt"), "needle\n")

            val scratchpad = SessionScratchpad()
            val ctx        = mkCtx(tmpDir.toString, scratchpad)
            val input = FilesSearchInput(
                rawPath = "", query = "needle", recursive = true,
                ignoreCase = false, linesContext = 0, maxMatches = 50,
                include = None, useRegex = false
            )

            val r1 = FilesSearch.render(FilesSearch.execute(input, ctx), ctx)
            val r2 = FilesSearch.render(FilesSearch.execute(input, ctx), ctx)

            val ref1 = r1.metadata.getOrElse("stored", "")
            val ref2 = r2.metadata.getOrElse("stored", "")
            assert(ref1 != ref2, "each search render must produce a distinct counter ref")
            assert(ref1.contains("__result_1__"), s"first ref should be __result_1__, got $ref1")
            assert(ref2.contains("__result_2__"), s"second ref should be __result_2__, got $ref2")
        }
        finally
        {
            deleteTmpDir(tmpDir)
        }
    }

    test("FilesSearch stores large results in scratchpad") {
        // Tests that FilesSearch properly stores large search results in scratchpad
        
        val tmpDir = Files.createTempDirectory("agentica-scratchpad-bug1-search")
        try
        {
            // Create enough matching files to exceed the body budget
            for (i <- 1 to 50)
            {
                val content = (1 to 20).map(j => s"needle line $j in file $i with extra padding").mkString("\n")
                Files.writeString(tmpDir.resolve(s"file$i.txt"), content)
            }

            val scratchpad = SessionScratchpad()
            val ctx        = mkCtx(tmpDir.toString, scratchpad)

            val input = FilesSearchInput(
                rawPath      = "",
                query        = "needle",
                recursive    = true,
                ignoreCase   = false,
                linesContext = 0,
                maxMatches   = 500,
                include      = None,
                useRegex     = false
            )
            val output = FilesSearch.execute(input, ctx)
            val result = FilesSearch.render(output, ctx)

            // Verify scratchpad behavior based on result size
            result.body match
            {
                case Some(ref: ToolBody.ScratchRef) =>
                    val stored = scratchpad.get(ref.ref)
                    assert(
                        stored.isDefined,
                        "FilesSearch must store large results in scratchpad so the ref resolves"
                    )
                case Some(ToolBody.Inline(_)) =>
                    // Results fit inline — still stored with counter key; body just wasn't oversize
                    assert(scratchpad.size >= 1, "even inline results must be stored in scratchpad")
                case None =>
                    fail("FilesSearch result must have a body")
            }
        }
        finally
        {
            deleteTmpDir(tmpDir)
        }
    }
}
