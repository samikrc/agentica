package agentica.testutil

import agentica.llm.{LLMProvider, LLMUsage}
import agentica.session.Message

/**
 *  Test double for LLMProvider. Returns one pre-scripted response string per
 *  stream() call in order. Throws if more calls are made than scripts provided.
 *  Use for all Phase 2 golden scenarios while the system prompt is still evolving.
 */
class ScriptedLLMProvider(responses: List[String]) extends LLMProvider:

    private val queue = scala.collection.mutable.Queue(responses*)

    val modelName: String = "scripted-test-model"

    def stream(messages: List[Message], onToken: String => Unit): LLMUsage =
        if queue.isEmpty then
            throw IllegalStateException("ScriptedLLMProvider: no more scripted responses")
        val response = queue.dequeue()
        response.split("(?<=\\n)|(?=\\n)").foreach(onToken)
        LLMUsage(model = modelName, promptTokens = 0, completionTokens = response.length / 4, latencyMs = 0)

    def complete(messages: List[Message]): (String, LLMUsage) =
        if queue.isEmpty then
            throw IllegalStateException("ScriptedLLMProvider: no more scripted responses")
        val response = queue.dequeue()
        val usage    = LLMUsage(model = modelName, promptTokens = 0, completionTokens = response.length / 4, latencyMs = 0)
        (response, usage)

    def remainingResponses: Int = queue.size
