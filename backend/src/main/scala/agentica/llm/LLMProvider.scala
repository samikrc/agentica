package agentica.llm

import agentica.session.Message
import ujson.Obj

/**
 *  Abstraction over local and cloud LLM providers.
 *  All providers must be thread-safe (called from virtual threads).
 */
/**
 *  A provider-agnostic description of one callable tool, serialised by each
 *  provider into its API-specific `tools` format (OpenAI function-calling,
 *  Responses API function items, Ollama `/api/chat` tools).
 *  @param name        Function name as it appears on the wire, e.g. `files_search`.
 *  @param description Human/model-readable description of what the tool does.
 *  @param parameters  JSON Schema object for the parameters (`{"type":"object","properties":{...}}`).
 */
case class ToolSpec(name: String, description: String, parameters: ujson.Obj)

/**
 *  A single tool invocation extracted from a provider response.
 *  @param id            Provider-assigned call ID (echoed back via `tool_call_id` results).
 *  @param name          Function name, e.g. `files_search`.
 *  @param argumentsJson Raw JSON object string of the call arguments.
 */
case class NativeToolCall(id: String, name: String, argumentsJson: String)

trait LLMProvider
{
    /**
     *  Calls the Chat Completions API with a full message list.
     *  @param messages  Full conversation history (system, user, assistant, tool turns).
     *  @param onToken   Called for each content chunk.
     *  @param tools     Tool definitions to advertise via native function calling;
     *                   empty means no tools are offered.
     *  @return          [[LLMResponse]] with token counts, latency, responseId, and tool calls.
     */
    def streamChatCompletions(
        messages: List[Message],
        onToken:  String => Unit,
        tools:    List[ToolSpec] = Nil
    ): LLMResponse

    /**
     *  Calls the Responses API with a structured input message list.
     *  Supports stateful multi-turn threading via `previousResponseId`.
     *  On the first call (`previousResponseId = None`) the full conversation context is
     *  expected in `input`; on continuation calls only the new single message need be present.
     *  Defaults to `throw UnsupportedOperationException` — only implemented by providers
     *  that support the OpenAI Responses API.
     *  @param input               Messages to send; full context on cold start, single message on continuation.
     *  @param onToken             Called for each content chunk.
     *  @param previousResponseId  Response ID from the prior turn; `None` starts a new thread.
     *  @param tools               Tool definitions to advertise via native function calling.
     *  @return                    [[LLMResponse]] with token counts, latency, and a responseId.
     */
    def streamResponses(
        input:              List[Message],
        onToken:            String => Unit,
        previousResponseId: Option[String] = None,
        tools:              List[ToolSpec] = Nil
    ): LLMResponse =
        throw UnsupportedOperationException(s"${getClass.getSimpleName} does not support the Responses API")

    /**
     *  Provider-specific model name used for requests and accounting.
     *  @return  Model name string.
     */
    def modelName: String

    /**
     *  Calls the Vision API with a base64-encoded image and returns the generated text.
     *  Used by the document ingestion pipeline (Stage B) to convert page images to Markdown.
     *
     *  @param base64Image  Base64-encoded PNG image (with data URI prefix: "data:image/png;base64,...").
     *  @param prompt       System/instruction prompt for the vision task.
     *  @return             Generated text content (e.g., Markdown representation of the image).
     *  @throws UnsupportedOperationException if the provider does not support vision.
     */
    def completeVision(base64Image: String, prompt: String): String =
        throw UnsupportedOperationException(s"${getClass.getSimpleName} does not support vision")

    /**
     *  Returns true if this provider supports vision (image input).
     *  Used by document tools to return a structured error if vision is unavailable.
     */
    def supportsVision: Boolean = false
}

/**
 *  Result of a single LLM provider call: token usage, latency, and optional response ID.
 *  @param model             Model used for the request.
 *  @param promptTokens      Number of prompt tokens consumed.
 *  @param completionTokens  Number of completion tokens produced.
 *  @param latencyMs         End-to-end provider call latency in milliseconds.
 *  @param responseId        Provider-assigned response ID; present for Responses API calls,
 *                           used to thread stateful multi-turn conversations.
 *  @param toolCalls         Tool invocations requested by the model via native function calling.
 *  @param finishReason      Provider finish reason for the selected completion choice.
 *  @param reasoningContent  Provider-separated reasoning text, retained for diagnostics but not shown as an answer.
 */
case class LLMResponse(
    model:            String,
    promptTokens:     Int,
    completionTokens: Int,
    latencyMs:        Long,
    responseId:       Option[String] = None,
    toolCalls:        List[NativeToolCall] = Nil,
    finishReason:     Option[String] = None,
    reasoningContent: Option[String] = None
)
