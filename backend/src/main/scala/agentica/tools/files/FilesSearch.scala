package agentica.tools.files

import agentica.agent.AgentEvent
import agentica.search.{IndexKind, IndexScope, WorkspaceIndexer}
import agentica.shell.{PathSandbox, Presentation, ScratchEntry}
import agentica.tools.{ArgError, ArgSpec, CommandSchema, ErrorCode, ExecutionContext, FilesError, ToolBody, ToolResult, ToolStatus}
import agentica.tools.Tool
import java.nio.file.{Files, NoSuchFileException, Path}
import scala.collection.immutable.ListMap
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*
import scala.util.matching.Regex

/**
 *  Validated input for [[FilesSearch]].
 *  @param query    Search text; quoted phrases are honoured by both arms.
 *  @param rawPath  Workspace subtree or file to scope the search.
 *  @param scope    `workspace` (default) or `stored` (this run's scratch content).
 *  @param ref      Stored entry to search, e.g. `$scratch/__result_1__`; implies `stored` scope.
 *  @param limit    Maximum hits rendered per page (safety cap; the body budget usually binds first).
 *  @param cursor   Paging offset into the fused result list.
 *  @param mode      `hybrid` (default), `exact` (grep arm only), or `ranked` (BM25 arm only).
 *  @param useRegex  Treat `query` as a raw regex for the exact arm.
 *  @param recursive Descend into subdirectories of `path` (default true).
 *  @param include   Glob pattern matched against file names (default: no filter).
 */
case class FilesSearchInput(
    query:     String,
    rawPath:   Option[String],
    scope:     String,
    ref:       Option[String],
    limit:     Int,
    cursor:    Int,
    mode:      String,
    useRegex:  Boolean,
    recursive: Boolean,
    include:   Option[String]
)

/**
 *  One rendered search hit.
 *  @param sourcePath  Workspace-relative path or `$scratch/<key>` for stored entries.
 *  @param startLine   First line of the matched range (1-based).
 *  @param endLine     Last line of the matched range (1-based).
 *  @param score       Fused RRF score; only comparable within one result set.
 *  @param snippet     Excerpt shown to the model.
 *  @param origin      Which arms contributed: `exact`, `ranked`, or `exact+ranked`.
 */
case class SearchHitView(
    sourcePath: String,
    startLine:  Int,
    endLine:    Int,
    score:      Double,
    snippet:    String,
    origin:     String
)

/**
 *  Raw output of [[FilesSearch.execute]].
 *  @param hits        Hits rendered for the requested page.
 *  @param total       Size of the fused result list (capped).
 *  @param totalCapped Whether the fused list hit the internal ceiling.
 *  @param cursor      Offset this page started at.
 *  @param scope       Scope that was searched.
 *  @param mode        Mode that was applied.
 *  @param indexNote   Refresh summary when the index pass was partial.
 *  @param moreCommand Exact next call for the following page, when one exists.
 *  @param error       `None` on success; `Some(e)` on failure.
 */
case class FilesSearchOutput(
    hits:        List[SearchHitView],
    total:       Int,
    totalCapped: Boolean,
    cursor:      Int,
    scope:       String,
    mode:        String,
    indexNote:   Option[String],
    moreCommand: Option[String],
    error:       Option[FilesError] = None
)

/**
 *  Implements `files_search` as hybrid lexical retrieval.
 *
 *  Two arms run over the selected corpus and are fused with reciprocal rank
 *  fusion: an exact arm (line grep with context and table expansion) and a
 *  BM25 arm over the FTS5 chunk index. `scope=workspace` refreshes the index
 *  incrementally before searching; `scope=stored` searches this run's
 *  scratchpad entries and their indexed rows. Results share one hit shape and
 *  page by character budget with a `For more entries, use:` continuation line.
 */
object FilesSearch extends Tool[FilesSearchInput, FilesSearchOutput]
{
    /**
     *  Canonical tool name.
     */
    val name: String = "files_search"

    /** Cap on extra context lines added when expanding a match to its enclosing table. */
    private val MaxTableExpansionLines = 60

    /** Ceiling on the fused result list; displayed as `200+` when reached. */
    private val MaxFusedHits = 200

    /** Files examined by the exact arm per call. */
    private val MaxScanFiles = 2000

    /** Bytes examined by the exact arm per call. */
    private val MaxScanBytes = 50L * 1024 * 1024

    /** Reciprocal rank fusion constant. */
    private val RrfK = 60

    /** Maximum snippet lines kept per hit. */
    private val SnippetMaxLines = 12

