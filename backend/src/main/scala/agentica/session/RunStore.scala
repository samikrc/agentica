package agentica.session

import java.sql.Connection
import java.time.Instant
import java.util.UUID

/** Persistence layer for [[ToolRun]] and [[TokenUsage]] records.
 *  Uses raw JDBC via the supplied connection factory; all operations are synchronous.
 *  @param conn  Factory function that returns a pooled JDBC connection.
 */
class RunStore(conn: () => Connection)
{

    /** Creates the `tool_runs` and `token_usage` tables if they do not already exist. */
    def init(): Unit =
    {
        val c  = conn()
        try {
        val st = c.createStatement()
        st.execute("""
            CREATE TABLE IF NOT EXISTS tool_runs (
                id          TEXT PRIMARY KEY,
                session_id  TEXT NOT NULL,
                tool        TEXT NOT NULL,
                input       TEXT NOT NULL DEFAULT '{}',
                output      TEXT NOT NULL DEFAULT '{}',
                status      TEXT NOT NULL,
                trace_id    TEXT NOT NULL,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
            )
        """)
        st.execute("""
            CREATE TABLE IF NOT EXISTS token_usage (
                id                TEXT PRIMARY KEY,
                trace_id          TEXT NOT NULL,
                session_id        TEXT NOT NULL,
                model             TEXT NOT NULL,
                prompt_tokens     INTEGER NOT NULL DEFAULT 0,
                completion_tokens INTEGER NOT NULL DEFAULT 0,
                latency_ms        INTEGER NOT NULL DEFAULT 0,
                created_at        TEXT NOT NULL
            )
        """)
        st.close()
        } finally { c.close() }
    }

    /** Persists a [[ToolRun]] record.
     *  @param run  The completed tool run to store.
     */
    def insertRun(run: ToolRun): Unit =
    {
        val c  = conn()
        val ps = c.prepareStatement(
            "INSERT INTO tool_runs (id, session_id, tool, input, output, status, trace_id, duration_ms) VALUES (?,?,?,?,?,?,?,?)"
        )
        ps.setString(1, run.id)
        ps.setString(2, run.sessionId)
        ps.setString(3, run.tool)
        ps.setString(4, run.input)
        ps.setString(5, run.output)
        ps.setString(6, run.status.value)
        ps.setString(7, run.traceId)
        ps.setLong(8, run.durationMs)
        ps.executeUpdate()
        ps.close()
        c.close()
    }

    /** Persists a [[TokenUsage]] record.
     *  @param u  The token accounting record to store.
     */
    def insertTokenUsage(u: TokenUsage): Unit =
    {
        val c  = conn()
        val ps = c.prepareStatement(
            "INSERT INTO token_usage (id, trace_id, session_id, model, prompt_tokens, completion_tokens, latency_ms, created_at) VALUES (?,?,?,?,?,?,?,?)"
        )
        ps.setString(1, u.id)
        ps.setString(2, u.traceId)
        ps.setString(3, u.sessionId)
        ps.setString(4, u.model)
        ps.setInt(5, u.promptTokens)
        ps.setInt(6, u.completionTokens)
        ps.setLong(7, u.latencyMs)
        ps.setString(8, u.createdAt)
        ps.executeUpdate()
        ps.close()
        c.close()
    }

    /** Returns all tool runs for a session ordered by insertion order (rowid ASC).
     *  @param sessionId  Session identifier.
     */
    def listRunsForSession(sessionId: String): List[ToolRun] =
    {
        val c   = conn()
        val ps  = c.prepareStatement("SELECT * FROM tool_runs WHERE session_id = ? ORDER BY rowid ASC")
        ps.setString(1, sessionId)
        val rs  = ps.executeQuery()
        val buf = scala.collection.mutable.ListBuffer.empty[ToolRun]
        while rs.next() do
        {
            buf += ToolRun(
                id         = rs.getString("id"),
                sessionId  = rs.getString("session_id"),
                tool       = rs.getString("tool"),
                input      = rs.getString("input"),
                output     = rs.getString("output"),
                status     = RunStatus.unsafe(rs.getString("status")),
                traceId    = rs.getString("trace_id"),
                durationMs = rs.getLong("duration_ms")
            )
        }
        rs.close()
        ps.close()
        c.close()
        buf.toList
    }

    /** Returns all token-usage records for a session ordered by `created_at` ascending.
     *  @param sessionId  Session identifier.
     */
    def tokenUsageForSession(sessionId: String): List[TokenUsage] =
    {
        val c   = conn()
        val ps  = c.prepareStatement("SELECT * FROM token_usage WHERE session_id = ? ORDER BY created_at ASC")
        ps.setString(1, sessionId)
        val rs  = ps.executeQuery()
        val buf = scala.collection.mutable.ListBuffer.empty[TokenUsage]
        while rs.next() do
        {
            buf += TokenUsage(
                id               = rs.getString("id"),
                traceId          = rs.getString("trace_id"),
                sessionId        = rs.getString("session_id"),
                model            = rs.getString("model"),
                promptTokens     = rs.getInt("prompt_tokens"),
                completionTokens = rs.getInt("completion_tokens"),
                latencyMs        = rs.getLong("latency_ms"),
                createdAt        = rs.getString("created_at")
            )
        }
        rs.close()
        ps.close()
        c.close()
        buf.toList
    }
}
