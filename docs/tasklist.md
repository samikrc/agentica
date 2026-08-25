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

### Step 2b — Automatic Scratchpad Chaining

*See `agent_loop_runtime.md` §4.7. All tool results are stored automatically in the scratchpad — no opt-in argument needed. File reads use path-keyed refs for staleness-aware caching; computed results (search, `llm.*`) use counter-keyed refs (`$scratch/__result_N__`) that increment per result. The model always has a stable `$scratch/` ref to pass to subsequent tools.*

- [x] Add `computedResultCounter: AtomicInteger` and `nextComputedKey(): String` to `SessionScratchpad`; key format is `"__result_N__"` where `N` increments per call (already stubbed in §4.6 class block).
- [x] Update `Tool.render()` signature to `render(output: O, ctx: ExecutionContext): ToolResult`; update all existing `Tool` implementations to match.
- [x] Update `FilesRead.render()`: always call `ctx.scratchpad.store(sourcePath, entry)`; for bodies ≤ 8000 chars return `ToolBody.Inline` with a `stored:` metadata entry; for large bodies return `ToolBody.ScratchRef` as before.
- [x] Update `FilesSearch.render()`: call `ctx.scratchpad.store(ctx.scratchpad.nextComputedKey(), entry)`; for small results return inline body plus counter-keyed `stored:` ref; for large results return `ScratchRef` only.
- [x] Update `[SCRATCHPAD]` section in `system_prompt.txt` to explain that all results include a `stored:` ref usable in subsequent tool arguments; remove `[CHAINING]` section (no longer needed — `store=true` arg eliminated).
- [x] Update unit tests: `FilesRead` on a small file → `Inline` body with a `stored:` metadata entry and path-keyed ref in scratchpad; `FilesRead` on a large file → `ScratchRef` only; `FilesSearch` on a small result → inline body with a counter-keyed `stored:` ref; counter increments correctly across multiple calls.

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
- [x] Add `GET /log/stream` endpoint (authenticated): replay last 200 lines of `agentica.log` on connect, then stream new lines via poll. **Upgraded to WebSocket** (`@cask.websocket`, `WsHandler`/`WsActor`) to enable clean lifecycle events (connect, close, channel-closed cleanup).
- [x] Create `ui/log-viewer.html` + `ui/js/log-viewer.js`: connects to `GET /log/stream` as a WebSocket; renders lines with level colouring (WARN yellow, ERROR red); auto-scroll with pause toggle; client-side substring filter input; "Clear display" button. Includes reconnect logic and connection-status indicator.
- [x] Add **"Debug log"** button via dropdown menu (converted settings gear to kebab menu): calls `window.open('/log-viewer.html')`.
- [x] Serve `log-viewer.html` as a static route in `Routes.scala`.
- [x] Repurpose or replace `debug.js` for the log viewer page (deleted; replaced by `log-viewer.js`).
- [x] Implement `ScriptedLLMProvider`: takes `List[String]`, returns one per `stream()` call; use for all Phase 2 scenarios while system prompt is evolving.
- [x] Implement `JSONFileLLMProvider`: reads responses from `scenarios/<name>.json`, replays sequentially for golden scenario tests.
- [x] Add agent-loop replay test scaffolding (`GoldenScenarioRunner`: create temp workspace, run loop with mock provider, assert tool call sequence and final answer).
- [x] Add 8 golden scenarios using `JSONFileLLMProvider` (read_file, list_and_search, multi_tool_single_response, use_memory, search_with_error_recovery, iteration_boundary, deep_list, stat_and_summarize).
- [x] Add integration tests with a mock `LLMProvider` (GoldenScenarioTest exercises full agent loop end-to-end).
- [x] Render assistant message content as Markdown in the chat UI using `marked.js` (self-hosted at `ui/js/marked.min.js` for JavaFX WebView compatibility); streaming tokens are re-rendered on every `onToken` event.
- [ ] Add UI smoke tests for streamed agent events.

### Phase 2.5 — Agent Turn Persistence and Step Rendering

*Adds full trajectory capture for agent runs: intermediate LLM reasoning steps and tool calls are persisted and rendered both during live runs and on session reload.*

