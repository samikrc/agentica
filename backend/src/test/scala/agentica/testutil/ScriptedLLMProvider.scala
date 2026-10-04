package agentica.testutil

import agentica.llm.{LLMProvider, LLMResponse, NativeToolCall, ToolSpec}
import agentica.session.Message

/** One deterministic provider response used by [[ScriptedLLMProvider]]. */
case class ScriptedResponse(content: String = "", toolCalls: List[NativeToolCall] = Nil)

object ScriptedResponse
{
    /** Creates a scripted response containing one native tool call. */
    def toolCall(name: String, argumentsJson: String, id: String = "call-1", content: String = ""): ScriptedResponse =
        ScriptedResponse(content, List(NativeToolCall(id, name, argumentsJson)))
}

/**
 *  Test double for [[LLMProvider]]. Returns one pre-scripted response per call in
 *  order and supports both text-only and native function-call responses.
 */
class ScriptedLLMProvider(responses: Seq[ScriptedResponse]) extends LLMProvider
{
    /** Creates a scripted provider from plain content-only responses. */
    def this(responses: List[String]) = this(responses.map(ScriptedResponse(_)))

    private val queue = scala.collection.mutable.Queue(responses*)

    val modelName: String = "scripted-test-model"

    /** Emits the next scripted Chat Completions response. */
    def streamChatCompletions(
        messages: List[Message],
        onToken: String => Unit,
        tools: List[ToolSpec] = Nil
    ): LLMResponse =
    {
        if (queue.isEmpty)
        {
            throw IllegalStateException("ScriptedLLMProvider: no more scripted responses")
        }
        val response = queue.dequeue()
        response.content.split("(?<=\\n)|(?=\\n)").filter(_.nonEmpty).foreach(onToken)
        LLMResponse(
            model = modelName,
            promptTokens = 0,
            completionTokens = response.content.length / 4,
            latencyMs = 0,
            toolCalls = response.toolCalls
        )
    }

    override def streamResponses(
        input: List[Message],
        onToken: String => Unit,
        previousResponseId: Option[String],
        tools: List[ToolSpec]
    ): LLMResponse = streamChatCompletions(Nil, onToken, tools)

    /** Returns the number of scripted responses not yet emitted. */
    def remainingResponses: Int = queue.size
}
