package agentica.search

import agentica.observability.TraceLogger
import java.sql.{Connection, PreparedStatement}

/**
 *  JDBC-backed [[SearchIndex]] over SQLite FTS5.
 *
 *  Uses the application connection factory; every operation borrows one
 *  connection and closes it (returning it to the pool). Index writes are
 *  single transactions so a failed chunk insert cannot leave partial rows.
 *
 *  @param conn  Factory function that returns a pooled JDBC connection.
 */
class SearchIndexImpl(conn: () => Connection) extends SearchIndex
{
    @volatile private var available = false

    /**
     *  Whether the FTS5 ranked arm is usable in this process.
     *  @return `true` when the probe succeeded during [[init]].
     */
    def isAvailable: Boolean = available

    /**
     *  Creates the index tables and runs the FTS5 availability probe.
     *  Failures disable the ranked arm rather than throwing.
     */
    def init(): Unit =
    {
        val c = conn()
        try
        {
            val st = c.createStatement()
            st.execute(
                """CREATE VIRTUAL TABLE IF NOT EXISTS chunk_fts USING fts5(
                  |    text,
                  |    kind        UNINDEXED,
                  |    session_id  UNINDEXED,
                  |    run_id      UNINDEXED,
                  |    source_path UNINDEXED,
                  |    chunk_index UNINDEXED,
                  |    start_line  UNINDEXED,
                  |    end_line    UNINDEXED,
                  |    tokenize = 'unicode61 remove_diacritics 2'
                  |)""".stripMargin)
            st.execute(
                """CREATE TABLE IF NOT EXISTS chunk_meta (
                  |    kind          TEXT NOT NULL,
                  |    session_id    TEXT NOT NULL DEFAULT '',
                  |    run_id        TEXT NOT NULL DEFAULT '',
                  |    source_path   TEXT NOT NULL,
                  |    last_modified INTEGER NOT NULL,
                  |    size_bytes    INTEGER NOT NULL,
                  |    line_count    INTEGER NOT NULL,
                  |    chunk_count   INTEGER NOT NULL,
                  |    indexed_at    INTEGER NOT NULL,
                  |    replaced_by   TEXT,
                  |    PRIMARY KEY (kind, session_id, run_id, source_path)
                  |)""".stripMargin)
            st.execute(
                "CREATE INDEX IF NOT EXISTS chunk_meta_indexed ON chunk_meta (kind, indexed_at)")
            st.execute(
                "CREATE INDEX IF NOT EXISTS chunk_meta_session ON chunk_meta (session_id)")
            st.close()

            val ps = c.prepareStatement("SELECT count(*) FROM chunk_fts WHERE chunk_fts MATCH ?")
            ps.setString(1, "probe")
            val rs = ps.executeQuery()
            rs.next()
            rs.close()
            ps.close()
            available = true
        }
        catch
        {
            case t: Throwable =>
                available = false
                TraceLogger.warn("-", "search_index_unavailable",
                    Map("error" -> String.valueOf(t.getMessage)))
        }
        finally
        {
            c.close()
        }
    }