    /**
     *  Argument schema for help generation and system-prompt tool index.
     */
    val schema: CommandSchema = CommandSchema(
        fullName = name,
        summary  = "Hybrid search (exact + ranked) over workspace files or stored content",
        args     = List(
            ArgSpec("query",  "Search terms; quoted phrases supported",                        required = true),
            ArgSpec("path",   "Workspace path scope (subtree or file)",                        required = false),
            ArgSpec("scope",  "workspace (default) or stored (this run's scratch content)",    required = false),
            ArgSpec("ref",    "Stored entry to search, e.g. $scratch/__result_1__; implies scope=stored", required = false),
            ArgSpec("limit",  "Maximum hits per page (default 8)",                             required = false),
            ArgSpec("cursor", "Paging offset into the result list (default 0)",                required = false),
            ArgSpec("mode",      "hybrid (default), exact, or ranked",                         required = false),
            ArgSpec("regex",     "Treat query as a raw regex for the exact arm",               required = false),
            ArgSpec("recursive", "Descend into subdirectories (default: true)",                required = false),
            ArgSpec("include",   "Glob pattern matched against file names (default: none)",    required = false)
        ),
        example  = """files_search query="revenue growth" path=reports/"""
    )

    /**
     *  Validates search arguments and the scope/ref/path matrix.
     *  @param args  Raw key-value argument map from the tokenizer.
     *  @return      Validated input or an [[ArgError]].
     */
    def validate(args: Map[String, String]): Either[ArgError, FilesSearchInput] =
    {
        val query = args.get("query").map(_.trim).filter(_.nonEmpty)
        if (query.isEmpty) return Left(ArgError("Missing required argument: query", Some("query")))

        val ref    = args.get("ref").map(_.trim).filter(_.nonEmpty)
        val path   = args.get("path").map(_.trim).filter(_.nonEmpty)
        val scope  = args.get("scope").map(_.trim.toLowerCase)
            .getOrElse(if (ref.isDefined) "stored" else "workspace")
        val mode   = args.get("mode").map(_.trim.toLowerCase).getOrElse("hybrid")
        val limit  = args.get("limit").flatMap(_.toIntOption).getOrElse(8).max(1).min(50)
        val cursor = args.get("cursor").flatMap(_.toIntOption).getOrElse(0).max(0)

        if (!Set("workspace", "stored").contains(scope))
        {
            Left(ArgError(s"Invalid scope '$scope'; use workspace or stored", Some("scope")))
        }
        else if (!Set("hybrid", "exact", "ranked").contains(mode))
        {
            Left(ArgError(s"Invalid mode '$mode'; use hybrid, exact, or ranked", Some("mode")))
        }
        else if (scope == "workspace" && ref.isDefined)
        {
            Left(ArgError("ref implies scope=stored; omit scope or set scope=stored",
                Some("ref"), hints = List("Use scope=stored with ref=, or drop ref= for a workspace search.")))
        }
        else if (scope == "workspace" && path.exists(_.startsWith("$scratch")))
        {
            Left(ArgError("path must be a workspace path; use scope=stored or ref= for scratch content",
                Some("path"), hints = List("Example: files_search query=\"...\" scope=stored")))
        }
        else if (scope == "stored" && path.isDefined)
        {
            Left(ArgError("path is not valid with scope=stored; use ref= to target a stored entry",
                Some("path")))
        }
        else
        {
            Right(FilesSearchInput(
                query    = query.get,
                rawPath  = path,
                scope    = scope,
                ref      = ref,
                limit    = limit,
                cursor   = cursor,
                mode      = mode,
                useRegex  = args.getOrElse("regex", "false").toLowerCase == "true",
                recursive = args.getOrElse("recursive", "true").toLowerCase != "false",
                include   = args.get("include").map(_.trim).filter(_.nonEmpty)
            ))
        }
    }

    /**
     *  Runs the selected arms over the selected corpus and fuses the results.
     *  @param input  Validated input.
     *  @param ctx    Runtime execution context.
     *  @return       Raw search output ready for [[render]].
     */
    def execute(input: FilesSearchInput, ctx: ExecutionContext): FilesSearchOutput =
    {
        try
        {
            if (input.scope == "stored") searchStored(input, ctx)
            else searchWorkspace(input, ctx)
        }
        catch
        {
            case _: NoSuchFileException =>
                FilesSearchOutput(Nil, 0, false, input.cursor, input.scope, input.mode, None, None,
                    Some(FilesError.NotFound))
            case ex: Exception =>
                FilesSearchOutput(Nil, 0, false, input.cursor, input.scope, input.mode, None, None,
                    Some(FilesError.IoError(ex.getMessage)))
        }
    }

