package agentica.testutil

import agentica.llm.{LLMProvider, LLMUsage}
import agentica.session.Message

/**
 *  Test double for LLMProvider. Returns one pre-scripted response string per
 *  stream() call in order. Throws if more calls are made than scripts provided.
 *  Use for all Phase 2 golden scenarios while the system prompt is still evolving.
 * 
 *  @param responses
 *    The pre-scripted response strings to return in order.
 */
class ScriptedLLMProvider(responses: List[String]) extends LLMProvider
{

    private val queue = scala.collection.mutable.Queue(responses*)

    val modelName: String = "scripted-test-model"

    /**
     *  Streams tokens from the next scripted response.
     *  Throws if no more responses are available.
     * 
     *  @param messages
     *    The messages to stream.
     *  @param onToken
     *    The function to call for each token.
     *  @return
     *    The usage of the streamed response.
     */
    def stream(messages: List[Message], onToken: String => Unit): LLMUsage =
    {
        if (queue.isEmpty)
        {
            throw IllegalStateException("ScriptedLLMProvider: no more scripted responses")
        }
        val response = queue.dequeue()
        response.split("(?<=\\n)|(?=\\n)").foreach(onToken)
        LLMUsage(model = modelName, promptTokens = 0, completionTokens = response.length / 4, latencyMs = 0)
    }

    /**
     *  Returns the next scripted response and its usage.
     *  Throws if no more responses are available.
     * 
     *  @param messages
     *    The messages to complete.
     *  @return
     *    The response and its usage.
     */
    def complete(messages: List[Message]): (String, LLMUsage) =
    {
        if (queue.isEmpty)
        {
            throw IllegalStateException("ScriptedLLMProvider: no more scripted responses")
        }
        val response = queue.dequeue()
        val usage    = LLMUsage(model = modelName, promptTokens = 0, completionTokens = response.length / 4, latencyMs = 0)
        (response, usage)
    }

    /**
     *  Returns the number of remaining scripted responses.
     * 
     *  @return
     *    The number of remaining scripted responses.
     */
    def remainingResponses: Int = queue.size
}