- [x] Add `AgentTurnStep` and `AgentTurn` case classes to `session/Models.scala` with `derives ReadWriter`.
- [x] Create `AgentTurnStore` (`session/AgentTurnStore.scala`): `init()` creates `agent_turns` SQLite table; `insert()` serialises `steps` as a JSON column; `listForSession()` deserialises and returns turns ordered by timestamp.
- [x] Wire `AgentTurnStore` into `AgentLoop`: accumulate `thinking` steps (one per iteration, from LLM response text) and `tool_call` steps (one per dispatched tool, capturing command, result, and wall-clock `durationMs`) in an in-memory `ListBuffer[AgentTurnStep]`. Persist the completed `AgentTurn` on `Final` (not on `Cancelled`).
- [x] Wire `AgentTurnStore` into `BackendServer`: instantiate, `init()`, pass to `AgentLoop` and `Routes`.
- [x] Add `GET /sessions/:id/agent-turns` route in `Routes.scala`: returns JSON array of `AgentTurn` records for the session.
- [x] Live run UI (`chat.js`): `onIteration` creates a new collapsible iteration block; `onToken` streams into the iteration's "thinking" div; `onToolStart` appends a clickable tool chip; `onToolResult` populates the chip result. Final answer is moved from the last iteration's thinking div into the main bubble on `onFinal`.
- [x] History reload (`chat.js` `loadHistory`): fetches `/messages` and `/agent-turns` in parallel; builds `assistantMsgId → AgentTurn` map; renders collapsed steps block above each assistant bubble.
- [x] CSS for agent step UI (`ui/css/main.css`): `.agent-steps` (collapsible outer block), `.agent-iter` (per-iteration section with left border), `.agent-thinking` (faded, gradient-clipped LLM text), `.agent-tool-chip` (monospaced command row + expandable result).
- [x] Session title UX: generate a concise title from the first completed turn when the session still has a default `Session ...` title; persist it via the `final` SSE event; show the title in the sidebar and chat header; show creation timestamp below working folder metadata.
- [x] Manual session rename: add `POST /sessions/:id/title`, a rename modal, chat-header click-to-rename, and sidebar double-click-to-rename.
- [x] Tests: `AgentTurnStoreTest` (7 tests — empty list, round-trip, step serialisation, session scoping, timestamp ordering, fields, unicode); trajectory tests in `AgentLoopTest` (6 tests — single-shot, one tool call, two tool calls, multi-iteration, result content, cancelled run).
- [x] Token stats panel on assistant messages: chart icon in actions bar toggles a two-line inline panel showing **Tokens In**, **Tokens Out**, and **Total Time** (seconds); stats are aggregated from `GET /sessions/:id/token-usage` grouped by `traceId`; fetched in parallel on history reload, asynchronously after `onFinal` on live runs.

---

## Phase 3 — Documents, Browser, and Cloud Providers

Goal: expand capabilities beyond chat/files with document processing, browser automation, and cloud LLM providers.

### Document Tools

*Full architecture in `docs/document_processing.md`. Markdown is the canonical interchange format between the AI layer and all renderers. Ingestion is Vision-First: each format is rendered to images by a JVM-native renderer (PDFBox for PDF, POI XSLF for PPTX, LibreOffice headless for DOCX) and then converted to Markdown by the Vision LLM. Output rendering uses LibreOffice headless for all Markdown/DOCX → DOCX/PDF conversions, and docx4j for template filling and in-place OOXML editing. LibreOffice is the only required system install.*

#### Stage A — External Dependency Detection and Font Initialization ✓

- [x] Implement `DocToolDetector`: check the `soffice` binary at startup; cache result; expose via `deps.check` agent tool and Settings UI panel.
- [x] Infrastructure for structured errors: `DocToolDetector.installInstructions` provides install guidance; document tools (Stage B/C) will use this to return user-friendly errors when LibreOffice is missing.
- [x] Download Liberation font files (open-source substitutes: Sans → Arial, Serif → Times New Roman, Mono → Courier New). Proprietary fonts (Calibri, Aptos, Segoe UI) are not bundled — warned at startup.
- [x] Register bundled fonts with AWT at startup (PDFBox/POI XSLF font embedding uses raw bytes from `DocFontLoader.loadedFonts` at render time); log registered families; warn if font resources missing.

#### Stage B — Vision-First Document Ingestion

*Implements `files.read_pdf_to_markdown`, `files.read_docx_to_markdown`, `files.read_pptx_to_markdown` via a shared Vision pipeline. PDFBox and POI XSLF dependencies added. Each tool persists the assembled Markdown as `<document>.md` (e.g., `report.pdf` → `report.md`) alongside the source file; subsequent calls reuse the cached file if the source has not been modified (last-modified timestamp comparison).*

