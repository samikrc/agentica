# Document Processing Architecture

## 1. Overview

Agentica's document intelligence subsystem provides a unified pipeline for reading, understanding, and generating DOCX, PPTX, and PDF documents. It targets both structured enterprise workflows (branded templates, job descriptions, reports) and fully free-form AI-generated documents from web content, uploaded files, or user prompts.

**Design principle:** Keep semantic content generation (AI) strictly separate from layout/format rendering (templates and renderers). The canonical interchange format between these two layers is Markdown.

**Ingestion approach:** Vision-First — each document format is rendered to per-page or per-slide images by a format-specific JVM renderer, then a Vision LLM converts each image to Markdown. This eliminates the Python/Docling subprocess dependency and leverages the same Vision LLM already configured in the agent.

**Output approach:** All Markdown → DOCX and Markdown → PDF conversions use LibreOffice headless, which is already required for DOCX rendering. No additional system tools are needed for output generation.

---

## 2. Architecture

```text
┌──────────────────────────────────────────────────────────────────┐
│                      INGESTION PIPELINE                          │
│                                                                  │
│      PDF               PPTX                  DOCX                │
│       │                 │                     │                  │
│       ▼                 ▼                     ▼                  │
│   PDFBox            POI XSLF           LibreOffice               │
│  (JVM, page        (JVM, slide         headless                  │
│   render)           render)            (page render)             │
│       │                 │                     │                  │
│       └─────────────────┴─────────────────────┘                  │
│                         │                                        │
│                Page / Slide Images (PNG)                         │
│                         │                                        │
│                         ▼                                        │
│               [ Vision LLM (multimodal) ]                        │
│                         │                                        │
│                         ▼                                        │
│           Markdown per page / slide, assembled                   │
└──────────────────────────────────────────────────────────────────┘
                          │
                          │  (canonical format — READ ONLY)
                          ▼
┌──────────────────────────────────────────────────────────────────┐
│                   MARKDOWN LAYER (AI ZONE)                       │
│                                                                  │
│   • AI prompting / reasoning                                     │
│   • Semantic editing and enrichment                              │
│   • RAG / vector search source                                   │
│   • Free-form document generation target                         │
└──────────────────────────────────────────────────────────────────┘
                          │
          ┌───────────────┼────────────────────┐
          │               │                    │
          ▼               ▼                    ▼
┌──────────────┐ ┌────────────────┐ ┌──────────────────────────────┐
│ FREE-FORM    │ │ TEMPLATE-BASED │ │ IN-PLACE EDITING             │
│ OUTPUT       │ │ OUTPUT         │ │                              │
│              │ │                │ │ docx4j (DOCX)                │
│ LibreOffice  │ │ docx4j         │ │ Open existing .docx,         │
│ Markdown →   │ │ Fill           │ │ locate section by anchor     │
│   DOCX       │ │ placeholders   │ │ (SDT, heading, proximity),   │
│   PDF        │ │ in branded     │ │ replace text runs preserving │
│              │ │ .docx          │ │ <w:rPr> styles, save back.   │
│              │ │ templates      │ │                              │
│              │ │                │ │ POI XSLF (PPTX)              │
│              │ │ LibreOffice    │ │ Find shape by slide + name,  │
│              │ │ → PDF opt.     │ │ replace XSLFTextRun,         │
│              │ │                │ │ save back.                   │
└──────────────┘ └────────────────┘ └──────────────────────────────┘

ROUND-TRIP EDITING PATTERN
───────────────────────────
  Existing DOCX
       │
       ├──► LibreOffice (render) ──► Vision LLM ──► Markdown   (AI reads — never written back)
       │                                                │
       │                               AI identifies section + produces new content
       │                                                │
       └──► docx4j ──────────────────────────────────────────► Modified DOCX  (OOXML only)
```

---

## 3. Ingestion Pipeline (Vision-First)

**Approach:** Each supported format is rendered to per-page or per-slide images by a format-specific renderer, then each image is passed to the Vision LLM to produce Markdown. Results are assembled in order with `---` separators and returned as a single Markdown document.

