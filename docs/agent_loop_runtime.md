# Agentica Phase 2 — Agent Loop and Runtime: Technical Design

> This document is the detailed technical spec for Phase 2 of Agentica. It covers the full plan→act→observe agent loop, the virtual shell runtime, tool system, context management, permission model, and observability/debugging. It is intended to guide implementation and to record design decisions made before coding begins.
>
> **All design questions have been resolved. Decisions are recorded inline throughout the document and summarised in §10.**

---

## 1. Current State (Phase 1 Baseline)

What exists and works today:

- `AgentEngine` trait with `run(session, history, userMsg, traceId, onToken, onEvent)` signature. *(Phase 2 adds `cancelFlag: AtomicBoolean` — see §3.3.)*
- `AgentLoop`: Phase 1 stub — single `llm.stream()` call, no tool dispatch, no iteration.
- `ContextManager.assemble()`: prepends a hardcoded system prompt, returns all history as-is.
- `Routes.scala`: orchestrates the virtual thread, SSE queue, run/cancel lifecycle, and `AgentEvent` → SSE serialization. Core wiring is complete; Phase 2 adds `cancelFlag` threading, `permissionQueues`, and `POST /permissions` (see §6.2).
- `AgentEvent` enum: `IterationBoundary`, `ToolCallStart`, `ToolCallResult`, `Final`, `Cancelled`, `AgentError` — all already defined. Phase 2 adds `PermissionRequired(tool, path, options)` (see §6.2).
- All `shell/` and `tools/` files exist as stub packages with TODO comments.
- `ScopeStore`, `VirtualShell`, `CommandRegistry`, `Tokenizer`, `CommandAst`, `Presentation`, `Tool` — all stubs.

Phase 2 replaces the `AgentLoop` body and fills in all stubs. `Routes.scala` receives minimal additions: `cancelFlag` parameter threading, `permissionQueues` map, and `POST /permissions` endpoint (§6.2). All other wiring is unchanged.

---

## 2. Phase 2 Scope

Phase 2 delivers the following sub-systems, in dependency order:

1. **Virtual Shell and Command DSL** — `Tokenizer`, `CommandAst`, `CommandRegistry`, `VirtualShell`, `Presentation` (§4)
2. **Tool implementations** — `files.*`, `memory.*`, `llm.*` (§5)
3. **Permissions** — `ScopeStore`, path sandboxing, UI-side permission prompts (§6)
4. **Context Management** — token-budget-aware window, oldest-first truncation (§7)
5. **Agent Loop** — multi-iteration plan→act→observe, cancellation, error recovery (§3)
6. **System Prompt** — hybrid template with substitution slots, full content spec (§8)
7. **Observability and Debugging** — log file output, log viewer UI, replay scaffolding, golden scenarios (§9)

---

## 3. Agent Loop (plan→act→observe)

### 3.1 Contract

The `AgentLoop` class continues to implement `AgentEngine`. Its `run()` signature gains one parameter: `cancelFlag: AtomicBoolean` (see §3.3). The full contract from `Routes.scala` side:

- `run()` is called on a virtual thread; it may block freely.
- It communicates progress via `onToken` and `onEvent` callbacks.
- It must emit exactly one terminal event: `Final`, `Cancelled`, or `AgentError`.
- After emitting a terminal event, it must not call `onToken` or `onEvent` again.

### 3.2 Loop Structure

```
AgentLoop.run(session, history, userMsg, traceId, cancelFlag, onToken, onEvent):

  assembled = ContextManager.assemble(history, userMsg, session)
  iteration = 1

  loop:
    if iteration > maxIterations:
      emit AgentError("max_iterations_exceeded")
      return

    if cancelFlag.get():
      emit Cancelled
      return

    emit IterationBoundary(iteration)

    llmMessages = buildLlmMessages(assembled, pendingToolResults)
    (responseText, usage) = llm.stream(llmMessages, onToken)
    record usage

    parsed = ToolCallParser.parse(responseText)

    if parsed.isEmpty or parsed is FinalAnswer:
      assistantMsg = persist(responseText)
      emit Final(assistantMsg.id)
      return

    for each toolCall in parsed:
      emit ToolCallStart(toolCall.command, toolCall.rawArgs)
      if cancelFlag.get():
        emit Cancelled
        return
      result = VirtualShell.execute(toolCall, session, traceId)
      emit ToolCallResult(toolCall.command, result.rendered, result.durationMs)
      persist tool run record

    pendingToolResults = format tool results as plain [TOOL RESULT] user turn
    append pendingToolResults to assembled context
    iteration += 1
```

### 3.3 Cancellation

**Decision (Q-1): `cancelFlag: AtomicBoolean` is added as a parameter to `AgentEngine.run()`.** The trait signature becomes:

```scala
def run(
    session:    Session,
    history:    List[Message],
    userMsg:    Message,
    traceId:    String,
    cancelFlag: AtomicBoolean,
    onToken:    String => Unit,
    onEvent:    AgentEvent => Unit
): Unit
```

Rationale: the cancel flag is a run-scoped input, conceptually identical to `traceId`. It belongs in the signature for the same reason `traceId` does. There is exactly one implementation (`AgentLoop`) and one call site (`Routes.scala`), so the blast radius is two files. The loop polls `cancelFlag.get()` between iterations and between tool calls.

### 3.4 Max Iterations

**Decision (Q-2): `maxIterations` is a global setting in `AppSettings` / `settings.json`, exposed in the Settings dialog alongside LLM URL and model.** Default value: **20**. The loop reads it from the injected settings at run start — it is not re-read mid-run.

The `AppSettings` model gains:
```scala
maxIterations: Int = 20
```

This means it appears in the Settings modal under an "Agent" section (or alongside the LLM settings), is persisted in `settings.json`, and is loaded by `AgentLoop` on construction. Per-session override is deferred.

### 3.5 Tool Result Injection and Tool Call Format

**Decision (Q-3 / Q-4 / Q-13 — consolidated):** The model emits `run(command="...")` inline in its freeform text. Tool results are injected back as a plain `user` turn prefixed with `[TOOL RESULT]`. No native function-call message role is used in Phase 2.

Message list shape per iteration:

```
{ role: "assistant", content: "I'll read the file.\nrun(command=\"files.read path=data/report.txt\")" }
{ role: "user",      content: "[TOOL RESULT]\n$ files.read path=data/report.txt\nok\n─────\n<contents>" }
```

Rationale: works with all local models regardless of fine-tuning; the CLI DSL is text-native; graceful degradation on model output variations. Native function-calling (`tool` message role) is deferred to Phase 3 when cloud providers arrive and can be toggled per-provider via `nativeFunctionCalling: Boolean` on `LLMProvider`.

