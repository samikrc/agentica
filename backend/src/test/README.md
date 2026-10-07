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
    "pauseBetweenPhasesMs": 30000,
    "reuseMarkdownCache": true
  }
]
```

Fields:

- `label` — display name for this provider sweep entry.
- `llmBaseURL`, `llmModel`, `llmAPIKey` — primary LLM (required).
- `vlmBaseURL`, `vlmModel`, `vlmAPIKey` — optional VLM; if omitted the primary LLM is used for vision tasks.
- `judgeBaseURL`, `judgeModel`, `judgeAPIKey` — optional separate judge LLM; if omitted the primary LLM is used as the judge.
- `pauseBetweenPhasesMs` — optional pause (ms) between answer generation and judging so you can unload the answer model and load the judge model on the same server (e.g., LM Studio).
- `reuseMarkdownCache` — optional boolean (default `true`); when `true`, a matching content-addressed Markdown cache entry skips PDF rendering and VLM transcription. Set `false` to force a fresh conversion.

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

### Markdown cache

PDF → Markdown conversion is the slowest part of an eval run (page rendering plus one VLM call per page). The harness therefore keeps a persistent, **content-addressed** cache at:

```
~/.local/state/Agentica/evals/.markdown-cache/<key>.md
```

The cache key is a SHA-256 over:

- the SHA-256 of the PDF bytes (filename alone is **not** used),
- the VLM model actually used for conversion,
- the image-enrichment setting, and
- a pipeline version constant (bumped when rendering/prompting changes).

Because the key covers the PDF bytes and the converter model, two files that happen to share a name but differ in content — or the same file converted with a different VLM model — never collide. The VLM endpoint URL is intentionally not part of the key, so switching between equivalent local endpoints (e.g. `localhost` vs an IP address) for the same model does not invalidate the cache.

`reuseMarkdownCache` (per-provider, default `true`) controls reads only:

- `true`, cache hit → the stored Markdown is loaded and the VLM is never called.
- `true`, cache miss → the PDF is converted normally and the resulting Markdown is written to the cache.
- `false` → the PDF is always converted and the cache entry is refreshed, so a forced re-run still updates the cache for subsequent runs.

Cache use does **not** change the workspace layout: whichever Markdown was produced is still copied into every session-type directory, so each session remains an independent sandbox rooted at its own folder.

Whether a run reused or regenerated Markdown is recorded in `manifest.json` under `markdownCache`:

```json
{
  "markdownCache": {
    "enabled": true,
    "hit": false,
    "key": "sha256-of-key-material",
    "pdfSha256": "sha256-of-pdf-bytes",
    "source": "/…/evals/.markdown-cache/<key>.md"
  }
}
```

The cache directory can be relocated with the `AGENTICA_MARKDOWN_CACHE_DIR` system property or environment variable (useful for tests and shared CI caches).

### Output files

For every PDF run, the harness creates a persistent root directory under
`~/.local/state/Agentica/evals/pdf-eval-YYYYMMDD-HHMMSS` and lays out artifacts like this:

```
~/.local/state/Agentica/evals/pdf-eval-20261003-002836/
└── IT Support Analyst - India/
    └── valar-qwen/
        ├── IT Support Analyst - India.pdf
        ├── manifest.json
        ├── all-questions-per-session/
        │   ├── IT Support Analyst - India.md
        │   ├── questions.json
        │   └── summary.json
        └── one-question-per-session/
            ├── IT Support Analyst - India.md
            ├── questions.json
            └── summary.json
```

`manifest.json` records the models, endpoints, and session modes used for that provider sweep (no API keys are written).  It lets reporting tools recover which LLM/VLM/judge were used without re-reading the original provider config.

`questions.json` is an array of objects, each containing:

- `question`
- `category` — `"direct"` or `"rephrase"`
- `referenceAnswer`
- `actualAnswer` — the agent's final answer (last iteration only)
- `thoughts` — reasoning and intermediate text from earlier iterations
- `toolCalls` — ordered array of raw tool call commands
- `toolCounts` — map of tool name → number of calls
- `status` — `answered`, `timeout`, or `error`
- `attempts` — `1` normally, `2` if a timeout triggered an immediate retry
- `judgeStatus` — `judged`, `judge-timeout`, `judge-error`, or `skipped`
- `verdict` — `correct`, `partial`, or `wrong` (when judged)
- `judgeScore` — numeric score derived from `verdict` for averaging
- `judgeRationale`

`summary.json` contains the Markdown score breakdown, the average judge score (over judged answers only), verdict counts, status counts, retry counts, total tool counts, and a reference to `questions.json`.

### Aggregating results across runs

`backend/src/test/scala/agentica/eval/eval-report.sc` is a standalone Scala-CLI script that scans all persisted eval run folders and prints a summary for each sweep, including the models used and the same statistics shown in `summary.json`:

```bash
scala-cli backend/src/test/scala/agentica/eval/eval-report.sc

# or point it at a custom evals root:
scala-cli backend/src/test/scala/agentica/eval/eval-report.sc /path/to/evals
```

### Adding a new evaluation suite

1. Extend `agentica.eval.EvalSuite`.
2. Add the `@DoNotDiscover` annotation to the concrete class so it is not auto-run.
3. Call `loadProviderConfigs()` to read the active provider sweep.
4. Add any required question/resource JSON files under `src/test/resources/files`.

### How the default skip works

`EvalSuite` subclasses annotated with `@DoNotDiscover` are not discovered during a plain `mvn test` run. When selected explicitly, the suite loads the provider config; if none is found, the tests are canceled with a clear message rather than failing.
