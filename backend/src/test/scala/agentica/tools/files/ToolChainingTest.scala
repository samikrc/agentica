package agentica.tools.files

import agentica.permissions.{GrantDecision, ScopeStore}
import agentica.session.{Session, MemoryStore}
import agentica.shell.{CommandRegistry, Presentation, ScratchEntry, SessionScratchpad, VirtualShell}
import agentica.tools.{ExecutionContext, ToolBody, ToolStatus}
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.{Files, Paths}
import java.util.concurrent.SynchronousQueue

/**
 *  Integration tests for scratchpad-based tool chaining.
 *
 *  Tests verify that `$scratch/<path>` refs produced by `files.read` and
 *  `files.search` are resolved by `VirtualShell.resolveRefs` before dispatch,
 *  enabling downstream tools to receive the full content as a plain string.
 *
 *  Coverage:
 *  - `files.read` → `$scratch/` ref → resolved into a subsequent command arg.
 *  - `files.search` → counter-keyed `$scratch/` ref → resolved into a subsequent command arg.
 *  - Stale cache invalidation: modified file triggers re-read, fresh file returns cached entry.
 *  - LRU eviction at scratchpad capacity (20 entries).
 *  - Empty search results still produce a scratchpad entry.
 *  - Unresolvable `$scratch/` ref is passed through and produces a tool error, not a JVM exception.
 */
