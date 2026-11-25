package agentica.search

import agentica.observability.TraceLogger
import java.sql.{Connection, PreparedStatement, ResultSet}

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
                  |    root_id     UNINDEXED,
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
                  |    root_id       TEXT NOT NULL DEFAULT '',
                  |    session_id    TEXT NOT NULL DEFAULT '',
                  |    run_id        TEXT NOT NULL DEFAULT '',
                  |    source_path   TEXT NOT NULL,
                  |    last_modified INTEGER NOT NULL,
                  |    size_bytes    INTEGER NOT NULL,
                  |    line_count    INTEGER NOT NULL,
                  |    chunk_count   INTEGER NOT NULL,
                  |    indexed_at    INTEGER NOT NULL,
                  |    replaced_by   TEXT,
                  |    PRIMARY KEY (kind, root_id, session_id, run_id, source_path)
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
     *  @param key  Full address of the source.
     *  @return     The metadata row, or `None` when not indexed.
     */
    def meta(key: SourceKey): Option[SourceMeta] =
    {
        if (!available) return None
        val c = conn()
        try
        {
            val ps = c.prepareStatement(s"SELECT * FROM chunk_meta WHERE $keyPredicate")
            bindKeys(ps, 1, key)
            val rs     = ps.executeQuery()
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
     *  @param key           Full address of the source.
     *  @param lastModified  Current source modification time (epoch ms).
     *  @param sizeBytes     Current source size.
     *  @return              `true` when the existing row is current.
     */
    def isCurrent(key: SourceKey, lastModified: Long, sizeBytes: Long): Boolean =
    {
        if (!available) return false
        val c = conn()
        try
        {
            val ps = c.prepareStatement(
                s"SELECT 1 FROM chunk_meta WHERE $keyPredicate AND last_modified = ? AND size_bytes = ?")
            val i = bindKeys(ps, 1, key)
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
     *  @param key           Full address of the source.
     *  @param text          Full source text to chunk and index.
     *  @param lastModified  Source modification time (epoch ms).
     *  @param sizeBytes     Source size.
     */
    def index(key: SourceKey, text: String, lastModified: Long, sizeBytes: Long): Unit =
    {
        if (!available) return
        val chunks = Chunker.chunk(text)
        val c      = conn()
        try
        {
            c.setAutoCommit(false)
            try
            {
                deleteChunks(c, key)

                val ps = c.prepareStatement(
                    """INSERT INTO chunk_fts
                      |    (text, kind, root_id, session_id, run_id, source_path,
                      |     chunk_index, start_line, end_line)
                      |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""".stripMargin)
                for (ch <- chunks)
                {
                    ps.setString(1, ch.text)
                    ps.setString(2, key.kind)
                    ps.setString(3, key.rootId)
                    ps.setString(4, key.sessionId.getOrElse(""))
                    ps.setString(5, key.runId.getOrElse(""))
                    ps.setString(6, key.sourcePath)
                    ps.setInt(7, ch.index)
                    ps.setInt(8, ch.startLine)
                    ps.setInt(9, ch.endLine)
                    ps.executeUpdate()
                }
                ps.close()

                val upsert = c.prepareStatement(
                    """INSERT INTO chunk_meta
                      |    (kind, root_id, session_id, run_id, source_path, last_modified,
                      |     size_bytes, line_count, chunk_count, indexed_at, replaced_by)
                      |VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
                      |ON CONFLICT(kind, root_id, session_id, run_id, source_path) DO UPDATE SET
                      |    last_modified = excluded.last_modified,
                      |    size_bytes    = excluded.size_bytes,
                      |    line_count    = excluded.line_count,
                      |    chunk_count   = excluded.chunk_count,
                      |    indexed_at    = excluded.indexed_at""".stripMargin)
                upsert.setString(1, key.kind)
                upsert.setString(2, key.rootId)
                upsert.setString(3, key.sessionId.getOrElse(""))
                upsert.setString(4, key.runId.getOrElse(""))
                upsert.setString(5, key.sourcePath)
                upsert.setLong(6, lastModified)
                upsert.setLong(7, sizeBytes)
                upsert.setInt(8, lineCount(text))
                upsert.setInt(9, chunks.size)
                upsert.setLong(10, System.currentTimeMillis())
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
     *  Runs one FTS5 `MATCH` query restricted to the given scope.
     *  @param scope      Row scope to search.
     *  @param matchExpr  FTS5 match expression, already sanitised by the caller.
     *  @param limit      Maximum hits to return.
     *  @return           Hits ordered best-first by BM25.
     */
    def search(scope: IndexScope, matchExpr: String, limit: Int): List[SearchHit] =
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
                  |WHERE chunk_fts MATCH ?
                  |  AND kind = ? AND root_id = ? AND session_id = ? AND run_id = ?
                  |ORDER BY rank
                  |LIMIT ?""".stripMargin)
            ps.setString(1, matchExpr)
            val i = bindScope(ps, 2, scope)
            ps.setInt(i, limit)
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
     *  Counts matches for a sanitised FTS5 expression within the given scope.
     *  @param scope      Row scope to count within.
     *  @param matchExpr  FTS5 match expression.
     *  @return           Total number of matching chunks.
     */
    def countMatches(scope: IndexScope, matchExpr: String): Int =
    {
        if (!available) return 0
        val c = conn()
        try
        {
            val ps = c.prepareStatement(
                """SELECT count(*) FROM chunk_fts
                  |WHERE chunk_fts MATCH ?
                  |  AND kind = ? AND root_id = ? AND session_id = ? AND run_id = ?""".stripMargin)
            ps.setString(1, matchExpr)
            bindScope(ps, 2, scope)
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
     *  @param scope  Row scope to list.
     *  @return       Metadata rows in stale-first order.
     */
    def listSources(scope: IndexScope): List[SourceMeta] =
    {
        if (!available) return Nil
        val c = conn()
        try
        {
            val ps = c.prepareStatement(
                """SELECT * FROM chunk_meta
                  |WHERE kind = ? AND root_id = ? AND session_id = ? AND run_id = ?
                  |ORDER BY indexed_at ASC""".stripMargin)
            bindScope(ps, 1, scope)
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
     *  @param key          Full address of the superseded source.
     *  @param replacement  Converted sibling path, or `None` to clear the alias.
     */
    def setReplacedBy(key: SourceKey, replacement: Option[String]): Unit =
    {
        if (!available) return
        val c = conn()
        try
        {
            val ps = c.prepareStatement(
                s"UPDATE chunk_meta SET replaced_by = ? WHERE $keyPredicate")
            ps.setString(1, replacement.orNull)
            bindKeys(ps, 2, key)
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
     *  @param key  Address of the path that may have been superseded.
     *  @return     The replacement path when an alias is recorded.
     */
    def resolveAlias(key: SourceKey): Option[String] =
        meta(key).flatMap(_.replacedBy)

    /**
     *  Deletes all chunks and the metadata row for one source.
     *  @param key  Full address of the source.
     */
    def deleteSource(key: SourceKey): Unit =
    {
        if (!available) return
        val c = conn()
        try
        {
            c.setAutoCommit(false)
            try
            {
                deleteChunks(c, key)
                val ps = c.prepareStatement(s"DELETE FROM chunk_meta WHERE $keyPredicate")
                bindKeys(ps, 1, key)
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
        deleteWhere("session_id", sessionId)

    /**
     *  Deletes every stored row owned by a run (called at run end for computed keys).
     *  @param runId  Run whose computed rows should be purged.
     */
    def deleteRun(runId: String): Unit =
        deleteWhere("run_id", runId)

    /** WHERE clause addressing one row by its full key. */
    private val keyPredicate: String =
        "kind = ? AND root_id = ? AND session_id = ? AND run_id = ? AND source_path = ?"

    /**
     *  Binds a [[SourceKey]] starting at `index`.
     *  @return The next free parameter index.
     */
    private def bindKeys(ps: PreparedStatement, index: Int, key: SourceKey): Int =
    {
        ps.setString(index, key.kind)
        ps.setString(index + 1, key.rootId)
        ps.setString(index + 2, key.sessionId.getOrElse(""))
        ps.setString(index + 3, key.runId.getOrElse(""))
        ps.setString(index + 4, key.sourcePath)
        index + 5
    }

    /**
     *  Binds an [[IndexScope]] starting at `index`.
     *  @return The next free parameter index.
     */
    private def bindScope(ps: PreparedStatement, index: Int, scope: IndexScope): Int =
    {
        ps.setString(index, scope.kind)
        ps.setString(index + 1, scope.rootId)
        ps.setString(index + 2, scope.sessionId.getOrElse(""))
        ps.setString(index + 3, scope.runId.getOrElse(""))
        index + 4
    }

    /** Deletes chunk rows for one source without touching metadata. */
    private def deleteChunks(c: Connection, key: SourceKey): Unit =
    {
        val ps = c.prepareStatement(
            """DELETE FROM chunk_fts
              |WHERE kind = ? AND root_id = ? AND session_id = ? AND run_id = ? AND source_path = ?""".stripMargin)
        bindKeys(ps, 1, key)
        ps.executeUpdate()
        ps.close()
    }

    /** Deletes all rows where `column = value` from both tables. */
    private def deleteWhere(column: String, value: String): Unit =
    {
        if (!available) return
        val c = conn()
        try
        {
            c.setAutoCommit(false)
            try
            {
                for (sql <- List(
                    s"DELETE FROM chunk_fts WHERE $column = ?",
                    s"DELETE FROM chunk_meta WHERE $column = ?"))
                {
                    val ps = c.prepareStatement(sql)
                    ps.setString(1, value)
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

    /** Reads one `chunk_meta` row from the current cursor position. */
    private def readMeta(rs: ResultSet): SourceMeta =
    {
        SourceMeta(
            key = SourceKey(
                kind       = rs.getString("kind"),
                rootId     = rs.getString("root_id"),
                sessionId  = Option(rs.getString("session_id")).filter(_.nonEmpty),
                runId      = Option(rs.getString("run_id")).filter(_.nonEmpty),
                sourcePath = rs.getString("source_path")
            ),
            lastModified = rs.getLong("last_modified"),
            sizeBytes    = rs.getLong("size_bytes"),
            lineCount    = rs.getInt("line_count"),
            chunkCount   = rs.getInt("chunk_count"),
            indexedAt    = rs.getLong("indexed_at"),
            replacedBy   = Option(rs.getString("replaced_by")).filter(_.nonEmpty)
        )
    }

    /** Number of lines in `text` (0 for empty input). */
    private def lineCount(text: String): Int =
    {
        if (text.isEmpty) 0 else text.count(_ == '\n') + 1
    }
}
