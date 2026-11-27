package agentica.search

import agentica.observability.TraceLogger
import java.nio.file.{Files, Path}
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*

/**
 *  Outcome of one incremental refresh pass.
 *  @param indexed    Files (re)indexed in this pass.
 *  @param skipped    Files already current, superseded, or unindexable.
 *  @param pending    Files that still need indexing when the pass stopped —
 *                    never merely unexamined ones, so `pending == 0` means the
 *                    scope is fully indexed.
 *  @param deleted    Stale rows purged because the file no longer exists.
 *  @param elapsedMs  Wall-clock duration of the pass.
 *  @param partial    `true` when `pending > 0` or the walk hit its file cap —
 *                    recall is incomplete for this pass.
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
    /**
     *  Files enumerated per pass before stopping (walk safety cap).
     *
     *  Discovery is cheap — metadata only, no content reads — so the cap exists
     *  purely to bound walk time on pathological trees. Hitting it marks the
     *  pass partial; it does not create recall holes for smaller workspaces
     *  because every discovered candidate is considered each pass.
     */
    val MaxFilesPerPass: Int = 5000

    /** Default soft time budget per pass, in milliseconds. */
    val DefaultBudgetMs: Long = 2500L

    /** Indexing decision for one candidate file. */
    private sealed trait Plan
    /** Row is current, including its supersede state; nothing to do. */
    private case object UpToDate  extends Plan
    /** Converted sibling is fresher; write the atomic supersede marker. */
    private case object Supersede extends Plan
    /** Extract text and index (or refresh) the content rows. */
    private case object Extract   extends Plan

    /**
     *  Refreshes the index for a workspace scope.
     *
     *  Discovery and indexing are separate phases: the pass first enumerates
     *  every candidate in the scope, classifies each against the persisted
     *  metadata in memory, and only then spends the time budget on files that
     *  actually need work. Because current files cost only a map lookup,
     *  repeated passes converge — never-indexed files are always prioritised
     *  and no file is permanently starved by walk order.
     *
     *  @param root       Workspace root (the session `rootPath`).
     *  @param scopeDir   Subtree to refresh, or `None` for the whole workspace.
     *  @param budgetMs   Soft time budget; the pass stops early when exceeded.
     *  @param onProgress Called as `(processed, total)` while indexing.
     *  @return           Per-pass counters; `partial` when work remains or the walk was truncated.
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

        // 1. Walk the whole scope, collecting candidate files. Discovery is
        //    metadata-only and cheap; extraction is budgeted separately below.
        val candidates = ListBuffer.empty[Path]
        var walkTruncated = false
        val stream     = Files.walk(scopeAbs)
        try
        {
            val it = stream.iterator().asScala
            while (it.hasNext && !walkTruncated)
            {
                val path = it.next()
                if (candidates.size >= MaxFilesPerPass) walkTruncated = true
                else if (isCandidate(rootAbs, scopeAbs, path) &&
                    Files.size(path) <= TextExtractor.MaxFileBytes)
                {
                    candidates += path
                }
            }
        }
        finally
        {
            stream.close()
        }

        // 2. Classify every candidate against the persisted metadata in memory.
        //    Currency includes the supersede state: a row whose alias no longer
        //    matches reality needs work even when (mtime, size) is unchanged.
        val planned = candidates.toList.map { p =>
            val rel     = relPath(rootAbs, p)
            val meta    = existing.get(rel)
            val mtime   = Files.getLastModifiedTime(p).toMillis
            val size    = Files.size(p)
            val sibling = convertedSibling(p).filter(mdPath =>
                Files.exists(mdPath) && Files.getLastModifiedTime(mdPath).toMillis >= mtime)
            val mdRel   = sibling.map(md => relPath(rootAbs, md))
            val plan    = (meta, mdRel) match
            {
                case (Some(m), Some(md))
                    if m.lastModified == mtime && m.sizeBytes == size && m.replacedBy.contains(md) =>
                    UpToDate
                case (Some(m), None)
                    if m.lastModified == mtime && m.sizeBytes == size && m.replacedBy.isEmpty =>
                    UpToDate
                case (_, Some(_)) => Supersede
                case (_, None)    => Extract
            }
            (p, rel, mtime, size, mdRel, plan, meta)
        }

        // 3. Order work: cheap text files first, then raw documents (extraction
        //    is ~150-200 ms/page); within each class never-indexed first, then
        //    stalest, so every pass makes forward progress.
        val work = planned.filter(_._6 != UpToDate).sortBy { case (p, _, _, _, _, _, meta) =>
            val docRank = if (TextExtractor.isDocument(p)) 1 else 0
            (docRank, meta.map(_.indexedAt).getOrElse(Long.MinValue))
        }

        // 4. Index until the budget elapses.
        var indexed   = 0
        var skipped   = planned.count(_._6 == UpToDate)
        var processed = 0
        val total     = work.size
        var outOfBudget = false
        for ((path, rel, mtime, size, mdRel, plan, _) <- work if !outOfBudget)
        {
            if (System.currentTimeMillis() - startedAt > budgetMs)
            {
                outOfBudget = true
            }
            else
            {
                processed += 1
                val key = SourceKey(IndexKind.Workspace, rootId, None, None, rel)
                plan match
                {
                    case Supersede =>
                        index.supersede(key, mtime, size, mdRel.get)
                        skipped += 1
                    case Extract   =>
                        TextExtractor.extract(path) match
                        {
                            case Some(text) =>
                                index.index(key, text, mtime, size)
                                indexed += 1
                            case None =>
                                // Write a zero-chunk marker keyed by (mtime, size)
                                // so unchanged unindexable files are cheaply
                                // skipped on later passes instead of retried forever.
                                index.index(key, "", mtime, size)
                                skipped += 1
                        }
                    case UpToDate  => skipped += 1
                }
                onProgress(processed, total)
            }
        }
        val pending = total - processed

        // 5. Purge rows whose file no longer exists inside the refreshed scope.
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
        RefreshStats(indexed, skipped, pending, deleted, elapsed, pending > 0 || walkTruncated)
    }

    /**
     *  Whether a walked path should be considered for indexing.
     *
     *  Skips directories, dotfiles, symlinks, and excluded directory names.
     *  Textual eligibility is content-based: known extensions are a fast path,
     *  unknown and extensionless files pass when they sniff as non-binary, so
     *  `.rs`, `.toml`, `Dockerfile` and scripts are indexed too.
     *
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
        TextExtractor.mayContainText(path)
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