    /**
     *  Formats the result page for LLM consumption, routing oversized pages to
     *  the scratchpad.
     *  @param output  Raw output from [[execute]].
     *  @param ctx     Runtime execution context for scratchpad storage.
     *  @return        Typed [[ToolResult]] ready for [[agentica.shell.Presentation]].
     */
    def render(output: FilesSearchOutput, ctx: ExecutionContext): ToolResult =
    {
        output.error match
        {
            case Some(FilesError.PathEscaped) =>
                ToolResult(status = ToolStatus.Err(
                    code    = FilesError.PathEscaped.toErrorCode,
                    message = "Path escapes workspace",
                    hints   = List("Use a path within the workspace root.")
                ))
            case Some(FilesError.NotFound) =>
                ToolResult(status = ToolStatus.Err(
                    code    = FilesError.NotFound.toErrorCode,
                    message = "Search path not found",
                    hints   = List("Check the path is correct and within the workspace.")
                ))
            case Some(FilesError.IoError(msg)) =>
                ToolResult(status = ToolStatus.Err(code = ErrorCode.InternalError, message = msg))
            case Some(error) =>
                ToolResult(status = ToolStatus.Err(code = error.toErrorCode, message = s"File search failed: ${error.toErrorCode.value}"))
            case None =>
                val bodyText = buildBody(output)
                val metadata = ListMap(
                    "Displaying" -> s"${output.hits.size} of ${output.total}${if (output.totalCapped) "+" else ""}",
                    "cursor"     -> output.cursor.toString,
                    "scope"      -> output.scope,
                    "mode"       -> output.mode
                ) ++ output.indexNote.map("index" -> _)

                if (bodyText.length <= Presentation.BODY_BUDGET_CHARS)
                {
                    ToolResult(
                        status   = ToolStatus.Ok,
                        metadata = metadata,
                        body     = Some(ToolBody.Inline(bodyText))
                    )
                }
                else
                {
                    val key   = ctx.scratchpad.nextComputedKey()
                    val now   = System.currentTimeMillis()
                    val entry = ScratchEntry(
                        content      = bodyText,
                        sizeBytes    = bodyText.length.toLong,
                        lineCount    = bodyText.count(_ == '\n') + 1,
                        sourcePath   = key,
                        lastModified = now,
                        storedAt     = now
                    )
                    val ref = ctx.scratchpad.store(key, entry)
                    ToolResult(
                        status   = ToolStatus.Ok,
                        metadata = metadata + ("stored" -> ref),
                        body     = Some(ToolBody.ScratchRef(
                            ref        = ref,
                            sourcePath = key,
                            sizeBytes  = bodyText.length.toLong,
                            lineCount  = bodyText.count(_ == '\n') + 1
                        ))
                    )
                }
        }
    }

    // ── Workspace scope ──────────────────────────────────────────────────────

    /**
     *  Refreshes and searches the workspace corpus.
     *  @param input  Validated input.
     *  @param ctx    Runtime execution context.
     *  @return       Paged search output.
     */
    private def searchWorkspace(input: FilesSearchInput, ctx: ExecutionContext): FilesSearchOutput =
    {
        val rootStr = ctx.session.rootPath.getOrElse("")
        PathSandbox.resolve(rootStr, input.rawPath.getOrElse(".")) match
        {
            case Left(_) =>
                FilesSearchOutput(Nil, 0, false, input.cursor, input.scope, input.mode, None, None,
                    Some(FilesError.PathEscaped))
            case Right(rawStart) =>
                val startPath = redirectedMarkdown(rawStart)
                val rootAbs   = java.nio.file.Paths.get(rootStr).toAbsolutePath.normalize()
                val scopeAbs  = if (Files.isRegularFile(startPath)) startPath.getParent else startPath
                val rootId    = rootAbs.toString
                val scope     = IndexScope(IndexKind.Workspace, rootId, None, None)

                // Result scope pushed into the ranked query: exact match covers a
                // file path, prefix match covers a directory subtree. `None` when
                // the search spans the whole workspace root.
                val startRel     = rootAbs.relativize(startPath).toString.replace('\\', '/')
                val pathConstraint =
                    if (startRel.isEmpty || startRel == ".") None else Some(startRel)

                // Incremental refresh before searching; progress is advisory.
                // mode=exact is the grep-only escape hatch and pays no indexing cost.
                val refreshStats = if (ctx.searchIndex.isAvailable && input.mode != "exact")
                    Some(new WorkspaceIndexer(ctx.searchIndex).refresh(rootAbs, Some(scopeAbs),
                        onProgress = (done, total) =>
                            ctx.onEvent(AgentEvent.ToolProgress(name,
                                s"Indexing: $done / ${math.max(total, 1)} files", done, math.max(total, 1)))))
                else None
                val indexNote = refreshStats.filter(_.partial).map(s =>
                    s"partial · indexed: ${s.indexed} · pending: ${s.pending}")

                val exactHits =
                    if (input.mode == "ranked") Nil
                    else exactArm(input, rootAbs, startPath, None)
                val rankedHits = rankedArm(input, ctx, scope, None, pathConstraint)

                // Apply depth and filename filters to the fused set so both arms
                // honour them identically.
                val (fused, capped) = fuse(exactHits, rankedHits)
                val filtered        = fused.filter { case (h, _) =>
                    passesFilters(h, rootAbs, scopeAbs, input)
                }
                buildOutput(input, (filtered, capped), indexNote)
        }
    }

