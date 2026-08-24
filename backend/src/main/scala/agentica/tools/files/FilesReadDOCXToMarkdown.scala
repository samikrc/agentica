package agentica.tools.files

import agentica.agent.AgentEvent
import agentica.doc.{DOCXPageRenderer, PDFPageRenderer, PageVisionTranscriber, DocToolDetector}
import agentica.llm.LLMProvider
import agentica.observability.TraceLogger
import agentica.permissions.{GrantDecision, GrantTTL}
import agentica.shell.{PathSandbox, Presentation}
import agentica.tools.{ArgError, ArgSpec, CommandSchema, ErrorCode, ExecutionContext, FilesError, Tool, ToolBody, ToolResult, ToolStatus}
import java.nio.file.{Files, Paths, Path}
import java.nio.file.attribute.BasicFileAttributes

/**
 *  Validated input for [[FilesReadDOCXToMarkdown]].
 *  @param path           Relative path to the DOCX file within the workspace.
 *  @param enrichImages   If true (default), run PageVisionTranscriber on each page. If false, return stub Markdown.
 */
case class FilesReadDOCXToMarkdownInput(path: java.nio.file.Path, enrichImages: Boolean)

/**
 *  Raw output of [[FilesReadDOCXToMarkdown.execute]].
 *  @param markdownPath Path to the generated/cached Markdown file (e.g., `report.md` for `report.docx`).
 *  @param pageCount    Number of pages rendered from the DOCX.
 *  @param sizeBytes    File size in bytes.
 *  @param sourcePath   Relative source path (for metadata).
 *  @param wasRegenerated Whether the Markdown was freshly generated (true) or cached (false).
 *  @param error        None on success; Some(error) on failure.
 */
case class FilesReadDOCXToMarkdownOutput(
    markdownPath:   String,
    pageCount:      Int,
    sizeBytes:      Long,
    sourcePath:     String,
    wasRegenerated: Boolean,
    error:          Option[FilesError] = None
)

/**
 *  Implements `files.read_docx_to_markdown` — converts DOCX to Markdown via Vision-First ingestion.
 *
 *  Pipeline:
 *  1. Validate path (sandbox check)
 *  2. Check LibreOffice availability
 *  3. Check staleness: compare source file mtime vs `<document>.md` mtime
 *  4. If cached `.md` is fresh, return its path
 *  5. If stale/missing, check permission for parent directory
 *  6. Render DOCX pages to PNG images via [[DOCXPageRenderer]] (LibreOffice headless)
 *  7. Transcribe each page via [[PageVisionTranscriber]] using VLM (or primary LLM fallback)
 *  8. Assemble with `---` separators and write to `<document>.md`
 *  9. Return path to the Markdown file
 *
 *  Supports `enrich_images=false` to skip vision and write stub Markdown.
 */
object FilesReadDOCXToMarkdown extends Tool[FilesReadDOCXToMarkdownInput, FilesReadDOCXToMarkdownOutput]
{
    val name: String = "files.read_docx_to_markdown"

    val schema: CommandSchema = CommandSchema(
        fullName = name,
        summary  = "Convert a DOCX file to Markdown using Vision LLM (requires LibreOffice), with caching",
        args     = List(
            ArgSpec("path",           "Relative path to the DOCX file", required = true),
            ArgSpec("enrich_images", "Run vision LLM on pages (default: true)", required = false, default = Some("true"))
        ),
        example  = """files.read_docx_to_markdown path=report.docx"""
    )

