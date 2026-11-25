package agentica.search

import agentica.observability.TraceLogger
import java.nio.file.{Files, Path}
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*

/**
 *  Outcome of one incremental refresh pass.
 *  @param indexed    Files (re)indexed in this pass.
 *  @param skipped    Files already current or unindexable.
 *  @param pending    Candidates left unprocessed when the budget elapsed.
 *  @param deleted    Stale rows purged because the file no longer exists.
 *  @param elapsedMs  Wall-clock duration of the pass.
 *  @param partial    `true` when `pending > 0` — recall is incomplete for this pass.
 */
case class RefreshStats(
    indexed:   Int,
    skipped:   Int,
    pending:   Int,
    deleted:   Int,
    elapsedMs: Long,
    partial:   Boolean
)

/**
 *  Incremental workspace indexer implementing refresh-on-search.
 *
 *  There is no build phase: each search refreshes its target scope before
 *  querying, indexing only files that are new or whose `(mtime, size)` changed.
 *  Work is ordered never-indexed first, then stalest, so a truncated pass makes
 *  forward progress and repeated searches converge.
 *
 *  Raw office documents are indexed from their text layer. When a converted
 *  sibling (`<stem>.md`) exists and is at least as new as the source, the raw
 *  document is superseded: its chunks are dropped and an alias is recorded so
 *  `path=<stem>.pdf` resolves to the `.md`.
 *
 *  @param index  Chunk index written by the refresh pass.
 */
class WorkspaceIndexer(index: SearchIndex)
{
    /** Files examined per pass before stopping (hard cap). */
    val MaxFilesPerPass: Int = 5000

    /** Total bytes examined per pass before stopping (hard cap). */
    val MaxBytesPerPass: Long = 50L * 1024 * 1024

    /** Default soft time budget per pass, in milliseconds. */
    val DefaultBudgetMs: Long = 2500L

    /**
     *  Refreshes the index for a workspace scope.
     *  @param root       Workspace root (the session `rootPath`).
     *  @param scopeDir   Subtree to refresh, or `None` for the whole workspace.
     *  @param budgetMs   Soft time budget; the pass stops early when exceeded.
     *  @param onProgress Called as `(processed, total)` while indexing.
     *  @return           Per-pass counters, including `partial` when the budget elapsed.
     */
    def refresh(root: Path,
                scopeDir: Option[Path],
                budgetMs: Long = DefaultBudgetMs,
                onProgress: (Int, Int) => Unit = (_, _) => ()): RefreshStats =
    {
        val startedAt = System.currentTimeMillis()
        val rootAbs   = root.toAbsolutePath.normalize()
        val rootId    = rootIdOf(rootAbs)
        val scopeAbs  = scopeDir.map(_.toAbsolutePath.normalize()).getOrElse(rootAbs)
        val scope     = IndexScope(IndexKind.Workspace, rootId, None, None)

        if (!index.isAvailable) return RefreshStats(0, 0, 0, 0, 0L, partial = false)

        val existing = index.listSources(scope).map(m => m.key.sourcePath -> m).toMap

        // 1. Walk the scope, collecting candidate files and the walked path set.
        val candidates = ListBuffer.empty[Path]
        val walked     = scala.collection.mutable.Set.empty[String]
        var totalBytes = 0L
        val stream     = Files.walk(scopeAbs)
        try
        {
            val it = stream.iterator().asScala
            while (it.hasNext && candidates.size < MaxFilesPerPass && totalBytes < MaxBytesPerPass)
            {
                val path = it.next()
                if (isCandidate(rootAbs, scopeAbs, path))
                {
                    val size = Files.size(path)
                    if (size <= TextExtractor.MaxFileBytes)
                    {
                        candidates += path
                        walked += relPath(rootAbs, path)
                        totalBytes += size
                    }
                }
            }
        }
        finally
        {
            stream.close()
        }

        // 2. Order: cheap text files first, then raw documents (text extraction is
        //    ~150-200 ms/page); within each class, never-indexed first then stalest.
        val sorted = candidates.toList.sortBy { p =>
            val rel     = relPath(rootAbs, p)
            val docRank = if (TextExtractor.isDocument(p)) 1 else 0
            (docRank, existing.get(rel).map(_.indexedAt).getOrElse(Long.MinValue))
        }

        // 3. Index changed candidates until the budget elapses.
        var indexed = 0
        var skipped = 0
        var pending = 0
        val total   = sorted.size
        var processed = 0
        var outOfBudget = false
        for (path <- sorted if !outOfBudget)
        {
            if (System.currentTimeMillis() - startedAt > budgetMs)
            {
                outOfBudget = true
            }
            else
            {
                processed += 1
                val rel     = relPath(rootAbs, path)
                val key     = SourceKey(IndexKind.Workspace, rootId, None, None, rel)
                val mtime   = Files.getLastModifiedTime(path).toMillis
                val size    = Files.size(path)
                val sibling = convertedSibling(path)

                sibling match
                {
                    case Some(mdPath) if Files.exists(mdPath) &&
                            Files.getLastModifiedTime(mdPath).toMillis >= mtime =>
                        // Converted sibling supersedes the raw document.
                        index.deleteSource(key)
                        index.index(key, "", mtime, size)
                        index.setReplacedBy(key, Some(relPath(rootAbs, mdPath)))
                        skipped += 1
                    case _ =>
                        if (index.isCurrent(key, mtime, size))
                        {
                            skipped += 1
                        }
                        else
                        {
                            TextExtractor.extract(path) match
                            {
                                case Some(text) =>
                                    index.index(key, text, mtime, size)
                                    indexed += 1
                                case None =>
                                    skipped += 1
                            }
                        }
                }
                onProgress(processed, total)
            }
        }
        if (outOfBudget) pending = total - processed

        // 4. Purge rows whose file no longer exists inside the refreshed scope.
        val deleted = purgeMissing(rootAbs, rootId, scopeAbs, existing.keySet)

        val elapsed = System.currentTimeMillis() - startedAt
        TraceLogger.info("-", "workspace_index_refresh", Map(
            "rootId"    -> rootId,
            "scope"     -> relPath(rootAbs, scopeAbs),
            "indexed"   -> indexed.toString,
            "skipped"   -> skipped.toString,
            "pending"   -> pending.toString,
            "deleted"   -> deleted.toString,
            "elapsedMs" -> elapsed.toString
        ))
        RefreshStats(indexed, skipped, pending, deleted, elapsed, pending > 0)
    }

