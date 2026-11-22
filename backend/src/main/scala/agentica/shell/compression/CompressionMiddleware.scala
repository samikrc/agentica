package agentica.shell.compression

import agentica.observability.TraceLogger
import agentica.shell.{Presentation, ScratchEntry, ToolCall, ToolMiddleware}
import agentica.tools.{ToolBody, ToolResult, ToolStatus}
import scala.collection.immutable.ListMap

/**
 *  Compresses oversized tool-result bodies before they enter the context
 *  window.
 *
 *  [[ToolBody.ScratchRef]] results — bodies too large for
 *  [[Presentation.BODY_BUDGET_CHARS]], which today show the model nothing but
 *  a `stored:` notice — are replaced by a compressed inline preview when one
 *  fits the budget. [[ToolBody.Inline]] bodies above
 *  [[InlineThresholdChars]] compress only when the reduction clears
 *  [[MinReductionRatio]]. In both cases the original stays (or is placed) in
 *  the scratchpad and the `stored` ref rides in metadata, so compression
 *  changes the default view, never availability — the Caveman
 *  "compressed view + recovery handle" contract.
 *
 *  All compressor failures are fail-closed: `None` from every compressor
 *  leaves the result untouched. Bodies above [[MaxCompressChars]] also pass
 *  through unchanged — compression is synchronous on the dispatch path, so
 *  very large reads (which [[agentica.tools.files.FilesRead]] materialises in
 *  full) are not worth the allocation and latency.
 */
class CompressionMiddleware(
    compressors: List[Compressor] = Compressors.all
) extends ToolMiddleware
{
    /** Inline bodies larger than this are compression candidates. */
    private val InlineThresholdChars = 8000

    /** Bodies larger than this are never compressed — bounds dispatch latency on huge reads. */
    private val MaxCompressChars = 1_000_000

    /** Minimum fractional reduction required to replace an inline body. */
    private val MinReductionRatio = 0.30

    /**
     *  Compresses the dispatched result's body when a compressor produces a
     *  sufficiently smaller view.
     *
     *  @param call    The dispatched tool call (`query` argument feeds relevance keeps).
     *  @param result  The result produced by dispatch or an inner `after` hook.
     *  @return        The result with a compressed body, or unchanged.
     */
    override def after(call: ToolCall, result: ToolResult): ToolResult =
    {
        if (result.status != ToolStatus.Ok) return result
        val query = call.cmd.args.getOrElse("query", "")
        result.body match
        {
            case Some(ToolBody.ScratchRef(ref, _, _, _)) =>
                call.ctx.scratchpad.get(ref) match
                {
                    case None => result
                    case Some(entry) if entry.content.length > MaxCompressChars => result
                    case Some(entry) =>
                        Compressors.first(compressors, entry.content, query) match
                        {
                            case Some((compressed, kind)) if compressed.length <= Presentation.BODY_BUDGET_CHARS =>
                                TraceLogger.info(call.ctx.traceId, "result_compressed",
                                    Map("tool" -> call.cmd.fullName, "type" -> kind,
                                        "from" -> entry.content.length.toString, "to" -> compressed.length.toString))
                                result.copy(
                                    metadata = result.metadata ++ ListMap(
                                        "compressed" -> s"$kind: ${entry.content.length} → ${compressed.length} chars",
                                        "stored"     -> ref,
                                        "hint"       -> s"compressed view — full original stored at $ref"),
                                    body = Some(ToolBody.Inline(compressed)))
                            case _ => result
                        }
                }

            case Some(ToolBody.Inline(text))
                    if text.length > InlineThresholdChars && text.length <= MaxCompressChars =>
                Compressors.first(compressors, text, query) match
                {
                    case Some((compressed, kind)) if compressed.length <= (text.length * (1 - MinReductionRatio)).toInt =>
                        val ref = result.metadata.getOrElse("stored",
                            storeOriginal(call, text))
                        TraceLogger.info(call.ctx.traceId, "result_compressed",
                            Map("tool" -> call.cmd.fullName, "type" -> kind,
                                "from" -> text.length.toString, "to" -> compressed.length.toString))
                        result.copy(
                            metadata = result.metadata ++ ListMap(
                                "compressed" -> s"$kind: ${text.length} → ${compressed.length} chars",
                                "stored"     -> ref,
                                "hint"       -> s"compressed view — full original stored at $ref"),
                            body = Some(ToolBody.Inline(compressed)))
                    case _ => result
                }

            case _ => result
        }
    }

    /**
     *  Stores an inline body the producing tool did not already store, so the
     *  compressed view keeps a recovery handle.
     *
     *  @param call  The dispatched call carrying the session scratchpad.
     *  @param text  Original body text.
     *  @return      The `$scratch/` ref of the stored entry.
     */
    private def storeOriginal(call: ToolCall, text: String): String =
    {
        val key = call.ctx.scratchpad.nextComputedKey()
        val now = System.currentTimeMillis()
        call.ctx.scratchpad.store(key, ScratchEntry(
            content      = text,
            sizeBytes    = text.length.toLong,
            lineCount    = text.count(_ == '\n') + 1,
            sourcePath   = key,
            lastModified = now,
            storedAt     = now
        ))
    }
}
