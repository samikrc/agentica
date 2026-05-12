package agentica.tools.memory

import agentica.tools.{ArgError, ArgSpec, CommandSchema, ExecutionContext, ToolBody, ToolResult, ToolStatus}
import agentica.tools.Tool

/**
 *  Validated input for [[MemoryGet]].
 *  @param key  Entry key to retrieve.
 */
case class MemoryGetInput(key: String)

/**
 *  Raw output of [[MemoryGet.execute]].
 *  @param key    Key queried.
 *  @param value  Retrieved value; [[None]] if the key does not exist.
 *  @param error  Non-empty if execution failed unexpectedly.
 */
case class MemoryGetOutput(key: String, value: Option[String], error: Option[String])

/**
 *  Implements the `memory.get` command.
 *  Retrieves a session-scoped key-value entry from the SQLite `memory_entries` table.
 */
object MemoryGet extends Tool[MemoryGetInput, MemoryGetOutput]
{

    /**
     *  Canonical tool name.
     */
    val name: String = "memory.get"

    /**
     *  Argument schema for help generation and system-prompt tool index.
     */
    val schema: CommandSchema = CommandSchema(
        fullName = "memory.get",
        summary  = "Retrieve a value from session memory by key",
        args     = List(
            ArgSpec("key", "Key to look up in session memory", required = true)
        ),
        example  = """memory.get key=user_name"""
    )

    /**
     *  Validates the `key` argument.
     *  @param args  Raw key-value argument map from the tokenizer.
     *  @return      Validated input or an [[ArgError]].
     */
    def validate(args: Map[String, String]): Either[ArgError, MemoryGetInput] =
    {
        args.get("key") match
        {
            case None      => Left(ArgError("Missing required argument: key", Some("key")))
            case Some(key) => Right(MemoryGetInput(key))
        }
    }

    /**
     *  Retrieves the entry from [[agentica.session.MemoryStore]].
     *  @param input  Validated input.
     *  @param ctx    Runtime execution context.
     *  @return       Raw get output.
     */
    def execute(input: MemoryGetInput, ctx: ExecutionContext): MemoryGetOutput =
    {
        try
        {
            val entry = ctx.memoryStore.get(ctx.session.id, input.key)
            MemoryGetOutput(input.key, entry.map(_.value), None)
        }
        catch
        {
            case ex: Exception => MemoryGetOutput(input.key, None, Some(ex.getMessage))
        }
    }

    /**
     *  Converts raw get output to a [[ToolResult]].
     *  @param output  Raw output from [[execute]].
     *  @return        Typed [[ToolResult]] ready for [[agentica.shell.Presentation]].
     */
    def render(output: MemoryGetOutput): ToolResult =
    {
        output.error match
        {
            case Some(msg) =>
                ToolResult(status = ToolStatus.Err(code = "internal_error", message = msg))
            case None =>
                output.value match
                {
                    case None =>
                        ToolResult(status = ToolStatus.Err(
                            code    = "not_found",
                            message = s"Key not found in session memory: ${output.key}",
                            hints   = List("""Use memory.set key=<key> value=<value> to store a value.""")
                        ))
                    case Some(v) =>
                        ToolResult(
                            status   = ToolStatus.Ok,
                            metadata = Map("key" -> output.key),
                            body     = Some(ToolBody.Inline(v))
                        )
                }
        }
    }
}