**Logging**: `ToolCallParser` runs on every LLM response before dispatch. Its output — parsed `command`, `args` — is the source of truth for all logging and SSE events. The sequence is:
1. Parse → `List[ParsedToolCall]`
2. For each: emit `ToolCallStart` SSE event + `TraceLogger` line
3. `VirtualShell.execute()` → `AgentResponse`
4. Emit `ToolCallResult` SSE event + `TraceLogger` line + `RunStore.persist()`

Parse failures (malformed `run()` output from the model) are logged as `TraceLogger.warn` with the raw response text, and treated as a final answer (no tool dispatched).

**Multiple `run()` calls per turn**: allowed. The parser extracts all `run(...)` occurrences in order. Each is dispatched and logged individually before the next LLM turn.

### 3.6 `ToolCallParser`

Scans the model's response text for all `run(command="...")` occurrences in order. Implementation: a simple iterative scan (no full regex over the whole string) that finds `run(command=` boundaries, then extracts the quoted value handling escaped quotes.

```scala
case class ParsedToolCall(rawCommand: String, startOffset: Int, endOffset: Int)

/** A run() call that could not be parsed; carries the raw offending text for error injection. */
case class ParseFailure(rawSnippet: String, reason: String, startOffset: Int)

/** Sum type returned by ToolCallParser.parse(). */
enum ToolCallResult:
  case Success(call: ParsedToolCall)
  case Failure(err: ParseFailure)

object ToolCallParser:
  def parse(text: String, traceId: String): List[ToolCallResult]
```

`rawCommand` is the unescaped value of the `command="..."` argument, ready for `Tokenizer.parse()`. `startOffset`/`endOffset` are used by the loop to extract any narrative text the model wrote outside `run()` calls.

Edge cases to handle: nested quotes, missing closing `)`, extra whitespace. Malformed `run()` syntax is **never silently dropped**. Instead:
- A `TraceLogger.warn` is emitted with `parseError` and the raw snippet.
- A `ToolCallResult.Failure` is returned in-position so `AgentLoop` can inject a structured error result into the `[TOOL RESULT]` block.

**Rationale:** Silently skipping a malformed `run()` call corrupts the model's reasoning chain. On the next iteration the model receives a `[TOOL RESULT]` block with no entry for the call it intended to make. It cannot distinguish "tool ran and returned nothing" from "tool was never dispatched". This can cause the model to fabricate output, stall in a loop, or produce a confident wrong final answer. By injecting a structured error result the model can observe the failure and self-correct (e.g. re-issue the call with corrected syntax).

Error result injected by `AgentLoop` for each `ToolCallResult.Failure`:
```
$ <rawSnippet>
error: parse_failed
─ message: <reason>
─ hint: check quoting — command= value must be a double-quoted string
```

### 3.7 Final Answer Detection

**Decision (Q-5):** The model is instructed to end its final answer with `<done>` on its own line. The loop checks for this marker after confirming no `run(...)` calls are present.

Termination logic:
1. If `ToolCallParser` finds `run(...)` calls → dispatch tools, continue loop.
2. Else if response contains `<done>` → strip the marker, persist remaining text as final answer.
3. Else (no `run()`, no `<done>`) → log a warning (`missing_terminator`), accept the response as final answer anyway (soft fallback). No error raised.

Rationale: the explicit marker is unambiguous and easy to verify in golden scenarios. The soft fallback ensures graceful degradation on non-compliant model output without breaking the user experience.

---

## 4. Virtual Shell Runtime

### 4.1 `CommandAst`

```scala
// shell/CommandAst.scala
case class Command(
  family: String,              // e.g. "files"
  verb:   String,              // e.g. "read"
  args:   Map[String, String]  // e.g. Map("path" -> "foo.txt")
)
```

No pipelines. The FTRD §10 output-capture design (`$last`, `$1`, `$2`, ...) is realised in Phase 2 as the more focused **`$scratch/<path>` ref system** (§4.6) — path-keyed and scoped to large-output references rather than every tool result. General-purpose `$last`/`$1` variables remain deferred.

### 4.2 `Tokenizer`

Hand-written tokenizer. Input: raw command string after stripping `run(command="...")`  wrapper.

Grammar (informally):

```
command    ::= family '.' verb { ' ' arg }
arg        ::= key '=' value
value      ::= bare-word | '"' quoted-string '"'
bare-word  ::= [^ \t=]+
quoted-string ::= any chars except unescaped '"', with \" as escape
```

Tokenizer stages:
1. Split on `=` to find key boundaries.
2. Detect quoted vs. bare values.
3. Handle escaped quotes (`\"`) within quoted values.
4. Return `Right(Command(...))` or `Left(ParseError(...))`.

Error cases to handle: missing verb, missing `=`, unclosed quote, unknown family (detected at dispatch time, not parse time).

Unit tests: parse all valid forms, all error cases, embedded newlines in quoted values, multi-word quoted values.

### 4.3 `CommandRegistry`

Central registry: all tools register here. It is the **only** place that enumerates the full tool list.

```scala
// shell/CommandRegistry.scala
trait CommandRegistry:
  def dispatch(cmd: Command, ctx: ExecutionContext): ToolResult
  def helpIndex: String              // one line per family — used in system prompt
  def helpFor(family: String, verb: String): String  // full schema + examples
  def allSchemas: List[CommandSchema] // for replay test JSON generation
```

`ExecutionContext` carries: `session.rootPath`, `traceId`, `session.id`, `scopeStore` reference, `scratchpad: SessionScratchpad` reference.

All tools are registered at application startup in `BackendServer.scala`, then injected into `VirtualShell`.

### 4.4 `VirtualShell`

Entry point called by `AgentLoop`:

```scala
// shell/VirtualShell.scala
class VirtualShell(registry: CommandRegistry):
  def execute(rawCommand: String, ctx: ExecutionContext): AgentResponse
```

Internal flow:
1. `Tokenizer.parse(rawCommand)` → `Command` or parse error.
2. `registry.dispatch(cmd, ctx)` → `ToolResult`.
3. `Presentation.render(cmd, toolResult)` → `AgentResponse`.

All three stages return `Either`-style results; errors propagate as structured `AgentResponse` with `error:` status.

### 4.5 `Presentation` Layer

Converts a typed `ToolResult` into the `AgentResponse` text envelope:

```
$ files.read path=foo.txt
ok
─ size: 1.2 KB · lines: 47 · truncated: false
─────
<body>
```

Rules:
- Line 1: command echo — always `$ family.verb arg1=val1 arg2=val2`.
- Line 2: `ok` or `error: <code>`.
- Metadata lines prefixed with `─`.
- Body (if any) separated by `─────`.
- Binary content → `<binary N KB mime/type>` placeholder.
- Errors: always include `─ hint: ...` and `─ try: ...` lines.

