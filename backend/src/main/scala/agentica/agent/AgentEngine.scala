package agentica.agent

import agentica.session.{Session, Message}

/** Pluggable agent engine interface.
 *  Phase 1: one implementation (AgentLoop stub — single LLM call, no tool dispatch).
 *  Phase 2+: full plan→act→observe loop with virtual shell dispatch.
 */
trait AgentEngine
{

    /** Run the agent for one user turn.
     *  @param session    Current session (contains model, rootPath, etc.)
     *  @param history    Full message history for this session (for context assembly).
     *  @param userMsg    The new user message just appended.
     *  @param traceId    Trace ID for this turn (propagated to logger + token accounting).
     *  @param onToken    Called for each streamed text token.
     *  @param onEvent    Called for structured SSE events (tool-call-start, tool-call-result, final, cancelled).
     */
    def run(
        session: Session,
        history: List[Message],
        userMsg: Message,
        traceId: String,
        onToken: String => Unit,
        onEvent: AgentEvent => Unit
    ): Unit
}

enum AgentEvent
{
    case IterationBoundary(iteration: Int)
    case ToolCallStart(tool: String, input: String)
    case ToolCallResult(tool: String, output: String, durationMs: Long)
    case Final(assistantMessageId: String)
    case Cancelled
    case AgentError(message: String)
}