**Font bundling:** At startup, Agentica registers a set of bundled fonts (common web-safe families) with PDFBox and POI XSLF via their programmatic font registration APIs. This ensures consistent rendering quality across machines regardless of locally installed fonts.

### 3a. PDF — Apache PDFBox

**Library:** [Apache PDFBox](https://pdfbox.apache.org/) — JVM, Maven dependency, no user install required.

**Pipeline:**
1. `PDDocument.load(path)` — open PDF
2. `PDFRenderer.renderImageWithDPI(pageIndex, dpi=150)` → `BufferedImage` per page
3. Encode each image as PNG bytes (base64 for Vision LLM call)
4. Vision LLM prompt: "Convert this document page to Markdown, preserving headings, lists, tables, and captions."
5. Concatenate per-page Markdown with `---` separators

**Tools exposed:** `files.read_pdf`

### 3b. PPTX — Apache POI XSLF

**Library:** [Apache POI XSLF](https://poi.apache.org/) — JVM, Maven dependency, no user install required.

**Pipeline:**
1. `XMLSlideShow.open(path)` — load PPTX
2. For each slide: create `BufferedImage` at target DPI, call `slide.draw(Graphics2D)` → PNG bytes
3. Vision LLM prompt: "Convert this presentation slide to Markdown. Capture title, bullet points, and any visible text."
4. Concatenate per-slide Markdown with `---` separators

**Note:** POI XSLF is the same library used for in-place PPTX editing (§5d) — no extra dependency for either path.

**Tools exposed:** `files.read_pptx`

### 3c. DOCX — LibreOffice Headless

**Library:** LibreOffice headless — external binary, user must install. Used for rendering because DOCX page layout (complex pagination, tracked changes, embedded objects) is too complex for JVM-native reproduction at sufficient fidelity.

**Pipeline:**
1. `soffice --headless --convert-to png --outdir <tmp_dir> <input.docx>` → per-page PNG files
2. Read PNG files from temp directory in page order
3. Vision LLM prompt: same page-to-Markdown prompt as PDF
4. Concatenate per-page Markdown with `---` separators

**Note:** LibreOffice is also used for DOCX→PDF output (§5a), making it dual-role: rendering input documents and converting output documents.

**Tools exposed:** `files.read_docx`

---

## 4. Vision LLM Pipeline

The Vision LLM step is the core of ingestion for all three formats — it is not optional post-processing. It is the primary mechanism for extracting semantic content from rendered images.

**Requirements:**
- The active `LLMProvider` must support multimodal (image + text) input.
- If the provider does not support vision, the tool returns a structured error with a clear message (e.g. "Vision LLM required for document ingestion; configure a multimodal provider in Settings").
- There is no text-only fallback for ingestion.

**Opt-out:** Pass `enrich_images=false` to skip the Vision LLM entirely and receive a Markdown document with `[page N: vision enrichment skipped]` placeholders. Useful for confirming file accessibility or counting pages before committing to a full ingestion pass.

**Prompt:** A single shared system prompt is used for all formats. It instructs the model to preserve headings, lists, tables, inline code, captions, and alt-text for embedded images. Slide-specific variants are minimal overrides on top of this shared base.

---

## 5. Output Rendering

### 5a. Free-Form Generation (LibreOffice)

**Use case:** AI-generated documents where formatting is generic — reports, summaries, analyses.

**Pipeline:** AI produces Markdown → LibreOffice headless converts to target format.

| Target | Command |
|--------|---------|
| DOCX | `soffice --headless --convert-to docx input.md` |
| PDF | `soffice --headless --convert-to pdf input.md` |

LibreOffice is already required for DOCX rendering (§3c), so no additional system dependency is introduced here.

**SVG support:** If the agent generates SVG images as part of a document, they can be referenced from Markdown/HTML and LibreOffice will render them correctly during conversion. For the docx4j/POI direct-embedding path, SVGs should be rasterized to PNG first using Apache Batik (JVM library, Maven dependency — add when needed).

**Tools exposed:**
- `files.write_markdown` — write raw Markdown to disk
- `files.markdown_to_docx` — Markdown → DOCX via LibreOffice headless
- `files.markdown_to_pdf` — Markdown → PDF via LibreOffice headless

### 5b. Template-Based DOCX Generation (docx4j)

**Use case:** Branded/corporate documents that must conform to a specific visual design — job descriptions, project proposals, quarterly reports.

**Mechanism:**
- Templates are `.docx` files stored in the workspace (e.g. `templates/jd_template.docx`)
- Templates use content-control placeholders (structured document tags) or `{{key}}` text markers
- The agent generates structured content (section by section) as Markdown or JSON
- Agentica maps the AI-generated content to placeholder keys and fills the template using docx4j
- The filled `.docx` is written to disk; optionally converted to PDF via LibreOffice

**Tools exposed:**
- `files.list_templates` — list available `.docx` templates in workspace
- `files.fill_template` — fill a named template with AI-generated content keyed by placeholder

### 5c. In-Place DOCX Editing (docx4j)

**Use case:** Editing specific sections of an *existing* DOCX without touching its styles, images, headers/footers, or surrounding content. This is the round-trip editing path.

**Why Pandoc cannot be used here:** Pandoc DOCX → MD → DOCX destroys styles, custom fonts, tracked changes, embedded images, and table formatting. Any round-trip through Markdown is destructive. docx4j operates directly on OOXML and only touches what it needs to.

**Dual-layer pattern:**
1. `files.read_docx` → Vision LLM → Markdown stored in scratchpad *(agent reads and reasons — this path is read-only)*
2. Agent identifies which section(s) need changing and produces new content
3. `files.edit_docx` → docx4j:
   - Locate target paragraph block using one of three strategies (in order of preference):
     1. **Named SDT (Structured Document Tag):** find by `<w:sdtPr><w:tag w:val="..."/>` — most reliable; requires template authored with named content controls (stable anchor)
     2. **Heading text match:** find `<w:pStyle val="HeadingN">` paragraph whose text matches the section name, then replace the paragraph block between that heading and the next same-or-higher heading (stable anchor)
     3. **Proximity match:** plain text search within a paragraph range — last resort, less precise
   - For each matched paragraph run: replace text content; copy `<w:rPr>` (font, size, bold, colour) from the first existing run of the paragraph to all newly inserted runs, preserving visual style
   - Save to original path or a new output path

**Stable anchors:** Real-world documents often lack named SDTs or headings that match section names reliably. When no anchor is found, the tool returns a structured error listing what it attempted rather than making a best-guess replacement. The agent can then ask the user to add an anchor to the template.

**Inline formatting:** A thin Markdown-to-runs converter handles paragraph-level inline formatting (`**bold**` → `<w:b>`, `*italic*` → `<w:i>`, plain text → unstyled run). Complex Markdown (tables, code blocks, images) is not supported in-place; use `files.fill_template` or `files.markdown_to_docx` for those.

**Tools exposed:**
- `files.edit_docx path=... section="..." content="..."` — replace a single named section
- `files.patch_docx path=... patches=[{section, content}, ...]` — replace multiple sections in one docx4j pass; more efficient than sequential single edits

### 5d. PPTX Editing and Generation (Apache POI XSLF)

**Read:** Available from Stage B via the Vision-First pipeline (`files.read_pptx`). POI XSLF dependency is already present from ingestion — no additional install.

**In-place edit:** Open existing `.pptx` with POI XSLF; find shape by slide index + shape name/title; replace `XSLFTextRun` content; save back. Same dual-layer principle as DOCX editing — Vision LLM for reasoning, POI for writing.

**Generate new:** Accept slide structure from agent (title, bullet content per slide); generate `.pptx` from scratch via POI XSLF.

Both are deferred until the DOCX pipeline (Stages C–D) is stable. Tool surface: `files.edit_pptx`, `files.write_pptx`.

---

## 6. External Dependencies

| Dependency | Role | Install Required By User |
|------------|------|--------------------------|
| Apache PDFBox | PDF page rendering (JVM library) | No — Maven dependency |
| Apache POI XSLF | PPTX slide rendering + in-place editing (JVM library) | No — Maven dependency |
| LibreOffice | DOCX rendering (input) + Markdown/DOCX → DOCX/PDF (output) | Yes — system package |
| docx4j | DOCX template filling + in-place OOXML editing (JVM library) | No — Maven dependency |
| Apache Batik | SVG → PNG rasterization for docx4j/POI embedding (JVM library) | No — Maven dependency (add when needed) |
| Vision LLM | Document image → Markdown (core ingestion) | Depends on provider config |

**Detection:** At startup and on first use, Agentica checks for the `soffice` binary. Detection results are cached and surfaced in a `deps.check` tool and in the Settings UI. Missing LibreOffice degrades gracefully per-feature (DOCX read and all output generation) rather than failing the whole application. JVM libraries (PDFBox, POI XSLF, docx4j, Batik) are always available as Maven dependencies — no detection needed.

---

## 7. Key Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Document ingestion | Vision-First (PDFBox / POI XSLF / LibreOffice → Vision LLM) | Eliminates Python/Docling subprocess; pure JVM for PDF and PPTX; reuses the Vision LLM already configured in the agent |
| PDF renderer | Apache PDFBox | JVM-native, no external install, mature Apache library; robust page-to-image rendering |
| PPTX renderer | Apache POI XSLF | Same library used for writing — shared dependency; `slide.draw(Graphics2D)` API is straightforward |
| DOCX renderer | LibreOffice headless | DOCX page layout is too complex for JVM-native rendering at sufficient fidelity; LibreOffice already required for PDF output |
| Font bundling | Programmatic registration at startup | Ensures consistent rendering across machines without relying on locally installed fonts |
| Canonical format | Markdown | Natural for LLMs; works for RAG, editing, prompting, and rendering |
| Free-form output | LibreOffice headless | Already required for DOCX rendering; eliminates Pandoc as a second mandatory system install |
| Template output | docx4j | Programmatic control over DOCX structure; enterprise template fidelity |
| Round-trip editing | docx4j in-place OOXML manipulation | Pandoc DOCX→MD→DOCX destroys styles, images, tracked changes; docx4j preserves `<w:rPr>` and surrounding structure |
| Stable anchors | SDT tags > heading text > proximity | Preference order ensures most reliable anchor is tried first; fail explicitly when no anchor found rather than making unreliable replacements |
| Read layer vs write layer | Strictly separated (Vision LLM reads, docx4j/POI writes) | Prevents round-trip through Markdown for existing documents; Markdown is always read-only for existing content |
| DOCX → PDF | LibreOffice headless | Highest fidelity for complex DOCX layouts; no vendor lock-in |
| Vision LLM | Via existing `LLMProvider` | Reuses configured provider; no separate vision client needed |
| Scratchpad routing | Existing `SessionScratchpad` | Large document content (>8000 chars) stored as `$scratch/` refs; consistent with other tools |
| PPTX write/edit | Deferred (POI XSLF) | POI already present from ingestion; write complexity justified only after DOCX pipeline is proven |

---

## 8. Implementation Stages

See [tasklist.md](tasklist.md) Phase 3 — Document Tools for the detailed task breakdown.

**Stage A** — External dependency detection, graceful degradation, and font initialization  
**Stage B** — Vision-First document ingestion (`files.read_pdf`, `files.read_docx`, `files.read_pptx`)  
**Stage C** — Free-form generation (`files.write_markdown`, `files.markdown_to_docx`, `files.markdown_to_pdf`)  
**Stage D** — Template-based generation + in-place editing via docx4j (`files.list_templates`, `files.fill_template`, `files.edit_docx`, `files.patch_docx`)  
**Stage E** — PPTX in-place editing and generation via Apache POI XSLF (`files.edit_pptx`, `files.write_pptx` — deferred)  
**Stage F** — Integration tests across all stages  
