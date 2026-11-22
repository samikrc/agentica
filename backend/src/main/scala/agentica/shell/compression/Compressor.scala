package agentica.shell.compression

/**
 *  A structural compressor for one content type.
 *
 *  Every compressor is deterministic and fail-closed: on any parse problem or
 *  when the output would not be smaller, it returns `None` and the caller
 *  forwards the original unchanged. Compressors never store recovery content —
 *  [[CompressionMiddleware]] guarantees the original is in the scratchpad
 *  before a compressed view may reach the model.
 *
 *  Design adapted from Caveman (https://github.com/JuliusBrussee/caveman) —
 *  deterministic, fail-closed per-type compressors with scratchpad-backed
 *  recovery of the original. Caveman is a design reference, not a dependency.
 */
trait Compressor
{
    /** The content type this compressor handles, e.g. `"tabular"` (reported in result metadata). */
    def contentType: String

    /**
     *  Attempts to compress `text`, biasing kept content toward `query` when
     *  non-empty (e.g. `files_search`'s `query` argument).
     *
     *  @param text   The original body text.
     *  @param query  Optional relevance query; `""` means query-agnostic.
     *  @return       The compressed text, or `None` when not compressible.
     */
    def compress(text: String, query: String = ""): Option[String]
}

/**
 *  The ordered compressor registry.
 *
 *  Order matters: strict parsers ([[JsonCompressor]], [[TabularCompressor]])
 *  fail fast on foreign content, then [[TextCompressor]] handles structured
 *  prose — delegating embedded tables to the tabular selection — and
 *  [[LinesCompressor]] is the generic line-oriented fallback. Text runs
 *  before Lines so a prose document with an embedded table gets row-level,
 *  column-aware elision rather than generic line elision; log-shaped input
 *  that [[TextCompressor]] over-keeps fails its not-smaller check and falls
 *  through to Lines.
 */
object Compressors
{
    /** Built-in compressors in detection order. */
    val all: List[Compressor] = List(
        JsonCompressor,
        TabularCompressor,
        TextCompressor,
        LinesCompressor
    )

    /**
     *  Runs each compressor in order and returns the first success.
     *
     *  @param compressors  Compressors to try, in detection order.
     *  @param text         The original body text.
     *  @param query        Optional relevance query forwarded to every compressor.
     *  @return             `(compressed, contentType)` of the winning compressor, or `None`.
     */
    def first(compressors: List[Compressor], text: String, query: String): Option[(String, String)] =
        compressors.iterator
            .flatMap(c => c.compress(text, query).map(_ -> c.contentType))
            .nextOption()
}
