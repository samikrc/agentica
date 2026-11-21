# VLM Image Parameter Experimentation Summary

## Objective

Determine the optimal `DPI` (PDF rasterization resolution) and `maxImageDimension` (longest-side pixel cap after resize) settings for VLM-based image-to-Markdown transcription of PDF documents. The goal was to balance transcription quality against processing time, particularly for models with dynamic-resolution vision encoders that suffer from slow CPU-bound image preprocessing on large inputs.

## Background

- **DPI** controls the resolution at which PDF pages are rasterized to PNG images by `PDFPageRenderer` (Apache PDFBox). Higher DPI produces sharper images but larger pixel dimensions, which increases vision-encoder token count and preprocessing time.
- **`maxImageDimension`** caps the longest side of the rendered image via `PDFPageRenderer.resizeToMaxDimension`. Images are downscaled (preserving aspect ratio) so their longest side does not exceed this value. This helps in two distinct ways:
  1. **CPU-bound preprocessing**: For dynamic-resolution VLMs (e.g. Qwen2.5-VL), the image processor tiles/patchifies the image *before* it reaches the encoder — this step commonly runs on CPU regardless of whether the encoder itself runs on GPU. On high-DPI renders this preprocessing alone can take minutes, which is the dominant cost observed against LM Studio's local pipeline.
  2. **Encoder/LLM compute (GPU or CPU)**: More patches means more vision tokens, which increases encoder forward-pass compute and LLM context length. This cost scales with hardware (GPU handles it far faster than CPU) but is never free — capping `maxImageDimension` reduces it on any setup.
- **No-op resizing**: If `maxImageDimension` is greater than or equal to the actual rendered image's longest side at a given DPI, no resizing occurs. The sweep harness detects and skips these redundant combinations, reusing the previous result.

## Models Evaluated

All models were served locally via LM Studio on `http://192.168.2.126:1234`:

| Label | Model |
|---|---|
| `gemma-4-12b` | `google/gemma-4-12b` |
| `gemma-4-e4b` | `google/gemma-4-e4b` |
| `gemma-4-12b-qat` | `google/gemma-4-12b-qat` |
| `glm-4.6v-flash` | `zai-org/glm-4.6v-flash` |
| `ministral-3-14b` | `mistralai/ministral-3-14b-reasoning` |
| `qwen3.5-9b-mtp` | `qwen3.5-9b-mtp` |
| `qwen2.5-vl-7b-instruct` | `qwen2.5-vl-7b-instruct` |
| `lfm2.5-vl-3b` | `lfm2.5-vl-3b` |
| `lfm3-vl-2b` | `lfm3-vl-2b` (reasoning on) |
| `lfm3-vl-2b-nothink` | `lfm3-vl-2b` with `reasoning_effort: "none"` |

## Test Harness

The experiment was conducted using `VLMCompareTest.scala` in sweep mode (`--sweep` command-line flag).

### Parameters Swept

- **DPI values**: `50, 100, 150`
- **maxImageDimension values**: `1024, 1200, 1400, 1650`
- **Per-page timeout**: 3 minutes (180,000 ms)
- **PDFs tested**: `IT Support Analyst - India.pdf`, `Even OPD Policy Document.pdf`

### Loop Structure

```
providers (outer)
  → PDFs
    → DPI values
      → maxImageDimension values
```

Providers are the outermost loop so each local model loads once, processes all PDFs across all DPI/maxDim combinations, then unloads — minimising load/unload cycles.

### Optimisations

- **No-op skip**: For a given (PDF, DPI) combination, if `maxImageDimension >= rendered longest side`, the previous successful result is reused without re-calling the VLM.
- **Render caching**: Pages are rendered once per DPI and reused across all maxDim values for that DPI.
- **Timeout skip**: If a model times out on any (DPI, maxDim) combination, all remaining combinations for that model are skipped.
- **Pre-run cleanup**: Before the provider loop, all currently-loaded models on the LM Studio server are unloaded via `/v1/models` query + `/api/v1/models/unload` calls.
- **Per-combo artifacts**: each combination writes `<stem>_<label>_dpi<D>_max<M>.md` and `.pages.json`, and `results.json` is rewritten after every combination so an aborted run keeps its partial results.

### Metrics Collected

For each (PDF, provider, DPI, maxDim) combination:

- Total characters
- Total words
- Total processing time (seconds)
- Average time per page (seconds)
- Word count ratio (VLM words / reference words)
- Token overlap (fraction of reference vocabulary tokens present in VLM output)
- Heading count
- Table row count
- Non-empty page ratio
- Timeout/error status
- Failed page count (pages ending as `[Page N: transcription failed …]` placeholders)

**Per-page text fidelity** (`MarkdownScorer.scoreTextPages`), compared against each page's PDFBox text layer:

