# Serving & Evaluation Optimization Notes

Consolidated tuning decisions for running Agentica against a local LM Studio
server — both the LMS-side model settings and the Agentica-side knobs that
affect accuracy, latency, and eval reproducibility.

---

## LM Studio server settings

### Context length

| Role | Recommended | Rationale |
|---|---|---|
| Agent LLMs | **40960** | History is capped at 8K tokens, but **tool-result turns are not budgeted and accumulate for the whole run**: with `BODY_BUDGET_CHARS = 15000` (~3.75K tokens each), 10 iterations of full-budget results exceed 32K before history is added. 40K covers the realistic worst case; the durable fix is budgeting tool-result turns (see note below). |
| Judge LLM | **40960** | Same provider path |
| VLM (`lfm2.5-vl-3b`) | **16384** | Peak request ≈ 8–10K (prompt + page image tokens + Markdown output); model's native ceiling is 32K |

**Why tool results dominate.** `AgentLoop` appends every tool result of the run to
`toolResultTurns` and sends the whole list on each iteration; only *history* is capped by
`contextBudgetTokens`. Before Oct 2026 each large result was ~2K tokens, so the old "~8K
tool results" assumption held. `BODY_BUDGET_CHARS` was then raised to 15,000 for eval
quality (see `agent_loop_runtime.md` §4.5), roughly doubling the per-result cost. Two
options: raise context (done above, cheap at these model sizes) or budget tool-result
turns — keep the newest N results and replace older ones with a one-line marker so the
model knows something was elided.

Do **not** set context to the model maximum (e.g. Qwen's 262144): the KV cache
won't fit on a 12 GB card, and attention cost grows with window size. Verified
empirically — `qwen3.5-9b-mtp` at 262144 context averaged 25.2 s/question; at
32768 (with max draft tokens 6) the same eval averaged 5.4 s/question, a 4.7×
speedup with no quality loss (1.00 → 0.98 avg judge score, within noise).

### Sampling

- **Eval runs: temperature 0.1**, sent explicitly by the harness
  (`eval-config.json → "temperature"`). Applied to agent, VLM, and judge calls.
  LMS server default of 1.0 produced large run-to-run score swings
  (Ministral: 0.20–0.95 on identical setups).
- Interactive app sends no temperature → uses server default.

### GPU offload

Set GPU Offload to **all layers** for every model on a 12 GB card. Any layer on
CPU costs several × decode speed. Approximate Q4/Q6 weights: 9B ≈ 5–6 GB,
12–14B ≈ 7–9 GB, e4b ≈ 3–4 GB, gpt-oss-20b (judge) ≈ 12 GB (spills some layers
to CPU — judging is slower; expected, not a bug).

### Speculative decoding (Qwen MTP)

- `Speculative Decoding: MTP` — enabled.
- `Max draft tokens: 6` (default 3 is conservative; draft tokens are verified
  against the target model so more drafts never hurt correctness — only wasted
  compute if rejected).
- `Draft probability: 0` = draft unconditionally (correct for MTP).

### Other

- **Flash Attention: on** — free speed/VRAM win.
- **Keep Model in Memory / Try mmap(): on.**
- **K/V cache quantization:** optional `q8_0` if VRAM-constrained at larger
  contexts; skip at 16–32K.
- **Max concurrent predictions:** default fine for single-user.

---

## Agentica-side knobs

| Knob | Value | Where |
|---|---|---|
| `contextBudgetTokens` | `8000` | `AppSettings` — cap on *history* messages only (newest-first); tool results are outside the budget |
| `BODY_BUDGET_CHARS` | `15000` | `Presentation` — max chars per tool result body (~3.75K tokens); raised from 8000 for eval quality, see `agent_loop_runtime.md` §4.5 |
| `WorkspaceIndexer.DefaultBudgetMs` | `2500` | `WorkspaceIndexer` — per-search refresh budget; the first search in a fresh workspace is slower and may report `index: partial` |
| `MaxFusedHits` | `200` | `FilesSearch` — ceiling on the fused result list (rendered as `200+`) |
| `MaxScanFiles` / `MaxScanBytes` | `2000` / `50 MB` | `FilesSearch` — exact-arm scan budget per call |
| `ChunkLines` / `OverlapLines` | `60` / `10` | `Chunker` — chunk size and overlap for the FTS5 index |
| `TextExtractor.MaxFileBytes` | `2 MB` | `TextExtractor` — largest file considered for indexing |
| `RenderDPI` | `150` | `PDFPageRenderer` — page rasterization DPI for VLM input |
| `MaxImageDimension` | `1200` | `PDFPageRenderer` — longest-side cap before VLM call; tuned via the `VLMCompareTest` sweep (see `VLM-Experiment-Summary.md`) |
| `vlmParallelism` | `4` | `AppSettings` / VLM settings tab — concurrent page transcriptions |
| `temperature` | `0.1` | `eval-config.json` — sent on every eval request |
| `questionTimeoutMs` | `150000` | `DocEvalTest` |

### Retrieval indexing and eval reproducibility

Refresh-on-search makes the *first* search in a workspace different from later ones: it
pays the indexing cost and can return `index: partial`. Doc-eval creates a fresh workspace
per sweep, so question 1 runs against a colder index than question 8 — a source of
run-to-run and ordering variance on top of the temperature-0.1 work above.

- **Warm the index before generating answers**: one throwaway `files_search` on a document
  keyword (or an explicit indexer call in the harness) so every question sees a complete
  index.
- **Record index state** (`complete` vs `partial`, pending count) in `summary.json`
  alongside `llmUsage`.
- **PDF-heavy workspaces need several searches to converge**: PDFBox text extraction
  measures ~150–200 ms/page (`agent_loop_runtime.md` §7.5.12), so a 50-page document costs
  8–10 s of indexing. Text files are indexed first; raw documents follow.
- **Pre-AG-31 baselines are not directly comparable**: `files_search` now returns ranked,
  paged pages, so tool-call and iteration counts shift by design. Re-baseline before
  comparing runs across the change.

### Document conversion caveat

150 DPI → ~1275×1650 px per page, then downscaled to 1200 px longest side.
Dense financial tables at 6–7 pt fonts land at ~5 px effective height — the
known fragile zone for digit fidelity. If nifty50-style tables show digit
errors, sweep `maxImageDimension` 1600–2000 via `VLMCompareTest` before
changing the global default.

---