    /**
     *  Redirects a raw document path to its converted Markdown sibling when the
     *  sibling exists and is at least as new as the document — the same
     *  freshness rule the indexer applies for superseding, so a stale `.md`
     *  left behind by an older conversion does not shadow the document.
     *  @param path  Resolved start path.
     *  @return      The `.md` sibling when present and fresh, otherwise the original path.
     */
    private def redirectedMarkdown(path: Path): Path =
    {
        val lower = path.getFileName.toString.toLowerCase
        val isDoc = Files.isRegularFile(path) &&
            (lower.endsWith(".pdf") || lower.endsWith(".docx") || lower.endsWith(".pptx"))
        if (!isDoc) return path
        val name = path.getFileName.toString
        val md   = name.substring(0, name.lastIndexOf('.')) + ".md"
        val mdPath = path.getParent.resolve(md)
        val fresh = Files.exists(mdPath) &&
            Files.getLastModifiedTime(mdPath).toMillis >= Files.getLastModifiedTime(path).toMillis
        if (fresh) mdPath else path
    }

    // ── Stored scope ─────────────────────────────────────────────────────────

    /**
     *  Searches scratch content: the exact arm covers entries currently in the
     *  pad, the ranked arm covers persisted rows — including file-backed rows
     *  from earlier runs whose pad entries are gone.
     *
     *  The two corpora differ deliberately: pad contents are per-run and exact,
     *  indexed rows are session-durable but only searchable through BM25. A
     *  `ref=` drill-down into a missing pad entry re-hydrates file-backed refs
     *  from disk so the exact arm still applies; computed keys have no backing
     *  file and rely on the ranked arm.
     *
     *  @param input  Validated input.
     *  @param ctx    Runtime execution context.
     *  @return       Paged search output.
     */
    private def searchStored(input: FilesSearchInput, ctx: ExecutionContext): FilesSearchOutput =
    {
        val entries: List[(String, ScratchEntry)] =
            input.ref match
            {
                case Some(r) =>
                    ctx.scratchpad.get(r).orElse(
                        agentica.shell.ScratchHydration.hydrate(r, ctx.scratchpad,
                            ctx.session.rootPath.getOrElse(""))
                    ).map(e => r.stripPrefix("$scratch/") -> e).toList
                case None    => ctx.scratchpad.entriesSnapshot
            }

        val pattern    = buildPattern(input)
        val exactHits  = if (input.mode == "ranked") Nil
                         else entries.flatMap { case (key, e) => exactHitsInText(key, e.content, pattern) }
        val rankedHits = if (input.mode == "exact") Nil
                         else
                             try storedRanked(input, ctx)
                             catch
                             {
                                 case ex: Exception =>
                                     agentica.observability.TraceLogger.warn(ctx.traceId,
                                         "stored_ranked_failed", Map("error" -> String.valueOf(ex.getMessage)))
                                     Nil
                             }
        buildOutput(input, fuse(exactHits, rankedHits), None)
    }

    /**
     *  Ranked arm over stored rows: the session's file-backed rows plus the
     *  current run's computed rows, optionally narrowed to one entry.
     *  @param input  Validated input.
     *  @param ctx    Runtime execution context.
     *  @return       Raw ranked hits with `$scratch/` display paths.
     */
    private def storedRanked(input: FilesSearchInput, ctx: ExecutionContext): List[RawHit] =
    {
        if (!ctx.searchIndex.isAvailable) return Nil
        val expr = ftsExpr(input)
        if (expr.isEmpty) return Nil

        val scopes = input.ref match
        {
            case Some(r) => List(IndexScope(IndexKind.Stored, "", Some(ctx.session.id), runFor(r.stripPrefix("$scratch/"), ctx)))
            case None    => List(
                IndexScope(IndexKind.Stored, "", Some(ctx.session.id), None),
                IndexScope(IndexKind.Stored, "", Some(ctx.session.id), Some(ctx.traceId)))
        }

        // A drill-down constraint goes into the query itself so chunks from
        // other entries cannot crowd out the drilled entry under the hit ceiling.
        val drillKey = input.ref.map(_.stripPrefix("$scratch/"))
        scopes.flatMap(s => ctx.searchIndex.search(s, expr, MaxFusedHits, drillKey))
            .filter(h => input.ref.forall(r => r == s"$$scratch/${h.sourcePath}"))
            .distinctBy(h => (h.sourcePath, h.chunkIndex))
            .map(h => RawHit(
                sourcePath = s"$$scratch/${h.sourcePath}",
                startLine  = h.startLine,
                endLine    = h.endLine,
                snippet    = h.snippet,
                exact      = false,
                ranked     = true
            ))
    }

