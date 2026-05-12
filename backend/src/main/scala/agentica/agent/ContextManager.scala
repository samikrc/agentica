package agentica.agent

import agentica.observability.TraceLogger
import agentica.session.{Message, MessageRole, Session}
import java.time.LocalDate

/**
 *  Assembles the message list sent to the LLM on each iteration.
 *
 *  The system prompt is loaded once from `system_prompt.txt` on the classpath.
 *  `{{TOOL_INDEX}}` is substituted once at startup; `{{ROOT_PATH}}` and `{{TODAY}}`
 *  are applied per-session in [[assemble]].
 *
 *  Token budgeting: messages are included newest-first until `contextBudgetTokens`
 *  is exhausted (estimated as `text.length / 4`). The system prompt, current user
 *  message, and in-flight tool-result messages are always included.
 */
object ContextManager
{

    /**
     *  Raw template text loaded from `system_prompt.txt` on the classpath.
     *  `{{TOOL_INDEX}}` is substituted by [[applyToolIndex]] at startup.
     */
    private var templateWithIndex: String = loadTemplate()

    /**
     *  Loads the system prompt template from the classpath resource.
     *  Falls back to a minimal inline prompt if the file is missing.
     *  @return  Raw template string with substitution slots intact.
     */
    private def loadTemplate(): String =
    {
        val stream = getClass.getClassLoader.getResourceAsStream("system_prompt.txt")
        if (stream == null)
        {
            TraceLogger.warn("-", "system_prompt_missing",
                Map("resource" -> "system_prompt.txt"))
            "You are Agentica, a local AI assistant.\nWorkspace: {{ROOT_PATH}}\nToday: {{TODAY}}\n{{TOOL_INDEX}}"
        }
        else
        {
            try
            {
                val text = scala.io.Source.fromInputStream(stream, "UTF-8").mkString
                stream.close()
                text
            }
            catch
            {
                case ex: Exception =>
                    TraceLogger.warn("-", "system_prompt_load_error",
                        Map("error" -> ex.getMessage))
                    "You are Agentica, a local AI assistant.\nWorkspace: {{ROOT_PATH}}\nToday: {{TODAY}}\n{{TOOL_INDEX}}"
            }
        }
    }

    /**
     *  Substitutes `{{TOOL_INDEX}}` into the template.
     *  Must be called once at startup after all tools have been registered.
     *  @param index  Output of `CommandRegistry.helpIndex`.
     */
    def applyToolIndex(index: String): Unit =
    {
        templateWithIndex = templateWithIndex.replace("{{TOOL_INDEX}}", index)
    }

    /**
     *  Estimates the token count of a message using the `length / 4` approximation.
     *  @param msg  Message to estimate.
     *  @return     Approximate token count.
     */
    private def estimateTokens(msg: Message): Int =
    {
        math.max(1, msg.content.length / 4)
    }

    /**
     *  Assembles the full message list for a single LLM call.
     *  Applies `{{ROOT_PATH}}` and `{{TODAY}}` substitutions, then applies the
     *  token-budget window to historical messages (newest-first, oldest dropped).
     *  The system prompt and current user message are always included.
     *  @param history              All session messages in chronological order,
     *                              NOT including the current user message.
     *  @param userMsg              The current user message (always included).
     *  @param session              Active session (provides `rootPath`).
     *  @param contextBudgetTokens  Maximum token budget for historical messages.
     *  @param traceId              Trace ID used for truncation log entries.
     *  @return                     Messages ready to send to the LLM provider.
     */
    def assemble(
        history:              List[Message],
        userMsg:              Message,
        session:              Session,
        contextBudgetTokens:  Int,
        traceId:              String
    ): List[Message] =
    {
        val rootPath   = session.rootPath.getOrElse("(no workspace)")
        val today      = LocalDate.now().toString
        val promptText = templateWithIndex
            .replace("{{ROOT_PATH}}", rootPath)
            .replace("{{TODAY}}", today)

        val systemMsg = Message(
            id        = "system-0",
            sessionId = "",
            role      = MessageRole.System,
            content   = promptText,
            timestamp = ""
        )

        val budgetedHistory = applyBudget(history, contextBudgetTokens, traceId)
        systemMsg +: budgetedHistory :+ userMsg
    }

    /**
     *  Legacy single-argument overload used by Phase 1 call sites.
     *  Uses no budget limit and no per-session substitution.
     *  @param history  Session messages in chronological order.
     *  @return         Messages ready to send to the LLM provider.
     */
    def assemble(history: List[Message]): List[Message] =
    {
        val systemMsg = Message(
            id        = "system-0",
            sessionId = "",
            role      = MessageRole.System,
            content   = templateWithIndex
                .replace("{{ROOT_PATH}}", "(no workspace)")
                .replace("{{TODAY}}", LocalDate.now().toString),
            timestamp = ""
        )
        systemMsg +: history
    }

    /**
     *  Applies the token-budget window to a list of historical messages.
     *  Messages are included newest-first; oldest are dropped once the budget is exhausted.
     *  Truncation is logged at debug level.
     *  @param history              Historical messages in chronological order.
     *  @param contextBudgetTokens  Token budget for historical messages.
     *  @param traceId              Trace ID for logging.
     *  @return                     Budget-filtered messages in chronological order.
     */
    private def applyBudget(
        history:             List[Message],
        contextBudgetTokens: Int,
        traceId:             String
    ): List[Message] =
    {
        var remaining = contextBudgetTokens
        val kept      = scala.collection.mutable.ArrayBuffer.empty[Message]

        history.reverseIterator.foreach { msg =>
            val cost = estimateTokens(msg)
            if (remaining >= cost)
            {
                kept.prepend(msg)
                remaining -= cost
            }
        }

        val dropped = history.length - kept.length
        if (dropped > 0)
        {
            TraceLogger.info(traceId, "context_truncated", Map(
                "messagesDropped" -> dropped.toString,
                "budgetTokens"    -> contextBudgetTokens.toString
            ))
        }

        kept.toList
    }
}
