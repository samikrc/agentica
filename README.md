# Agentica — AI Assistant (Scala backend + browser UI)

## Running Agentica

The UI is served directly by the backend — no Tauri, no Electron, no build step.
Just a JVM fat-jar + any browser.

### Quick start (Linux / macOS)
For dev:
```
AGENTICA_DEV_TOKEN=dev-token AGENTICA_PORT=8080 LLM_BASE_URL=http://172.23.64.1:1234 LLM_MODEL=mistralai/ministral-3-14b-reasoning mvn compile exec:java
```


```bash
cd backend && mvn package -DskipTests   # build the fat-jar (once)
cd ..
chmod +x launch.sh
./launch.sh
# Open: http://localhost:8080/?token=dev-token
```

### Quick start (Windows)

```bat
cd backend
mvn package -DskipTests
cd ..
launch.bat
REM Open: http://localhost:8080/?token=dev-token
```

### Configuration

LLM server URL and model name are configured in the **Settings** modal (gear icon in the sidebar). Defaults:

| Setting | Default |
|---|---|
| Server URL | `http://172.23.64.1:1234` |
| Model | `mistralai/ministral-3-14b-reasoning` |

Settings are persisted as `settings.json` alongside the SQLite database.

Launch scripts accept additional environment variables:

| Variable | Default | Purpose |
|---|---|---|
| `LLM_BASE_URL` | `http://localhost:1234` | LM Studio / Ollama base URL (launch script only) |
| `LLM_MODEL` | `mistralai/ministral-3-14b-reasoning` | Model name (launch script only) |
| `AGENTICA_DEV_TOKEN` | `dev-token` | Bearer token for the API |
| `AGENTICA_PORT` | `8080` | HTTP port the backend listens on |
| `AGENTICA_UI_ROOT` | `../ui` (relative to jar) | Path to the `ui/` folder |

## UI Features

- **Session management**: Create, rename, and delete sessions from the sidebar
- **Dynamic session titles**: Sessions are automatically titled based on the first agent response
- **Message actions**: 
  - **Copy**: Copy message text to clipboard (plain text for user messages, HTML for agent messages)
  - **Restart**: Restart conversation from any user message (deletes all subsequent messages and data)
- **Agent steps**: View detailed step-by-step execution history for each agent response
- **Settings**: Configure LLM server URL and model (persisted to settings.json)
- **Theme**: Toggle between light and dark themes

---

## Developer Notes

### Recommended dev setup (fast iteration)

#### Terminal 1 — Scala Backend

```bash
cd backend
AGENTICA_DEV_TOKEN=dev-token \
AGENTICA_PORT=8080 \
LLM_BASE_URL=http://localhost:1234 \
LLM_MODEL=mistralai/ministral-3-14b-reasoning \
mvn compile exec:java
```

#### Browser

Open `http://localhost:8080/?token=dev-token`

If the backend runs inside WSL and LM Studio runs on Windows, set `LLM_BASE_URL` to the Windows host IP reachable from WSL, for example `http://172.23.64.1:1234`.

Hot-editing `ui/` JS/CSS files takes effect on the next browser refresh — no rebuild needed.

---

### Backend Integration Strategy

The backend serves both the REST/SSE API **and** the static UI files:

- `GET /` → redirects to `/index.html`
- `GET /index.html`, `/css/*`, `/js/*`, `/fonts/*` → served from `AGENTICA_UI_ROOT`
- `GET /settings`, `POST /settings` → user-configurable settings
- `GET /log/stream` → WebSocket endpoint; streams `agentica.log` in real time (authenticated)
- All chat/session API routes are under `/sessions`, `/runs`, `/health`
- `POST /sessions/:id/title` → rename a session; also used by the UI after first-turn title generation
- `GET /sessions/:id/agent-turns` → trajectory steps for each agent run
- `POST /sessions/:id/restart` → restart conversation from a specific user message (deletes all subsequent messages and data)

The bearer token is passed as a `?token=` URL query param, which `api.js` picks up automatically.

---

### Data Storage

SQLite database and `settings.json` are stored in the OS-appropriate app data directory (see `AppDirs.scala`).

---

## Project Structure

