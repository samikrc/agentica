# AI Desktop Assistant (Claude Cowork-like) — Requirements & Architecture

## 1. Overview

This document defines the functional requirements, technical architecture, and key design decisions for building a cross-platform desktop AI assistant application similar to Claude Cowork.

The application will:

* Run as a desktop app (Windows, macOS, Linux)
* Use local and cloud LLMs
* Operate on user-selected local folders
* Provide chat-based and agent-driven workflows
* Maintain persistent sessions (chat history + context)

---

## 2. Core Design Principles

* **Local-first**: Data remains on user machine unless explicitly sent to cloud APIs
* **Security-first**: All system actions are permission-gated
* **Modular architecture**: UI, orchestration, and execution layers separated
* **LLM-agnostic**: Support multiple providers (local + cloud)
* **Extensible tools framework**

---

## 3. High-Level Architecture

```text
[ UI (Vanilla HTML/CSS/JS, SSE client) ]
          ↓  (HTTP + SSE on 127.0.0.1, bearer-token auth)
[ Tauri Shell (Rust) ]
          ↓  (spawns sidecar, injects bearer token via env/stdin)
[ Scala Backend (Sidecar, JVM) ]
   ├── Session Management (SQLite via ScalaSQL)
   ├── Agent Loop (custom, ~300 LOC — to be developed)
   ├── Virtual Shell Runtime (tokenizer-based DSL)
   ├── Tool Execution Layer (typed, whitelisted)
   ├── File System Access (sandboxed to user-selected roots)
   ├── LLM Provider Abstraction (LlmProvider trait)
   └── Observability (structured logs, trace IDs, token/cost accounting)
          ↓
[ LLM Providers ]
   ├── Local (Ollama — default, llama.cpp — power-user)
   └── Cloud (OpenAI, Anthropic) — later phase
```

---

## 4. Technology Stack

### Frontend

* Vanilla HTML, CSS, and JavaScript (no framework, no build step)
* Streaming via **Server-Sent Events (SSE)** using the browser's native `EventSource` / `fetch` + `ReadableStream`

**Rationale**: The UI surface is chat-centric and modest in scope. A vanilla stack avoids a JS toolchain (bundlers, transpilers, framework upgrades) and keeps the app footprint small. SSE is chosen over WebSockets because the stream is one-directional (server → client tokens), is trivially reconnectable, works over plain HTTP, and is natively supported by the browser without extra libraries.

### Desktop Layer

* Tauri
* Distribution via native OS-specific executables/installers (no portable mode)
* **Primary target for v1: Windows**. macOS and Linux are supported by the stack but are not release targets for the initial milestone.

### Backend (Sidecar)

* Scala 3
* HTTP server — Li Haoyi's **Cask** (with hand-rolled SSE endpoints; Cask does not provide first-class SSE helpers, but chunked responses are straightforward)
* **SQLite** for persistence
* SQLite access via **ScalaSQL** (`com-lihaoyi/scalasql`) — aligns with the Li Haoyi toolchain already in use (Cask), gives typed queries without a heavyweight ORM
* **Concurrency model**: direct-style Scala on **JDK 21+ virtual threads (Loom)**. No `cats-effect` / `ZIO` monadic stack. Each request handler is plain blocking code on a virtual thread; this matches Cask's synchronous programming model and keeps the codebase approachable.

### Agent Engine

* **Custom agent loop written in Scala** (estimated ~300 LOC for the initial plan→act→observe loop). **To be developed** — see §9.
* Google ADK was considered and **dropped for v1**. Rationale: ADK's value-add shrinks when the tool surface is a single `run()` command backed by a virtual shell (§10). ADK would drag in gRPC/Guava/Protobuf and constrain the loop to Google's abstractions for little benefit. A purpose-built loop is small, debuggable, and keeps the project dependency-light.

### LLM Integration

* **Local (default): Ollama** — chosen for its built-in model management, OpenAI-compatible HTTP API, and automatic GPU selection. Reduces first-run friction for end users.
* **Local (power-user): llama.cpp** — direct integration for users who want fine-grained control (custom quantization, non-Ollama models, lower overhead).
* **Cloud**: OpenAI / Anthropic APIs (later phase).
* All providers sit behind a single `LlmProvider` Scala trait so the agent loop and context manager are provider-agnostic.