**Decision (Q-6):** Body budget is a **fixed constant of 8000 characters** (~2000 tokens), sufficient for any local model with ≥4K context window. This is a named constant in `Presentation.scala` (`BODY_BUDGET_CHARS = 8000`), not a user-configurable setting. When the body exceeds this limit, the presentation layer routes the full content to the `SessionScratchpad` (§4.7) instead of truncating.

Overflow response shape:

```
$ files.read path=big_report.txt
ok
─ size: 120 KB · lines: 4821 · stored: $scratch/data/big_report.txt
─ hint: content too large for context; use targeted tools to query it
─ try: run(command="files.search query=\"your term\" path=big_report.txt")
─ try: run(command="files.read path=big_report.txt lines=1-50")
─ try: run(command="llm.summarize text=$scratch/data/big_report.txt")
```

### 4.6 Scratchpad — `SessionScratchpad`

A **session-scoped, in-memory content cache** that holds full tool output bodies too large for the context window. Entries are keyed by source path (not a sequence number), so the same file resolves to the same ref across multiple turns within a session.

```scala
// shell/Scratchpad.scala
case class ScratchEntry(
  content:      String,   // full text content
  sizeBytes:    Long,
  lineCount:    Int,
  sourcePath:   String,   // relative to rootPath, e.g. "data/report.txt"
  lastModified: Long,     // file's lastModifiedTime at read time (epoch ms)
  storedAt:     Long      // System.currentTimeMillis — for LRU eviction
)

class SessionScratchpad:
  private val MAX_ENTRIES = 20
  private val entries = mutable.LinkedHashMap[String, ScratchEntry]()

  def store(path: String, entry: ScratchEntry): String  // returns "$scratch/<path>"
  def get(ref: String): Option[ScratchEntry]            // resolves "$scratch/<path>"
  def isStale(path: String, currentModified: Long): Boolean
  private def evictOldestIfFull(): Unit
```

**Lifecycle**: one `SessionScratchpad` per active session, held in a `ConcurrentHashMap[sessionId, SessionScratchpad]` in `BackendServer`. Removed when the session is deleted. Lost on backend restart — the agent re-reads files on the next turn (graceful degradation).

**Staleness**: when `files.read` is called for a path already in the scratchpad, `isStale()` compares the file's current `lastModifiedTime` against the stored value. If stale, the entry is replaced. If fresh, the existing ref is returned without re-reading.

**Substitution pass**: the tokenizer/executor resolves `$scratch/<path>` refs in any argument value before dispatching to the tool. The tool receives the full `String` content directly — it never sees the ref. This means `llm.summarize text=$scratch/data/report.txt` passes the full content straight into `LLMProvider.complete()` without it ever appearing in the agent's conversation history.

**Scope**: `ExecutionContext` carries a reference to the session's `SessionScratchpad`.

### 4.7 `AgentResponse` and `ToolResult`

```scala
// Execution layer output — typed
case class ToolResult(
  status:   ToolStatus,  // Ok | Error(code, message)
  metadata: Map[String, String],
  body:     Option[ToolBody]  // FileContent | MemoryValue | SummaryText | ...
)

enum ToolStatus:
  case Ok
  case Err(code: String, message: String, hints: List[String], trySuggestions: List[String])

// Presentation layer output — text ready for LLM
case class AgentResponse(text: String, durationMs: Long)
```

Error code set (closed): `not_found`, `permission_denied`, `invalid_args`, `path_escaped`, `cancelled`, `internal_error`.

---

## 5. Tool Implementations

### 5.1 `Tool` Trait

```scala
// tools/Tool.scala
trait Tool[I, O]:
  def name: String      // "files.read"
  def schema: CommandSchema
  def validate(args: Map[String, String]): Either[ArgError, I]
  def execute(input: I, ctx: ExecutionContext): O
  def render(output: O): ToolResult
```

The `validate → execute → render` pipeline is the contract. `VirtualShell` calls them in sequence; each stage is independently testable.

**Path resolution rule** (applies to all file-touching tools): every `path=` argument is resolved against `ctx.session.rootPath` using `Path.of(rootPath).resolve(argPath).normalize()`, then checked that the resolved path `startsWith(rootPath)`. If not: return `ToolStatus.Err("path_escaped", ...)`. This logic lives in a single shared `PathSandbox` utility, not per-tool boilerplate.

### 5.2 `files` Family

| Command | Args | Returns |
|---|---|---|
| `files.read` | `path`, `lines` (optional range, e.g. `1-50`) | File text content inline if ≤8000 chars; otherwise stored in `SessionScratchpad` and ref returned |
| `files.write` | `path`, `content` | Confirmation + bytes written |
| `files.list` | `path` (optional), `recursive` (default false), `all` (dotfiles, default false), `depth` (default 3), `pattern` (glob filter) | Indented tree listing with size and date per file |
| `files.search` | `query`, `path` (optional), `recursive` (default true), `ignore_case` (default false), `lines_context` (default 2), `max_matches` (default 50), `include` (glob), `regex` (default false) | Grep-style matches with context lines |
| `files.stat` | `path` | File size, modified time, type |

**`files.write` is a sensitive (mutating) tool** — subject to permission scope check before execution (§6).

**Decision (Q-7):** Both tools are grep/ls-style, implemented with `java.nio.file` APIs (no subprocess, no shell). `files.search` uses a line-by-line scan with `PathMatcher` for `include` glob filtering. `files.list` uses `Files.walk()` with depth cap. Index-based search is Phase 5.

`files.search` output shape (in `AgentResponse` envelope):
```
$ files.search query="revenue" path=reports/ lines_context=2
ok
─ matches: 7 · files: 3 · truncated: false
─────
reports/q3.txt:15:> Total revenue grew 12% YoY
reports/q3.txt:14:  Total revenue grew 12%
reports/q3.txt:16:  compared to prior quarter
---
reports/annual.txt:42:> Revenue: $4.2M
```

`files.list` output shape:
```
$ files.list path=src/ recursive=true depth=2
ok
─ entries: 12 · dirs: 3 · files: 9
─────
src/
  main.py          4.2 KB  2025-05-01
  utils/
    helpers.py     1.1 KB  2025-04-28
  tests/
    test_main.py   0.8 KB  2025-05-01
```

Binary files encountered during search are skipped with a `<binary file skipped>` note. Results exceeding `max_matches` or the 8000-char body budget route to the scratchpad as normal.

### 5.3 `memory` Family

Session-scoped key-value store. Memory entries are persisted in SQLite per session.

| Command | Args | Returns |
|---|---|---|
| `memory.set` | `key`, `value` | Confirmation |
| `memory.get` | `key` | Stored value or not_found |
| `memory.list` | (none) | All keys for this session |

Schema additions needed to `session` DB: `memory_entries(session_id, key, value, updated_at)`.

