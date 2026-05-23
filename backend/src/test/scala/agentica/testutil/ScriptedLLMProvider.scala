package agentica.testutil

import agentica.llm.{LLMProvider, LLMResponse}
import agentica.session.Message

/**
 *  Test double for [[LLMProvider]]. Returns one pre-scripted response string per
 *  `streamChatCompletions` call in order. Throws if more calls are made than scripts provided.
 *  Use for all Phase 2 golden scenarios while the system prompt is still evolving.
 *  @param responses  The pre-scripted response strings to return in order.
 */
class ScriptedLLMProvider(responses: List[String]) extends LLMProvider
{

    private val queue = scala.collection.mutable.Queue(responses*)

    val modelName: String = "scripted-test-model"

    /**
     *  Streams tokens from the next scripted response, splitting on newline boundaries.
     *  Throws if no more responses are available.
     *  @param messages  Ignored — responses are pre-scripted.
     *  @param onToken   Called for each token fragment.
     *  @return          Stub [[LLMResponse]] with zero token counts.
     */
    def streamChatCompletions(messages: List[Message], onToken: String => Unit): LLMResponse =
    {
        if (queue.isEmpty)
        {
            throw IllegalStateException("ScriptedLLMProvider: no more scripted responses")
        }
        val response = queue.dequeue()
        response.split("(?<=\\n)|(?=\\n)").foreach(onToken)
        LLMResponse(model = modelName, promptTokens = 0, completionTokens = response.length / 4, latencyMs = 0)
    }

    /**
     *  Delegates to [[streamChatCompletions]], ignoring the input messages and response ID.
     *  Satisfies the [[LLMProvider]] trait for test scenarios that call `streamResponses`.
     *  @param input               Ignored.
     *  @param onToken             Called for each token fragment.
     *  @param previousResponseId  Ignored.
     *  @return                    Stub [[LLMResponse]] with zero token counts.
     */
    override def streamResponses(
        input:              List[Message],
        onToken:            String => Unit,
        previousResponseId: Option[String] = None
    ): LLMResponse =
        streamChatCompletions(Nil, onToken)

    /**
     *  Returns the number of remaining scripted responses.
     * 
     *  @return
     *    The number of remaining scripted responses.
     */
    def remainingResponses: Int = queue.size
}
