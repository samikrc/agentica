package agentica.llm

import agentica.session.Message
import ujson.*

/**
 *  [[LLMProvider]] backed by a locally running Ollama instance.
 *  Communicates with the Ollama `/api/chat` endpoint using NDJSON streaming.
 *  @param baseUrl    Base URL of the Ollama server.
 *  @param modelName  Model identifier to send in requests.
 */
class OllamaProvider(
    baseUrl:         String = "http://localhost:11434",
    val modelName:   String = "llama3.2"
) extends LLMProvider
{
    /**
     *  Converts a [[Message]] list to the JSON array expected by the Ollama API.
     *  @param messages  Conversation history.
     *  @return          JSON array of `{role, content}` objects.
     */
    private def toOllamaMessages(messages: List[Message]): ujson.Arr =
    {
        ujson.Arr(messages.map { m =>
            ujson.Obj("role" -> m.role.value, "content" -> m.content)
        }*)
    }

    /**
     *  Calls the Ollama Chat API, streaming NDJSON lines and calling `onToken` per delta.
     *  @param messages  Full conversation history to send as context.
     *  @param onToken   Callback invoked with each streamed text delta.
     *  @return          [[LLMResponse]] capturing token counts and total latency.
     */
    def streamChatCompletions(messages: List[Message], onToken: String => Unit): LLMResponse =
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

        LLMResponse(
            model            = modelName,
            promptTokens     = promptTokens,
            completionTokens = completionTokens,
            latencyMs        = System.currentTimeMillis() - t0
        )
    }
}
