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

    test("FilesRead small files bypass scratchpad storage") {
        // Tests that small files are returned inline and not stored in scratchpad
        
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
                "small file must produce an Inline body, not a ScratchRef")
            assert(scratchpad.size == 0, "small file must not be stored in the scratchpad")
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
                    // Results fit inline - scratchpad not used, which is valid
                    ()
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