    /**
     *  Looks up the metadata row for one source.
     *  @param kind        Row kind.
     *  @param sessionId   Owning session for stored rows.
     *  @param runId       Owning run for computed stored rows.
     *  @param sourcePath  Workspace-relative path or scratch key.
     *  @return            The metadata row, or `None` when not indexed.
     */
    def meta(kind: String, sessionId: Option[String], runId: Option[String],
             sourcePath: String): Option[SourceMeta] =
    {
        if (!available) return None
        val c = conn()
        try
        {
            val ps = c.prepareStatement(
                s"SELECT * FROM chunk_meta WHERE ${keyPredicate}")
            bindKeys(ps, 1, kind, sessionId, runId, sourcePath)
            val rs = ps.executeQuery()
            val result = if (rs.next()) Some(readMeta(rs)) else None
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
     *  Reports whether the indexed copy of a source matches the given
     *  `(lastModified, sizeBytes)` pair.
     *  @param kind          Row kind.
     *  @param sessionId     Owning session for stored rows.
     *  @param runId         Owning run for computed stored rows.
     *  @param sourcePath    Workspace-relative path or scratch key.
     *  @param lastModified  Current source modification time (epoch ms).
     *  @param sizeBytes     Current source size.
     *  @return              `true` when the existing row is current.
     */
    def isCurrent(kind: String, sessionId: Option[String], runId: Option[String],
                  sourcePath: String, lastModified: Long, sizeBytes: Long): Boolean =
    {
        if (!available) return false
        val c = conn()
        try
        {
            val ps = c.prepareStatement(
                s"SELECT 1 FROM chunk_meta WHERE ${keyPredicate} AND last_modified = ? AND size_bytes = ?")
            var i = bindKeys(ps, 1, kind, sessionId, runId, sourcePath)
            ps.setLong(i, lastModified)
            ps.setLong(i + 1, sizeBytes)
            val rs     = ps.executeQuery()
            val result = rs.next()
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
     *  Replaces all chunks for one source and upserts its metadata row.
     *  @param kind          Row kind.
     *  @param sessionId     Owning session for stored rows.
     *  @param runId         Owning run for computed stored rows.
     *  @param sourcePath    Workspace-relative path or scratch key.
     *  @param text          Full source text to chunk and index.
     *  @param lastModified  Source modification time (epoch ms).
     *  @param sizeBytes     Source size.
     */
    def index(kind: String, sessionId: Option[String], runId: Option[String],
              sourcePath: String, text: String, lastModified: Long, sizeBytes: Long): Unit =
    {
        if (!available) return
        val chunks = Chunker.chunk(text)
        val c      = conn()
        try
        {
            c.setAutoCommit(false)
            try
            {
                deleteChunks(c, kind, sessionId, runId, sourcePath)

                val ps = c.prepareStatement(
                    """INSERT INTO chunk_fts
                      |    (text, kind, session_id, run_id, source_path, chunk_index, start_line, end_line)
                      |VALUES (?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin)
                for (ch <- chunks)
                {
                    ps.setString(1, ch.text)
                    ps.setString(2, kind)
                    ps.setString(3, sessionId.getOrElse(""))
                    ps.setString(4, runId.getOrElse(""))
                    ps.setString(5, sourcePath)
                    ps.setInt(6, ch.index)
                    ps.setInt(7, ch.startLine)
                    ps.setInt(8, ch.endLine)
                    ps.executeUpdate()
                }
                ps.close()

                val upsert = c.prepareStatement(
                    """INSERT INTO chunk_meta
                      |    (kind, session_id, run_id, source_path, last_modified, size_bytes,
                      |     line_count, chunk_count, indexed_at, replaced_by)
                      |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
                      |ON CONFLICT(kind, session_id, run_id, source_path) DO UPDATE SET
                      |    last_modified = excluded.last_modified,
                      |    size_bytes    = excluded.size_bytes,
                      |    line_count    = excluded.line_count,
                      |    chunk_count   = excluded.chunk_count,
                      |    indexed_at    = excluded.indexed_at""".stripMargin)
                upsert.setString(1, kind)
                upsert.setString(2, sessionId.getOrElse(""))
                upsert.setString(3, runId.getOrElse(""))
                upsert.setString(4, sourcePath)
                upsert.setLong(5, lastModified)
                upsert.setLong(6, sizeBytes)
                upsert.setInt(7, lineCount(text))
                upsert.setInt(8, chunks.size)
                upsert.setLong(9, System.currentTimeMillis())
                upsert.executeUpdate()
                upsert.close()

                c.commit()
            }
            catch
            {
                case t: Throwable =>
                    c.rollback()
                    throw t
            }
            finally
            {
                c.setAutoCommit(true)
            }
        }
        finally
        {
            c.close()
        }
    }

    /**
     *  Runs one FTS5 `MATCH` query restricted to the given row scope.
     *  @param kind        Row kind.
     *  @param sessionId   Owning session for stored rows.
     *  @param runId       Owning run for computed stored rows.
     *  @param matchExpr   FTS5 match expression, already sanitised by the caller.
     *  @param limit       Maximum hits to return.
     *  @return            Hits ordered best-first by BM25.
     */
    def search(kind: String, sessionId: Option[String], runId: Option[String],
               matchExpr: String, limit: Int): List[SearchHit] =
    {
        if (!available) return Nil
        val c = conn()
        try
        {
            val ps = c.prepareStatement(
                """SELECT source_path, chunk_index, start_line, end_line,
                  |       snippet(chunk_fts, 0, '«', '»', '…', 12) AS snip,
                  |       bm25(chunk_fts) AS rank
                  |FROM chunk_fts
                  |WHERE chunk_fts MATCH ? AND kind = ? AND session_id = ? AND run_id = ?
                  |ORDER BY rank
                  |LIMIT ?""".stripMargin)
            ps.setString(1, matchExpr)
            ps.setString(2, kind)
            ps.setString(3, sessionId.getOrElse(""))
            ps.setString(4, runId.getOrElse(""))
            ps.setInt(5, limit)
            val rs  = ps.executeQuery()
            val buf = scala.collection.mutable.ListBuffer.empty[SearchHit]
            while rs.next() do
            {
                buf += SearchHit(
                    sourcePath = rs.getString("source_path"),
                    chunkIndex = rs.getInt("chunk_index"),
                    startLine  = rs.getInt("start_line"),
                    endLine    = rs.getInt("end_line"),
                    snippet    = rs.getString("snip"),
                    // bm25() returns negative values (more negative = better);
                    // expose a positive score where higher is better.
                    score      = -rs.getDouble("rank")
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
     *  Counts matches for a sanitised FTS5 expression within the given row scope.
     *  @param kind        Row kind.
     *  @param sessionId   Owning session for stored rows.
     *  @param runId       Owning run for computed stored rows.
     *  @param matchExpr   FTS5 match expression.
     *  @return            Total number of matching chunks.
     */
    def countMatches(kind: String, sessionId: Option[String], runId: Option[String],
                     matchExpr: String): Int =
    {
        if (!available) return 0
        val c = conn()
        try
        {
            val ps = c.prepareStatement(
                """SELECT count(*) FROM chunk_fts
                  |WHERE chunk_fts MATCH ? AND kind = ? AND session_id = ? AND run_id = ?""".stripMargin)
            ps.setString(1, matchExpr)
            ps.setString(2, kind)
            ps.setString(3, sessionId.getOrElse(""))
            ps.setString(4, runId.getOrElse(""))
            val rs = ps.executeQuery()
            rs.next()
            val n = rs.getInt(1)
            rs.close()
            ps.close()
            n
        }
        finally
        {
            c.close()
        }
    }

    /**
     *  Lists metadata rows for one scope, ordered by `indexed_at` ascending.
     *  @param kind        Row kind.
     *  @param sessionId   Owning session for stored rows.
     *  @param runId       Owning run for computed stored rows.
     *  @return            Metadata rows in stale-first order.
     */
    def listSources(kind: String, sessionId: Option[String], runId: Option[String]): List[SourceMeta] =
    {
        if (!available) return Nil
        val c = conn()
        try
        {
            val ps = c.prepareStatement(
                """SELECT * FROM chunk_meta
                  |WHERE kind = ? AND session_id = ? AND run_id = ?
                  |ORDER BY indexed_at ASC""".stripMargin)
            ps.setString(1, kind)
            ps.setString(2, sessionId.getOrElse(""))
            ps.setString(3, runId.getOrElse(""))
            val rs  = ps.executeQuery()
            val buf = scala.collection.mutable.ListBuffer.empty[SourceMeta]
            while rs.next() do buf += readMeta(rs)
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
     *  Records the converted sibling that supersedes a source.
     *  @param kind          Row kind.
     *  @param sessionId     Owning session for stored rows.
     *  @param runId         Owning run for computed stored rows.
     *  @param sourcePath    Superseded source path.
     *  @param replacement   Converted sibling path, or `None` to clear the alias.
     */
    def setReplacedBy(kind: String, sessionId: Option[String], runId: Option[String],
                      sourcePath: String, replacement: Option[String]): Unit =
    {
        if (!available) return
        val c = conn()
        try
        {
            val ps = c.prepareStatement(
                s"UPDATE chunk_meta SET replaced_by = ? WHERE ${keyPredicate}")
            ps.setString(1, replacement.orNull)
            bindKeys(ps, 2, kind, sessionId, runId, sourcePath)
            ps.executeUpdate()
            ps.close()
        }
        finally
        {
            c.close()
        }
    }

    /**
     *  Resolves a supersede alias (e.g. `q3.pdf` → `q3.md`).
     *  @param kind        Row kind.
     *  @param sessionId   Owning session for stored rows.
     *  @param runId       Owning run for computed stored rows.
     *  @param sourcePath  Path that may have been superseded.
     *  @return            The replacement path when an alias is recorded.
     */
    def resolveAlias(kind: String, sessionId: Option[String], runId: Option[String],
                     sourcePath: String): Option[String] =
        meta(kind, sessionId, runId, sourcePath).flatMap(_.replacedBy)

    /**
     *  Deletes all chunks and the metadata row for one source.
     *  @param kind        Row kind.
     *  @param sessionId   Owning session for stored rows.
     *  @param runId       Owning run for computed stored rows.
     *  @param sourcePath  Workspace-relative path or scratch key.
     */
    def deleteSource(kind: String, sessionId: Option[String], runId: Option[String],
                     sourcePath: String): Unit =
    {
        if (!available) return
        val c = conn()
        try
        {
            c.setAutoCommit(false)
            try
            {
                deleteChunks(c, kind, sessionId, runId, sourcePath)
                val ps = c.prepareStatement(
                    s"DELETE FROM chunk_meta WHERE ${keyPredicate}")
                bindKeys(ps, 1, kind, sessionId, runId, sourcePath)
                ps.executeUpdate()
                ps.close()
                c.commit()
            }
            catch
            {
                case t: Throwable =>
                    c.rollback()
                    throw t
            }
            finally
            {
                c.setAutoCommit(true)
            }
        }
        finally
        {
            c.close()
        }
    }

    /**
     *  Deletes every stored row owned by a session (called on session delete).
     *  @param sessionId  Session whose stored rows should be purged.
     */
    def deleteSession(sessionId: String): Unit =
    {
        if (!available) return
        val c = conn()
        try
        {
            c.setAutoCommit(false)
            try
            {
                for (sql <- List(
                    "DELETE FROM chunk_fts WHERE session_id = ?",
                    "DELETE FROM chunk_meta WHERE session_id = ?"))
                {
                    val ps = c.prepareStatement(sql)
                    ps.setString(1, sessionId)
                    ps.executeUpdate()
                    ps.close()
                }
                c.commit()
            }
            catch
            {
                case t: Throwable =>
                    c.rollback()
                    throw t
            }
            finally
            {
                c.setAutoCommit(true)
            }
        }
        finally
        {
            c.close()
        }
    }

    /**
     *  Deletes every stored row owned by a run (called at run end for computed keys).
     *  @param runId  Run whose computed rows should be purged.
     */
    def deleteRun(runId: String): Unit =
    {
        if (!available) return
        val c = conn()
        try
        {
            c.setAutoCommit(false)
            try
            {
                for (sql <- List(
                    "DELETE FROM chunk_fts WHERE run_id = ?",
                    "DELETE FROM chunk_meta WHERE run_id = ?"))
                {
                    val ps = c.prepareStatement(sql)
                    ps.setString(1, runId)
                    ps.executeUpdate()
                    ps.close()
                }
                c.commit()
            }
            catch
            {
                case t: Throwable =>
                    c.rollback()
                    throw t
            }
            finally
            {
                c.setAutoCommit(true)
            }
        }
        finally
        {
            c.close()
        }
    }

    /** WHERE clause addressing one row by its full key. */
    private val keyPredicate: String =
        "kind = ? AND session_id = ? AND run_id = ? AND source_path = ?"

    /**
     *  Binds the four key values starting at `index`.
     *  @return The next free parameter index.
     */
    private def bindKeys(ps: PreparedStatement, index: Int, kind: String,
                         sessionId: Option[String], runId: Option[String],
                         sourcePath: String): Int =
    {
        ps.setString(index, kind)
        ps.setString(index + 1, sessionId.getOrElse(""))
        ps.setString(index + 2, runId.getOrElse(""))
        ps.setString(index + 3, sourcePath)
        index + 4
    }

    /** Deletes chunk rows for one source without touching metadata. */
    private def deleteChunks(c: Connection, kind: String, sessionId: Option[String],
                             runId: Option[String], sourcePath: String): Unit =
    {
        val ps = c.prepareStatement(
            """DELETE FROM chunk_fts
              |WHERE kind = ? AND session_id = ? AND run_id = ? AND source_path = ?""".stripMargin)
        bindKeys(ps, 1, kind, sessionId, runId, sourcePath)
        ps.executeUpdate()
        ps.close()
    }

    /** Reads one `chunk_meta` row from the current cursor position. */
    private def readMeta(rs: java.sql.ResultSet): SourceMeta =
    {
        val sessionId  = Option(rs.getString("session_id")).filter(_.nonEmpty)
        val runId      = Option(rs.getString("run_id")).filter(_.nonEmpty)
        val replacedBy = Option(rs.getString("replaced_by")).filter(_.nonEmpty)
        SourceMeta(
            kind         = rs.getString("kind"),
            sessionId    = sessionId,
            runId        = runId,
            sourcePath   = rs.getString("source_path"),
            lastModified = rs.getLong("last_modified"),
            sizeBytes    = rs.getLong("size_bytes"),
            lineCount    = rs.getInt("line_count"),
            chunkCount   = rs.getInt("chunk_count"),
            indexedAt    = rs.getLong("indexed_at"),
            replacedBy   = replacedBy
        )
    }

    /** Number of lines in `text` (0 for empty input). */
    private def lineCount(text: String): Int =
    {
        if (text.isEmpty) 0 else text.count(_ == '\n') + 1
    }
}
