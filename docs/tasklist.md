# Agentica Task List

This task list tracks implementation work by phase. It reflects the current browser-first architecture: the Scala backend serves both the REST/SSE API and the static `ui/` files, while a JavaFX WebView thin launcher is planned as the packaged-mode path.

---

## Phase 1 — Browser MVP

Goal: provide a working local app that runs as a JVM backend plus browser UI.

### Completed

- [x] Serve `ui/` static files from the Scala backend.
- [x] Add browser entry URL: `http://localhost:<port>/?token=<token>`.
- [x] Remove runtime dependency on Tauri from the primary UI path.
- [x] Initialize frontend API client from `window.location.origin`.
- [x] Read bearer token from the `?token=` query parameter.
- [x] Protect API/SSE routes with bearer-token auth.
- [x] Support SQLite-backed sessions and messages.
- [x] Store SQLite DB under OS-standard app data directories.
- [x] Implement chat UI using vanilla HTML/CSS/JS.
- [x] Implement streaming responses over SSE/fetch streaming.
- [x] Add Phase 1 `AgentLoop` stub: single streaming LLM call, persistence, token accounting, final event.
- [x] Support LM Studio / OpenAI-compatible local LLM endpoint.
- [x] Keep Ollama provider available.
- [x] Add new-session modal with root-path text input.
- [x] Add browser folder picker fallback that can populate the selected folder name.
- [x] Add `launch.sh` for Linux/macOS browser mode.
- [x] Add `launch.bat` for Windows browser mode.
- [x] Configure Maven shade plugin for a runnable fat jar.
- [x] Update `README.md` for browser-first operation.
- [x] Update `docs/FTRD.md` for browser-first architecture.

### Remaining / Cleanup

- [x] Remove or update stale Tauri comments in source code.
- [ ] Make backend bind host configurable via an environment variable such as `AGENTICA_HOST`.
- [ ] Prefer `127.0.0.1` for packaged mode and allow `0.0.0.0` for WSL/browser development.
- [ ] Generalize static asset serving beyond `/css/*` and `/js/*` if additional asset folders are added.
- [ ] Decide whether launch scripts should auto-open the browser or only print the URL.
- [ ] Add a basic smoke-test checklist for browser mode.

---

## Phase 1.5 — JavaFX WebView Thin Launcher

Goal: keep browser mode as the main development loop while adding a desktop-like packaged mode that reuses the same web UI.

- [x] Add JavaFX dependencies behind a Maven profile or configurable properties.
- [x] Add `agentica.DesktopLauncher` as a minimal JavaFX application.
- [x] First version should only open a `WebView` and load an already-running backend URL.
- [x] Validate rendering of current HTML/CSS in JavaFX WebView.
- [x] Validate frontend JavaScript execution.
- [x] Validate session loading and selection.
- [x] Validate new-session modal behavior.
- [x] Validate chat submit flow.
- [x] Validate streaming response rendering over SSE/fetch streaming.
- [x] Validate scrolling and textarea behavior.
- [x] Document any WebView incompatibilities before adding packaging complexity.

---

## Phase 2 — Full Agent Loop and Tool Runtime

Goal: replace the Phase 1 single-call loop with a safe plan→act→observe agent loop backed by the virtual shell and scoped tools.

> **Build order** (dependency-driven — see `agent_loop_runtime.md` §11): Virtual Shell foundations → `files.*` → `memory.*` → Permissions → Context Management → `llm.*` → Agent Loop → System Prompt → Debugging & Tests. The sections below are ordered accordingly.

### Step 1 — Virtual Shell Foundations

*`CommandAst`, `Tokenizer`, `Tool` trait, `PathSandbox`, `SessionScratchpad`, `CommandRegistry`, `VirtualShell`, `Presentation`*

