package agentica.tools.files

import agentica.agent.AgentEvent
import agentica.permissions.GrantDecision
import agentica.shell.PathSandbox
import agentica.tools.{ArgError, ArgSpec, CommandSchema, ErrorCode, ExecutionContext, FilesError, ToolResult, ToolStatus}
import agentica.tools.Tool
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, NoSuchFileException, StandardOpenOption}
import java.util.concurrent.TimeUnit


/**
 *  Validated input for [[FilesWrite]].
 *  @param rawPath  Raw path argument as supplied by the agent.
 *  @param content  Text content to write.
 */
case class FilesWriteInput(rawPath: String, content: String)

/**
 *  Raw output of [[FilesWrite.execute]].
 *  @param sourcePath    Path relative to the workspace root.
 *  @param bytesWritten  Number of bytes written to disk.
 *  @param error         `None` on success; `Some(e)` on failure.
 */
case class FilesWriteOutput(
    sourcePath:   String,
    bytesWritten: Long,
    error:        Option[FilesError] = None
)

/**
 *  Implements the `files.write` command.
 *  Sensitive tool — requires user permission via the UI modal before executing.
 *  Emits [[AgentEvent.PermissionRequired]] and blocks on `ctx.permissionLatch`
 *  for up to 60 seconds when no valid grant exists.
 */
object FilesWrite extends Tool[FilesWriteInput, FilesWriteOutput]
{

    /**
     *  Canonical tool name.
     */
    val name: String = "files.write"

    /**
     *  Argument schema for help generation and system-prompt tool index.
     */
    val schema: CommandSchema = CommandSchema(
        fullName = "files.write",
        summary  = "Write text content to a workspace file (requires user permission)",
        args     = List(
            ArgSpec("path",    "Relative path to the file within the workspace", required = true),
            ArgSpec("content", "Text content to write to the file",              required = true)
        ),
        example  = """files.write path=notes.md content="# My Notes"""
    )

    /**
     *  Validates the `path` and `content` arguments.
     *  @param args  Raw key-value argument map from the tokenizer.
     *  @return      Validated input or an [[ArgError]].
     */
    def validate(args: Map[String, String]): Either[ArgError, FilesWriteInput] =
    {
        val path    = args.get("path")
        val content = args.get("content")
        (path, content) match
        {
            case (None, _)    => Left(ArgError("Missing required argument: path",    Some("path")))
            case (_, None)    => Left(ArgError("Missing required argument: content", Some("content")))
            case (Some(p), Some(c)) => Right(FilesWriteInput(p, c))
        }
    }

    /**
     *  Checks permissions, then writes the file.
     *  Emits [[AgentEvent.PermissionRequired]] and blocks for up to 60 seconds
     *  if no valid grant exists; returns a `permission_denied` error on timeout or denial.
     *  @param input  Validated input.
     *  @param ctx    Runtime execution context.
     *  @return       Raw write output.
     */
    def execute(input: FilesWriteInput, ctx: ExecutionContext): FilesWriteOutput =
    {
        val rootStr = ctx.session.rootPath.getOrElse("")
        PathSandbox.resolve(rootStr, input.rawPath) match
        {
            case Left(_) =>
                FilesWriteOutput(input.rawPath, 0, Some(FilesError.PathEscaped))
            case Right(resolved) =>
                val rootPath   = java.nio.file.Paths.get(rootStr).toAbsolutePath.normalize()
                val sourcePath = rootPath.relativize(resolved).toString
                checkPermissionAndWrite(input, ctx, resolved, sourcePath)
        }
    }

    /**
     *  Checks for an existing grant and prompts the user if none exists,
     *  then delegates to [[writeFile]] on approval.
     *  @param input      Validated write input.
     *  @param ctx        Runtime execution context.
     *  @param resolved   Resolved absolute path within the sandbox.
     *  @param sourcePath Path relative to the workspace root.
     *  @return           Write output with byte count or a [[FilesWriteError]].
     */
    private def checkPermissionAndWrite(
        input:      FilesWriteInput,
        ctx:        ExecutionContext,
        resolved:   java.nio.file.Path,
        sourcePath: String
    ): FilesWriteOutput =
    {
        if (ctx.scopeStore.hasGrant(ctx.session.id, name, resolved.toString))
        {
            writeFile(input, ctx, resolved, sourcePath)
        }
        else
        {
            ctx.onEvent(AgentEvent.PermissionRequired(
                tool    = name,
                path    = Some(sourcePath),
                options = List("Allow once", "Allow for session", "Allow always", "Deny")
            ))
            Option(ctx.permissionLatch.poll(60, TimeUnit.SECONDS)) match
            {
                case None =>
                    FilesWriteOutput(sourcePath, 0, Some(FilesError.PermissionDenied))
                case Some(GrantDecision.Denied) =>
                    FilesWriteOutput(sourcePath, 0, Some(FilesError.PermissionDenied))
                case Some(granted: GrantDecision.Granted) =>
                    ctx.scopeStore.addGrant(ctx.session.id, name, granted)
                    writeFile(input, ctx, resolved, sourcePath)
            }
        }
    }

    /**
     *  Performs the filesystem write; called only after permission is confirmed.
     *  @param input      Validated write input.
     *  @param ctx        Runtime execution context.
     *  @param resolved   Resolved absolute path within the sandbox.
     *  @param sourcePath Path relative to the workspace root.
     *  @return           Write output with byte count, or [[FilesError.IoError]] on failure.
     */
    private def writeFile(
        input:      FilesWriteInput,
        ctx:        ExecutionContext,
        resolved:   java.nio.file.Path,
        sourcePath: String
    ): FilesWriteOutput =
    {
        try
        {
            Files.createDirectories(resolved.getParent)
            val bytes = input.content.getBytes(StandardCharsets.UTF_8)
            Files.write(resolved, bytes,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            )
            val bytesWritten = bytes.length.toLong
            if (ctx.scopeStore.hasGrant(ctx.session.id, name, resolved.toString))
            {
                ctx.scopeStore.consumeOnce(ctx.session.id, name, resolved.toString)
            }
            FilesWriteOutput(sourcePath, bytesWritten, None)
        }
        catch
        {
            case ex: Exception =>
                FilesWriteOutput(sourcePath, 0, Some(FilesError.IoError(ex.getMessage)))
        }
    }

    /**
     *  Converts raw write output to a [[ToolResult]].
     *  @param output  Raw output from [[execute]].
     *  @return        Typed [[ToolResult]] ready for [[agentica.shell.Presentation]].
     */
    def render(output: FilesWriteOutput, ctx: ExecutionContext): ToolResult =
    {
        output.error match
        {
            case Some(FilesError.PathEscaped) =>
                ToolResult(status = ToolStatus.Err(
                    code    = FilesError.PathEscaped.toErrorCode,
                    message = s"Path escapes workspace: ${output.sourcePath}",
                    hints   = List("Use a path within the workspace root.")
                ))
            case Some(FilesError.PermissionDenied) =>
                ToolResult(status = ToolStatus.Err(
                    code    = FilesError.PermissionDenied.toErrorCode,
                    message = s"User denied write permission for: ${output.sourcePath}",
                    hints   = List("files.write requires user approval before executing.")
                ))
            case Some(FilesError.IoError(msg)) =>
                ToolResult(status = ToolStatus.Err(code = ErrorCode.InternalError, message = msg))
            case None =>
                ToolResult(
                    status   = ToolStatus.Ok,
                    metadata = Map(
                        "path"         -> output.sourcePath,
                        "bytesWritten" -> output.bytesWritten.toString
                    )
                )
        }
    }
}