    // ── Arms ─────────────────────────────────────────────────────────────────

    /**
     *  Exact (grep) arm: line matches with context, table expansion, and range
     *  merging. Searches text files on disk; raw office documents are served by
     *  the ranked arm.
     *  @param input     Validated input.
     *  @param rootAbs   Absolute workspace root.
     *  @param startPath File or directory to scan.
     *  @param displayPrefix Optional display prefix (unused for workspace).
     *  @return          Raw exact hits with score 1.0 semantics.
     */
    private def exactArm(input: FilesSearchInput, rootAbs: Path, startPath: Path,
                         displayPrefix: Option[String]): List[RawHit] =
    {
        val pattern = buildPattern(input)
        val out     = ListBuffer.empty[RawHit]
        var files   = 0
        var bytes   = 0L
        val maxDepth = if (input.recursive) Int.MaxValue else 1
        val stream   = Files.walk(startPath, maxDepth)
        try
        {
            val it = stream.iterator().asScala
            while (it.hasNext && files < MaxScanFiles && bytes < MaxScanBytes && out.size < MaxFusedHits)
            {
                val file = it.next()
                if (isScannable(file))
                {
                    val size = try Files.size(file) catch { case _: Throwable => 0L }
                    files += 1
                    bytes += size
                    val rel = rootAbs.relativize(file).toString.replace('\\', '/')
                    val display = displayPrefix.map(p => s"$p$rel").getOrElse(rel)
                    val lines =
                        try Files.readAllLines(file).asScala.toIndexedSeq
                        catch { case _: Exception => IndexedSeq.empty }
                    out ++= hitsInLines(display, lines, pattern)
                }
            }
        }
        finally
        {
            stream.close()
        }
        out.toList
    }

    /**
     *  Exact hits within one stored entry's text.
     *  @param key      Scratch key of the entry.
     *  @param text     Entry content.
     *  @param pattern  Compiled match pattern.
     *  @return         Raw exact hits with `$scratch/` display paths.
     */
    private def exactHitsInText(key: String, text: String, pattern: Regex): List[RawHit] =
    {
        val lines = text.split("\n", -1).toIndexedSeq
        hitsInLines(s"$$scratch/$key", lines, pattern)
    }

    /**
     *  Finds matches in a line buffer and returns merged, context-expanded ranges.
     *  @param display  Display path for the source.
     *  @param lines    Source lines.
     *  @param pattern  Compiled match pattern.
     *  @return         One hit per merged range.
     */
    private def hitsInLines(display: String, lines: IndexedSeq[String], pattern: Regex): List[RawHit] =
    {
        if (lines.isEmpty) return Nil
        val matchIdx = lines.indices.filter(i => pattern.findFirstIn(lines(i)).isDefined)
        if (matchIdx.isEmpty) return Nil

        val ranges = ListBuffer.empty[(Int, Int)]
        for (idx <- matchIdx)
        {
            var start = math.max(0, idx - 2)
            var end   = math.min(lines.length - 1, idx + 2)
            if ((start to end).exists(i => lines(i).trim.startsWith("|")))
            {
                var extra = 0
                while (start > 0 && extra < MaxTableExpansionLines && lines(start - 1).trim.startsWith("|"))
                {
                    start -= 1
                    extra += 1
                }
                while (end < lines.length - 1 && extra < MaxTableExpansionLines && lines(end + 1).trim.startsWith("|"))
                {
                    end += 1
                    extra += 1
                }
            }
            ranges += start -> end
        }

        val merged = ListBuffer.empty[(Int, Int)]
        ranges.sorted.foreach { case (s, e) =>
            if (merged.nonEmpty && s <= merged.last._2 + 1)
                merged.update(merged.length - 1, (merged.last._1, math.max(merged.last._2, e)))
            else merged += ((s, e))
        }

        merged.toList.map { case (s, e) =>
            val snippet = lines.slice(s, math.min(e + 1, s + SnippetMaxLines)).mkString("\n")
            RawHit(
                sourcePath = display,
                startLine  = s + 1,
                endLine    = e + 1,
                snippet    = snippet,
                exact      = true,
                ranked     = false
            )
        }
    }

