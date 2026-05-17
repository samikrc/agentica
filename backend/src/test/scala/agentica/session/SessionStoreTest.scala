package agentica.session

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.BeforeAndAfterEach
import java.sql.{Connection, DriverManager}
import scala.compiletime.uninitialized

/**
 *  Unit tests for [[SessionStore]].
 *  Uses an in-memory SQLite database (created fresh per test) to verify
 *  persistence, CRUD operations, and deletion.
 */
class SessionStoreTest extends AnyFunSuite with BeforeAndAfterEach
{

    private var store: SessionStore = uninitialized

    /**
     *  Opens a fresh in-memory SQLite database before each test and initialises
     *  the schema.  The store receives a connection factory that returns a
     *  non-closing proxy so the in-memory database survives between store calls.
     */
    override def beforeEach(): Unit =
    {
        Class.forName("org.sqlite.JDBC")
        val realConn = DriverManager.getConnection("jdbc:sqlite::memory:")
        // Initialise the sessions table schema.
        val st = realConn.createStatement()
        st.execute("""
            CREATE TABLE sessions (
                id         TEXT PRIMARY KEY,
                title      TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL DEFAULT '',
                updated_at TEXT NOT NULL DEFAULT '',
                model      TEXT NOT NULL DEFAULT '',
                root_path  TEXT
            )
        """)
        st.close()
        // Proxy that ignores close() calls so the in-memory DB stays alive
        // for the duration of the test across multiple store method calls.
        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            realConn.getClass.getClassLoader,
            Array(classOf[Connection]),
            (_, method, args) =>
                if method.getName == "close" then null
                else if args == null then method.invoke(realConn)
                else method.invoke(realConn, args*)
        ).asInstanceOf[Connection]
        store = SessionStore(() => proxy)
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    test("list returns empty list when no sessions exist") {
        assert(store.list() == Nil)
    }

    test("create and retrieve a session") {
        val session = store.create("Test Session", "test-model", Some("/tmp/workspace"))
        val results = store.list()
        assert(results.size == 1)
        assert(results.head.id == session.id)
        assert(results.head.title == "Test Session")
        assert(results.head.rootPath == Some("/tmp/workspace"))
    }

    test("get returns None for non-existent session") {
        assert(store.get("nonexistent").isEmpty)
    }

    test("get returns the session when it exists") {
        val session = store.create("Test", "test-model", None)
        val retrieved = store.get(session.id)
        assert(retrieved.isDefined)
        assert(retrieved.get.id == session.id)
        assert(retrieved.get.title == "Test")
    }

    test("updateTitle modifies the session title") {
        val session = store.create("Original Title", "test-model", None)
        store.updateTitle(session.id, "New Title")
        val updated = store.get(session.id)
        assert(updated.isDefined)
        assert(updated.get.title == "New Title")
    }

    test("delete removes a session from the store") {
        val session1 = store.create("Session 1", "test-model", None)
        val session2 = store.create("Session 2", "test-model", None)
        assert(store.list().size == 2)

        store.delete(session1.id)
        assert(store.list().size == 1)
        assert(store.get(session1.id).isEmpty)
        assert(store.get(session2.id).isDefined)
    }

    test("delete is idempotent - deleting non-existent session does not error") {
        store.delete("nonexistent")
        assert(store.list().isEmpty)
    }
}
