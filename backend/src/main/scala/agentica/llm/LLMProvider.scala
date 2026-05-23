package agentica.llm

import agentica.session.Message

/**
 *  Abstraction over local and cloud LLM providers.
 *  All providers must be thread-safe (called from virtual threads).
 */
trait LLMProvider
{
    /**
     *  Calls the Chat Completions API with a full message list.
     *  @param messages  Full conversation history (system, user, assistant turns).
     *  @param onToken   Called for each content chunk.
     *  @return          [[LLMResponse]] with token counts, latency, and no responseId.
     */
    def streamChatCompletions(
        messages: List[Message],
        onToken:  String => Unit
    ): LLMResponse

    /**
     *  Calls the Responses API with a structured input message list.
     *  Supports stateful multi-turn threading via `previousResponseId`.
     *  On the first call (`previousResponseId = None`) the full conversation context is
     *  expected in `input`; on continuation calls only the new single message need be present.
     *  Defaults to `throw UnsupportedOperationException` — only implemented by providers
     *  that support the OpenAI Responses API.
     *  @param input               Messages to send; full context on cold start, single message on continuation.
     *  @param onToken             Called for each content chunk.
     *  @param previousResponseId  Response ID from the prior turn; `None` starts a new thread.
     *  @return                    [[LLMResponse]] with token counts, latency, and a responseId.
     */
    def streamResponses(
        input:              List[Message],
        onToken:            String => Unit,
        previousResponseId: Option[String] = None
    ): LLMResponse =
        throw UnsupportedOperationException(s"${getClass.getSimpleName} does not support the Responses API")

    /**
     *  Provider-specific model name used for requests and accounting.
     *  @return  Model name string.
     */
    def modelName: String
}

/**
 *  Result of a single LLM provider call: token usage, latency, and optional response ID.
 *  @param model             Model used for the request.
 *  @param promptTokens      Number of prompt tokens consumed.
 *  @param completionTokens  Number of completion tokens produced.
 *  @param latencyMs         End-to-end provider call latency in milliseconds.
 *  @param responseId        Provider-assigned response ID; present for Responses API calls,
 *                           used to thread stateful multi-turn conversations.
 */
case class LLMResponse(
    model:            String,
    promptTokens:     Int,
    completionTokens: Int,
    latencyMs:        Long,
    responseId:       Option[String] = None
)
