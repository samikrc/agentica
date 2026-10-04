package agentica.testutil

import agentica.llm.{LLMProvider, LLMResponse, NativeToolCall, ToolSpec}
import agentica.session.Message
import java.nio.file.{Files, Path}

/**
 *  Test double for LLMProvider that reads scripted responses from a JSON file.
 *  Responses may be legacy strings or structured objects:
 *
 *  {{
 *    "responses": [
 *      {"content":"", "tool_calls":[
 *        {"id":"call-1", "name":"files_read", "arguments":{"path":"data.txt"}}
 *      ]},
 *      {"content":"The file contains the expected data."}
 *    ]
 *  }}
 *
 *  @param path  Path to the JSON scenario file.
 */
class JSONFileLLMProvider(path: Path) extends LLMProvider
{
    private val json = ujson.read(Files.readString(path))
    private val responses = json.obj("responses").arr.map(parseResponse).toList
    private val queue = scala.collection.mutable.Queue(responses*)

    val modelName: String = json.obj.get("model").map(_.str).getOrElse("json-file-model")

    /** Parses a legacy string or structured JSON scripted response. */
    private def parseResponse(value: ujson.Value): ScriptedResponse = value match
    {
        case ujson.Str(content) => ScriptedResponse(content)
        case obj: ujson.Obj =>
            val content = obj.value.get("content").collect { case ujson.Str(s) => s }.getOrElse("")
            val calls = obj.value.get("tool_calls").map(_.arr.toList).getOrElse(Nil).map { call =>
                val callObj = call.obj
                val id      = callObj.get("id").map(_.str).getOrElse("call-1")
                val name    = callObj("name").str
                val args    = callObj.get("arguments") match
                {
                    case Some(ujson.Str(raw)) => raw
                    case Some(arguments)      => ujson.write(arguments)
                    case None                 => "{}"
                }
                NativeToolCall(id, name, args)
            }
            ScriptedResponse(content, calls)
        case other => throw IllegalArgumentException(s"Invalid scripted response in $path: ${ujson.write(other)}")
    }

    /** Emits the next scripted Chat Completions response. */
    def streamChatCompletions(
        messages: List[Message],
        onToken: String => Unit,
        tools: List[ToolSpec] = Nil
    ): LLMResponse =
    {
        if (queue.isEmpty)
        {
            throw IllegalStateException(s"JSONFileLLMProvider($path): no more scripted responses")
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
