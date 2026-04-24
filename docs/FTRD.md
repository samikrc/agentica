# AI Desktop Assistant (Claude Cowork-like) — Requirements & Architecture

## 1. Overview

This document defines the functional requirements, technical architecture, and key design decisions for building a cross-platform desktop AI assistant application similar to Claude Cowork.

The application will:
- Run as a desktop app (Windows, macOS, Linux)
- Use local and cloud LLMs
- Operate on user-selected local folders
- Provide chat-based and agent-driven workflows
- Maintain persistent sessions (chat history + context)

---

## 2. Core Design Principles

- **Local-first**: Data remains on user machine unless explicitly sent to cloud APIs  
- **Security-first**: All system actions are permission-gated  
- **Modular architecture**: UI, orchestration, and execution layers separated  
- **LLM-agnostic**: Support multiple providers (local + cloud)  
- **Extensible tools framework**

---

## 3. High-Level Architecture

```text
[ React UI (HTML/CSS/JS) ]
          ↓
[ Tauri Shell (Rust) ]
          ↓
[ Scala Backend (Sidecar) ]
   ├── Session Management
   ├── Agent Engine (ADK)
   ├── Tool Execution Layer
   ├── File System Access
   └── LLM Integration Layer (partial)
          ↓
[ LLM Providers ]
   ├── Local (Ollama, llama.cpp)
   └── Cloud (OpenAI, Anthropic)
```

---

## 4. Technology Stack

### Frontend
- Vanilla HTML, CSS and JS
- Streaming UI support  

### Desktop Layer
- Tauri  

### Backend (Sidecar)
- Scala 3  
- HTTP server - Li Haoyi's Cask  
- SQLite for persistence  

### Agent Engine
- Google Agent Development Kit (ADK) (Java, used via JVM interop from Scala)  

### LLM Integration
- Local: llama.cpp  
- Cloud: OpenAI / Anthropic APIs  

### Build & Packaging
- Maven for Scala backend
- GraalVM native-image (recommended)  

---

## 5. Functional Requirements

### 5.1 Chat & Interaction
- Chat interface with streaming responses  
- Multi-session support  
- Edit and regenerate responses  
- Session auto-titling  

### 5.2 Session Management
- Create, load, update, delete sessions  
- Maintain message history  
- Store tool execution logs  
- Attach files to sessions  

### 5.3 File System Integration
- User-selected folder access only  
- Read/write/search files  
- File indexing and chunking  

### 5.4 LLM Integration
- Switch between local and cloud models  
- Streaming responses  
- Prompt management  

### 5.5 Agent Capabilities
- Plan → act → observe loop  
- Tool calling (structured via command interface)  
- Multi-step task execution  

### 5.6 Context Management
- Sliding window context  
- Summarization of older messages  
- Retrieval (RAG) from local data  

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

- Session persistence (SQLite)  
- Context management (sliding window, summarization, RAG)  
- Model routing (local vs cloud)  
- Agent orchestration (via ADK)  
- Virtual shell runtime (command parsing + execution)  
- Tool execution with validation  
- File system access (sandboxed, no direct shell)  
- LLM API integration (connectors + streaming)  

---

## 8. Sidecar Pattern

- Scala backend runs as a separate executable  
- Tauri launches it at app startup  
- Communication via HTTP (localhost)  

### Benefits
- Language flexibility  
- Clean separation of concerns  
- Easier debugging  

---

## 9. Agent Engine Integration (ADK)

The system will use Google Agent Development Kit (ADK) as the agent orchestration layer.

### Responsibilities of ADK
- Agent loop (plan → act → observe)  
- Tool invocation interface  
- Model abstraction (partial)  

### Responsibilities outside ADK
- Context management (session trimming, summarization, RAG)  
- Model routing (local vs cloud, fallback strategies)  
- Security enforcement for tool execution  
- Streaming integration with UI  

### Integration Pattern

```text
UI → Scala backend → Context assembly
   → ADK agent invocation
      → ADK emits tool calls (run command)
         → Scala validates and executes tools
      → ADK produces response
   → Persist + stream to UI
```

---

## 10. Virtual Shell Runtime (Core Design)

Inspired by emerging agent design patterns, the system will implement a **virtual shell abstraction** where the agent interacts using command-like text, but execution is fully controlled and safe.

### Design Goals
- Align with LLM training (CLI-style interaction)  
- Avoid unsafe shell execution  
- Enable composability and discoverability  

### Key Concept

> The agent *feels* like it is executing shell commands, but actually invokes safe, typed Scala tools.

### Architecture

