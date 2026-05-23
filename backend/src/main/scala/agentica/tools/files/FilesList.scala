package agentica.tools.files

import agentica.shell.{PathSandbox, Presentation}
import agentica.tools.{ArgError, ArgSpec, CommandSchema, ErrorCode, ExecutionContext, FilesError, ToolBody, ToolResult, ToolStatus}
import agentica.tools.Tool
import java.nio.file.{Files, NoSuchFileException, Path}
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import scala.jdk.CollectionConverters.*

/**
 *  Validated input for [[FilesList]].
 *  @param rawPath   Raw path argument, defaulting to the workspace root.
 *  @param recursive Whether to descend into subdirectories.
 *  @param all       Whether to include dotfiles.
 *  @param depth     Maximum directory depth to traverse.
 *  @param pattern   Optional glob pattern to filter file names.
 */
case class FilesListInput(
    rawPath:   String,
    recursive: Boolean,
    all:       Boolean,
    depth:     Int,
    pattern:   Option[String]
)

/**
 *  Raw output of [[FilesList.execute]].
 *  @param lines    Formatted listing lines, one per entry.
 *  @param entries  Total number of entries listed.
 *  @param dirs     Number of directory entries.
 *  @param files    Number of file entries.
 *  @param error    Non-empty if execution failed.
 */
case class FilesListOutput(
    lines:   List[String],
    entries: Int,
    dirs:    Int,
    files:   Int,
    error:   Option[FilesError] = None
)

/**
 *  Implements the `files.list` command.
 *  Produces an indented tree listing of workspace files using `java.nio.file` APIs.
 */
object FilesList extends Tool[FilesListInput, FilesListOutput]
{

    /**
     *  Canonical tool name.
     */
    val name: String = "files.list"

    /**
     *  Argument schema for help generation and system-prompt tool index.
     */
    val schema: CommandSchema = CommandSchema(
        fullName = "files.list",
        summary  = "List workspace files as an indented tree",
        args     = List(
            ArgSpec("path",      "Root path to list (default: workspace root)",         required = false),
            ArgSpec("recursive", "Descend into subdirectories (default: false)",         required = false),
            ArgSpec("all",       "Include dotfiles (default: false)",                    required = false),
            ArgSpec("depth",     "Maximum directory depth (default: 3)",                 required = false),
            ArgSpec("pattern",   "Glob pattern to filter file names (default: none)",    required = false)
        ),
        example  = """files.list path=src/ recursive=true depth=2"""
    )

    /**
     *  Validates listing arguments, applying defaults where omitted.
     *  @param args  Raw key-value argument map from the tokenizer.
     *  @return      Validated input or an [[ArgError]].
     */
    def validate(args: Map[String, String]): Either[ArgError, FilesListInput] =
    {
        val depth = args.get("depth") match
        {
            case None    => Right(3)
            case Some(d) => d.toIntOption match
            {
                case Some(n) if n >= 1 => Right(n)
                case _                 => Left(ArgError(s"depth must be a positive integer, got: '$d'", Some("depth")))
            }
        }
        depth.map(d => FilesListInput(
            rawPath   = args.getOrElse("path", "."),
            recursive = args.getOrElse("recursive", "false").toLowerCase == "true",
            all       = args.getOrElse("all", "false").toLowerCase == "true",
            depth     = d,
            pattern   = args.get("pattern")
        ))
    }

    /**
     *  Walks the directory tree and builds the listing lines.
     *  @param input  Validated input.
     *  @param ctx    Runtime execution context.
     *  @return       Raw listing output.
     */
    def execute(input: FilesListInput, ctx: ExecutionContext): FilesListOutput =
    {
        val rootStr = ctx.session.rootPath.getOrElse("")
        PathSandbox.resolve(rootStr, input.rawPath) match
        {
            case Left(_) =>
                FilesListOutput(Nil, 0, 0, 0, Some(FilesError.PathEscaped))
            case Right(startPath) =>
                try
                {
                    val matcher = input.pattern.map(p =>
                        startPath.getFileSystem.getPathMatcher(s"glob:$p")
                    )
                    val maxDepth = if (input.recursive) input.depth else 1
                    val buf      = scala.collection.mutable.ListBuffer.empty[String]
                    var dirCount  = 0
                    var fileCount = 0

                    def walk(dir: Path, indent: Int): Unit =
                    {
                        if (indent > maxDepth) return
                        val entries = Files.list(dir).iterator().asScala.toList
                            .filter(p => input.all || !p.getFileName.toString.startsWith("."))
                            .sortWith { (a, b) =>
                                val aDir = Files.isDirectory(a)
                                val bDir = Files.isDirectory(b)
                                if (aDir == bDir) a.getFileName.toString < b.getFileName.toString
                                else aDir
                            }
                        entries.foreach { entry =>
                            val name = entry.getFileName.toString
                            val isDir = Files.isDirectory(entry)
                            val matchOk = matcher.forall(m => isDir || m.matches(entry.getFileName))
                            if (matchOk)
                            {
                                if (isDir)
                                {
                                    dirCount += 1
                                    buf += s"${"  " * indent}$name/"
                                    if (input.recursive) walk(entry, indent + 1)
                                }
                                else
                                {
                                    fileCount += 1
                                    val attrs = Files.readAttributes(entry, classOf[BasicFileAttributes])
                                    val size  = f"${attrs.size() / 1024.0}%.1f KB"
                                    val date  = Instant.ofEpochMilli(attrs.lastModifiedTime().toMillis)
                                        .toString.take(10)
                                    buf += f"${"  " * indent}$name%-30s  $size%8s  $date"
                                }
                            }
                        }
                    }

                    buf += s"${input.rawPath.stripSuffix("/")}/"
                    walk(startPath, 1)
                    FilesListOutput(buf.toList, dirCount + fileCount, dirCount, fileCount, None)
                }
                catch
                {
                    case _: NoSuchFileException =>
                        FilesListOutput(Nil, 0, 0, 0, Some(FilesError.NotFound))
                    case ex: Exception =>
                        FilesListOutput(Nil, 0, 0, 0, Some(FilesError.IoError(ex.getMessage)))
                }
        }
    }

    /**
     *  Converts raw listing output to a [[ToolResult]].
     *  @param output  Raw output from [[execute]].
     *  @return        Typed [[ToolResult]] ready for [[agentica.shell.Presentation]].
     */
    def render(output: FilesListOutput, ctx: ExecutionContext): ToolResult =
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
                    message = "Directory not found",
                    hints   = List("Check the path is correct and within the workspace.")
                ))
            case Some(FilesError.IoError(msg)) =>
                ToolResult(status = ToolStatus.Err(code = ErrorCode.InternalError, message = msg))
            case None =>
                val text     = output.lines.mkString("\n")
                val metadata = Map(
                    "entries" -> output.entries.toString,
                    "dirs"    -> output.dirs.toString,
                    "files"   -> output.files.toString
                )
                val body = if (text.length <= Presentation.BODY_BUDGET_CHARS)
                {
                    ToolBody.Inline(text)
                }
                else
                {
                    ToolBody.Inline(text.take(Presentation.BODY_BUDGET_CHARS) + "\n... (truncated)")
                }
                ToolResult(status = ToolStatus.Ok, metadata = metadata, body = Some(body))
        }
    }
}