- [x] Implement `CommandAst`: `case class Command(family, verb, args: Map[String, String])`.
- [x] Implement `Tokenizer`: hand-written `family.verb key=val` parser with quoted-string and escaped-quote support; return `Right(Command)` or `Left(ParseError)`.
- [x] Implement `Tool[I,O]` trait: `validate / execute / render` pipeline; independently testable per stage.
- [x] Implement `PathSandbox` utility: resolve `path=` args against `session.rootPath`, reject if result escapes sandbox; used by all file-touching tools.
- [x] Implement `SessionScratchpad`: session-scoped in-memory content cache, path-keyed, staleness check (`lastModifiedTime` comparison), LRU eviction (max 20 entries); `store()`, `get()`, `isStale()`.
- [x] Hold one `SessionScratchpad` per active session in `BackendServer` (`ConcurrentHashMap[sessionId, SessionScratchpad]`); remove on session delete.
- [x] Add `scratchpad: SessionScratchpad` reference to `ExecutionContext`; also carries `rootPath`, `traceId`, `sessionId`, `scopeStore`.
- [x] Implement `CommandRegistry`: `dispatch()`, `helpIndex()`, `helpFor()`, `allSchemas()`; register all tools here at startup.
- [x] Add `help` command (handled directly in `CommandRegistry`, not a `Tool[I,O]`): `help` / `help <family>` / `help <family.verb>`; output in `AgentResponse` envelope.
- [x] Implement `VirtualShell`: `execute(rawCommand, ctx)` → `Tokenizer` → `CommandRegistry.dispatch` → `Presentation.render`.
- [x] Implement `Presentation` layer: `BODY_BUDGET_CHARS = 8000` constant; route oversized bodies to `SessionScratchpad`; emit `try` suggestions with scratchpad ref; binary content → `<binary N KB mime/type>` placeholder.
- [x] Add substitution pass in `VirtualShell` (before dispatch): resolve `$scratch/<path>` refs in any arg value to full `String` content from `SessionScratchpad`.
- [x] Unit tests: tokenizer (all valid forms, all error cases, embedded newlines, multi-word quoted values), `PathSandbox` escape detection.

### Step 2 — `files.*` Tool Implementations

- [x] `files.read`: optional `lines=start-end` range arg; route to scratchpad if body > 8000 chars; check staleness before re-reading.
- [x] `files.write`: permission scope check (see Step 3); confirm bytes written.
- [x] `files.list`: ls-style; args: `path`, `recursive` (default false), `all` (dotfiles, default false), `depth` (default 3), `pattern` glob; indented tree output with size and date.
- [x] `files.search`: grep-style; args: `query`, `path`, `recursive` (default true), `ignore_case` (default false), `lines_context` (default 2), `max_matches` (default 50), `include` glob, `regex` flag; skip binary files; `>` prefix on matching line.
- [x] `files.stat`: file size, modified time, type.
- [x] Ensure all `files.*` tools run `PathSandbox` check before any execution.
- [x] Ensure tools use typed validation and structured presentation envelopes; no arbitrary shell execution.

### Step 3 — `memory.*` Tools and Permissions

*`MemoryStore`, `memory.*`, `ScopeStore`, permission flow, UI modal*

- [x] Implement `MemoryStore` trait with `sessionId: Option[String]`; back with SQLite `memory_entries(session_id, key, value, updated_at)`; `session_id = NULL` reserved for Phase 6 global scope.
- [x] Implement `memory.set`, `memory.get`, `memory.list` using session-scoped `MemoryStore`.
- [x] Add `MemoryEntry` to `session/Models.scala`; create DB migration.
- [x] Implement `ScopeStore`: SQLite-backed `permission_grants` table; `Grant`, `GrantTTL`, `GrantDecision` types in `permissions/Models.scala`.
- [x] Add `AgentEvent.PermissionRequired(tool, path, options)` to `AgentEvent` enum in `AgentEngine.scala`.
- [x] Emit `PermissionRequired` SSE event from `VirtualShell` when `files.write` has no grant; handle in `Routes.scala` SSE serialiser.
- [x] Add `permissionQueues: ConcurrentHashMap[runId, SynchronousQueue[GrantDecision]]` to `Routes.scala`.
- [x] Add `POST /permissions` endpoint: look up `runId`, offer `GrantDecision` to the queue.
- [x] Block the agent virtual thread on `SynchronousQueue.poll(60, SECONDS)`; treat timeout as `Denied`.
- [x] On `Granted`: store grant in `ScopeStore` with chosen `GrantTTL`; continue execution.
- [x] On `Denied` or timeout: return `AgentResponse(error: permission_denied)`; log event.
- [x] UI: handle `permission_required` SSE event; show modal with **Allow once / Allow for session / Allow always / Deny** options; POST decision to `/permissions`.
- [x] Add path traversal and workspace-boundary tests.

