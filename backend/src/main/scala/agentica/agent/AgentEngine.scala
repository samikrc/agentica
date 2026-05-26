package agentica.agent

import agentica.session.{Session, Message}
import java.util.concurrent.atomic.AtomicBoolean

/**
 *  Pluggable agent engine interface.
 *  Phase 1: one implementation (AgentLoop stub — single LLM call, no tool dispatch).
 *  Phase 2+: full plan→act→observe loop with virtual shell dispatch.
 */
trait AgentEngine
{

    /**
     *  Runs the agent for one user turn.
     *  @param session     Current session (contains model, rootPath, etc.).
     *  @param history     Full message history for this session (for context assembly).
     *  @param userMsg     The new user message just appended.
     *  @param traceId     Trace ID for this turn (propagated to logger + token accounting).
     *  @param cancelFlag  Polled between iterations and tool calls; set externally to cancel the run.
     *  @param emitToken    Called to emit each streamed text token from the LLM.
     *  @param emitEvent    Called to emit structured lifecycle SSE events.
     */
    def run(
        session:    Session,
        history:    List[Message],
        userMsg:    Message,
        traceId:    String,
        cancelFlag: AtomicBoolean,
        emitToken:  String => Unit,
        emitEvent:  AgentEvent => Unit
    ): Unit
}

/**
 *  Structured lifecycle events emitted by [[AgentEngine]] implementations
 *  during a single agent turn, propagated to the SSE stream via [[Routes]].
 */
enum AgentEvent
{
    /**
     *  Marks the start of a new plan-act-observe iteration.
     *  @param iteration  1-based iteration counter.
     */
    case IterationBoundary(iteration: Int)

    /**
     *  Emitted immediately before the LLM call on each iteration.
     *  @param iteration   1-based iteration counter.
     *  @param model       Model name being called.
     *  @param msgCount    Number of messages in the assembled context.
     */
    case LLMCallStart(iteration: Int, model: String, msgCount: Int)

    /**
     *  Signals that the agent is about to invoke a tool.
     *  @param tool   Full tool name (e.g. `"files.read"`).
     *  @param input  JSON-serialised input arguments.
     */
    case ToolCallStart(tool: String, input: String)

    /**
     *  Carries the result of a completed tool invocation.
     *  @param tool        Full tool name.
     *  @param output      JSON-serialised tool output.
     *  @param durationMs  Wall-clock duration of the tool call in milliseconds.
     */
    case ToolCallResult(tool: String, output: String, durationMs: Long)

    /**
     *  Signals successful completion of the turn; carries the persisted message ID.
     *  @param assistantMessageId  UUID of the assistant [[Message]] written to the DB.
     *  @param sessionTitle        Optional generated session title for the first completed turn.
     */
    case Final(assistantMessageId: String, sessionTitle: Option[String])

    /**
     *  Signals that the turn was cancelled by the user before completion.
     */
    case Cancelled

    /**
     *  Signals an unrecoverable error during the turn.
     *  @param message  Human-readable error description.
     */
    case AgentError(message: String)

    /**
     *  Signals that a sensitive tool requires user permission before proceeding.
     *  The agent run is suspended until the UI modal posts a decision.
     *  @param tool     Canonical tool name, e.g. `"files.write"`.
     *  @param path     Absolute resolved path the tool intends to access, if applicable.
     *  @param options  Human-readable TTL option labels presented to the user.
     */
    case PermissionRequired(tool: String, path: Option[String], options: List[String])

    /**
     *  Emitted by long-running tools to report incremental progress.
     *  @param tool     Canonical tool name, e.g. `"files.read_pdf"`.
     *  @param message  Human-readable progress description, e.g. `"Transcribing page 3 / 12"`.
     *  @param current  Current step (1-based).
     *  @param total    Total number of steps.
     */
    case ToolProgress(tool: String, message: String, current: Int, total: Int)
}