- [x] Define `PageRenderer` abstraction: `def renderToImages(path: Path): List[Array[Byte]]` — returns ordered PNG byte arrays.
- [x] Implement `PDFPageRenderer` using PDFBox: `Loader.loadPDF` → `PDFRenderer.renderImageWithDPI(pageIndex, dpi=150)` → PNG bytes per page.
- [x] Implement `PPTXSlideRenderer` using POI XSLF: `XMLSlideShow` → `slide.draw(Graphics2D)` on a target-DPI `BufferedImage` → PNG bytes per slide.
- [x] Implement `DOCXPageRenderer` using LibreOffice headless: `soffice --headless --convert-to png --outdir <tmp>` → read PNG files in page order.
- [x] Implement `PageVisionTranscriber`: base64-encode each image → call VLM `completeVision()`; collect per-page Markdown; assemble with `---` separators.
- [x] Add vision support to `LLMProvider`: `completeVision(base64Image, prompt)` and `supportsVision` flag; implemented in `OpenAIProvider`.
- [x] Return structured error if neither VLM nor LLM supports vision.
- [x] Support `enrich_images=false` arg: skip VLM, return stub Markdown with `[page N: vision enrichment skipped]` placeholders.
- [ ] Rename `files.read_pdf` → `files.read_pdf_to_markdown`; write assembled Markdown to `<source>.md`; on subsequent calls compare source vs `.md` last-modified timestamps — regenerate only if source is newer; return path to `.md` file. Permission-gated (writes to source directory).
- [ ] Rename `files.read_docx` → `files.read_docx_to_markdown`; same persist + staleness + permission logic; LibreOffice availability check with structured error.
- [ ] Rename `files.read_pptx` → `files.read_pptx_to_markdown`; same persist + staleness + permission logic.
- [ ] Use separately configured VLM provider (Settings → VLM tab) for vision calls; fall back to primary LLM if VLM not configured.
- [ ] Tests: mock LLMProvider returning fixed per-page Markdown; fixture PDF/DOCX/PPTX files; verify assembled Markdown structure and `.md` caching; staleness re-generation; `enrich_images=false` path.

#### Stage C — Free-Form Document Generation

- [ ] Implement `files.write_markdown`: write Markdown content to a sandboxed path; confirm bytes written; permission-gated like `files.write`.
- [ ] Implement `files.markdown_to_docx`: invoke `soffice --headless --convert-to docx <input.md> --outdir <tmp>`; return output path.
- [ ] Implement `files.markdown_to_pdf`: invoke `soffice --headless --convert-to pdf <input.md> --outdir <tmp>`; return output path.
- [ ] Tests: mock `ProcessBuilder` for LibreOffice; verify arg construction, output path handling, and non-zero exit handling.

#### Stage D — Template-Based DOCX Generation + In-Place Editing (docx4j)

- [ ] Add `docx4j` Maven dependency.
- [ ] Implement `files.list_templates`: scan workspace `templates/` directory for `.docx` files; return names and placeholder keys extracted from each template.
- [ ] Implement `TemplateEngine` (docx4j): load `.docx` template, accept `Map[placeholderKey, content]`, fill structured document tags or `{{key}}` text markers, write filled DOCX to output path.
- [ ] Implement `files.fill_template`: validate template exists, accept key→value content map from agent, invoke `TemplateEngine`, return output path; permission-gated.
- [ ] Support optional `convert_to_pdf=true` arg: pipe filled DOCX through LibreOffice headless.
- [ ] Implement `DocxSectionLocator`: given a loaded docx4j `WordprocessingMLPackage`, locate a target paragraph block via (in priority order): (1) named SDT (`<w:tag w:val="..."/>`), (2) heading text match (`<w:pStyle val="HeadingN">` + text comparison), (3) proximity text search. Return structured error (listing attempted strategies) when no anchor is found rather than making a best-guess replacement.
- [ ] Implement thin Markdown-to-runs converter: handle `**bold**` → `<w:b>`, `*italic*` → `<w:i>`, plain text → unstyled run; copy `<w:rPr>` from first existing run of the target paragraph as the style prototype for all inserted runs.
- [ ] Implement `files.edit_docx path=... section="..." content="..."`: locate section via `DocxSectionLocator`, replace paragraph block using Markdown-to-runs converter, save to path; permission-gated.
- [ ] Implement `files.patch_docx path=... patches=[{section, content}, ...]`: apply all section replacements in a single docx4j pass (more efficient than repeated single edits); return output path; permission-gated.
- [ ] Tests: fixture `.docx` templates with known placeholders (SDT, heading, proximity); verify fill accuracy; verify PDF conversion path; verify `edit_docx` preserves surrounding paragraphs and `<w:rPr>` styles; verify `patch_docx` applies all patches atomically; verify structured error when no anchor found.

