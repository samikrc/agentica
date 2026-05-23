package agentica.tools.memory

import agentica.session.MemoryEntry
import agentica.tools.{ArgError, ArgSpec, CommandSchema, ErrorCode, ExecutionContext, ToolBody, ToolResult, ToolStatus}
import agentica.tools.Tool

/**
 *  Validated input for [[MemoryList]].
 *  No arguments — always lists all entries for the current session.
 */
case class MemoryListInput()

/**
 *  Raw output of [[MemoryList.execute]].
 *  @param entries  All memory entries for the session, ordered by key.
 *  @param error    Non-empty if execution failed.
 */
case class MemoryListOutput(entries: List[MemoryEntry], error: Option[String])

/**
 *  Implements the `memory.list` command.
 *  Lists all session-scoped key-value entries from the SQLite `memory_entries` table.
 */
object MemoryList extends Tool[MemoryListInput, MemoryListOutput]
{

    /**
     *  Canonical tool name.
     */
    val name: String = "memory.list"

    /**
     *  Argument schema for help generation and system-prompt tool index.
     */
    val schema: CommandSchema = CommandSchema(
        fullName = "memory.list",
        summary  = "List all key-value pairs in session memory",
        args     = Nil,
        example  = "memory.list"
    )

    /**
     *  Validates arguments (none required).
     *  @param args  Raw key-value argument map from the tokenizer.
     *  @return      Always [[Right]] with an empty [[MemoryListInput]].
     */
    def validate(args: Map[String, String]): Either[ArgError, MemoryListInput] =
    {
        Right(MemoryListInput())
    }

    /**
     *  Lists all entries from [[agentica.session.MemoryStore]] for the current session.
     *  @param input  Validated input (unused).
     *  @param ctx    Runtime execution context.
     *  @return       Raw list output.
     */
    def execute(input: MemoryListInput, ctx: ExecutionContext): MemoryListOutput =
    {
        try
        {
            val entries = ctx.memoryStore.list(ctx.session.id)
            MemoryListOutput(entries, None)
        }
        catch
        {
            case ex: Exception => MemoryListOutput(Nil, Some(ex.getMessage))
        }
    }

    /**
     *  Converts raw list output to a [[ToolResult]].
     *  @param output  Raw output from [[execute]].
     *  @return        Typed [[ToolResult]] ready for [[agentica.shell.Presentation]].
     */
    def render(output: MemoryListOutput, ctx: ExecutionContext): ToolResult =
    {
        output.error match
        {
            case Some(msg) =>
                ToolResult(status = ToolStatus.Err(code = ErrorCode.InternalError, message = msg))
            case None =>
                val lines = if (output.entries.isEmpty)
                {
                    "(no entries in session memory)"
                }
                else
                {
                    output.entries.map(e => s"${e.key} = ${e.value}  [${e.updatedAt}]").mkString("\n")
                }
                ToolResult(
                    status   = ToolStatus.Ok,
                    metadata = Map("count" -> output.entries.length.toString),
                    body     = Some(ToolBody.Inline(lines))
                )
        }
    }
}
