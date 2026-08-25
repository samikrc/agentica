# Agentica — AI Assistant (Scala backend + browser UI)

## Running Agentica

The UI is served directly by the backend — just a JVM fat-jar + any browser.

### Quick start (Linux / macOS)
For dev:
```
AGENTICA_DEV_TOKEN=dev-token AGENTICA_PORT=8080 LLM_BASE_URL=http://172.23.64.1:1234 LLM_MODEL=mistralai/ministral-3-14b-reasoning mvn compile exec:exec
```

The backend's own default port is `11211`; the examples below explicitly set `AGENTICA_PORT=8080` for convenience.

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

Most configuration lives in the **Settings** modal (gear/menu icon in the sidebar). Settings are persisted to `settings.json` in the OS app-data directory, next to the SQLite database.

#### Settings Modal Fields

The modal is organized into tabs:

- **General**
  - **Theme**: `light` or `dark`
  - **Show status line**: toggles the status bar at the bottom of the chat pane
  - **Debug mode**: saves VLM input images next to the source document for inspection
- **LLM**
  - **Server URL**: OpenAI-compatible LLM server base URL
  - **API Key**: Bearer token for hosted endpoints; leave empty for local LM Studio
  - **Model**: Model identifier sent to the LLM server
  - **API Mode**: `chatcompletions` (default, stateless) or `responses` (stateful)
- **VLM** (optional; when empty the primary LLM is used for vision calls)
  - **Server URL**: Vision LLM server base URL
  - **API Key**: Bearer token for the VLM server
  - **Model**: Vision model identifier
  - **Parallel page transcription**: enable concurrent VLM calls for document pages
  - **Threads**: concurrency limit (1–32) for parallel transcription

Saved settings take effect immediately for future agent runs without restarting the backend.

#### Environment Variables

Environment variables are read once at startup and used as fallbacks or overrides:

| Variable | Default | Purpose |
|---|---|---|
| `AGENTICA_PORT` | `11211` (backend) / `8080` (launch scripts) | HTTP port the backend listens on |
| `AGENTICA_DEV_TOKEN` | `dev-token` | Bearer token for browser/dev mode |
| `AGENTICA_UI_ROOT` | `../ui` (relative to jar) | Path to the `ui/` folder |
| `LLM_PROVIDER` | `openai` | Primary LLM provider type: `openai` or `ollama` |
| `LLM_BASE_URL` | `http://localhost:1234` | LM Studio / Ollama base URL (launch script / initial default only) |
| `LLM_MODEL` | `mistralai/ministral-3-14b-reasoning` | Model name (launch script / initial default only) |
| `LLM_API_KEY` | `lm-studio` | Fallback API key when the settings API key is empty |
| `VLM_API_KEY` | `lm-studio` | Fallback API key when the settings VLM API key is empty |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama server URL when `LLM_PROVIDER=ollama` |
| `OLLAMA_MODEL` | `llama3.2` | Default Ollama model |
| `AGENTICA_DESKTOP_URL` / `agentica.desktop.url` | `http://127.0.0.1:8080/?token=dev-token` | URL the JavaFX desktop shell loads |

Environment-supplied `LLM_BASE_URL` / `LLM_MODEL` are only used to seed the initial settings file; once settings have been saved, the persisted values take precedence. Use the Settings modal to change them permanently.

## UI Features

- **Menu dropdown** (top-left): Settings, Debug Log
- **Sidebar**: Session list with create, rename (double-click or title button), delete, and select
- **Chat pane**
  - **Dynamic session titles**: Sessions are automatically titled based on the first agent response
  - **Collapsible agent steps**: Each assistant response shows the plan→act→observe iterations, thinking text, tool calls, and tool results
  - **Live tool progress**: Long-running tools stream progress updates inside the active step
  - **Stop button**: The Send button becomes Stop while the agent is running; clicking it cancels the active run
  - **Token usage**: A chart icon on assistant messages reveals tokens in/out, total latency, and number of LLM turns for that run
  - **Message actions**:
    - **Copy**: Copy message text to clipboard
    - **Restart**: Restart conversation from any user message (deletes all subsequent messages and data)
- **New session modal**: Name the session and optionally set a working folder (with a folder picker on supported browsers)
- **Rename session modal**: Edit the session title inline
- **Permission modal**: When a sensitive tool needs access, choose Deny / Allow once / Allow for session / Allow always
- **Settings modal**: Tabbed UI (General, LLM, VLM) with theme, status line, debug mode, API mode, and separate VLM configuration
- **Theme**: Light and dark modes, applied immediately and persisted
- **Debug log viewer**: Opens `/log-viewer.html` in a new tab/window with a live WebSocket tail of `agentica.log`
- **Sidebar toggle**: Collapse/expand the session list on narrow screens

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
- `GET /index.html`, `/css/*`, `/js/*`, `/fonts/*`, `/icons/*` → served from `AGENTICA_UI_ROOT`
- `GET /settings`, `POST /settings` → user-configurable settings; changes trigger provider rebuild in `BackendServer`
- `GET /health` → smoke check returning `{"status":"ok"}`

#### Sessions
- `GET /sessions` → list all sessions (most-recent first)
- `POST /sessions` → create a new session `{title, model, rootPath?}`
- `GET /sessions/:id` → fetch session metadata
- `DELETE /sessions/:id` → delete session and all its messages
- `POST /sessions/:id/title` → rename a session
- `POST /sessions/:id/restart` → restart conversation from a specific user message
- `GET /sessions/:id/messages` → fetch all messages
- `POST /sessions/:id/messages` → append user message and start an agent run (returns 202 with `runId`, `traceId`, `userMessageId`)
- `GET /sessions/:id/stream/:runId` → SSE stream of tokens and lifecycle events
- `GET /sessions/:id/agent-turns` → persisted trajectory steps for each agent run
- `GET /sessions/:id/token-usage` → token usage per LLM call for the session

#### Runs & permissions
- `DELETE /runs/:runId` → request cancellation of an in-progress run
- `POST /permissions/:runId` → resolve a permission grant (denied / once / session / always)

#### Log streaming
- `GET /log/stream` → WebSocket endpoint; streams `agentica.log` in real time (authenticated via `?token=`)

The bearer token is passed as an `Authorization: Bearer ...` header for most API calls; SSE/WebSocket endpoints fall back to a `?token=` URL query param, which `api.js` picks up automatically.

Settings changes via `POST /settings` take effect immediately: `BackendServer` rebuilds the LLM/VLM providers and passes them to `AgentLoop.updateProviders`, so no server restart is required.

---

## Data Storage

SQLite database and `settings.json` are stored in the OS-appropriate app data directory (see `AppDirs.scala`).

---

## Known Issues

- **Emoji rendering in JavaFX WebView on WSL/Linux** — Some emoji and symbol characters may not render correctly in the JavaFX WebView on WSL or Linux due to limited font support. Local fallback fonts (`NotoEmoji-Regular.ttf`, `Symbola.ttf`) are bundled and served to improve coverage, but full emoji rendering depends on the host OS font stack. Native Windows typically renders better.
- **JavaFX classpath warning** — `Unsupported JavaFX configuration: classes were loaded from 'unnamed module'` appears at startup because JavaFX is on the classpath rather than the module path. This is cosmetic and does not affect functionality.
