package agentica.tools.files

import agentica.permissions.{GrantDecision, ScopeStore}
import agentica.session.{Session, MemoryStore}
import agentica.shell.{CommandRegistry, Presentation, ScratchEntry, SessionScratchpad, VirtualShell}
import agentica.tools.{ExecutionContext, ToolBody, ToolStatus}
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.{Files, Paths}
import agentica.permissions.PermissionCoordinator

/**
 *  Integration tests for scratchpad-based tool chaining.
 *
 *  Tests verify that `$scratch/<path>` refs produced by `files_read` and
 *  `files_search` are resolved by `VirtualShell.resolveRefs` before dispatch,
 *  enabling downstream tools to receive the full content as a plain string.
 *
 *  Coverage:
 *  - `files_read` → `$scratch/` ref → resolved into a subsequent command arg.
 *  - `files_search` → counter-keyed `$scratch/` ref → resolved into a subsequent command arg.
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

    /** Creates an execution context for tool-chaining tests. */
    private def mkCtx(rootPath: String, scratchpad: SessionScratchpad): ExecutionContext =
        ExecutionContext(
            session         = Session("s1", "Test", "", "", "test-model", Some(rootPath)),
            traceId         = "chain-test",
            scopeStore      = noGrants,
            scratchpad      = scratchpad,
            memoryStore     = null,
            llmProvider     = null,
            vlmProvider     = None,
            onEvent         = _ => (),
            permissionCoordinator = new PermissionCoordinator("test-run"),
            debugMode       = false
        )

    /** Builds a virtual shell with the tools used by chaining tests. */
    private def mkShell(): (VirtualShell, CommandRegistry) =
    {
        val registry = CommandRegistry()
        List(FilesRead, FilesSearch, FilesStat).foreach(registry.register)
        val shell = VirtualShell(registry)
        (shell, registry)
    }

    /** Deletes a temporary test workspace recursively. */
    private def deleteTmpDir(dir: java.nio.file.Path): Unit =
        Files.walk(dir)
            .sorted(java.util.Comparator.reverseOrder())
            .forEach(Files.delete(_))

    // ── 1. VirtualShell resolves $scratch/ ref from files_read into a downstream arg ──

    test("VirtualShell resolves scratch ref from files_read into downstream tool arg") {
        val tmpDir = Files.createTempDirectory("agentica-chain-resolve")
        try
        {
            Files.writeString(tmpDir.resolve("config.txt"), "key=value\nsecret=abc")

            val scratchpad = SessionScratchpad()
            val ctx        = mkCtx(tmpDir.toString, scratchpad)
            val (shell, _) = mkShell()

            // Step 1: read the file — populates the scratchpad
            val readResp = shell.execute("files_read path=config.txt", ctx)
            assert(readResp.text.contains("ok"), s"files_read must succeed, got:\n${readResp.text}")
            assert(readResp.text.contains("stored: $scratch/config.txt"),
                "response must include stored: ref in metadata")

            // Verify the ref is in the scratchpad
            val entry = scratchpad.get("$scratch/config.txt")
            assert(entry.isDefined, "scratchpad must hold the entry after files_read")
            assert(entry.get.content.contains("key=value"), "stored content must match file")

            // Step 2: use the ref as a raw arg value (simulates what a downstream tool would see
            // after VirtualShell's resolveRefs pass).  We use files_stat (which ignores text=)
            // and instead manually call resolveRefs indirectly by issuing a command that
            // references $scratch/config.txt in an arg and observing that the shell does not
            // emit a "scratch_ref_not_found" warning — i.e. the ref resolved cleanly.
            //
            // The most direct test: build a new tool that accepts text= and just returns it.
            // We don't have one in prod, so instead we verify the substitution path directly
            // by checking that resolveRefs (via a second shell.execute call that passes the ref)
            // doesn't trip the warn path.  We use files_stat with path=$scratch/config.txt —
            // that path won't exist on disk but we're specifically testing that resolveRefs
            // expands the ref *before* the tool sees it, so the resolved value ("key=value\n...")
            // is what arrives at validate(), which will fail with invalid_args (not a path), NOT
            // with not_found on the literal "$scratch/config.txt" string.
            val chainResp = shell.execute("files_stat path=$scratch/config.txt", ctx)
            // After resolution the arg value is the file's text content — not a path — so
            // files_stat will fail with path_escaped or invalid_args, NOT not_found on the
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

    // ── 2. files_read then files_search: two separate refs coexist in the scratchpad ──

    test("files_read stores a path-keyed ref while a routine search page stays inline") {
        val tmpDir = Files.createTempDirectory("agentica-chain-independent")
        try
        {
            Files.writeString(tmpDir.resolve("notes.txt"), "project: alpha\nstatus: active")
            Files.writeString(tmpDir.resolve("log.txt"), "alpha started\nalpha completed")

            val scratchpad = SessionScratchpad()
            val ctx        = mkCtx(tmpDir.toString, scratchpad)
            val (shell, _) = mkShell()

            // Read file → path-keyed ref
            val readResp = shell.execute("files_read path=notes.txt", ctx)
            assert(readResp.text.contains("ok"))

            // Search → inline page, no persisted entry (only oversized pages overflow)
            val searchResp = shell.execute("files_search query=alpha", ctx)
            assert(searchResp.text.contains("ok"))
            assert(searchResp.text.contains("For more entries") || searchResp.text.contains("lines"),
                "search result must render unified hits")

            assert(scratchpad.size == 1, s"expected only the read entry, got ${scratchpad.size}")

            val readEntry = scratchpad.get("$scratch/notes.txt")
            assert(readEntry.isDefined, "path-keyed ref must exist for notes.txt")
            assert(readEntry.get.content.contains("project: alpha"),
                "file content must be stored in the path-keyed entry")
            assert(scratchpad.get("$scratch/__result_1__").isEmpty,
                "routine search pages must not create counter-keyed entries")
        }
        finally
        {
            deleteTmpDir(tmpDir)
        }
    }

    // ── 3. Stale cache: re-reading a modified file replaces the scratchpad entry ──

    test("files_read re-reads and updates scratchpad when file is modified") {
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

    test("files_read returns cached entry without re-reading when file is unchanged") {
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

        // Touching file1 refreshes its recency under true-LRU eviction.
        assert(scratchpad.get("$scratch/file1.txt").isDefined,
            "oldest entry must be present before the 21st store")

        // Store the 21st entry — the least-recently-used entry (file2) is evicted,
        // not file1, because file1 was just touched.
        scratchpad.store("file21.txt", ScratchEntry("content 21", 10L, 1, "file21.txt", now, now))

        assert(scratchpad.size == 20, "size must remain at 20 after eviction")
        assert(scratchpad.get("$scratch/file1.txt").isDefined,
            "a recently used entry must survive eviction")
        assert(scratchpad.get("$scratch/file2.txt").isEmpty,
            "the least-recently-used entry must be evicted")
        assert(scratchpad.get("$scratch/file21.txt").isDefined,
            "newly stored entry must be present after eviction")
        assert(scratchpad.get("$scratch/file20.txt").isDefined,
            "untouched entries must survive eviction")
    }

    // ── 6. Empty search results still produce a scratchpad entry ──

    test("FilesSearch reports an empty result set without persisting it") {
        val tmpDir = Files.createTempDirectory("agentica-chain-empty-search")
        try
        {
            Files.writeString(tmpDir.resolve("doc.txt"), "nothing relevant here")

            val scratchpad = SessionScratchpad()
            val ctx        = mkCtx(tmpDir.toString, scratchpad)

            val input = FilesSearchInput(
                query    = "XYZZY_NOMATCH",
                rawPath  = None,
                scope    = "workspace",
                ref      = None,
                limit    = 8,
                cursor   = 0,
                mode     = "hybrid",
                useRegex = false,
                recursive = true,
                include = None
            )
            val output = FilesSearch.execute(input, ctx)
            val result = FilesSearch.render(output, ctx)

            assert(result.status == ToolStatus.Ok,
                "zero-match search must still return Ok status")
            assert(result.metadata.get("Displaying").contains("0 of 0"),
                "zero-match search must report an empty page")
            assert(!result.metadata.contains("stored"),
                "routine pages carry no stored: ref")
            assert(scratchpad.size == 0,
                "routine pages are not persisted in the scratchpad")
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

            // No files_read has run — scratchpad is empty.
            // Using a ref that was never stored.
            val resp = shell.execute("files_stat path=$scratch/ghost.txt", ctx)

            // Must not throw; must produce a structured error response.
            assert(resp.text.nonEmpty, "response must not be empty")
            assert(resp.text.startsWith("$ files_stat"),
                "response must start with command echo line")
            // The ref was replaced by its string value (either the raw ref or the resolved content),
            // so the path passed to files_stat will not be a valid relative path → error response.
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
