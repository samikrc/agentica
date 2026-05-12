package agentica.tools.files

import agentica.shell.{PathSandbox, ScratchEntry}
import agentica.tools.{ArgError, ArgSpec, CommandSchema, ExecutionContext, ToolBody, ToolResult, ToolStatus}
import agentica.tools.Tool
import agentica.shell.Presentation
import java.nio.file.{Files, NoSuchFileException}
import java.nio.file.attribute.BasicFileAttributes

/**
 *  Possible failure modes for [[FilesRead.execute]].
 */
enum FilesReadError
{
    /** The resolved path escapes the session workspace root. */
    case PathEscaped

    /** The file does not exist at the resolved path. */
    case NotFound

    /**
     *  An I/O exception occurred during the read.
     *  @param message  Exception message from the underlying I/O layer.
     */
    case IoError(message: String)
}

/**
 *  Validated input for [[FilesRead]].
 *  @param path      Absolute resolved path to the file to read.
 *  @param lineRange Optional `(start, end)` 1-based inclusive line range.
 */
case class FilesReadInput(path: java.nio.file.Path, lineRange: Option[(Int, Int)])

/**
 *  Raw output of [[FilesRead.execute]].
 *  @param content      Full (or range-sliced) text content; meaningful only when `error` is `None`.
 *  @param sizeBytes    File size in bytes.
 *  @param totalLines   Total line count of the full file.
 *  @param truncated    Whether a `lines=` range was applied.
 *  @param sourcePath   Relative source path (relative to rootPath).
 *  @param lastModified File's `lastModifiedTime` at read time (epoch ms).
 *  @param error        `None` on success; `Some(e)` on failure.
 */
case class FilesReadOutput(
    content:      String,
    sizeBytes:    Long,
    totalLines:   Int,
    truncated:    Boolean,
    sourcePath:   String,
    lastModified: Long,
    error:        Option[FilesReadError] = None
)

/**
 *  Implements the `files.read` command.
 *  Reads a workspace file and returns its content inline if it fits within the body
 *  budget, otherwise stores it in the session scratchpad and returns a ref.
 *  Staleness is checked against the scratchpad before re-reading.
 */
object FilesRead extends Tool[FilesReadInput, FilesReadOutput]
{

    /**
     *  Canonical tool name.
     */
    val name: String = "files.read"

    /**
     *  Argument schema for help generation and system-prompt tool index.
     */
    val schema: CommandSchema = CommandSchema(
        fullName = "files.read",
        summary  = "Read a workspace file; large files are stored in the scratchpad",
        args     = List(
            ArgSpec("path",  "Relative path to the file within the workspace", required = true),
            ArgSpec("lines", "Optional line range to read, e.g. 1-50",         required = false)
        ),
        example  = """files.read path=src/main.scala lines=1-50"""
    )

    /**
     *  Validates and resolves the `path` and optional `lines` arguments.
     *  @param args  Raw key-value argument map from the tokenizer.
     *  @return      Validated input or an [[ArgError]].
     */
    def validate(args: Map[String, String]): Either[ArgError, FilesReadInput] =
    {
        args.get("path") match
        {
            case None =>
                Left(ArgError("Missing required argument: path", Some("path")))
            case Some(rawPath) =>
                val lineRange = args.get("lines") match
                {
                    case None => Right(None)
                    case Some(spec) =>
                        spec.split('-') match
                        {
                            case Array(s, e) =>
                                (s.toIntOption, e.toIntOption) match
                                {
                                    case (Some(start), Some(end)) if start >= 1 && end >= start =>
                                        Right(Some((start, end)))
                                    case _ =>
                                        Left(ArgError(s"lines must be in format start-end (e.g. 1-50), got: '$spec'", Some("lines")))
                                }
                            case _ =>
                                Left(ArgError(s"lines must be in format start-end (e.g. 1-50), got: '$spec'", Some("lines")))
                        }
                }
                lineRange.map(lr => FilesReadInput(java.nio.file.Paths.get(rawPath), lr))
        }
    }

    /**
     *  Reads the file, applying the optional line range.
     *  Checks the scratchpad for a fresh cached entry before reading from disk.
     *  @param input  Validated input.
     *  @param ctx    Runtime execution context.
     *  @return       Raw file output.
     */
    def execute(input: FilesReadInput, ctx: ExecutionContext): FilesReadOutput =
    {
        val rootStr  = ctx.session.rootPath.getOrElse("")
        val rootPath = java.nio.file.Paths.get(rootStr).toAbsolutePath.normalize()
        PathSandbox.resolve(rootStr, input.path.toString) match
        {
            case Left(_) =>
                FilesReadOutput("", 0, 0, false, input.path.toString, 0, Some(FilesReadError.PathEscaped))
            case Right(resolved) =>
                val sourcePath = rootPath.relativize(resolved).toString
                readFromDisk(input, ctx, resolved, sourcePath)
        }
    }

