package agentica.tools.files

import agentica.permissions.{GrantDecision, PermissionCoordinator, ScopeStore}
import agentica.search.{SearchIndex, SearchIndexDisabled, SearchIndexImpl}
import agentica.session.{MemoryStore, Session}
import agentica.shell.{CommandRegistry, SessionScratchpad, VirtualShell}
import agentica.tools.{ExecutionContext, ToolBody}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.sql.{Connection, DriverManager}
import scala.compiletime.uninitialized

/**
 *  Tests for the hybrid retrieval contract of [[FilesSearch]].
 *
 *  Covers the scope/ref/path validation matrix, fusion origins, AND-to-OR
 *  fallback, mode overrides, stored-scope search and drill-down, budget
 *  paging metadata, degradation without an index, the `resolveRefs` guard for
 *  `ref`/`path`, and ref re-hydration from disk.
 */
class FilesSearchHybridTest extends AnyFunSuite with BeforeAndAfterEach
{

    private var index:    SearchIndexImpl = uninitialized
    private var realConn: Connection      = uninitialized
    private var ws:       Path            = uninitialized

    private val noGrants: ScopeStore = new ScopeStore
    {
        def hasGrant(sessionId: String, toolName: String, resolvedPath: String): Boolean = false
        def addGrant(sessionId: String, toolName: String, decision: GrantDecision.Granted): Unit = ()
        def consumeOnce(sessionId: String, toolName: String, resolvedPath: String): Unit = ()
        def deleteForSession(sessionId: String): Unit = ()
    }