### Build & Packaging

* **Maven** for the Scala backend. Maven's incremental compilation is adequate for a single-module sidecar, and the team is already comfortable with it; avoiding sbt/Mill keeps the build surface familiar.
* **`jlink` + `jpackage`** for distribution: produce a minimal custom JRE containing only the modules the sidecar needs, then let `jpackage` wrap it into OS-native installers. A shaded fat-jar + bundled JRE is an acceptable alternative during development.
* **GraalVM native-image is explicitly NOT used** in v1. Rationale: the backend depends on Apache POI (heavy reflection) and the JVM ecosystem generally; native-image would require extensive reachability metadata and risks silent runtime failures. `jlink`/`jpackage` gives us a self-contained native installer without those hazards. Native-image may be revisited as a future optimization.

---

## 4.1 Deployment Model (Executable Mode Only)

The application is distributed exclusively as a native executable application using OS-specific packaging.

Supported formats:

* **Windows (primary v1 target)**: `.exe` / `.msi` produced via `jpackage`
* macOS: `.app` bundle / `.dmg` (future)
* Linux: AppImage / `.deb` / `.rpm` (future)

Portable (run-from-folder) mode is explicitly NOT supported.

### Rationale

* Ensures alignment with OS conventions and security models
* Avoids platform inconsistencies (especially macOS bundle restrictions)
* Enables better integration with OS-level features (permissions, updates, sandboxing)

### Code Signing & Notarization

* **Windows**: the `.exe`/`.msi` must be signed with an Authenticode certificate to avoid SmartScreen warnings. The bundled JRE's DLLs inherit the installer's signature when shipped inside the `.msi`.
* **macOS (future)**: the bundled JRE's `.dylib` files must each be signed, and the resulting `.app` must be notarized by Apple. This is non-trivial and should be planned before the macOS release.
* **Linux (future)**: no mandatory signing; optional GPG signing of `.deb`/`.rpm` for repositories.

### Auto-Update (Phase 1)

* Use **Tauri's built-in updater plugin** for delivering signed updates.
* Release artifacts and update manifests are published to a controlled distribution endpoint (e.g., GitHub Releases) and verified via public-key signature before install.
* Rationale: shipping updates is a Phase 1 requirement because the agent loop, tool surface, and prompts will evolve rapidly; relying on users to manually reinstall is not viable.

### Data Storage Model

Application data must NOT be stored relative to the executable.

Instead, all persistent data (SQLite, sessions, cache, indexing artifacts) must reside in OS-standard application data directories:

* Windows: AppData (Roaming/Local)
* macOS: ~/Library/Application Support
* Linux: ~/.local/share

All components must resolve paths via a configurable BASE_DIR abstraction.

---

## 5. Functional Requirements

### 5.1 Chat & Interaction

* Chat interface with streaming responses
* Multi-session support
* Edit and regenerate responses
* Session auto-titling

### 5.2 Session Management

* Create, load, update, delete sessions
* Maintain message history
* Store tool execution logs
* Attach files to sessions

### 5.3 File System Integration

* User-selected folder access only
* Read/write/search files
* File indexing and chunking

### 5.4 LLM Integration

* Switch between local and cloud models
* Streaming responses
* Prompt management

### 5.5 Agent Capabilities

* Plan → act → observe loop
* Tool calling (structured via command interface)
* Multi-step task execution

### 5.6 Context Management

* Sliding window context
* Summarization of older messages
* Retrieval (RAG) from local data

---

## 6. Session Data Model

### Session
```json
{
  "id": "uuid",
  "title": "Analyze QFI data",
  "created_at": "...",
  "updated_at": "...",
  "model": "gpt-4 / llama3",
  "root_path": "/user/project-folder"
}
```

### Message
```json
{
  "id": "uuid",
  "session_id": "...",
  "role": "user | assistant | system",
  "content": "...",
  "timestamp": "...",
  "attachments": []
}
```

### Tool Execution
```json
{
  "id": "uuid",
  "session_id": "...",
  "tool": "read_file",
  "input": {},
  "output": {},
  "status": "success"
}
```

---

## 7. Backend Responsibilities (Scala)