- Word recall / precision / F1 (multiset, counting repeats — catches dropped, invented, and duplicated text)
- Signed-number recall / precision, with the missing and extra numbers listed per page (so `-0.43%` vs `0.43%` is a mismatch)
- Order similarity (1 − word error rate): low values with high F1 indicate reordering rather than missing text

**Expected facts** (`MarkdownScorer.checkFacts`), when a `<stem>.facts.json` file sits next to the PDF:

- One graded outcome per fact: `pass-cell` (value in the expected table cell), `pass-line` (same line as its row label), `pass-terms` (page-level terms), `partial`, `wrong` (matches a listed known misreading), `misplaced` (on the page but not with its row), `missing`
- Strict = cell/terms passes; lenient also accepts line passes
- Per-`type` rollup (table, chart, diagram, footnote) and a cross-provider disagreement report listing every fact whose outcome differs between providers

All metrics are persisted to `results.json` in the work directory, rewritten after every completed combination so a crashed or aborted run keeps its partial results.

## Selection criteria

Document-level coverage (word ratio, token overlap) says whether *enough* text came out, not whether it is *correct*. A model can score 1.00 word ratio while misreading every number in a table. Choose a VLM using the metric families below, in this order:

1. **Chart and table correctness — expected facts (`FactsStrict` / `FactsLenient`).** The primary criterion for document work. Facts check printed values in their row/column cell (strict) or on their row's line (lenient), detect known misreadings (`wrong`, e.g. `47.35%` where `47.36%` is printed), distinguish a dash from a zero, and report misplaced values (present on the page but attached to the wrong row). A model that transcribes prose well but cannot keep a year matrix aligned is unusable for chart-heavy documents regardless of its other scores.
2. **Number fidelity (`NumR` / `NumP`).** Signed-number recall and precision against the PDF text layer. Recall catches dropped values; precision catches invented or duplicated ones. A large gap between word F1 and number recall means the model is paraphrasing or skipping dense numeric regions.
3. **Text fidelity (`WordF1`, `WordRecall`, `WordPrecision`) and order (`Order`).** Word F1 measures dropped, invented, and duplicated text. Order similarity (1 − word error rate) distinguishes *reordered* content from *missing* content: high F1 with low order is usually a multi-column or table-layout problem rather than lost text. Treat very low order on prose pages as a warning even when F1 looks fine.
4. **Structure detection (`Headings`, `TableRows`).** Presence of structure, not its correctness — a table transcribed with the wrong values still counts its rows. Useful mainly to spot models that emit unstructured blobs; once a model produces tables, judge it by facts and number fidelity instead.
5. **Throughput and reliability (`AvgPageS`, `FailedPages`, timeout status).** Wall-clock time per page decides whether a model is viable at document scale; `FailedPages` counts pages that exhausted retries. A model that times out at one DPI/maxDim combination is skipped for the rest of the sweep, so timeouts also silently reduce coverage — check the status column before trusting an aggregate.

Practical guidance:

- For **retrieval/QA over prose**, word F1 and order dominate; facts matter less.
- For **chart, table, or financial documents**, facts and number precision dominate — reject a model whose strict fact passes are materially below the best candidate even if its word F1 is higher.
- Compare models only at the **same DPI / maxDim**, and prefer the smallest configuration that keeps facts and number recall within noise of the best — larger images cost time on every page.
- Facts currently exist for one PDF (`nifty-50-whitepaper-2024.facts.json`); for the other documents use number fidelity plus manual spot-checks of tables and charts.

## Results and Decision

Based on the sweep results across all models and parameter combinations:

### Selected Configuration

| Parameter | Value |
|---|---|
| **DPI** | 150 |
| **maxImageDimension** | 1200 |
| **Model** | `lfm3-vl-2b` with reasoning disabled (`reasoning_effort: "none"`) |

`lfm3-vl-2b` replaced `lfm2.5-vl-3b` as the production VLM (`eval-config.json` sets `"vlmModel": "lfm3-vl-2b"`). Production transcription runs with reasoning off — see the LFM3 section below for why.

### LFM3 results (150 DPI / 1200 px)

