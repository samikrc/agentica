package agentica.tools.files

import agentica.doc.{DOCXPageRenderer, PageVisionTranscriber, DocToolUnavailableException}
import agentica.observability.TraceLogger
import agentica.shell.{PathSandbox, Presentation, ScratchEntry}
import agentica.tools.{ArgError, ArgSpec, CommandSchema, ErrorCode, ExecutionContext, FilesError, Tool, ToolBody, ToolResult, ToolStatus}
import java.nio.file.{Files, Paths}

/**
 *  Validated input for [[FilesReadDOCX]].
 *  @param path           Relative path to the DOCX file within the workspace.
 *  @param enrichImages   If true (default), run PageVisionTranscriber on each page. If false, return stub Markdown.
 */
case class FilesReadDOCXInput(path: java.nio.file.Path, enrichImages: Boolean)

/**
 *  Raw output of [[FilesReadDOCX.execute]].
 *  @param content    Markdown content (from PageVisionTranscriber or stub).
 *  @param pageCount  Number of pages rendered from the DOCX.
 *  @param sizeBytes  File size in bytes.
 *  @param sourcePath Relative source path (for metadata).
 *  @param error      None on success; Some(error) on failure.
 */
case class FilesReadDOCXOutput(
    content:    String,
    pageCount:  Int,
    sizeBytes:  Long,
    sourcePath: String,
    error:      Option[FilesError] = None
)

/**
 *  Implements `files.read_docx` — reads a DOCX file via Vision-First ingestion.
 *
 *  Pipeline:
 *  1. Validate path (sandbox check)
 *  2. Check if active LLMProvider supports vision
 *  3. Check if LibreOffice is available
 *  4. Render DOCX pages to PNG images via [[DOCXPageRenderer]] (LibreOffice headless)
 *  5. Transcribe each page via [[PageVisionTranscriber]] → Markdown
 *  6. Assemble with `---` separators; route to scratchpad if >8000 chars
 *
 *  Supports `enrich_images=false` to skip vision and return stub Markdown.
 */
object FilesReadDOCX extends Tool[FilesReadDOCXInput, FilesReadDOCXOutput]
{
    val name: String = "files.read_docx"

    val schema: CommandSchema = CommandSchema(
        fullName = name,
        summary  = "Read a DOCX file and convert it to Markdown using Vision LLM (requires LibreOffice)",
        args     = List(
            ArgSpec("path",           "Relative path to the DOCX file", required = true),
            ArgSpec("enrich_images", "Run vision LLM on pages (default: true)", required = false, default = Some("true"))
        ),
        example  = """files.read_docx path=report.docx"""
    )

    def validate(args: Map[String, String]): Either[ArgError, FilesReadDOCXInput] =
    {
        args.get("path") match
        {
            case None =>
                Left(ArgError("Missing required argument: path", Some("path")))
            case Some(rawPath) =>
                val enrichImages = args.getOrElse("enrich_images", "true").toLowerCase match
                {
                    case "false" | "0" | "no" => false
                    case _ => true
                }
                Right(FilesReadDOCXInput(Paths.get(rawPath), enrichImages))
        }
    }

    def execute(input: FilesReadDOCXInput, ctx: ExecutionContext): FilesReadDOCXOutput =
    {
        val rootStr = ctx.session.rootPath.getOrElse("")
        PathSandbox.resolve(rootStr, input.path.toString) match
        {
            case Left(_) =>
                FilesReadDOCXOutput("", 0, 0, input.path.toString, Some(FilesError.PathEscaped))
            case Right(resolved) =>
                val sourcePath = Paths.get(rootStr).toAbsolutePath.normalize()
                    .relativize(resolved).toString
                readDOCX(input, ctx, resolved, sourcePath)
        }
    }

    private def readDOCX(
        input:      FilesReadDOCXInput,
        ctx:        ExecutionContext,
        resolved:   java.nio.file.Path,
        sourcePath: String
    ): FilesReadDOCXOutput =
    {
        try
        {
            if (!Files.exists(resolved))
            {
                return FilesReadDOCXOutput("", 0, 0, sourcePath, Some(FilesError.NotFound))
            }

            val sizeBytes = Files.size(resolved)

            if (input.enrichImages && !ctx.llmProvider.supportsVision)
            {
                return FilesReadDOCXOutput(
                    "",
                    0,
                    sizeBytes,
                    sourcePath,
                    Some(FilesError.IoError(
                        "Vision LLM required for DOCX ingestion. Configure a multimodal provider (e.g., GPT-4 Vision, Claude 3) in Settings."
                    ))
                )
            }

            TraceLogger.info(ctx.traceId, "files_read_docx_render", Map("path" -> sourcePath))
            val images    = DOCXPageRenderer.renderToImages(resolved)
            val pageCount = images.size

            val markdown = if (input.enrichImages)
            {
                PageVisionTranscriber.transcribe(images, ctx.llmProvider, ctx.traceId)
            }
            else
            {
                PageVisionTranscriber.stubMarkdown(pageCount)
            }

            FilesReadDOCXOutput(markdown, pageCount, sizeBytes, sourcePath)
        }
        catch
        {
            case t: Throwable =>
                val msg = Option(t.getMessage).getOrElse(t.getClass.getName)
                TraceLogger.error(ctx.traceId, "files_read_docx_error", Map("error" -> msg))
                FilesReadDOCXOutput("", 0, 0, sourcePath, Some(FilesError.IoError(msg)))
        }
    }

    def render(output: FilesReadDOCXOutput, ctx: ExecutionContext): ToolResult =
    {
        output.error match
        {
            case Some(FilesError.PathEscaped) =>
                ToolResult(status = ToolStatus.Err(
                    code    = ErrorCode.PathEscaped,
                    message = s"Path escapes workspace: ${output.sourcePath}",
                    hints   = List("Use a path within the workspace root.")
                ))
            case Some(FilesError.NotFound) =>
                ToolResult(status = ToolStatus.Err(
                    code    = ErrorCode.NotFound,
                    message = s"File not found: ${output.sourcePath}",
                    hints   = List("Check the path is correct and within the workspace.")
                ))
            case Some(FilesError.IoError(msg)) =>
                ToolResult(status = ToolStatus.Err(
                    code    = ErrorCode.InternalError,
                    message = msg,
                    hints   = List(
                        "For DOCX reading, ensure:",
                        "1. The file is a valid DOCX",
                        "2. A Vision LLM is configured",
                        "3. LibreOffice is installed (see deps.check for install instructions)"
                    )
                ))
            case None =>
                val entry = ScratchEntry(
                    content      = output.content,
                    sizeBytes    = output.content.length.toLong,
                    lineCount    = output.pageCount,
                    sourcePath   = output.sourcePath,
                    lastModified = System.currentTimeMillis(),
                    storedAt     = System.currentTimeMillis()
                )
                val ref = ctx.scratchpad.store(s"__docx_${output.sourcePath}__", entry)

                val body = if (output.content.length <= Presentation.BODY_BUDGET_CHARS)
                    ToolBody.Inline(output.content)
                else
                    ToolBody.ScratchRef(
                        ref        = ref,
                        sourcePath = output.sourcePath,
                        sizeBytes  = output.content.length.toLong,
                        lineCount  = output.pageCount
                    )

                ToolResult(
                    status   = ToolStatus.Ok,
                    metadata = Map(
                        "pages"  -> output.pageCount.toString,
                        "size"   -> f"${output.sizeBytes / 1024.0}%.1f KB",
                        "stored" -> ref
                    ),
                    body = Some(body)
                )
        }
    }
}
