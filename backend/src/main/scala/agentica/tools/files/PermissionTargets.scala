package agentica.tools.files

import agentica.shell.PathSandbox
import agentica.tools.{ExecutionContext, PermissionTarget}
import java.nio.file.{Files, Paths}
import java.nio.file.attribute.BasicFileAttributes

/**
 *  Shared [[PermissionTarget]] constructors for permission-gated tools.
 */
private[files] object PermissionTargets
{
    /**
     *  Returns a grant target on the resolved file itself.
     *  Mirrors `files_write`-style tools whose protected resource is the file
     *  they write; returns `None` when the path escapes the sandbox so no
     *  prompt precedes the tool's own `path_escaped` error.
     *  @param rawPath  Raw file path argument.
     *  @param ctx      Runtime execution context.
     *  @return         Grant target on the resolved file, or `None` on escape.
     */
    def singleFileTarget(rawPath: String, ctx: ExecutionContext): Option[PermissionTarget] =
        try
        {
            val rootStr = ctx.session.rootPath.getOrElse("")
            PathSandbox.resolve(rootStr, rawPath).toOption.map { resolved =>
                val sourcePath = Paths.get(rootStr).toAbsolutePath.normalize().relativize(resolved).toString
                PermissionTarget(grantKey = resolved.toString, displayPath = Some(sourcePath))
            }
        }
        catch { case _: Throwable => None }


    /**
     *  Returns the parent-directory grant target for a tool that writes a
     *  sibling output next to the source — e.g. `<document>.md` — so the grant
     *  covers the containing directory. Mirrors each tool's `execute`
     *  preconditions (path escape, missing file, fresh output cache, missing
     *  converter dependency, missing vision provider) so the user is never
     *  prompted for a call that will not reach the write.
     *  @param inputPath       Raw document path argument.
     *  @param enrichImages    Whether page-image transcription is requested.
     *  @param extension       Document extension stripped for the `.md` name
     *                         (e.g. `".pdf"`, `".docx"`, `".pptx"`).
     *  @param ctx             Runtime execution context.
     *  @param converterReady  Whether the converter dependency is available —
     *                         e.g. `DocToolDetector.available` for LibreOffice.
     *  @return              Grant target on the containing directory, or `None`
     *                       when the call cannot reach the write.
     */
    def directoryTarget(
        inputPath:      String,
        enrichImages:   Boolean,
        extension:      String,
        ctx:            ExecutionContext,
        converterReady: Boolean = true
    ): Option[PermissionTarget] =
        try
        {
            val rootStr = ctx.session.rootPath.getOrElse("")
            PathSandbox.resolve(rootStr, inputPath).toOption
                .filter(Files.exists(_))
                .flatMap { resolved =>
                    val sourceMtime = Files.readAttributes(resolved, classOf[BasicFileAttributes]).lastModifiedTime().toMillis
                    val sourceFileName = resolved.getFileName.toString
                    val mdFileName = if (sourceFileName.toLowerCase.endsWith(extension))
                        sourceFileName.dropRight(extension.length) + ".md"
                    else
                        sourceFileName + ".md"
                    val mdPath = resolved.getParent.resolve(mdFileName)
                    val cacheFresh = Files.exists(mdPath) &&
                    {
                        val mdAttrs = Files.readAttributes(mdPath, classOf[BasicFileAttributes])
                        mdAttrs.lastModifiedTime().toMillis >= sourceMtime && mdAttrs.size() > 100
                    }
                    val visionMissing = enrichImages && !ctx.vlmProvider.getOrElse(ctx.llmProvider).supportsVision
                    if (cacheFresh || visionMissing || !converterReady)
                    {
                        None
                    }
                    else
                    {
                        val parentRelPath = Paths.get(rootStr).toAbsolutePath.normalize().relativize(resolved.getParent).toString
                        Some(PermissionTarget(grantKey = parentRelPath, displayPath = Some(parentRelPath)))
                    }
                }
        }
        catch { case _: Throwable => None }
}