* Session persistence (SQLite via ScalaSQL)
* Context management (sliding window, summarization, RAG) — design TBD, see §13
* Model routing (local vs cloud) via `LlmProvider` trait
* **Agent orchestration (custom Scala loop — to be developed; see §9)**
* Virtual shell runtime (command parsing + execution)
* Tool execution with validation and permission gating
* File system access (sandboxed, no direct shell)
* LLM API integration (connectors + streaming)
* Resolve storage paths via OS-specific base directory (no relative executable storage)
* Observability: structured logs + trace IDs spanning UI → sidecar → LLM → tool
* Token and cost accounting per session

---

## 8. Sidecar Pattern

- Scala backend runs as a separate executable (the JVM runtime is bundled via `jlink`/`jpackage`).
- Tauri launches it at app startup and terminates it on app shutdown.
- Communication via **HTTP + SSE on `127.0.0.1`**. The listening port is chosen from the ephemeral range at launch and passed back to the Tauri shell via the sidecar's stdout handshake.

### Local Transport Security

A bare localhost port is still reachable by any local process/user. To mitigate:

- Sidecar **binds only to `127.0.0.1`** (never `0.0.0.0`).
- On each launch, Tauri generates a **cryptographically random per-launch bearer token** and passes it to the sidecar via environment variable (or stdin on startup).
- Every HTTP/SSE request from the UI must carry this token in an `Authorization: Bearer <token>` header. Requests without the token are rejected with `401`.
- The token is never written to disk; it exists only in the parent (Tauri) and child (sidecar) process memory.

### Benefits
- Language flexibility (Rust for desktop shell, Scala for heavy backend logic)
- Clean separation of concerns
- Easier debugging (the sidecar can be run standalone against a browser or curl for backend development)

---

## 9. Agent Loop (Custom, To Be Developed)

The system will use a **custom Scala agent loop** rather than a third-party framework.

### Rationale

- The exposed tool surface is deliberately minimal (a single `run(command=...)` against the virtual shell — see §10). A general-purpose framework adds abstraction layers with little payoff at this scope.
- A hand-written loop of ~300 LOC is small enough to read, debug, and evolve as prompting/tooling research progresses.
- Avoids pulling in heavy transitive dependencies (gRPC, Guava, Protobuf) that Google ADK would introduce.
- Keeps the project aligned with the local-first, dependency-light design ethos.

> **Status: to be developed.** This is a net-new component, not a wrapper around an existing library.

### Responsibilities of the Agent Loop

- Orchestrate the plan → act → observe cycle
- Assemble the LLM prompt (system prompt + context window + latest user turn + tool-result turns)
- Invoke the LLM via the `LlmProvider` trait (streaming)
- Parse tool-call output from the model
- Dispatch tool calls through the virtual shell runtime (§10)
- Feed tool outputs back into the next iteration
- Decide termination (final answer, max-iterations, cancellation, error)
- Emit streaming tokens and structured events (tool-call-start, tool-call-result, iteration-boundary) to the UI over SSE

### Responsibilities Outside the Agent Loop

- Context management (sliding window, summarization, RAG) — owned by a separate `ContextManager`
- Model routing (local vs cloud, fallback strategies) — owned by `LlmProvider` implementations
- Security enforcement for tool execution — owned by the tool engine (§10, §12)
- Persistence — owned by the session store

### Integration Pattern

```text
UI → Scala backend → ContextManager.assemble()
   → AgentLoop.run(prompt, tools)
      → LlmProvider.stream(prompt)  [tokens streamed to UI via SSE]
      → Loop parses model output → tool calls
         → VirtualShell.execute(command) → typed result
      → Loop re-prompts with tool result until terminal condition
   → Persist final state + stream completion event to UI
```

### Pluggability

Although ADK is dropped for v1, the loop should live behind an `AgentEngine` trait so an alternative implementation (ADK, LangChain4j, etc.) can be swapped in later without touching the rest of the backend.

---

## 10. Virtual Shell Runtime (Core Design)

Inspired by emerging agent design patterns, the system will implement a **virtual shell abstraction** where the agent interacts using command-like text, but execution is fully controlled and safe.

### Design Goals
- Align with LLM training (CLI-style interaction)  
- Avoid unsafe shell execution  
- Enable composability and discoverability  

### Key Concept

> The agent *feels* like it is executing shell commands, but actually invokes safe, typed Scala tools.

### Architecture (Two-Layer Model)

