package agentica.shell

import agentica.search.{SearchIndexImpl, SourceKey}
import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite
import java.sql.{Connection, DriverManager}
import scala.compiletime.uninitialized

/**
 *  Unit tests for [[SessionScratchpad]] capacity and indexing behaviour.
 *
 *  Covers the eviction order when re-storing an existing key at capacity and
 *  the currency check that avoids rewriting index rows for unchanged
 *  file-backed entries.
 */
class ScratchpadTest extends AnyFunSuite with BeforeAndAfterEach
{

    private var realConn: Connection = uninitialized

    /**
     *  Opens a fresh in-memory database before each test.
     */
    override def beforeEach(): Unit =
    {
        Class.forName("org.sqlite.JDBC")
        realConn = DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    /**
     *  Closes the database connection after each test.
     */
    override def afterEach(): Unit =
        realConn.close()

    /**
     *  Counting wrapper around [[SearchIndexImpl]] that records `index` calls.
     *  @param conn  Connection factory passed through to the real implementation.
     */
    private class SpyIndex(conn: () => Connection) extends SearchIndexImpl(conn)
    {
        /** Number of `index` invocations received. */
        var indexCalls: Int = 0

        override def index(key: SourceKey, text: String, lastModified: Long, sizeBytes: Long): Unit =
        {
            indexCalls += 1
            super.index(key, text, lastModified, sizeBytes)
        }
    }

    /** Close-suppressing connection proxy over the shared in-memory database. */
    private def proxyConn: Connection =
        java.lang.reflect.Proxy.newProxyInstance(
            realConn.getClass.getClassLoader,
            Array(classOf[Connection]),
            (_, method, args) =>
                if (method.getName == "close") null
                else if (args == null) method.invoke(realConn)
                else method.invoke(realConn, args*)
        ).asInstanceOf[Connection]

    /** Builds a small entry with the given content and timestamp. */
    private def entry(content: String, key: String, mtime: Long): ScratchEntry =
        ScratchEntry(
            content      = content,
            sizeBytes    = content.length.toLong,
            lineCount    = 1,
            sourcePath   = key,
            lastModified = mtime,
            storedAt     = System.currentTimeMillis()
        )

    test("re-storing an existing key at capacity does not evict an unrelated entry") {
        val pad = SessionScratchpad()
        for (i <- 1 to 20) pad.store(s"file$i.md", entry(s"content $i", s"file$i.md", i))
        assert(pad.size == 20)

        pad.store("file5.md", entry("content 5 updated", "file5.md", 5))

        assert(pad.size == 20,
            s"re-storing a present key must not shrink the pad, got ${pad.size}")
        assert(pad.get("$scratch/file1.md").isDefined,
            "the oldest unrelated entry must survive a re-store")
        assert(pad.get("$scratch/file5.md").exists(_.content == "content 5 updated"),
            "the re-stored content must replace the previous entry")
    }

    test("re-storing an unchanged file-backed entry does not rewrite its index rows") {
        val spy = new SpyIndex(() => proxyConn)
        spy.init()
        val pad  = SessionScratchpad(Some(spy), Some("s1"), Some("run-1"))
        val time = 123456L

        pad.store("data/a.md", entry("same content", "data/a.md", time))
        assert(spy.indexCalls == 1)

        pad.store("data/a.md", entry("same content", "data/a.md", time))
        assert(spy.indexCalls == 1,
            "an unchanged file-backed re-store must reuse the persisted rows")

        pad.store("data/a.md", entry("same content", "data/a.md", time + 1000))
        assert(spy.indexCalls == 2,
            "a changed file-backed re-store must re-index (control)")
    }
}