**Decision (Q-8):** Session-scoped only in Phase 2. `MemoryStore` is a trait backed by SQLite with `sessionId: Option[String]` — `None` means global, but only `Some(sessionId)` is used in Phase 2. Phase 6 adds `scope=global` to the tool surface without structural change.

### 5.4 `llm` Family

Utility tools that make a **nested, non-streaming** LLM call (via `LLMProvider.complete()`). These are tools the agent can call as processing steps within a run — not the primary conversational call.

| Command | Args | Returns |
|---|---|---|
| `llm.summarize` | `text` (string or `$scratch/<path>` ref) | Summary paragraph |
| `llm.extract` | `text` (string or `$scratch/<path>` ref), `fields` | JSON with extracted field values |
| `llm.classify` | `text` (string or `$scratch/<path>` ref), `labels` | Selected label + confidence |

`$scratch/<path>` refs are resolved by the substitution pass before the tool executes. The full content goes directly into `LLMProvider.complete()` — never into the agent's conversation history.

**Decision (Q-9):** Same `LLMProvider` instance as the main conversation, but each `llm.*` tool call constructs a **fresh, isolated message list** — it does not share or append to the agent's conversation history. The tool call is effectively a stateless completion: `LLMProvider.complete(List(systemMsg, userMsg))` where `userMsg` contains the tool's input (e.g., the text to summarise). No session ID is passed; no messages are persisted to `MessageStore`.

This avoids cross-contamination between the tool sub-call and the main conversation context. Token usage is recorded via `TokenAccounting` with `callType = "tool_llm"` to distinguish it from the main loop calls. Separate model routing is a Phase 3+ concern.

### 5.5 `help` Command

A special command (not a `Tool[I,O]`) handled directly in `CommandRegistry`:

```
help                 → helpIndex (one line per family)
help files           → all verbs for files family
help files.read      → full arg schema + example for files.read
```

Output is in the `AgentResponse` envelope with `ok` status and body containing the help text. The system prompt references `help` as the discovery mechanism.

---

## 6. Permission Model

### 6.1 Scope Representation

```scala
// permissions/ScopeStore.scala
case class Grant(
  id:        String,
  sessionId: Option[String],  // None = global
  toolSet:   String,          // "files.write", "files.*", etc.
  pathPrefix: Option[String], // None = any path
  ttl:       GrantTTL
)

enum GrantTTL:
  case Once          // consumed on first use, then deleted
  case ForSession    // valid while session is active
  case Always        // persisted indefinitely
```

Stored in SQLite table `permission_grants`. Checked before any `execute()` call on a sensitive tool.

Sensitive tools in Phase 2: `files.write`. (`memory.set` is not gated — it's session-scoped and low risk.)

`GrantDecision` carries the user's response from the modal back to the suspended agent run:

```scala
enum GrantDecision:
  case Granted(ttl: GrantTTL, pathPrefix: Option[String])
  case Denied
```

### 6.2 Check Flow

**Decision (Q-10): Option A — SSE-based run suspension with UI modal.**

```
VirtualShell.execute(cmd, ctx):
  if tool.isSensitive:
    grant = ScopeStore.check(cmd, ctx)
    if grant.isEmpty:
      emit AgentEvent.PermissionRequired(tool, path, grantOptions)
      result = ctx.permissionLatch.await(timeout = 60s)
      if result == Denied or timeout:
        return AgentResponse(error: permission_denied)
      else:
        ScopeStore.store(result.grant)
        // fall through to execute
```

Flow:
1. Agent loop hits a sensitive tool with no existing grant.
2. Emits `AgentEvent.PermissionRequired(tool, path, List[GrantTTL])` over SSE.
3. Blocks on a per-run `SynchronousQueue[GrantDecision]` with a **60-second timeout**.
4. Frontend receives the SSE event, shows a modal dialog with options: **Allow once / Allow for session / Allow always / Deny**.
5. User responds → frontend calls `POST /permissions { runId, tool, path, decision }`.
6. Backend handler writes the decision into the `SynchronousQueue` for the blocked run.
7. If timeout expires with no response → `permission_denied` returned gracefully; loop continues.

`AgentEvent` gains a new case:
```scala
case PermissionRequired(tool: String, path: Option[String], options: List[String])
```

`Routes.scala` gains:
- `permissionQueues: ConcurrentHashMap[runId, SynchronousQueue[GrantDecision]]`
- `POST /permissions` endpoint: looks up `runId`, offers decision to queue.

Phase 2 scope: `files.write` only. `memory.set` is not gated in Phase 2 (low risk, session-scoped).

### 6.3 Path Sandboxing

Enforced in `PathSandbox` utility (not per-tool):
- Resolve `path` against `session.rootPath`.
- Reject if resolved path does not start with `rootPath`.
- Reject if `rootPath` is null/empty (session has no workspace configured).

This check runs before the permission scope check.

---

## 7. Context Management

### 7.1 Current State

`ContextManager.assemble()` returns `[systemPrompt] + allHistory` with no truncation. This works for short sessions but will overflow the model's context window on longer ones.

### 7.2 Token Budget Strategy

Phase 2 `ContextManager` needs:

1. **Token estimation**: a fast approximate token count for each message. For Phase 2, use `text.length / 4` as a rough estimate (1 token ≈ 4 chars). A tiktoken-compatible counter can be added later.
2. **Budget-aware window**: given a configurable `contextBudget` (e.g., 8000 tokens for the messages, leaving headroom for the system prompt and the next LLM completion), include messages from the most recent backwards until the budget is exhausted.
3. **System prompt**: always included regardless of budget.
4. **Current user message**: always included.
5. **Tool result messages**: always included if they are part of the current run's loop (they're ephemeral and essential for the current turn).

**Decision (Q-11):** `contextBudgetTokens: Int = 8000` added to `AppSettings`, exposed in the Settings dialog alongside LLM URL and model name. Conservative default (8000) is safe for smallest common local models (Mistral 7B at 4K–8K window); users with 32K+ models raise it once. Token estimation uses `text.length / 4` (fast, good enough for windowing). Model-derived context length is not used — unreliable for local providers.

Priority within the budget (highest to lowest):
1. System prompt — always included, outside the budget.
2. Current user message — always included.
3. In-flight tool result messages for the current run — always included.
4. Historical messages — included newest-first until budget is exhausted; oldest are silently dropped. Truncation logged at `TraceLogger.debug`.

### 7.3 Summarization

**Decision (Q-12): Deferred to Phase 5.** Dropped messages are silently discarded. No summary injection in Phase 2.

Rationale: summarization requires an extra `llm.*` call mid-assembly, adding latency and tokens on every turn once the window fills. The `SessionScratchpad` mitigates the main content-loss problem for file reads; `memory.*` handles deliberate facts. For short-to-medium personal assistant tasks the window won't fill. Phase 5 (RAG + long autonomous runs) is the right time to add summarization.