```text
[ ADK Agent ]
     ↓
run(command="...")
     ↓
[ Command Parser ]
     ↓
[ Execution Planner (Pipeline AST) ]
     ↓
[ Safe Tool Engine ]
     ↓
[ Text Output returned to agent ]
```

### Command Interface

Single exposed tool:

```text
run(command="read_file foo.txt | summarize")
```

### Command Language (DSL)

A constrained CLI-like syntax (not full bash):

```text
read_file path=foo.txt
search query="revenue" path=reports/
summarize
write_file path=out.txt
```

Optional pipeline support:

```text
read_file foo.txt | summarize | write_file out.txt
```

### Command Parsing

- Convert text → AST (pipeline of operations)  
- Implemented using Scala parser combinators or tokenizer-based parser  

### Tool Execution Model

- Each command maps to a typed tool  
- No shell execution or subprocess calls  

Example:

```text
ReadFile → Summarize → WriteFile
```

### Security Properties

- Strict tool whitelist  
- No arbitrary command execution  
- Path sandboxing enforced  
- No escape hatch to system shell  

### CLI Illusion Layer

To improve agent effectiveness:

- `--help` support for all commands  
- Rich error messages (suggestions, hints)  
- Optional exit codes  

Example:

```text
Error: File not found: foo.txt
Did you mean: data/foo.txt?
```

### Key Insight

> A deterministic execution system presented as a CLI-like interface maximizes both safety and LLM effectiveness.

## 11. Office Document Processing (Excel, Word, PowerPoint)

The system will support reading, writing, and updating Microsoft Office documents through **safe, native tools implemented in Scala/JVM**, avoiding arbitrary Python or shell execution.

### Design Principles

- No direct shell or arbitrary script execution  
- All operations exposed as **typed tools**  
- Prefer JVM-native libraries for determinism and safety  
- External binaries (if required) must be wrapped as **sealed tools**  

---

### 11.1 Excel Processing

**Library**: Apache POI (XSSF)

Capabilities:
- Read/write `.xlsx` files  
- Access sheets, rows, cells  
- Apply basic formatting (alignment, wrapping)  

#### Example Tool Commands

```text
excel_read path=... sheet="Sheet1"
excel_read_range path=... sheet="Sheet1" range="A1:C20"
excel_write_cell path=... sheet="Sheet1" row=1 col=1 value="Hello"
```

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

### 11.6 Intent-Level Abstractions

Instead of exposing only low-level operations, the system may provide higher-level commands:

```text
excel_update_table path=... operation="add column total = a + b"
```

These are translated internally into multiple deterministic operations.

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

### 12.4 OS-Level Isolation (Future)
- Linux: bubblewrap / namespaces  
- macOS: sandboxing / restricted execution  
- Windows: AppContainer / restricted tokens  

### 12.5 LLM Guardrails
- Treat file content as untrusted  
- Validate all tool actions  
- Never execute raw LLM output  

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

### Example Tools
- ReadFile  
- WriteFile  
- SearchFiles  
- CallLLM  
- ExcelRead / ExcelWrite  
- WordEdit  
- PPTProcess  

---

## 15. Streaming Architecture

1. UI sends request  
2. Backend assembles context  
3. ADK agent invoked  
4. LLM response streamed token-by-token  
5. Tokens forwarded to UI in real-time  
6. Final response persisted to session store  

### Notes
- Support partial rendering in UI  
- Handle cancellation/interruption  
- Ensure final consistency before persistence  

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
| UI | HTML/CSS/JS (React) |
| Desktop Framework | Tauri |
| Backend | Scala sidecar |
| Communication | HTTP (localhost) |
| Agent Engine | Google ADK |
| Execution Model | Virtual shell + safe tools |
| Storage | SQLite |
| LLM Support | Local + Cloud |
| Office Handling | Apache POI + sealed tools |
| Security | Multi-layer sandboxing |
| Packaging | Native binaries (GraalVM) |

---

## 18. Key Insight

> Sandbox the *effects* of the LLM, not the LLM itself.  

> Deterministic execution + probabilistic reasoning = safe and powerful agents  

---

## 19. Implementation Phases

### Phase 1 (MVP)
- Basic chat UI  
- SQLite session storage  
- LLM integration (local + cloud)  

### Phase 2
- Context management  
- ADK agent integration  
- Basic tool execution  

### Phase 3
- Virtual shell runtime  
- File system tools  
- Office document tools  

### Phase 4
- RAG (file indexing + retrieval)  
- Advanced workflows  
- Streaming improvements  

### Phase 5
- OS-level sandboxing  
- Plugin ecosystem  
- Multi-agent orchestration  

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