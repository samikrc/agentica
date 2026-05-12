package agentica.testutil

import agentica.llm.{LLMProvider, LLMUsage}
import agentica.session.Message
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 *  Test double for LLMProvider that reads scripted responses from a JSON file.
 *  Returns one response per stream() call in order. Throws if more calls are made
 *  than responses provided in the file.
 *
 *  JSON format:
 *  {
 *    "responses": [
 *      "First response with run(command=\"files.read path=data.txt\")",
 *      "Second response\\n<done>"
 *    ]
 *  }
 *
 *  @param path  Path to the JSON scenario file.
 */
class JSONFileLLMProvider(path: Path) extends LLMProvider
{

    private val json    = ujson.read(Files.readString(path))
    private val responses = json.obj("responses").arr.map(_.str).toList
    private val queue   = scala.collection.mutable.Queue(responses*)

    val modelName: String = json.obj.get("model").map(_.str).getOrElse("json-file-model")

    /**
     *  Streams tokens from the next scripted response.
     *  Throws if no more responses are available.
     *
     *  @param messages
     *    The messages to stream (ignored — responses are scripted).
     *  @param onToken
     *    The function to call for each token.
     *  @return
     *    The usage of the streamed response.
     */
    def stream(messages: List[Message], onToken: String => Unit): LLMUsage =
    {
        if (queue.isEmpty)
        {
            throw IllegalStateException(s"JSONFileLLMProvider($path): no more scripted responses")
        }
        val response = queue.dequeue()
        // Split on newlines to simulate token streaming while preserving line breaks
        response.split("(?<=\\n)|(?=\\n)").foreach(onToken)
        LLMUsage(model = modelName, promptTokens = 0, completionTokens = response.length / 4, latencyMs = 0)
    }

    /**
     *  Returns the next scripted response and its usage.
     *  Throws if no more responses are available.
     *
     *  @param messages
     *    The messages to complete (ignored — responses are scripted).
     *  @return
     *    The response and its usage.
     */
    def complete(messages: List[Message]): (String, LLMUsage) =
    {
        if (queue.isEmpty)
        {
            throw IllegalStateException(s"JSONFileLLMProvider($path): no more scripted responses")
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