The runtime is split into two cleanly separated layers. This is a deliberate borrowing from the Manus / agent-clip design: keeping raw execution semantics clean while shaping the agent-facing output for cognition and token efficiency.

```text
[ Agent Loop (custom Scala, §9) ]
     ↓  run(command="...")
[ Command Tokenizer ]
     ↓  Single-Command AST
[ Execution Layer ] — typed Scala tool, deterministic, returns structured value
     ↓  raw structured result
[ Presentation Layer ] — truncates, summarizes, adds metadata + recovery hints
     ↓  AgentResponse envelope (text)
[ Returned to Agent ]
```

**Execution Layer** properties:
- Pure Scala, typed inputs and outputs
- No formatting concerns
- Independently unit-testable
- Identical semantics whether invoked from agent, integration test, or REPL

**Presentation Layer** properties:
- Converts the typed result into the AgentResponse envelope (see below)
- Enforces token budgets (truncates large bodies, replaces binary with placeholders like `<binary 12KB image/png>`)
- Adds metadata, recovery hints, and "try this next" suggestions on errors
- Owns *all* string formatting the agent sees

Rationale: agent-UX (terse, parseable, recoverable) and tool semantics (correct, typed) evolve on different schedules. Splitting them prevents formatting concerns from leaking into core logic.

*(Pipelines and a multi-stage Execution Planner were considered but dropped for v1 — see "Command Language" below.)*

### Command Interface

Single exposed tool in v1:

```text
run(command="files.read path=foo.txt")
```

A second tool, `commit(...)`, is **deferred** (see "Future: Typed Commits at Transactional Edges" below).

### Command Language (DSL)

A constrained CLI-like syntax (not full bash). Commands are namespaced by **action family** (see next subsection):

```text
files.read path=foo.txt
files.search query="revenue" path=reports/
llm.summarize text="..."
files.write path=out.txt content="..."
```

**Pipelines are NOT supported in v1.** The agent issues successive `run()` calls and the loop carries intermediate values across iterations as part of the conversation.

Rationale: piping between tools forces a common intermediate representation (text vs structured values), which causes lossy conversions (e.g., Apache POI cell objects stringified into a `summarize` stage). Requiring explicit steps keeps each tool's I/O type clean and defers the pipeline/typed-value design to a later revision.

**Output-capture variables (e.g., `$last`, `$1`, `$2`) are deferred.** A future revision may add executor-side substitution so the agent can reference prior results without re-emitting their text. Not in v1.

### Action Families

Tools are organized into a small number of **action families** rather than a flat catalog. This is the single highest-leverage decision for keeping the system prompt small and the agent's choice surface manageable as the tool set grows.

| Family | v1 verbs | Later |
|---|---|---|
| `files` | `read`, `write`, `search`, `list`, `stat` | `move`, `delete` (typed-commit) |
| `memory` | `get`, `set`, `list` | `forget`, vector-search |
| `doc` | `word.read`, `word.append`, `ppt.read`, `ppt.add_slide` (Phase 3) | `excel.*` (deferred, see §11.1) |
| `llm` | `summarize`, `extract`, `classify` | |
| `web` | (deferred) | `fetch`, `search` |

Design rules:
- 5–8 well-named families is the target ceiling. Resist creating a new family for one-off operations.
- Verb naming is consistent across families (`read`, `write`, `list`, `search` mean the same shape of thing everywhere).
- Every command schema lives in a single Scala registry; the dispatch table, help text, and JSON schemas (for replay tests) are generated from it. No drift.

Rationale: this directly addresses the "40 overlapping tools" sprawl pattern observed in production agent systems and quoted in the references (§20). Compression into reusable verbs is the core value of the command-layer architecture.

### Command Parsing

- Convert text → single-command AST (no pipelines).
- Implemented using a **hand-written tokenizer with quoted-string handling** (no parser combinators).
- Rationale: the grammar is trivially regular (command name + `key=value` args with quoted strings). A tokenizer is smaller, faster, and easier to evolve than a parser-combinator-based implementation.

### Tool Execution Model

