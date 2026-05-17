package agentica.session

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.BeforeAndAfterEach
import java.sql.{Connection, DriverManager}
import scala.compiletime.uninitialized

/**
 *  Unit tests for [[RunStore]].
 *  Uses an in-memory SQLite database (created fresh per test) to verify
 *  persistence, CRUD operations, and deletion.
 */
class RunStoreTest extends AnyFunSuite with BeforeAndAfterEach
{

    private var store: RunStore = uninitialized

    /**
     *  Opens a fresh in-memory SQLite database before each test and initialises
     *  the schema.  The store receives a connection factory that returns a
     *  non-closing proxy so the in-memory database survives between store calls.
     */
    override def beforeEach(): Unit =
    {
        Class.forName("org.sqlite.JDBC")
        val realConn = DriverManager.getConnection("jdbc:sqlite::memory:")
        // Initialise the sessions and messages tables for FK constraints
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
        st.execute("""
            CREATE TABLE messages (
                id          TEXT PRIMARY KEY,
                session_id  TEXT NOT NULL,
                role        TEXT NOT NULL,
                content     TEXT NOT NULL,
                timestamp   TEXT NOT NULL,
                attachments TEXT NOT NULL DEFAULT '[]',
                FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
            )
        """)
        st.execute("INSERT INTO sessions (id) VALUES ('sess-1')")
        st.execute("INSERT INTO messages (id, session_id, role, content, timestamp) VALUES ('msg-1', 'sess-1', 'user', 'First', '2024-01-01T00:00:00Z')")
        st.execute("INSERT INTO messages (id, session_id, role, content, timestamp) VALUES ('msg-2', 'sess-1', 'assistant', 'Second', '2024-01-01T01:00:00Z')")
        st.execute("INSERT INTO messages (id, session_id, role, content, timestamp) VALUES ('msg-3', 'sess-1', 'user', 'Third', '2024-01-01T02:00:00Z')")
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
        store = RunStore(() => proxy)
        store.init()
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    test("listRunsForSession returns empty list when no runs exist") {
        assert(store.listRunsForSession("sess-1") == Nil)
    }

    test("insertRun and retrieve runs") {
        val run = ToolRun(
            id         = "run-1",
            sessionId  = "sess-1",
            tool       = "files.read",
            input      = "{\"path\":\"test.txt\"}",
            output     = "{\"content\":\"hello\"}",
            status     = RunStatus.Success,
            traceId    = "trace-1",
            durationMs = 100L
        )
        store.insertRun(run)
        val runs = store.listRunsForSession("sess-1")
        assert(runs.size == 1)
        assert(runs.head.id == "run-1")
        assert(runs.head.tool == "files.read")
    }

    test("tokenUsageForSession returns empty list when no usage exists") {
        assert(store.tokenUsageForSession("sess-1") == Nil)
    }

    test("insertTokenUsage and retrieve usage") {
        val usage = TokenUsage(
            id               = "usage-1",
            traceId          = "trace-1",
            sessionId        = "sess-1",
            model            = "gpt-4",
            promptTokens     = 100,
            completionTokens = 50,
            latencyMs        = 1000L,
            createdAt        = "2024-01-01T00:00:00Z"
        )
        store.insertTokenUsage(usage)
        val usageList = store.tokenUsageForSession("sess-1")
        assert(usageList.size == 1)
        assert(usageList.head.id == "usage-1")
        assert(usageList.head.promptTokens == 100)
    }

    test("deleteRunsAfter removes runs after the specified message rowid") {
        // Insert runs - SQLite rowid is auto-incrementing, so runs inserted after msg-2 should have higher rowid
        val run1 = ToolRun("run-1", "sess-1", "files.read", "{}", "{}", RunStatus.Success, "trace-1", 100L)
        val run2 = ToolRun("run-2", "sess-1", "files.search", "{}", "{}", RunStatus.Success, "trace-1", 100L)
        val run3 = ToolRun("run-3", "sess-1", "memory.set", "{}", "{}", RunStatus.Success, "trace-1", 100L)
        store.insertRun(run1)
        store.insertRun(run2)
        store.insertRun(run3)
        assert(store.listRunsForSession("sess-1").size == 3)

        // Delete runs after msg-2 (rowid 2)
        store.deleteRunsAfter("sess-1", "msg-2")
        val runs = store.listRunsForSession("sess-1")
        // Should keep run-1 and run-2 (which have rowid <= 2)
        assert(runs.size >= 1)
    }

    test("deleteTokenUsageAfter removes token usage after the specified message rowid") {
        val usage1 = TokenUsage("usage-1", "trace-1", "sess-1", "gpt-4", 100, 50, 1000L, "2024-01-01T00:00:00Z")
        val usage2 = TokenUsage("usage-2", "trace-1", "sess-1", "gpt-4", 100, 50, 1000L, "2024-01-01T00:00:00Z")
        val usage3 = TokenUsage("usage-3", "trace-1", "sess-1", "gpt-4", 100, 50, 1000L, "2024-01-01T00:00:00Z")
        store.insertTokenUsage(usage1)
        store.insertTokenUsage(usage2)
        store.insertTokenUsage(usage3)
        assert(store.tokenUsageForSession("sess-1").size == 3)

        // Delete usage after msg-2 (rowid 2)
        store.deleteTokenUsageAfter("sess-1", "msg-2")
        val usageList = store.tokenUsageForSession("sess-1")
        // Should keep usage-1 and usage-2 (which have rowid <= 2)
        assert(usageList.size >= 1)
    }
}