    def validate(args: Map[String, String]): Either[ArgError, FilesReadDOCXToMarkdownInput] =
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
                Right(FilesReadDOCXToMarkdownInput(Paths.get(rawPath), enrichImages))
        }
    }

    def execute(input: FilesReadDOCXToMarkdownInput, ctx: ExecutionContext): FilesReadDOCXToMarkdownOutput =
    {
        val rootStr = ctx.session.rootPath.getOrElse("")
        PathSandbox.resolve(rootStr, input.path.toString) match
        {
            case Left(_) =>
                FilesReadDOCXToMarkdownOutput("", 0, 0, input.path.toString, false, Some(FilesError.PathEscaped))
            case Right(resolved) =>
                val sourcePath = Paths.get(rootStr).toAbsolutePath.normalize()
                    .relativize(resolved).toString
                readDOCX(input, ctx, resolved, sourcePath)
        }
    }

    private def readDOCX(
        input:      FilesReadDOCXToMarkdownInput,
        ctx:        ExecutionContext,
        resolved:   Path,
        sourcePath: String
    ): FilesReadDOCXToMarkdownOutput =
    {
        try
        {
            if (!Files.exists(resolved))
            {
                return FilesReadDOCXToMarkdownOutput("", 0, 0, sourcePath, false, Some(FilesError.NotFound))
            }

            val sizeBytes = Files.size(resolved)
            val sourceAttrs = Files.readAttributes(resolved, classOf[BasicFileAttributes])
            val sourceMtime = sourceAttrs.lastModifiedTime().toMillis

            // Determine the Markdown output path: <document>.md (strip .docx extension)
            val sourceFileName = resolved.getFileName.toString
            val mdFileName = if (sourceFileName.toLowerCase.endsWith(".docx"))
                sourceFileName.dropRight(5) + ".md"
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
                val pageCount = try {
                    val content = Files.readString(mdPath)
                    content.split("\n---\n").length
                } catch {
                    case _: Throwable => 0
                }
                return FilesReadDOCXToMarkdownOutput(mdRelPath, pageCount, sizeBytes, sourcePath, false)
            }

            // Check LibreOffice availability before rendering
            if (!DocToolDetector.available)
            {
                return FilesReadDOCXToMarkdownOutput(
                    "", 0, sizeBytes, sourcePath, false,
                    Some(FilesError.IoError(
                        s"LibreOffice is required for DOCX conversion. ${DocToolDetector.installInstructions} Use deps.check for installation instructions."
                    ))
                )
            }

            // Get the effective vision provider
            val visionProvider = ctx.vlmProvider.getOrElse(ctx.llmProvider)
            TraceLogger.info(ctx.traceId, "files_read_docx_vision_provider",
                Map("provider" -> (if (ctx.vlmProvider.isDefined) "vlm" else "llm_fallback"),
                    "model" -> visionProvider.modelName))

            if (input.enrichImages && !visionProvider.supportsVision)
            {
                return FilesReadDOCXToMarkdownOutput(
                    "", 0, sizeBytes, sourcePath, false,
                    Some(FilesError.IoError(
                        "Vision LLM required for DOCX ingestion. Configure a multimodal provider in Settings → VLM tab, or ensure the primary LLM supports vision."
                    ))
                )
            }

            // Check permission for parent directory
            val parentDir = resolved.getParent
            val parentRelPath = Paths.get(ctx.session.rootPath.getOrElse("")).toAbsolutePath.normalize()
                .relativize(parentDir).toString
            val hasGrant = ctx.scopeStore.hasGrant(ctx.session.id, name, parentRelPath)
            if (!hasGrant)
            {
                ctx.onEvent(AgentEvent.PermissionRequired(
                    tool    = name,
                    path    = Some(parentRelPath),
                    options = List("Allow once", "Allow for session", "Allow always", "Deny")
                ))
                Option(ctx.permissionLatch.poll(60, java.util.concurrent.TimeUnit.SECONDS)) match
                {
                    case None =>
                        return FilesReadDOCXToMarkdownOutput(
                            "", 0, sizeBytes, sourcePath, false,
                            Some(FilesError.IoError("Permission denied to write Markdown file to workspace"))
                        )
                    case Some(GrantDecision.Denied) =>
                        return FilesReadDOCXToMarkdownOutput(
                            "", 0, sizeBytes, sourcePath, false,
                            Some(FilesError.IoError("Permission denied to write Markdown file to workspace"))
                        )
                    case Some(granted: GrantDecision.Granted) =>
                        ctx.scopeStore.addGrant(ctx.session.id, name, granted)
                }
            }

            TraceLogger.info(ctx.traceId, "files_read_docx_to_markdown_render", Map("path" -> sourcePath))
            val allImages = DOCXPageRenderer.renderToImages(resolved)
                .map(PDFPageRenderer.resizeToMaxDimension(_, PDFPageRenderer.MaxImageDimension))
            val pageCount = allImages.size

            val debugDir  = if (ctx.debugMode) Some(resolved.getParent.resolve("debug")) else None
            val debugStem = if (sourceFileName.toLowerCase.endsWith(".docx")) sourceFileName.dropRight(5) else sourceFileName

            val markdown = if (input.enrichImages)
            {
                PageVisionTranscriber.transcribe(
                    totalPages    = pageCount,
                    renderBatch   = (from, to) => allImages.slice(from, to),
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
                    TraceLogger.error(ctx.traceId, "files_read_docx_to_markdown_write_error",
                        Map("path" -> mdPath.toString, "error" -> t.getMessage))
                    return FilesReadDOCXToMarkdownOutput(
                        "", pageCount, sizeBytes, sourcePath, false,
                        Some(FilesError.IoError(s"Failed to write Markdown file: ${t.getMessage}"))
                    )
            }

            FilesReadDOCXToMarkdownOutput(mdRelPath, pageCount, sizeBytes, sourcePath, true)
        }
        catch
        {
            case t: Throwable =>
                val msg = Option(t.getMessage).getOrElse(t.getClass.getName)
                TraceLogger.error(ctx.traceId, "files_read_docx_to_markdown_error", Map("error" -> msg))

                // Handle corrupted cache
                val mdPath = resolved.getParent.resolve(resolved.getFileName.toString.dropRight(5) + ".md")
                if (Files.exists(mdPath) && msg.contains("markdown"))
                {
                    try
                    {
                        Files.delete(mdPath)
                        TraceLogger.info(ctx.traceId, "files_read_docx_to_markdown_corrupted_cache_deleted",
                            Map("path" -> mdPath.toString))
                        return FilesReadDOCXToMarkdownOutput(
                            "", 0, 0, sourcePath, false,
                            Some(FilesError.IoError("Cached Markdown file was corrupted and has been deleted. Please retry the conversion."))
                        )
                    }
                    catch { case _: Throwable => }
                }

                FilesReadDOCXToMarkdownOutput("", 0, 0, sourcePath, false, Some(FilesError.IoError(msg)))
        }
    }

    def render(output: FilesReadDOCXToMarkdownOutput, ctx: ExecutionContext): ToolResult =
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
                        "For DOCX conversion, ensure:",
                        "1. The file is a valid DOCX",
                        "2. A Vision LLM is configured",
                        "3. LibreOffice is installed (see deps.check for install instructions)"
                    )
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
                        s"DOCX converted to Markdown: ${output.markdownPath}\n" +
                        s"($cacheStatus, ${output.pageCount} pages, ${f"${output.sizeBytes / 1024.0}%.1f"} KB)\n" +
                        s"To read or search the content, use path=\"${output.markdownPath}\" — not the original .docx path."
                    ))
                )
        }
    }
}
