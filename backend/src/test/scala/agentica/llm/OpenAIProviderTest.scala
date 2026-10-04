package agentica.llm

import org.scalatest.funsuite.AnyFunSuite
import ujson.*

class OpenAIProviderTest extends AnyFunSuite
{
    private val provider = OpenAIProvider(modelName = "test-model")

    /** Builds a provider response containing the supplied usage fields. */
    private def usage(fields: (String, Int)*): ujson.Obj =
    {
        val inner = ujson.Obj()
        fields.foreach { case (k, v) => inner(k) = ujson.Num(v) }
        ujson.Obj("usage" -> inner)
    }

    /** Returns a provider response with no usage object. */
    private def noUsage: ujson.Obj = ujson.Obj()

    // ── Chat Completions field names ──────────────────────────────────────────

    test("extractUsage reads prompt_tokens and completion_tokens (Chat Completions)") {
        val json = usage("prompt_tokens" -> 100, "completion_tokens" -> 50)
        assert(OpenAIProvider.extractUsage(json) == (100, 50))
    }

    // ── Responses API field names ─────────────────────────────────────────────

    test("extractUsage reads input_tokens and output_tokens (Responses API)") {
        val json = usage("input_tokens" -> 14397, "output_tokens" -> 568)
        assert(OpenAIProvider.extractUsage(json) == (14397, 568))
    }

    // ── Fallback precedence ───────────────────────────────────────────────────

    test("extractUsage prefers prompt_tokens over input_tokens when both present") {
        val json = usage("prompt_tokens" -> 200, "input_tokens" -> 999,
                         "completion_tokens" -> 40, "output_tokens" -> 999)
        assert(OpenAIProvider.extractUsage(json) == (200, 40))
    }

    // ── Missing / empty ───────────────────────────────────────────────────────

    test("extractUsage returns (0, 0) when usage object is absent") {
        assert(OpenAIProvider.extractUsage(noUsage) == (0, 0))
    }

    test("extractUsage returns (0, 0) when usage object has no known fields") {
        val json = usage("total_tokens" -> 500)
        assert(OpenAIProvider.extractUsage(json) == (0, 0))
    }

    test("extractUsage returns partial counts when only one field is present") {
        val json = usage("input_tokens" -> 300)
        assert(OpenAIProvider.extractUsage(json) == (300, 0))
    }

    test("Chat Completions tool schemas use the native function wrapper") {
        val spec = ToolSpec("files_read", "Read a file", ujson.Obj(
            "type" -> "object",
            "properties" -> ujson.Obj("path" -> ujson.Obj("type" -> "string")),
            "required" -> ujson.Arr("path")
        ))
        val json = provider.toCCFunctionSpecs(List(spec)).arr.head
        assert(json("type").str == "function")
        assert(json("function")("name").str == "files_read")
        assert(json("function")("parameters")("required").arr.head.str == "path")
    }

    test("Chat Completions parser treats null tool_calls as no calls") {
        val message = ujson.Obj("content" -> "Final answer", "tool_calls" -> ujson.Null)
        assert(provider.extractToolCalls(Some(message)).isEmpty)
    }

    test("Chat Completions parser extracts native tool calls") {
        val message = ujson.Obj(
            "content" -> ujson.Null,
            "tool_calls" -> ujson.Arr(ujson.Obj(
                "id" -> "call-123",
                "type" -> "function",
                "function" -> ujson.Obj("name" -> "files_read", "arguments" -> "{\"path\":\"a.txt\"}")
            ))
        )
        assert(provider.extractToolCalls(Some(message)) ==
            List(NativeToolCall("call-123", "files_read", "{\"path\":\"a.txt\"}")))
    }

    test("message serialization correlates assistant calls and tool results") {
        val calls = """[{"id":"call-1","type":"function","function":{"name":"files_read","arguments":"{}"}}]"""
        val messages = List(
            agentica.session.Message("a", "s", agentica.session.MessageRole.Assistant, "", "", toolCallsJson = Some(calls)),
            agentica.session.Message("t", "s", agentica.session.MessageRole.Tool, "result", "", toolCallId = Some("call-1"))
        )
        val json = provider.toMessagesJSON(messages)
        assert(json(0)("tool_calls")(0)("id").str == "call-1")
        assert(json(1)("role").str == "tool")
        assert(json(1)("tool_call_id").str == "call-1")
    }
}