    /**
     *  Ranked arm: BM25 over the chunk index, with AND-first and OR fallback.
     *
     *  Failures degrade to no ranked hits rather than failing the whole search:
     *  the exact arm's results must survive a malformed-expression or index
     *  error in this arm.
     *
     *  @param input         Validated input.
     *  @param ctx           Runtime execution context.
     *  @param scope         Index scope to search.
     *  @param prefix        Optional display prefix for hits.
     *  @param pathConstraint Optional workspace-relative path constraint pushed into the query.
     *  @return              Raw ranked hits.
     */
    private def rankedArm(input: FilesSearchInput, ctx: ExecutionContext, scope: IndexScope,
                          prefix: Option[String], pathConstraint: Option[String] = None): List[RawHit] =
    {
        if (!ctx.searchIndex.isAvailable || input.mode == "exact") return Nil
        val andExpr = ftsExpr(input)
        if (andExpr.isEmpty) return Nil
        val orExpr  = ftsExpr(input, "OR")

        val hits =
            try
            {
                val andHits = ctx.searchIndex.search(scope, andExpr, MaxFusedHits, pathConstraint)
                if (andHits.size >= input.limit || andExpr == orExpr) andHits
                else (andHits ++ ctx.searchIndex.search(scope, orExpr, MaxFusedHits, pathConstraint))
                    .distinctBy(h => (h.sourcePath, h.chunkIndex))
            }
            catch
            {
                case ex: Exception =>
                    agentica.observability.TraceLogger.warn(ctx.traceId, "ranked_arm_failed",
                        Map("error" -> String.valueOf(ex.getMessage)))
                    Nil
            }

        hits.map(h => RawHit(
            sourcePath = prefix.map(p => s"$p${h.sourcePath}").getOrElse(h.sourcePath),
            startLine  = h.startLine,
            endLine    = h.endLine,
            snippet    = h.snippet,
            exact      = false,
            ranked     = true
        ))
    }

    // ── Fusion, paging, rendering ────────────────────────────────────────────

    /**
     *  One candidate hit before fusion.
     *  @param sourcePath Display path.
     *  @param startLine  First line of the range.
     *  @param endLine    Last line of the range.
     *  @param snippet    Excerpt.
     *  @param exact      Whether the exact arm produced it.
     *  @param ranked     Whether the ranked arm produced it.
     */
    private case class RawHit(sourcePath: String, startLine: Int, endLine: Int,
                              snippet: String, exact: Boolean, ranked: Boolean)

    /**
     *  Fuses the two arms with reciprocal rank fusion and merges overlapping
     *  ranges within the same source.
     *  @param exactHits   Exact-arm hits in arm order.
     *  @param rankedHits  Ranked-arm hits in arm order.
     *  @return            Fused `(hit, score)` pairs best first capped at [[MaxFusedHits]], and whether the cap was exceeded.
     */
    private def fuse(exactHits: List[RawHit], rankedHits: List[RawHit]): (List[(RawHit, Double)], Boolean) =
    {
        val exactRank  = exactHits.zipWithIndex.map { case (h, i) => h -> i }.toMap
        val rankedRank = rankedHits.zipWithIndex.map { case (h, i) => h -> i }.toMap

        /**
         *  Reciprocal-rank contribution of one raw hit across both arms.
         *  @param h  Hit to score.
         *  @return   Sum of the hit's RRF weights; 0 when it came from neither arm.
         */
        def contribution(h: RawHit): Double =
        {
            val e = exactRank.get(h).map(i => 1.0 / (RrfK + i + 1)).getOrElse(0.0)
            val r = rankedRank.get(h).map(i => 1.0 / (RrfK + i + 1)).getOrElse(0.0)
            e + r
        }

        val fused = ListBuffer.empty[(RawHit, Double)]
        for ((source, hits) <- (exactHits ++ rankedHits).groupBy(_.sourcePath))
        {
            val groups = ListBuffer.empty[ListBuffer[RawHit]]
            for (h <- hits.sortBy(_.startLine))
            {
                groups.lastOption match
                {
                    case Some(g) if h.startLine <= g.map(_.endLine).max + 2 => g += h
                    case _                                                  => groups += ListBuffer(h)
                }
            }
            for (g <- groups)
            {
                val snippet = g.find(_.exact).map(_.snippet).getOrElse(g.head.snippet)
                val merged = RawHit(
                    sourcePath = source,
                    startLine  = g.map(_.startLine).min,
                    endLine    = g.map(_.endLine).max,
                    snippet    = snippet,
                    exact      = g.exists(_.exact),
                    ranked     = g.exists(_.ranked)
                )
                fused += merged -> g.map(contribution).sum
            }
        }
        val ordered = fused.sortBy { case (_, score) => -score }.toList
        (ordered.take(MaxFusedHits), ordered.size > MaxFusedHits)
    }

