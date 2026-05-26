package agentica.doc

import agentica.observability.TraceLogger
import agentica.util.ProcessUtils

/**
 *  Status of the LibreOffice (`soffice`) binary on the current machine.
 *
 *  @param available  Whether a usable `soffice` binary was found on PATH.
 *  @param path       Resolved binary path if found (output of `which soffice`).
 *  @param version    First line of `soffice --version` output if available.
 */
case class DocToolStatus(
    available: Boolean,
    path:      Option[String],
    version:   Option[String]
)

/**
 *  Detects the LibreOffice (`soffice`) binary at application startup.
 *
 *  Detection is performed once, eagerly on first access, and the result is cached for
 *  the lifetime of the JVM. Any document tool that requires LibreOffice should call
 *  [[DocToolDetector.status]] before execution and return a structured error when
 *  `status.available == false`.
 *
 *  Detection strategy:
 *  1. Run `which soffice` (Linux/macOS) to locate the binary.
 *  2. If found, run `soffice --version` to capture the version string.
 *  3. Cache the result in [[_status]].
 *
 *  Thread-safe: `_status` is written exactly once via a lazy val.
 */
object DocToolDetector
{

    /**
     *  Install instructions injected into structured tool errors when LibreOffice is absent.
     *  Kept here so all doc tools reference the same message.
     */
    val installInstructions: String =
        "LibreOffice is required for DOCX rendering and document output generation. " +
        "Install it with:\n" +
        "  Ubuntu/Debian: sudo apt install libreoffice\n" +
        "  macOS (Homebrew): brew install --cask libreoffice\n" +
        "  Windows: download from https://www.libreoffice.org/download/"

    /**
     *  Cached detection result.  Computed once on first access; never recomputed.
     */
    lazy val status: DocToolStatus = detect()

    /**
     *  Convenience accessor: `true` iff LibreOffice is available.
     */
    def available: Boolean = status.available

    // ── Private ───────────────────────────────────────────────────────────────

    private def detect(): DocToolStatus =
    {
        val whichResult = ProcessUtils.runCaptured(List("which", "soffice"))
        whichResult match
        {
            case Some(path) if path.nonEmpty =>
                val trimmedPath = path.trim
                val version     = ProcessUtils.runCaptured(List(trimmedPath, "--version"))
                val versionLine = version.map(_.trim.linesIterator.nextOption().getOrElse("")).filter(_.nonEmpty)
                val s           = DocToolStatus(available = true, path = Some(trimmedPath), version = versionLine)
                TraceLogger.info("-", "doc_tool_detector",
                    Map("soffice" -> "found", "path" -> trimmedPath, "version" -> versionLine.getOrElse("unknown")))
                s

            case _ =>
                TraceLogger.warn("-", "doc_tool_detector",
                    Map("soffice" -> "not_found",
                        "hint"   -> "Install LibreOffice to enable DOCX rendering and document output generation."))
                DocToolStatus(available = false, path = None, version = None)
        }
    }

}
