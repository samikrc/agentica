# Desktop AI Assistant (Tauri + Scala + ADK)

## Developer Notes

### Development vs Build Modes

The application uses two distinct workflows:

#### Dev Mode (Primary workflow)
Run:
    tauri dev

This:
- Launches the desktop app window
- Uses a live frontend (with hot reload)
- Connects to a locally running backend

You should use this mode for almost all development.

#### Build Mode (Packaging)
Run:
    tauri build

This:
- Produces platform-specific binaries (.exe, .app, etc.)
- Is used only for final testing and distribution

---

### Recommended Development Setup

Run the frontend and backend independently for fast iteration.

#### Terminal 1 — Scala Backend

    mvn compile
    mvn exec:java

- Starts backend server (e.g., http://localhost:8080)
- Full logs visible
- Fast restart cycle

#### Terminal 2 — Tauri App

    tauri dev

- Connects to backend via localhost
- UI reloads automatically on changes

---

### Backend Integration Strategy

During development:
- Backend runs as a standalone HTTP server
- Tauri connects via localhost

In production:
- Backend is bundled as a sidecar executable
- Tauri launches it automatically

---

### Configuration Requirements

Backend URL must be configurable:

- Development:
    http://localhost:8080

- Production:
    http://127.0.0.1:<dynamic-port>

Avoid hardcoding URLs to ensure flexibility across environments.

---

### Data Storage in Development

- Use a development-specific data directory
- Keep it separate from production data
- SQLite can be used normally

---

### Key Advantages of This Setup

- Fast iteration (no rebuild required)
- Independent debugging of backend and UI
- Cleaner architecture aligned with sidecar model
