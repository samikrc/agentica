package agentica.search

/**
 *  No-op [[SearchIndex]] used where ranked retrieval is not wired in.
 *
 *  Reports the ranked arm as unavailable so callers degrade to the grep arm;
 *  every mutation is a no-op. Used as the default in [[agentica.tools.ExecutionContext]]
 *  so tests and embedding scenarios need not construct an index.
 */
object SearchIndexDisabled extends SearchIndex
{
    /**
     *  No-op initialisation.
     */
    def init(): Unit = ()

    /**
     *  Always unavailable, forcing grep-only behaviour.
     *  @return `false`.
     */
    def isAvailable: Boolean = false

    /**
     *  Always empty.
     *  @param key  Full address of the source.
     *  @return     `None`.
     */
    def meta(key: SourceKey): Option[SourceMeta] = None

    /**
     *  Never current, so callers would re-index if they could.
     *  @param key           Full address of the source.
     *  @param lastModified  Current source modification time (epoch ms).
     *  @param sizeBytes     Current source size.
     *  @return              `false`.
     */
    def isCurrent(key: SourceKey, lastModified: Long, sizeBytes: Long): Boolean = false

    /**
     *  No-op indexing.
     *  @param key           Full address of the source.
     *  @param text          Full source text.
     *  @param lastModified  Source modification time (epoch ms).
     *  @param sizeBytes     Source size.
     */
    def index(key: SourceKey, text: String, lastModified: Long, sizeBytes: Long): Unit = ()

    /**
     *  Always empty.
     *  @param scope      Row scope to search.
     *  @param matchExpr  FTS5 match expression.
     *  @param limit      Maximum hits.
     *  @return           `Nil`.
     */
    def search(scope: IndexScope, matchExpr: String, limit: Int): List[SearchHit] = Nil

    /**
     *  Always zero.
     *  @param scope      Row scope to count within.
     *  @param matchExpr  FTS5 match expression.
     *  @return           `0`.
     */
    def countMatches(scope: IndexScope, matchExpr: String): Int = 0

    /**
     *  Always empty.
     *  @param scope  Row scope to list.
     *  @return       `Nil`.
     */
    def listSources(scope: IndexScope): List[SourceMeta] = Nil

    /**
     *  No-op alias update.
     *  @param key          Full address of the superseded source.
     *  @param replacement  Converted sibling path, or `None`.
     */
    def setReplacedBy(key: SourceKey, replacement: Option[String]): Unit = ()

    /**
     *  Always empty.
     *  @param key  Address of the path that may have been superseded.
     *  @return     `None`.
     */
    def resolveAlias(key: SourceKey): Option[String] = None

    /**
     *  No-op delete.
     *  @param key  Full address of the source.
     */
    def deleteSource(key: SourceKey): Unit = ()

    /**
     *  No-op delete.
     *  @param sessionId  Session whose stored rows would be purged.
     */
    def deleteSession(sessionId: String): Unit = ()

    /**
     *  No-op delete.
     *  @param runId  Run whose computed rows would be purged.
     */
    def deleteRun(runId: String): Unit = ()
}
