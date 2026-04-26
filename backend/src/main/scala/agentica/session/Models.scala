package agentica.session

import upickle.default.*

/** A chat session grouping all messages under a single conversation context.
 *  @param id        Unique session identifier (UUID).
 *  @param title     Human-readable session title.
 *  @param createdAt ISO-8601 creation timestamp.
 *  @param updatedAt ISO-8601 last-updated timestamp.
 *  @param model     LLM model name used for this session (e.g. "llama3.2").
 *  @param rootPath  Optional filesystem path set as the session working directory.
 */
case class Session(
    id: String,
    title: String,
    createdAt: String,
    updatedAt: String,
    model: String,
    rootPath: Option[String]
) derives ReadWriter

/** A single message in a session's conversation history.
 *  @param id          Unique message identifier (UUID).
 *  @param sessionId   Parent session identifier.
 *  @param role        Speaker role: "user", "assistant", or "system".
 *  @param content     Text content of the message.
 *  @param timestamp   ISO-8601 timestamp of when the message was recorded.
 *  @param attachments List of attachment references (file paths or URIs); empty by default.
 */
case class Message(
    id: String,
    sessionId: String,
    role: String,       // "user" | "assistant" | "system"
    content: String,
    timestamp: String,
    attachments: List[String] = Nil
) derives ReadWriter

/** A record of a single tool invocation within an agent turn.
 *  @param id         Unique run identifier (UUID).
 *  @param sessionId  Parent session identifier.
 *  @param tool       Name of the tool that was invoked.
 *  @param input      JSON-serialised input payload passed to the tool.
 *  @param output     JSON-serialised output returned by the tool.
 *  @param status     Outcome of the run: "success", "error", or "cancelled".
 *  @param traceId    Trace identifier linking the run to its agent turn.
 *  @param durationMs Wall-clock duration of the tool invocation in milliseconds.
 */
case class ToolRun(
    id: String,
    sessionId: String,
    tool: String,
    input: String,      // JSON string
    output: String,     // JSON string
    status: String,     // "success" | "error" | "cancelled"
    traceId: String,
    durationMs: Long
) derives ReadWriter

/** Token and latency accounting for a single LLM call.
 *  @param id                Unique record identifier (UUID).
 *  @param traceId           Trace identifier of the agent turn that triggered the call.
 *  @param sessionId         Parent session identifier.
 *  @param model             LLM model name used for the call.
 *  @param promptTokens      Number of tokens in the prompt sent to the model.
 *  @param completionTokens  Number of tokens in the model's response.
 *  @param latencyMs         End-to-end latency of the LLM call in milliseconds.
 *  @param createdAt         ISO-8601 timestamp of when the record was created.
 */
case class TokenUsage(
    id: String,
    traceId: String,
    sessionId: String,
    model: String,
    promptTokens: Int,
    completionTokens: Int,
    latencyMs: Long,
    createdAt: String
) derives ReadWriter
