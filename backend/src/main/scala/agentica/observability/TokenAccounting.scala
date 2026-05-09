package agentica.observability

import agentica.llm.LLMUsage
import agentica.session.{RunStore, TokenUsage}
import java.time.Instant
import java.util.UUID

/** Records per-LLM-call token and latency data into the token_usage table.
 *  Also emits a structured log line via TraceLogger for real-time observability.
 */
class TokenAccounting(runStore: RunStore)
{

    /** Persists LLM usage and emits a structured completion log event.
     *  @param traceId    Trace identifier for the agent turn.
     *  @param sessionId  Session identifier associated with the LLM call.
     *  @param usage      Provider-reported usage and latency values.
     */
    def record(traceId: String, sessionId: String, usage: LLMUsage): Unit =
    {
        val row = TokenUsage(
            id               = UUID.randomUUID().toString,
            traceId          = traceId,
            sessionId        = sessionId,
            model            = usage.model,
            promptTokens     = usage.promptTokens,
            completionTokens = usage.completionTokens,
            latencyMs        = usage.latencyMs,
            createdAt        = Instant.now().toString
        )
        runStore.insertTokenUsage(row)
        TraceLogger.info(
            traceId = traceId,
            msg     = "llm_call_complete",
            extra   = Map(
                "model"            -> usage.model,
                "promptTokens"     -> usage.promptTokens.toString,
                "completionTokens" -> usage.completionTokens.toString,
                "latencyMs"        -> usage.latencyMs.toString,
                "sessionId"        -> sessionId
            )
        )
    }
}
