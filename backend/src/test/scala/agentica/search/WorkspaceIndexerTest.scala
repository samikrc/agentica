package agentica.search

import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.nio.file.attribute.FileTime
import java.sql.{Connection, DriverManager}
import scala.compiletime.uninitialized

/**
 *  Unit tests for [[WorkspaceIndexer]] refresh-on-search behaviour.
 *
 *  Covers first-pass indexing, currency skipping, re-index on change,
 *  purge-on-delete, binary/skip-dir filtering, the supersede rule for
 *  converted siblings, and budget truncation.
 */
class WorkspaceIndexerTest extends AnyFunSuite with BeforeAndAfterEach
{

    private var index:   SearchIndexImpl = uninitialized
    private var realConn: Connection     = uninitialized
    private var ws:      Path            = uninitialized
    private var indexer: WorkspaceIndexer = uninitialized

    /**
     *  Opens a fresh in-memory index and a temp workspace before each test.
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
        index   = new SearchIndexImpl(() => proxy)
        index.init()
        indexer = new WorkspaceIndexer(index)
        ws      = Files.createTempDirectory("agentica-indexer-test")
    }

    /**
     *  Closes the connection and deletes the temp workspace after each test.
     */
    override def afterEach(): Unit =
    {
        realConn.close()
        if (Files.exists(ws))
            Files.walk(ws).sorted(java.util.Comparator.reverseOrder())
                .forEach(p => Files.deleteIfExists(p))
    }

    /** Writes a UTF-8 file under the workspace, creating parent directories. */
    private def write(rel: String, content: String): Path =
    {
        val path = ws.resolve(rel)
        Files.createDirectories(path.getParent)
        Files.writeString(path, content, StandardCharsets.UTF_8)
    }

    /** Workspace search scope for the temp workspace. */
    private def scope: IndexScope =
        IndexScope(IndexKind.Workspace, ws.toAbsolutePath.normalize().toString, None, None)

    test("first pass indexes supported text files") {
        write("a.md", "alpha revenue grew")
        write("reports/b.md", "beta costs fell")

        val stats = indexer.refresh(ws, None)

        assert(stats.indexed == 2)
        assert(!stats.partial)
        assert(index.countMatches(scope, "revenue") == 1)
        assert(index.search(scope, "costs", 5).head.sourcePath == "reports/b.md")
    }

    test("unchanged files are skipped on the next pass") {
        write("a.md", "alpha")
        indexer.refresh(ws, None)

        val stats = indexer.refresh(ws, None)

        assert(stats.indexed == 0)
        assert(stats.skipped == 1)
    }

