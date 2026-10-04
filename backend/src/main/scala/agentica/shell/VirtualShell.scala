package agentica.shell

import agentica.llm.ToolSpec
import agentica.observability.TraceLogger
import agentica.tools.{AgentResponse, ExecutionContext, ToolResult, ToolStatus}

/**
 *  Entry point for all agent tool calls.
 *
 *  Called by `AgentLoop` for each provider-native function call. The function
 *  name and JSON arguments are converted to a [[Command]] before dispatch.
 *  Implements the full dispatch pipeline:
 *
 *  1. Convert native name/JSON arguments into a [[Command]].
 *  2. Substitution pass — resolve `$$scratch/<path>` refs in arg values.
 *  3. Handle `help` commands inline without going through a [[Tool]].
 *  4. [[CommandRegistry.dispatch]] → [[ToolResult]].
 *  5. [[Presentation.render]] → [[AgentResponse]] text for the LLM.
 *
 *  All three stages return structured errors; none throw.
 *
 *  @param registry  The command registry with all registered tools.
 */
class VirtualShell(registry: CommandRegistry)
{
    /**
     *  Executes a raw command string and returns the formatted [[AgentResponse]].
     *
     *  @param rawCommand  Internal command string, e.g. `"files_read path=foo.txt"`.
     *  @param ctx         Runtime execution context for this agent run.
     *  @return            [[AgentResponse]] ready for a native tool-result message.
     */
    def execute(rawCommand: String, ctx: ExecutionContext): AgentResponse =
    {
        Tokenizer.parse(rawCommand) match
        {
            case Left(err) =>
                TraceLogger.warn(ctx.traceId, "tool_parse_error",
                    Map("raw" -> rawCommand, "error" -> err.message))
                AgentResponse(
                    text = s"$$ $rawCommand\nerror: invalid_args\n─ message: ${err.message}",
                    durationMs = 0L
                )

            case Right(cmd) => executeCommand(cmd, ctx)
        }
    }

    /**
     *  Executes an already-parsed [[Command]] — the entry point for native
     *  function-calling dispatches (and internally for the text DSL).
     *
     *  @param cmd  Parsed command.
     *  @param ctx  Runtime execution context for this agent run.
     *  @return     [[AgentResponse]] for the tool result.
     */
    def executeCommand(cmd: Command, ctx: ExecutionContext): AgentResponse =
    {
        val t0 = System.currentTimeMillis()

        // Substitution pass: resolve $scratch/<path> refs in all arg values
        val resolvedArgs = resolveRefs(cmd.args, ctx)
        val resolvedCmd  = cmd.copy(args = resolvedArgs)

        // Help command handled inline
        if (resolvedCmd.family == "help" || resolvedCmd.fullName == "help.index")
        {
            val elapsed = System.currentTimeMillis() - t0
            AgentResponse(text = buildHelpResponse(resolvedCmd), durationMs = elapsed)
        }
        else
        {
            val result  = registry.dispatch(resolvedCmd, ctx)
            val elapsed = System.currentTimeMillis() - t0
            Presentation.render(resolvedCmd, result, elapsed)
        }
    }

    /**
     *  All advertised tool schemas for native function calling (including `help`).
     */
    def toolSchemas: List[ToolSpec] = registry.nativeToolSchemas

    /** Returns the canonical display form of a native call without executing it. */
    def describeNative(name: String, argumentsJson: String): String =
    {
        registry.commandFromNative(name, argumentsJson).map(displayCommand)
            .getOrElse(s"$name $argumentsJson")
    }

    /**
     *  Executes a native function call (`tool_calls` entry from a provider response).
     *
     *  @param name          Wire function name, e.g. `files_search`.
     *  @param argumentsJson Raw JSON object string of call arguments.
     *  @param ctx           Runtime execution context.
     *  @return  `(displayCommand, response)` — `displayCommand` is the canonical
     *           `family_verb arg=val` string used for events, logging and persistence.
     */
    def executeNative(name: String, argumentsJson: String, ctx: ExecutionContext): (String, AgentResponse) =
    {
        registry.commandFromNative(name, argumentsJson) match
        {
            case Left(err) =>
                TraceLogger.warn(ctx.traceId, "tool_call_parse_error",
                    Map("name" -> name, "arguments" -> argumentsJson, "error" -> err))
                val display = s"$name $argumentsJson"
                (display, AgentResponse(
                    text = s"$$ $name\nerror: invalid_args\n─ message: $err",
                    durationMs = 0L
                ))
            case Right(cmd) =>
                (displayCommand(cmd), executeCommand(cmd, ctx))
        }
    }

    /** Canonical `family_verb key="value"` display string for a command. */
    private def displayCommand(cmd: Command): String =
    {
        val argStr = cmd.args.map { case (k, v) =>
            if v.contains(' ') || v.contains('"') then s"""$k="${v.replace("\"", "\\\"")}""""
            else s"$k=$v"
        }.mkString(" ")
        if argStr.isEmpty then cmd.fullName else s"${cmd.fullName} $argStr"
    }

    // ── Substitution pass ─────────────────────────────────────────────────────

    /**
     *  Resolves scratchpad references in command argument values.
     *  @param args  Command arguments before substitution.
     *  @param ctx   Execution context containing the session scratchpad.
     *  @return      Argument map with known scratch references replaced by their content.
     */
    private def resolveRefs(args: Map[String, String], ctx: ExecutionContext): Map[String, String] =
    {
        args.map { case (k, v) =>
            if (v.startsWith("$scratch/"))
            {
                ctx.scratchpad.get(v) match
                {
                    case Some(entry) => k -> entry.content
                    case None        =>
                        TraceLogger.warn(ctx.traceId, "scratch_ref_not_found", Map("ref" -> v))
                        k -> v   // pass through unresolved; the tool will produce a not_found error
                }
            }
            else
            {
                k -> v
            }
        }
    }

    // ── Help handler ──────────────────────────────────────────────────────────

    /**
     *  Builds global, family-level, or tool-specific help output.
     *  @param cmd  Internal help command and optional topic.
     *  @return     Rendered help response suitable for a tool-result message.
     */
    private def buildHelpResponse(cmd: Command): String =
    {
        // Accepts: "help", "help files", "help files_read"
        // Represented as: family="help" verb=<topic>, or Command("help","index",Map.empty)
        val topic = cmd.args.get("topic")
            .orElse(if cmd.verb != "index" then Some(cmd.verb) else None)
            .getOrElse("")

        if (topic.isEmpty)
        {
            s"$$ help\nok\n─────\n${registry.helpIndex}"
        }
        else if (registry.allSchemas.exists(_.fullName == topic))
        {
            val separatorIdx = topic.indexOf('_')
            val family = topic.substring(0, separatorIdx)
            val verb   = topic.substring(separatorIdx + 1)
            s"$$ help $topic\nok\n─────\n${registry.helpFor(family, verb)}"
        }
        else
        {
            // Family-level help: list all verbs for this family
            val verbs = registry.allSchemas.filter(_.fullName.startsWith(s"${topic}_"))
            if (verbs.isEmpty)
            {
                s"$$ help $topic\nerror: not_found\n─ message: No tools registered for family '$topic'."
            }
            else
            {
                val body = verbs.map(s => s"  ${s.fullName} — ${s.summary}").mkString("\n")
                s"$$ help $topic\nok\n─────\n$body"
            }
        }
    }
}
