package agentica.session

import upickle.default.*
import java.sql.Connection
import java.time.Instant
import java.util.UUID

/**
 *  Persistence layer for [[AgentTurn]] records.
 *  Each row represents one complete agent invocation, with its ordered trajectory of
 *  intermediate LLM responses and tool dispatches serialised as a JSON array in `steps`.
 *  @param conn  Factory function returning a pooled JDBC connection.
 */
class AgentTurnStore(conn: () => Connection)
{

    /**
     *  Creates the `agent_turns` table if it does not already exist.
     *  The `steps` column stores a JSON array of [[AgentTurnStep]] objects.
     */
    def init(): Unit =
    {
        val c  = conn()
        try
        {
            val st = c.createStatement()
            st.execute("""
                CREATE TABLE IF NOT EXISTS agent_turns (
                    id                TEXT PRIMARY KEY,
                    session_id        TEXT NOT NULL,
                    user_msg_id       TEXT NOT NULL,
                    assistant_msg_id  TEXT NOT NULL,
                    steps             TEXT NOT NULL DEFAULT '[]',
                    trace_id          TEXT NOT NULL,
                    timestamp         TEXT NOT NULL,
                    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
                )
            """)
            st.close()
        }
        finally { c.close() }
    }

    /**
     *  Persists a completed [[AgentTurn]].
     *  Steps are serialised to JSON using upickle before storage.
     *  @param turn  The completed agent turn to store.
     */
    def insert(turn: AgentTurn): Unit =
    {
        val stepsJson = write(turn.steps)
        val c  = conn()
        val ps = c.prepareStatement(
            "INSERT INTO agent_turns (id, session_id, user_msg_id, assistant_msg_id, steps, trace_id, timestamp) VALUES (?,?,?,?,?,?,?)"
        )
        try
        {
            ps.setString(1, turn.id)
            ps.setString(2, turn.sessionId)
            ps.setString(3, turn.userMsgId)
            ps.setString(4, turn.assistantMsgId)
            ps.setString(5, stepsJson)
            ps.setString(6, turn.traceId)
            ps.setString(7, turn.timestamp)
            ps.executeUpdate()
        }
        finally
        {
            ps.close()
            c.close()
        }
    }

    /**
     *  Returns all agent turns for a session ordered by `timestamp` ascending.
     *  Steps are deserialised from JSON using upickle.
     *  @param sessionId  Session identifier.
     *  @return           Ordered list of [[AgentTurn]] records.
     */
    def listForSession(sessionId: String): List[AgentTurn] =
    {
        val c  = conn()
        val ps = c.prepareStatement(
            "SELECT * FROM agent_turns WHERE session_id = ? ORDER BY timestamp ASC"
        )
        try
        {
            ps.setString(1, sessionId)
            val rs  = ps.executeQuery()
            val buf = scala.collection.mutable.ListBuffer.empty[AgentTurn]
            while rs.next() do
            {
                val steps = read[List[AgentTurnStep]](rs.getString("steps"))
                buf += AgentTurn(
                    id             = rs.getString("id"),
                    sessionId      = rs.getString("session_id"),
                    userMsgId      = rs.getString("user_msg_id"),
                    assistantMsgId = rs.getString("assistant_msg_id"),
                    steps          = steps,
                    traceId        = rs.getString("trace_id"),
                    timestamp      = rs.getString("timestamp")
                )
            }
            rs.close()
            buf.toList
        }
        finally
        {
            ps.close()
            c.close()
        }
    }

    /**
     *  Deletes all agent turns that belong to messages strictly after `fromMessageId`.
     *  Turns whose timestamps fall between `fromMessageId` and the next message are kept,
     *  because they were produced as part of `fromMessageId`'s exchange.
     *  If `fromMessageId` is the last message in the session nothing is deleted.
     *  This is used for the restart functionality to truncate conversation history.
     *  @param sessionId      Session identifier.
     *  @param fromMessageId  Anchor message; turns up to and including this window are kept.
     */
    def deleteAfter(sessionId: String, fromMessageId: String): Unit =
    {
        val c  = conn()
        val ps = c.prepareStatement(
            """DELETE FROM agent_turns
               WHERE session_id = ?
               AND timestamp >= (
                   SELECT MIN(timestamp) FROM messages
                   WHERE session_id = ?
                   AND timestamp > (SELECT timestamp FROM messages WHERE id = ?)
               )"""
        )
        try
        {
            ps.setString(1, sessionId)
            ps.setString(2, sessionId)
            ps.setString(3, fromMessageId)
            ps.executeUpdate()
        }
        finally
        {
            ps.close()
            c.close()
        }
    }
}
