package agentica.tools.memory

import agentica.tools.{ArgError, ArgSpec, CommandSchema, ErrorCode, ExecutionContext, ToolResult, ToolStatus}
import agentica.tools.Tool

/**
 *  Validated input for [[MemorySet]].
 *  @param key    Entry key to store under.
 *  @param value  String value to persist.
 */
case class MemorySetInput(key: String, value: String)

/**
 *  Raw output of [[MemorySet.execute]].
 *  @param key    Key that was stored.
 *  @param error  Non-empty if execution failed.
 */
case class MemorySetOutput(key: String, error: Option[String])

/**
 *  Implements the `memory.set` command.
 *  Upserts a session-scoped key-value entry into the SQLite `memory_entries` table.
 */
object MemorySet extends Tool[MemorySetInput, MemorySetOutput]
{

    /**
     *  Canonical tool name.
     */
    val name: String = "memory.set"

    /**
     *  Argument schema for help generation and system-prompt tool index.
     */
    val schema: CommandSchema = CommandSchema(
        fullName = "memory.set",
        summary  = "Persist a key-value pair in session memory",
        args     = List(
            ArgSpec("key",   "Unique key to store the value under",     required = true),
            ArgSpec("value", "String value to associate with the key",   required = true)
        ),
        example  = """memory.set key=user_name value="Alice"""
    )

    /**
     *  Validates the `key` and `value` arguments.
     *  @param args  Raw key-value argument map from the tokenizer.
     *  @return      Validated input or an [[ArgError]].
     */
    def validate(args: Map[String, String]): Either[ArgError, MemorySetInput] =
    {
        (args.get("key"), args.get("value")) match
        {
            case (None, _)          => Left(ArgError("Missing required argument: key",   Some("key")))
            case (_, None)          => Left(ArgError("Missing required argument: value", Some("value")))
            case (Some(k), Some(v)) => Right(MemorySetInput(k, v))
        }
    }

    /**
     *  Upserts the key-value entry via [[agentica.session.MemoryStore]].
     *  @param input  Validated input.
     *  @param ctx    Runtime execution context.
     *  @return       Raw set output.
     */
    def execute(input: MemorySetInput, ctx: ExecutionContext): MemorySetOutput =
    {
        try
        {
            ctx.memoryStore.set(ctx.session.id, input.key, input.value)
            MemorySetOutput(input.key, None)
        }
        catch
        {
            case ex: Exception => MemorySetOutput(input.key, Some(ex.getMessage))
        }
    }

    /**
     *  Converts raw set output to a [[ToolResult]].
     *  @param output  Raw output from [[execute]].
     *  @return        Typed [[ToolResult]] ready for [[agentica.shell.Presentation]].
     */
    def render(output: MemorySetOutput, ctx: ExecutionContext): ToolResult =
    {
        output.error match
        {
            case Some(msg) =>
                ToolResult(status = ToolStatus.Err(code = ErrorCode.InternalError, message = msg))
            case None =>
                ToolResult(
                    status   = ToolStatus.Ok,
                    metadata = Map("key" -> output.key)
                )
        }
    }
}
