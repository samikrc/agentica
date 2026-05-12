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
 *  Proves that FilesRead (and FilesSearch) do NOT store large content in the scratchpad.
 *
 *  Bug #1: render() returns ToolBody.ScratchRef for files that exceed BODY_BUDGET_CHARS,
 *  but execute() never calls ctx.scratchpad.store(). The ref is dead — when
 *  VirtualShell.resolveRefs() later sees $scratch/... it finds Nothing and falls back
 *  to passing the literal ref string to the next tool.
 *
 *  Root cause: Tool.render() has signature render(output: O): ToolResult — no
 *  ExecutionContext parameter — so only execute() can reach ctx.scratchpad.store().
 *  The fix belongs in execute(): store content there when it exceeds the budget,
 *  then communicate to render() via a flag in the output type.
 *
 *  These tests FAIL with the current implementation and PASS after the fix.
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

    // ── Bug #1: FilesRead ─────────────────────────────────────────────────────

    test("BUG #1 — FilesRead: execute() must store large file content in scratchpad") {
        // When a file exceeds BODY_BUDGET_CHARS (8000 chars), render() correctly classifies
        // it as a ScratchRef. But execute() never calls ctx.scratchpad.store(), so the ref
        // is dead: VirtualShell.resolveRefs() returns None and logs scratch_ref_not_found.
        // This test FAILS until execute() is fixed to call ctx.scratchpad.store().

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

            // Confirm prerequisite: file was read and is large enough
            assert(output.error.isEmpty, "file read must succeed")
            assert(
                output.content.length > Presentation.BODY_BUDGET_CHARS,
                "test prerequisite: content must exceed BODY_BUDGET_CHARS to exercise the scratchpad path"
            )

            // Confirm render() classifies it as a ScratchRef (this part works)
            val result = FilesRead.render(output)
            val body   = result.body
            assert(body.isDefined && body.get.isInstanceOf[ToolBody.ScratchRef],
                "render() must return a ScratchRef for oversized content")

            val ref = body.get.asInstanceOf[ToolBody.ScratchRef].ref
            assert(ref == "$scratch/report.txt")

            // BUG: execute() read the file content but never called ctx.scratchpad.store().
            // VirtualShell.resolveRefs() will find None for this ref and pass the literal
            // "$scratch/report.txt" string to the next tool instead of the file content.
            val stored = scratchpad.get(ref)
            assert(
                stored.isDefined,
                "execute() must call ctx.scratchpad.store() so the returned $scratch ref resolves"
            )
            assert(stored.get.content == bigContent, "stored content must match the original file")
        }
        finally
        {
            deleteTmpDir(tmpDir)
        }
    }

    test("BUG #1 — FilesRead: small file is NOT stored in scratchpad (happy path should still pass)") {
        // Small files go inline and must never touch the scratchpad.
        // This test must pass both before and after the fix.

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
            val result = FilesRead.render(output)

            assert(result.body.exists(_.isInstanceOf[ToolBody.Inline]),
                "small file must produce an Inline body, not a ScratchRef")
            assert(scratchpad.size == 0, "small file must not be stored in the scratchpad")
        }
        finally
        {
            deleteTmpDir(tmpDir)
        }
    }

    // ── Bug #1: FilesSearch (same structural issue) ────────────────────────────

    test("BUG #1 — FilesSearch: execute() must store large search results in scratchpad") {
        // FilesSearch.render() also returns ScratchRef("$scratch/__search_result__", ...)
        // when matches exceed the body budget. Same bug: content never stored.

        val tmpDir = Files.createTempDirectory("agentica-scratchpad-bug1-search")
        try
        {
            // Write enough matching files to push the result past BODY_BUDGET_CHARS.
            // Each line "needle line NNN — padding..." is ~50 chars; 200 files * 2 matching
            // lines = ~20 KB of results, well above the 8000-char budget.
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
            val result = FilesSearch.render(output)

            // Only assert the scratchpad behaviour if results were large enough
            result.body match
            {
                case Some(ref: ToolBody.ScratchRef) =>
                    val stored = scratchpad.get(ref.ref)
                    assert(
                        stored.isDefined,
                        "FilesSearch must store large results in scratchpad so the ref resolves"
                    )
                case Some(ToolBody.Inline(_)) =>
                    // Results fit inline — scratchpad path not triggered; nothing to assert
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
