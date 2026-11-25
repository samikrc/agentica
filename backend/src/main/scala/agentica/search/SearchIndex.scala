package agentica.search

/**
 *  Kinds of rows held in the search index.
 *
 *  Workspace rows come from the refresh-on-search crawler and are shared by
 *  every session. Stored rows mirror `SessionScratchpad` entries and are
 *  session-scoped; computed entries (`__result_N__`) are additionally
 *  run-qualified because the scratchpad counter restarts each run.
 */
object IndexKind
{
    /** Crawled workspace file; `sessionId` and `runId` are empty. */
    val Workspace: String = "workspace"

    /** SessionScratchpad entry; `sessionId` is set, `runId` only for computed keys. */
    val Stored: String = "stored"
}

/**
 *  Metadata for one indexed source (a workspace file or a stored entry).
 *  @param kind          [[IndexKind.Workspace]] or [[IndexKind.Stored]].
 *  @param sessionId     Owning session for stored rows; `None` for workspace rows.
 *  @param runId         Owning run for computed stored rows; `None` otherwise.
 *  @param sourcePath    Workspace-relative path or scratch key.
 *  @param lastModified  Source `lastModifiedTime` at index time (epoch ms).
 *  @param sizeBytes     Source size at index time.
 *  @param lineCount     Number of source lines.
 *  @param chunkCount    Number of chunks written to the FTS table.
 *  @param indexedAt     `System.currentTimeMillis` at index time; drives stale-first refresh ordering.
 *  @param replacedBy    Converted sibling that supersedes this source (e.g. `q3.pdf` → `q3.md`).
 */
case class SourceMeta(
    kind:         String,
    sessionId:    Option[String],
    runId:        Option[String],
    sourcePath:   String,
    lastModified: Long,
    sizeBytes:    Long,
    lineCount:    Int,
    chunkCount:   Int,
    indexedAt:    Long,
    replacedBy:   Option[String]
)

/**
 *  One ranked hit from the index.
 *  @param sourcePath  Workspace-relative path or scratch key of the matched source.
 *  @param chunkIndex  Zero-based chunk ordinal within the source.
 *  @param startLine   First source line in the matched chunk (1-based, inclusive).
 *  @param endLine     Last source line in the matched chunk (1-based, inclusive).
 *  @param snippet     Matched excerpt, bounded by the FTS5 `snippet()` window.
 *  @param score       Positive BM25 score; higher is better and only comparable within one result set.
 */
case class SearchHit(
    sourcePath: String,
    chunkIndex: Int,
    startLine:  Int,
    endLine:    Int,
    snippet:    String,
    score:      Double
)

/**
 *  Persistence contract for the FTS5 chunk index used by hybrid retrieval.
 *
 *  Backed by two SQLite tables: `chunk_fts` (FTS5 virtual table carrying chunk
 *  text) and `chunk_meta` (metadata only — no text). Rows are addressed by the
 *  tuple `(kind, sessionId, runId, sourcePath)`; absent session/run values are
 *  stored as empty strings so the primary key behaves consistently.
 */
trait SearchIndex
{
    /**
     *  Creates the index tables and runs the FTS5 availability probe.
     *  Failures disable the ranked arm rather than throwing; see [[isAvailable]].
     */
    def init(): Unit

    /**
     *  Whether the FTS5 ranked arm is usable in this process.
     *  @return `true` when the probe succeeded during [[init]].
     */
    def isAvailable: Boolean

    /**
     *  Looks up the metadata row for one source.
     *  @param kind        [[IndexKind.Workspace]] or [[IndexKind.Stored]].
     *  @param sessionId   Owning session for stored rows.
     *  @param runId       Owning run for computed stored rows.
     *  @param sourcePath  Workspace-relative path or scratch key.
     *  @return            The metadata row, or `None` when the source is not indexed.
     */
    def meta(kind: String, sessionId: Option[String], runId: Option[String], sourcePath: String): Option[SourceMeta]

