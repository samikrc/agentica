package agentica.session

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.BeforeAndAfterEach
import java.sql.{Connection, DriverManager}
import scala.compiletime.uninitialized

/**
 *  Unit tests for [[AgentTurnStore]].
 *  Uses an in-memory SQLite database (created fresh per test) to verify
 *  persistence, step serialisation round-trips, and session scoping.
 */
class AgentTurnStoreTest extends AnyFunSuite with BeforeAndAfterEach
{

    private var store: AgentTurnStore = uninitialized
    private var realConn: Connection = uninitialized

    /**
     *  Opens a fresh in-memory SQLite database before each test and initialises
     *  the schema.  The store receives a connection factory that returns a
     *  non-closing proxy so the in-memory database survives between store calls.
     */
    override def beforeEach(): Unit =
    {
        Class.forName("org.sqlite.JDBC")
        realConn = DriverManager.getConnection("jdbc:sqlite::memory:")
        // Create minimal sessions table so the FK constraint is satisfied.
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
        st.execute("INSERT INTO sessions (id) VALUES ('sess-2')")
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
        store = new AgentTurnStore(() => proxy)
        store.init()
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     *  Builds a minimal [[AgentTurn]] with the given id, sessionId and steps.
     */
    private def turn(
        id:        String,
        sessionId: String,
        steps:     List[AgentTurnStep] = Nil,
        asstMsgId: String              = "asst-1"
    ): AgentTurn =
    {
        AgentTurn(
            id             = id,
            sessionId      = sessionId,
            userMsgId      = "user-1",
            assistantMsgId = asstMsgId,
            steps          = steps,
            traceId        = "trace-1",
            timestamp      = "2024-01-01T00:00:00Z"
        )
    }

    private def thinkingStep(iter: Int, text: String): AgentTurnStep =
    {
        AgentTurnStep(stepType = agentica.session.StepType.Thinking, iteration = iter, content = text,
                      command = "", result = "", durationMs = 0L)
    }

    private def toolStep(iter: Int, cmd: String, result: String, dur: Long): AgentTurnStep =
    {
        AgentTurnStep(stepType = agentica.session.StepType.ToolCall, iteration = iter, content = "",
                      command = cmd, result = result, durationMs = dur)
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    test("listForSession returns empty list when no turns exist") {
        assert(store.listForSession("sess-1") == Nil)
    }

    test("insert and retrieve a turn with no steps") {
        store.insert(turn("t1", "sess-1"))
        val results = store.listForSession("sess-1")
        assert(results.size == 1)
        assert(results.head.id == "t1")
        assert(results.head.steps == Nil)
    }

    test("steps round-trip: thinking and tool_call steps survive serialisation") {
        val steps = List(
            thinkingStep(1, "I will search for revenue data"),
            toolStep(1, "files.search query=\"revenue\"", "$ files.search\nok\nfoo.txt:42: revenue = 100", 83L),
            thinkingStep(2, "Now I have the data, I can answer"),
        )
        store.insert(turn("t2", "sess-1", steps = steps))

        val loaded = store.listForSession("sess-1")
        assert(loaded.size == 1)
        val loadedSteps = loaded.head.steps
        assert(loadedSteps.size == 3)

        val s0 = loadedSteps(0)
        assert(s0.stepType == agentica.session.StepType.Thinking)
        assert(s0.iteration == 1)
        assert(s0.content == "I will search for revenue data")
        assert(s0.command == "")
        assert(s0.durationMs == 0L)

        val s1 = loadedSteps(1)
        assert(s1.stepType == agentica.session.StepType.ToolCall)
        assert(s1.iteration == 1)
        assert(s1.command == "files.search query=\"revenue\"")
        assert(s1.result.contains("revenue = 100"))
        assert(s1.durationMs == 83L)

        val s2 = loadedSteps(2)
        assert(s2.stepType == agentica.session.StepType.Thinking)
        assert(s2.iteration == 2)
        assert(s2.content == "Now I have the data, I can answer")
    }

    test("listForSession only returns turns for the requested session") {
        store.insert(turn("t-a1", "sess-1"))
        store.insert(turn("t-a2", "sess-1"))
        store.insert(turn("t-b1", "sess-2"))

        val forSess1 = store.listForSession("sess-1")
        assert(forSess1.size == 2)
        assert(forSess1.map(_.id).toSet == Set("t-a1", "t-a2"))

        val forSess2 = store.listForSession("sess-2")
        assert(forSess2.size == 1)
        assert(forSess2.head.id == "t-b1")
    }

    test("listForSession returns turns ordered by timestamp ascending") {
        store.insert(turn("t-late",  "sess-1").copy(timestamp = "2024-01-01T02:00:00Z"))
        store.insert(turn("t-early", "sess-1").copy(timestamp = "2024-01-01T00:00:00Z"))
        store.insert(turn("t-mid",   "sess-1").copy(timestamp = "2024-01-01T01:00:00Z"))

        val ids = store.listForSession("sess-1").map(_.id)
        assert(ids == List("t-early", "t-mid", "t-late"))
    }

    test("assistantMsgId and userMsgId survive round-trip") {
        store.insert(turn("t3", "sess-1", asstMsgId = "asst-abc").copy(
            userMsgId = "user-xyz"
        ))
        val loaded = store.listForSession("sess-1").head
        assert(loaded.assistantMsgId == "asst-abc")
        assert(loaded.userMsgId == "user-xyz")
    }

    test("step content with unicode and special chars survives round-trip") {
        val steps = List(
            thinkingStep(1, "Revenue ≥ $1M → growth \"strong\" & <done> marker"),
            toolStep(1, "files.search query=\"NRR,revenue\"", "NRR: 115%\nRevenue: $50M", 12L)
        )
        store.insert(turn("t4", "sess-1", steps = steps))

        val loaded = store.listForSession("sess-1").head.steps
        assert(loaded(0).content.contains("≥"))
        assert(loaded(0).content.contains("<done>"))
        assert(loaded(1).command.contains("NRR,revenue"))
        assert(loaded(1).result.contains("115%"))
    }

    test("deleteAfter removes agent turns after the specified message timestamp") {
        // Insert messages with different timestamps to establish the reference point
        val st = realConn.createStatement()
        st.execute("INSERT INTO messages (id, session_id, role, content, timestamp) VALUES ('msg-1', 'sess-1', 'user', 'First', '2024-01-01T00:00:00Z')")
        st.execute("INSERT INTO messages (id, session_id, role, content, timestamp) VALUES ('msg-2', 'sess-1', 'assistant', 'Second', '2024-01-01T01:00:00Z')")
        st.execute("INSERT INTO messages (id, session_id, role, content, timestamp) VALUES ('msg-3', 'sess-1', 'user', 'Third', '2024-01-01T02:00:00Z')")
        st.close()

        // Insert agent turns with timestamps
        store.insert(turn("t-early", "sess-1").copy(timestamp = "2024-01-01T00:30:00Z"))
        store.insert(turn("t-mid", "sess-1").copy(timestamp = "2024-01-01T01:30:00Z"))
        store.insert(turn("t-late", "sess-1").copy(timestamp = "2024-01-01T02:30:00Z"))
        assert(store.listForSession("sess-1").size == 3)

        // Delete turns after msg-2 (timestamp 01:00:00)
        store.deleteAfter("sess-1", "msg-2")
        val turns = store.listForSession("sess-1")
        assert(turns.size == 2)
        assert(turns.head.id == "t-early")
        assert(turns(1).id == "t-mid")
    }

    test("deleteAfter with earliest message removes all but earliest turns") {
        val st = realConn.createStatement()
        st.execute("INSERT INTO messages (id, session_id, role, content, timestamp) VALUES ('msg-1', 'sess-1', 'user', 'First', '2024-01-01T00:00:00Z')")
        st.execute("INSERT INTO messages (id, session_id, role, content, timestamp) VALUES ('msg-2', 'sess-1', 'assistant', 'Second', '2024-01-01T01:00:00Z')")
        st.close()

        store.insert(turn("t-1", "sess-1").copy(timestamp = "2024-01-01T00:30:00Z"))
        store.insert(turn("t-2", "sess-1").copy(timestamp = "2024-01-01T01:30:00Z"))
        assert(store.listForSession("sess-1").size == 2)

        store.deleteAfter("sess-1", "msg-1")
        val turns = store.listForSession("sess-1")
        assert(turns.size == 1)
        assert(turns.head.id == "t-1")
    }
}