### Step 4 — Context Management

- [x] Add `contextBudgetTokens: Int = 8000` to `AppSettings`; expose in Settings dialog.
- [x] Implement token estimation in `ContextManager`: `text.length / 4` per message.
- [x] Implement budget-aware history window: include messages newest-first until budget exhausted; system prompt, current user message, and in-flight tool results always included regardless of budget.
- [x] Log truncation events at `TraceLogger.debug` (`messagesDropped`, `budgetTokens`) when messages are dropped.
- [x] Workspace files enter context only via explicit `files.read` tool calls (no automatic injection in Phase 2).
- [x] Summarization of dropped messages: **deferred to Phase 5**.
- [x] Add tests for context trimming behaviour (messages dropped correctly, priority order respected).

### Step 5 — `llm.*` Tool Implementations

*Depends on `LLMProvider.complete()` and `SessionScratchpad` being available*

- [ ] Implement `llm.summarize`, `llm.extract`, `llm.classify`: each constructs a fresh isolated message list, calls `LLMProvider.complete()`, persists no messages to `MessageStore`. *(Stub files exist; bodies not implemented.)*
- [ ] Record token usage for `llm.*` tool calls via `TokenAccounting` with `callType = "tool_llm"`.
- [ ] All `text=` args accept `$scratch/<path>` refs (resolved by substitution pass before dispatch).

### Step 6 — Agent Loop

*All of the above must be in place before the loop can be wired end-to-end*

- [x] Add `cancelFlag: AtomicBoolean` parameter to `AgentEngine.run()` and update `AgentLoop` and `Routes.scala`.
- [x] Add `maxIterations: Int` (default 20) to `AppSettings` and expose in Settings UI.
- [x] Implement `ToolCallParser`: scan model response text for all `run(command="...")` occurrences; return `List[ToolCallResult]` (sum of `Success(ParsedToolCall)` and `Failure(ParseFailure)`); log parse failures as `TraceLogger.warn`; inject a structured `parse_failed` error result into `[TOOL RESULT]` for each failure so the model can self-correct (never silently drop — see `agent_loop_runtime.md` §3.6).
- [x] Implement multi-iteration plan→act→observe loop with cancellation check and max-iteration cap.
- [x] Inject tool results back as plain `user`-role turns prefixed with `[TOOL RESULT]`.
- [x] Dispatch commands through `VirtualShell` / `CommandRegistry`.
- [x] Detect `<done>` marker for final answer; soft fallback (accept + warn) if absent.
- [x] Emit `ToolCallStart` and `ToolCallResult` SSE events with command, args, rendered output, and duration.
- [x] Log each tool call via `TraceLogger` (command, args, status, durationMs, traceId, iteration).
- [x] Persist each tool call to `RunStore` immediately after execution (per-call, not end-of-run).

### Step 7 — System Prompt

*Depends on `CommandRegistry.helpIndex()` being finalized*

- [x] Create `backend/src/main/resources/system_prompt.txt` with `{{TOOL_INDEX}}`, `{{ROOT_PATH}}`, `{{TODAY}}` substitution slots (full template in `agent_loop_runtime.md` §8.1).
- [x] Load template once at `BackendServer` startup; substitute `{{TOOL_INDEX}}` from `CommandRegistry.helpIndex()`.
- [x] Apply `{{ROOT_PATH}}` and `{{TODAY}}` per-session in `ContextManager.assemble()`.