Rows are in [Result Details](#result-details). Findings from that run:

- **Reasoning off is 2–4× faster at similar quality.** Per-page time drops from 23.8–38.2 s to 6.0–17.3 s on the prose documents. The reasoning-on variant never finished the whitepaper — a page exceeded the 180 s per-page timeout after ~930 s total — while reasoning off completed all 16 pages in 192 s.
- **Quality cost of disabling reasoning is small and document-dependent.** Word F1 is unchanged on IT Support and within 0.02–0.07 elsewhere; order similarity falls most on `ind_nifty50` (0.37 → 0.20) and word F1 0.94 → 0.87, i.e. the reasoning variant holds multi-column layout better. For retrieval-style use this is acceptable; for dense tabular pages it is a real regression.
- **Whitepaper facts (reasoning off): 44 strict / 47 lenient of 50.** Three facts fail strictly but pass leniently (value on the right line, wrong cell), and three fail both — see `results.json` `facts.results` for the per-fact outcomes. This is the first facts-based measurement of any model, so there is no cross-model baseline yet.
- **Number precision vs recall is the notable weakness.** `ind_nifty50` shows NumR 0.95 with NumP 0.78 — the model reproduces printed numbers but also emits numbers that are not in the text layer, consistent with chart value inference.

### DPI sensitivity (lfm3-vl-2b, reasoning on)

Grid run on the two prose documents before the reasoning-off measurements:

| PDF | DPI | MaxDim | WordF1 | NumR | NumP | Order | AvgPageS |
|---|---|---|---|---|---|---|---|
| IT Support Analyst - India | 50 | 1024 | 0.78 | 0.67 | 1.00 | 0.63 | 97.7* |
| IT Support Analyst - India | 100 | 1024 | 1.00 | 1.00 | 1.00 | 1.00 | 32.2 |
| IT Support Analyst - India | 100 | 1200 | 1.00 | 1.00 | 1.00 | 1.00 | 19.8 |
| IT Support Analyst - India | 150 | 1024–1650 | 1.00 | 1.00 | 1.00 | 1.00 | 23.0–27.6 |
| Even OPD Policy Document | 50 | 1024 | 0.99 | 0.89 | 1.00 | 0.56 | 30.8 |
| Even OPD Policy Document | 100 | 1024 | 1.00 | 0.89 | 1.00 | 0.55 | 25.7 |
| Even OPD Policy Document | 100 | 1200 | 1.00 | 0.89 | 1.00 | 0.56 | 15.9 |
| Even OPD Policy Document | 100 | 1400 | 1.00 | 0.89 | 1.00 | 0.55 | 21.4 |

\* includes model load. Timings in this grid were collected around a laptop hibernation: any measurement spanning the suspend is inflated, and one page in the Even OPD grid was aborted by the suspend (recorded as a timeout, after which the harness skipped the remaining combinations for that model). Quality metrics are unaffected; treat the timing column as indicative only.

Observations: **50 DPI loses numbers** (IT Support number recall 0.67) — 100 DPI is the floor for numeric documents. **Even OPD's order similarity stays at ~0.55 at every DPI**, which is a layout/reading-order property of that document, not a resolution problem.

### Rationale

> **Caveat:** the original decision below predates the per-page text-fidelity and expected-facts metrics. It was made from document-level word/token coverage, which cannot distinguish a correct transcription from a well-formatted misreading of a table. The LFM3 section above is the first facts-based measurement; it covers one model and one facts file.

- **150 DPI** provides the best text clarity for OCR-quality transcription. Lower DPI values (50, 100) resulted in degraded quality, particularly for small text and fine details.
- **1200 maxImageDimension** strikes the optimal balance between image quality and vision-encoder processing time. It is large enough to preserve the detail from 150 DPI renders while keeping the token count manageable for the LFM model's vision encoder.
- **`lfm2.5-vl-3b`** was the original selection based on overall performance across the test PDFs — combining good transcription quality (word count ratio, token overlap, heading/table detection) with reasonable processing speed. It has since been replaced by `lfm3-vl-2b` with reasoning disabled, which is faster and is the model the production config selects.

## Files

| File | Description |
|---|---|
| `VLMCompareTest.scala` | Test harness: fixed configuration or `--sweep` grid, provider/PDF filters, structured `results.json` |
| `VLMCompareOptionsTest.scala` | Unit tests for option parsing, image resizing, no-op reuse, and `results.json` shape |
| `MarkdownScorer.scala` | Scoring: document-level metrics, per-page text fidelity, expected-fact grading; standalone re-scoring entry point |
| `PDFPageRenderer.scala` | PDF rasterization with configurable DPI and `resizeToMaxDimension` |
| `PageVisionTranscriber.scala` | Core transcription logic with per-page timeout and retry support |
| `files/<stem>.facts.json` | Hand-verified expected facts for tables, charts, diagrams, and footnotes of one PDF |

## How to Reproduce

1. Ensure LM Studio is running and the target models are downloaded (the harness unloads and loads models itself via the LM Studio API).
2. Configure `providers`, `pdfPaths`, `dpiValues`, and `maxDimValues` in `VLMCompareTest.scala` as needed.
3. From `backend/`, run the harness as a standalone main:
   ```
   mvn test-compile exec:exec -Dexec.classpathScope=test \
     -Dexec.args="-Djava.awt.headless=true -Dorg.jboss.logging.provider=jdk -classpath %classpath agentica.misctests.VLMCompareTest --sweep"
   ```
   Fixed configuration instead of a grid: replace `--sweep` with `--dpi 150 --max-dimension 1200`.
   Select a subset: `--providers lfm2.5-vl-3b,gemma-4-12b-qat --pdfs whitepaper,nifty50`.
4. Review the summary table printed at the end of the run; `results.json`, per-page JSON, and Markdown outputs are in the printed work directory.
5. Re-score an existing run directory without calling any model: run `agentica.misctests.MarkdownScorer <work-dir>`.

## Result Details

Note: Models/combinations not listed in the table were not tested due to timeout. Rows measured before the text-fidelity and expected-facts metrics carry `N/A` in those columns. `Facts` is strict/lenient/total against the whitepaper's 50 facts; documents without a `.facts.json` show `N/A` there.

### Column reference

| Column | Definition |
|---|---|
| `PDF` | Source document (filename stem). |
| `Provider` | Label from `VLMCompareTest.providers`; the model id is in the [Models Evaluated](#models-evaluated) table. |
| `DPI` | Rasterisation resolution used to render each page to PNG. |
| `MaxDim` | Longest-side pixel cap applied after rendering; `MaxDim` ≥ the rendered longest side means no resize (identical image to a smaller cap). |
| `Chars` / `Words` | Characters and words in the generated Markdown (words = whitespace tokens longer than one character). |
| `TotalS` | Wall-clock transcription time for the whole document. `reused` = result copied from a no-op configuration; `N/A` = not measured (timeout/error). |
| `AvgPageS` | `TotalS` ÷ pages. Includes model load on the first page of a run. |
| `WordRatio` | Generated words ÷ reference words, capped at 1.00. Coverage only — says nothing about correctness. |
| `TokenOvl` | Fraction of the reference vocabulary that appears in the output. |
| `Headings` | Count of Markdown `#` heading lines. Presence check, not a correctness measure. |
| `TableRows` | Count of lines with two or more `\|` (Markdown table rows). A table with wrong values still counts. |
| `NonEmpty%` | Share of pages with ≥ 50 characters of output. |
| `WordF1` | Harmonic mean of word recall and precision against the page's PDF text layer, counting repeats (multiset). Penalises dropped, invented, and duplicated text. |
| `NumR` | Signed-number recall: share of reference numbers reproduced exactly (sign and `%` included). |
| `NumP` | Signed-number precision: share of output numbers that exist in the reference. Low precision with high recall means invented values. |
| `Order` | 1 − word error rate (Levenshtein over word sequences). Low values with high `WordF1` indicate reordering — usually multi-column layout — rather than missing text. |
| `FactsStrict` | Expected facts passing strictly (value in the right row/column cell, or all page-level terms present) out of the document's total facts. |
| `FactsLenient` | Also counts same-line matches (value on the row's line but not in an identifiable cell). |
| `FailedPages` | Pages that exhausted retries and remain as `[Page N: transcription failed …]` placeholders. |
| `Notes` | Status and qualifiers: `reasoning on`/`off`, `timeout`, `reused`, `partial-error`, or an error message. |

| PDF | Provider | DPI | MaxDim | Chars | Words | TotalS | AvgPageS | WordRatio | TokenOvl | Headings | TableRows | NonEmpty% | WordF1 | NumR | NumP | Order | FactsStrict | FactsLenient | FailedPages | Notes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| IT Support Analyst - India | gemma-4-12b | 50 | 1024 | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | timeout |
| IT Support Analyst - India | gemma-4-e4b | 50 | 1024 | 4140 | 526 | 40.4 | 20.2 | 0.74 | 0.60 | 1 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | gemma-4-e4b | 50 | 1200 | 4140 | 526 | 40.4 | 20.2 | 0.74 | 0.60 | 1 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | gemma-4-e4b | 50 | 1400 | 4140 | 526 | 40.4 | 20.2 | 0.74 | 0.60 | 1 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | gemma-4-e4b | 50 | 1650 | 4140 | 526 | 40.4 | 20.2 | 0.74 | 0.60 | 1 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | gemma-4-e4b | 100 | 1024 | 4743 | 627 | 31.6 | 15.8 | 0.88 | 0.88 | 2 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | gemma-4-e4b | 100 | 1200 | 4743 | 627 | 31.6 | 15.8 | 0.88 | 0.88 | 2 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | gemma-4-e4b | 100 | 1400 | 4743 | 627 | 31.6 | 15.8 | 0.88 | 0.88 | 2 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | gemma-4-e4b | 100 | 1650 | 4743 | 627 | 31.6 | 15.8 | 0.88 | 0.88 | 2 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | gemma-4-e4b | 150 | 1024 | 4255 | 566 | 32.8 | 16.4 | 0.80 | 0.82 | 0 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | gemma-4-e4b | 150 | 1200 | 4698 | 626 | 26.8 | 13.4 | 0.88 | 0.90 | 1 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | gemma-4-e4b | 150 | 1400 | 4866 | 643 | 21.6 | 10.8 | 0.91 | 0.92 | 4 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | gemma-4-e4b | 150 | 1650 | 4866 | 643 | 21.6 | 10.8 | 0.91 | 0.92 | 4 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | gemma-4-e4b | 50 | 1024 | 10096 | 1472 | 117.9 | 14.7 | 0.97 | 0.95 | 3 | 27 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | gemma-4-e4b | 50 | 1200 | 10096 | 1472 | 117.9 | 14.7 | 0.97 | 0.95 | 3 | 27 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | gemma-4-e4b | 50 | 1400 | 10096 | 1472 | 117.9 | 14.7 | 0.97 | 0.95 | 3 | 27 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | gemma-4-e4b | 50 | 1650 | 10096 | 1472 | 117.9 | 14.7 | 0.97 | 0.95 | 3 | 27 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | gemma-4-e4b | 100 | 1024 | 9734 | 1444 | 109.1 | 13.6 | 0.95 | 0.95 | 4 | 33 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | gemma-4-e4b | 100 | 1200 | 9701 | 1441 | 95.5 | 11.9 | 0.95 | 0.95 | 3 | 25 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | gemma-4-e4b | 100 | 1400 | 9453 | 1415 | 94.6 | 11.8 | 0.93 | 0.95 | 3 | 27 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | gemma-4-e4b | 100 | 1650 | 10456 | 1549 | 99.5 | 12.4 | 1.00 | 0.96 | 5 | 45 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | gemma-4-e4b | 150 | 1024 | 9832 | 1451 | 102.9 | 12.9 | 0.96 | 0.95 | 2 | 32 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | gemma-4-e4b | 150 | 1200 | 9697 | 1444 | 90.9 | 11.4 | 0.95 | 0.94 | 1 | 27 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | gemma-4-e4b | 150 | 1400 | 10228 | 1521 | 98.4 | 12.3 | 1.00 | 0.98 | 4 | 35 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | gemma-4-e4b | 150 | 1650 | 9251 | 1369 | 93.1 | 11.6 | 0.90 | 0.93 | 5 | 24 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | gemma-4-12b-qat | 50 | 1024 | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | timeout |
| IT Support Analyst - India | glm-4.6v-flash | 50 | 1024 | 4737 | 632 | 64.7 | 32.4 | 0.89 | 0.82 | 0 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | glm-4.6v-flash | 50 | 1200 | 4737 | 632 | 64.7 | 32.4 | 0.89 | 0.82 | 0 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | glm-4.6v-flash | 50 | 1400 | 4737 | 632 | 64.7 | 32.4 | 0.89 | 0.82 | 0 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | glm-4.6v-flash | 50 | 1650 | 4737 | 632 | 64.7 | 32.4 | 0.89 | 0.82 | 0 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | glm-4.6v-flash | 100 | 1024 | 5346 | 713 | 48.9 | 24.4 | 1.00 | 1.00 | 0 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | glm-4.6v-flash | 100 | 1200 | 5346 | 713 | 48.9 | 24.4 | 1.00 | 1.00 | 0 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | glm-4.6v-flash | 100 | 1400 | 5346 | 713 | 48.9 | 24.4 | 1.00 | 1.00 | 0 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | glm-4.6v-flash | 100 | 1650 | 5346 | 713 | 48.9 | 24.4 | 1.00 | 1.00 | 0 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | glm-4.6v-flash | 150 | 1024 | 5358 | 713 | 45.3 | 22.7 | 1.00 | 1.00 | 0 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | glm-4.6v-flash | 150 | 1200 | 5358 | 713 | 47.7 | 23.9 | 1.00 | 1.00 | 0 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | glm-4.6v-flash | 150 | 1400 | 5366 | 713 | 48.8 | 24.4 | 1.00 | 1.00 | 0 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | glm-4.6v-flash | 150 | 1650 | 5366 | 713 | 48.8 | 24.4 | 1.00 | 1.00 | 0 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | glm-4.6v-flash | 50 | 1024 | 10631 | 1513 | 150.5 | 18.8 | 1.00 | 1.00 | 0 | 40 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | glm-4.6v-flash | 50 | 1200 | 10631 | 1513 | 150.5 | 18.8 | 1.00 | 1.00 | 0 | 40 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | glm-4.6v-flash | 50 | 1400 | 10631 | 1513 | 150.5 | 18.8 | 1.00 | 1.00 | 0 | 40 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | glm-4.6v-flash | 50 | 1650 | 10631 | 1513 | 150.5 | 18.8 | 1.00 | 1.00 | 0 | 40 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | glm-4.6v-flash | 100 | 1024 | 10429 | 1517 | 176.5 | 22.1 | 1.00 | 1.00 | 1 | 40 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | glm-4.6v-flash | 100 | 1200 | 12129 | 1516 | 188.7 | 23.6 | 1.00 | 1.00 | 1 | 34 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | glm-4.6v-flash | 100 | 1400 | 13225 | 1524 | 228.0 | 28.5 | 1.00 | 1.00 | 0 | 28 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | glm-4.6v-flash | 100 | 1650 | 11405 | 1513 | 152.2 | 19.0 | 1.00 | 1.00 | 0 | 34 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | glm-4.6v-flash | 150 | 1024 | 10978 | 1509 | 153.4 | 19.2 | 0.99 | 1.00 | 1 | 43 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | glm-4.6v-flash | 150 | 1200 | 11959 | 1517 | 173.6 | 21.7 | 1.00 | 1.00 | 1 | 36 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | glm-4.6v-flash | 150 | 1400 | 10912 | 1526 | 204.6 | 25.6 | 1.00 | 1.00 | 0 | 37 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | glm-4.6v-flash | 150 | 1650 | 10902 | 1517 | 210.3 | 26.3 | 1.00 | 1.00 | 0 | 34 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | ministral-3-14b | 50 | 1024 | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | timeout |
| IT Support Analyst - India | qwen3.5-9b-mtp | 50 | 1024 | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | timeout |
| IT Support Analyst - India | qwen2.5-vl-7b-instruct | 50 | 1024 | 5221 | 691 | 35.8 | 17.9 | 0.97 | 0.93 | 3 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | qwen2.5-vl-7b-instruct | 50 | 1200 | 5221 | 691 | 35.8 | 17.9 | 0.97 | 0.93 | 3 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | qwen2.5-vl-7b-instruct | 50 | 1400 | 5221 | 691 | 35.8 | 17.9 | 0.97 | 0.93 | 3 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | qwen2.5-vl-7b-instruct | 50 | 1650 | 5221 | 691 | 35.8 | 17.9 | 0.97 | 0.93 | 3 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | qwen2.5-vl-7b-instruct | 100 | 1024 | 5389 | 715 | 59.9 | 30.0 | 1.00 | 1.00 | 3 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | qwen2.5-vl-7b-instruct | 100 | 1200 | 5389 | 715 | 59.9 | 30.0 | 1.00 | 1.00 | 3 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | qwen2.5-vl-7b-instruct | 100 | 1400 | 5389 | 715 | 59.9 | 30.0 | 1.00 | 1.00 | 3 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | qwen2.5-vl-7b-instruct | 100 | 1650 | 5389 | 715 | 59.9 | 30.0 | 1.00 | 1.00 | 3 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | qwen2.5-vl-7b-instruct | 150 | 1024 | 5364 | 714 | 59.6 | 29.8 | 1.00 | 1.00 | 5 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | qwen2.5-vl-7b-instruct | 150 | 1200 | 5398 | 716 | 84.1 | 42.1 | 1.00 | 1.00 | 2 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | qwen2.5-vl-7b-instruct | 150 | 1400 | 5372 | 717 | 124.2 | 62.1 | 1.00 | 1.00 | 6 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | qwen2.5-vl-7b-instruct | 150 | 1650 | 5372 | 717 | 124.2 | 62.1 | 1.00 | 1.00 | 6 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | qwen2.5-vl-7b-instruct | 50 | 1024 | 11391 | 1698 | 130.7 | 16.3 | 1.00 | 0.97 | 21 | 8 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | qwen2.5-vl-7b-instruct | 50 | 1200 | 11391 | 1698 | 130.7 | 16.3 | 1.00 | 0.97 | 21 | 8 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | qwen2.5-vl-7b-instruct | 50 | 1400 | 11391 | 1698 | 130.7 | 16.3 | 1.00 | 0.97 | 21 | 8 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | qwen2.5-vl-7b-instruct | 50 | 1650 | 11391 | 1698 | 130.7 | 16.3 | 1.00 | 0.97 | 21 | 8 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | qwen2.5-vl-7b-instruct | 100 | 1024 | 9967 | 1466 | 193.7 | 24.2 | 0.97 | 0.97 | 20 | 11 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | qwen2.5-vl-7b-instruct | 100 | 1200 | 9970 | 1471 | 271.3 | 33.9 | 0.97 | 0.97 | 25 | 11 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | qwen2.5-vl-7b-instruct | 100 | 1400 | 10477 | 1536 | 405.8 | 50.7 | 1.00 | 0.97 | 20 | 27 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | qwen2.5-vl-7b-instruct | 100 | 1650 | 10054 | 1483 | 667.9 | 83.5 | 0.98 | 0.97 | 30 | 19 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | qwen2.5-vl-7b-instruct | 150 | 1024 | 9994 | 1462 | 192.8 | 24.1 | 0.96 | 0.97 | 17 | 11 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | qwen2.5-vl-7b-instruct | 150 | 1200 | 9965 | 1469 | 270.1 | 33.8 | 0.97 | 0.97 | 25 | 11 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | qwen2.5-vl-7b-instruct | 150 | 1400 | 10286 | 1535 | 404.1 | 50.5 | 1.00 | 0.97 | 22 | 8 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | qwen2.5-vl-7b-instruct | 150 | 1650 | 10049 | 1476 | 667.7 | 83.5 | 0.97 | 0.97 | 30 | 19 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | lfm2.5-vl-3b | 50 | 1024 | 3937 | 529 | 12.1 | 6.1 | 0.75 | 0.75 | 3 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | lfm2.5-vl-3b | 50 | 1200 | 3937 | 529 | 12.1 | 6.1 | 0.75 | 0.75 | 3 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | lfm2.5-vl-3b | 50 | 1400 | 3937 | 529 | 12.1 | 6.1 | 0.75 | 0.75 | 3 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | lfm2.5-vl-3b | 50 | 1650 | 3937 | 529 | 12.1 | 6.1 | 0.75 | 0.75 | 3 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | lfm2.5-vl-3b | 100 | 1024 | 4165 | 548 | 6.0 | 3.0 | 0.77 | 0.81 | 4 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | lfm2.5-vl-3b | 100 | 1200 | 4165 | 548 | 6.0 | 3.0 | 0.77 | 0.81 | 4 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | lfm2.5-vl-3b | 100 | 1400 | 4165 | 548 | 6.0 | 3.0 | 0.77 | 0.81 | 4 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | lfm2.5-vl-3b | 100 | 1650 | 4165 | 548 | 6.0 | 3.0 | 0.77 | 0.81 | 4 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | lfm2.5-vl-3b | 150 | 1024 | 4747 | 626 | 6.8 | 3.4 | 0.88 | 0.87 | 4 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | lfm2.5-vl-3b | 150 | 1200 | 5355 | 711 | 9.1 | 4.5 | 1.00 | 1.00 | 2 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | lfm2.5-vl-3b | 150 | 1400 | 5355 | 711 | 9.0 | 4.5 | 1.00 | 1.00 | 2 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| IT Support Analyst - India | lfm2.5-vl-3b | 150 | 1650 | 5355 | 711 | 9.0 | 4.5 | 1.00 | 1.00 | 2 | 0 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | lfm2.5-vl-3b | 50 | 1024 | 8527 | 1233 | 14.7 | 1.8 | 0.81 | 0.84 | 12 | 33 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | lfm2.5-vl-3b | 50 | 1200 | 8527 | 1233 | 14.7 | 1.8 | 0.81 | 0.84 | 12 | 33 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | lfm2.5-vl-3b | 50 | 1400 | 8527 | 1233 | 14.7 | 1.8 | 0.81 | 0.84 | 12 | 33 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | lfm2.5-vl-3b | 50 | 1650 | 8527 | 1233 | 14.7 | 1.8 | 0.81 | 0.84 | 12 | 33 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | lfm2.5-vl-3b | 100 | 1024 | 9314 | 1332 | 16.6 | 2.1 | 0.88 | 0.85 | 8 | 26 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | lfm2.5-vl-3b | 100 | 1200 | 10315 | 1534 | 22.9 | 2.9 | 1.00 | 0.99 | 8 | 40 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | lfm2.5-vl-3b | 100 | 1400 | 10321 | 1528 | 22.9 | 2.9 | 1.00 | 0.99 | 8 | 40 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | lfm2.5-vl-3b | 100 | 1650 | 10291 | 1528 | 23.1 | 2.9 | 1.00 | 0.99 | 8 | 40 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | lfm2.5-vl-3b | 150 | 1024 | 9461 | 1366 | 17.0 | 2.1 | 0.90 | 0.87 | 8 | 21 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | lfm2.5-vl-3b | 150 | 1200 | 9992 | 1470 | 22.3 | 2.8 | 0.97 | 0.97 | 8 | 40 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | lfm2.5-vl-3b | 150 | 1400 | 9968 | 1470 | 22.3 | 2.8 | 0.97 | 0.97 | 8 | 40 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |
| Even OPD Policy Document | lfm2.5-vl-3b | 150 | 1650 | 10310 | 1528 | 22.9 | 2.9 | 1.00 | 0.99 | 8 | 40 | 100% | N/A | N/A | N/A | N/A | N/A | N/A | N/A |  |

| PDF | Provider | DPI | MaxDim | Chars | Words | TotalS | AvgPageS | WordRatio | TokenOvl | Headings | TableRows | NonEmpty% | WordF1 | NumR | NumP | Order | FactsStrict | FactsLenient | FailedPages | Notes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| IT Support Analyst - India | lfm3-vl-2b | 150 | 1200 | 5338 | 710 | 73.4 | 36.7 | 1.00 | 1.00 | 0 | 0 | 100% | 1.00 | 1.00 | 1.00 | 1.00 | N/A | N/A | 0 | reasoning on |
| Even OPD Policy Document | lfm3-vl-2b | 150 | 1200 | 10528 | 1534 | 190.1 | 23.8 | 1.00 | 1.00 | 3 | 40 | 100% | 1.00 | 0.89 | 1.00 | 0.56 | N/A | N/A | 0 | reasoning on |
| ind_nifty50 | lfm3-vl-2b | 150 | 1200 | 5622 | 753 | 76.5 | 38.2 | 1.00 | 0.95 | 1 | 60 | 100% | 0.94 | 0.96 | 0.82 | 0.37 | N/A | N/A | 0 | reasoning on |
| nifty-50-whitepaper-2024 | lfm3-vl-2b | 150 | 1200 | N/A | N/A | 929.7 | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | timeout (page > 180 s) |
| IT Support Analyst - India | lfm3-vl-2b-nothink | 150 | 1200 | 5342 | 710 | 34.7 | 17.3 | 1.00 | 1.00 | 0 | 0 | 100% | 1.00 | 1.00 | 1.00 | 1.00 | N/A | N/A | 0 | reasoning off |
| Even OPD Policy Document | lfm3-vl-2b-nothink | 150 | 1200 | 10212 | 1497 | 48.3 | 6.0 | 0.99 | 0.99 | 0 | 29 | 100% | 0.98 | 0.89 | 1.00 | 0.55 | N/A | N/A | 0 | reasoning off |
| ind_nifty50 | lfm3-vl-2b-nothink | 150 | 1200 | 4777 | 613 | 30.4 | 15.2 | 0.86 | 0.84 | 0 | 78 | 100% | 0.87 | 0.95 | 0.78 | 0.20 | N/A | N/A | 0 | reasoning off |
| nifty-50-whitepaper-2024 | lfm3-vl-2b-nothink | 150 | 1200 | 26935 | 3684 | 191.7 | 12.0 | 0.89 | 0.92 | 12 | 219 | 100% | 0.92 | 0.82 | 0.99 | 0.43 | 44/50 | 47/50 | 0 | reasoning off |

## Testing a New Model

To evaluate a new VLM model and add its results to this document:

### 1. Load the Model in LM Studio

Download or import the model into LM Studio and ensure it is available on the server at `http://192.168.2.126:1234` (or update `lmStudioServerURL` in `VLMCompareTest.scala` if using a different server).

### 2. Add the Model to the Providers List

Add a new entry to the `providers` list in `VLMCompareTest.scala`:

```scala
val providers: List[(String, String, String, String)] = List(
    // ... existing providers ...
    ("new-model-label", lmStudioServerURL, "org/new-model-name", "lm-studio")
)
```

- **Label**: Short identifier used in output filenames and the summary table (e.g. `"new-model-label"`).
- **Server URL**: Typically `lmStudioServerURL` for local models.
- **Model name**: The exact model identifier as shown in LM Studio (e.g. `"org/new-model-name"`).
- **API key**: `"lm-studio"` for local LM Studio instances.

### 3. Run the Sweep

From `backend/`:

```
mvn test-compile exec:exec -Dexec.classpathScope=test \
  -Dexec.args="-Djava.awt.headless=true -Dorg.jboss.logging.provider=jdk -classpath %classpath agentica.misctests.VLMCompareTest --sweep --providers new-model-label"
```

The `--providers` filter keeps the run to the new model; drop it to sweep every configured provider.

The harness will:
- Unload any currently-loaded models on the server (pre-run cleanup)
- Load the new model once, then iterate over all PDFs × DPI × maxDim combinations
- Unload the model after all combinations are complete
- Print a summary table at the end

### 4. Add Results to This Document

Copy the rows for the new model from the printed summary table and append them to the **Result Details** table above. Use the markdown table format:

```markdown
| PDF | Provider | DPI | MaxDim | Chars | Words | TotalS | AvgPageS | WordRatio | TokenOvl | Headings | TableRows | NonEmpty% | WordF1 | NumR | NumP | Order | FactsStrict | FactsLenient | FailedPages | Notes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| <pdf-stem> | <label> | <dpi> | <maxdim> | <chars> | <words> | <totalS> | <avgPageS> | <wordRatio> | <tokenOvl> | <headings> | <tableRows> | <nonEmpty%> | <wordF1> | <numR> | <numP> | <order> | <strict>/<total> | <lenient>/<total> | <failedPages> | <notes> |
```

Use `N/A` for metrics a run did not produce (no facts file, unsupported model, timeout).

If the model timed out, record a single row with `N/A` for all metric fields and `timeout` in the Notes column.

The table above records the original document-level metrics. Newer runs also produce per-page text fidelity (word F1, number recall/precision, order similarity) and, for PDFs with a `.facts.json`, strict/lenient fact passes — record those in a separate table or alongside the existing rows rather than reusing the old columns.

### 5. Update the Decision (If Applicable)

If the new model outperforms the current selection (`lfm3-vl-2b` with reasoning disabled at 150 DPI / 1200 maxDim), update the **Results and Decision** section above with the new optimal configuration and rationale.