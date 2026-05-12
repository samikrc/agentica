package agentica.llm

import agentica.session.{Message, MessageRole}
import ujson.*

/** LlmProvider backed by a locally running Ollama instance.
 *  Communicates with the Ollama /api/chat endpoint.
 *  Streams NDJSON response lines, calling onToken for each content delta.
 */
class OllamaProvider(
    baseUrl: String = "http://localhost:11434",
    val modelName: String = "llama3.2"
) extends LLMProvider
{

    /** Converts a list of [[Message]] objects to the JSON array format expected by the Ollama API. */
    private def toOllamaMessages(messages: List[Message]): ujson.Arr =
    {
        ujson.Arr(messages.map { m =>
            ujson.Obj("role" -> m.role.value, "content" -> m.content)
        }*)
    }

    /** Streams a chat completion from Ollama, calling `onToken` for each text delta.
     *  Buffers the full NDJSON response body and parses it line by line.
     *  @param messages  Full conversation history to send as context.
     *  @param onToken   Callback invoked with each streamed text token.
     *  @return          [[LLMUsage]] capturing token counts and total latency.
     */
    def stream(messages: List[Message], onToken: String => Unit): LLMUsage =
    {
        val t0               = System.currentTimeMillis()
        var promptTokens     = 0
        var completionTokens = 0

        val body = ujson.Obj(
            "model"    -> modelName,
            "messages" -> toOllamaMessages(messages),
            "stream"   -> true
        )

        val response = requests.post(
            url     = s"$baseUrl/api/chat",
            data    = ujson.write(body),
            headers = Map("Content-Type" -> "application/json")
        )

        val reader = java.io.BufferedReader(
            java.io.InputStreamReader(
                new java.io.ByteArrayInputStream(response.bytes)
            )
        )
        var line = reader.readLine()
        while line != null do
        {
            if line.nonEmpty then
            {
                val json  = ujson.read(line)
                val delta = json.obj.get("message").flatMap(_.obj.get("content")).map(_.str).getOrElse("")
                if delta.nonEmpty then onToken(delta)
                if json.obj.get("done").exists(_.bool) then
                {
                    promptTokens     = json.obj.get("prompt_eval_count").map(_.num.toInt).getOrElse(0)
                    completionTokens = json.obj.get("eval_count").map(_.num.toInt).getOrElse(0)
                }
            }
            line = reader.readLine()
        }

        LLMUsage(
            model            = modelName,
            promptTokens     = promptTokens,
            completionTokens = completionTokens,
            latencyMs        = System.currentTimeMillis() - t0
        )
    }

    /** Non-streaming completion: accumulates all tokens and returns the full response.
     *  @param messages  Full conversation history to send as context.
     *  @return          Tuple of (full response text, [[LLMUsage]]).
     */
    def complete(messages: List[Message]): (String, LLMUsage) =
    {
        val buf   = StringBuilder()
        val usage = stream(messages, tok => buf.append(tok))
        (buf.toString, usage)
    }
}
