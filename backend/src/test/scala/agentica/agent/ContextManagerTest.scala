package agentica.agent

import agentica.session.{Message, MessageRole, Session}
import org.scalatest.funsuite.AnyFunSuite

/**
 *  Unit tests for [[ContextManager]].
 *  Covers token-budget windowing (newest-first dropping, priority order)
 *  and the legacy single-argument overload.
 */
class ContextManagerTest extends AnyFunSuite
{

    private val traceId = "test-trace"

    private val session = Session(
        id        = "s1",
        title     = "Test",
        createdAt = "",
        updatedAt = "",
        model     = "test-model",
        rootPath  = Some("/workspace")
    )

    private def msg(role: MessageRole, content: String): Message =
        Message(id = "", sessionId = "s1", role = role, content = content, timestamp = "")

    private val userMsg = msg(MessageRole.User, "What files are here?")

    // ── Full-budget: all history fits ─────────────────────────────────────────

    test("all history included when budget is not exceeded") {
        val history = List(
            msg(MessageRole.User,      "Hello"),       // ~5 chars → 1 token
            msg(MessageRole.Assistant, "Hi there")    // ~8 chars → 2 tokens
        )
        val result = ContextManager.assemble(history, userMsg, session,
            contextBudgetTokens = 10000, traceId = traceId)

        // system prompt + both history messages + userMsg
        assert(result.head.role == MessageRole.System)
        val roles = result.map(_.role)
        assert(roles.count(_ == MessageRole.User)      >= 2)  // history user + userMsg
        assert(roles.count(_ == MessageRole.Assistant) == 1)
        assert(result.last == userMsg)
    }

    // ── Budget windowing: oldest dropped ─────────────────────────────────────

    test("oldest messages dropped when budget is tight") {
        // Each message content is 40 chars → ~10 tokens each
        val old1   = msg(MessageRole.User,      "A" * 40)
        val old2   = msg(MessageRole.Assistant, "B" * 40)
        val recent = msg(MessageRole.User,      "C" * 40)

        val history = List(old1, old2, recent)

        // Budget of 15 tokens fits only ~1.5 messages; newest-first means recent is kept
        val result = ContextManager.assemble(history, userMsg, session,
            contextBudgetTokens = 15, traceId = traceId)

        val contents = result.map(_.content)
        assert(!contents.contains(old1.content), "oldest message should be dropped")
        assert(contents.contains(recent.content), "most recent history message must be kept")
        assert(result.last == userMsg, "userMsg must always be last")
    }

    test("userMsg is always included regardless of zero budget") {
        val history = List(msg(MessageRole.User, "X" * 400))
        val result  = ContextManager.assemble(history, userMsg, session,
            contextBudgetTokens = 0, traceId = traceId)

        assert(result.last == userMsg)
        assert(result.head.role == MessageRole.System)
    }

    test("system prompt is always the first message") {
        val result = ContextManager.assemble(Nil, userMsg, session,
            contextBudgetTokens = 1000, traceId = traceId)
        assert(result.head.role == MessageRole.System)
    }

    test("history kept in chronological order after windowing") {
        val h1 = msg(MessageRole.User,      "first")
        val h2 = msg(MessageRole.Assistant, "second")
        val h3 = msg(MessageRole.User,      "third")

        val result = ContextManager.assemble(List(h1, h2, h3), userMsg, session,
            contextBudgetTokens = 10000, traceId = traceId)

        val contentOrder = result.map(_.content)
        val h1Idx = contentOrder.indexOf(h1.content)
        val h2Idx = contentOrder.indexOf(h2.content)
        val h3Idx = contentOrder.indexOf(h3.content)
        assert(h1Idx < h2Idx && h2Idx < h3Idx, "history must remain in chronological order")
        assert(h3Idx < contentOrder.indexOf(userMsg.content), "history before userMsg")
    }

    // ── Legacy overload ───────────────────────────────────────────────────────

    test("legacy single-arg assemble prepends system prompt") {
        val history = List(msg(MessageRole.User, "hi"), msg(MessageRole.Assistant, "hello"))
        val result  = ContextManager.assemble(history)
        assert(result.head.role == MessageRole.System)
        assert(result.tail == history)
    }
}
