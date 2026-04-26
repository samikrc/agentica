# Agentica — AI Assistant (Scala backend + browser UI)

## Running Agentica

The UI is served directly by the backend — no Tauri, no Electron, no build step.
Just a JVM fat-jar + any browser.

### Quick start (Linux / macOS)

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

Edit the top of `launch.sh` / `launch.bat` to set:

| Variable | Default | Purpose |
|---|---|---|
| `LLM_BASE_URL` | `http://localhost:1234` | LM Studio / Ollama base URL |
| `LLM_MODEL` | `mistralai/ministral-3-14b-reasoning` | Model name |
| `AGENTICA_DEV_TOKEN` | `dev-token` | Bearer token for the API |
| `AGENTICA_PORT` | `8080` | HTTP port the backend listens on |
| `AGENTICA_UI_ROOT` | `../ui` (relative to jar) | Path to the `ui/` folder |

---

## Developer Notes

### Recommended dev setup (fast iteration)

#### Terminal 1 — Scala Backend

```bash
cd backend
AGENTICA_DEV_TOKEN=dev-token \
AGENTICA_PORT=8080 \
LLM_BASE_URL=http://172.23.64.1:1234 \
LLM_MODEL=mistralai/ministral-3-14b-reasoning \
mvn compile exec:java
```

#### Browser

Open `http://localhost:8080/?token=dev-token`

Hot-editing `ui/` JS/CSS files takes effect on the next browser refresh — no rebuild needed.

---

### Backend Integration Strategy

The backend serves both the REST/SSE API **and** the static UI files:

- `GET /` → redirects to `/index.html`
- `GET /index.html`, `/css/*`, `/js/*` → served from `AGENTICA_UI_ROOT`
- All API routes are under `/sessions`, `/runs`, `/health`

The bearer token is passed as a `?token=` URL query param, which `api.js` picks up automatically.

---

### Data Storage

SQLite database is stored in the OS-appropriate app data directory (see `AppDirs.scala`).

---

## Project Structure

```text
agentica/
│
├── docs/
│   └── FTRD.md                          # Functional & Technical Requirements Document
│
├── ui/                                  # Tauri frontend (vanilla HTML/CSS/JS, no build step)
│   ├── index.html
│   ├── css/
│   │   └── main.css
│   └── js/
│       ├── main.js                      # App entry point, session init
│       ├── chat.js                      # Chat rendering, SSE client
│       ├── session.js                   # Session list, create/load/delete
│       ├── debug.js                     # Debug pane (tool calls, iterations, latencies)
│       └── api.js                       # HTTP wrapper with bearer-token injection
│
├── tauri/                               # Tauri shell (Rust)
│   ├── Cargo.toml
│   ├── tauri.conf.json
│   └── src/
│       ├── main.rs                      # App entry: spawn sidecar, generate bearer token
│       └── sidecar.rs                   # Sidecar lifecycle: start/stop/port handshake
│
├── backend/                             # Scala 3 sidecar (Maven)
│   ├── pom.xml
│   └── src/main/scala/agentica/
│       ├── Main.scala                   # Entry point: read bearer token, start Cask server
│       ├── server/
│       │   ├── Routes.scala             # Cask routes (chat, sessions, stream, runs)
│       │   └── Auth.scala               # Bearer-token middleware
│       ├── session/
│       │   ├── SessionStore.scala       # ScalaSQL: sessions CRUD
│       │   ├── MessageStore.scala       # ScalaSQL: messages CRUD
│       │   ├── RunStore.scala           # ScalaSQL: tool execution log
│       │   └── Models.scala             # Case classes: Session, Message, ToolRun
│       ├── agent/
│       │   ├── AgentEngine.scala        # trait AgentEngine (pluggable)
│       │   ├── AgentLoop.scala          # Custom plan→act→observe loop (~300 LOC)
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
│       │   ├── LlmProvider.scala        # trait LlmProvider (stream / complete)
│       │   ├── OllamaProvider.scala     # Default local provider
│       │   ├── LlamaCppProvider.scala   # Power-user local provider (Phase 3)
│       │   └── OpenAiProvider.scala     # Cloud provider (Phase 3)
│       ├── permissions/
│       │   └── ScopeStore.scala         # Scoped grants: (tool-set, path-prefix, TTL)
│       ├── observability/
│       │   ├── TraceLogger.scala        # Structured JSON-lines logger with traceId
│       │   └── TokenAccounting.scala    # Per-call token/cost recording
│       └── platform/
│           └── AppDirs.scala            # OS-specific data dir resolution
│
├── scenarios/                           # Golden scenarios catalog (§15c of FTRD)
│   └── README.md
│
└── scripts/
    ├── package-windows.sh               # jlink + jpackage → Windows .msi
    └── dev-sidecar.sh                   # Run sidecar standalone for curl/browser dev
```

### Key structural notes

- **`ui/api.js`** — only file that knows the bearer token and HTTP base URL; all other JS calls through it.
- **`tauri/src/sidecar.rs`** — owns the port-handshake protocol: sidecar prints `PORT=<n>` to stdout on startup, Tauri reads it before opening the window.
- **`backend/shell/`** — virtual shell is a self-contained package: `Tokenizer` → `CommandAst` → `CommandRegistry` dispatch → `Presentation` envelope. Each stage is independently unit-testable.
- **`backend/tools/`** — one file per tool, grouped by action family. `CommandRegistry` is the only place that enumerates the full tool list.
- **`backend/agent/`** — `AgentEngine` trait keeps the loop swappable; `ContextManager` evolves independently of loop control flow.
- **`scenarios/`** — at repo root, tooling-agnostic; consumed by integration tests and CI.