- Each command maps to a typed tool in the execution layer.
- No shell execution or subprocess calls (except sealed external tools, e.g., `soffice` — see §11.4).
- One `run()` invocation = one tool execution. Multi-step workflows are achieved by the agent loop issuing successive `run()` calls (e.g., `files.read` → `llm.summarize` → `files.write` as three separate iterations), not by chaining inside a single command.
- Every tool resolves `path=` arguments against the session's `root_path` (§6) and rejects any path that escapes it. This is a stated invariant of every file-touching tool, not per-tool boilerplate.

Example (three successive agent iterations):

```text
run(command="files.read path=foo.txt")
run(command="llm.summarize text=\"...contents from previous step...\"")
run(command="files.write path=out.txt content=\"...summary...\"")
```

### AgentResponse Envelope

Every command result — success or error — is rendered by the presentation layer in a fixed, parseable shape. This gives the agent a stable target to reason against and keeps token cost predictable.

**Success:**

```text
$ files.read path=foo.txt
ok
─ size: 1.2 KB · lines: 47 · truncated: false
─────
<body>
```

**Error:**

```text
$ files.read path=foo.txt
error: not_found
─ hint: did you mean data/foo.txt? (3 candidates)
─ try: files.search query=foo
```

**Long-running progress (streamed over SSE between start and final):**

```text
─ progress: 23% · indexed 230/1000 files
```

Properties:
- Line 1: command echo (always).
- Line 2: status — `ok` or `error: <code>` from a closed code set.
- Metadata lines prefixed with `─`.
- Body separated by `─────` when present.
- Errors always carry at least one `hint` and at least one `try` suggestion.
- Binary or oversized content is replaced inline with placeholders, never inlined as bytes.

Rationale: a consistent envelope is the agent's UI. It enables deterministic recovery prompts, reliable parsing in replay tests, and bounded token cost.

### Security Properties

- Strict tool whitelist  
- No arbitrary command execution  
- Path sandboxing enforced  
- No escape hatch to system shell  

### CLI Illusion Layer

To improve agent effectiveness without bloating the system prompt:

- **On-demand `help`, not static `--help` everywhere.** The system prompt contains only a *command index* (one line per family) plus the AgentResponse envelope spec. The agent calls `help <command>` to retrieve the full argument schema and examples for any specific command. `help` output is itself in the AgentResponse envelope — uniformly parseable.
- Rich error messages with `hint` + `try` suggestions (see envelope above).
- Status codes from a closed set (`ok`, `not_found`, `permission_denied`, `invalid_args`, `cancelled`, `internal_error`, ...).

Rationale: static help text for every command would balloon the system prompt and is rarely needed for the common operations. Lazy retrieval keeps the static prompt small while preserving discoverability.

### Future: Typed Commits at Transactional Edges (Deferred)

The production pattern recommended in the references (§20) is a **hybrid**: keep the command-layer for reasoning and exploration, but require typed function-calls for sensitive commits (destructive writes, paid cloud LLM calls, sealed external tools, future payments / network writes).

The planned mechanism is a second tool alongside `run()`:

```text
commit(action="files.write", path="...", content="...", reason="...")
```

`commit()` would differ from `run()` in three ways: mandatory permission-scope check (§12.6), full `reason` text logged for audit, and a strictly typed (not presentation-fuzzed) result. **Deferred from v1**; v1 enforces the same gates inline within `run()` for sensitive commands. Reintroduce `commit()` when the cloud-LLM and broader-mutation surfaces arrive.

### Key Insight

> A deterministic execution system presented as a CLI-like interface maximizes both safety and LLM effectiveness.

## 11. Office Document Processing (Word, PowerPoint)

The system will support reading, writing, and updating Microsoft Office documents through **safe, native tools implemented in Scala/JVM**, avoiding arbitrary Python or shell execution.

### Design Principles

- No direct shell or arbitrary script execution
- All operations exposed as **typed tools**
- Prefer JVM-native libraries for determinism and safety
- External binaries (if required) must be wrapped as **sealed tools**

### Apache POI Caveats (applies to all Office tools below)

- POI uses significant memory for large `.xlsx` / `.docx` files. Reads of large workbooks should use the **XSSF event (SAX) API**; writes of large workbooks should use **SXSSF** streaming.
- POI's `.doc`/`.xls` (binary, pre-2007) support is lossy for round-trip edits. v1 targets only the Office Open XML formats (`.docx`, `.pptx`, `.xlsx`).
- Chart and embedded-object support is partial; complex documents may lose fidelity on write.
- POI is heavily reflective — this is another reason `jlink`/`jpackage` is used instead of GraalVM native-image (§4).