    /**
     *  Builds the paged output from a fused hit list.
     *  @param input      Validated input.
     *  @param fused      Fused `(hit, score)` pairs, best first.
     *  @param indexNote  Refresh summary when the index pass was partial.
     *  @return           Paged output including the continuation command.
     */
    private def buildOutput(input: FilesSearchInput, fused: (List[(RawHit, Double)], Boolean),
                            indexNote: Option[String]): FilesSearchOutput =
    {
        val (hits, capped) = fused
        val total          = hits.size
        val page   = hits.drop(input.cursor)

        // Select hits until the body budget is exhausted; always take at least one.
        val budget   = Presentation.BODY_BUDGET_CHARS - 200
        val selected = ListBuffer.empty[(RawHit, Double)]
        var used     = 0
        for ((h, score) <- page if selected.size < input.limit)
        {
            val rendered = renderHit(input.cursor + selected.size + 1, hitView(h, score)).length
            if (selected.isEmpty || used + rendered <= budget)
            {
                selected += h -> score
                used += rendered
            }
        }

        val views = selected.map { case (h, score) => hitView(h, score) }.toList

        val more = if (input.cursor + views.size < total)
            Some(nextCommand(input, input.cursor + views.size))
        else None

        FilesSearchOutput(views, total, capped, input.cursor, input.scope, input.mode, indexNote, more)
    }

    /**
     *  Renders one hit block.
     *  @param rank 1-based display rank.
     *  @param view Hit to render.
     *  @return     Block text.
     */
    private def renderHit(rank: Int, view: SearchHitView): String =
    {
        val header  = f"[$rank] ${view.sourcePath}  lines ${view.startLine}-${view.endLine}  score ${view.score}%.3f  (${view.origin})"
        val snippet = view.snippet.split("\n", -1).map(l => s"    $l").mkString("\n")
        s"$header\n$snippet"
    }

    /**
     *  Converts a raw hit to a display view.
     *  @param h     Raw hit.
     *  @param score Fused score.
     *  @return      Display view.
     */
    private def hitView(h: RawHit, score: Double): SearchHitView =
    {
        val origin =
            if (h.exact && h.ranked) "exact+ranked"
            else if (h.exact) "exact"
            else "ranked"
        SearchHitView(h.sourcePath, h.startLine, h.endLine, score, h.snippet, origin)
    }

    /**
     *  Builds the exact continuation command for the next page.
     *  @param input   Validated input.
     *  @param cursor  Offset for the next page.
     *  @return        `files_search` command string.
     */
    private def nextCommand(input: FilesSearchInput, cursor: Int): String =
    {
        /**
         *  Quotes one argument value for the continuation command.
         *  @param v  Raw argument value.
         *  @return   Double-quoted value with embedded quotes escaped.
         */
        def quoted(v: String): String = "\"" + v.replace("\"", "\\\"") + "\""

        val parts   = ListBuffer(s"query=${quoted(input.query)}")
        input.rawPath.foreach(p => parts += s"path=${quoted(p)}")
        if (input.scope == "stored") parts += "scope=stored"
        input.ref.foreach(r => parts += s"ref=${quoted(r)}")
        if (input.mode != "hybrid") parts += s"mode=${input.mode}"
        if (input.limit != 8) parts += s"limit=${input.limit}"
        if (input.useRegex) parts += "regex=true"
        if (!input.recursive) parts += "recursive=false"
        input.include.foreach(g => parts += s"include=${quoted(g)}")
        parts += s"cursor=$cursor"
        s"$name ${parts.mkString(" ")}"
    }

    /**
     *  Renders the body text for one page, including the continuation line.
     *  @param output  Paged output.
     *  @return        Body text.
     */
    private def buildBody(output: FilesSearchOutput): String =
    {
        if (output.hits.isEmpty) return "No matches."
        val blocks = output.hits.zipWithIndex.map { case (v, i) => renderHit(output.cursor + i + 1, v) }
        val body   = blocks.mkString("\n")
        output.moreCommand.map(m => s"$body\nFor more entries, use: $m").getOrElse(body)
    }

    // ── Query preparation ────────────────────────────────────────────────────

    /**
     *  Compiles the exact-arm pattern from the query.
     *  @param input  Validated input.
     *  @return       Case-insensitive regex matching any quoted phrase or term.
     */
    private def buildPattern(input: FilesSearchInput): Regex =
    {
        if (input.useRegex) return s"(?i)${input.query}".r
        val (phrases, terms) = queryParts(input.query)
        val literals         = (phrases ++ terms).distinct
        if (literals.isEmpty) "(?!)".r
        else s"(?i)${literals.map(Regex.quote).mkString("(", "|", ")")}".r
    }

