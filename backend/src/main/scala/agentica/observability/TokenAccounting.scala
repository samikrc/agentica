package agentica.observability

import agentica.llm.LLMResponse
import agentica.session.{RunStore, TokenUsage}
import java.time.Instant
import java.util.UUID

/**
 *  Records per-LLM-call token and latency data into the token_usage table.
 *  Also emits a structured log line via [[TraceLogger]] for real-time observability.
 *  @param runStore  Persistence layer used to insert [[TokenUsage]] rows.
 */
class TokenAccounting(runStore: RunStore)
{
    /**
     *  Persists LLM response metrics and emits a structured completion log event.
     *  @param traceId      Trace identifier for the agent turn.
     *  @param sessionId    Session identifier associated with the LLM call.
     *  @param llmResponse  Provider-reported usage and latency values.
     */
    def record(traceId: String, sessionId: String, llmResponse: LLMResponse): Unit =
    {
        val row = TokenUsage(
            id               = UUID.randomUUID().toString,
            traceId          = traceId,
            sessionId        = sessionId,
            model            = llmResponse.model,
            promptTokens     = llmResponse.promptTokens,
            completionTokens = llmResponse.completionTokens,
            latencyMs        = llmResponse.latencyMs,
            createdAt        = Instant.now().toString
        )
        runStore.insertTokenUsage(row)
        TraceLogger.info(
            traceId = traceId,
            msg     = "llm_call_complete",
            extra   = Map(
                "model"            -> llmResponse.model,
                "promptTokens"     -> llmResponse.promptTokens.toString,
                "completionTokens" -> llmResponse.completionTokens.toString,
                "latencyMs"        -> llmResponse.latencyMs.toString,
                "sessionId"        -> sessionId
            )
        )
    }
}