---

### 11.1 Excel Processing — DEFERRED

**Excel support is explicitly dropped from v1.**

Rationale: Excel is the richest of the three formats (formulas, multiple data types per cell, ranges, formatting, pivot tables). Exposing it safely to an agent requires careful design of the tool surface — in particular, avoiding mini-languages embedded in tool arguments (e.g., `operation="add column total = a + b"`) that would re-introduce the very eval-on-LLM-output risk the architecture is built to prevent. Excel tooling will be designed properly in a later revision.

---

### 11.2 Word Processing

**Library**: Apache POI (XWPF)

Capabilities:
- Read `.docx`  
- Append paragraphs  
- Replace text  
- Basic formatting  

#### Example Tool Commands

```text
word_read path=doc.docx
word_append_paragraph path=doc.docx text="Summary..."
word_replace_text path=doc.docx find="foo" replace="bar"
```

---

### 11.3 PowerPoint Processing

**Library**: Apache POI (XSLF)

Capabilities:
- Read slides  
- Extract text  
- Create slides  
- Basic layout manipulation  

#### Example Tool Commands

```text
ppt_read path=deck.pptx
ppt_add_slide path=deck.pptx title="Summary" content="..."
```

---

### 11.4 Slide/Image Conversion

Some operations (e.g., PPT → images) require external tools.

#### Approach

- Use LibreOffice (`soffice`) in headless mode
- Wrap it as a **sealed internal tool**

#### Operational Constraints for `soffice`

- `soffice` is **not thread-safe** and a single user-profile directory cannot be shared across concurrent invocations. The tool wrapper MUST either serialize invocations via a mutex or pass a unique `-env:UserInstallation=file:///<tmp>/<uuid>` per call.
- Process timeouts must be enforced; `soffice` can hang on malformed inputs.
- stdout/stderr must be captured and surfaced through a structured error payload (error taxonomy to be designed later) rather than discarded.

#### Example Tool Command

```text
ppt_to_images path=deck.pptx
```

#### Constraints

- No arbitrary command execution  
- Fixed command templates only  
- Strict path validation  
- Sanitized outputs  

---

### 11.5 Tool Design Pattern

All Office operations follow the same abstraction:

```scala
trait Tool[I, O] {
  def validate(input: I): ValidatedInput
  def execute(input: ValidatedInput): O
  def render(output: O): String
}
```

Example:

```text
ExcelWrite(path, sheet, row, col, value)
```

---

### 11.6 Intent-Level Abstractions (Future)

Higher-level intent commands (e.g., "add a derived column") are **deferred**. Any such command must be expressed as a properly parsed + validated sub-DSL, not as a free-form string argument evaluated at runtime — otherwise it re-introduces the eval-on-LLM-output hazard the architecture exists to prevent.

---

### 11.7 Performance Considerations

- Large file handling (streaming / chunking)  
- Partial reads (range-based)  
- Caching frequently accessed data  
- Limits on rows/columns processed per request  

---

### 11.8 Security Model

- No Python execution  
- No arbitrary shell access  
- All operations mapped to whitelisted tools  
- Path sandboxing enforced  
- External binaries wrapped with strict validation  

---

## 12. Security & Sandboxing

### 12.1 UI-Level Permissions
- Explicit user approval for:
  - Folder access  
  - File modifications  
  - Tool execution  

### 12.2 Tauri Restrictions
- File system scope limitation  
- Disable shell execution by default  

### 12.3 Backend Sandbox
- Strict tool abstraction (no arbitrary commands)
- Path validation (prevent traversal)
- Tool permission checks
- Enforce separation between application data directory and user-selected workspace directories

### 12.4 OS-Level Isolation (Future)
- Linux: bubblewrap / namespaces  
- macOS: sandboxing / restricted execution  
- Windows: AppContainer / restricted tokens  

### 12.5 LLM Guardrails
- Treat file content as untrusted
- Validate all tool actions
- Never execute raw LLM output

### 12.6 Tool Permission Model