```text
agentica/
│
├── docs/
│   ├── FTRD.md                          # Functional & Technical Requirements Document
│   └── tasklist.md                      # Phase-by-phase implementation task list
│
├── launch.bat                           # Windows launcher for browser mode
├── launch.sh                            # Linux/macOS launcher for browser mode
│
├── ui/                                  # Browser UI served directly by the backend
│   ├── index.html
│   ├── log-viewer.html                  # Debug log viewer (WebSocket)
│   ├── css/
│   │   └── main.css
│   ├── fonts/
│   │   ├── NotoEmoji-Regular.ttf        # Emoji fallback font
│   │   └── Symbola.ttf                  # Symbol/emoji fallback font
│   ├── icons/
│   │   ├── menu.svg                     # Settings menu icon
│   │   ├── sidebar-toggle.svg           # Sidebar toggle icon
│   │   ├── copy.svg                     # Copy message icon
│   │   └── restart.svg                  # Restart conversation icon
│   └── js/
│       ├── main.js                      # App entry point, session init
│       ├── chat.js                      # Chat rendering, SSE client, agent step UI, title metadata display, copy/restart
│       ├── session.js                   # Session list, create/load/delete/rename
│       ├── settings.js                  # Settings modal, theme, LLM config
│       ├── api.js                       # HTTP wrapper with bearer-token injection
│       ├── log-viewer.js                # WebSocket log viewer client
│       └── marked.min.js               # Self-hosted Markdown renderer
│
├── backend/                             # Scala 3 local backend + static UI server (Maven)
│   ├── pom.xml
│   └── src/main/scala/agentica/
│       ├── BackendServer.scala          # Entry point: configure app, start Cask server
│       ├── DesktopLauncher.scala        # JavaFX WebView thin launcher
│       ├── settings/
│       │   └── SettingsStore.scala      # JSON-backed app settings persistence
│       ├── server/
│       │   ├── Routes.scala             # Static UI, chat, sessions, stream, runs
│       │   └── Auth.scala               # Bearer-token middleware
│       ├── session/
│       │   ├── SessionStore.scala       # ScalaSQL: sessions CRUD
│       │   ├── MessageStore.scala       # ScalaSQL: messages CRUD
│       │   ├── RunStore.scala           # ScalaSQL: tool execution log
│       │   ├── AgentTurnStore.scala     # Persistence: agent turn trajectory (agent_turns table)
│       │   └── Models.scala             # Case classes: Session, Message, ToolRun, AgentTurn
│       ├── agent/
│       │   ├── AgentEngine.scala        # trait AgentEngine (pluggable)
│       │   ├── AgentLoop.scala          # Multi-iteration plan→act→observe loop with turn persistence
│       │   └── ContextManager.scala     # Sliding window, token budget, summarization
│       ├── shell/
│       │   ├── VirtualShell.scala       # run() entry point; dispatches via registry
│       │   ├── Tokenizer.scala          # Hand-written tokenizer (key=value, quoted strings)
│       │   ├── CommandAst.scala         # Single-command AST
│       │   ├── CommandRegistry.scala    # Central registry: dispatch + help + schemas
│       │   └── Presentation.scala       # Presentation layer: AgentResponse envelope
│       ├── tools/
│       │   ├── Tool.scala               # trait Tool[I, O] (validate / execute / render)
│       │   ├── files/
│       │   │   ├── FilesRead.scala
│       │   │   ├── FilesWrite.scala
│       │   │   ├── FilesSearch.scala
│       │   │   ├── FilesList.scala
│       │   │   └── FilesStat.scala
│       │   ├── memory/
│       │   │   ├── MemoryGet.scala
│       │   │   ├── MemorySet.scala
│       │   │   └── MemoryList.scala
│       │   ├── llm/
│       │   │   ├── LlmSummarize.scala
│       │   │   ├── LlmExtract.scala
│       │   │   └── LlmClassify.scala
│       │   └── doc/                     # Phase 3
│       │       ├── WordRead.scala
│       │       ├── WordAppend.scala
│       │       ├── PptRead.scala
│       │       ├── PptAddSlide.scala
│       │       └── PptToImages.scala    # Sealed soffice wrapper
│       ├── llm/
│       │   ├── LLMProvider.scala        # trait LLMProvider (stream / complete)
│       │   ├── OpenAIProvider.scala     # OpenAI-compatible provider (LM Studio)
│       │   ├── OllamaProvider.scala     # Ollama provider
│       │   └── LlamaCppProvider.scala   # Power-user local provider (Phase 3)
│       ├── permissions/
│       │   └── ScopeStore.scala         # Scoped grants: (tool-set, path-prefix, TTL)
│       ├── observability/
│       │   ├── TraceLogger.scala        # Structured JSON-lines logger with traceId
│       │   └── TokenAccounting.scala    # Per-call token/cost recording
│       └── platform/
│           └── AppDirs.scala            # OS-specific data dir resolution
```

### Key structural notes

- **`ui/api.js`** — only file that knows the bearer token and HTTP base URL; all other JS calls through it.
- **`backend/BackendServer.scala`** — owns runtime configuration, including HTTP port, bind host, LLM provider, data directory, and `AGENTICA_UI_ROOT`.
- **`backend/server/Routes.scala`** — serves both static browser assets and authenticated REST/SSE APIs.
- **`backend/shell/`** — virtual shell is a self-contained package: `Tokenizer` → `CommandAst` → `CommandRegistry` dispatch → `Presentation` envelope. Each stage is independently unit-testable.
- **`backend/tools/`** — one file per tool, grouped by action family. `CommandRegistry` is the only place that enumerates the full tool list.
- **`backend/agent/`** — `AgentEngine` trait keeps the loop swappable; `ContextManager` evolves independently of loop control flow. `AgentLoop` records full reasoning trajectories (`AgentTurnStore`) and emits SSE step events for live UI rendering.
- **`backend/session/AgentTurnStore`** — each completed agent run persists its `thinking` and `tool_call` steps as a JSON column in `agent_turns`; the frontend fetches these on reload to reconstruct the collapsible step view.
- **`ui/js/chat.js`** — chat rendering, SSE streaming, live agent step UI (`onIteration`/`onToolStart`/`onToolResult`), history reload with interleaved agent turns, Markdown rendering via `marked.js`.
- **`GET /log/stream`** — WebSocket endpoint (not SSE); enables clean connection lifecycle events so the background polling thread shuts down when the viewer tab closes.
- **Desktop mode** — `DesktopLauncher` is a JavaFX WebView thin launcher that loads the same backend-served UI. It requires the backend to be running separately (Phase 1.5A). Automatic backend startup is planned for Phase 1.5B.

---

## Known Issues

- **Emoji rendering in JavaFX WebView on WSL/Linux** — Some emoji and symbol characters may not render correctly in the JavaFX WebView on WSL or Linux due to limited font support. Local fallback fonts (`NotoEmoji-Regular.ttf`, `Symbola.ttf`) are bundled and served to improve coverage, but full emoji rendering depends on the host OS font stack. Native Windows typically renders better.
- **JavaFX classpath warning** — `Unsupported JavaFX configuration: classes were loaded from 'unnamed module'` appears at startup because JavaFX is on the classpath rather than the module path. This is cosmetic and does not affect functionality.