    test("changed files are re-indexed") {
        val file = write("a.md", "alpha")
        indexer.refresh(ws, None)
        Files.writeString(file, "alpha beta gamma", StandardCharsets.UTF_8)
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 5000))

        val stats = indexer.refresh(ws, None)

        assert(stats.indexed == 1)
        assert(index.countMatches(scope, "gamma") == 1)
    }

    test("deleted files are purged") {
        val file = write("gone.md", "vanishing token")
        indexer.refresh(ws, None)
        assert(index.countMatches(scope, "vanishing") == 1)

        Files.delete(file)
        val stats = indexer.refresh(ws, None)

        assert(stats.deleted == 1)
        assert(index.countMatches(scope, "vanishing") == 0)
    }

    test("binary files, dotfiles, and skipped directories are ignored") {
        write("real.md", "kept token")
        write(".hidden.md", "hidden token")
        write("node_modules/pkg.md", "vendored token")
        val bin = ws.resolve("blob.md")
        Files.write(bin, Array[Byte](0, 1, 2, 3))

        val stats = indexer.refresh(ws, None)

        assert(stats.indexed == 1)
        assert(index.countMatches(scope, "kept") == 1)
        assert(index.countMatches(scope, "hidden") == 0)
        assert(index.countMatches(scope, "vendored") == 0)
    }

    test("converted sibling supersedes the raw document") {
        val pdfSrc = Paths.get(getClass.getResource("/files/IT Support Analyst - India.pdf").toURI)
        Files.copy(pdfSrc, ws.resolve("doc.pdf"))
        // Write the converted Markdown afterwards so it is at least as new as the PDF.
        write("doc.md", "converted revenue commentary")

        indexer.refresh(ws, None)

        val rootId = ws.toAbsolutePath.normalize().toString
        val pdfKey = SourceKey(IndexKind.Workspace, rootId, None, None, "doc.pdf")

        assert(index.resolveAlias(pdfKey).contains("doc.md"))
        assert(index.countMatches(scope, "converted") == 1)
        // The PDF text layer must not contribute chunks once superseded.
        assert(index.meta(pdfKey).exists(_.chunkCount == 0))
    }

    test("budget exhaustion reports pending work as partial") {
        write("a.md", "alpha")
        write("b.md", "beta")

        val stats = indexer.refresh(ws, None, budgetMs = -1L)

        assert(stats.partial)
        assert(stats.pending == 2)
        assert(stats.indexed == 0)
    }

    test("scoped refresh only touches the subtree") {
        write("keep/a.md", "alpha")
        write("other/b.md", "beta")
        indexer.refresh(ws, None)

        val stats = indexer.refresh(ws, Some(ws.resolve("keep")))

        assert(stats.indexed == 0)
        assert(stats.deleted == 0, "rows outside the scope must not be purged")
        assert(index.countMatches(scope, "beta") == 1)
    }

    // ── Review regression tests ──────────────────────────────────────────────

    test("deleting the converted sibling re-indexes the raw document and clears the alias") {
        val pdfSrc = Paths.get(getClass.getResource("/files/IT Support Analyst - India.pdf").toURI)
        Files.copy(pdfSrc, ws.resolve("doc.pdf"))
        write("doc.md", "converted commentary")
        indexer.refresh(ws, None)

        val rootId = ws.toAbsolutePath.normalize().toString
        val pdfKey = SourceKey(IndexKind.Workspace, rootId, None, None, "doc.pdf")
        assert(index.resolveAlias(pdfKey).contains("doc.md"), "control: alias recorded")

        Files.delete(ws.resolve("doc.md"))
        indexer.refresh(ws, None)

        val meta = index.meta(pdfKey)
        assert(meta.exists(_.replacedBy.isEmpty),
            "the stale alias must be cleared once the sibling is gone")
        assert(meta.exists(_.chunkCount > 0),
            "the PDF text layer must be re-indexed once the sibling is gone")
    }

    test("unindexable files are marked once and not retried") {
        val target = ws.resolve("bad.md")
        Files.write(target, Array[Byte](0, 1, 2, 0, 3))

        val first = indexer.refresh(ws, None)
        val rootId = ws.toAbsolutePath.normalize().toString
        val key    = SourceKey(IndexKind.Workspace, rootId, None, None, "bad.md")
        val marked = index.meta(key)

        assert(marked.exists(_.chunkCount == 0),
            "unindexable files must receive a zero-chunk marker row")
        val indexedAt = marked.get.indexedAt

        val second = indexer.refresh(ws, None)
        assert(index.meta(key).get.indexedAt == indexedAt,
            "an unchanged unindexable file must not be re-extracted on later passes")
        assert(second.pending == 0 && !second.partial,
            "marker rows must count as completed work, not pending")
    }

    test("unknown and extensionless text files are indexed") {
        write("build.rs", "fn main() { println!(\"needle rust\"); }")
        write("Dockerfile", "RUN echo needle docker")

        indexer.refresh(ws, None)

        assert(index.countMatches(scope, "needle") == 2,
            "unknown-extension and extensionless text files must be indexed")
    }

    test("pending counts only files that still need work") {
        write("a.md", "alpha")
        write("b.md", "beta")
        indexer.refresh(ws, None)
        write("c.md", "gamma")

        val stats = indexer.refresh(ws, None, budgetMs = -1L)

        assert(stats.pending == 1,
            s"only the never-indexed file may count as pending, got ${stats.pending}")
    }
}

