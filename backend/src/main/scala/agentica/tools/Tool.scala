package agentica.tools

import agentica.agent.AgentEvent
import agentica.permissions.{GrantDecision, ScopeStore}
import agentica.session.{MemoryStore, Session}
import agentica.shell.SessionScratchpad
import java.util.concurrent.SynchronousQueue

// ─── Argument schema ──────────────────────────────────────────────────────────

/**
 *  Descriptor for a single argument accepted by a tool.
 *  @param name         Argument name as it appears in the DSL, e.g. `"path"`.
 *  @param description  Human-readable description shown in help output.
 *  @param required     Whether the argument must be present for the tool to execute.
 *  @param default      Optional default value shown in help text.
 */
case class ArgSpec(
    name:        String,
    description: String,
    required:    Boolean,
    default:     Option[String] = None
)

/**
 *  Full schema for a single tool verb, used by [[agentica.shell.CommandRegistry]]
 *  to generate help text and system-prompt tool index.
 *  @param fullName    Canonical `family.verb` name, e.g. `"files.read"`.
 *  @param summary     One-line description for the help index.
 *  @param args        Ordered list of accepted argument specifications.
 *  @param example     An example invocation string shown in detailed help.
 */
case class CommandSchema(
    fullName: String,
    summary:  String,
    args:     List[ArgSpec],
    example:  String
)

// ─── Execution result types ───────────────────────────────────────────────────

/**
 *  Outcome of a tool execution's validation stage.
 *  @param message  Human-readable description of what was wrong.
 *  @param arg      Name of the offending argument, if applicable.
 */
case class ArgError(message: String, arg: Option[String] = None)

/**
 *  Closed set of tool execution status codes.
 */
enum ToolStatus
{
    /**
     *  The tool executed successfully.
     */
    case Ok

    /**
     *  The tool failed with a structured error.
     *  @param code            Short machine-readable code: `not_found`, `permission_denied`,
     *                         `invalid_args`, `path_escaped`, `cancelled`, `internal_error`.
     *  @param message         Human-readable error description.
     *  @param hints           Contextual hints to surface in the presentation layer.
     *  @param trySuggestions  Ready-to-run `run(command="...")` invocations the agent can try.
     */
    case Err(
        code:            String,
        message:         String,
        hints:           List[String] = Nil,
        trySuggestions:  List[String] = Nil
    )
}

/**
 *  Body of a tool result — either text content or a scratchpad reference.
 */
enum ToolBody
{
    /** Inline text body small enough to fit in the context window. */
    case Inline(text: String)
    /** Full content was stored in the scratchpad; ref is `$scratch/<path>`. */
    case ScratchRef(ref: String, sourcePath: String, sizeBytes: Long, lineCount: Int)
}

/**
 *  Typed output of the execution layer, before presentation rendering.
 *  @param status    Execution outcome.
 *  @param metadata  Key-value pairs surfaced as `─ key: value` metadata lines.
 *  @param body      Optional content body.
 */
case class ToolResult(
    status:   ToolStatus,
    metadata: Map[String, String] = Map.empty,
    body:     Option[ToolBody]    = None
)

/**
 *  Presentation-layer output: plain text ready for the LLM context window.
 *  @param text       Formatted text in the `AgentResponse` envelope format.
 *  @param durationMs Wall-clock time from dispatch to render completion.
 */
case class AgentResponse(text: String, durationMs: Long)

// ─── Execution context ────────────────────────────────────────────────────────

/**
 *  Runtime context threaded through every tool call within a single agent run.
 *  @param session          The active session (carries `rootPath`, `id`, etc.).
 *  @param traceId          Trace identifier for this agent run, used in all log lines.
 *  @param scopeStore       Permission grant store; checked by sensitive tools before execute.
 *  @param scratchpad       Session-scoped large-output cache.
 *  @param memoryStore      Session-scoped key-value memory store.
 *  @param onEvent          Callback to emit structured [[AgentEvent]] SSE events from within a tool.
 *  @param permissionLatch  Queue on which [[VirtualShell]] blocks awaiting a [[GrantDecision]] from the UI.
 */
case class ExecutionContext(
    session:          Session,
    traceId:          String,
    scopeStore:       ScopeStore,
    scratchpad:       SessionScratchpad,
    memoryStore:      MemoryStore,
    onEvent:          AgentEvent => Unit,
    permissionLatch:  SynchronousQueue[GrantDecision]
)

// ─── Tool trait ───────────────────────────────────────────────────────────────

/**
 *  Contract for all Agentica tool implementations.
 *
 *  The three-stage pipeline — `validate → execute → render` — is the sole
 *  contract between [[agentica.shell.VirtualShell]] and a tool implementation.
 *  Each stage is independently testable.
 *
 *  @tparam I  Validated input type produced by `validate`.
 *  @tparam O  Raw output type produced by `execute`.
 */
trait Tool[I, O]
{
    /**
     *  Canonical `family.verb` name, e.g. `"files.read"`.
     *  Must match the key used when registering with [[agentica.shell.CommandRegistry]].
     */
    def name: String

    /** Full argument schema; used by [[agentica.shell.CommandRegistry]] for help and system prompt generation. */
    def schema: CommandSchema

    /**
     *  Validates raw argument strings from the parsed [[agentica.shell.Command]].
     *  Returns a typed input value or a structured error without touching the filesystem.
     *  @param args  Raw key-value argument map from the tokenizer.
     */
    def validate(args: Map[String, String]): Either[ArgError, I]

    /**
     *  Executes the tool using the validated input.
     *  May perform I/O; must respect `ctx.session.rootPath` via [[agentica.shell.PathSandbox]].
     *  @param input  Validated input produced by `validate`.
     *  @param ctx    Runtime execution context.
     */
    def execute(input: I, ctx: ExecutionContext): O

    /**
     *  Converts the raw execution output to a typed [[ToolResult]] ready for [[agentica.shell.Presentation]].
     *  @param output  Raw output produced by `execute`.
     */
    def render(output: O): ToolResult
}
