# Backend Tests

This directory contains the backend unit/integration tests and evaluation harnesses.

## Test categories

| Directory | Purpose |
| --- | --- |
| `agentica/agent` | Core agent loop tests — turn scheduling, tool-call parsing, context management, and golden-scenario workflows. |
| `agentica/doc` | Document rendering tests — PDF page rendering and DOCX/PPTX/PDF tool detection. |
| `agentica/llm` | LLM provider tests — OpenAI-compatible request formatting and response streaming. |
| `agentica/session` | Persistence-layer tests — in-memory stores for sessions, messages, runs, and agent turns. |
| `agentica/settings` | Settings store tests — JSON persistence for user-configurable backend settings. |
| `agentica/shell` | Utility tests — tokenization and filesystem path sandboxing. |
| `agentica/tools/files` | File-tool tests — scratchpad handling, sandboxing, and multi-tool chaining. |
| `agentica/testutil` | Shared test utilities — eval harness, eval suite base class, golden-scenario runner, scripted LLM stubs. |
| `agentica/misctests` | Ad-hoc/manual test drivers — not run by `mvn test` (most are standalone `object`s with a `main` method). |
| `agentica/eval` | Evaluation suites — see [Evaluation tests](#evaluation-tests) below. |

## Running tests

```bash
cd backend
mvn test
```

A plain `mvn test` runs the standard unit/integration suite and does **not** execute the evaluation tests. Those are opt-in (see below).

## Evaluation tests

Evaluation suites live under `src/test/scala/agentica/eval` and test the agent against real documents using external LLM/VLM providers. They are intentionally excluded from the default test run.

### Why are they separate?

- They call out to real model endpoints, which may be slow, rate-limited, or unavailable.
- They require a provider configuration file.
- Results are meant for human inspection rather than binary pass/fail assertions.

### Configuration file

Create an active configuration from the example file:

```bash
cp src/test/resources/eval-config.json.example src/test/resources/eval-config.json
```

Edit `src/test/resources/eval-config.json` to point at your endpoints and models. The file is a JSON array of provider sweep entries:

```json
[
  {
    "label": "local-qwen-vlm",
    "llmBaseURL": "http://localhost:1234/v1",
    "llmModel": "mistralai/ministral-3-14b-reasoning",
    "llmAPIKey": "lm-studio",
    "vlmBaseURL": "http://localhost:1234/v1",
    "vlmModel": "qwen2.5-vl-7b-instruct",
    "vlmAPIKey": "lm-studio",
    "judgeBaseURL": "http://localhost:1234/v1",
    "judgeModel": "claude-3-5-sonnet",
    "judgeAPIKey": "lm-studio",
    "pauseBetweenPhasesMs": 30000
  }
]
```

Fields:

- `label` — display name for this provider sweep entry.
- `llmBaseURL`, `llmModel`, `llmAPIKey` — primary LLM (required).
- `vlmBaseURL`, `vlmModel`, `vlmAPIKey` — optional VLM; if omitted the primary LLM is used for vision tasks.
- `judgeBaseURL`, `judgeModel`, `judgeAPIKey` — optional separate judge LLM; if omitted the primary LLM is used as the judge.
- `pauseBetweenPhasesMs` — optional pause (ms) between answer generation and judging so you can unload the answer model and load the judge model on the same server (e.g., LM Studio).

You can also point at a config file outside the repo by setting the environment variable or Java system property `PDF_EVAL_CONFIG`.

### Running an evaluation test

All commands below assume you are in the `backend/` directory (where the `pom.xml` lives):

```bash
cd backend
```

The project uses `scalatest-maven-plugin`, so select the suite with `-Dsuites`:

```bash
mvn test -Dsuites=agentica.eval.PDFEvalTest
```

If you placed the config at `src/test/resources/eval-config.json`, it will be picked up automatically. Otherwise set the path:

```bash
PDF_EVAL_CONFIG=/path/to/eval-config.json mvn test -Dsuites=agentica.eval.PDFEvalTest
```

If you prefer to run from the repository root, add `-pl backend`:

```bash
PDF_EVAL_CONFIG=/path/to/eval-config.json mvn -pl backend test -Dsuites=agentica.eval.PDFEvalTest
```

### Session types

Each provider in the config array can include a `"sessionTypes"` array that controls how questions are asked for that provider:

- **`"all-questions-per-session"`** — all questions are asked in one session as follow-up questions, with conversation history accumulating (default if omitted).
- **`"all-questions-per-session-shuffled"`** — the same shared-session strategy, but questions are deterministically reordered (fixed seed `42`) to test robustness to question ordering.
- **`"one-question-per-session"`** — each question is asked in an independent session with no prior conversation history.

You can combine modes per provider, e.g. `"sessionTypes": ["all-questions-per-session", "all-questions-per-session-shuffled", "one-question-per-session"]`. Each gets its own output subfolder.

### Answer → Judge workflow

Pipeline in one JVM for each provider:

1. For each session type: convert PDF → Markdown, score it, and generate all answers.
2. **Once** all session types have their answers, if the judge endpoint is a local LM Studio server, unload every loaded model and pause for `pauseBetweenPhasesMs` ms (defaults to 30 000 ms for local endpoints if not specified).
3. Judge all stored answers across all session types with the configured `judgeProvider`.

This means only **one** model swap per provider, regardless of how many session types are configured. Generated answers and judge results are persisted as JSON in the sweep directory described below.

### Output files

For every PDF run, the harness creates a temporary root directory (printed at the start) and lays out artifacts like this:

```
/tmp/pdf-eval-123456/
└── IT Support Analyst - India/
    ├── IT Support Analyst - India.pdf
    └── local-qwen-vlm/
        ├── multiple/
        │   ├── IT Support Analyst - India.pdf
        │   ├── IT Support Analyst - India.md
        │   ├── questions.json
        │   └── summary.json
        └── single/
            ├── IT Support Analyst - India.pdf
            ├── IT Support Analyst - India.md
            ├── questions.json
            └── summary.json
```

`questions.json` is an array of objects, each containing:

- `question`
- `category` — `"direct"` or `"rephrase"`
- `referenceAnswer`
- `actualAnswer` — the agent's final answer (last iteration only)
- `thoughts` — reasoning and intermediate text from earlier iterations
- `toolCalls` — ordered array of raw tool call commands
- `toolCounts` — map of tool name → number of calls
- `judgeScore`
- `judgeRationale`

`summary.json` contains the Markdown score breakdown, the average judge score, the total tool counts across all questions, and a reference to `questions.json`.

### Adding a new evaluation suite

1. Extend `agentica.testutil.EvalSuite`.
2. Add the `@DoNotDiscover` annotation to the concrete class so it is not auto-run.
3. Call `loadProviderConfigs()` to read the active provider sweep.
4. Add any required question/resource JSON files under `src/test/resources/files`.

### How the default skip works

`EvalSuite` subclasses annotated with `@DoNotDiscover` are not discovered during a plain `mvn test` run. When selected explicitly, the suite loads the provider config; if none is found, the tests are canceled with a clear message rather than failing.