### 7.4 Workspace File Context (RAG Deferral)

The task list includes "Decide how workspace files enter context." For Phase 2, workspace files enter context only via explicit `files.read` tool calls issued by the agent. Proactive file injection (embedding-based retrieval, RAG) is Phase 5. The Phase 2 decision to document: *files enter context on demand via agent tool calls, not via automatic injection.*

---

## 8. System Prompt Design

The system prompt is the primary interface between the codebase and the model's behavior. It must be maintained as a **checked-in text template** (not hardcoded in `ContextManager`) so it can be evolved, diffed, and tested independently.

**Decision (Q-14): Hybrid — static template file with runtime substitution slots.**

File: `backend/src/main/resources/system_prompt.txt` (checked in, diffable, editable without recompilation).

Substitution slots applied at startup/session start:
- `{{TOOL_INDEX}}` → `CommandRegistry.helpIndex()` (auto-updated when tools are added)
- `{{ROOT_PATH}}` → `session.rootPath`
- `{{TODAY}}` → current date (ISO-8601)

Template structure:

```
[IDENTITY]
You are Agentica, a local AI assistant. You are working in workspace: {{ROOT_PATH}}.
Today is {{TODAY}}.

[TOOL USAGE]
To use a tool, emit exactly:
  run(command="family.verb arg1=val1 arg2=val2")

You may call multiple tools in one response. After each tool call you will
receive a [TOOL RESULT] message. When you have finished, end your response
with <done> on its own line.

[TOOL INDEX]
{{TOOL_INDEX}}

[RESPONSE ENVELOPE]
Tool results arrive as:
  $ family.verb arg=val
  ok  (or error: <code>)
  ─ metadata
  ─────
  body

If a file is too large for context it is stored as $scratch/<path>.
Use run(command="llm.summarize text=$scratch/<path>") or targeted
files.search / files.read with lines= to query it.

[SAFETY]
- Never access paths outside the workspace.
- Only registered run() commands work; no shell execution.
```