    /**
     *  Reads file content from disk, checking the scratchpad cache first.
     *  @param input      Validated read input.
     *  @param ctx        Runtime execution context.
     *  @param resolved   Resolved absolute path within the sandbox.
     *  @param sourcePath Path relative to the workspace root.
     *  @return           Read output with content or a typed error.
     */
    private def readFromDisk(
        input:      FilesReadInput,
        ctx:        ExecutionContext,
        resolved:   java.nio.file.Path,
        sourcePath: String
    ): FilesReadOutput =
    {
        try
        {
            val attrs        = Files.readAttributes(resolved, classOf[BasicFileAttributes])
            val lastModified = attrs.lastModifiedTime().toMillis
            val sizeBytes    = attrs.size()

            // Check scratchpad for a fresh cached entry (no range requested)
            val fromCache: Option[FilesReadOutput] =
                if (input.lineRange.isEmpty && !ctx.scratchpad.isStale(sourcePath, lastModified))
                {
                    ctx.scratchpad.get(s"$$scratch/$sourcePath").map { entry =>
                        FilesReadOutput(
                            content      = entry.content,
                            sizeBytes    = entry.sizeBytes,
                            totalLines   = entry.lineCount,
                            truncated    = false,
                            sourcePath   = sourcePath,
                            lastModified = lastModified
                        )
                    }
                }
                else None

            fromCache match
            {
                case Some(cached) => cached
                case None =>
                    val allLines   = Files.readAllLines(resolved)
                    val totalLines = allLines.size()

                    val (lines, truncated) = input.lineRange match
                    {
                        case None =>
                            (allLines, false)
                        case Some((start, end)) =>
                            val sl = allLines.subList(
                                Math.max(0, start - 1),
                                Math.min(totalLines, end)
                            )
                            (sl, true)
                    }

                    val content = lines.toArray.mkString("\n")
                    FilesReadOutput(
                        content      = content,
                        sizeBytes    = sizeBytes,
                        totalLines   = totalLines,
                        truncated    = truncated,
                        sourcePath   = sourcePath,
                        lastModified = lastModified
                    )
            }
        }
        catch
        {
            case _: NoSuchFileException =>
                FilesReadOutput("", 0, 0, false, sourcePath, 0, Some(FilesReadError.NotFound))
            case ex: Exception =>
                FilesReadOutput("", 0, 0, false, sourcePath, 0, Some(FilesReadError.IoError(ex.getMessage)))
        }
    }

    /**
     *  Converts raw output to a [[ToolResult]], routing large bodies to the scratchpad.
     *  Must be called with the [[ExecutionContext]] available — for scratchpad routing
     *  the caller (`CommandRegistry`) passes the output through; scratchpad storage
     *  is handled here via a post-render check in `VirtualShell`.
     *  @param output  Raw output from [[execute]].
     *  @return        Typed [[ToolResult]] ready for [[agentica.shell.Presentation]].
     */
    def render(output: FilesReadOutput): ToolResult =
    {
        output.error match
        {
            case Some(FilesReadError.PathEscaped) =>
                ToolResult(status = ToolStatus.Err(
                    code    = "path_escaped",
                    message = s"Path escapes workspace: ${output.sourcePath}",
                    hints   = List("Use a path within the workspace root.")
                ))
            case Some(FilesReadError.NotFound) =>
                ToolResult(status = ToolStatus.Err(
                    code    = "not_found",
                    message = s"File not found: ${output.sourcePath}",
                    hints   = List("Check the path is correct and within the workspace.")
                ))
            case Some(FilesReadError.IoError(msg)) =>
                ToolResult(status = ToolStatus.Err(code = "internal_error", message = msg))
            case None =>
                val rangeNote = if (output.truncated) " · partial" else ""
                val metadata  = Map(
                    "size"  -> f"${output.sizeBytes / 1024.0}%.1f KB",
                    "lines" -> s"${output.totalLines}$rangeNote"
                )
                val body = if (output.content.length <= Presentation.BODY_BUDGET_CHARS)
                {
                    ToolBody.Inline(output.content)
                }
                else
                {
                    ToolBody.ScratchRef(
                        ref        = s"$$scratch/${output.sourcePath}",
                        sourcePath = output.sourcePath,
                        sizeBytes  = output.sizeBytes,
                        lineCount  = output.totalLines
                    )
                }
                ToolResult(status = ToolStatus.Ok, metadata = metadata, body = Some(body))
        }
    }
}
