package agentica.tools.deps

import agentica.doc.{DocFontLoader, DocToolDetector}
import agentica.tools.{ArgError, ArgSpec, CommandSchema, ExecutionContext, ToolBody, ToolResult, ToolStatus, Tool}

/**
 *  Validated input for [[DepsCheck]] — no arguments needed.
 */
case class DepsCheckInput()

/**
 *  Raw output of [[DepsCheck.execute]].
 *
 *  @param sofficeAvailable  Whether the `soffice` binary was found.
 *  @param sofficePath       Resolved path to `soffice`, if found.
 *  @param sofficeVersion    Version string from `soffice --version`, if available.
 *  @param loadedFonts       Font family names successfully registered at startup.
 *  @param missingFonts      Proprietary font names that could not be bundled.
 */
case class DepsCheckOutput(
    sofficeAvailable: Boolean,
    sofficePath:      Option[String],
    sofficeVersion:   Option[String],
    loadedFonts:      List[String],
    missingFonts:     List[String]
)

/**
 *  Implements the `deps_check` command.
 *
 *  Reports the detection status of all external dependencies required by the document
 *  processing pipeline (Stage A).  Currently covers:
 *
 *  - LibreOffice (`soffice`) binary — required for DOCX rendering and Markdown→DOCX/PDF output.
 *  - Bundled font registration status — Liberation fonts registered with AWT/PDFBox.
 *  - Proprietary fonts (Calibri, Aptos, Segoe UI) that cannot be bundled.
 *
 *  JVM libraries (PDFBox, POI XSLF, docx4j) are always available as Maven dependencies
 *  and are not surfaced here.
 */
object DepsCheck extends Tool[DepsCheckInput, DepsCheckOutput]
{
    val name: String = "deps_check"

    val schema: CommandSchema = CommandSchema(
        fullName = name,
        summary  = "Report the status of external dependencies required for document processing.",
        args     = Nil,
        example  = "deps_check"
    )

    /**
     *  Validates dependency-check arguments.
     *  @param args  Raw native-call arguments; currently ignored because this tool has no parameters.
     *  @return      Validated dependency-check input.
     */
    def validate(args: Map[String, String]): Either[ArgError, DepsCheckInput] =
        Right(DepsCheckInput())

    /**
     *  Detects installed document-processing dependencies.
     *  @param input  Validated dependency-check input.
     *  @param ctx    Execution context for the current run.
     *  @return       Availability information for each supported dependency.
     */
    def execute(input: DepsCheckInput, ctx: ExecutionContext): DepsCheckOutput =
    {
        val status = DocToolDetector.status

        val loadedFonts  = DocFontLoader.loadedFonts.keys.toList.sorted
        val missingFonts = List("Calibri", "Aptos", "Segoe UI")

        DepsCheckOutput(
            sofficeAvailable = status.available,
            sofficePath      = status.path,
            sofficeVersion   = status.version,
            loadedFonts      = loadedFonts,
            missingFonts     = missingFonts
        )
    }

    /**
     *  Renders dependency availability and installation guidance.
     *  @param output  Raw dependency detection output.
     *  @param ctx     Execution context for the current run.
     *  @return        Standardized tool result containing status details and guidance.
     */
    def render(output: DepsCheckOutput, ctx: ExecutionContext): ToolResult =
    {
        val lines = scala.collection.mutable.ListBuffer[String]()

        lines += "## LibreOffice"
        if (output.sofficeAvailable)
        {
            lines += s"  status:  available"
            output.sofficePath.foreach(p => lines += s"  path:    $p")
            output.sofficeVersion.foreach(v => lines += s"  version: $v")
        }
        else
        {
            lines += s"  status:  NOT FOUND"
            lines += s"  impact:  files_read_docx, files_markdown_to_docx, files_markdown_to_pdf will be unavailable"
            lines += s""
            lines += s"  Install instructions:"
            DocToolDetector.installInstructions.linesIterator.foreach(l => lines += s"  $l")
        }

        lines += ""
        lines += "## Bundled Fonts (Liberation — open source)"
        if (output.loadedFonts.nonEmpty)
            output.loadedFonts.foreach(f => lines += s"  ✓ $f")
        else
            lines += "  (none loaded — font resources may be missing from classpath)"

        lines += ""
        lines += "## Proprietary Fonts (not bundled)"
        output.missingFonts.foreach { f =>
            lines += s"  ✗ $f — not bundled (proprietary licence); rendering falls back to Liberation substitute"
        }

        ToolResult(
            status = ToolStatus.Ok,
            body   = Some(ToolBody.Inline(lines.mkString("\n")))
        )
    }
}
