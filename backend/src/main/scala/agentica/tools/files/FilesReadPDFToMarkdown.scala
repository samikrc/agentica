package agentica.tools.files

import agentica.agent.AgentEvent
import agentica.doc.{PDFPageRenderer, PageVisionTranscriber, DocToolDetector}
import agentica.llm.LLMProvider
import agentica.observability.TraceLogger
import agentica.permissions.{GrantDecision, GrantTTL}
import agentica.shell.{PathSandbox, Presentation}
import agentica.tools.{ArgError, ArgSpec, CommandSchema, ErrorCode, ExecutionContext, FilesError, Tool, ToolBody, ToolResult, ToolStatus}
import java.nio.file.{Files, Paths, Path}
import java.nio.file.attribute.BasicFileAttributes

/**
 *  Validated input for [[FilesReadPDFToMarkdown]].
 *  @param path           Relative path to the PDF file within the workspace.
 *  @param enrichImages   If true (default), run PageVisionTranscriber on each page. If false, return stub Markdown.
 */
case class FilesReadPDFToMarkdownInput(path: java.nio.file.Path, enrichImages: Boolean)

/**
 *  Raw output of [[FilesReadPDFToMarkdown.execute]].
 *  @param markdownPath Path to the generated/cached Markdown file (e.g., `report.md` for `report.pdf`).
 *  @param pageCount    Number of pages in the PDF.
 *  @param sizeBytes    File size in bytes.
 *  @param sourcePath   Relative source path (for metadata).
 *  @param wasRegenerated Whether the Markdown was freshly generated (true) or cached (false).
 *  @param error        None on success; Some(error) on failure.
 */
case class FilesReadPDFToMarkdownOutput(
    markdownPath:   String,
    pageCount:      Int,
    sizeBytes:      Long,
    sourcePath:     String,
    wasRegenerated: Boolean,
    error:          Option[FilesError] = None
)

/**
 *  Implements `files.read_pdf_to_markdown` — converts PDF to Markdown via Vision-First ingestion.
 *
 *  Pipeline:
 *  1. Validate path (sandbox check)
 *  2. Check staleness: compare source file mtime vs `<document>.md` mtime
 *  3. If cached `.md` is fresh, return its path
 *  4. If stale/missing, check permission for parent directory
 *  5. Render PDF pages to PNG images via [[PDFPageRenderer]]
 *  6. Transcribe each page via [[PageVisionTranscriber]] using VLM (or primary LLM fallback)
 *  7. Assemble with `---` separators and write to `<document>.md`
 *  8. Return path to the Markdown file
 *
 *  Supports `enrich_images=false` to skip vision and write stub Markdown.
 */
object FilesReadPDFToMarkdown extends Tool[FilesReadPDFToMarkdownInput, FilesReadPDFToMarkdownOutput]
{
    val name: String = "files.read_pdf_to_markdown"

    val schema: CommandSchema = CommandSchema(
        fullName = name,
        summary  = "Convert a PDF file to Markdown using Vision LLM, with caching",
        args     = List(
            ArgSpec("path",           "Relative path to the PDF file", required = true),
            ArgSpec("enrich_images", "Run vision LLM on pages (default: true)", required = false, default = Some("true"))
        ),
        example  = """files.read_pdf_to_markdown path="My Report.pdf""""
    )