    /**
     *  Creates a fresh in-memory index and temp workspace before each test.
     */
    override def beforeEach(): Unit =
    {
        Class.forName("org.sqlite.JDBC")
        realConn = DriverManager.getConnection("jdbc:sqlite::memory:")
        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            realConn.getClass.getClassLoader,
            Array(classOf[Connection]),
            (_, method, args) =>
                if (method.getName == "close") null
                else if (args == null) method.invoke(realConn)
                else method.invoke(realConn, args*)
        ).asInstanceOf[Connection]
        index = new SearchIndexImpl(() => proxy)
        index.init()
        ws = Files.createTempDirectory("agentica-hybrid-search-test")
    }

    /**
     *  Closes the index connection and deletes the temp workspace.
     */
    override def afterEach(): Unit =
    {
        realConn.close()
        if (Files.exists(ws))
            Files.walk(ws).sorted(java.util.Comparator.reverseOrder())
                .forEach(p => Files.deleteIfExists(p))
    }

    /** Writes a UTF-8 file under the workspace. */
    private def write(rel: String, content: String): Path =
    {
        val path = ws.resolve(rel)
        Files.createDirectories(path.getParent)
        Files.writeString(path, content, StandardCharsets.UTF_8)
    }

    /**
     *  Builds an ExecutionContext rooted at the temp workspace.
     *  @param pad    Scratchpad to use.
     *  @param search Index to expose; defaults to the per-test index.
     */
    private def mkCtx(pad: SessionScratchpad, search: SearchIndex = index): ExecutionContext =
        ExecutionContext(
            session         = Session("s1", "Test", "", "", "test-model", Some(ws.toString)),
            traceId         = "hybrid-test-run",
            scopeStore      = noGrants,
            scratchpad      = pad,
            memoryStore     = null.asInstanceOf[MemoryStore],
            llmProvider     = null,
            vlmProvider     = None,
            onEvent         = _ => (),
            permissionCoordinator = new PermissionCoordinator("hybrid-run"),
            debugMode       = false,
            searchIndex     = search
        )

    /** Pad wired to the per-test index for the current session and run. */
    private def indexedPad: SessionScratchpad =
        SessionScratchpad(Some(index), Some("s1"), Some("hybrid-test-run"))

    /** Builds validated input via the tool's own validation. */
    private def input(args: (String, String)*): FilesSearchInput =
    {
        FilesSearch.validate(args.toMap) match
        {
            case Right(i)  => i
            case Left(err) => fail(s"validation failed: ${err.message}")
        }
    }

    // ── Validation matrix ────────────────────────────────────────────────────

    test("validation rejects missing query, bad scope/mode, and ambiguous combinations") {
        assert(FilesSearch.validate(Map.empty).isLeft)
        assert(FilesSearch.validate(Map("query" -> "x", "scope" -> "elsewhere")).isLeft)
        assert(FilesSearch.validate(Map("query" -> "x", "mode" -> "fuzzy")).isLeft)
        assert(FilesSearch.validate(Map("query" -> "x", "scope" -> "workspace",
            "ref" -> "$scratch/a")).isLeft,
            "an explicit workspace scope with ref must be rejected")
        assert(FilesSearch.validate(Map("query" -> "x", "path" -> "$scratch/a")).isLeft,
            "a $scratch path in workspace scope must be rejected")
        assert(FilesSearch.validate(Map("query" -> "x", "scope" -> "stored", "path" -> "a")).isLeft,
            "path is invalid with stored scope")
    }

    test("validation infers stored scope from ref") {
        val i = input("query" -> "x", "ref" -> "$scratch/__result_1__")
        assert(i.scope == "stored")
        assert(i.ref.contains("$scratch/__result_1__"))
    }

    // ── Workspace hybrid ─────────────────────────────────────────────────────

    test("a passage matched by both arms is reported as exact+ranked") {
        write("reports/q3.md", "Revenue grew 12% in Q3.\nAPAC revenue decelerated as growth slowed.\nCosts flat.")
        val ctx = mkCtx(SessionScratchpad())

        val out = FilesSearch.execute(input("query" -> "revenue", "path" -> "reports/"), ctx)

        assert(out.hits.nonEmpty)
        assert(out.hits.exists(_.origin == "exact+ranked"),
            s"expected a fused hit, got ${out.hits.map(_.origin)}")
    }

    test("AND-first falls back to OR when the precise query is too sparse") {
        write("a.md", "alpha beta gamma")
        val ctx = mkCtx(SessionScratchpad())

        val out = FilesSearch.execute(input("query" -> "alpha zzznomatch"), ctx)

        assert(out.hits.nonEmpty, "OR fallback must still find the alpha passage")
    }

    test("mode overrides select a single arm") {
        write("a.md", "alpha beta")
        val ctx = mkCtx(SessionScratchpad())

        val exact  = FilesSearch.execute(input("query" -> "alpha", "mode" -> "exact"), ctx)
        val ranked = FilesSearch.execute(input("query" -> "alpha", "mode" -> "ranked"), ctx)

        assert(exact.hits.nonEmpty)
        assert(exact.hits.forall(_.origin == "exact"))
        assert(ranked.hits.nonEmpty)
        assert(ranked.hits.forall(_.origin == "ranked"))
    }

    test("degradation: without an index only the exact arm runs") {
        write("a.md", "alpha beta")
        val ctx = mkCtx(SessionScratchpad(), SearchIndexDisabled)

        val out = FilesSearch.execute(input("query" -> "alpha"), ctx)

        assert(out.hits.nonEmpty)
        assert(out.hits.forall(_.origin == "exact"))
    }

    test("budget paging reports Displaying and a continuation command") {
        for (i <- 1 to 5) write(s"file$i.md", s"needle in file $i")
        val ctx = mkCtx(SessionScratchpad())

        val out    = FilesSearch.execute(input("query" -> "needle", "limit" -> "2"), ctx)
        val result = FilesSearch.render(out, ctx)

        assert(out.hits.size == 2)
        assert(result.metadata.get("Displaying").contains("2 of 5"))
        assert(out.moreCommand.exists(_.contains("cursor=2")))
        val body = result.body.get.asInstanceOf[ToolBody.Inline].text
        assert(body.contains("For more entries, use: files_search"))
    }

    test("recursive=false restricts hits to the immediate directory") {
        write("top.md", "needle in top")
        write("sub/nested.md", "needle in nested")
        val ctx = mkCtx(SessionScratchpad())

        val out = FilesSearch.execute(input("query" -> "needle", "recursive" -> "false"), ctx)

        assert(out.hits.nonEmpty)
        assert(out.hits.forall(h => !h.sourcePath.contains("/")),
            s"nested hits must be filtered, got ${out.hits.map(_.sourcePath)}")
    }

    test("include filters hits by file name glob") {
        write("a.md", "needle in markdown")
        write("b.txt", "needle in text")
        val ctx = mkCtx(SessionScratchpad())

        val out = FilesSearch.execute(input("query" -> "needle", "include" -> "*.md"), ctx)

        assert(out.hits.nonEmpty)
        assert(out.hits.forall(_.sourcePath.endsWith(".md")),
            s"non-matching files must be filtered, got ${out.hits.map(_.sourcePath)}")
    }

    // ── Stored scope ─────────────────────────────────────────────────────────

    test("stored scope searches this run's scratch entries and supports ref drill-down") {
        val pad = indexedPad
        val now = System.currentTimeMillis()
        pad.store("data/report.md", agentica.shell.ScratchEntry(
            content      = "APAC revenue commentary and regional notes",
            sizeBytes    = 45L,
            lineCount    = 1,
            sourcePath   = "data/report.md",
            lastModified = now,
            storedAt     = now
        ))
        val ctx = mkCtx(pad)

        val all = FilesSearch.execute(input("query" -> "APAC", "scope" -> "stored"), ctx)
        assert(all.hits.nonEmpty, "stored scope must find indexed scratch content")
        assert(all.hits.head.sourcePath.startsWith("$scratch/"))

        val drill = FilesSearch.execute(input("query" -> "APAC",
            "ref" -> "$scratch/data/report.md"), ctx)
        assert(drill.hits.nonEmpty)
        assert(drill.hits.forall(_.sourcePath == "$scratch/data/report.md"))
    }

    test("stored scope with an empty corpus reports zero results") {
        val ctx = mkCtx(SessionScratchpad())
        val out = FilesSearch.execute(input("query" -> "anything", "scope" -> "stored"), ctx)

        assert(out.hits.isEmpty)
        assert(out.total == 0)
    }

    // ── resolveRefs guard and hydration ──────────────────────────────────────

    test("resolveRefs keeps files_search ref arguments intact") {
        val pad = indexedPad
        val now = System.currentTimeMillis()
        pad.store("__result_1__", agentica.shell.ScratchEntry(
            content      = "alpha beta gamma",
            sizeBytes    = 16L,
            lineCount    = 1,
            sourcePath   = "__result_1__",
            lastModified = now,
            storedAt     = now
        ))
        val ctx = mkCtx(pad)

        val registry = CommandRegistry()
        registry.register(FilesSearch)
        val shell = VirtualShell(registry)

        val resp = shell.execute("files_search query=alpha ref=$scratch/__result_1__", ctx)

        assert(!resp.text.contains("error:"), s"ref must not be substituted: ${resp.text}")
        assert(resp.text.contains("$scratch/__result_1__"))
    }

    test("an expired file-backed ref is re-hydrated from disk") {
        write("doc.md", "hydrated content here")
        val pad = indexedPad
        val ctx = mkCtx(pad)
        assert(pad.get("$scratch/doc.md").isEmpty)

        val registry = CommandRegistry()
        registry.register(FilesStat)
        val shell = VirtualShell(registry)

        // files_stat receives the hydrated content as its path argument and fails,
        // but the hydration side effect repopulates the pad.
        shell.execute("files_stat path=$scratch/doc.md", ctx)

        val entry = pad.get("$scratch/doc.md")
        assert(entry.isDefined, "expired file-backed ref must be re-hydrated from disk")
        assert(entry.get.content.contains("hydrated content"))
    }
}
