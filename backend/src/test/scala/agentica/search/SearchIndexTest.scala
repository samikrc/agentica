package agentica.search

import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import java.sql.{Connection, DriverManager}
import scala.compiletime.uninitialized

/**
 *  Unit tests for [[SearchIndexImpl]] over an in-memory SQLite database.
 *
 *  Covers the FTS5 probe, index/search ranking, `(mtime, size)` currency
 *  checks, replacement on re-index, alias resolution, scoped deletes, and
 *  root/session/run isolation.
 */
class SearchIndexTest extends AnyFunSuite with BeforeAndAfterEach
{

    private var index: SearchIndexImpl = uninitialized
    private var realConn: Connection   = uninitialized

    private val root = "/workspace"

    /**
     *  Opens a fresh in-memory SQLite database before each test, initialises
     *  the index, and hands the store a non-closing connection proxy so the
     *  database survives across store calls.
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
    }

    /**
     *  Closes the underlying in-memory connection after each test.
     */
    override def afterEach(): Unit =
    {
        realConn.close()
    }

    /** Workspace source key for `path` under the default root. */
    private def wsKey(path: String): SourceKey =
        SourceKey(IndexKind.Workspace, root, None, None, path)

    /** Workspace search scope for the default root. */
    private def wsScope: IndexScope =
        IndexScope(IndexKind.Workspace, root, None, None)

    /** Stored source key for a file-backed entry. */
    private def storedKey(sessionId: String, path: String, runId: Option[String] = None): SourceKey =
        SourceKey(IndexKind.Stored, "", Some(sessionId), runId, path)

    /** Stored search scope for one session. */
    private def storedScope(sessionId: String, runId: Option[String] = None): IndexScope =
        IndexScope(IndexKind.Stored, "", Some(sessionId), runId)

    /** Indexes a workspace file with the given text and timestamps. */
    private def indexWorkspace(path: String, text: String, mtime: Long = 1L, size: Long = 100L): Unit =
        index.index(wsKey(path), text, mtime, size)

    test("init enables the ranked arm (FTS5 available)") {
        assert(index.isAvailable)
    }

    test("search returns ranked hits with positive scores and line bounds") {
        indexWorkspace("reports/q3.md",
            "Revenue grew 12% in Q3.\n" +
            "APAC revenue decelerated sharply as growth slowed.\n" +
            "Costs were flat.")
        indexWorkspace("reports/q2.md",
            "Revenue was steady.\n" +
            "No regional detail was provided.")

        val hits = index.search(wsScope, "revenue", 10)

        assert(hits.nonEmpty)
        assert(hits.forall(_.score > 0))
        assert(hits.forall(_.sourcePath.startsWith("reports/")))
        assert(hits.head.snippet.toLowerCase.contains("revenue"))
        // Best-first ordering.
        assert(hits.map(_.score) == hits.map(_.score).sorted.reverse)
        assert(hits.forall(h => h.startLine >= 1 && h.endLine >= h.startLine))
    }

    test("isCurrent tracks (mtime, size) and rejects changes") {
        indexWorkspace("a.md", "alpha beta", mtime = 10L, size = 50L)

        assert(index.isCurrent(wsKey("a.md"), 10L, 50L))
        assert(!index.isCurrent(wsKey("a.md"), 11L, 50L))
        assert(!index.isCurrent(wsKey("a.md"), 10L, 51L))
        assert(!index.isCurrent(wsKey("missing.md"), 10L, 50L))
    }

    test("re-indexing replaces chunks rather than duplicating them") {
        indexWorkspace("a.md", "alpha beta gamma")
        val before = index.countMatches(wsScope, "alpha")

        indexWorkspace("a.md", "alpha beta gamma", mtime = 2L)
        val after = index.countMatches(wsScope, "alpha")

        assert(before == 1)
        assert(after == 1, "re-index must replace, not append")
        assert(index.meta(wsKey("a.md")).exists(_.lastModified == 2L))
    }