### Step 8 — Debugging and Tests

*Log viewer can be built in parallel with Steps 1–6; golden scenarios require a working loop*

- [x] Add file output to `TraceLogger`: write to `AppDirs.dataDir/logs/agentica.log` alongside stdout.
- [x] Standardise `TraceLogger` extra-field names across all call sites: `parseError` renamed from `reason` (done). `callType`, `parentTraceId` (for `llm.*` tools), `decision`/`grantTTL` (permissions) — blocked on Step 5 / UI completion. `messagesDropped`/`budgetTokens` already correct.
- [x] Add `GET /log/stream` SSE endpoint (authenticated): replay last 200 lines of `agentica.log` on connect, then stream new lines via `RandomAccessFile` poll (100ms interval).
- [x] Create `ui/log-viewer.html` + `ui/js/log-viewer.js`: connects to `GET /log/stream` as `EventSource`; renders lines with level colouring (WARN yellow, ERROR red); auto-scroll with pause toggle; client-side substring filter input; "Clear display" button.
- [x] Add **"Debug log"** button via dropdown menu (converted settings gear to kebab menu): calls `window.open('/log-viewer.html')`.
- [x] Serve `log-viewer.html` as a static route in `Routes.scala`.
- [x] Repurpose or replace `debug.js` for the log viewer page (deleted; replaced by `log-viewer.js`).
- [x] Implement `ScriptedLLMProvider`: takes `List[String]`, returns one per `stream()` call; use for all Phase 2 scenarios while system prompt is evolving.
- [x] Implement `JSONFileLLMProvider`: reads responses from `scenarios/<name>.json`, replays sequentially for golden scenario tests.
- [x] Add agent-loop replay test scaffolding (`GoldenScenarioRunner`: create temp workspace, run loop with mock provider, assert tool call sequence and final answer).
- [x] Add 8 golden scenarios using `JSONFileLLMProvider` (read_file, list_and_search, multi_tool_single_response, use_memory, search_with_error_recovery, iteration_boundary, deep_list, stat_and_summarize).
- [x] Add integration tests with a mock `LLMProvider` (GoldenScenarioTest exercises full agent loop end-to-end).
- [ ] Add UI smoke tests for streamed agent events.

---

## Phase 3 — Documents, Browser, and Cloud Providers

Goal: expand capabilities beyond chat/files with document processing, browser automation, and cloud LLM providers.

### Document Tools (Markdown-First)

**Approach:** LLM generates Markdown first, then converts to target format via Pandoc (optional) or pure-JVM fallback.

**Read tools (pure JVM):**
- [ ] Add Apache PDFBox dependency; implement `files.read_pdf` tool.
- [ ] Add Apache POI dependency; implement `files.read_docx` tool.

**Write tools (markdown-first):**
- [ ] Implement `files.write_markdown` tool: save raw markdown content.
- [ ] Add Flexmark dependency for Markdown AST parsing.
- [ ] Implement `files.markdown_to_docx` tool: Pandoc if available, else Flexmark → POI XWPF.
- [ ] Implement `files.markdown_to_pptx` tool: Pandoc if available, else Flexmark → POI XSLF.
- [ ] Implement `files.markdown_to_pdf` tool: Playwright print-to-PDF (HTML intermediate).

**Advanced (optional):**
- [ ] Support template files (`.docx`, `.pptx`) stored in workspace for branded output.
- [ ] Add Pandoc detection/installation helper.
- [ ] Add document-tool tests with fixture files and template validation.
- [ ] Keep Excel deferred until a safe tool surface is designed.

### Browser Tools (Playwright)

- [ ] Add `playwright-java` dependency.
- [ ] Implement `browser.open` tool: fetch URL, render JS, return page text.
- [ ] Implement `browser.select` tool: extract content by CSS selector.
- [ ] Add Playwright browser detection/installation helper.
- [ ] Add browser-tool tests with local HTTP server fixtures.

