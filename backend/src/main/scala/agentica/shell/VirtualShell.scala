package agentica.shell

import agentica.llm.ToolSpec
import agentica.observability.TraceLogger
import agentica.permissions.PermissionMiddleware
import agentica.shell.compression.CompressionMiddleware
import agentica.tools.{AgentResponse, ExecutionContext, ToolResult, ToolStatus}
import java.nio.file.Files

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
 *  @param registry     The command registry with all registered tools.
 *  @param middleware   Additional [[ToolMiddleware]] components appended after
 *                      the always-installed [[agentica.permissions.PermissionMiddleware]].
 */
class VirtualShell(
    registry:   CommandRegistry,
    middleware: List[ToolMiddleware] = Nil
)
{
    /** Maximum file size re-hydrated from disk when a scratch ref has expired. */
    private val HydrationMaxBytes: Long = 10L * 1024 * 1024

    /**
     *  The dispatch chain: permission gating first, then result compression,
     *  then caller-supplied components. `after` hooks run in reverse order, so
     *  compression sees the fully-transformed result just before presentation.
     *  Run-scoped components are passed per call via `executeCommand`'s `extra`.
     */
    private val chain: List[ToolMiddleware] =
        new PermissionMiddleware(registry) +: CompressionMiddleware() +: middleware
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
     *  The middleware chain wraps dispatch: `before` hooks in order may
     *  short-circuit with a [[ToolResult]]; `after` hooks in reverse order may
     *  transform the result. `extra` carries run-scoped components on top of
     *  the shell's base chain.
     *
     *  @param cmd    Parsed command.
     *  @param ctx    Runtime execution context for this agent run.
     *  @param extra  Additional middleware applied after the base chain for this call.
     *  @return       [[AgentResponse]] for the tool result.
     */
    def executeCommand(cmd: Command, ctx: ExecutionContext, extra: List[ToolMiddleware] = Nil): AgentResponse =
    {
        val t0 = System.currentTimeMillis()

        // Substitution pass: resolve $scratch/<path> refs in all arg values
        val resolvedArgs = resolveRefs(cmd, ctx)
        val resolvedCmd  = cmd.copy(args = resolvedArgs)

        // Help command handled inline
        if (resolvedCmd.family == "help" || resolvedCmd.fullName == "help.index")
        {
            val elapsed = System.currentTimeMillis() - t0
            AgentResponse(text = buildHelpResponse(resolvedCmd), durationMs = elapsed)
        }
        else
        {
            // Run the middleware chain: `before` hooks execute in order and the
            // first Some(result) short-circuits dispatch entirely; on None the
            // command dispatches and `after` hooks transform the result in
            // reverse order (first-listed middleware is outermost).
            val runChain = chain ++ extra
            val call     = ToolCall(resolvedCmd, ctx)
            val shorted  = runChain.iterator.map(_.before(call)).collectFirst { case Some(r) => r }
            val result   = shorted match
            {
                case Some(r) => r
                case None    =>
                    val dispatched = registry.dispatch(resolvedCmd, ctx)
                    runChain.reverseIterator.foldLeft(dispatched) { (r, m) => m.after(call, r) }
            }
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
     *  @param extra         Run-scoped middleware applied after the base chain.
     *  @return  `(displayCommand, response)` — `displayCommand` is the canonical
     *           `family_verb arg=val` string used for events, logging and persistence.
     */
    def executeNative(
        name:          String,
        argumentsJson: String,
        ctx:           ExecutionContext,
        extra:         List[ToolMiddleware] = Nil
    ): (String, AgentResponse) =
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
                (displayCommand(cmd), executeCommand(cmd, ctx, extra))
        }
    }

    /** Canonical `family_verb key="value"` display string for a command. */
    private def displayCommand(cmd: Command): String =
    {
        val argStr = cmd.args.map { case (k, v) =>
            if (v.contains(' ') || v.contains('"')) s"""$k="${v.replace("\"", "\\\"")}""""
            else s"$k=$v"
        }.mkString(" ")
        if (argStr.isEmpty) cmd.fullName else s"${cmd.fullName} $argStr"
    }

    // ── Substitution pass ─────────────────────────────────────────────────────

    /**
     *  Resolves scratchpad references in command argument values.
     *
     *  `files_search`'s `ref` and `path` arguments are exempt: they consume the
     *  reference itself rather than the referenced content. A file-backed ref
     *  that is no longer in the pad (a later run) is re-hydrated by re-reading
     *  the workspace file, so refs survive across runs.
     *
     *  @param cmd  Command whose arguments are being resolved.
     *  @param ctx  Execution context containing the session scratchpad.
     *  @return     Argument map with known scratch references replaced by their content.
     */
    private def resolveRefs(cmd: Command, ctx: ExecutionContext): Map[String, String] =
    {
        val keepRef = cmd.fullName == "files_search"
        cmd.args.map { case (k, v) =>
            if (keepRef && (k == "ref" || k == "path"))
            {
                k -> v
            }
            else if (v.startsWith("$scratch/"))
            {
                ctx.scratchpad.get(v) match
                {
                    case Some(entry) => k -> entry.content
                    case None        =>
                        hydrateRef(v, ctx) match
                        {
                            case Some(entry) => k -> entry.content
                            case None        =>
                                TraceLogger.warn(ctx.traceId, "scratch_ref_not_found", Map("ref" -> v))
                                k -> v   // pass through unresolved; the tool will produce a not_found error
                        }
                }
            }
            else
            {
                k -> v
            }
        }
    }

    /**
     *  Re-hydrates a file-backed scratch ref by re-reading the workspace file.
     *  Computed keys (`__result_N__`) have no backing file and are not hydrated.
     *  @param ref  Scratch ref, e.g. `$scratch/data/report.md`.
     *  @param ctx  Execution context providing the workspace root and pad.
     *  @return     The re-stored entry, or `None` when the file is unavailable.
     */
    private def hydrateRef(ref: String, ctx: ExecutionContext): Option[ScratchEntry] =
    {
        val key = ref.stripPrefix("$scratch/")
        if (key.startsWith("__result_")) return None
        val rootStr = ctx.session.rootPath.getOrElse("")
        PathSandbox.resolve(rootStr, key).toOption
            .filter(Files.isRegularFile(_))
            .filter(p => Files.size(p) <= HydrationMaxBytes)
            .flatMap { path =>
                try
                {
                    val content = Files.readString(path)
                    val now     = System.currentTimeMillis()
                    val entry   = ScratchEntry(
                        content      = content,
                        sizeBytes    = Files.size(path),
                        lineCount    = content.count(_ == '\n') + 1,
                        sourcePath   = key,
                        lastModified = Files.getLastModifiedTime(path).toMillis,
                        storedAt     = now
                    )
                    ctx.scratchpad.store(key, entry)
                    Some(entry)
                }
                catch
                {
                    case _: Throwable => None
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
            .orElse(if (cmd.verb != "index") Some(cmd.verb) else None)
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
