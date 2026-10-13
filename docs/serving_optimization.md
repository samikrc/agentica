# Serving & Evaluation Optimization Notes

Consolidated tuning decisions for running Agentica against a local LM Studio
server — both the LMS-side model settings and the Agentica-side knobs that
affect accuracy, latency, and eval reproducibility.

---

## LM Studio server settings

### Context length

| Role | Recommended | Rationale |
|---|---|---|
| Agent LLMs | **32768** | Worst-case request ≈ 20K (8K history cap + ~8K tool results + system/output); 32K gives ~1.5–2× headroom |
| Judge LLM | **32768** | Same provider path |
| VLM (`lfm2.5-vl-3b`) | **16384** | Peak request ≈ 8–10K (prompt + page image tokens + Markdown output); model's native ceiling is 32K |

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
| `BODY_BUDGET_CHARS` | `15000` | `Presentation` — max chars per tool result body (~4K tokens) |
| `RenderDPI` | `150` | `PDFPageRenderer` — page rasterization DPI for VLM input |
| `MaxImageDimension` | `1200` | `PDFPageRenderer` — longest-side cap before VLM call; tuned via the `VLMCompareTest` sweep (see `VLM-Experiment-Summary.md`) |
| `vlmParallelism` | `4` | `AppSettings` / VLM settings tab — concurrent page transcriptions |
| `temperature` | `0.1` | `eval-config.json` — sent on every eval request |
| `questionTimeoutMs` | `150000` | `DocEvalTest` |

### Document conversion caveat

150 DPI → ~1275×1650 px per page, then downscaled to 1200 px longest side.
Dense financial tables at 6–7 pt fonts land at ~5 px effective height — the
known fragile zone for digit fidelity. If nifty50-style tables show digit
errors, sweep `maxImageDimension` 1600–2000 via `VLMCompareTest` before
changing the global default.

---