### LLM Providers and Secrets

- [ ] Stabilize llama.cpp provider as a power-user option.
- [ ] Add cloud OpenAI provider configuration.
- [ ] Add Anthropic provider configuration.
- [ ] Implement OS-keychain-backed secret storage.
- [ ] Ensure secrets are never stored in SQLite or plain files.
- [ ] Add provider selection UI/configuration.

---

## Phase 4 — Vision and Audio

Goal: add vision and audio capabilities using multimodal LLMs and external transcription tools.

### Vision Tools

- [ ] Implement `vision.describe` tool using multimodal LLM (base64 image → LLM).
- [ ] Implement `vision.extract_text` tool (OCR via LLM or Tesseract).
- [ ] Add vision-tool tests with fixture images.

### Audio Tools

- [ ] Implement `audio.transcribe` tool (Whisper CLI subprocess with graceful fallback).
- [ ] Add audio-tool tests with fixture audio files.

---

## Phase 5 — Packaging

Goal: bring the JavaFX WebView thin launcher to full standalone operation and package for distribution.

### Stage 5A — Launcher Starts Backend

- [ ] Choose launcher strategy: child JVM process first, embedded backend later if needed.
- [ ] Generate or choose a local bearer token at launcher startup.
- [ ] Choose an available local port at launcher startup.
- [ ] Start backend with `AGENTICA_PORT`, `AGENTICA_TOKEN`, and `AGENTICA_UI_ROOT` set.
- [ ] Load `http://127.0.0.1:<port>/?token=<token>` in WebView.
- [ ] Forward backend logs to log files.
- [ ] Shut down backend when the JavaFX window exits.
- [ ] Show a useful error screen if backend startup fails.

### Stage 5B — Native Integration Bridge

- [ ] Add optional JavaScript-to-JavaFX bridge for packaged mode.
- [ ] Implement native directory chooser through JavaFX.
- [ ] Return full selected folder path to the existing web UI.
- [ ] Keep browser-mode folder picker/text input fallback intact.
- [ ] Ensure the bridge is unavailable or safely ignored in plain browser mode.

### Stage 5C — Packaging Exploration

- [ ] Decide whether JavaFX builds are per-OS artifacts or bundled runtime images.
- [ ] Add Maven profile for desktop launcher main class.
- [ ] Add JavaFX platform classifier handling for Linux, Windows, macOS Intel, and macOS ARM.
- [ ] Test desktop launcher on Linux.
- [ ] Test desktop launcher on Windows.
- [ ] Test desktop launcher on macOS if available.
- [ ] Revisit `jlink` / `jpackage` after launcher compatibility is proven.

---

## Phase 6 — Retrieval, Excel, and Workflow Refinement

Goal: improve long-context behavior and add richer workflow support.

- [ ] Add file indexing pipeline.
- [ ] Add retrieval-augmented context selection.
- [ ] Add global/session-level search over indexed files.
- [ ] Design safe Excel tool surface.
- [ ] Implement Excel read tools after design review.
- [ ] Implement Excel write/update tools after design review.
- [ ] Add advanced workflow primitives.
- [ ] Refine streaming and cancellation semantics.
- [ ] Improve cost/token dashboards.

---

## Phase 7 — Hardening and Ecosystem

Goal: improve isolation, extensibility, collaboration, and advanced product capabilities.

- [ ] Add OS-level sandboxing options.
- [ ] Design plugin/tool ecosystem.
- [ ] Add multi-agent orchestration.
- [ ] Add cross-session memory.
- [ ] Add optional cloud sync.
- [ ] Add advanced permission policies.
- [ ] Add voice input/output if useful.
- [ ] Add real-time collaboration if product direction requires it.

---

## Near-Term Recommended Next Steps

- [ ] Finish Phase 1 cleanup items.
- [ ] Add configurable `AGENTICA_HOST`.
- [ ] Begin Phase 2 agent loop implementation.
