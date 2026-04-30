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

### Stage 1.5A — WebView Compatibility Test

- [ ] Add JavaFX dependencies behind a Maven profile or configurable properties.
- [ ] Add `agentica.DesktopLauncher` as a minimal JavaFX application.
- [ ] First version should only open a `WebView` and load an already-running backend URL.
- [ ] Validate rendering of current HTML/CSS in JavaFX WebView.
- [ ] Validate frontend JavaScript execution.
- [ ] Validate session loading and selection.
- [ ] Validate new-session modal behavior.
- [ ] Validate chat submit flow.
- [ ] Validate streaming response rendering over SSE/fetch streaming.
- [ ] Validate scrolling, textarea behavior, and debug pane behavior.
- [ ] Document any WebView incompatibilities before adding packaging complexity.

### Stage 1.5B — Launcher Starts Backend

- [ ] Choose launcher strategy: child JVM process first, embedded backend later if needed.
- [ ] Generate or choose a local bearer token at launcher startup.
- [ ] Choose an available local port at launcher startup.
- [ ] Start backend with `AGENTICA_PORT`, `AGENTICA_TOKEN`, and `AGENTICA_UI_ROOT` set.
- [ ] Load `http://127.0.0.1:<port>/?token=<token>` in WebView.
- [ ] Forward backend logs to launcher console or log files.
- [ ] Shut down backend when the JavaFX window exits.
- [ ] Show a useful error screen if backend startup fails.

### Stage 1.5C — Native Integration Bridge

- [ ] Add optional JavaScript-to-JavaFX bridge for packaged mode.
- [ ] Implement native directory chooser through JavaFX.
- [ ] Return full selected folder path to the existing web UI.
- [ ] Keep browser-mode folder picker/text input fallback intact.
- [ ] Ensure the bridge is unavailable or safely ignored in plain browser mode.

### Stage 1.5D — Packaging Exploration

- [ ] Decide whether JavaFX builds are per-OS artifacts or bundled runtime images.
- [ ] Add Maven profile for desktop launcher main class.
- [ ] Add JavaFX platform classifier handling for Linux, Windows, macOS Intel, and macOS ARM.
- [ ] Test desktop launcher on Linux.
- [ ] Test desktop launcher on Windows.
- [ ] Test desktop launcher on macOS if available.
- [ ] Revisit `jlink` / `jpackage` after launcher compatibility is proven.

---

## Phase 2 — Full Agent Loop and Tool Runtime

Goal: replace the Phase 1 single-call loop with a safe plan→act→observe agent loop backed by the virtual shell and scoped tools.

### Agent Loop

- [ ] Implement multi-iteration plan→act→observe loop.
- [ ] Add max-iteration limits and cancellation handling.
- [ ] Parse model tool-call output into virtual-shell commands.
- [ ] Dispatch commands through `VirtualShell` / `CommandRegistry`.
- [ ] Feed structured tool results back into the next LLM turn.
- [ ] Emit structured events for iterations, tool starts, tool results, final answer, cancellation, and errors.
- [ ] Persist traceable run/tool events for replay/debugging.

### Context Management

- [ ] Implement context-window assembly.
- [ ] Add token-budget-aware message selection.
- [ ] Add summarization strategy for older messages.
- [ ] Decide how workspace files enter context.
- [ ] Add tests for context trimming and summarization behavior.

### Virtual Shell and Tools

- [ ] Stabilize tokenizer and command AST behavior.
- [ ] Stabilize central command registry.
- [ ] Add `help` command generated from registry metadata.
- [ ] Complete core file tools: list, read, write, search, stat.
- [ ] Complete memory tools: get, set, list.
- [ ] Complete LLM utility tools: summarize, extract, classify.
- [ ] Ensure tools use typed validation and structured presentation envelopes.
- [ ] Ensure no arbitrary shell execution is exposed.

### Permissions and Safety

- [ ] Implement scoped permission checks for sensitive tools.
- [ ] Add UI prompts for folder access, file modification, and tool execution.
- [ ] Support allow once / allow for session / allow always for path.
- [ ] Return structured permission-denied results to the agent.
- [ ] Add path traversal and workspace-boundary tests.

### Debugging and Tests

- [ ] Expand debug pane to show iterations, tool calls, durations, and token counts.
- [ ] Add agent-loop replay test scaffolding.
- [ ] Add first 5–10 golden scenarios.
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

## Phase 4 — Retrieval, Excel, and Workflow Refinement

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

## Phase 5 — Hardening and Ecosystem

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
- [ ] Add minimal JavaFX `DesktopLauncher` that loads an already-running backend.
- [ ] Test JavaFX WebView compatibility before starting backend-launch or packaging work.
