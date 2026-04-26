package agentica.agent

import agentica.llm.LLMProvider
import agentica.observability.{TraceLogger, TokenAccounting}
import agentica.session.{Session, Message, MessageStore}

/** Phase 1 stub: single LLM call, no tool dispatch.
 *  Fulfils the AgentEngine contract so the full SSE pipeline is exercised.
 *  Phase 2 replaces this with the full plan→act→observe loop + virtual shell.
 */
class AgentLoop(
    llm:             LLMProvider,
    messageStore:    MessageStore,
    tokenAccounting: TokenAccounting
) extends AgentEngine
{

    /** Executes a single agent turn: streams one LLM call, records token usage,
     *  persists the assistant reply, and emits [[AgentEvent]] lifecycle events.
     *  On LLM error, fires [[AgentEvent.AgentError]] and returns early.
     *  @param session  Current session metadata (model, id, rootPath, etc.).
     *  @param history  Assembled message history passed to the LLM as context.
     *  @param userMsg  The new user message (appended to history before the call).
     *  @param traceId  Trace identifier propagated to logging and token accounting.
     *  @param onToken  Callback invoked for each streamed text token.
     *  @param onEvent  Callback invoked for structured lifecycle events.
     */
    def run(
        session: Session,
        history: List[Message],
        userMsg: Message,
        traceId: String,
        onToken: String => Unit,
        onEvent: AgentEvent => Unit
    ): Unit =
    {
        TraceLogger.info(traceId, "agent_loop_start", Map("sessionId" -> session.id, "model" -> session.model))
        onEvent(AgentEvent.IterationBoundary(1))

        val buf            = StringBuilder()
        val wrappedOnToken = (tok: String) => { buf.append(tok); onToken(tok) }

        val usage = try
        {
            llm.stream(history :+ userMsg, wrappedOnToken)
        }
        catch
        {
            case ex: Exception =>
                TraceLogger.error(traceId, "llm_stream_error", Map("error" -> ex.getMessage))
                onEvent(AgentEvent.AgentError(ex.getMessage))
                return
        }

        tokenAccounting.record(traceId, session.id, usage)

        val assistantMsg = messageStore.append(session.id, "assistant", buf.toString)
        TraceLogger.info(traceId, "agent_loop_complete", Map(
            "sessionId"        -> session.id,
            "assistantMsgId"   -> assistantMsg.id,
            "promptTokens"     -> usage.promptTokens.toString,
            "completionTokens" -> usage.completionTokens.toString
        ))
        onEvent(AgentEvent.Final(assistantMsg.id))
    }
}
