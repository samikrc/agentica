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

    def modelName: String
}

case class LLMUsage(
    model: String,
    promptTokens: Int,
    completionTokens: Int,
    latencyMs: Long
)
