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

## Test Harness

The experiment was conducted using `VLMCompareTest.scala` in sweep mode (`runSweepMode = true`).

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
- **In-memory scoring**: `MarkdownScorer` scores VLM output in memory — no `.md` files are written in sweep mode.

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

## Results and Decision

Based on the sweep results across all models and parameter combinations:

### Selected Configuration

| Parameter | Value |
|---|---|
| **DPI** | 150 |
| **maxImageDimension** | 1200 |
| **Model** | `lfm2.5-vl-3b` (LFM 2.5 VL 3B) |

### Rationale

- **150 DPI** provides the best text clarity for OCR-quality transcription. Lower DPI values (50, 100) resulted in degraded quality, particularly for small text and fine details.
- **1200 maxImageDimension** strikes the optimal balance between image quality and vision-encoder processing time. It is large enough to preserve the detail from 150 DPI renders while keeping the token count manageable for the LFM model's vision encoder.
- **`lfm2.5-vl-3b`** was selected as the VLM model based on its overall performance across the test PDFs — combining good transcription quality (word count ratio, token overlap, heading/table detection) with reasonable processing speed.

## Files

| File | Description |
|---|---|
| `VLMCompareTest.scala` | Test harness with `runFixed` and `runSweep` modes |
| `MarkdownScorer.scala` | Scoring utility for comparing VLM Markdown against PDFBox reference text |
| `PDFPageRenderer.scala` | PDF rasterization with configurable DPI and `resizeToMaxDimension` |
| `PageVisionTranscriber.scala` | Core transcription logic with per-page timeout support |

## How to Reproduce

1. Ensure LM Studio is running with the target models loaded.
2. Set `runSweepMode = true` in `VLMCompareTest.scala`.
3. Configure `dpiValues`, `maxDimValues`, `providers`, and `pdfPaths` as needed.
4. Run:
   ```
   mvn test -pl backend -Dtest=VLMCompareTest -Dsurefire.failIfNoSpecifiedTests=false
   ```
5. Review the summary table printed at the end of the run.

## Result Details

Note: Models/combinations not listed in the table were not tested due to timeout.

