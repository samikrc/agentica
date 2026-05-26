package agentica.session

import java.sql.Connection
import java.time.Instant
import java.util.UUID

/** Persistence layer for [[Message]] records.
 *  Uses raw JDBC via the supplied connection factory; all operations are synchronous.
 *  @param conn  Factory function that returns a pooled JDBC connection.
 */
class MessageStore(conn: () => Connection)
{

    /** Creates the `messages` table if it does not already exist. */
    def init(): Unit =
    {
        val c  = conn()
        try {
        val st = c.createStatement()
        st.execute("""
            CREATE TABLE IF NOT EXISTS messages (
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
        } finally { c.close() }
    }

    /**
     *  Appends a new message to a session's history and returns the persisted [[Message]].
     *  @param sessionId  Parent session identifier.
     *  @param role       Speaker role: [[MessageRole.User]], [[MessageRole.Assistant]], or [[MessageRole.System]].
     *  @param content    Text content of the message.
     *  @return           The persisted [[Message]] with its generated UUID and timestamp.
     */
    def append(sessionId: String, role: MessageRole, content: String): Message =
    {
        val msg = Message(
            id          = UUID.randomUUID().toString,
            sessionId   = sessionId,
            role        = role,
            content     = content,
            timestamp   = Instant.now().toString,
            attachments = Nil
        )
        val c  = conn()
        val ps = c.prepareStatement(
            "INSERT INTO messages (id, session_id, role, content, timestamp, attachments) VALUES (?,?,?,?,?,?)"
        )
        ps.setString(1, msg.id)
        ps.setString(2, msg.sessionId)
        ps.setString(3, msg.role.value)
        ps.setString(4, msg.content)
        ps.setString(5, msg.timestamp)
        ps.setString(6, "[]")
        ps.executeUpdate()
        ps.close()
        c.close()
        msg
    }

    /** Returns all messages for a session ordered by `timestamp` ascending.
     *  @param sessionId  Session identifier.
     */
    def listForSession(sessionId: String): List[Message] =
    {
        val c   = conn()
        val ps  = c.prepareStatement(
            "SELECT * FROM messages WHERE session_id = ? ORDER BY timestamp ASC"
        )
        ps.setString(1, sessionId)
        val rs  = ps.executeQuery()
        val buf = scala.collection.mutable.ListBuffer.empty[Message]
        while rs.next() do
        {
            buf += Message(
                id          = rs.getString("id"),
                sessionId   = rs.getString("session_id"),
                role        = MessageRole.unsafe(rs.getString("role")),
                content     = rs.getString("content"),
                timestamp   = rs.getString("timestamp"),
                attachments = Nil
            )
        }
        rs.close()
        ps.close()
        c.close()
        buf.toList
    }

    /** Overwrites the text content of a message in place (used for streaming accumulation).
     *  @param id       Message identifier.
     *  @param content  New full text content.
     */
    def updateContent(id: String, content: String): Unit =
    {
        val c  = conn()
        val ps = c.prepareStatement("UPDATE messages SET content = ? WHERE id = ?")
        ps.setString(1, content)
        ps.setString(2, id)
        ps.executeUpdate()
        ps.close()
        c.close()
    }

    /** Deletes all messages belonging to the given session.
     *  @param sessionId  Session identifier.
     */
    def deleteForSession(sessionId: String): Unit =
    {
        val c  = conn()
        val ps = c.prepareStatement("DELETE FROM messages WHERE session_id = ?")
        ps.setString(1, sessionId)
        ps.executeUpdate()
        ps.close()
        c.close()
    }

    /** Deletes all messages in a session that come after the specified message ID.
     *  This is used for the restart functionality to truncate conversation history.
     *  @param sessionId      Session identifier.
     *  @param fromMessageId  The message ID to delete after (this message is kept).
     */
    def deleteAfter(sessionId: String, fromMessageId: String): Unit =
    {
        val c  = conn()
        val ps = c.prepareStatement(
            "DELETE FROM messages WHERE session_id = ? AND timestamp > (SELECT timestamp FROM messages WHERE id = ?)"
        )
        ps.setString(1, sessionId)
        ps.setString(2, fromMessageId)
        ps.executeUpdate()
        ps.close()
        c.close()
    }

    /**
     *  Deletes the specified message and all messages after it (inclusive).
     *  Used by restart: the frontend re-submits the message as a fresh turn,
     *  so the original DB entry must be removed to avoid duplicates.
     *  @param sessionId      Session identifier.
     *  @param fromMessageId  The message ID to delete from (inclusive).
     */
    def deleteFrom(sessionId: String, fromMessageId: String): Unit =
    {
        val c  = conn()
        val ps = c.prepareStatement(
            "DELETE FROM messages WHERE session_id = ? AND timestamp >= (SELECT timestamp FROM messages WHERE id = ?)"
        )
        ps.setString(1, sessionId)
        ps.setString(2, fromMessageId)
        ps.executeUpdate()
        ps.close()
        c.close()
    }
}
