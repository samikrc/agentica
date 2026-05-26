package agentica.session

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.BeforeAndAfterEach
import java.sql.{Connection, DriverManager}
import scala.compiletime.uninitialized

/**
 *  Unit tests for [[MessageStore]].
 *  Uses an in-memory SQLite database (created fresh per test) to verify
 *  persistence, CRUD operations, and deletion.
 */
class MessageStoreTest extends AnyFunSuite with BeforeAndAfterEach
{

    private var store: MessageStore = uninitialized

    /**
     *  Opens a fresh in-memory SQLite database before each test and initialises
     *  the schema.  The store receives a connection factory that returns a
     *  non-closing proxy so the in-memory database survives between store calls.
     */
    override def beforeEach(): Unit =
    {
        Class.forName("org.sqlite.JDBC")
        val realConn = DriverManager.getConnection("jdbc:sqlite::memory:")
        // Initialise the messages table schema.
        val st = realConn.createStatement()
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
        store = MessageStore(() => proxy)
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    test("listForSession returns empty list when no messages exist") {
        assert(store.listForSession("s1") == Nil)
    }

    test("append and retrieve messages") {
        val msg1 = store.append("s1", MessageRole.User, "Hello")
        val msg2 = store.append("s1", MessageRole.Assistant, "Hi there")
        val messages = store.listForSession("s1")
        assert(messages.size == 2)
        assert(messages.head.id == msg1.id)
        assert(messages.head.role == MessageRole.User)
        assert(messages(1).id == msg2.id)
        assert(messages(1).role == MessageRole.Assistant)
    }

    test("updateContent modifies message content") {
        val msg = store.append("s1", MessageRole.User, "Original")
        store.updateContent(msg.id, "Updated")
        val messages = store.listForSession("s1")
        assert(messages.head.content == "Updated")
    }

    test("deleteForSession removes all messages for a session") {
        store.append("s1", MessageRole.User, "Hello")
        store.append("s1", MessageRole.Assistant, "Hi")
        assert(store.listForSession("s1").size == 2)

        store.deleteForSession("s1")
        assert(store.listForSession("s1").isEmpty)
    }

    test("deleteAfter removes messages after the specified message") {
        val msg1 = store.append("s1", MessageRole.User, "First")
        val msg2 = store.append("s1", MessageRole.Assistant, "Second")
        val msg3 = store.append("s1", MessageRole.User, "Third")
        val msg4 = store.append("s1", MessageRole.Assistant, "Fourth")
        assert(store.listForSession("s1").size == 4)

        store.deleteAfter("s1", msg2.id)
        val messages = store.listForSession("s1")
        assert(messages.size == 2)
        assert(messages.head.id == msg1.id)
        assert(messages(1).id == msg2.id)
    }

    test("deleteAfter with first message keeps only that message") {
        val msg1 = store.append("s1", MessageRole.User, "First")
        store.append("s1", MessageRole.Assistant, "Second")
        store.append("s1", MessageRole.User, "Third")
        assert(store.listForSession("s1").size == 3)

        store.deleteAfter("s1", msg1.id)
        val messages = store.listForSession("s1")
        assert(messages.size == 1)
        assert(messages.head.id == msg1.id)
    }

    test("deleteAfter with last message keeps all messages") {
        val msg1 = store.append("s1", MessageRole.User, "First")
        val msg2 = store.append("s1", MessageRole.Assistant, "Second")
        val msg3 = store.append("s1", MessageRole.User, "Third")
        assert(store.listForSession("s1").size == 3)

        store.deleteAfter("s1", msg3.id)
        val messages = store.listForSession("s1")
        assert(messages.size == 3)
    }

    test("deleteFrom removes the specified message and everything after it") {
        val msg1 = store.append("s1", MessageRole.User, "First")
        val msg2 = store.append("s1", MessageRole.Assistant, "Second")
        val msg3 = store.append("s1", MessageRole.User, "Third")
        val msg4 = store.append("s1", MessageRole.Assistant, "Fourth")
        assert(store.listForSession("s1").size == 4)

        store.deleteFrom("s1", msg2.id)
        val messages = store.listForSession("s1")
        assert(messages.size == 1)
        assert(messages.head.id == msg1.id)
    }

    test("deleteFrom with first message removes all messages") {
        val msg1 = store.append("s1", MessageRole.User, "First")
        store.append("s1", MessageRole.Assistant, "Second")
        store.append("s1", MessageRole.User, "Third")
        assert(store.listForSession("s1").size == 3)

        store.deleteFrom("s1", msg1.id)
        assert(store.listForSession("s1").isEmpty)
    }

    test("deleteFrom with last message removes only that message") {
        val msg1 = store.append("s1", MessageRole.User, "First")
        val msg2 = store.append("s1", MessageRole.Assistant, "Second")
        val msg3 = store.append("s1", MessageRole.User, "Third")
        assert(store.listForSession("s1").size == 3)

        store.deleteFrom("s1", msg3.id)
        val messages = store.listForSession("s1")
        assert(messages.size == 2)
        assert(messages.head.id == msg1.id)
        assert(messages(1).id == msg2.id)
    }
}