#### Stage E — PPTX In-Place Editing and Generation (Apache POI XSLF — deferred)

- [ ] POI XSLF Maven dependency is already present from Stage B — no additional install needed.
- [ ] Implement `files.edit_pptx path=... slide=N shape="..." content="..."`: open existing `.pptx`, find shape by slide index + shape name/title, replace `XSLFTextRun` content preserving run formatting, save back; permission-gated. *(Same dual-layer principle as DOCX editing — Vision LLM for reading/reasoning, POI for writing.)*
- [ ] Implement `files.write_pptx`: accept slide structure from agent (title, bullet content per slide), generate `.pptx` from scratch via POI XSLF.
- [ ] *(Blocked: begin after Stage D DOCX pipeline is proven stable.)*

#### Stage F — Integration Tests

- [ ] Add golden scenario: read PDF → generate DOCX report via Pandoc.
- [ ] Add golden scenario: read DOCX → extract content → fill branded template via docx4j → export PDF.
- [ ] Add fixture files for PDF, DOCX, PPTX with known content for deterministic test assertions.
- [ ] Keep Excel deferred until a safe tool surface is designed.

### Browser Tools (Playwright)

- [ ] Add `playwright-java` dependency.
- [ ] Implement `browser.open` tool: fetch URL, render JS, return page text.
- [ ] Implement `browser.select` tool: extract content by CSS selector.
- [ ] Add Playwright browser detection/installation helper.
- [ ] Add browser-tool tests with local HTTP server fixtures.

### Settings Redesign — Tabbed UI + VLM Configuration

*Current flat settings modal does not scale. Redesign as a tabbed interface with separate LLM and VLM configuration.*

- [ ] Redesign settings modal as tabbed UI with three tabs: **General**, **LLM**, **VLM**.
  - **General tab:** Theme, Show status line.
  - **LLM tab:** Server URL, API Key, Model, API Mode (Chat Completions / Responses).
  - **VLM tab:** Server URL, API Key, Model. When empty, falls back to primary LLM.
- [ ] Add `vlmServerUrl`, `vlmApiKey`, `vlmModel` fields to `AppSettings`.
- [ ] Add `apiKey` field to `AppSettings` for LLM API key (currently only via `LLM_API_KEY` env var).
- [ ] Update `SettingsStore` to serialize/deserialize new fields.
- [ ] Update `BackendServer`: construct a separate VLM `LLMProvider` from VLM settings when configured; pass to document tools via `ExecutionContext`.
- [ ] Update settings UI HTML (`index.html`): replace flat form with tabbed layout.
- [ ] Update `settings.js`: handle tab switching, new fields, save/load.
- [ ] Default LLM and VLM server URLs to `http://172.23.64.1:1234`; default model to `mistralai/ministral-3-14b-reasoning`.

### LLM Providers and Secrets

- [ ] Stabilize llama.cpp provider as a power-user option.
- [ ] Add cloud OpenAI provider configuration.
- [ ] Add Anthropic provider configuration.
- [ ] Implement OS-keychain-backed secret storage.
- [ ] Ensure secrets are never stored in SQLite or plain files.

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

- [ ] Implement `llm.summarize`, `llm.extract`, `llm.classify` tool bodies (Phase 2 Step 5 — currently stubs).
- [ ] Add configurable `AGENTICA_HOST` bind address (Phase 1 cleanup).
- [ ] Add UI smoke tests for streamed agent events.
- [ ] Rename document read tools to `read_*_to_markdown`; add Markdown caching with staleness check (Phase 3, Stage B).
- [ ] Settings redesign: tabbed UI (General / LLM / VLM), separate VLM configuration, LLM API key field.
- [ ] Begin Phase 3: document generation (Stage C), browser tools (`browser.open`), cloud LLM providers.
