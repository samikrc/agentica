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

- [ ] Implement `CommandAst`: `case class Command(family, verb, args: Map[String, String])`.
- [ ] Implement `Tokenizer`: hand-written `family.verb key=val` parser with quoted-string and escaped-quote support; return `Right(Command)` or `Left(ParseError)`.
- [ ] Implement `Tool[I,O]` trait: `validate / execute / render` pipeline; independently testable per stage.
- [ ] Implement `PathSandbox` utility: resolve `path=` args against `session.rootPath`, reject if result escapes sandbox; used by all file-touching tools.
- [ ] Implement `SessionScratchpad`: session-scoped in-memory content cache, path-keyed, staleness check (`lastModifiedTime` comparison), LRU eviction (max 20 entries); `store()`, `get()`, `isStale()`.
- [ ] Hold one `SessionScratchpad` per active session in `BackendServer` (`ConcurrentHashMap[sessionId, SessionScratchpad]`); remove on session delete.
- [ ] Add `scratchpad: SessionScratchpad` reference to `ExecutionContext`; also carries `rootPath`, `traceId`, `sessionId`, `scopeStore`.
- [ ] Implement `CommandRegistry`: `dispatch()`, `helpIndex()`, `helpFor()`, `allSchemas()`; register all tools here at startup.
- [ ] Add `help` command (handled directly in `CommandRegistry`, not a `Tool[I,O]`): `help` / `help <family>` / `help <family.verb>`; output in `AgentResponse` envelope.
- [ ] Implement `VirtualShell`: `execute(rawCommand, ctx)` → `Tokenizer` → `CommandRegistry.dispatch` → `Presentation.render`.
- [ ] Implement `Presentation` layer: `BODY_BUDGET_CHARS = 8000` constant; route oversized bodies to `SessionScratchpad`; emit `try` suggestions with scratchpad ref; binary content → `<binary N KB mime/type>` placeholder.
- [ ] Add substitution pass in `VirtualShell` (before dispatch): resolve `$scratch/<path>` refs in any arg value to full `String` content from `SessionScratchpad`.
- [ ] Unit tests: tokenizer (all valid forms, all error cases, embedded newlines, multi-word quoted values), `PathSandbox` escape detection.

### Step 2 — `files.*` Tool Implementations

- [ ] `files.read`: optional `lines=start-end` range arg; route to scratchpad if body > 8000 chars; check staleness before re-reading.
- [ ] `files.write`: permission scope check (see Step 3); confirm bytes written.
- [ ] `files.list`: ls-style; args: `path`, `recursive` (default false), `all` (dotfiles, default false), `depth` (default 3), `pattern` glob; indented tree output with size and date.
- [ ] `files.search`: grep-style; args: `query`, `path`, `recursive` (default true), `ignore_case` (default false), `lines_context` (default 2), `max_matches` (default 50), `include` glob, `regex` flag; skip binary files; `>` prefix on matching line.
- [ ] `files.stat`: file size, modified time, type.
- [ ] Ensure all `files.*` tools run `PathSandbox` check before any execution.
- [ ] Ensure tools use typed validation and structured presentation envelopes; no arbitrary shell execution.

### Step 3 — `memory.*` Tools and Permissions

*`MemoryStore`, `memory.*`, `ScopeStore`, permission flow, UI modal*

- [ ] Implement `MemoryStore` trait with `sessionId: Option[String]`; back with SQLite `memory_entries(session_id, key, value, updated_at)`; `session_id = NULL` reserved for Phase 6 global scope.
- [ ] Implement `memory.set`, `memory.get`, `memory.list` using session-scoped `MemoryStore`.
- [ ] Add `MemoryEntry` to `session/Models.scala`; create DB migration.
- [ ] Implement `ScopeStore`: SQLite-backed `permission_grants` table; `Grant`, `GrantTtl`, `GrantDecision` types in `permissions/Models.scala`.
- [ ] Add `AgentEvent.PermissionRequired(tool, path, options)` to `AgentEvent` enum in `AgentEngine.scala`.
- [ ] Emit `PermissionRequired` SSE event from `VirtualShell` when `files.write` has no grant; handle in `Routes.scala` SSE serialiser.
- [ ] Add `permissionQueues: ConcurrentHashMap[runId, SynchronousQueue[GrantDecision]]` to `Routes.scala`.
- [ ] Add `POST /permissions` endpoint: look up `runId`, offer `GrantDecision` to the queue.
- [ ] Block the agent virtual thread on `SynchronousQueue.poll(60, SECONDS)`; treat timeout as `Denied`.
- [ ] On `Granted`: store grant in `ScopeStore` with chosen `GrantTtl`; continue execution.
- [ ] On `Denied` or timeout: return `AgentResponse(error: permission_denied)`; log event.
- [ ] UI: handle `permission_required` SSE event; show modal with **Allow once / Allow for session / Allow always / Deny** options; POST decision to `/permissions`.
- [ ] Add path traversal and workspace-boundary tests.

