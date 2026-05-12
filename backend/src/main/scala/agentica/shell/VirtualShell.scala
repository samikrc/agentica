package agentica.shell

import agentica.observability.TraceLogger
import agentica.tools.{AgentResponse, ExecutionContext, ToolResult, ToolStatus}

/**
 *  Entry point for all agent tool calls.
 *
 *  Called by `AgentLoop` for each `run(command="...")` extracted by `ToolCallParser`.
 *  Implements the full dispatch pipeline:
 *
 *  1. [[Tokenizer.parse]] — parse raw command string into [[Command]].
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
     *  @param rawCommand  The unescaped value of `run(command="...")`, e.g. `"files.read path=foo.txt"`.
     *  @param ctx         Runtime execution context for this agent run.
     *  @return            [[AgentResponse]] ready to be injected as a `[TOOL RESULT]` user turn.
     */
    def execute(rawCommand: String, ctx: ExecutionContext): AgentResponse =
    {
        val t0 = System.currentTimeMillis()

        Tokenizer.parse(rawCommand) match
        {
            case Left(err) =>
                TraceLogger.warn(ctx.traceId, "tool_parse_error",
                    Map("raw" -> rawCommand, "error" -> err.message))
                val elapsed = System.currentTimeMillis() - t0
                AgentResponse(
                    text = s"$$ $rawCommand\nerror: invalid_args\n─ message: ${err.message}",
                    durationMs = elapsed
                )

            case Right(cmd) =>
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
    }

    // ── Substitution pass ─────────────────────────────────────────────────────

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

    private def buildHelpResponse(cmd: Command): String =
    {
        // Accepts: "help", "help files", "help files.read"
        // Represented as: family="help" verb=<topic>, or Command("help","index",Map.empty)
        val topic = cmd.args.get("topic")
            .orElse(if cmd.verb != "index" then Some(cmd.verb) else None)
            .getOrElse("")

        if (topic.isEmpty)
        {
            s"$$ help\nok\n─────\n${registry.helpIndex}"
        }
        else if (topic.contains('.'))
        {
            val parts  = topic.split('.').toList
            val family = parts.head
            val verb   = parts.tail.mkString(".")
            s"$$ help $topic\nok\n─────\n${registry.helpFor(family, verb)}"
        }
        else
        {
            // Family-level help: list all verbs for this family
            val verbs = registry.allSchemas.filter(_.fullName.startsWith(s"$topic."))
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
