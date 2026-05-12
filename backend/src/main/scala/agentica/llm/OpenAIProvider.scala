package agentica.llm

import agentica.session.{Message, MessageRole}
import ujson.*
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration

/** 
 *  LLM provider that speaks the OpenAI /v1/chat/completions API.
 *  Compatible with LM Studio (default: http://localhost:1234),
 *  OpenAI, and any other OpenAI-compatible server.
 *  Streams SSE `data: {...}` lines and calls onToken for each content delta.
 */
class OpenAIProvider(
    baseUrl:         String = "http://localhost:1234",
    val modelName:   String = "local-model",
    apiKey:          String = "lm-studio"
) extends LLMProvider
{

    private def toMessages(messages: List[Message]): ujson.Arr =
        ujson.Arr(messages.map { m =>
            ujson.Obj("role" -> m.role.value, "content" -> m.content)
        }*)

    /** 
     *  Sends a chat completion request and forwards the resulting content to `onToken`.
     *  @param messages  Full conversation history to send as context.
     *  @param onToken   Callback invoked with the returned assistant content.
     *  @return          [[LLMUsage]] capturing provider usage and latency.
     */
    def stream(messages: List[Message], onToken: String => Unit): LLMUsage =
    {
        val t0 = System.currentTimeMillis()
        var promptTokens     = 0
        var completionTokens = 0

        val body = ujson.Obj(
            "model"    -> modelName,
            "messages" -> toMessages(messages),
            "stream"   -> false
        )

        val client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build()

        val req = HttpRequest.newBuilder()
            .uri(URI.create(s"$baseUrl/v1/chat/completions"))
            .header("Content-Type", "application/json")
            .header("Authorization", s"Bearer $apiKey")
            .header("Connection", "close")
            .POST(HttpRequest.BodyPublishers.ofString(ujson.write(body)))
            .build()

        System.err.println(s"[DEBUG] OpenAIProvider -> POST $baseUrl/v1/chat/completions model=$modelName msgs=${messages.length}")
        val resp    = client.send(req, HttpResponse.BodyHandlers.ofString())
        val bodyStr = resp.body()
        System.err.println(s"[DEBUG] OpenAIProvider <- status=${resp.statusCode()} len=${bodyStr.length}")

        val json    = ujson.read(bodyStr)
        val content = json.obj.get("choices")
            .flatMap(_.arr.headOption)
            .flatMap(_.obj.get("message"))
            .flatMap(_.obj.get("content"))
            .map(_.str)
            .getOrElse("")
        json.obj.get("usage").foreach { u =>
            promptTokens     = u.obj.get("prompt_tokens").map(_.num.toInt).getOrElse(0)
            completionTokens = u.obj.get("completion_tokens").map(_.num.toInt).getOrElse(0)
        }
        if content.nonEmpty then onToken(content)

        LLMUsage(
            model            = modelName,
            promptTokens     = promptTokens,
            completionTokens = completionTokens,
            latencyMs        = System.currentTimeMillis() - t0
        )
    }

    /** 
     *  Runs a non-streaming completion and returns the full assistant response.
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
