package agentica.shell

import agentica.tools.{ArgError, CommandSchema, ErrorCode, ExecutionContext, Tool, ToolResult, ToolStatus}

/**
 *  Central registry mapping `(family, verb)` → [[Tool]] instances.
 *
 *  All tools are registered here at application startup in `BackendServer`.
 *  This is the only place that enumerates the full tool list — `VirtualShell`
 *  dispatches through the registry; the system prompt tool index is generated
 *  from it; `help` is served directly from it.
 *
 *  Tools are registered via [[register]] at startup.
 *  Thread-safe after startup (no writes after initialization).
 */
class CommandRegistry
{
    // Raw tool registry: fullName → tool (type-erased)
    private val tools = scala.collection.mutable.LinkedHashMap.empty[String, Tool[?, ?]]

    /**
     *  Registers a tool with the registry.
     *  Must be called before any dispatch.  Not thread-safe; intended for startup only.
     *  @param tool  Tool implementation to register.
     */
    def register(tool: Tool[?, ?]): Unit =
    {
        tools.put(tool.name, tool)
    }

    /**
     *  Dispatches a parsed command to its registered tool and returns a typed result.
     *  Returns a [[ToolResult]] with `ToolStatus.Err("not_found")` if no tool matches.
     *  @param cmd  Parsed command from [[Tokenizer]].
     *  @param ctx  Runtime execution context.
     */
    def dispatch(cmd: Command, ctx: ExecutionContext): ToolResult =
    {
        tools.get(cmd.fullName) match
        {
            case None =>
                ToolResult(
                    status = ToolStatus.Err(
                        code    = ErrorCode.NotFound,
                        message = s"Unknown command: ${cmd.fullName}",
                        hints   = List(s"Run 'help ${cmd.family}' to see available verbs for this family.")
                    )
                )
            case Some(tool) =>
                dispatchErased(tool.asInstanceOf[Tool[Any, Any]], cmd.args, ctx)
        }
    }

    private def dispatchErased(tool: Tool[Any, Any], args: Map[String, String], ctx: ExecutionContext): ToolResult =
    {
        tool.validate(args) match
        {
            case Left(ArgError(msg, arg)) =>
                val argHint = arg.map(a => s"Offending argument: '$a'.").getOrElse("")
                ToolResult(
                    status = ToolStatus.Err(
                        code    = ErrorCode.InvalidArgs,
                        message = s"$msg $argHint".trim
                    )
                )
            case Right(input) =>
                val output = tool.execute(input, ctx)
                tool.render(output, ctx)
        }
    }

    /**
     *  Returns the help index: one line per tool verb, formatted for the system prompt.
     *  Format: `  family.verb — summary`
     */
    def helpIndex: String =
    {
        tools.values
            .map(t => s"  ${t.schema.fullName} — ${t.schema.summary}")
            .mkString("\n")
    }

    /**
     *  Returns detailed help for a specific tool verb.
     *  @param family  Tool family, e.g. `"files"`.
     *  @param verb    Tool verb, e.g. `"read"`.
     *  @return        Full schema + example, or an error message if not found.
     */
    def helpFor(family: String, verb: String): String =
    {
        val key = s"$family.$verb"
        tools.get(key) match
        {
            case None => s"Unknown command: $key. Run 'help $family' to see available verbs."
            case Some(tool) =>
                val s    = tool.schema
                val args = s.args.map { a =>
                    val req = if a.required then "(required)" else s"(optional, default: ${a.default.getOrElse("none")})"
                    s"  ${a.name}  $req — ${a.description}"
                }.mkString("\n")
                s"${s.fullName} — ${s.summary}\n\nArguments:\n$args\n\nExample:\n  ${s.example}"
        }
    }

    /**
     *  Returns all registered [[CommandSchema]] objects.
     *  Used for generating test fixtures and system-prompt variants.
     */
    def allSchemas: List[CommandSchema] =
    {
        tools.values.map(_.schema).toList
    }
}