### Step 4 — Context Management

- [ ] Add `contextBudgetTokens: Int = 8000` to `AppSettings`; expose in Settings dialog.
- [ ] Implement token estimation in `ContextManager`: `text.length / 4` per message.
- [ ] Implement budget-aware history window: include messages newest-first until budget exhausted; system prompt, current user message, and in-flight tool results always included regardless of budget.
- [ ] Log truncation events at `TraceLogger.debug` (`messagesDropped`, `budgetTokens`) when messages are dropped.
- [ ] Workspace files enter context only via explicit `files.read` tool calls (no automatic injection in Phase 2).
- [ ] Summarization of dropped messages: **deferred to Phase 5**.
- [ ] Add tests for context trimming behaviour (messages dropped correctly, priority order respected).

### Step 5 — `llm.*` Tool Implementations

*Depends on `LLMProvider.complete()` and `SessionScratchpad` being available*

- [ ] Implement `llm.summarize`, `llm.extract`, `llm.classify`: each constructs a fresh isolated message list, calls `LLMProvider.complete()`, persists no messages to `MessageStore`.
- [ ] Record token usage for `llm.*` tool calls via `TokenAccounting` with `callType = "tool_llm"`.
- [ ] All `text=` args accept `$scratch/<path>` refs (resolved by substitution pass before dispatch).

### Step 6 — Agent Loop

*All of the above must be in place before the loop can be wired end-to-end*

- [ ] Add `cancelFlag: AtomicBoolean` parameter to `AgentEngine.run()` and update `AgentLoop` and `Routes.scala`.
- [ ] Add `maxIterations: Int` (default 20) to `AppSettings` and expose in Settings UI.
- [ ] Implement `ToolCallParser`: scan model response text for all `run(command="...")` occurrences, return `List[ParsedToolCall]`; log parse failures as warnings.
- [ ] Implement multi-iteration plan→act→observe loop with cancellation check and max-iteration cap.
- [ ] Inject tool results back as plain `user`-role turns prefixed with `[TOOL RESULT]`.
- [ ] Dispatch commands through `VirtualShell` / `CommandRegistry`.
- [ ] Detect `<done>` marker for final answer; soft fallback (accept + warn) if absent.
- [ ] Emit `ToolCallStart` and `ToolCallResult` SSE events with command, args, rendered output, and duration.
- [ ] Log each tool call via `TraceLogger` (command, args, status, durationMs, traceId, iteration).
- [ ] Persist each tool call to `RunStore` immediately after execution (per-call, not end-of-run).

### Step 7 — System Prompt

*Depends on `CommandRegistry.helpIndex()` being finalized*

- [ ] Create `backend/src/main/resources/system_prompt.txt` with `{{TOOL_INDEX}}`, `{{ROOT_PATH}}`, `{{TODAY}}` substitution slots (full template in `agent_loop_runtime.md` §8.1).
- [ ] Load template once at `BackendServer` startup; substitute `{{TOOL_INDEX}}` from `CommandRegistry.helpIndex()`.
- [ ] Apply `{{ROOT_PATH}}` and `{{TODAY}}` per-session in `ContextManager.assemble()`.

### Step 8 — Debugging and Tests

*Log viewer can be built in parallel with Steps 1–6; golden scenarios require a working loop*

