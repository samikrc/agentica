package agentica.tools.files

import agentica.shell.{PathSandbox, Presentation, ScratchEntry}
import agentica.tools.{ArgError, ArgSpec, CommandSchema, ErrorCode, ExecutionContext, FilesError, ToolBody, ToolResult, ToolStatus}
import agentica.tools.Tool
import java.nio.file.{Files, NoSuchFileException, Path}
import scala.jdk.CollectionConverters.*
import scala.util.matching.Regex


/**
 *  Validated input for [[FilesSearch]].
 *  @param rawPath      Root path to search; defaults to workspace root.
 *  @param query        Search term(s). A single literal, a comma/pipe-separated list
 *                      of literals (OR logic), or a regex when `useRegex` is true.
 *  @param recursive    Whether to descend into subdirectories.
 *  @param ignoreCase   Whether the match is case-insensitive.
 *  @param linesContext Number of context lines before and after each match.
 *  @param maxMatches   Maximum number of matches to return.
 *  @param include      Optional glob pattern to filter file names.
 *  @param useRegex     Whether `query` is a raw regex pattern (skips comma/pipe splitting).
 */
case class FilesSearchInput(
    rawPath:      String,
    query:        String,
    recursive:    Boolean,
    ignoreCase:   Boolean,
    linesContext: Int,
    maxMatches:   Int,
    include:      Option[String],
    useRegex:     Boolean
)

/**
 *  Raw output of [[FilesSearch.execute]].
 *  @param blocks     Formatted match blocks, one entry per file match group.
 *  @param matches    Total number of matching lines found.
 *  @param files      Number of files containing at least one match.
 *  @param truncated  Whether the result was cut at `maxMatches`.
 *  @param error      `None` on success; `Some(e)` on failure.
 */
case class FilesSearchOutput(
    blocks:    List[String],
    matches:   Int,
    files:     Int,
    truncated: Boolean,
    error:     Option[FilesError] = None
)

/**
 *  Implements the `files.search` command.
 *  Grep-style line-by-line scan using `java.nio.file` APIs.
 *  Binary files are skipped with a note.
 */
object FilesSearch extends Tool[FilesSearchInput, FilesSearchOutput]
{

    /**
     *  Canonical tool name.
     */
    val name: String = "files.search"

    /**
     *  Argument schema for help generation and system-prompt tool index.
     */
    val schema: CommandSchema = CommandSchema(
        fullName = "files.search",
        summary  = "Grep-style search across workspace files",
        args     = List(
            ArgSpec("query",         "Term(s) to search; comma or pipe-separated for OR logic", required = true),
            ArgSpec("path",          "Root path to search (default: workspace root)",            required = false),
            ArgSpec("recursive",     "Descend into subdirectories (default: true)",              required = false),
            ArgSpec("ignore_case",   "Case-insensitive match (default: false)",                  required = false),
            ArgSpec("lines_context", "Context lines before/after each match (default: 2)",      required = false),
            ArgSpec("max_matches",   "Maximum matches to return (default: 50)",                 required = false),
            ArgSpec("include",       "Glob pattern to filter filenames (default: none)",        required = false),
            ArgSpec("regex",         "Treat query as a raw regex, skipping comma/pipe split",   required = false)
        ),
        example  = """files.search query="revenue,growth,deceleration" path=reports/ lines_context=3"""
    )

    /**
     *  Validates search arguments, applying defaults where omitted.
     *  @param args  Raw key-value argument map from the tokenizer.
     *  @return      Validated input or an [[ArgError]].
     */
    def validate(args: Map[String, String]): Either[ArgError, FilesSearchInput] =
    {
        args.get("query") match
        {
            case None =>
                Left(ArgError("Missing required argument: query", Some("query")))
            case Some(query) =>
                val linesContext = args.get("lines_context").flatMap(_.toIntOption).getOrElse(2)
                val maxMatches   = args.get("max_matches").flatMap(_.toIntOption).getOrElse(50)
                Right(FilesSearchInput(
                    rawPath      = args.getOrElse("path", "."),
                    query        = query,
                    recursive    = args.getOrElse("recursive", "true").toLowerCase != "false",
                    ignoreCase   = args.getOrElse("ignore_case", "false").toLowerCase == "true",
                    linesContext = linesContext,
                    maxMatches   = maxMatches,
                    include      = args.get("include"),
                    useRegex     = args.getOrElse("regex", "false").toLowerCase == "true"
                ))
        }
    }

