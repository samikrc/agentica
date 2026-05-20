package agentica.tools.files

import agentica.shell.PathSandbox
import agentica.tools.{ArgError, ArgSpec, CommandSchema, ExecutionContext, ToolResult, ToolStatus, FilesError}
import agentica.tools.Tool
import java.nio.file.{Files, NoSuchFileException}
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

/**
 *  Validated input for [[FilesStat]].
 *  @param rawPath  Raw path string as supplied in the command argument.
 */
case class FilesStatInput(rawPath: String)

/**
 *  Raw output of [[FilesStat.execute]].
 *  @param sourcePath   Path relative to the workspace root.
 *  @param sizeBytes    File size in bytes.
 *  @param lastModified ISO-8601 last-modified timestamp.
 *  @param fileType     `"file"`, `"directory"`, or `"other"`.
 *  @param error        Non-empty if execution failed.
 */
case class FilesStatOutput(
    sourcePath:   String,
    sizeBytes:    Long,
    lastModified: String,
    fileType:     String,
    error:        Option[FilesError] = None
)

/**
 *  Implements the `files.stat` command.
 *  Returns the size, last-modified time, and type of a workspace file or directory.
 */
object FilesStat extends Tool[FilesStatInput, FilesStatOutput]
{

    /**
     *  Canonical tool name.
     */
    val name: String = "files.stat"

    /**
     *  Argument schema for help generation and system-prompt tool index.
     */
    val schema: CommandSchema = CommandSchema(
        fullName = "files.stat",
        summary  = "Return size, modified time, and type of a workspace file or directory",
        args     = List(
            ArgSpec("path", "Relative path to the file or directory within the workspace", required = true)
        ),
        example  = """files.stat path=src/main.scala"""
    )

    /**
     *  Validates the `path` argument.
     *  @param args  Raw key-value argument map from the tokenizer.
     *  @return      Validated input or an [[ArgError]].
     */
    def validate(args: Map[String, String]): Either[ArgError, FilesStatInput] =
    {
        args.get("path") match
        {
            case None          => Left(ArgError("Missing required argument: path", Some("path")))
            case Some(rawPath) => Right(FilesStatInput(rawPath))
        }
    }

    /**
     *  Reads file attributes from the filesystem.
     *  @param input  Validated input.
     *  @param ctx    Runtime execution context.
     *  @return       Raw stat output.
     */
    def execute(input: FilesStatInput, ctx: ExecutionContext): FilesStatOutput =
    {
        val rootStr = ctx.session.rootPath.getOrElse("")
        PathSandbox.resolve(rootStr, input.rawPath) match
        {
            case Left(_) =>
                FilesStatOutput(input.rawPath, 0, "", "", Some(FilesError.PathEscaped))
            case Right(resolved) =>
                val rootPath   = java.nio.file.Paths.get(rootStr).toAbsolutePath.normalize()
                val sourcePath = rootPath.relativize(resolved).toString
                try
                {
                    val attrs    = Files.readAttributes(resolved, classOf[BasicFileAttributes])
                    val fileType = if (attrs.isDirectory) "directory"
                                   else if (attrs.isRegularFile) "file"
                                   else "other"
                    FilesStatOutput(
                        sourcePath   = sourcePath,
                        sizeBytes    = attrs.size(),
                        lastModified = Instant.ofEpochMilli(attrs.lastModifiedTime().toMillis).toString,
                        fileType     = fileType,
                        error        = None
                    )
                }
                catch
                {
                    case _: NoSuchFileException =>
                        FilesStatOutput(sourcePath, 0, "", "", Some(FilesError.NotFound))
                    case ex: Exception =>
                        FilesStatOutput(sourcePath, 0, "", "", Some(FilesError.IoError(ex.getMessage)))
                }
        }
    }

    /**
     *  Converts raw stat output to a [[ToolResult]].
     *  @param output  Raw output from [[execute]].
     *  @return        Typed [[ToolResult]] ready for [[agentica.shell.Presentation]].
     */
    def render(output: FilesStatOutput, ctx: ExecutionContext): ToolResult =
    {
        output.error match
        {
            case Some(FilesError.PathEscaped) =>
                ToolResult(status = ToolStatus.Err(
                    code    = FilesError.PathEscaped.toErrorCode,
                    message = s"Path escapes workspace: ${output.sourcePath}",
                    hints   = List("Use a path within the workspace root.")
                ))
            case Some(FilesError.NotFound) =>
                ToolResult(status = ToolStatus.Err(
                    code    = FilesError.NotFound.toErrorCode,
                    message = s"Not found: ${output.sourcePath}",
                    hints   = List("Check the path is correct and within the workspace.")
                ))
            case Some(FilesError.IoError(msg)) =>
                ToolResult(status = ToolStatus.Err(code = "internal_error", message = msg))
            case None =>
                ToolResult(
                    status   = ToolStatus.Ok,
                    metadata = Map(
                        "path"     -> output.sourcePath,
                        "type"     -> output.fileType,
                        "size"     -> f"${output.sizeBytes / 1024.0}%.1f KB",
                        "modified" -> output.lastModified
                    )
                )
        }
    }
}