    test("workspace rows are isolated by root") {
        index.index(SourceKey(IndexKind.Workspace, "/ws-a", None, None, "doc.md"), "shared keyword", 1L, 10L)
        index.index(SourceKey(IndexKind.Workspace, "/ws-b", None, None, "doc.md"), "shared keyword", 1L, 10L)

        assert(index.countMatches(IndexScope(IndexKind.Workspace, "/ws-a", None, None), "keyword") == 1)
        assert(index.countMatches(IndexScope(IndexKind.Workspace, "/ws-b", None, None), "keyword") == 1)
        assert(index.countMatches(wsScope, "keyword") == 0)
    }

    test("stored rows are isolated by session and run") {
        index.index(storedKey("s1", "data/report.md"), "shared keyword here", 1L, 10L)
        index.index(storedKey("s2", "data/report.md"), "shared keyword here", 1L, 10L)
        index.index(storedKey("s1", "__result_1__", Some("run-1")), "computed keyword", 1L, 10L)

        assert(index.countMatches(storedScope("s1"), "keyword") == 1)
        assert(index.countMatches(storedScope("s2"), "keyword") == 1)
        assert(index.countMatches(storedScope("s1", Some("run-1")), "keyword") == 1)
        assert(index.countMatches(storedScope("s1"), "computed") == 0)
        assert(index.countMatches(wsScope, "keyword") == 0)
    }

    test("deleteSource removes chunks and metadata") {
        indexWorkspace("a.md", "alpha beta")
        index.deleteSource(wsKey("a.md"))

        assert(index.countMatches(wsScope, "alpha") == 0)
        assert(index.meta(wsKey("a.md")).isEmpty)
    }

    test("deleteSession purges only that session's stored rows") {
        index.index(storedKey("s1", "a.md"), "alpha", 1L, 10L)
        index.index(storedKey("s2", "a.md"), "alpha", 1L, 10L)
        indexWorkspace("w.md", "alpha")

        index.deleteSession("s1")

        assert(index.countMatches(storedScope("s1"), "alpha") == 0)
        assert(index.countMatches(storedScope("s2"), "alpha") == 1)
        assert(index.countMatches(wsScope, "alpha") == 1)
    }

    test("deleteRun purges only that run's computed rows") {
        index.index(storedKey("s1", "__result_1__", Some("run-1")), "alpha", 1L, 10L)
        index.index(storedKey("s1", "__result_1__", Some("run-2")), "alpha", 1L, 10L)
        index.index(storedKey("s1", "file.md"), "alpha", 1L, 10L)

        index.deleteRun("run-1")

        assert(index.countMatches(storedScope("s1", Some("run-1")), "alpha") == 0)
        assert(index.countMatches(storedScope("s1", Some("run-2")), "alpha") == 1)
        assert(index.countMatches(storedScope("s1"), "alpha") == 1)
    }

    test("listSources orders stale-first by indexed_at") {
        indexWorkspace("a.md", "alpha")
        indexWorkspace("b.md", "beta")
        indexWorkspace("c.md", "gamma")

        val order = index.listSources(wsScope).map(_.key.sourcePath)
        assert(order == List("a.md", "b.md", "c.md"))
    }

    test("replaced_by alias round-trips") {
        indexWorkspace("q3.pdf", "text layer")
        indexWorkspace("q3.md", "converted")

        index.setReplacedBy(wsKey("q3.pdf"), Some("q3.md"))
        assert(index.resolveAlias(wsKey("q3.pdf")).contains("q3.md"))
        assert(index.resolveAlias(wsKey("q3.md")).isEmpty)

        index.setReplacedBy(wsKey("q3.pdf"), None)
        assert(index.resolveAlias(wsKey("q3.pdf")).isEmpty)
    }

    test("empty text indexes metadata with zero chunks") {
        indexWorkspace("empty.md", "", mtime = 5L, size = 0L)

        val meta = index.meta(wsKey("empty.md"))
        assert(meta.isDefined)
        assert(meta.get.chunkCount == 0)
        assert(index.countMatches(wsScope, "anything") == 0)
    }

    test("long documents produce multiple searchable chunks") {
        val text = (1 to 200).map(i => s"line $i mentions token$i").mkString("\n")
        indexWorkspace("big.md", text)

        val meta = index.meta(wsKey("big.md")).get
        assert(meta.chunkCount > 1)
        assert(meta.lineCount == 200)
        assert(index.search(wsScope, "token150", 5).nonEmpty)
    }
}