    /**
     *  Whether a walked path should be considered for indexing.
     *  Skips directories, dotfiles, symlinks, unsupported extensions, and
     *  excluded directory names.
     *  @param rootAbs   Absolute workspace root.
     *  @param scopeAbs  Absolute refresh scope.
     *  @param path      Walked path.
     *  @return          `true` when the path is a regular indexable file.
     */
    private def isCandidate(rootAbs: Path, scopeAbs: Path, path: Path): Boolean =
    {
        if (!Files.isRegularFile(path)) return false
        if (Files.isSymbolicLink(path)) return false
        if (path.getFileName.toString.startsWith(".")) return false
        val rel = rootAbs.relativize(path)
        if (rel.iterator().asScala.exists(seg => TextExtractor.isSkippedDir(seg.toString))) return false
        TextExtractor.isSupported(path)
    }

    /**
     *  Purges rows for files that disappeared from the refreshed scope.
     *  @param rootAbs   Absolute workspace root.
     *  @param rootId    Normalised root identifier.
     *  @param scopeAbs  Absolute refresh scope.
     *  @param existing  Paths currently indexed for this root.
     *  @return          Number of rows deleted.
     */
    private def purgeMissing(rootAbs: Path, rootId: String, scopeAbs: Path,
                             existing: Set[String]): Int =
    {
        var deleted = 0
        for (rel <- existing)
        {
            val abs = rootAbs.resolve(rel)
            val inScope = abs.startsWith(scopeAbs)
            if (inScope && !Files.exists(abs))
            {
                index.deleteSource(SourceKey(IndexKind.Workspace, rootId, None, None, rel))
                deleted += 1
            }
        }
        deleted
    }

    /**
     *  Converted Markdown sibling for a raw office document, if the naming
     *  convention applies.
     *  @param path  Source document path.
     *  @return      Sibling `<stem>.md` path, or `None` for non-documents.
     */
    private def convertedSibling(path: Path): Option[Path] =
    {
        if (!TextExtractor.isDocument(path)) return None
        val name = path.getFileName.toString
        val stem = name.substring(0, name.lastIndexOf('.'))
        Some(path.getParent.resolve(stem + ".md"))
    }

    /**
     *  Normalised identifier for a workspace root, used as the index `root_id`.
     *  @param rootAbs  Absolute workspace root.
     *  @return         Absolute path string.
     */
    private def rootIdOf(rootAbs: Path): String = rootAbs.toString

    /**
     *  Workspace-relative path with forward slashes for stable display and keys.
     *  @param rootAbs  Absolute workspace root.
     *  @param path     Absolute path inside the root.
     *  @return         Relative path such as `"reports/q3.md"`.
     */
    private def relPath(rootAbs: Path, path: Path): String =
        rootAbs.relativize(path).toString.replace('\\', '/')
}
