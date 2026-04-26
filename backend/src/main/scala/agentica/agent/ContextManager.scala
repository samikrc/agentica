package agentica.agent

import agentica.session.Message

/** Assembles the message list sent to the LLM.
 *  Phase 1: returns all messages as-is (no token budget enforcement, no summarization).
 *  Phase 2: sliding window + summarization of older context + RAG injection.
 */
object ContextManager
{

    private val systemPrompt = Message(
        id        = "system-0",
        sessionId = "",
        role      = "system",
        content   = "You are Agentica, a helpful AI assistant running locally on the user's machine.",
        timestamp = ""
    )

    def assemble(history: List[Message]): List[Message] =
    {
        systemPrompt +: history
    }
}
