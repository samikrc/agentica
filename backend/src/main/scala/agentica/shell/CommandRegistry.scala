package agentica.shell

import agentica.llm.ToolSpec
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

    /**
     *  Runs validation, execution, and rendering for a type-erased registered tool.
     *  @param tool  Registered tool with erased input and output types.
     *  @param args  Raw string arguments supplied by the native call.
     *  @param ctx   Execution context for the current run.
     *  @return      Render-ready typed tool result.
     */
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
     *  Format: `  family_verb — summary`
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
        val key = s"${family}_$verb"
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

    // ── Native function calling ───────────────────────────────────────────────

    /**
     *  All registered tools as native function-calling [[ToolSpec]]s, plus a
     *  synthetic `help` spec for progressive schema discovery.
     *
     *  All DSL argument values are strings; the JSON schemas therefore declare
     *  every parameter `"type": "string"`. Numeric/boolean coercion happens in
     *  `Tool.validate` exactly as it does for the text DSL.
     */
    def nativeToolSchemas: List[ToolSpec] =
    {
        val toolSpecs = allSchemas.map { s =>
            val properties = ujson.Obj.from(s.args.map { a =>
                a.name -> ujson.Obj("type" -> "string", "description" -> a.description)
            })
            val parameters = ujson.Obj(
                "type"       -> "object",
                "properties" -> properties,
                "required"   -> ujson.Arr.from(s.args.filter(_.required).map(a => ujson.Str(a.name)))
            )
            val description = s"${s.summary} Example: ${s.example}"
            ToolSpec(s.fullName, description, parameters)
        }
        val helpSpec = ToolSpec(
            name        = "help",
            description = "Show usage details for a tool: help() lists all tools, help(topic=\"files\") " +
                          "lists a family's verbs, help(topic=\"files_read\") shows the full argument schema.",
            parameters  = ujson.Obj(
                "type"       -> "object",
                "properties" -> ujson.Obj(
                    "topic" -> ujson.Obj("type" -> "string",
                        "description" -> "Tool family (e.g. files) or full name (e.g. files_read)")
                ),
                "required"   -> ujson.Arr()
            )
        )
        helpSpec +: toolSpecs
    }

    /**
     *  Converts a native tool call into a [[Command]].
     *
     *  @param name          Wire function name, e.g. `files_search` (or `help`).
     *  @param argumentsJson Raw JSON object string of call arguments.
     *  @return  `Right(cmd)` on success; `Left(errorMessage)` for unknown names or
     *           malformed argument JSON.
     */
    def commandFromNative(name: String, argumentsJson: String): Either[String, Command] =
    {
        if (name == "help")
        {
            val args = parseArgumentsJson(argumentsJson) match
            {
                case Left(err)  => return Left(err)
                case Right(map) => map
            }
            val topic = args.get("topic").getOrElse("")
            Right(Command("help", "index", if topic.isEmpty then Map.empty else Map("topic" -> topic)))
        }
        else
        {
            tools.get(name) match
            {
                case None => Left(s"Unknown tool: $name")
                case Some(_) =>
                    val separatorIdx = name.indexOf('_')
                    if (separatorIdx <= 0 || separatorIdx == name.length - 1)
                    {
                        Left(s"Invalid canonical tool name: $name")
                    }
                    else
                    {
                        parseArgumentsJson(argumentsJson).map { args =>
                            Command(name.substring(0, separatorIdx), name.substring(separatorIdx + 1), args)
                        }
                    }
            }
        }
    }

    /** Parses a JSON object of call arguments into a string map. */
    private def parseArgumentsJson(raw: String): Either[String, Map[String, String]] =
    {
        try
        {
            val json = ujson.read(raw)
            if (!json.isInstanceOf[ujson.Obj])
            {
                Left(s"arguments must be a JSON object, got: $raw")
            }
            else
            {
                Right(json.obj.map { case (k, v) =>
                    k -> (v match
                    {
                        case ujson.Str(s) => s
                        case ujson.Num(n) => if n.isWhole then n.toLong.toString else n.toString
                        case ujson.Bool(b) => b.toString
                        case other        => other.render()
                    })
                }.toMap)
            }
        }
        catch
        {
            case t: Throwable => Left(s"malformed arguments JSON: ${t.getMessage}")
        }
    }
}
