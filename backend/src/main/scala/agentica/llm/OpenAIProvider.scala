package agentica.llm

import agentica.session.Message
import ujson.*
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration

/**
 *  LLM provider that speaks OpenAI-compatible APIs.
 *  Supports both the Chat Completions (`/v1/chat/completions`) and
 *  Responses (`/v1/responses`) endpoints.
 *  Compatible with LM Studio (default: http://localhost:1234),
 *  OpenAI, and any other OpenAI-compatible server.
 *  @param baseUrl    Base URL of the LLM server.
 *  @param modelName  Model identifier to send in requests.
 *  @param apiKey     Bearer token for the Authorization header.
 */
class OpenAIProvider(
    baseUrl:         String = "http://localhost:1234",
    val modelName:   String = "local-model",
    apiKey:          String = "lm-studio"
) extends LLMProvider
{

    /**
     *  Builds a shared HTTP client for each request.
     *  @return  Configured [[HttpClient]].
     */
    private def buildClient(): HttpClient =
    {
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build()
    }

    /**
     *  Sends a POST request to the given path and returns the raw response body.
     *  Also logs the request and response at DEBUG level.
     *  @param path     URL path relative to `baseUrl`, e.g. `/v1/chat/completions`.
     *  @param body     JSON request body to send.
     *  @return         Raw response body string.
     */
    private def doRequest(path: String, body: ujson.Obj): String =
    {
        val req = HttpRequest.newBuilder()
            .uri(URI.create(s"$baseUrl$path"))
            .header("Content-Type", "application/json")
            .header("Authorization", s"Bearer $apiKey")
            .header("Connection", "close")
            .POST(HttpRequest.BodyPublishers.ofString(ujson.write(body)))
            .build()

        System.err.println(s"[DEBUG] OpenAIProvider -> POST $baseUrl$path model=$modelName")
        val resp    = buildClient().send(req, HttpResponse.BodyHandlers.ofString())
        val bodyStr = resp.body()
        System.err.println(s"[DEBUG] OpenAIProvider <- status=${resp.statusCode()} len=${bodyStr.length}")
        bodyStr
    }

    /**
     *  Extracts token usage counts from the common `usage` object present in both APIs.
     *  @param json  Parsed JSON response root.
     *  @return      Tuple of (promptTokens, completionTokens).
     */
    private def extractUsage(json: ujson.Value): (Int, Int) =
    {
        json.obj.get("usage") match
        {
            case None    => (0, 0)
            case Some(u) =>
                val prompt     = u.obj.get("prompt_tokens")
                                   .orElse(u.obj.get("input_tokens"))
                                   .map(_.num.toInt).getOrElse(0)
                val completion = u.obj.get("completion_tokens")
                                   .orElse(u.obj.get("output_tokens"))
                                   .map(_.num.toInt).getOrElse(0)
                (prompt, completion)
        }
    }

    /**
     *  Converts a list of [[Message]] values to the JSON array expected by the Chat Completions API.
     *  @param messages  Conversation history.
     *  @return          JSON array of `{role, content}` objects.
     */
    private def toMessagesJSON(messages: List[Message]): ujson.Arr =
    {
        ujson.Arr(messages.map { m =>
            ujson.Obj("role" -> m.role.value, "content" -> m.content)
        }*)
    }

    /**
     *  Calls the Chat Completions API (`/v1/chat/completions`) and forwards the
     *  response content to `onToken`.
     *  @param messages  Full conversation history to send as context.
     *  @param onToken   Callback invoked with the returned assistant content.
     *  @return          [[LLMResponse]] capturing provider usage and latency.
     */
    def streamChatCompletions(messages: List[Message], onToken: String => Unit): LLMResponse =
    {
        val t0   = System.currentTimeMillis()
        val body = ujson.Obj(
            "model"    -> modelName,
            "messages" -> toMessagesJSON(messages),
            "stream"   -> false
        )

        val json    = ujson.read(doRequest("/v1/chat/completions", body))
        val content = json.obj.get("choices")
            .flatMap(_.arr.headOption)
            .flatMap(_.obj.get("message"))
            .flatMap(_.obj.get("content"))
            .map(_.str)
            .getOrElse("")

        val (promptTokens, completionTokens) = extractUsage(json)
        if content.nonEmpty then onToken(content)

        LLMResponse(
            model            = modelName,
            promptTokens     = promptTokens,
            completionTokens = completionTokens,
            latencyMs        = System.currentTimeMillis() - t0
        )
    }

    /**
     *  Calls the Responses API (`/v1/responses`) and forwards the response text to `onToken`.
     *  On cold start (`previousResponseId = None`) the full `input` list is serialised as a
     *  JSON array.  On continuation (`previousResponseId = Some`) only the last message in
     *  `input` is sent — the server retains prior state via the response ID.
     *  @param input               Full context on cold start; single new message on continuation.
     *  @param onToken             Callback invoked with the returned assistant content.
     *  @param previousResponseId  ID of the previous response for multi-turn threading; `None` starts a new conversation.
     *  @return                    [[LLMResponse]] capturing provider usage, latency, and the new response ID.
     */
    override def streamResponses(
        input:              List[Message],
        onToken:            String => Unit,
        previousResponseId: Option[String] = None
    ): LLMResponse =
    {
        val t0        = System.currentTimeMillis()
        val inputJSON = previousResponseId match
        {
            case None    => toMessagesJSON(input)
            case Some(_) => toMessagesJSON(List(input.last))
        }
        val body = ujson.Obj(
            "model" -> modelName,
            "input" -> inputJSON
        )
        previousResponseId.foreach { id => body("previous_response_id") = id }

        val json    = ujson.read(doRequest("/v1/responses", body))
        val content = json.obj.get("output")
            .flatMap(_.arr.headOption)
            .flatMap(_.obj.get("content"))
            .flatMap(_.arr.headOption)
            .flatMap(_.obj.get("text"))
            .map(_.str)
            .getOrElse("")

        val responseId                        = json.obj.get("id").map(_.str)
        val (promptTokens, completionTokens) = extractUsage(json)
        if content.nonEmpty then onToken(content)

        LLMResponse(
            model            = modelName,
            promptTokens     = promptTokens,
            completionTokens = completionTokens,
            latencyMs        = System.currentTimeMillis() - t0,
            responseId       = responseId
        )
    }
}
