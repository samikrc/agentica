package agentica.tools.files

import agentica.agent.AgentEvent
import agentica.doc.{PDFPageRenderer, PageVisionTranscriber, DocToolDetector}
import agentica.observability.TraceLogger
import agentica.shell.{PathSandbox, Presentation, ScratchEntry}
import agentica.tools.{ArgError, ArgSpec, CommandSchema, ErrorCode, ExecutionContext, FilesError, Tool, ToolBody, ToolResult, ToolStatus}
import java.nio.file.{Files, Paths}

/**
 *  Validated input for [[FilesReadPDF]].
 *  @param path           Relative path to the PDF file within the workspace.
 *  @param enrichImages   If true (default), run PageVisionTranscriber on each page. If false, return stub Markdown.
 */
case class FilesReadPDFInput(path: java.nio.file.Path, enrichImages: Boolean)

/**
 *  Raw output of [[FilesReadPDF.execute]].
 *  @param content    Markdown content (from PageVisionTranscriber or stub).
 *  @param pageCount  Number of pages in the PDF.
 *  @param sizeBytes  File size in bytes.
 *  @param sourcePath Relative source path (for metadata).
 *  @param error      None on success; Some(error) on failure.
 */
case class FilesReadPDFOutput(
    content:    String,
    pageCount:  Int,
    sizeBytes:  Long,
    sourcePath: String,
    error:      Option[FilesError] = None
)

/**
 *  Implements `files.read_pdf` — reads a PDF file via Vision-First ingestion.
 *
 *  Pipeline:
 *  1. Validate path (sandbox check)
 *  2. Check if active LLMProvider supports vision
 *  3. Render PDF pages to PNG images via [[PDFPageRenderer]]
 *  4. Transcribe each page via [[PageVisionTranscriber]] → Markdown
 *  5. Assemble with `---` separators; route to scratchpad if >8000 chars
 *
 *  Supports `enrich_images=false` to skip vision and return stub Markdown
 *  (useful for page counting and file access verification).
 */
object FilesReadPDF extends Tool[FilesReadPDFInput, FilesReadPDFOutput]
{
    val name: String = "files.read_pdf"

    val schema: CommandSchema = CommandSchema(
        fullName = name,
        summary  = "Read a PDF file and convert it to Markdown using Vision LLM",
        args     = List(
            ArgSpec("path",           "Relative path to the PDF file", required = true),
            ArgSpec("enrich_images", "Run vision LLM on pages (default: true)", required = false, default = Some("true"))
        ),
        example  = """files.read_pdf path="My Report.pdf""""
    )

    def validate(args: Map[String, String]): Either[ArgError, FilesReadPDFInput] =
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
                Right(FilesReadPDFInput(Paths.get(rawPath), enrichImages))
        }
    }

    def execute(input: FilesReadPDFInput, ctx: ExecutionContext): FilesReadPDFOutput =
    {
        val rootStr = ctx.session.rootPath.getOrElse("")
        PathSandbox.resolve(rootStr, input.path.toString) match
        {
            case Left(_) =>
                FilesReadPDFOutput("", 0, 0, input.path.toString, Some(FilesError.PathEscaped))
            case Right(resolved) =>
                val sourcePath = Paths.get(rootStr).toAbsolutePath.normalize()
                    .relativize(resolved).toString
                readPDF(input, ctx, resolved, sourcePath)
        }
    }

    private def readPDF(
        input:      FilesReadPDFInput,
        ctx:        ExecutionContext,
        resolved:   java.nio.file.Path,
        sourcePath: String
    ): FilesReadPDFOutput =
    {
        try
        {
            if (!Files.exists(resolved))
            {
                return FilesReadPDFOutput("", 0, 0, sourcePath, Some(FilesError.NotFound))
            }

            val sizeBytes = Files.size(resolved)

            if (input.enrichImages && !ctx.llmProvider.supportsVision)
            {
                return FilesReadPDFOutput(
                    "",
                    0,
                    sizeBytes,
                    sourcePath,
                    Some(FilesError.IoError(
                        "Vision LLM required for PDF ingestion. Configure a multimodal provider (e.g., GPT-4 Vision, Claude 3) in Settings."
                    ))
                )
            }

            TraceLogger.info(ctx.traceId, "files_read_pdf_render", Map("path" -> resolved.toString))
            val images    = PDFPageRenderer.renderToImages(resolved)
            val pageCount = images.size

            val markdown = if (input.enrichImages)
            {
                PageVisionTranscriber.transcribe(
                    images,
                    ctx.llmProvider,
                    ctx.traceId,
                    onProgress = (cur, total) =>
                        ctx.onEvent(AgentEvent.ToolProgress(
                            "files.read_pdf",
                            s"Transcribing page $cur / $total",
                            cur,
                            total
                        ))
                )
            }
            else
            {
                PageVisionTranscriber.stubMarkdown(pageCount)
            }

            FilesReadPDFOutput(markdown, pageCount, sizeBytes, sourcePath)
        }
        catch
        {
            case t: Throwable =>
                val msg = Option(t.getMessage).getOrElse(t.getClass.getName)
                TraceLogger.error(ctx.traceId, "files_read_pdf_error", Map("error" -> msg))
                FilesReadPDFOutput("", 0, 0, sourcePath, Some(FilesError.IoError(msg)))
        }
    }

    def render(output: FilesReadPDFOutput, ctx: ExecutionContext): ToolResult =
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
                    code            = ErrorCode.NotFound,
                    message         = s"File not found: ${output.sourcePath}",
                    hints           = List(
                        "Check the path is correct and within the workspace.",
                        "If the filename contains spaces or hyphens, you MUST quote the value: path=\"My File - Name.pdf\""
                    ),
                    trySuggestions  = List(
                        s"""files.read_pdf path=\\"${output.sourcePath}\\"""",
                        "files.list"
                    )
                ))
            case Some(FilesError.IoError(msg)) =>
                ToolResult(status = ToolStatus.Err(
                    code    = ErrorCode.InternalError,
                    message = msg,
                    hints   = List("For PDF reading, ensure the file is a valid PDF and a Vision LLM is configured.")
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
                val ref = ctx.scratchpad.store(s"__pdf_${output.sourcePath}__", entry)

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