    /**
     *  Builds the FTS5 match expression for the ranked arm.
     *
     *  Every term is emitted as a quoted phrase — `"term"` or `"term"*` for
     *  prefix matching — so user input can never produce FTS5 operators:
     *  uppercase `AND`/`OR`/`NOT` in the query become literal tokens rather
     *  than syntax. Terms are pre-stripped to letters, digits, and underscore,
     *  so no embedded-quote escape is possible.
     *
     *  @param input  Validated input.
     *  @param op     `AND` for the precise pass, `OR` for the relaxed fallback.
     *  @return       Sanitised match expression; empty when nothing usable remains.
     */
    private def ftsExpr(input: FilesSearchInput, op: String = "AND"): String =
    {
        val (phrases, terms) = queryParts(input.query)
        val parts =
            phrases.map(p => "\"" + p.replace("\"", "") + "\"") ++
            terms.map { t =>
                val cleaned = t.replaceAll("[^\\p{L}\\p{N}_]", "")
                if (cleaned.isEmpty) ""
                else if (cleaned.length >= 4) s""""$cleaned"*"""
                else s""""$cleaned""""
            }
        parts.filter(_.nonEmpty).distinct match
        {
            case Nil     => ""
            case List(p) => p
            case ps      => ps.mkString(s" $op ")
        }
    }

    /**
     *  Splits a query into quoted phrases and loose terms.
     *  @param query  Raw query text.
     *  @return       `(phrases, terms)`.
     */
    private def queryParts(query: String): (List[String], List[String]) =
    {
        val phraseR = "\"([^\"]+)\"".r
        val phrases = phraseR.findAllMatchIn(query).map(_.group(1).trim).filter(_.nonEmpty).toList
        val rest    = phraseR.replaceAllIn(query, " ")
        val terms   = rest.split("[,\\s|]+").map(_.trim).filter(_.nonEmpty).distinct.toList
        (phrases, terms)
    }

    /**
     *  Applies the depth and filename filters to one fused hit.
     *
     *  Runs after fusion so the exact and ranked arms are filtered identically:
     *  `recursive=false` drops hits in subdirectories of the search scope, and
     *  `include` keeps only hits whose file name matches the glob.
     *
     *  @param h        Fused hit.
     *  @param rootAbs  Absolute workspace root.
     *  @param scopeAbs Absolute search scope (directory or file).
     *  @param input    Validated input carrying the filters.
     *  @return         `true` when the hit should be kept.
     */
    private def passesFilters(h: RawHit, rootAbs: Path, scopeAbs: Path, input: FilesSearchInput): Boolean =
    {
        if (h.sourcePath.startsWith("$scratch/")) return true
        val scopeRel = rootAbs.relativize(scopeAbs).toString.replace('\\', '/')
        val underScope =
            if (scopeRel.isEmpty || scopeRel == ".") h.sourcePath
            else if (h.sourcePath == scopeRel) Path.of(scopeRel).getFileName.toString
            else if (h.sourcePath.startsWith(scopeRel + "/")) h.sourcePath.drop(scopeRel.length + 1)
            else return false

        if (!input.recursive && underScope.contains("/")) return false
        input.include match
        {
            case Some(glob) =>
                java.nio.file.FileSystems.getDefault
                    .getPathMatcher(s"glob:$glob")
                    .matches(Path.of(underScope).getFileName)
            case None => true
        }
    }

    /**
     *  Whether a walked path should be scanned by the exact arm.
     *
     *  Textual eligibility is content-based, matching the indexer: known
     *  extensions are a fast path, unknown and extensionless files pass when
     *  they sniff as non-binary, so `.rs`, `Makefile` and scripts are greppable.
     *  Office documents are excluded — their binary containers are meaningless
     *  to line grep; the ranked arm serves them from the extracted text layer.
     *
     *  @param path  Walked path.
     *  @return      `true` for regular, non-symlinked text files.
     */
    private def isScannable(path: Path): Boolean =
    {
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) return false
        if (path.getFileName.toString.startsWith(".")) return false
        if (Files.size(path) > agentica.search.TextExtractor.MaxFileBytes) return false
        if (path.iterator().asScala.exists(seg => agentica.search.TextExtractor.isSkippedDir(seg.toString))) return false
        agentica.search.TextExtractor.mayContainText(path) && !agentica.search.TextExtractor.isDocument(path)
    }

    /**
     *  Run qualifier for a scratch key: computed keys are run-scoped.
     *  @param key  Scratch key.
     *  @param ctx  Runtime execution context carrying the run trace id.
     *  @return     The run id for `__result_N__` keys, otherwise `None`.
     */
    private def runFor(key: String, ctx: ExecutionContext): Option[String] =
    {
        if (key.startsWith("__result_")) Some(ctx.traceId) else None
    }
}
