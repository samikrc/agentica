package agentica.shell

import agentica.search.{IndexKind, SearchIndex, SourceKey}
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable

/**
 *  A single entry stored in the [[SessionScratchpad]].
 *
 *  @param content       Full text content of the stored output.
 *  @param sizeBytes     Byte length of `content` (UTF-8).
 *  @param lineCount     Number of lines in `content`.
 *  @param sourcePath    Source path relative to the session root, e.g. `"data/report.txt"`.
 *  @param lastModified  File's `lastModifiedTime` (epoch ms) at the moment of storage.
 *                       Used by [[SessionScratchpad.isStale]] to detect file changes.
 *  @param storedAt      `System.currentTimeMillis` at storage time; used for LRU eviction ordering.
 */
case class ScratchEntry(
    content:      String,
    sizeBytes:    Long,
    lineCount:    Int,
    sourcePath:   String,
    lastModified: Long,
    storedAt:     Long
)

/**
 *  Session-scoped, in-memory cache for large tool output bodies that exceed the
 *  context-window body budget ([[Presentation.BODY_BUDGET_CHARS]]).
 *
 *  Entries are keyed by the source path (relative to the session root), so
 *  the same file always resolves to the same stable ref within a session.
 *  The ref format is `$scratch/<sourcePath>`, e.g. `$scratch/data/report.txt`.
 *
 *  One [[SessionScratchpad]] is held per active session in `BackendServer`
 *  (`TrieMap[sessionId, SessionScratchpad]`) and removed when the
 *  session is deleted.  Lost on backend restart — the agent gracefully re-reads
 *  files on the next turn.
 *
 *  Thread-safe: all mutations are `synchronized` on this instance.
 *
 *  @param index      Optional chunk index; when present every stored entry is
 *                    indexed as a `stored` row and evictions purge its rows.
 *  @param sessionId  Owning session for index rows.
 *  @param runId      Owning run, applied to computed keys (`__result_N__`) only.
 */
class SessionScratchpad(
    index:     Option[SearchIndex] = None,
    sessionId: Option[String]     = None,
    runId:     Option[String]     = None
)
{
    private val MAX_ENTRIES     = 20
    private val entries         = mutable.LinkedHashMap[String, ScratchEntry]()
    private val computedCounter = AtomicInteger(0)

    /**
     *  Stores a large content body and returns its stable `$scratch/<path>` ref.
     *  If the scratchpad is at capacity, the least-recently-used entry is
     *  evicted first. Re-storing an existing key refreshes its recency.
     *
     *  @param sourcePath    Relative source path used as the cache key.
     *  @param entry         Full entry to store.
     *  @return              Stable ref string, e.g. `"$scratch/data/report.txt"`.
     */
    def store(sourcePath: String, entry: ScratchEntry): String =
        this.synchronized {
            // Drop the existing key before measuring capacity: re-storing a
            // present key must not evict an unrelated entry.
            entries.remove(sourcePath)
            evictOldestIfFull()
            entries.put(sourcePath, entry)
            indexEntry(sourcePath, entry)
            s"$$scratch/$sourcePath"
        }

    /**
     *  Resolves a `$scratch/<path>` ref to its stored entry and refreshes its
     *  recency so an actively used entry is not evicted next.
     *
     *  @param ref  A ref string of the form `"$scratch/<sourcePath>"`.
     *  @return     `Some(entry)` if found, `None` otherwise.
     */
    def get(ref: String): Option[ScratchEntry] =
        this.synchronized {
            val key = ref.stripPrefix("$scratch/")
            entries.remove(key).map { entry =>
                entries.put(key, entry)
                entry
            }
        }

    /**
     *  Checks whether the stored entry for `sourcePath` is stale.
     *  An entry is stale when the file's current `lastModifiedTime` differs from
     *  the value recorded at storage time.
     *
     *  @param sourcePath       Relative source path of the cached file.
     *  @param currentModified  Current `lastModifiedTime` (epoch ms) from the filesystem.
     *  @return                 `true` if the entry is absent or its timestamp has changed.
     */
    def isStale(sourcePath: String, currentModified: Long): Boolean =
        this.synchronized {
            entries.get(sourcePath) match
            {
                case None        => true
                case Some(entry) => entry.lastModified != currentModified
            }
        }

    /**
     *  Returns a unique counter-based key for a computed result (search, LLM output, etc.).
     *  Key format: `"__result_N__"` where `N` is a monotonically-increasing integer.
     *  Each call increments the counter; refs are stable within the scratchpad entry lifetime.
     */
    def nextComputedKey(): String = s"__result_${computedCounter.incrementAndGet()}__"

    /**
     *  Returns the number of entries currently held in the scratchpad.
     *  Primarily useful for tests.
     */
    def size: Int = this.synchronized(entries.size)

    /**
     *  Returns a snapshot of all stored entries, most-recently-used last.
     *  Used by stored-scope search to enumerate the current corpus.
     *  @return Pairs of `(scratchKey, entry)`.
     */
    def entriesSnapshot: List[(String, ScratchEntry)] =
        this.synchronized(entries.toList)

    /**
     *  Evicts the least-recently-used entry when the scratchpad reaches capacity,
     *  purging its index rows so search results never outlive their ref.
     *  Does nothing while the scratchpad remains below its configured limit.
     */
    private def evictOldestIfFull(): Unit =
    {
        if (entries.size >= MAX_ENTRIES)
        {
            val oldest = entries.head._1
            entries.remove(oldest)
            removeIndex(oldest)
        }
    }

    /**
     *  Mirrors one stored entry into the chunk index.
     *
     *  Computed keys (`__result_N__`) are run-qualified; file-backed keys are
     *  session-scoped and durable. File-backed re-stores — cached reads,
     *  hydration — skip the write when the persisted row already matches the
     *  entry's `(lastModified, sizeBytes)`, so repeated stores of an unchanged
     *  file do not rewrite its chunks.
     *
     *  @param key    Scratch key.
     *  @param entry  Entry that was stored.
     */
    private def indexEntry(key: String, entry: ScratchEntry): Unit =
    {
        for (idx <- index; sid <- sessionId)
        {
            val sourceKey = SourceKey(IndexKind.Stored, "", Some(sid), runFor(key), key)
            val reusable  = runFor(key).isEmpty &&
                idx.isCurrent(sourceKey, entry.lastModified, entry.sizeBytes)
            if (!reusable)
                idx.index(sourceKey, entry.content, entry.lastModified, entry.sizeBytes)
        }
    }

    /**
     *  Purges the index rows for an evicted key.
     *  @param key  Scratch key being removed.
     */
    private def removeIndex(key: String): Unit =
    {
        for (idx <- index; sid <- sessionId)
            idx.deleteSource(SourceKey(IndexKind.Stored, "", Some(sid), runFor(key), key))
    }

    /**
     *  Run qualifier for a scratch key: computed keys are run-scoped, file-backed
     *  keys are not.
     *  @param key  Scratch key.
     *  @return     The owning run for `__result_N__` keys, otherwise `None`.
     */
    private def runFor(key: String): Option[String] =
    {
        if (key.startsWith("__result_")) runId else None
    }
}