    /**
     *  Reports whether the indexed copy of a source matches the given
     *  `(lastModified, sizeBytes)` pair, i.e. no re-indexing is needed.
     *  @param kind          Row kind.
     *  @param sessionId     Owning session for stored rows.
     *  @param runId         Owning run for computed stored rows.
     *  @param sourcePath    Workspace-relative path or scratch key.
     *  @param lastModified  Current source modification time (epoch ms).
     *  @param sizeBytes     Current source size.
     *  @return              `true` when the existing index row is current.
     */
    def isCurrent(kind: String, sessionId: Option[String], runId: Option[String],
                  sourcePath: String, lastModified: Long, sizeBytes: Long): Boolean

    /**
     *  Replaces all chunks for one source and upserts its metadata row.
     *  Chunking uses [[Chunker]]; the write is a single transaction.
     *  @param kind          Row kind.
     *  @param sessionId     Owning session for stored rows.
     *  @param runId         Owning run for computed stored rows.
     *  @param sourcePath    Workspace-relative path or scratch key.
     *  @param text          Full source text to chunk and index.
     *  @param lastModified  Source modification time (epoch ms).
     *  @param sizeBytes     Source size.
     */
    def index(kind: String, sessionId: Option[String], runId: Option[String],
              sourcePath: String, text: String, lastModified: Long, sizeBytes: Long): Unit

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
               matchExpr: String, limit: Int): List[SearchHit]

    /**
     *  Counts matches for a sanitised FTS5 expression within the given row scope.
     *  @param kind        Row kind.
     *  @param sessionId   Owning session for stored rows.
     *  @param runId       Owning run for computed stored rows.
     *  @param matchExpr   FTS5 match expression.
     *  @return            Total number of matching chunks.
     */
    def countMatches(kind: String, sessionId: Option[String], runId: Option[String],
                     matchExpr: String): Int

    /**
     *  Lists metadata rows for one scope, ordered by `indexed_at` ascending so
     *  the refresh pass can prioritise never-indexed and stalest sources.
     *  @param kind        Row kind.
     *  @param sessionId   Owning session for stored rows.
     *  @param runId       Owning run for computed stored rows.
     *  @return            Metadata rows in stale-first order.
     */
    def listSources(kind: String, sessionId: Option[String], runId: Option[String]): List[SourceMeta]

    /**
     *  Records the converted sibling that supersedes a source.
     *  @param kind          Row kind.
     *  @param sessionId     Owning session for stored rows.
     *  @param runId         Owning run for computed stored rows.
     *  @param sourcePath    Superseded source path.
     *  @param replacement   Converted sibling path, or `None` to clear the alias.
     */
    def setReplacedBy(kind: String, sessionId: Option[String], runId: Option[String],
                      sourcePath: String, replacement: Option[String]): Unit

    /**
     *  Resolves a supersede alias (e.g. `q3.pdf` → `q3.md`).
     *  @param kind        Row kind.
     *  @param sessionId   Owning session for stored rows.
     *  @param runId       Owning run for computed stored rows.
     *  @param sourcePath  Path that may have been superseded.
     *  @return            The replacement path when an alias is recorded.
     */
    def resolveAlias(kind: String, sessionId: Option[String], runId: Option[String],
                     sourcePath: String): Option[String]

    /**
     *  Deletes all chunks and the metadata row for one source.
     *  @param kind        Row kind.
     *  @param sessionId   Owning session for stored rows.
     *  @param runId       Owning run for computed stored rows.
     *  @param sourcePath  Workspace-relative path or scratch key.
     */
    def deleteSource(kind: String, sessionId: Option[String], runId: Option[String], sourcePath: String): Unit

    /**
     *  Deletes every stored row owned by a session (called on session delete).
     *  @param sessionId  Session whose stored rows should be purged.
     */
    def deleteSession(sessionId: String): Unit

    /**
     *  Deletes every stored row owned by a run (called at run end for computed keys).
     *  @param runId  Run whose computed rows should be purged.
     */
    def deleteRun(runId: String): Unit
}