- Permissions are granted as **scopes**: `(tool-set, path-prefix, TTL)` tuples persisted in SQLite and scoped to a session or globally.
- The UI prompts the user on first use of a sensitive tool/path and offers: *deny*, *allow once*, *allow for this session*, *allow always for this path*.
- Every tool invocation checks the scope store before executing; denied calls return a structured permission-denied error to the agent so it can adapt.
- Rationale: per-call prompting is UX death for agent apps; unconstrained "allow all" is unsafe. Scoped grants are the pragmatic middle ground.

### 12.7 Secrets Storage

- API keys for cloud providers (OpenAI, Anthropic) and any other secrets MUST be stored in the **OS-native keychain**:
  - Windows: Credential Manager
  - macOS: Keychain
  - Linux: libsecret / Secret Service API
- Access is brokered through a Tauri plugin; the Scala sidecar requests secrets by name over the local HTTP channel and never persists them to SQLite or disk.
- **Note**: v1 targets local LLM usage only (Ollama / llama.cpp), so secrets storage is implemented but lightly exercised in v1. Cloud providers arrive in a later phase.

---

## 13. Context Management Strategy

- Sliding window (recent messages)  
- Summarization for older context  
- Retrieval-based augmentation (RAG)  

### Key Responsibilities
- Prevent context overflow  
- Maintain relevance of prompts  
- Optimize token usage  

---

## 14. Tool Execution Model

### Design
- Strongly typed tool interface  
- Whitelisted tools only  
- No arbitrary execution  

### Tool Surface

Tools are organized into **action families** rather than a flat catalog. See §10 → "Action Families" for the v1 list and naming rules.

All tool implementations follow the two-layer model from §10 (clean execution layer + presentation layer producing the AgentResponse envelope). Help text, dispatch table, and replay-test schemas are generated from a single command registry to avoid drift.

*(Excel tools deferred — see §11.1.)*

---

## 15. Streaming Architecture

1. UI sends request (`POST /sessions/{id}/messages`) and opens an SSE subscription (`GET /sessions/{id}/stream`) carrying the bearer token.
2. Backend assembles context.
3. Custom agent loop is invoked (§9).
4. LLM response is streamed token-by-token from `LlmProvider`.
5. Tokens and structured events (tool-call-start, tool-call-result, iteration-boundary, final) are forwarded to the UI as SSE events in real time.
6. Final response is persisted to the session store after the stream closes.

### Cancellation Protocol

- UI cancels via `DELETE /runs/{runId}` on the backend.
- The backend aborts the in-flight LLM HTTP call, raises a cooperative cancel flag observed by the tool engine, and emits a `cancelled` SSE event.
- Any partial output is persisted with `status: cancelled`, preserving replay fidelity.

### Notes
- Support partial rendering in UI
- Ensure final consistency before persistence (final DB write happens after the stream closes, not per-token)

---

## 15a. Observability

- **Structured logs** (JSON lines) with a shared `traceId` propagated across UI → sidecar → LLM calls → tool invocations, enabling end-to-end reconstruction of a single user turn.
- **Debug pane in the UI** showing, for the current session: each iteration of the agent loop, every tool call (input + output + duration), and LLM latency/token counts. This is the single highest-leverage developer/user feature for an agent app.
- Log output paths follow the OS data-directory conventions (§4.1) and rotate on size.

## 15b. Token & Cost Accounting

- Every LLM call records: provider, model, prompt tokens, completion tokens, latency, and (for cloud providers) computed cost using a maintained price table.
- Aggregates are exposed per session and globally, surfaced in the UI.
- Rationale: cost/token visibility is essential once cloud providers arrive; instrumenting it from v1 avoids retrofitting.

## 15c. Testing Strategy

- **Unit tests**: execution-layer tool implementations, presentation-layer envelope rendering, DSL tokenizer, context manager, `LlmProvider` adapters (against recorded fixtures). Execution and presentation layers are tested independently.
- **Agent-loop replay tests**: record real LLM interactions once, replay deterministically against fixtures. Ensures prompt/tool changes can be reviewed via diffs on expected traces.
- **Integration tests**: spin up the sidecar, hit it over HTTP+SSE with a test bearer token, run end-to-end scenarios against a mock `LlmProvider`.
- **UI smoke tests**: basic DOM-level checks that SSE events render correctly; full E2E (Tauri + UI + sidecar) deferred.

### Golden Scenarios Catalog