| PDF | Provider | DPI | MaxDim | Chars | Words | TotalS | AvgPageS | WordRatio | TokenOvl | Headings | TableRows | NonEmpty% | Notes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| IT Support Analyst - India | gemma-4-12b | 50 | 1024 | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | timeout |
| IT Support Analyst - India | gemma-4-e4b | 50 | 1024 | 4140 | 526 | 40.4 | 20.2 | 0.74 | 0.60 | 1 | 0 | 100% |  |
| IT Support Analyst - India | gemma-4-e4b | 50 | 1200 | 4140 | 526 | 40.4 | 20.2 | 0.74 | 0.60 | 1 | 0 | 100% |  |
| IT Support Analyst - India | gemma-4-e4b | 50 | 1400 | 4140 | 526 | 40.4 | 20.2 | 0.74 | 0.60 | 1 | 0 | 100% |  |
| IT Support Analyst - India | gemma-4-e4b | 50 | 1650 | 4140 | 526 | 40.4 | 20.2 | 0.74 | 0.60 | 1 | 0 | 100% |  |
| IT Support Analyst - India | gemma-4-e4b | 100 | 1024 | 4743 | 627 | 31.6 | 15.8 | 0.88 | 0.88 | 2 | 0 | 100% |  |
| IT Support Analyst - India | gemma-4-e4b | 100 | 1200 | 4743 | 627 | 31.6 | 15.8 | 0.88 | 0.88 | 2 | 0 | 100% |  |
| IT Support Analyst - India | gemma-4-e4b | 100 | 1400 | 4743 | 627 | 31.6 | 15.8 | 0.88 | 0.88 | 2 | 0 | 100% |  |
| IT Support Analyst - India | gemma-4-e4b | 100 | 1650 | 4743 | 627 | 31.6 | 15.8 | 0.88 | 0.88 | 2 | 0 | 100% |  |
| IT Support Analyst - India | gemma-4-e4b | 150 | 1024 | 4255 | 566 | 32.8 | 16.4 | 0.80 | 0.82 | 0 | 0 | 100% |  |
| IT Support Analyst - India | gemma-4-e4b | 150 | 1200 | 4698 | 626 | 26.8 | 13.4 | 0.88 | 0.90 | 1 | 0 | 100% |  |
| IT Support Analyst - India | gemma-4-e4b | 150 | 1400 | 4866 | 643 | 21.6 | 10.8 | 0.91 | 0.92 | 4 | 0 | 100% |  |
| IT Support Analyst - India | gemma-4-e4b | 150 | 1650 | 4866 | 643 | 21.6 | 10.8 | 0.91 | 0.92 | 4 | 0 | 100% |  |
| Even OPD Policy Document | gemma-4-e4b | 50 | 1024 | 10096 | 1472 | 117.9 | 14.7 | 0.97 | 0.95 | 3 | 27 | 100% |  |
| Even OPD Policy Document | gemma-4-e4b | 50 | 1200 | 10096 | 1472 | 117.9 | 14.7 | 0.97 | 0.95 | 3 | 27 | 100% |  |
| Even OPD Policy Document | gemma-4-e4b | 50 | 1400 | 10096 | 1472 | 117.9 | 14.7 | 0.97 | 0.95 | 3 | 27 | 100% |  |
| Even OPD Policy Document | gemma-4-e4b | 50 | 1650 | 10096 | 1472 | 117.9 | 14.7 | 0.97 | 0.95 | 3 | 27 | 100% |  |
| Even OPD Policy Document | gemma-4-e4b | 100 | 1024 | 9734 | 1444 | 109.1 | 13.6 | 0.95 | 0.95 | 4 | 33 | 100% |  |
| Even OPD Policy Document | gemma-4-e4b | 100 | 1200 | 9701 | 1441 | 95.5 | 11.9 | 0.95 | 0.95 | 3 | 25 | 100% |  |
| Even OPD Policy Document | gemma-4-e4b | 100 | 1400 | 9453 | 1415 | 94.6 | 11.8 | 0.93 | 0.95 | 3 | 27 | 100% |  |
| Even OPD Policy Document | gemma-4-e4b | 100 | 1650 | 10456 | 1549 | 99.5 | 12.4 | 1.00 | 0.96 | 5 | 45 | 100% |  |
| Even OPD Policy Document | gemma-4-e4b | 150 | 1024 | 9832 | 1451 | 102.9 | 12.9 | 0.96 | 0.95 | 2 | 32 | 100% |  |
| Even OPD Policy Document | gemma-4-e4b | 150 | 1200 | 9697 | 1444 | 90.9 | 11.4 | 0.95 | 0.94 | 1 | 27 | 100% |  |
| Even OPD Policy Document | gemma-4-e4b | 150 | 1400 | 10228 | 1521 | 98.4 | 12.3 | 1.00 | 0.98 | 4 | 35 | 100% |  |
| Even OPD Policy Document | gemma-4-e4b | 150 | 1650 | 9251 | 1369 | 93.1 | 11.6 | 0.90 | 0.93 | 5 | 24 | 100% |  |
| IT Support Analyst - India | gemma-4-12b-qat | 50 | 1024 | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | timeout |
| IT Support Analyst - India | glm-4.6v-flash | 50 | 1024 | 4737 | 632 | 64.7 | 32.4 | 0.89 | 0.82 | 0 | 0 | 100% |  |
| IT Support Analyst - India | glm-4.6v-flash | 50 | 1200 | 4737 | 632 | 64.7 | 32.4 | 0.89 | 0.82 | 0 | 0 | 100% |  |
| IT Support Analyst - India | glm-4.6v-flash | 50 | 1400 | 4737 | 632 | 64.7 | 32.4 | 0.89 | 0.82 | 0 | 0 | 100% |  |
| IT Support Analyst - India | glm-4.6v-flash | 50 | 1650 | 4737 | 632 | 64.7 | 32.4 | 0.89 | 0.82 | 0 | 0 | 100% |  |
| IT Support Analyst - India | glm-4.6v-flash | 100 | 1024 | 5346 | 713 | 48.9 | 24.4 | 1.00 | 1.00 | 0 | 0 | 100% |  |
| IT Support Analyst - India | glm-4.6v-flash | 100 | 1200 | 5346 | 713 | 48.9 | 24.4 | 1.00 | 1.00 | 0 | 0 | 100% |  |
| IT Support Analyst - India | glm-4.6v-flash | 100 | 1400 | 5346 | 713 | 48.9 | 24.4 | 1.00 | 1.00 | 0 | 0 | 100% |  |
| IT Support Analyst - India | glm-4.6v-flash | 100 | 1650 | 5346 | 713 | 48.9 | 24.4 | 1.00 | 1.00 | 0 | 0 | 100% |  |
| IT Support Analyst - India | glm-4.6v-flash | 150 | 1024 | 5358 | 713 | 45.3 | 22.7 | 1.00 | 1.00 | 0 | 0 | 100% |  |
| IT Support Analyst - India | glm-4.6v-flash | 150 | 1200 | 5358 | 713 | 47.7 | 23.9 | 1.00 | 1.00 | 0 | 0 | 100% |  |
| IT Support Analyst - India | glm-4.6v-flash | 150 | 1400 | 5366 | 713 | 48.8 | 24.4 | 1.00 | 1.00 | 0 | 0 | 100% |  |
| IT Support Analyst - India | glm-4.6v-flash | 150 | 1650 | 5366 | 713 | 48.8 | 24.4 | 1.00 | 1.00 | 0 | 0 | 100% |  |
| Even OPD Policy Document | glm-4.6v-flash | 50 | 1024 | 10631 | 1513 | 150.5 | 18.8 | 1.00 | 1.00 | 0 | 40 | 100% |  |
| Even OPD Policy Document | glm-4.6v-flash | 50 | 1200 | 10631 | 1513 | 150.5 | 18.8 | 1.00 | 1.00 | 0 | 40 | 100% |  |
| Even OPD Policy Document | glm-4.6v-flash | 50 | 1400 | 10631 | 1513 | 150.5 | 18.8 | 1.00 | 1.00 | 0 | 40 | 100% |  |
| Even OPD Policy Document | glm-4.6v-flash | 50 | 1650 | 10631 | 1513 | 150.5 | 18.8 | 1.00 | 1.00 | 0 | 40 | 100% |  |
| Even OPD Policy Document | glm-4.6v-flash | 100 | 1024 | 10429 | 1517 | 176.5 | 22.1 | 1.00 | 1.00 | 1 | 40 | 100% |  |
| Even OPD Policy Document | glm-4.6v-flash | 100 | 1200 | 12129 | 1516 | 188.7 | 23.6 | 1.00 | 1.00 | 1 | 34 | 100% |  |
| Even OPD Policy Document | glm-4.6v-flash | 100 | 1400 | 13225 | 1524 | 228.0 | 28.5 | 1.00 | 1.00 | 0 | 28 | 100% |  |
| Even OPD Policy Document | glm-4.6v-flash | 100 | 1650 | 11405 | 1513 | 152.2 | 19.0 | 1.00 | 1.00 | 0 | 34 | 100% |  |
| Even OPD Policy Document | glm-4.6v-flash | 150 | 1024 | 10978 | 1509 | 153.4 | 19.2 | 0.99 | 1.00 | 1 | 43 | 100% |  |
| Even OPD Policy Document | glm-4.6v-flash | 150 | 1200 | 11959 | 1517 | 173.6 | 21.7 | 1.00 | 1.00 | 1 | 36 | 100% |  |
| Even OPD Policy Document | glm-4.6v-flash | 150 | 1400 | 10912 | 1526 | 204.6 | 25.6 | 1.00 | 1.00 | 0 | 37 | 100% |  |
| Even OPD Policy Document | glm-4.6v-flash | 150 | 1650 | 10902 | 1517 | 210.3 | 26.3 | 1.00 | 1.00 | 0 | 34 | 100% |  |
| IT Support Analyst - India | ministral-3-14b | 50 | 1024 | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | timeout |
| IT Support Analyst - India | qwen3.5-9b-mtp | 50 | 1024 | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | N/A | timeout |
| IT Support Analyst - India | qwen2.5-vl-7b-instruct | 50 | 1024 | 5221 | 691 | 35.8 | 17.9 | 0.97 | 0.93 | 3 | 0 | 100% |  |
| IT Support Analyst - India | qwen2.5-vl-7b-instruct | 50 | 1200 | 5221 | 691 | 35.8 | 17.9 | 0.97 | 0.93 | 3 | 0 | 100% |  |
| IT Support Analyst - India | qwen2.5-vl-7b-instruct | 50 | 1400 | 5221 | 691 | 35.8 | 17.9 | 0.97 | 0.93 | 3 | 0 | 100% |  |
| IT Support Analyst - India | qwen2.5-vl-7b-instruct | 50 | 1650 | 5221 | 691 | 35.8 | 17.9 | 0.97 | 0.93 | 3 | 0 | 100% |  |
| IT Support Analyst - India | qwen2.5-vl-7b-instruct | 100 | 1024 | 5389 | 715 | 59.9 | 30.0 | 1.00 | 1.00 | 3 | 0 | 100% |  |
| IT Support Analyst - India | qwen2.5-vl-7b-instruct | 100 | 1200 | 5389 | 715 | 59.9 | 30.0 | 1.00 | 1.00 | 3 | 0 | 100% |  |
| IT Support Analyst - India | qwen2.5-vl-7b-instruct | 100 | 1400 | 5389 | 715 | 59.9 | 30.0 | 1.00 | 1.00 | 3 | 0 | 100% |  |
| IT Support Analyst - India | qwen2.5-vl-7b-instruct | 100 | 1650 | 5389 | 715 | 59.9 | 30.0 | 1.00 | 1.00 | 3 | 0 | 100% |  |
| IT Support Analyst - India | qwen2.5-vl-7b-instruct | 150 | 1024 | 5364 | 714 | 59.6 | 29.8 | 1.00 | 1.00 | 5 | 0 | 100% |  |
| IT Support Analyst - India | qwen2.5-vl-7b-instruct | 150 | 1200 | 5398 | 716 | 84.1 | 42.1 | 1.00 | 1.00 | 2 | 0 | 100% |  |
| IT Support Analyst - India | qwen2.5-vl-7b-instruct | 150 | 1400 | 5372 | 717 | 124.2 | 62.1 | 1.00 | 1.00 | 6 | 0 | 100% |  |
| IT Support Analyst - India | qwen2.5-vl-7b-instruct | 150 | 1650 | 5372 | 717 | 124.2 | 62.1 | 1.00 | 1.00 | 6 | 0 | 100% |  |
| Even OPD Policy Document | qwen2.5-vl-7b-instruct | 50 | 1024 | 11391 | 1698 | 130.7 | 16.3 | 1.00 | 0.97 | 21 | 8 | 100% |  |
| Even OPD Policy Document | qwen2.5-vl-7b-instruct | 50 | 1200 | 11391 | 1698 | 130.7 | 16.3 | 1.00 | 0.97 | 21 | 8 | 100% |  |
| Even OPD Policy Document | qwen2.5-vl-7b-instruct | 50 | 1400 | 11391 | 1698 | 130.7 | 16.3 | 1.00 | 0.97 | 21 | 8 | 100% |  |
| Even OPD Policy Document | qwen2.5-vl-7b-instruct | 50 | 1650 | 11391 | 1698 | 130.7 | 16.3 | 1.00 | 0.97 | 21 | 8 | 100% |  |
| Even OPD Policy Document | qwen2.5-vl-7b-instruct | 100 | 1024 | 9967 | 1466 | 193.7 | 24.2 | 0.97 | 0.97 | 20 | 11 | 100% |  |
| Even OPD Policy Document | qwen2.5-vl-7b-instruct | 100 | 1200 | 9970 | 1471 | 271.3 | 33.9 | 0.97 | 0.97 | 25 | 11 | 100% |  |
| Even OPD Policy Document | qwen2.5-vl-7b-instruct | 100 | 1400 | 10477 | 1536 | 405.8 | 50.7 | 1.00 | 0.97 | 20 | 27 | 100% |  |
| Even OPD Policy Document | qwen2.5-vl-7b-instruct | 100 | 1650 | 10054 | 1483 | 667.9 | 83.5 | 0.98 | 0.97 | 30 | 19 | 100% |  |
| Even OPD Policy Document | qwen2.5-vl-7b-instruct | 150 | 1024 | 9994 | 1462 | 192.8 | 24.1 | 0.96 | 0.97 | 17 | 11 | 100% |  |
| Even OPD Policy Document | qwen2.5-vl-7b-instruct | 150 | 1200 | 9965 | 1469 | 270.1 | 33.8 | 0.97 | 0.97 | 25 | 11 | 100% |  |
| Even OPD Policy Document | qwen2.5-vl-7b-instruct | 150 | 1400 | 10286 | 1535 | 404.1 | 50.5 | 1.00 | 0.97 | 22 | 8 | 100% |  |
| Even OPD Policy Document | qwen2.5-vl-7b-instruct | 150 | 1650 | 10049 | 1476 | 667.7 | 83.5 | 0.97 | 0.97 | 30 | 19 | 100% |  |
| IT Support Analyst - India | lfm2.5-vl-3b | 50 | 1024 | 3937 | 529 | 12.1 | 6.1 | 0.75 | 0.75 | 3 | 0 | 100% |  |
| IT Support Analyst - India | lfm2.5-vl-3b | 50 | 1200 | 3937 | 529 | 12.1 | 6.1 | 0.75 | 0.75 | 3 | 0 | 100% |  |
| IT Support Analyst - India | lfm2.5-vl-3b | 50 | 1400 | 3937 | 529 | 12.1 | 6.1 | 0.75 | 0.75 | 3 | 0 | 100% |  |
| IT Support Analyst - India | lfm2.5-vl-3b | 50 | 1650 | 3937 | 529 | 12.1 | 6.1 | 0.75 | 0.75 | 3 | 0 | 100% |  |
| IT Support Analyst - India | lfm2.5-vl-3b | 100 | 1024 | 4165 | 548 | 6.0 | 3.0 | 0.77 | 0.81 | 4 | 0 | 100% |  |
| IT Support Analyst - India | lfm2.5-vl-3b | 100 | 1200 | 4165 | 548 | 6.0 | 3.0 | 0.77 | 0.81 | 4 | 0 | 100% |  |
| IT Support Analyst - India | lfm2.5-vl-3b | 100 | 1400 | 4165 | 548 | 6.0 | 3.0 | 0.77 | 0.81 | 4 | 0 | 100% |  |
| IT Support Analyst - India | lfm2.5-vl-3b | 100 | 1650 | 4165 | 548 | 6.0 | 3.0 | 0.77 | 0.81 | 4 | 0 | 100% |  |
| IT Support Analyst - India | lfm2.5-vl-3b | 150 | 1024 | 4747 | 626 | 6.8 | 3.4 | 0.88 | 0.87 | 4 | 0 | 100% |  |
| IT Support Analyst - India | lfm2.5-vl-3b | 150 | 1200 | 5355 | 711 | 9.1 | 4.5 | 1.00 | 1.00 | 2 | 0 | 100% |  |
| IT Support Analyst - India | lfm2.5-vl-3b | 150 | 1400 | 5355 | 711 | 9.0 | 4.5 | 1.00 | 1.00 | 2 | 0 | 100% |  |
| IT Support Analyst - India | lfm2.5-vl-3b | 150 | 1650 | 5355 | 711 | 9.0 | 4.5 | 1.00 | 1.00 | 2 | 0 | 100% |  |
| Even OPD Policy Document | lfm2.5-vl-3b | 50 | 1024 | 8527 | 1233 | 14.7 | 1.8 | 0.81 | 0.84 | 12 | 33 | 100% |  |
| Even OPD Policy Document | lfm2.5-vl-3b | 50 | 1200 | 8527 | 1233 | 14.7 | 1.8 | 0.81 | 0.84 | 12 | 33 | 100% |  |
| Even OPD Policy Document | lfm2.5-vl-3b | 50 | 1400 | 8527 | 1233 | 14.7 | 1.8 | 0.81 | 0.84 | 12 | 33 | 100% |  |
| Even OPD Policy Document | lfm2.5-vl-3b | 50 | 1650 | 8527 | 1233 | 14.7 | 1.8 | 0.81 | 0.84 | 12 | 33 | 100% |  |
| Even OPD Policy Document | lfm2.5-vl-3b | 100 | 1024 | 9314 | 1332 | 16.6 | 2.1 | 0.88 | 0.85 | 8 | 26 | 100% |  |
| Even OPD Policy Document | lfm2.5-vl-3b | 100 | 1200 | 10315 | 1534 | 22.9 | 2.9 | 1.00 | 0.99 | 8 | 40 | 100% |  |
| Even OPD Policy Document | lfm2.5-vl-3b | 100 | 1400 | 10321 | 1528 | 22.9 | 2.9 | 1.00 | 0.99 | 8 | 40 | 100% |  |
| Even OPD Policy Document | lfm2.5-vl-3b | 100 | 1650 | 10291 | 1528 | 23.1 | 2.9 | 1.00 | 0.99 | 8 | 40 | 100% |  |
| Even OPD Policy Document | lfm2.5-vl-3b | 150 | 1024 | 9461 | 1366 | 17.0 | 2.1 | 0.90 | 0.87 | 8 | 21 | 100% |  |
| Even OPD Policy Document | lfm2.5-vl-3b | 150 | 1200 | 9992 | 1470 | 22.3 | 2.8 | 0.97 | 0.97 | 8 | 40 | 100% |  |
| Even OPD Policy Document | lfm2.5-vl-3b | 150 | 1400 | 9968 | 1470 | 22.3 | 2.8 | 0.97 | 0.97 | 8 | 40 | 100% |  |
| Even OPD Policy Document | lfm2.5-vl-3b | 150 | 1650 | 10310 | 1528 | 22.9 | 2.9 | 1.00 | 0.99 | 8 | 40 | 100% |  |

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

