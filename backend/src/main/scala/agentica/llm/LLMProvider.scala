package agentica.llm

import agentica.session.Message

/** Abstraction over local and cloud LLM providers.
 *  All providers must be thread-safe (called from virtual threads).
 */
trait LLMProvider
{

    /** Stream a chat completion token by token.
     *  @param messages  Full conversation history (system, user, assistant turns).
     *  @param onToken   Called for each streamed text token.
     *  @return          LlmUsage with token counts and latency.
     */
    def stream(
        messages: List[Message],
        onToken: String => Unit
    ): LLMUsage

    /** Non-streaming completion (used for summarization, titling, etc.) */
    def complete(messages: List[Message]): (String, LLMUsage)

    /** Provider-specific model name used for requests and accounting. */
    def modelName: String
}

/** Token and latency usage reported by an LLM provider call.
 *  @param model             Model used for the request.
 *  @param promptTokens      Number of prompt tokens consumed.
 *  @param completionTokens  Number of completion tokens produced.
 *  @param latencyMs         End-to-end provider call latency in milliseconds.
 */
case class LLMUsage(
    model: String,
    promptTokens: Int,
    completionTokens: Int,
    latencyMs: Long
)
