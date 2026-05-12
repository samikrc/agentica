package agentica.shell

import agentica.tools.{AgentResponse, ToolBody, ToolResult, ToolStatus}

/**
 *  Converts a typed [[agentica.tools.ToolResult]] into the plain-text
 *  [[agentica.tools.AgentResponse]] envelope consumed by the LLM context window.
 *
 *  Envelope format:
 *  {{{
 *    $ family.verb arg1=val1 arg2=val2
 *    ok                         ← or "error: <code>"
 *    ─ key: value               ← zero or more metadata lines
 *    ─────                      ← separator (only present when body follows)
 *    <body>
 *  }}}
 *
 *  Error format additionally includes `─ hint:` and `─ try:` lines.
 *
 *  Body budget: [[BODY_BUDGET_CHARS]].  Bodies that exceed this limit must have
 *  already been routed to [[SessionScratchpad]] by the tool — the
 *  [[ToolBody.ScratchRef]] variant carries the overflow reference.
 */
object Presentation
{
    /**
     *  Maximum number of characters allowed in an inline body.
     *  Fixed constant (~2000 tokens); not user-configurable.
     */
    val BODY_BUDGET_CHARS: Int = 8000

    /**
     *  Separator line used between the metadata block and the body.
     */
    private val SEPARATOR = "─────"

    /**
     *  Renders a [[ToolResult]] into a plain-text [[AgentResponse]].
     *
     *  @param cmd        The parsed command that produced this result (used for the echo line).
     *  @param result     Typed tool result from the execution layer.
     *  @param durationMs Wall-clock duration of the dispatch-to-render cycle.
     *  @return           [[AgentResponse]] with the formatted text and duration.
     */
    def render(cmd: Command, result: ToolResult, durationMs: Long): AgentResponse =
    {
        val lines = scala.collection.mutable.ArrayBuffer.empty[String]

        // Line 1: command echo
        val argStr = cmd.args.map { case (k, v) =>
            if v.contains(' ') then s"""$k="$v"""" else s"$k=$v"
        }.mkString(" ")
        lines += (if argStr.isEmpty then s"$$ ${cmd.fullName}" else s"$$ ${cmd.fullName} $argStr")

        // Line 2: status
        result.status match
        {
            case ToolStatus.Ok =>
                lines += "ok"

            case ToolStatus.Err(code, message, hints, trySuggestions) =>
                lines += s"error: $code"
                lines += s"─ message: $message"
                hints.foreach(h => lines += s"─ hint: $h")
                trySuggestions.foreach(t => lines += s"""─ try: run(command="$t")""")
        }

        // Metadata lines (only for Ok — errors embed their own hints above)
        if (result.status == ToolStatus.Ok)
        {
            result.metadata.foreach { case (k, v) => lines += s"─ $k: $v" }
        }

        // Body
        result.body match
        {
            case None => ()

            case Some(ToolBody.Inline(text)) =>
                lines += SEPARATOR
                lines += text

            case Some(ToolBody.ScratchRef(ref, sourcePath, sizeBytes, lineCount)) =>
                // Body exceeded budget — content stored in scratchpad
                val sizeKb = f"${sizeBytes / 1024.0}%.1f"
                lines += s"─ stored: $ref"
                lines += s"─ hint: content too large for context ($sizeKb KB, $lineCount lines); use targeted tools"
                lines += s"""─ try: run(command="files.search query=\\"your term\\" path=$sourcePath")"""
                lines += s"""─ try: run(command="files.read path=$sourcePath lines=1-50")"""
                lines += s"""─ try: run(command="llm.summarize text=$ref")"""
        }

        AgentResponse(text = lines.mkString("\n"), durationMs = durationMs)
    }
}