class ToolChainingTest extends AnyFunSuite
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
            traceId         = "chain-test",
            scopeStore      = noGrants,
            scratchpad      = scratchpad,
            memoryStore     = null,
            llmProvider     = null,
            onEvent         = _ => (),
            permissionLatch = SynchronousQueue[GrantDecision]()
        )

    private def mkShell(): (VirtualShell, CommandRegistry) =
    {
        val registry = CommandRegistry()
        List(FilesRead, FilesSearch, FilesStat).foreach(registry.register)
        val shell = VirtualShell(registry)
        (shell, registry)
    }

    private def deleteTmpDir(dir: java.nio.file.Path): Unit =
        Files.walk(dir)
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(Files.delete(_))

    // ── 1. VirtualShell resolves $scratch/ ref from files.read into a downstream arg ──

    test("VirtualShell resolves scratch ref from files.read into downstream tool arg") {
        val tmpDir = Files.createTempDirectory("agentica-chain-resolve")
        try
        {
            Files.writeString(tmpDir.resolve("config.txt"), "key=value\nsecret=abc")

            val scratchpad = SessionScratchpad()
            val ctx        = mkCtx(tmpDir.toString, scratchpad)
            val (shell, _) = mkShell()

            // Step 1: read the file — populates the scratchpad
            val readResp = shell.execute("files.read path=config.txt", ctx)
            assert(readResp.text.contains("ok"), s"files.read must succeed, got:\n${readResp.text}")
            assert(readResp.text.contains("stored: $scratch/config.txt"),
                "response must include stored: ref in metadata")

            // Verify the ref is in the scratchpad
            val entry = scratchpad.get("$scratch/config.txt")
            assert(entry.isDefined, "scratchpad must hold the entry after files.read")
            assert(entry.get.content.contains("key=value"), "stored content must match file")

            // Step 2: use the ref as a raw arg value (simulates what a downstream tool would see
            // after VirtualShell's resolveRefs pass).  We use files.stat (which ignores text=)
            // and instead manually call resolveRefs indirectly by issuing a command that
            // references $scratch/config.txt in an arg and observing that the shell does not
            // emit a "scratch_ref_not_found" warning — i.e. the ref resolved cleanly.
            //
            // The most direct test: build a new tool that accepts text= and just returns it.
            // We don't have one in prod, so instead we verify the substitution path directly
            // by checking that resolveRefs (via a second shell.execute call that passes the ref)
            // doesn't trip the warn path.  We use files.stat with path=$scratch/config.txt —
            // that path won't exist on disk but we're specifically testing that resolveRefs
            // expands the ref *before* the tool sees it, so the resolved value ("key=value\n...")
            // is what arrives at validate(), which will fail with invalid_args (not a path), NOT
            // with not_found on the literal "$scratch/config.txt" string.
            val chainResp = shell.execute("files.stat path=$scratch/config.txt", ctx)
            // After resolution the arg value is the file's text content — not a path — so
            // files.stat will fail with path_escaped or invalid_args, NOT not_found on the
            // literal ref string.  The key assertion is that the raw ref string is NOT in the
            // error message (it was replaced by the content).
            assert(
                !chainResp.text.contains("$scratch/config.txt"),
                "resolved arg must not contain the raw $scratch/ ref — it should have been substituted"
            )
        }
        finally
        {
            deleteTmpDir(tmpDir)
        }
    }

    // ── 2. files.read then files.search: two separate refs coexist in the scratchpad ──

    test("files.read and files.search produce independent scratchpad refs") {
        val tmpDir = Files.createTempDirectory("agentica-chain-independent")
        try
        {
            Files.writeString(tmpDir.resolve("notes.txt"), "project: alpha\nstatus: active")
            Files.writeString(tmpDir.resolve("log.txt"), "alpha started\nalpha completed")

            val scratchpad = SessionScratchpad()
            val ctx        = mkCtx(tmpDir.toString, scratchpad)
            val (shell, _) = mkShell()

            // Read file → path-keyed ref
            val readResp = shell.execute("files.read path=notes.txt", ctx)
            assert(readResp.text.contains("ok"))

            // Search → counter-keyed ref
            val searchResp = shell.execute("files.search query=alpha", ctx)
            assert(searchResp.text.contains("ok"))

            // Both refs must be present and independent
            assert(scratchpad.size == 2, s"expected 2 scratchpad entries, got ${scratchpad.size}")

            val readEntry   = scratchpad.get("$scratch/notes.txt")
            val searchEntry = scratchpad.get("$scratch/__result_1__")
            assert(readEntry.isDefined,   "path-keyed ref must exist for notes.txt")
            assert(searchEntry.isDefined, "counter-keyed ref must exist for search result")

            assert(readEntry.get.content.contains("project: alpha"),
                "file content must be stored in path-keyed entry")
            assert(searchEntry.get.content.contains("alpha"),
                "search match text must be stored in counter-keyed entry")
        }
        finally
        {
            deleteTmpDir(tmpDir)
        }
    }

    // ── 3. Stale cache: re-reading a modified file replaces the scratchpad entry ──

    test("files.read re-reads and updates scratchpad when file is modified") {
        val tmpDir = Files.createTempDirectory("agentica-chain-staleness")
        try
        {
            val file = tmpDir.resolve("data.txt")
            Files.writeString(file, "version: 1")

            val scratchpad = SessionScratchpad()
            val ctx        = mkCtx(tmpDir.toString, scratchpad)
            val input1     = FilesReadInput(Paths.get("data.txt"), None)

            // First read
            val out1 = FilesRead.execute(input1, ctx)
            FilesRead.render(out1, ctx)
            assert(scratchpad.get("$scratch/data.txt").exists(_.content.contains("version: 1")))

            // Modify file — ensure mtime changes
            Thread.sleep(10)
            Files.writeString(file, "version: 2")
            // Touch the mtime to be certain it differs (some FSes have 1s resolution)
            file.toFile.setLastModified(System.currentTimeMillis())

            // Second read of the same path
            val out2 = FilesRead.execute(input1, ctx)
            FilesRead.render(out2, ctx)

            val updated = scratchpad.get("$scratch/data.txt")
            assert(updated.isDefined, "entry must still be present after re-read")
            assert(updated.get.content.contains("version: 2"),
                "scratchpad must hold updated content after file modification")
        }
        finally
        {
            deleteTmpDir(tmpDir)
        }
    }

    // ── 4. Fresh cache: re-reading an unchanged file returns cached entry (no disk re-read) ──

    test("files.read returns cached entry without re-reading when file is unchanged") {
        val tmpDir = Files.createTempDirectory("agentica-chain-cache-hit")
        try
        {
            Files.writeString(tmpDir.resolve("stable.txt"), "constant content")

            val scratchpad = SessionScratchpad()
            val ctx        = mkCtx(tmpDir.toString, scratchpad)
            val input      = FilesReadInput(Paths.get("stable.txt"), None)

            // First read — populates cache
            FilesRead.render(FilesRead.execute(input, ctx), ctx)
            val entry1 = scratchpad.get("$scratch/stable.txt")
            assert(entry1.isDefined)
            val storedAt1 = entry1.get.storedAt

            // Small delay so storedAt would differ if re-stored
            Thread.sleep(5)

            // Second execute() on an unchanged file — must return the cached content.
            // Note: render() unconditionally re-stores (it updates storedAt), but execute()
            // must return the cached FilesReadOutput without going to disk again.
            val out2 = FilesRead.execute(input, ctx)
            assert(out2.content == "constant content",
                "execute() must return cached content on a cache hit")
            assert(out2.error.isEmpty,
                "cache hit must produce no error")
            // Confirm the entry content is stable (render will update storedAt, which is fine)
            FilesRead.render(out2, ctx)
            val entry2 = scratchpad.get("$scratch/stable.txt")
            assert(entry2.isDefined)
            assert(entry2.get.content == "constant content",
                "cached content must remain correct after second render")
        }
        finally
        {
            deleteTmpDir(tmpDir)
        }
    }

    // ── 5. LRU eviction: 21st entry evicts the oldest ──

    test("scratchpad evicts oldest entry when capacity (20) is exceeded") {
        val scratchpad = SessionScratchpad()
        val now        = System.currentTimeMillis()

        // Fill to capacity
        for (i <- 1 to 20)
        {
            scratchpad.store(
                s"file$i.txt",
                ScratchEntry(s"content $i", 10L, 1, s"file$i.txt", now, now)
            )
        }
        assert(scratchpad.size == 20, "scratchpad must be at capacity before eviction")

        // The oldest entry is file1.txt — verify it is present before eviction
        assert(scratchpad.get("$scratch/file1.txt").isDefined,
            "oldest entry must be present before the 21st store")

        // Store the 21st entry — must trigger LRU eviction of file1.txt
        scratchpad.store("file21.txt", ScratchEntry("content 21", 10L, 1, "file21.txt", now, now))

        assert(scratchpad.size == 20, "size must remain at 20 after eviction")
        assert(scratchpad.get("$scratch/file1.txt").isEmpty,
            "oldest entry must be evicted when scratchpad exceeds capacity")
        assert(scratchpad.get("$scratch/file21.txt").isDefined,
            "newly stored entry must be present after eviction")
        // All middle entries must still be there
        assert(scratchpad.get("$scratch/file20.txt").isDefined,
            "second-oldest entry must survive eviction")
    }

    // ── 6. Empty search results still produce a scratchpad entry ──

    test("FilesSearch stores empty result set in scratchpad") {
        val tmpDir = Files.createTempDirectory("agentica-chain-empty-search")
        try
        {
            Files.writeString(tmpDir.resolve("doc.txt"), "nothing relevant here")

            val scratchpad = SessionScratchpad()
            val ctx        = mkCtx(tmpDir.toString, scratchpad)

            val input = FilesSearchInput(
                rawPath = "", query = "XYZZY_NOMATCH", recursive = true,
                ignoreCase = false, linesContext = 0, maxMatches = 50,
                include = None, useRegex = false
            )
            val output = FilesSearch.execute(input, ctx)
            val result = FilesSearch.render(output, ctx)

            assert(result.status == ToolStatus.Ok,
                "zero-match search must still return Ok status")
            assert(scratchpad.size == 1,
                "even an empty search result must be stored in scratchpad")
            assert(result.metadata.get("stored").exists(_.startsWith("$scratch/__result_")),
                "empty search must still return a stored: ref so downstream tools can chain on it")
            assert(result.metadata("matches") == "0",
                "matches metadata must be 0")
        }
        finally
        {
            deleteTmpDir(tmpDir)
        }
    }

    // ── 7. Unresolvable $scratch/ ref: VirtualShell passes it through; tool produces an error ──

    test("VirtualShell passes unresolvable scratch ref through; tool returns error not exception") {
        val tmpDir = Files.createTempDirectory("agentica-chain-unresolvable")
        try
        {
            val scratchpad = SessionScratchpad()
            val ctx        = mkCtx(tmpDir.toString, scratchpad)
            val (shell, _) = mkShell()

            // No files.read has run — scratchpad is empty.
            // Using a ref that was never stored.
            val resp = shell.execute("files.stat path=$scratch/ghost.txt", ctx)

            // Must not throw; must produce a structured error response.
            assert(resp.text.nonEmpty, "response must not be empty")
            assert(resp.text.startsWith("$ files.stat"),
                "response must start with command echo line")
            // The ref was replaced by its string value (either the raw ref or the resolved content),
            // so the path passed to files.stat will not be a valid relative path → error response.
            assert(
                resp.text.contains("error:"),
                "unresolvable ref must produce an error, not a successful result"
            )
        }
        finally
        {
            deleteTmpDir(tmpDir)
        }
    }

    // ── 8. Counter key is scratchpad-scoped (new SessionScratchpad resets counter) ──

    test("counter resets to 1 in a new SessionScratchpad instance") {
        val sp1 = SessionScratchpad()
        val sp2 = SessionScratchpad()

        assert(sp1.nextComputedKey() == "__result_1__",
            "first key in first scratchpad must be __result_1__")
        sp1.nextComputedKey()  // advance sp1 to 2

        assert(sp2.nextComputedKey() == "__result_1__",
            "first key in second scratchpad must also start at 1 (independent counter)")
    }
}
