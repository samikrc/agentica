package agentica.session

import java.sql.Connection
import java.time.Instant

/**
 *  Persistence contract for session-scoped key-value memory entries.
 *  Backed by the SQLite `memory_entries` table.
 *  Phase 2: session-scoped only (`sessionId` is always `Some`).
 *  Phase 6 adds global scope via `sessionId = None`.
 */
trait MemoryStore
{
    /**
     *  Creates the `memory_entries` table if it does not already exist.
     */
    def init(): Unit

    /**
     *  Upserts a key-value pair for the given session.
     *  If the key already exists its value and `updated_at` are overwritten.
     *  @param sessionId  Owning session identifier.
     *  @param key        Entry key, unique within the session.
     *  @param value      String value to store.
     *  @return           The persisted [[MemoryEntry]].
     */
    def set(sessionId: String, key: String, value: String): MemoryEntry

    /**
     *  Retrieves a single entry by session and key.
     *  @param sessionId  Owning session identifier.
     *  @param key        Entry key.
     *  @return           [[Some]] if the key exists, [[None]] otherwise.
     */
    def get(sessionId: String, key: String): Option[MemoryEntry]

    /**
     *  Lists all entries for the given session, ordered by key ascending.
     *  @param sessionId  Owning session identifier.
     *  @return           All [[MemoryEntry]] records for the session.
     */
    def list(sessionId: String): List[MemoryEntry]

    /**
     *  Removes all memory entries owned by the given session.
     *  Called on session delete.
     *  @param sessionId  Session whose entries should be purged.
     */
    def deleteForSession(sessionId: String): Unit
}

/**
 *  JDBC-backed implementation of [[MemoryStore]].
 *  Uses raw JDBC via the supplied connection factory; all operations are synchronous.
 *  @param conn  Factory function that returns a pooled JDBC connection.
 */
class MemoryStoreImpl(conn: () => Connection) extends MemoryStore
{

    /**
     *  Creates the `memory_entries` table if it does not already exist.
     */
    def init(): Unit =
    {
        val c  = conn()
        try
        {
            val st = c.createStatement()
            st.execute("""
                CREATE TABLE IF NOT EXISTS memory_entries (
                    session_id  TEXT NOT NULL,
                    key         TEXT NOT NULL,
                    value       TEXT NOT NULL,
                    updated_at  TEXT NOT NULL,
                    PRIMARY KEY (session_id, key),
                    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
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
     *  Upserts a key-value pair for the given session.
     *  @param sessionId  Owning session identifier.
     *  @param key        Entry key, unique within the session.
     *  @param value      String value to store.
     *  @return           The persisted [[MemoryEntry]].
     */
    def set(sessionId: String, key: String, value: String): MemoryEntry =
    {
        val now   = Instant.now().toString
        val entry = MemoryEntry(sessionId = sessionId, key = key, value = value, updatedAt = now)
        val c     = conn()
        try
        {
            val ps = c.prepareStatement("""
                INSERT INTO memory_entries (session_id, key, value, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(session_id, key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at
            """)
            ps.setString(1, sessionId)
            ps.setString(2, key)
            ps.setString(3, value)
            ps.setString(4, now)
            ps.executeUpdate()
            ps.close()
        }
        finally
        {
            c.close()
        }
        entry
    }

    /**
     *  Retrieves a single entry by session and key.
     *  @param sessionId  Owning session identifier.
     *  @param key        Entry key.
     *  @return           [[Some]] if the key exists, [[None]] otherwise.
     */
    def get(sessionId: String, key: String): Option[MemoryEntry] =
    {
        val c  = conn()
        try
        {
            val ps = c.prepareStatement(
                "SELECT * FROM memory_entries WHERE session_id = ? AND key = ?"
            )
            ps.setString(1, sessionId)
            ps.setString(2, key)
            val rs = ps.executeQuery()
            val result = if (rs.next())
            {
                Some(MemoryEntry(
                    sessionId = rs.getString("session_id"),
                    key       = rs.getString("key"),
                    value     = rs.getString("value"),
                    updatedAt = rs.getString("updated_at")
                ))
            }
            else
            {
                None
            }
            rs.close()
            ps.close()
            result
        }
        finally
        {
            c.close()
        }
    }

    /**
     *  Lists all entries for the given session, ordered by key ascending.
     *  @param sessionId  Owning session identifier.
     *  @return           All [[MemoryEntry]] records for the session.
     */
    def list(sessionId: String): List[MemoryEntry] =
    {
        val c   = conn()
        try
        {
            val ps  = c.prepareStatement(
                "SELECT * FROM memory_entries WHERE session_id = ? ORDER BY key ASC"
            )
            ps.setString(1, sessionId)
            val rs  = ps.executeQuery()
            val buf = scala.collection.mutable.ListBuffer.empty[MemoryEntry]
            while rs.next() do
            {
                buf += MemoryEntry(
                    sessionId = rs.getString("session_id"),
                    key       = rs.getString("key"),
                    value     = rs.getString("value"),
                    updatedAt = rs.getString("updated_at")
                )
            }
            rs.close()
            ps.close()
            buf.toList
        }
        finally
        {
            c.close()
        }
    }

    /**
     *  Removes all memory entries owned by the given session.
     *  @param sessionId  Session whose entries should be purged.
     */
    def deleteForSession(sessionId: String): Unit =
    {
        val c  = conn()
        try
        {
            val ps = c.prepareStatement("DELETE FROM memory_entries WHERE session_id = ?")
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