The template is loaded once at `BackendServer` startup into a `String`, substitution applied per-session for `ROOT_PATH` and per-run for `TODAY`. `TOOL_INDEX` is substituted once at startup (tools don't change at runtime).

### 8.1 Full Template Specification

The following is the intended content of `system_prompt.txt`. Substitution slots are marked `{{SLOT}}`.

```
[IDENTITY]
You are Agentica, a local-first AI assistant running on the user's machine.
You have access to the user's workspace and a set of tools to help complete tasks.
Workspace: {{ROOT_PATH}}
Today: {{TODAY}}

[TOOL USAGE]
To call a tool, emit exactly this pattern in your response:

  run(command="family.verb arg1=val1 arg2=val2")

Rules:
- You may emit multiple run() calls in a single response. They are dispatched
  in the order they appear.
- After each tool call you will receive a [TOOL RESULT] message. Read it
  before deciding the next action.
- Do NOT emit run() and <done> in the same response.
- When you have completed the task and are ready to give your final answer,
  write your answer and then end your response with:

    <done>

  on its own line. If you forget <done>, your response will still be accepted
  but it is preferred.
- If a tool returns an error, read the hint: and try: lines and adapt your
  approach. Do not retry the exact same call unchanged.
- Call run(command="help") to see all available tools.
  Call run(command="help files") to see all verbs for a family.
  Call run(command="help files.read") to see full arg schema and an example.

[TOOL INDEX]
{{TOOL_INDEX}}

[TOOL RESULT FORMAT]
Tool results are delivered as [TOOL RESULT] messages in this envelope:

  $ family.verb arg=val
  ok
  ─ metadata-key: value
  ─────
  body content

Or on error:

  $ family.verb arg=val
  error: <code>
  ─ hint: what went wrong
  ─ try: run(command="...")   ← a suggested corrective action

Error codes: not_found, permission_denied, invalid_args, path_escaped,
cancelled, internal_error.

[SCRATCHPAD]
If a file or tool output is too large to include in context, it is stored
automatically and a reference is returned instead:

  ─ stored: $scratch/path/to/file.txt
  ─ hint: content too large for context; use targeted tools to query it
  ─ try: run(command="files.search query=\"term\" path=path/to/file.txt")
  ─ try: run(command="files.read path=path/to/file.txt lines=1-50")
  ─ try: run(command="llm.summarize text=$scratch/path/to/file.txt")

Rules for scratchpad refs:
- $scratch/path refs are stable within this session.
- Pass a $scratch ref as the text= argument to any llm.* tool; the full
  content will be used without appearing in the conversation.
- Re-reading the same file returns the same ref if the file has not changed.

[PERMISSIONS]
- files.write requires user approval. If you receive permission_denied, the
  user has been notified and asked. Do not retry immediately; wait for the
  next user message.
- Never attempt to access files outside the workspace ({{ROOT_PATH}}).
  path_escaped errors mean the path left the workspace boundary.

[BEHAVIOUR GUIDELINES]
- Think step by step before issuing tool calls. State your plan briefly.
- Prefer targeted reads over reading whole large files:
    files.search before files.read
    files.read with lines= for a known range
- Use memory.set to preserve important facts across a long multi-step task.
- If you are unsure what files exist, use files.list or files.search first.
- When a task is complete, give a concise summary of what was done.
- Do not apologise for tool failures; adapt and try a different approach.
- Do not fabricate file contents or tool results; always use run() to read
  actual data.
```

---

## 9. Observability, Debugging, and Testing

### 9.1 Debug Log Viewer (UI)

**Departure from FTRD §15a:** The FTRD describes a "debug pane in the UI" inline with chat. That pane was removed from `index.html` in a prior cleanup; `debug.js` is currently dead code (its `document.getElementById('debug-log')` target is absent).

Phase 2 deliberately replaces the inline debug pane with a **"Open debug log" button** that opens a dedicated log viewer window. Rationale: a separable window is more useful for a developer-facing debug surface — it can go on a second monitor, supports live filtering across long sessions, and survives navigation of the main chat. The same SSE/log information is exposed; only the rendering surface changes.

#### Backend: `GET /log/stream` SSE endpoint

- `TraceLogger` writes to both stdout and a rolling file at `AppDirs.dataDir/logs/agentica.log`.
- `GET /log/stream` (authenticated) tails the log file in real time:
  1. On connect, replay the last 200 lines from `agentica.log`.
  2. Then stream new lines as they arrive via a background thread reading with `RandomAccessFile` (poll every 100ms).
  3. Each line is emitted as an SSE `event: log` with the raw log line as data.
- Global log (all sessions, all tool calls). Client-side filtering handles narrowing.

#### Frontend: log viewer

- A **"Debug log"** button in the main UI header calls `window.open('/log-viewer.html')`.
- `log-viewer.html` is a minimal static page served by the backend:
  - Connects to `GET /log/stream` as an `EventSource`.
  - Renders lines with level-based colouring: INFO (default), WARN (yellow), ERROR (red).
  - Auto-scrolls to bottom; **"Pause scroll"** toggle.
  - **Filter input**: client-side substring filter on log lines (type `traceId`, `files.read`, `sessionId`, etc.).
  - **"Clear display"** button (clears the viewer display only, not the file).
- `debug.js` is repurposed or replaced to serve this page; it no longer targets `index.html` DOM elements.

### 9.2 Structured Tracing

`TraceLogger` emits structured log lines to **both stdout and `agentica.log`**. Log line fields:
- `traceId`, `sessionId`, `iteration`, `tool`, `status`, `durationMs`
- For `llm.*` tool calls: `callType=tool_llm`, `parentTraceId` linking to the outer run trace.
- For permission events: `tool`, `path`, `decision`, `grantTTL`.
- For context truncation: `messagesDropped`, `budgetTokens`.
- For parse failures: `rawResponse` (first 200 chars), `parseError`.

### 9.3 `RunStore` and Tool Run Persistence

`RunStore` exists but its schema needs to align with Phase 2 tool events. Each `ToolRun` record should store:
- `runId`, `sessionId`, `traceId`, `iteration`
- `tool` (e.g., `"files.read"`), `input` (raw args as JSON), `output` (rendered response), `status`, `durationMs`

**Decision (Q-15):** Per-call persistence. Each tool run record is written to `RunStore` immediately after execution. Safer for crash recovery and debug replay. (See also §3.5 logging decision.)

### 9.4 Replay Test Scaffolding

Golden scenario format (per FTRD §15c):

```yaml
# scenarios/read-and-summarize.yaml
description: "Read a file and produce a summary"
workspace:
  - path: "data/report.txt"
    content: "..."
prompt: "Summarize the file data/report.txt"
expected_tool_calls:
  - "files.read path=data/report.txt"
  - "llm.summarize text=..."
success_criteria:
  final_answer_contains: ["summary", "report"]
```

The test runner:
1. Creates a temp workspace with the specified files.
2. Instantiates `AgentLoop` with a `MockLLMProvider` that replays pre-recorded responses.
3. Verifies tool calls match expected sequence.
4. Records metrics (tool calls, tokens, latency) to SQLite.

**Decision (Q-16): Hybrid mock strategy.**

- **Phase 2 (building)**: `ScriptedLLMProvider` — constructor takes `List[String]`, returns one string per `stream()` call in order. Fast to write, no files. Used for all new scenarios while the system prompt is still evolving.
- **Post system-prompt stabilisation**: `RecordingLLMProvider` wrapper captures real model responses to a YAML file. Recorded scenarios are replayed by `ReplayLLMProvider` and treated as regression tests — a replay mismatch signals an unintended prompt change.
- **Recording mechanism**: ~30 LOC wrapper around `LLMProvider` that writes each response to `scenarios/<name>.yaml` alongside the golden scenario spec. Re-recording is a deliberate act, not automatic.

Both providers implement `LLMProvider`; `AgentLoop` is unaware of which is in use.

---

## 10. Design Decisions — Summary

All design questions raised during planning have been resolved. The table below summarises each decision with cross-references to the relevant section.

New `AgentEvent` cases added in Phase 2:
```scala
enum AgentEvent:
  case IterationBoundary(iteration: Int)
  case ToolCallStart(tool: String, input: String)
  case ToolCallResult(tool: String, output: String, durationMs: Long)
  case PermissionRequired(tool: String, path: Option[String], options: List[String])  // new
  case Final(assistantMessageId: String)
  case Cancelled
  case AgentError(message: String)
```

| # | Question | Impact |
|---|---|---|
| ~~**Q-1**~~ | ~~How should the `cancelFlag: AtomicBoolean` be threaded into `AgentLoop`?~~ | **Decided**: add to `AgentEngine.run()` signature |
| ~~**Q-2**~~ | ~~Is `maxIterations` hardcoded, global setting, or per-session?~~ | **Decided**: global `AppSettings` field, default 20, exposed in Settings UI |
| ~~**Q-3/Q-4/Q-13**~~ | ~~Tool result injection and tool call format~~ | **Decided**: plain `assistant`/`user` turns; inline `run(command="...")` text; `ToolCallParser` extracts and logs all calls |
| ~~**Q-5**~~ | ~~Final answer: absence of `run()` sufficient, or require explicit marker?~~ | **Decided**: explicit `<done>` marker required; soft fallback (accept without marker, log warning) if absent |
| ~~**Q-6**~~ | ~~Token budget for a single tool result body?~~ | **Decided**: fixed 8000 char constant in `Presentation.scala`; overflow → `SessionScratchpad` with path-keyed stable refs + staleness check + LRU eviction (max 20 entries) |
| ~~**Q-7**~~ | ~~`files.search`: grep-based or index-based?~~ | **Decided**: grep-based (`java.nio.file`, no subprocess); Unix-aligned args for both `files.search` (grep-style) and `files.list` (ls-style); index-based search deferred to Phase 5 |
| ~~**Q-8**~~ | ~~`memory.*`: session-scoped only or global cross-session in Phase 2?~~ | **Decided**: session-scoped only; `MemoryStore` trait with `Option[sessionId]` ready for Phase 6 global upgrade |
| ~~**Q-9**~~ | ~~`llm.*` tools: same provider or configurable separate model?~~ | **Decided**: same `LLMProvider`; fresh isolated message list per tool call, no session context shared; `callType="tool_llm"` in token accounting |
| ~~**Q-10**~~ | ~~Permission prompts: SSE-based run suspension or natural language relay + re-submit?~~ | **Decided**: Option A — `PermissionRequired` SSE event; run suspends on `SynchronousQueue`; UI modal with Allow once/session/always/Deny; `POST /permissions`; 60s timeout → graceful `permission_denied` |
| ~~**Q-11**~~ | ~~Context budget: fixed constant, global setting, or model-derived?~~ | **Decided**: global `AppSettings` field `contextBudgetTokens` (default 8000); newest-first history inclusion; system prompt + current user msg + in-flight tool results always included |
| ~~**Q-12**~~ | ~~Is context summarization required for Phase 2 or deferred?~~ | **Decided**: deferred to Phase 5; silent truncation of oldest messages in Phase 2 |
| ~~**Q-13**~~ | ~~Duplicate of Q-3/Q-4~~ | **Decided**: see Q-3 |
| ~~**Q-14**~~ | ~~System prompt: file-based template or programmatically generated?~~ | **Decided**: hybrid — `system_prompt.txt` resource file with `{{TOOL_INDEX}}`, `{{ROOT_PATH}}`, `{{TODAY}}` substitution slots; `TOOL_INDEX` filled from `CommandRegistry` at startup |
| ~~**Q-15**~~ | ~~Tool run persistence: per-call or end-of-run batch?~~ | **Decided**: per-call (covered by Q-3 logging decision) |
| ~~**Q-16**~~ | ~~Mock LLM in tests: pre-recorded or scripted sequences?~~ | **Decided**: hybrid — `ScriptedLLMProvider` for new scenarios; `RecordingLLMProvider` + `ReplayLLMProvider` for regression lock-in once system prompt stabilises |

---

## 11. Implementation Order (Suggested)

Given the dependency graph, the recommended order is:

1. `CommandAst` + `Tokenizer` (no dependencies; unit-testable immediately)
2. `Tool` trait + `PathSandbox` utility
3. `SessionScratchpad` (no tool dependencies; needed by presentation layer)
4. `files.*` tool implementations (execution layer only, no presentation)
5. `CommandRegistry` + `VirtualShell` stub dispatch
6. `Presentation` layer + `AgentResponse` (uses `SessionScratchpad` for overflow routing)
7. `memory.*` tool implementations + `MemoryStore` DB schema
8. `ScopeStore` + path sandbox integration
9. `ContextManager` Phase 2 (token-budget-aware window)
10. `AgentLoop` Phase 2 (full iteration loop)
11. `llm.*` tool implementations (depend on `LLMProvider.complete`; accept `$scratch` refs)
12. System prompt finalization (depends on `CommandRegistry.helpIndex`)
13. Log file output for `TraceLogger` + `GET /log/stream` endpoint + `log-viewer.html` + "Debug log" button
14. Replay test scaffolding (`ScriptedLLMProvider` first) + first 5 golden scenarios

---

## 12. Files Modified / Created in Phase 2

### New implementations (replacing stubs):
- `shell/CommandAst.scala`
- `shell/Tokenizer.scala`
- `shell/CommandRegistry.scala`
- `shell/VirtualShell.scala`
- `shell/Presentation.scala`
- `tools/Tool.scala`
- `tools/files/FilesRead.scala`, `FilesWrite.scala`, `FilesList.scala`, `FilesSearch.scala`, `FilesStat.scala`
- `tools/memory/MemoryGet.scala`, `MemorySet.scala`, `MemoryList.scala`
- `tools/memory/MemoryStore.scala` *(new)*
- `tools/llm/LlmSummarize.scala`, `LlmExtract.scala`, `LlmClassify.scala`
- `permissions/ScopeStore.scala`
- `agent/AgentLoop.scala` *(full replacement of Phase 1 stub)*
- `agent/ContextManager.scala` *(extended)*

### Modified:
- `session/Models.scala` — add `MemoryEntry`, extend `ToolRun` fields
- `BackendServer.scala` — wire `CommandRegistry`, `VirtualShell`, new `AgentLoop` constructor; load `system_prompt.txt`; add `scratchpads: ConcurrentHashMap[sessionId, SessionScratchpad]`; clean up on session delete
- `server/Routes.scala` — add `cancelFlag` to `agentEngine.run()` call; add `permissionQueues: ConcurrentHashMap[runId, SynchronousQueue[GrantDecision]]`; add `POST /permissions` and `GET /log/stream` endpoints; serve `log-viewer.html` static route
- `agent/AgentEngine.scala` — add `cancelFlag: AtomicBoolean` parameter to `run()`; add `PermissionRequired` to `AgentEvent` enum
- `settings/SettingsStore.scala` — add `maxIterations: Int = 20` and `contextBudgetTokens: Int = 8000` to `AppSettings`
- `observability/TraceLogger.scala` — add file output to `AppDirs.dataDir/logs/agentica.log` alongside stdout; expand structured field set
- `ui/index.html` — add "Debug log" button; add permission modal
- `ui/js/main.js` (or `chat.js`) — handle `permission_required` SSE event, show modal, POST decision
- `ui/js/settings.js` — expose `maxIterations` and `contextBudgetTokens` in settings modal

### New resources:
- `backend/src/main/resources/system_prompt.txt` — hybrid template with `{{TOOL_INDEX}}`, `{{ROOT_PATH}}`, `{{TODAY}}` slots
- `ui/log-viewer.html` — dedicated log viewer page (opens via `window.open`)
- `ui/js/log-viewer.js` — connects to `GET /log/stream`, renders/filters log lines
- `scenarios/*.yaml` — first 5–10 golden scenarios

### New utility:
- `shell/PathSandbox.scala` *(shared path resolution + escape check)*
- `shell/ToolCallParser.scala` *(extracts `run()` calls from model output)*
- `shell/Scratchpad.scala` *(session-scoped content cache: `ScratchEntry`, `SessionScratchpad`)*
- `agent/ExecutionContext.scala` *(carries rootPath, traceId, sessionId, scopeStore, scratchpad)*
- `permissions/Models.scala` *(`Grant`, `GrantTTL`, `GrantDecision`)*

### New test scaffolding:
- `tests/.../ScriptedLLMProvider.scala` *(takes `List[String]`, returns one per `stream()` call)*
- `tests/.../RecordingLLMProvider.scala` *(wraps real provider, writes responses to YAML)*
- `tests/.../ReplayLLMProvider.scala` *(reads YAML, replays; mismatch fails)*
- `tests/.../GoldenScenarioRunner.scala` *(temp workspace + agent loop + assertions)*

---

## 13. Tool Ecosystem (Phase 3+)

This section maps the full set of planned tools for Agentica, grouped by category. Tools marked **Phase 3** are implemented using pure-JVM dependencies. Tools marked **Phase 4** require external binaries or cloud APIs.

### 13.0 Reference Use Cases

The following use cases drive tool design and are referenced throughout this section:

| ID | Use Case | Triggering Input | Desired Output | Key Tools Needed |
|---|---|---|---|---|
| **UC1** | **Web to Presentation** | URLs or PDFs containing research/articles | PPTX slide deck summarizing content | `browser.open`, `files.read_pdf`, `llm.summarize`, `files.write_pptx` |
| **UC2** | **Image to Document** | Image file (diagram, whiteboard, screenshot) | DOCX or PDF describing the image content | `vision.describe`, `files.write_docx` |
| **UC3** | **Audio to Presentation** | Audio file (meeting recording, podcast) | PPTX with transcript summary and key points | `audio.transcribe`, `llm.summarize`, `files.write_pptx` |

### 13.1 Browser Tools (`browser.*`)

**Goal:** Full web content extraction and automation using headless browsers.

**Libraries:** `playwright-java` (Microsoft Playwright Java bindings) — requires `chromium`/`firefox` browser binaries via `playwright install`.

| Tool | Args | Output | Phase |
|---|---|---|---|
| `browser.open` | `url` | Page text/markdown | Phase 3 |
| `browser.select` | `selector` (CSS), `attribute` (optional) | Selected element text | Phase 3 |
| `browser.click` | `selector` | Confirmation + new page state | Phase 4 |
| `browser.fill` | `selector`, `value` | Confirmation | Phase 4 |
| `browser.screenshot` | `url` or `selector`, `fullPage` | PNG bytes (stored to scratchpad) | Phase 4 |
| `browser.pdf` | `url` | PDF bytes (stored to scratchpad) | Phase 4 |

**Use cases:**
- **UC1 (web→PPTX):** `browser.open` + `browser.select` to extract content from JS-rendered pages
- **UC2 (image review):** `browser.screenshot` for visual verification of pages

### 13.2 Document Tools (`files.read_*`, `files.write_*`)

**Goal:** Read and write Office documents. Two approaches: **Markdown-first** (recommended for LLM generation) and **Direct API** (fallback).

**Libraries:**
- **Pandoc** (external) — converts Markdown to DOCX/PPTX with template support
- **Flexmark** (JVM) — parses Markdown AST for custom rendering
- **Apache POI** (JVM) — direct DOCX/PPTX construction (fallback)
- **Apache PDFBox** (JVM) — direct PDF generation
- **Playwright** (external, already required for `browser.*`) — HTML → PDF

#### Markdown-First Approach (Recommended)

The agent generates documents in Markdown format first, then converts:

```
Content → Markdown → [Pandoc] → DOCX/PPTX
         ↓
         → [Flexmark → POI] → DOCX/PPTX (fallback)
         ↓
         → [Playwright] → PDF (print-to-PDF)
```

**Benefits:**
- **LLM-friendly** — Markdown is simpler to generate than HTML or API calls
- **Human-verifiable** — User can preview `.md` before conversion
- **Professional output** — Pandoc templates provide consistent branding
- **Graceful degradation** — Pure-JVM fallback if Pandoc unavailable

| Tool | Args | Output | Phase |
|---|---|---|---|
| `files.write_markdown` | `path`, `content` | Saved `.md` file | Phase 3 |
| `files.markdown_to_docx` | `path`, `template` (optional) | DOCX file | Phase 3 |
| `files.markdown_to_pptx` | `path`, `template` (optional) | PPTX file | Phase 3 |
| `files.markdown_to_pdf` | `path` | PDF file | Phase 3 |
| `files.read_pdf` | `path`, `pages` (optional range) | Extracted text | Phase 3 |
| `files.read_docx` | `path` | Extracted text | Phase 3 |

**Markdown slide format for PPTX:**
```markdown
---
title: Q3 2024 Results
---

# Revenue Overview

- $42M (+23% YoY)
- Strong enterprise adoption

---

# Key Drivers

1. Seat expansion: +15%
2. New logos: +8%
```

**Design notes:**
- All document tools respect `PathSandbox` checks (workspace-scoped paths only).
- `files.read_*` outputs plain text for LLM consumption; original file remains in workspace.
- Pandoc is an optional external dependency; graceful fallback to Flexmark + POI if unavailable.
- Templates (`.docx`, `.pptx`) can be stored in workspace for branded output.

**Use cases:**
- **UC1 (web→PPTX):** `browser.open` → `llm.summarize` → `files.write_markdown` → `files.markdown_to_pptx`
- **UC2 (image→DOCX):** `vision.describe` → `files.write_markdown` → `files.markdown_to_docx`
- **UC3 (audio→PPTX):** `audio.transcribe` → `llm.summarize` → `files.write_markdown` → `files.markdown_to_pptx`

### 13.3 Vision Tools (`vision.*`)

**Goal:** Extract structured descriptions from images.

**Implementation:** Uses multimodal LLM via `LLMProvider`. Not a JVM library — depends on the connected LLM supporting `image_url` in messages (GPT-4V, LLaVA, etc.).

| Tool | Args | Output | Phase |
|---|---|---|---|
| `vision.describe` | `path` (image file in workspace) | Structured description | Phase 4 |
| `vision.extract_text` | `path` | OCR text | Phase 4 (uses LLM or Tesseract) |

**Design notes:**
- Images must be in the workspace (PathSandbox check applies).
- Tool reads image bytes, base64-encodes, sends to LLM with prompt: *"Describe this image in detail..."*
- Output is plain text suitable for downstream `files.write_docx` or `memory.set`.

### 13.4 Audio Tools (`audio.*`)

**Goal:** Transcribe audio files to text.

**Implementation:** External dependency — no pure-JVM solution matches Whisper quality.

| Tool | Args | Output | Phase |
|---|---|---|---|
| `audio.transcribe` | `path`, `language` (optional) | Transcribed text | Phase 4 |

**Use case:**
- **UC3 (audio→PPTX):** `audio.transcribe` → `llm.summarize` → `files.write_pptx`

**Options:**
- **Option A (subprocess):** Call `whisper` CLI if installed on host. Graceful degradation if missing.
- **Option B (cloud API):** POST to OpenAI `/v1/audio/transcriptions` or Azure Speech. Requires API key.
- **Option C (bundled binary):** Package `whisper.cpp` per-platform (complex; deferred).

**Recommendation:** Implement Option A first (subprocess with detection), add cloud fallback later.

### 13.5 Web Tools (`web.*`) — Deprecated in favor of `browser.*`

The original `web.fetch` using Jsoup is superseded by `browser.open`. Jsoup cannot render SPAs or execute JavaScript. Browser tools cover all static + dynamic cases.

---

## 14. Out of Scope for Phase 2

Noted explicitly to avoid scope creep:

- **Progress events** for long-running tools (`─ progress: N% · ...` per FTRD §10 envelope spec) — no Phase 2 tool is long-running.
- **`$last`/`$1`/`$2` general-purpose output-capture variables** (FTRD §10) — the `$scratch/<path>` ref system covers the actual use case (large-output handling) more naturally.
- **`commit()` tool** (FTRD §10) — sensitive operations remain inline in `run()` with permission checks.
- **Cross-session memory** — `MemoryStore` trait is ready for it but Phase 6.
- **Context summarization on truncation** — Phase 5.
- **RAG / proactive file injection** — Phase 5; files enter context only via explicit `files.read` in Phase 2.
- **Cloud LLM provider routing** for `llm.*` tools — Phase 3+ once cloud providers exist.
- **Native function-call message role** — Phase 3+ behind a `nativeFunctionCalling: Boolean` per-provider flag.
- **Office document tools** (`files.read_docx`, `files.write_pptx`, etc.) — Phase 3 (see §13.2).
- **Browser automation** (`browser.*`) — Phase 3–5 (see §13.1).
- **Vision tools** (`vision.*`) — Phase 4 (see §13.3).
- **Audio transcription** (`audio.*`) — Phase 4 (see §13.4).
- **Packaging** (`jlink`, `jpackage`) — Phase 5.

---

*End of Phase 2 Technical Design.*
