package agentica.permissions

import java.sql.Connection
import java.time.Instant
import java.util.UUID

/**
 *  JDBC-backed implementation of [[ScopeStore]].
 *  Stores permission grants in the SQLite `permission_grants` table.
 *  All operations are synchronous and use a connection factory for thread safety.
 *  @param conn  Factory function that returns a JDBC connection.
 */
class ScopeStoreImpl(conn: () => Connection) extends ScopeStore
{

    /**
     *  Creates the `permission_grants` table if it does not already exist.
     */
    def init(): Unit =
    {
        val c  = conn()
        try
        {
            val st = c.createStatement()
            st.execute("""
                CREATE TABLE IF NOT EXISTS permission_grants (
                    id          TEXT PRIMARY KEY,
                    session_id  TEXT,
                    tool_set    TEXT NOT NULL,
                    path_prefix TEXT,
                    ttl         TEXT NOT NULL,
                    created_at  TEXT NOT NULL
                )
            """)
            st.close()
        }
        finally
        {
            c.close()
        }
    }

    /**
     *  Checks whether a valid grant exists for the given tool and path.
     *  Matches on exact tool name or wildcard family (`files.*`).
     *  Global grants (`session_id IS NULL`) are also matched.
     *  @param sessionId  Current session identifier.
     *  @param toolName   Canonical tool name, e.g. `"files.write"`.
     *  @param path       Absolute resolved path the tool intends to access.
     *  @return           `true` if a non-expired grant covers this tool and path.
     */
    def hasGrant(sessionId: String, toolName: String, path: String): Boolean =
    {
        val family    = toolName.split('.').headOption.getOrElse("") + ".*"
        val c         = conn()
        try
        {
            val ps = c.prepareStatement("""
                SELECT path_prefix FROM permission_grants
                WHERE (session_id = ? OR session_id IS NULL)
                  AND (tool_set = ? OR tool_set = ?)
            """)
            ps.setString(1, sessionId)
            ps.setString(2, toolName)
            ps.setString(3, family)
            val rs     = ps.executeQuery()
            var found  = false
            while rs.next() && !found do
            {
                val prefix = Option(rs.getString("path_prefix"))
                found = prefix.forall(p => path.startsWith(p))
            }
            rs.close()
            ps.close()
            found
        }
        finally
        {
            c.close()
        }
    }

    /**
     *  Records a new grant returned from the permission modal.
     *  For `Once` TTL, the record is inserted but consumed after first use.
     *  @param sessionId  Owning session.
     *  @param toolName   Canonical tool name.
     *  @param decision   Approved decision carrying TTL and optional path prefix.
     */
    def addGrant(sessionId: String, toolName: String, decision: GrantDecision.Granted): Unit =
    {
        val sid = decision.ttl match
        {
            case GrantTTL.Always => null
            case _               => sessionId
        }
        val c  = conn()
        try
        {
            val ps = c.prepareStatement("""
                INSERT INTO permission_grants (id, session_id, tool_set, path_prefix, ttl, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
            """)
            ps.setString(1, UUID.randomUUID().toString)
            ps.setString(2, sid)
            ps.setString(3, toolName)
            ps.setString(4, decision.pathPrefix.orNull)
            ps.setString(5, decision.ttl.toString)
            ps.setString(6, Instant.now().toString)
            ps.executeUpdate()
            ps.close()
        }
        finally
        {
            c.close()
        }
    }

    /**
     *  Removes all `Once`-scoped grants consumed by this tool call.
     *  Called immediately after a successful `Once`-scoped execution.
     *  @param sessionId  Owning session.
     *  @param toolName   Canonical tool name.
     *  @param path       Absolute resolved path that was used.
     */
    def consumeOnce(sessionId: String, toolName: String, path: String): Unit =
    {
        val c  = conn()
        try
        {
            val ps = c.prepareStatement("""
                DELETE FROM permission_grants
                WHERE session_id = ? AND tool_set = ? AND ttl = 'Once'
                  AND (path_prefix IS NULL OR ? LIKE path_prefix || '%')
            """)
            ps.setString(1, sessionId)
            ps.setString(2, toolName)
            ps.setString(3, path)
            ps.executeUpdate()
            ps.close()
        }
        finally
        {
            c.close()
        }
    }

    /**
     *  Removes all grants owned by the given session (called on session delete).
     *  @param sessionId  Session whose grants should be purged.
     */
    def deleteForSession(sessionId: String): Unit =
    {
        val c  = conn()
        try
        {
            val ps = c.prepareStatement(
                "DELETE FROM permission_grants WHERE session_id = ?"
            )
            ps.setString(1, sessionId)
            ps.executeUpdate()
            ps.close()
        }
        finally
        {
            c.close()
        }
    }
}
