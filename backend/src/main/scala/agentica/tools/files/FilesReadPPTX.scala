package agentica.tools.files

import agentica.doc.{PPTXSlideRenderer, PageVisionTranscriber}
import agentica.observability.TraceLogger
import agentica.shell.{PathSandbox, Presentation, ScratchEntry}
import agentica.tools.{ArgError, ArgSpec, CommandSchema, ErrorCode, ExecutionContext, FilesError, Tool, ToolBody, ToolResult, ToolStatus}
import java.nio.file.{Files, Paths}

/**
 *  Validated input for [[FilesReadPPTX]].
 *  @param path           Relative path to the PPTX file within the workspace.
 *  @param enrichImages   If true (default), run PageVisionTranscriber on each slide. If false, return stub Markdown.
 */
case class FilesReadPPTXInput(path: java.nio.file.Path, enrichImages: Boolean)

/**
 *  Raw output of [[FilesReadPPTX.execute]].
 *  @param content    Markdown content (from PageVisionTranscriber or stub).
 *  @param slideCount Number of slides in the PPTX.
 *  @param sizeBytes  File size in bytes.
 *  @param sourcePath Relative source path (for metadata).
 *  @param error      None on success; Some(error) on failure.
 */
case class FilesReadPPTXOutput(
    content:    String,
    slideCount: Int,
    sizeBytes:  Long,
    sourcePath: String,
    error:      Option[FilesError] = None
)

/**
 *  Implements `files.read_pptx` — reads a PPTX file via Vision-First ingestion.
 *
 *  Pipeline:
 *  1. Validate path (sandbox check)
 *  2. Check if active LLMProvider supports vision
 *  3. Render PPTX slides to PNG images via [[PPTXSlideRenderer]] (Apache POI XSLF)
 *  4. Transcribe each slide via [[PageVisionTranscriber]] → Markdown
 *  5. Assemble with `---` separators; route to scratchpad if >8000 chars
 *
 *  Supports `enrich_images=false` to skip vision and return stub Markdown.
 */
object FilesReadPPTX extends Tool[FilesReadPPTXInput, FilesReadPPTXOutput]
{
    val name: String = "files.read_pptx"

    val schema: CommandSchema = CommandSchema(
        fullName = name,
        summary  = "Read a PPTX file and convert it to Markdown using Vision LLM",
        args     = List(
            ArgSpec("path",           "Relative path to the PPTX file", required = true),
            ArgSpec("enrich_images", "Run vision LLM on slides (default: true)", required = false, default = Some("true"))
        ),
        example  = """files.read_pptx path=presentation.pptx"""
    )

    def validate(args: Map[String, String]): Either[ArgError, FilesReadPPTXInput] =
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
                Right(FilesReadPPTXInput(Paths.get(rawPath), enrichImages))
        }
    }

    def execute(input: FilesReadPPTXInput, ctx: ExecutionContext): FilesReadPPTXOutput =
    {
        val rootStr = ctx.session.rootPath.getOrElse("")
        PathSandbox.resolve(rootStr, input.path.toString) match
        {
            case Left(_) =>
                FilesReadPPTXOutput("", 0, 0, input.path.toString, Some(FilesError.PathEscaped))
            case Right(resolved) =>
                val sourcePath = Paths.get(rootStr).toAbsolutePath.normalize()
                    .relativize(resolved).toString
                readPPTX(input, ctx, resolved, sourcePath)
        }
    }

    private def readPPTX(
        input:      FilesReadPPTXInput,
        ctx:        ExecutionContext,
        resolved:   java.nio.file.Path,
        sourcePath: String
    ): FilesReadPPTXOutput =
    {
        try
        {
            if (!Files.exists(resolved))
            {
                return FilesReadPPTXOutput("", 0, 0, sourcePath, Some(FilesError.NotFound))
            }

            val sizeBytes = Files.size(resolved)

            if (input.enrichImages && !ctx.llmProvider.supportsVision)
            {
                return FilesReadPPTXOutput(
                    "",
                    0,
                    sizeBytes,
                    sourcePath,
                    Some(FilesError.IoError(
                        "Vision LLM required for PPTX ingestion. Configure a multimodal provider (e.g., GPT-4 Vision, Claude 3) in Settings."
                    ))
                )
            }

            TraceLogger.info(ctx.traceId, "files_read_pptx_render", Map("path" -> sourcePath))
            val images     = PPTXSlideRenderer.renderToImages(resolved)
            val slideCount = images.size

            val markdown = if (input.enrichImages)
            {
                PageVisionTranscriber.transcribe(images, ctx.llmProvider, ctx.traceId)
            }
            else
            {
                PageVisionTranscriber.stubMarkdown(slideCount)
            }

            FilesReadPPTXOutput(markdown, slideCount, sizeBytes, sourcePath)
        }
        catch
        {
            case t: Throwable =>
                val msg = Option(t.getMessage).getOrElse(t.getClass.getName)
                TraceLogger.error(ctx.traceId, "files_read_pptx_error", Map("error" -> msg))
                FilesReadPPTXOutput("", 0, 0, sourcePath, Some(FilesError.IoError(msg)))
        }
    }

    def render(output: FilesReadPPTXOutput, ctx: ExecutionContext): ToolResult =
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
                    hints   = List("For PPTX reading, ensure the file is a valid PPTX and a Vision LLM is configured.")
                ))
            case None =>
                val entry = ScratchEntry(
                    content      = output.content,
                    sizeBytes    = output.content.length.toLong,
                    lineCount    = output.slideCount,
                    sourcePath   = output.sourcePath,
                    lastModified = System.currentTimeMillis(),
                    storedAt     = System.currentTimeMillis()
                )
                val ref = ctx.scratchpad.store(s"__pptx_${output.sourcePath}__", entry)

                val body = if (output.content.length <= Presentation.BODY_BUDGET_CHARS)
                    ToolBody.Inline(output.content)
                else
                    ToolBody.ScratchRef(
                        ref        = ref,
                        sourcePath = output.sourcePath,
                        sizeBytes  = output.content.length.toLong,
                        lineCount  = output.slideCount
                    )

                ToolResult(
                    status   = ToolStatus.Ok,
                    metadata = Map(
                        "slides" -> output.slideCount.toString,
                        "size"   -> f"${output.sizeBytes / 1024.0}%.1f KB",
                        "stored" -> ref
                    ),
                    body = Some(body)
                )
        }
    }
}