A checked-in directory of `scenarios/*.yaml` files, each describing: starting workspace state, user prompt, success criteria. Run on every prompt or tool change against recorded LLM traces. Per-scenario metrics persisted in SQLite over time:

- Task completion (boolean against success criteria)
- Tool calls per successful run
- Retries / error-recovery count
- Total tokens (prompt + completion)
- Wall-clock latency

Rationale: the references (§20) repeatedly note that long-horizon completion — not single tool-call validity — is the metric that matters for agent systems. A persisted golden-scenario suite is the only way to know whether prompt/tool changes are net-positive.

---

## 16. Future Enhancements

- Multi-agent workflows  
- Cross-session memory  
- Cloud sync  
- Plugin/tool ecosystem  
- Advanced permission policies  
- Voice input/output  
- Real-time collaboration  

---

## 17. Key Design Decisions Summary

| Area | Decision |
|------|--------|
| UI | Vanilla HTML/CSS/JS, SSE transport |
| Desktop Framework | Tauri (primary v1 target: Windows) |
| Backend | Scala 3 sidecar, Cask HTTP server |
| Concurrency | Direct-style + JDK 21 virtual threads (Loom) |
| Communication | HTTP + SSE on `127.0.0.1`, per-launch bearer token |
| Agent Engine | **Custom Scala loop (~300 LOC, to be developed)** — ADK dropped |
| Execution Model | Virtual shell, two-layer (execution + presentation), action families, AgentResponse envelope; no pipelines in v1; `commit()` deferred |
| DSL Parser | Hand-written tokenizer |
| Storage | SQLite via ScalaSQL; manual schema management for now |
| LLM Support | Local: Ollama (default) + llama.cpp (power-user); Cloud: later |
| Office Handling | Apache POI (Word, PowerPoint); **Excel deferred** |
| Security | Multi-layer sandboxing + scoped permissions + OS keychain |
| Build | Maven |
| Packaging | `jlink` + `jpackage` (not GraalVM native-image) |
| Updates | Tauri updater plugin (Phase 1) |
| Observability | Structured logs, trace IDs, token/cost accounting, debug pane |

---

## 18. Key Insight

> Sandbox the *effects* of the LLM, not the LLM itself.  

> Deterministic execution + probabilistic reasoning = safe and powerful agents  

---

## 19. Implementation Phases

### Phase 1 (MVP)
- Basic chat UI (vanilla HTML/CSS/JS, SSE)
- Tauri shell + Scala sidecar with bearer-token auth
- SQLite session storage via ScalaSQL
- Local LLM integration (Ollama default)
- `jlink`/`jpackage` Windows installer
- Tauri auto-updater wired up
- Structured logging + token/cost accounting scaffolding

### Phase 2
- Custom agent loop (§9) — the ~300 LOC plan→act→observe implementation
- Context management (sliding window; summarization TBD)
- Virtual shell runtime with tokenizer-based DSL, two-layer execution/presentation model, AgentResponse envelope
- Action families: `files`, `memory`, `llm` (read/search/list + summarize/extract/classify)
- On-demand `help` command + central command registry (drives dispatch, help, replay schemas)
- Scoped permission model + UI prompts
- Debug pane in UI (tool calls, iterations, latencies)
- Golden scenarios catalog scaffolded with first 5–10 scenarios

### Phase 3
- Office document tools (Word, PowerPoint via POI; LibreOffice sealed wrapper)
- llama.cpp provider as power-user option
- Cloud LLM providers (OpenAI, Anthropic) + OS-keychain secrets
- macOS packaging + notarization

### Phase 4
- RAG (file indexing + retrieval)
- Excel tool surface (properly designed — see §11.1)
- Advanced workflows
- Streaming and cancellation refinements

### Phase 5
- OS-level sandboxing
- Plugin ecosystem
- Multi-agent orchestration
- Linux packaging

---

## 20. References & Inspiration

- Reddit discussion on agent architecture and command-based interfaces:  
  https://www.reddit.com/r/LocalLLaMA/comments/1rrisqn/i_was_backend_lead_at_manus_after_building_agents/

- Command-layer vs function-calling discussion:  
  https://cloudai.pt/the-post-function-calling-ai-stack-why-more-agent-builders-are-turning-to-command-layers/

- Unix philosophy (conceptual inspiration):  
  https://en.wikipedia.org/wiki/Unix_philosophy  

---

**End of Document**