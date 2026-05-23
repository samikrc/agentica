package agentica.llm

import org.scalatest.funsuite.AnyFunSuite
import ujson.*

class OpenAIProviderTest extends AnyFunSuite
{
    private def usage(fields: (String, Int)*): ujson.Obj =
    {
        val inner = ujson.Obj()
        fields.foreach { case (k, v) => inner(k) = ujson.Num(v) }
        ujson.Obj("usage" -> inner)
    }

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
}
