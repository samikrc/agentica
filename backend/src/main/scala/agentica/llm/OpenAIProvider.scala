package agentica.llm

import agentica.session.{Message, MessageRole}
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
 *  @param baseURL    Base URL of the LLM server.
 *  @param modelName  Model identifier to send in requests.
 *  @param apiKey     Bearer token for the Authorization header.
 */
object OpenAIProvider:
    /**
     *  Extracts token usage counts, supporting both Chat Completions
     *  (`prompt_tokens`/`completion_tokens`) and Responses API
     *  (`input_tokens`/`output_tokens`) field names.
     *  @param json  Parsed JSON response root.
     *  @return      Tuple of (promptTokens, completionTokens).
     */
    private[llm] def extractUsage(json: ujson.Value): (Int, Int) =
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

class OpenAIProvider(
    baseURL:             String = "http://localhost:1234",
    val modelName:       String = "local-model",
    apiKey:              String = "lm-studio",
    requestTimeoutSeconds: Int  = 300
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
     *  Returns the current timestamp used in provider diagnostics.
     *  @return  Current instant in ISO-8601 format.
     */
    private def now(): String =
        java.time.Instant.now().toString

    /**
     *  Sends a POST request to the given path and returns the raw response body.
     *  Also logs the request and response at DEBUG level.
     *  @param path     URL path relative to `baseURL`, e.g. `/v1/chat/completions`.
     *  @param body     JSON request body to send.
     *  @return         Raw response body string.
     */
    private def doRequest(path: String, body: ujson.Obj): String =
    {
        val req = HttpRequest.newBuilder()
            .uri(URI.create(s"$baseURL$path"))
            .header("Content-Type", "application/json")
            .header("Authorization", s"Bearer $apiKey")
            .timeout(Duration.ofSeconds(requestTimeoutSeconds))
            .POST(HttpRequest.BodyPublishers.ofString(ujson.write(body)))
            .build()

        val t0 = System.currentTimeMillis()
        System.err.println(s"[${now()}] OpenAIProvider -> POST $baseURL$path model=$modelName timeout=${requestTimeoutSeconds}s")
        val resp    = buildClient().send(req, HttpResponse.BodyHandlers.ofString())
        val elapsed = System.currentTimeMillis() - t0
        val bodyStr = resp.body()
        System.err.println(s"[${now()}] OpenAIProvider <- status=${resp.statusCode()} len=${bodyStr.length} elapsed=${elapsed}ms")
        if (resp.statusCode() < 200 || resp.statusCode() >= 300)
        {
            throw IllegalStateException(
                s"OpenAI-compatible endpoint returned HTTP ${resp.statusCode()}: ${bodyStr.take(2000)}")
        }
        bodyStr
    }

    /**
     *  Extracts token usage counts from the common `usage` object present in both APIs.
     *  @param json  Parsed JSON response root.
     *  @return      Tuple of (promptTokens, completionTokens).
     */
    private def extractUsage(json: ujson.Value): (Int, Int) = OpenAIProvider.extractUsage(json)

    /**
     *  Converts a list of [[Message]] values to the JSON array expected by the Chat Completions API.
     *  @param messages  Conversation history.
     *  @return          JSON array of `{role, content}` objects.
     */
    private[llm] def toMessagesJSON(messages: List[Message]): ujson.Arr =
    {
        ujson.Arr(messages.map { m =>
            if (m.role == MessageRole.Tool)
            {
                ujson.Obj(
                    "role"         -> "tool",
                    "tool_call_id" -> m.toolCallId.getOrElse(""),
                    "content"      -> m.content
                )
            }
            else if (m.role == MessageRole.Assistant && m.toolCallsJson.isDefined)
            {
                ujson.Obj(
                    "role"       -> "assistant",
                    "content"    -> (if m.content.isEmpty then ujson.Null else Str(m.content)),
                    "tool_calls" -> ujson.read(m.toolCallsJson.get)
                )
            }
            else
            {
                ujson.Obj("role" -> m.role.value, "content" -> m.content)
            }
        }*)
    }

    /** OpenAI Chat Completions `tools` array shape: nested under a `function` key. */
    private[llm] def toCCFunctionSpecs(tools: List[ToolSpec]): ujson.Arr =
    {
        ujson.Arr(tools.map { t =>
            ujson.Obj(
                "type"     -> "function",
                "function" -> ujson.Obj(
                    "name"        -> t.name,
                    "description" -> t.description,
                    "parameters"  -> t.parameters
                )
            )
        }*)
    }

    /** Extracts native tool calls from a Chat Completions `message.tool_calls` array. */
    private[llm] def extractToolCalls(messageJson: Option[ujson.Value]): List[NativeToolCall] =
    {
        messageJson
            .flatMap(_.obj.get("tool_calls"))
            .collect { case calls: ujson.Arr => calls.value.toList }
            .getOrElse(Nil)
            .flatMap {
                case tc: ujson.Obj =>
                    for
                    {
                        fn   <- tc.value.get("function").collect { case value: ujson.Obj => value }
                        name <- fn.value.get("name").collect { case ujson.Str(value) => value }
                        args <- fn.value.get("arguments").collect { case ujson.Str(value) => value }
                    } yield
                    {
                        val id = tc.value.get("id").collect { case ujson.Str(value) => value }.filter(_.nonEmpty)
                            .getOrElse(s"call-${java.util.UUID.randomUUID()}")
                        NativeToolCall(id, name, args)
                    }
                case _ => None
            }
    }

    /**
     *  Calls the Chat Completions API (`/v1/chat/completions`) and forwards the
     *  response content to `onToken`.
     *  @param messages  Full conversation history to send as context.
     *  @param onToken   Callback invoked with the returned assistant content.
     *  @return          [[LLMResponse]] capturing provider usage and latency.
     */
    def streamChatCompletions(messages: List[Message], onToken: String => Unit, tools: List[ToolSpec] = Nil): LLMResponse =
    {
        val t0   = System.currentTimeMillis()
        val body = ujson.Obj(
            "model"    -> modelName,
            "messages" -> toMessagesJSON(messages),
            "stream"   -> false
        )
        if tools.nonEmpty then
        {
            body("tools")       = toCCFunctionSpecs(tools)
            body("tool_choice") = "auto"
        }

        val json       = ujson.read(doRequest("/v1/chat/completions", body))
        val choiceJson = json.obj.get("choices").flatMap(_.arr.headOption)
        val messageJson = choiceJson.flatMap(_.obj.get("message"))
        val content = messageJson
            .flatMap(_.obj.get("content"))
            .map {
                case s if s == ujson.Null => ""
                case v                    => v.str
            }
            .getOrElse("")
        val toolCalls   = extractToolCalls(messageJson)
        val finishReason = choiceJson.flatMap(_.obj.get("finish_reason")).collect {
            case value if value != ujson.Null => value.str
        }
        val reasoningContent = messageJson.flatMap { message =>
            message.obj.get("reasoning_content").orElse(message.obj.get("reasoning"))
        }.collect {
            case value if value != ujson.Null && value.str.nonEmpty => value.str
        }

        val (promptTokens, completionTokens) = extractUsage(json)
        if content.nonEmpty then onToken(content)

        LLMResponse(
            model            = modelName,
            promptTokens     = promptTokens,
            completionTokens = completionTokens,
            latencyMs        = System.currentTimeMillis() - t0,
            toolCalls        = toolCalls,
            finishReason     = finishReason,
            reasoningContent = reasoningContent
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
        previousResponseId: Option[String] = None,
        tools:              List[ToolSpec] = Nil
    ): LLMResponse =
    {
        val t0        = System.currentTimeMillis()
        val inputJSON = previousResponseId match
        {
            case None    => toResponsesInputJSON(input)
            case Some(_) => toResponsesInputJSON(List(input.last))
        }
        val body = ujson.Obj(
            "model" -> modelName,
            "input" -> inputJSON
        )
        previousResponseId.foreach { id => body("previous_response_id") = id }
        if tools.nonEmpty then
        {
            body("tools") = ujson.Arr(tools.map { t =>
                ujson.Obj(
                    "type"        -> "function",
                    "name"        -> t.name,
                    "description" -> t.description,
                    "parameters"  -> t.parameters
                )
            }*)
        }

        val json = ujson.read(doRequest("/v1/responses", body))

        // output[] mixes message items and function_call items; collect both.
        var content   = ""
        val toolCalls = scala.collection.mutable.ListBuffer.empty[NativeToolCall]
        json.obj.get("output").foreach { output =>
            output.arr.foreach { item =>
                item.obj.get("type").map(_.str) match
                {
                    case Some("message") =>
                        content = item.obj.get("content")
                            .flatMap(_.arr.headOption)
                            .flatMap(_.obj.get("text"))
                            .map(_.str)
                            .getOrElse("")
                    case Some("function_call") =>
                        for
                        {
                            name <- item.obj.get("name").map(_.str)
                            args <- item.obj.get("arguments").map(_.str)
                        } do
                        {
                            val id = item.obj.get("call_id").orElse(item.obj.get("id")).map(_.str).getOrElse("")
                            toolCalls += NativeToolCall(id, name, args)
                        }
                    case _ => ()
                }
            }
        }

        val responseId                        = json.obj.get("id").map(_.str)
        val (promptTokens, completionTokens) = extractUsage(json)
        if content.nonEmpty then onToken(content)

        LLMResponse(
            model            = modelName,
            promptTokens     = promptTokens,
            completionTokens = completionTokens,
            latencyMs        = System.currentTimeMillis() - t0,
            responseId       = responseId,
            toolCalls        = toolCalls.toList
        )
    }

    /** Responses API `tools` shape: function fields are flattened (no `function` wrapper). */
    private[llm] def toResponsesInputJSON(messages: List[Message]): ujson.Arr =
    {
        ujson.Arr(messages.flatMap { m =>
            if (m.role == MessageRole.Tool)
            {
                List(ujson.Obj(
                    "type"    -> "function_call_output",
                    "call_id" -> m.toolCallId.getOrElse(""),
                    "output"  -> m.content
                ))
            }
            else if (m.role == MessageRole.Assistant && m.toolCallsJson.isDefined)
            {
                // Echo the assistant's tool calls as function_call items, plus any text content.
                val fcItems = ujson.read(m.toolCallsJson.get).arr.toList.map { tc =>
                    ujson.Obj(
                        "type"      -> "function_call",
                        "call_id"   -> tc.obj.get("id").map(_.str).getOrElse(""),
                        "name"      -> tc.obj("function").obj("name").str,
                        "arguments" -> tc.obj("function").obj("arguments").str
                    )
                }
                val textItem = Option.when(m.content.nonEmpty) {
                    ujson.Obj(
                        "type"    -> "message",
                        "role"    -> "assistant",
                        "content" -> ujson.Arr(ujson.Obj("type" -> "output_text", "text" -> m.content))
                    )
                }
                fcItems ++ textItem.toList
            }
            else
            {
                List(ujson.Obj("role" -> m.role.value, "content" -> m.content))
            }
        }*)
    }

    /**
     *  OpenAI-compatible vision support via Chat Completions API.
     *  Sends the image as a base64 data URI in a user message with the prompt.
     */
    override def completeVision(base64Image: String, prompt: String): String =
    {
        val body = ujson.Obj(
            "model" -> modelName,
            "messages" -> ujson.Arr(
                ujson.Obj(
                    "role" -> "user",
                    "content" -> ujson.Arr(
                        ujson.Obj(
                            "type" -> "text",
                            "text" -> prompt
                        ),
                        ujson.Obj(
                            "type" -> "image_url",
                            "image_url" -> ujson.Obj(
                                "url" -> base64Image
                            )
                        )
                    )
                )
            ),
            "stream" -> false
        )

        val json    = ujson.read(doRequest("/v1/chat/completions", body))
        val content = json.obj.get("choices")
            .flatMap(_.arr.headOption)
            .flatMap(_.obj.get("message"))
            .flatMap(_.obj.get("content"))
            .map(_.str)
            .getOrElse("")

        content
    }

    /**
     *  Returns true — OpenAI-compatible providers typically support vision.
     *  This is a best-effort assumption; actual support depends on the model.
     */
    override def supportsVision: Boolean = true
}