    /**
     *  Scans the directory tree for the query term in each text file.
     *  @param input  Validated input.
     *  @param ctx    Runtime execution context.
     *  @return       Raw search output.
     */
    def execute(input: FilesSearchInput, ctx: ExecutionContext): FilesSearchOutput =
    {
        val rootStr = ctx.session.rootPath.getOrElse("")
        PathSandbox.resolve(rootStr, input.rawPath) match
        {
            case Left(_) =>
                FilesSearchOutput(Nil, 0, 0, false, Some(FilesError.PathEscaped))
            case Right(startPath) =>
                try
                {
                    val flags   = if (input.ignoreCase) "(?i)" else ""
                    val pattern = if (input.useRegex)
                    {
                        (flags + input.query).r
                    }
                    else
                    {
                        val terms = input.query.split("[,|]").map(_.trim).filter(_.nonEmpty)
                        if (terms.length > 1)
                        {
                            (flags + terms.map(Regex.quote).mkString("(", "|", ")")).r
                        }
                        else
                        {
                            (flags + Regex.quote(input.query)).r
                        }
                    }
                    val rootPath = java.nio.file.Paths.get(rootStr).toAbsolutePath.normalize()

                    val fileMatcher = input.include.map(p =>
                        startPath.getFileSystem.getPathMatcher(s"glob:$p")
                    )

                    val maxDepth  = if (input.recursive) Int.MaxValue else 1
                    val allFiles  = Files.walk(startPath, maxDepth).iterator().asScala.toList
                        .filter(p => Files.isRegularFile(p))
                        .filter(p => fileMatcher.forall(m => m.matches(p.getFileName)))

                    val blocks       = scala.collection.mutable.ListBuffer.empty[String]
                    var totalMatches = 0
                    var fileCount    = 0
                    var truncated    = false

                    allFiles.foreach { file =>
                        if (!truncated)
                        {
                            val relPath = rootPath.relativize(file).toString
                            val lines = try
                            {
                                Files.readAllLines(file).asScala.toIndexedSeq
                            }
                            catch
                            {
                                case _: Exception => IndexedSeq.empty
                            }

                            if (lines.isEmpty) ()
                            else
                            {
                                val matchLines = lines.zipWithIndex.collect {
                                    case (line, idx) if pattern.findFirstIn(line).isDefined => idx
                                }
                                if (matchLines.nonEmpty)
                                {
                                    fileCount += 1
                                    matchLines.foreach { matchIdx =>
                                        if (!truncated)
                                        {
                                            totalMatches += 1
                                            if (totalMatches > input.maxMatches)
                                            {
                                                truncated = true
                                            }
                                            else
                                            {
                                                val start = Math.max(0, matchIdx - input.linesContext)
                                                val end   = Math.min(lines.length - 1, matchIdx + input.linesContext)
                                                val chunk = (start to end).map { i =>
                                                    val prefix = if (i == matchIdx) ">" else " "
                                                    s"$relPath:${i + 1}:$prefix ${lines(i)}"
                                                }
                                                blocks ++= chunk
                                                blocks += "---"
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    FilesSearchOutput(blocks.toList, totalMatches, fileCount, truncated, None)
                }
                catch
                {
                    case _: NoSuchFileException =>
                        FilesSearchOutput(Nil, 0, 0, false, Some(FilesError.NotFound))
                    case ex: Exception =>
                        FilesSearchOutput(Nil, 0, 0, false, Some(FilesError.IoError(ex.getMessage)))
                }
        }
    }

    /**
     *  Formats raw output for LLM consumption, routing large bodies to the scratchpad.
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
            case None =>
                val text        = output.blocks.mkString("\n")
                val computedKey = ctx.scratchpad.nextComputedKey()
                val now         = System.currentTimeMillis()
                val entry = ScratchEntry(
                    content      = text,
                    sizeBytes    = text.length.toLong,
                    lineCount    = output.blocks.length,
                    sourcePath   = computedKey,
                    lastModified = now,
                    storedAt     = now
                )
                val ref          = ctx.scratchpad.store(computedKey, entry)
                val baseMetadata = Map(
                    "matches"   -> output.matches.toString,
                    "files"     -> output.files.toString,
                    "truncated" -> output.truncated.toString
                )
                if (text.length <= Presentation.BODY_BUDGET_CHARS)
                    ToolResult(
                        status   = ToolStatus.Ok,
                        metadata = baseMetadata + ("stored" -> ref),
                        body     = Some(ToolBody.Inline(text))
                    )
                else
                    ToolResult(
                        status   = ToolStatus.Ok,
                        metadata = baseMetadata,
                        body     = Some(ToolBody.ScratchRef(
                            ref        = ref,
                            sourcePath = computedKey,
                            sizeBytes  = text.length.toLong,
                            lineCount  = output.blocks.length
                        ))
                    )
        }
    }
}