- [ ] Add file output to `TraceLogger`: write to `AppDirs.dataDir/logs/agentica.log` alongside stdout.
- [ ] Expand `TraceLogger` structured fields: `callType`, `parentTraceId` (for `llm.*` tools), `decision`/`grantTtl` (permissions), `messagesDropped`/`budgetTokens` (context trim), `parseError` (tool call parse failures).
- [ ] Add `GET /log/stream` SSE endpoint (authenticated): replay last 200 lines of `agentica.log` on connect, then stream new lines via `RandomAccessFile` poll (100ms interval).
- [ ] Create `ui/log-viewer.html` + `ui/js/log-viewer.js`: connects to `GET /log/stream` as `EventSource`; renders lines with level colouring (WARN yellow, ERROR red); auto-scroll with pause toggle; client-side substring filter input; "Clear display" button.
- [ ] Add **"Debug log"** button to main UI header: calls `window.open('/log-viewer.html')`.
- [ ] Serve `log-viewer.html` as a static route in `Routes.scala`.
- [ ] Repurpose or replace `debug.js` for the log viewer page (no longer targets `index.html` DOM elements).
- [ ] Implement `ScriptedLLMProvider`: takes `List[String]`, returns one per `stream()` call; use for all Phase 2 scenarios while system prompt is evolving.
- [ ] Implement `RecordingLLMProvider`: wraps real `LLMProvider`, writes responses to `scenarios/<name>.yaml`.
- [ ] Implement `ReplayLLMProvider`: reads recorded YAML, replays responses; mismatch fails test.
- [ ] Add agent-loop replay test scaffolding (`GoldenScenarioRunner`: create temp workspace, run loop with mock provider, assert tool call sequence and final answer).
- [ ] Add first 5–10 golden scenarios using `ScriptedLLMProvider`.
- [ ] Add integration tests with a mock `LLMProvider`.
- [ ] Add UI smoke tests for streamed agent events.

---

## Phase 3 — Documents, Cloud Providers, and Packaging

Goal: expand capabilities beyond chat/files and prepare real packaged distribution.

### Document Tools

- [ ] Implement Word read tool.
- [ ] Implement Word append/update tool.
- [ ] Implement PowerPoint read tool.
- [ ] Implement PowerPoint slide-add/update tool.
- [ ] Implement sealed LibreOffice wrapper where needed for conversions.
- [ ] Add document-tool tests with fixture files.
- [ ] Keep Excel deferred until a safe tool surface is designed.

### LLM Providers and Secrets

- [ ] Stabilize llama.cpp provider as a power-user option.
- [ ] Add cloud OpenAI provider configuration.
- [ ] Add Anthropic provider configuration.
- [ ] Implement OS-keychain-backed secret storage.
- [ ] Ensure secrets are never stored in SQLite or plain files.
- [ ] Add provider selection UI/configuration.

### Packaging

- [ ] Revisit JavaFX launcher packaging after Phase 1.5 validation.
- [ ] Explore `jlink` runtime images.
- [ ] Explore `jpackage` native installers.
- [ ] Define Windows signing path.
- [ ] Define macOS signing/notarization path.
- [ ] Define Linux package strategy if needed.
- [ ] Define update strategy after packaged mode stabilizes.

---

## Phase 4 — Desktop Launcher and Packaging

Goal: bring the JavaFX WebView thin launcher to full standalone operation and package for distribution.

### Stage 4A — Launcher Starts Backend

- [ ] Choose launcher strategy: child JVM process first, embedded backend later if needed.
- [ ] Generate or choose a local bearer token at launcher startup.
- [ ] Choose an available local port at launcher startup.
- [ ] Start backend with `AGENTICA_PORT`, `AGENTICA_TOKEN`, and `AGENTICA_UI_ROOT` set.
- [ ] Load `http://127.0.0.1:<port>/?token=<token>` in WebView.
- [ ] Forward backend logs to log files.
- [ ] Shut down backend when the JavaFX window exits.
- [ ] Show a useful error screen if backend startup fails.

### Stage 4B — Native Integration Bridge

- [ ] Add optional JavaScript-to-JavaFX bridge for packaged mode.
- [ ] Implement native directory chooser through JavaFX.
- [ ] Return full selected folder path to the existing web UI.
- [ ] Keep browser-mode folder picker/text input fallback intact.
- [ ] Ensure the bridge is unavailable or safely ignored in plain browser mode.

### Stage 4C — Packaging Exploration

- [ ] Decide whether JavaFX builds are per-OS artifacts or bundled runtime images.
- [ ] Add Maven profile for desktop launcher main class.
- [ ] Add JavaFX platform classifier handling for Linux, Windows, macOS Intel, and macOS ARM.
- [ ] Test desktop launcher on Linux.
- [ ] Test desktop launcher on Windows.
- [ ] Test desktop launcher on macOS if available.
- [ ] Revisit `jlink` / `jpackage` after launcher compatibility is proven.

---

## Phase 5 — Retrieval, Excel, and Workflow Refinement

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

## Phase 6 — Hardening and Ecosystem

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
