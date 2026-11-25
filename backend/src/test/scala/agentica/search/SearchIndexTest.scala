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
 *  session/run isolation.
 */
class SearchIndexTest extends AnyFunSuite with BeforeAndAfterEach
{

    private var index: SearchIndexImpl = uninitialized
    private var realConn: Connection   = uninitialized

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

    /** Indexes a workspace file with the given text and timestamps. */
    private def indexWorkspace(path: String, text: String, mtime: Long = 1L, size: Long = 100L): Unit =
        index.index(IndexKind.Workspace, None, None, path, text, mtime, size)

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

        val hits = index.search(IndexKind.Workspace, None, None, "revenue", 10)

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

        assert(index.isCurrent(IndexKind.Workspace, None, None, "a.md", 10L, 50L))
        assert(!index.isCurrent(IndexKind.Workspace, None, None, "a.md", 11L, 50L))
        assert(!index.isCurrent(IndexKind.Workspace, None, None, "a.md", 10L, 51L))
        assert(!index.isCurrent(IndexKind.Workspace, None, None, "missing.md", 10L, 50L))
    }

    test("re-indexing replaces chunks rather than duplicating them") {
        indexWorkspace("a.md", "alpha beta gamma")
        val before = index.countMatches(IndexKind.Workspace, None, None, "alpha")

        indexWorkspace("a.md", "alpha beta gamma", mtime = 2L)
        val after = index.countMatches(IndexKind.Workspace, None, None, "alpha")

        assert(before == 1)
        assert(after == 1, "re-index must replace, not append")
        assert(index.meta(IndexKind.Workspace, None, None, "a.md").exists(_.lastModified == 2L))
    }

    test("stored rows are isolated by session and run") {
        index.index(IndexKind.Stored, Some("s1"), None, "data/report.md", "shared keyword here", 1L, 10L)
        index.index(IndexKind.Stored, Some("s2"), None, "data/report.md", "shared keyword here", 1L, 10L)
        index.index(IndexKind.Stored, Some("s1"), Some("run-1"), "__result_1__", "computed keyword", 1L, 10L)

        assert(index.countMatches(IndexKind.Stored, Some("s1"), None, "keyword") == 1)
        assert(index.countMatches(IndexKind.Stored, Some("s2"), None, "keyword") == 1)
        assert(index.countMatches(IndexKind.Stored, Some("s1"), Some("run-1"), "keyword") == 1)
        assert(index.countMatches(IndexKind.Stored, Some("s1"), None, "computed") == 0)
        assert(index.countMatches(IndexKind.Workspace, None, None, "keyword") == 0)
    }

    test("deleteSource removes chunks and metadata") {
        indexWorkspace("a.md", "alpha beta")
        index.deleteSource(IndexKind.Workspace, None, None, "a.md")

        assert(index.countMatches(IndexKind.Workspace, None, None, "alpha") == 0)
        assert(index.meta(IndexKind.Workspace, None, None, "a.md").isEmpty)
    }

    test("deleteSession purges only that session's stored rows") {
        index.index(IndexKind.Stored, Some("s1"), None, "a.md", "alpha", 1L, 10L)
        index.index(IndexKind.Stored, Some("s2"), None, "a.md", "alpha", 1L, 10L)
        indexWorkspace("w.md", "alpha")

        index.deleteSession("s1")

        assert(index.countMatches(IndexKind.Stored, Some("s1"), None, "alpha") == 0)
        assert(index.countMatches(IndexKind.Stored, Some("s2"), None, "alpha") == 1)
        assert(index.countMatches(IndexKind.Workspace, None, None, "alpha") == 1)
    }

    test("deleteRun purges only that run's computed rows") {
        index.index(IndexKind.Stored, Some("s1"), Some("run-1"), "__result_1__", "alpha", 1L, 10L)
        index.index(IndexKind.Stored, Some("s1"), Some("run-2"), "__result_1__", "alpha", 1L, 10L)
        index.index(IndexKind.Stored, Some("s1"), None, "file.md", "alpha", 1L, 10L)

        index.deleteRun("run-1")

        assert(index.countMatches(IndexKind.Stored, Some("s1"), Some("run-1"), "alpha") == 0)
        assert(index.countMatches(IndexKind.Stored, Some("s1"), Some("run-2"), "alpha") == 1)
        assert(index.countMatches(IndexKind.Stored, Some("s1"), None, "alpha") == 1)
    }

    test("listSources orders stale-first by indexed_at") {
        indexWorkspace("a.md", "alpha")
        indexWorkspace("b.md", "beta")
        indexWorkspace("c.md", "gamma")

        val order = index.listSources(IndexKind.Workspace, None, None).map(_.sourcePath)
        assert(order == List("a.md", "b.md", "c.md"))
    }

    test("replaced_by alias round-trips") {
        indexWorkspace("q3.pdf", "text layer")
        indexWorkspace("q3.md", "converted")

        index.setReplacedBy(IndexKind.Workspace, None, None, "q3.pdf", Some("q3.md"))
        assert(index.resolveAlias(IndexKind.Workspace, None, None, "q3.pdf").contains("q3.md"))
        assert(index.resolveAlias(IndexKind.Workspace, None, None, "q3.md").isEmpty)

        index.setReplacedBy(IndexKind.Workspace, None, None, "q3.pdf", None)
        assert(index.resolveAlias(IndexKind.Workspace, None, None, "q3.pdf").isEmpty)
    }

    test("empty text indexes metadata with zero chunks") {
        indexWorkspace("empty.md", "", mtime = 5L, size = 0L)

        val meta = index.meta(IndexKind.Workspace, None, None, "empty.md")
        assert(meta.isDefined)
        assert(meta.get.chunkCount == 0)
        assert(index.countMatches(IndexKind.Workspace, None, None, "anything") == 0)
    }

    test("long documents produce multiple searchable chunks") {
        val text = (1 to 200).map(i => s"line $i mentions token$i").mkString("\n")
        indexWorkspace("big.md", text)

        val meta = index.meta(IndexKind.Workspace, None, None, "big.md").get
        assert(meta.chunkCount > 1)
        assert(meta.lineCount == 200)
        assert(index.search(IndexKind.Workspace, None, None, "token150", 5).nonEmpty)
    }
}
