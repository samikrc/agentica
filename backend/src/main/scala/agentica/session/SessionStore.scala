package agentica.session

import java.sql.Connection
import java.time.Instant
import java.util.UUID

/** Persistence layer for [[Session]] records.
 *  Uses raw JDBC via the supplied connection factory; all operations are synchronous.
 *  @param conn  Factory function that returns a pooled JDBC connection.
 */
class SessionStore(conn: () => Connection)
{

    /** Creates the `sessions` table if it does not already exist. */
    def init(): Unit =
    {
        val c  = conn()
        try {
        val st = c.createStatement()
        st.execute("""
            CREATE TABLE IF NOT EXISTS sessions (
                id               TEXT PRIMARY KEY,
                title            TEXT NOT NULL,
                created_at       TEXT NOT NULL,
                updated_at       TEXT NOT NULL,
                model            TEXT NOT NULL,
                root_path        TEXT,
                last_response_id TEXT
            )
        """)
        st.close()
        // Add last_response_id column to existing DBs that predate this field.
        try
        {
            val alt = c.createStatement()
            alt.execute("ALTER TABLE sessions ADD COLUMN last_response_id TEXT")
            alt.close()
        }
        catch { case _: Exception => () }
        } finally { c.close() }
    }

    /** Inserts a new session row and returns the created [[Session]].
     *  @param title     Display title for the session.
     *  @param model     LLM model name to associate with the session.
     *  @param rootPath  Optional working-directory path for the session.
     */
    def create(title: String, model: String, rootPath: Option[String]): Session =
    {
        val session = Session(
            id        = UUID.randomUUID().toString,
            title     = title,
            createdAt = Instant.now().toString,
            updatedAt = Instant.now().toString,
            model     = model,
            rootPath  = rootPath
        )
        val c  = conn()
        val ps = c.prepareStatement(
            "INSERT INTO sessions (id, title, created_at, updated_at, model, root_path) VALUES (?,?,?,?,?,?)"
        )
        ps.setString(1, session.id)
        ps.setString(2, session.title)
        ps.setString(3, session.createdAt)
        ps.setString(4, session.updatedAt)
        ps.setString(5, session.model)
        ps.setString(6, session.rootPath.orNull)
        ps.executeUpdate()
        ps.close()
        c.close()
        session
    }

    /** Returns all sessions ordered by `updated_at` descending (most recent first). */
    def list(): List[Session] =
    {
        val c   = conn()
        val rs  = c.createStatement().executeQuery("SELECT * FROM sessions ORDER BY updated_at DESC")
        val buf = scala.collection.mutable.ListBuffer.empty[Session]
        while rs.next() do
        {
            buf += Session(
                id             = rs.getString("id"),
                title          = rs.getString("title"),
                createdAt      = rs.getString("created_at"),
                updatedAt      = rs.getString("updated_at"),
                model          = rs.getString("model"),
                rootPath       = Option(rs.getString("root_path")),
                lastResponseId = Option(rs.getString("last_response_id"))
            )
        }
        rs.close()
        c.close()
        buf.toList
    }

    /** Looks up a single session by its UUID.
     *  @param id  Session identifier.
     *  @return    [[Some]] if found, [[None]] otherwise.
     */
    def get(id: String): Option[Session] =
    {
        val c      = conn()
        val ps     = c.prepareStatement("SELECT * FROM sessions WHERE id = ?")
        ps.setString(1, id)
        val rs     = ps.executeQuery()
        val result = if rs.next() then
        {
            Some(Session(
                id             = rs.getString("id"),
                title          = rs.getString("title"),
                createdAt      = rs.getString("created_at"),
                updatedAt      = rs.getString("updated_at"),
                model          = rs.getString("model"),
                rootPath       = Option(rs.getString("root_path")),
                lastResponseId = Option(rs.getString("last_response_id"))
            ))
        } else None
        rs.close()
        ps.close()
        c.close()
        result
    }

    /** Updates the title of a session and refreshes its `updated_at` timestamp.
     *  @param id     Session identifier.
     *  @param title  New display title.
     */
    def updateTitle(id: String, title: String): Unit =
    {
        val c  = conn()
        val ps = c.prepareStatement("UPDATE sessions SET title = ?, updated_at = ? WHERE id = ?")
        ps.setString(1, title)
        ps.setString(2, Instant.now().toString)
        ps.setString(3, id)
        ps.executeUpdate()
        ps.close()
        c.close()
    }

    /** Updates the last Responses API response ID for a session.
     *  Called after every successful [[agentica.llm.LLMProvider.streamResponses]] call
     *  so the next agent run can continue the stateful conversation thread.
     *  @param id          Session identifier.
     *  @param responseId  Response ID returned by the Responses API.
     */
    def updateLastResponseId(id: String, responseId: String): Unit =
    {
        val c  = conn()
        val ps = c.prepareStatement(
            "UPDATE sessions SET last_response_id = ?, updated_at = ? WHERE id = ?"
        )
        ps.setString(1, responseId)
        ps.setString(2, Instant.now().toString)
        ps.setString(3, id)
        ps.executeUpdate()
        ps.close()
        c.close()
    }

    /** Deletes a session row by its UUID.
     *  @param id  Session identifier.
     */
    def delete(id: String): Unit =
    {
        val c  = conn()
        val ps = c.prepareStatement("DELETE FROM sessions WHERE id = ?")
        ps.setString(1, id)
        ps.executeUpdate()
        ps.close()
        c.close()
    }
}