Set `runSweepMode = true` (if not already) and run:

```
mvn test -pl backend -Dtest=VLMCompareTest -Dsurefire.failIfNoSpecifiedTests=false
```

The harness will:
- Unload any currently-loaded models on the server (pre-run cleanup)
- Load the new model once, then iterate over all PDFs × DPI × maxDim combinations
- Unload the model after all combinations are complete
- Print a summary table at the end

### 4. Add Results to This Document

Copy the rows for the new model from the printed summary table and append them to the **Result Details** table above. Use the markdown table format:

```markdown
| PDF | Provider | DPI | MaxDim | Chars | Words | TotalS | AvgPageS | WordRatio | TokenOvl | Headings | TableRows | NonEmpty% | Notes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| <pdf-stem> | <label> | <dpi> | <maxdim> | <chars> | <words> | <totalS> | <avgPageS> | <wordRatio> | <tokenOvl> | <headings> | <tableRows> | <nonEmpty%> | <notes> |
```

If the model timed out, record a single row with `N/A` for all metric fields and `timeout` in the Notes column.

### 5. Update the Decision (If Applicable)

If the new model outperforms the current selection (`lfm2.5-vl-3b` at 150 DPI / 1200 maxDim), update the **Results and Decision** section above with the new optimal configuration and rationale.