    def validate(args: Map[String, String]): Either[ArgError, FilesReadPDFToMarkdownInput] =
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
                Right(FilesReadPDFToMarkdownInput(Paths.get(rawPath), enrichImages))
        }
    }

    def execute(input: FilesReadPDFToMarkdownInput, ctx: ExecutionContext): FilesReadPDFToMarkdownOutput =
    {
        val rootStr = ctx.session.rootPath.getOrElse("")
        PathSandbox.resolve(rootStr, input.path.toString) match
        {
            case Left(_) =>
                FilesReadPDFToMarkdownOutput("", 0, 0, input.path.toString, false, Some(FilesError.PathEscaped))
            case Right(resolved) =>
                val sourcePath = Paths.get(rootStr).toAbsolutePath.normalize()
                    .relativize(resolved).toString
                readPDF(input, ctx, resolved, sourcePath)
        }
    }

    private def readPDF(
        input:      FilesReadPDFToMarkdownInput,
        ctx:        ExecutionContext,
        resolved:   Path,
        sourcePath: String
    ): FilesReadPDFToMarkdownOutput =
    {
        try
        {
            if (!Files.exists(resolved))
            {
                return FilesReadPDFToMarkdownOutput("", 0, 0, sourcePath, false, Some(FilesError.NotFound))
            }

            val sizeBytes = Files.size(resolved)
            val sourceAttrs = Files.readAttributes(resolved, classOf[BasicFileAttributes])
            val sourceMtime = sourceAttrs.lastModifiedTime().toMillis

            // Determine the Markdown output path: <document>.md (strip original extension)
            val sourceFileName = resolved.getFileName.toString
            val mdFileName = if (sourceFileName.toLowerCase.endsWith(".pdf"))
                sourceFileName.dropRight(4) + ".md"
            else
                sourceFileName + ".md"
            val mdPath = resolved.getParent.resolve(mdFileName)
            val mdRelPath = Paths.get(ctx.session.rootPath.getOrElse("")).toAbsolutePath.normalize()
                .relativize(mdPath).toString

            // Check if cached Markdown is fresh
            val cachedMdFresh = Files.exists(mdPath) && {
                val mdAttrs = Files.readAttributes(mdPath, classOf[BasicFileAttributes])
                val mdMtime = mdAttrs.lastModifiedTime().toMillis
                mdMtime >= sourceMtime && mdAttrs.size() > 100
            }

            if (cachedMdFresh)
            {
                // Cache hit - return existing Markdown file path
                val pageCount = try {
                    // Try to get page count from cached content by counting separators
                    val content = Files.readString(mdPath)
                    content.split("\n---\n").length
                } catch {
                    case _: Throwable => 0
                }
                return FilesReadPDFToMarkdownOutput(mdRelPath, pageCount, sizeBytes, sourcePath, false)
            }

            // Cache miss or stale - need to generate
            // Get the effective vision provider: VLM if configured, otherwise primary LLM
            val visionProvider = ctx.vlmProvider.getOrElse(ctx.llmProvider)
            TraceLogger.info(ctx.traceId, "files_read_pdf_vision_provider",
                Map("provider" -> (if (ctx.vlmProvider.isDefined) "vlm" else "llm_fallback"),
                    "model" -> visionProvider.modelName))

            if (input.enrichImages && !visionProvider.supportsVision)
            {
                return FilesReadPDFToMarkdownOutput(
                    "", 0, sizeBytes, sourcePath, false,
                    Some(FilesError.IoError(
                        "Vision LLM required for PDF ingestion. Configure a multimodal provider (e.g., GPT-4 Vision, Claude 3) in Settings → VLM tab, or ensure the primary LLM supports vision."
                    ))
                )
            }

            // Check permission for parent directory (broader scope as requested)
            val parentDir = resolved.getParent
            val parentRelPath = Paths.get(ctx.session.rootPath.getOrElse("")).toAbsolutePath.normalize()
                .relativize(parentDir).toString
            val hasGrant = ctx.scopeStore.hasGrant(ctx.session.id, name, parentRelPath)
            TraceLogger.info(ctx.traceId, "files_read_pdf_perm_check",
                Map("tool" -> name, "sessionId" -> ctx.session.id, "path" -> parentRelPath, "hasGrant" -> hasGrant.toString))
            if (!hasGrant)
            {
                // Emit permission required event and block awaiting decision
                TraceLogger.info(ctx.traceId, "files_read_pdf_perm_waiting", Map("path" -> parentRelPath))
                ctx.onEvent(AgentEvent.PermissionRequired(
                    tool    = name,
                    path    = Some(parentRelPath),
                    options = List("Allow once", "Allow for session", "Allow always", "Deny")
                ))
                val decision = Option(ctx.permissionLatch.poll(60, java.util.concurrent.TimeUnit.SECONDS))
                TraceLogger.info(ctx.traceId, "files_read_pdf_perm_decision",
                    Map("decision" -> decision.map(_.toString).getOrElse("timeout")))
                decision match
                {
                    case None =>
                        return FilesReadPDFToMarkdownOutput(
                            "", 0, sizeBytes, sourcePath, false,
                            Some(FilesError.IoError("Permission request timed out (60s)"))
                        )
                    case Some(GrantDecision.Denied) =>
                        return FilesReadPDFToMarkdownOutput(
                            "", 0, sizeBytes, sourcePath, false,
                            Some(FilesError.IoError("Permission denied to write Markdown file to workspace"))
                        )
                    case Some(granted: GrantDecision.Granted) =>
                        TraceLogger.info(ctx.traceId, "files_read_pdf_perm_granted",
                            Map("ttl" -> granted.ttl.toString, "prefix" -> granted.pathPrefix.getOrElse("(none)")))
                        ctx.scopeStore.addGrant(ctx.session.id, name, granted)
                }
            }

            TraceLogger.info(ctx.traceId, "files_read_pdf_to_markdown_render", Map("path" -> resolved.toString))
            val pageCount = PDFPageRenderer.pageCount(resolved)

            val debugDir  = if (ctx.debugMode) Some(resolved.getParent.resolve("debug")) else None
            val debugStem = sourceFileName.dropRight(if (sourceFileName.toLowerCase.endsWith(".pdf")) 4 else 0)

            val markdown = if (input.enrichImages)
            {
                PageVisionTranscriber.transcribe(
                    totalPages    = pageCount,
                    renderBatch   = (from, to) => PDFPageRenderer.renderBatch(resolved, from, to),
                    llmProvider   = visionProvider,
                    traceId       = ctx.traceId,
                    parallelism   = ctx.vlmParallelism,
                    debugImageDir = debugDir,
                    debugStem     = debugStem,
                    onProgress    = (cur, total) =>
                        ctx.onEvent(AgentEvent.ToolProgress(name, s"Transcribing page $cur / $total", cur, total))
                )
            }
            else
            {
                PageVisionTranscriber.stubMarkdown(pageCount)
            }

            // Write assembled Markdown to file
            try
            {
                Files.writeString(mdPath, markdown)
            }
            catch
            {
                case t: Throwable =>
                    TraceLogger.error(ctx.traceId, "files_read_pdf_to_markdown_write_error",
                        Map("path" -> mdPath.toString, "error" -> t.getMessage))
                    return FilesReadPDFToMarkdownOutput(
                        "", pageCount, sizeBytes, sourcePath, false,
                        Some(FilesError.IoError(s"Failed to write Markdown file: ${t.getMessage}"))
                    )
            }

            FilesReadPDFToMarkdownOutput(mdRelPath, pageCount, sizeBytes, sourcePath, true)
        }
        catch
        {
            case t: Throwable =>
                val msg = Option(t.getMessage).getOrElse(t.getClass.getName)
                TraceLogger.error(ctx.traceId, "files_read_pdf_to_markdown_error", Map("error" -> msg))

                // Check if it's a corrupted cached file issue - if .md exists but couldn't be read properly
                val mdPath = resolved.getParent.resolve(resolved.getFileName.toString.dropRight(4) + ".md")
                if (Files.exists(mdPath) && msg.contains("markdown"))
                {
                    try
                    {
                        Files.delete(mdPath)
                        TraceLogger.info(ctx.traceId, "files_read_pdf_to_markdown_corrupted_cache_deleted",
                            Map("path" -> mdPath.toString))
                        return FilesReadPDFToMarkdownOutput(
                            "", 0, 0, sourcePath, false,
                            Some(FilesError.IoError(s"Cached Markdown file was corrupted and has been deleted. Please retry the conversion."))
                        )
                    }
                    catch
                    {
                        case _: Throwable => // Ignore delete failure
                    }
                }

                FilesReadPDFToMarkdownOutput("", 0, 0, sourcePath, false, Some(FilesError.IoError(msg)))
        }
    }

    def render(output: FilesReadPDFToMarkdownOutput, ctx: ExecutionContext): ToolResult =
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
                        s"""files.read_pdf_to_markdown path=\\"${output.sourcePath}\\"""",
                        "files.list"
                    )
                ))
            case Some(FilesError.IoError(msg)) =>
                ToolResult(status = ToolStatus.Err(
                    code    = ErrorCode.InternalError,
                    message = msg,
                    hints   = List("For PDF conversion, ensure the file is a valid PDF and a Vision LLM is configured.")
                ))
            case None =>
                val cacheStatus = if (output.wasRegenerated) "generated" else "cached"
                ToolResult(
                    status   = ToolStatus.Ok,
                    metadata = Map(
                        "markdown_path" -> output.markdownPath,
                        "pages"         -> output.pageCount.toString,
                        "size"          -> f"${output.sizeBytes / 1024.0}%.1f KB",
                        "cache_status"  -> cacheStatus
                    ),
                    body = Some(ToolBody.Inline(
                        s"PDF converted to Markdown: ${output.markdownPath}\n" +
                        s"($cacheStatus, ${output.pageCount} pages, ${f"${output.sizeBytes / 1024.0}%.1f"} KB)\n" +
                        s"To read or search the content, use path=\"${output.markdownPath}\" — not the original .pdf path."
                    ))
                )
        }
    }
}
