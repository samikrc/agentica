# Document Processing Architecture

## 1. Overview

Agentica's document intelligence subsystem provides a unified pipeline for reading, understanding, enriching, and generating DOCX, PPTX, and PDF documents. It targets both structured enterprise workflows (branded templates, job descriptions, reports) and fully free-form AI-generated documents from web content, uploaded files, or user prompts.

**Design principle:** Keep semantic content generation (AI) strictly separate from layout/format rendering (templates and renderers). The canonical interchange format between these two layers is Markdown.

---

## 2. Architecture

```text
┌──────────────────────────────────────────────────────────────┐
│                     INGESTION PIPELINE                       │
│                                                              │
│   PDF / DOCX / PPTX                                          │
│         │                                                    │
│         ▼                                                    │
│   [ Docling (Python, ProcessBuilder) ]                       │
│         │                                                    │
│         ├── Markdown text (semantic structure preserved)     │
│         └── Extracted images / charts / figures             │
│                     │                                        │
│                     ▼                                        │
│         [ Vision LLM (multimodal) ]                          │
│                     │                                        │
│                     ▼                                        │
│         AI-generated chart/image descriptions               │
│                     │                                        │
│                     ▼                                        │
│         Enriched Markdown (descriptions injected inline)    │
└──────────────────────────────────────────────────────────────┘
                       │
                       │  (canonical format — READ ONLY)
                       ▼
┌──────────────────────────────────────────────────────────────┐
│                   MARKDOWN LAYER (AI ZONE)                   │
│                                                              │
│   • AI prompting / reasoning                                 │
│   • Semantic editing and enrichment                          │
│   • RAG / vector search source                               │
│   • Free-form document generation target                     │
└──────────────────────────────────────────────────────────────┘
                       │
          ┌────────────┼────────────────────┐
          │            │                    │
          ▼            ▼                    ▼
┌──────────────┐ ┌───────────────┐ ┌───────────────────────────┐
│ FREE-FORM    │ │ TEMPLATE-BASED│ │ IN-PLACE EDITING           │
│ OUTPUT       │ │ OUTPUT        │ │                            │
│              │ │               │ │ docx4j (DOCX)              │
│ Pandoc       │ │ docx4j        │ │ Open existing .docx,       │
│ Markdown →   │ │ Fill          │ │ locate section by heading  │
│   DOCX       │ │ placeholders  │ │ text or SDT name, replace  │
│   PDF        │ │ in branded    │ │ text runs preserving       │
│              │ │ .docx         │ │ <w:rPr> styles, save back. │
│ LibreOffice  │ │ templates     │ │                            │
│ DOCX → PDF   │ │               │ │ POI XSLF (PPTX)            │
│ (hi fidelity)│ │ LibreOffice   │ │ Find shape by slide +      │
│              │ │ → PDF opt.    │ │ name, replace XSLFTextRun, │
│              │ │               │ │ save back. (later phase)   │
└──────────────┘ └───────────────┘ └───────────────────────────┘

ROUND-TRIP EDITING PATTERN
───────────────────────────
  Existing DOCX
       │
       ├──► Docling ──► Markdown  (AI reads and reasons — never written back)
       │                   │
       │              AI identifies section + produces new content
       │                   │
       └──► docx4j ─────────────► Modified DOCX  (OOXML manipulation only)
```

---

## 3. Ingestion Pipeline (Docling)

**Tool:** [Docling](https://docling-project.github.io/docling/) — Python-based, invoked via Java `ProcessBuilder`.

**Rationale:** Docling is the strongest open-source document ingestion engine available. It handles PDF (including scanned/complex layouts), DOCX, and PPTX, preserves semantic structure (headings, tables, lists, captions), and exports clean Markdown. Native JVM alternatives (PDFBox, POI) are weaker at semantic structure recovery, especially for PDF.

**Invocation contract:**

```
docling --to md --image-export-dir <tmp_dir> <input_file>
```

- Output: Markdown file alongside extracted image files (PNG)
- Agentica reads both from the temp directory
- `ProcessBuilder` manages stdin/stdout/stderr; exit code checked

**Supported input formats:**
- PDF (`.pdf`)
- DOCX (`.docx`)
- PPTX (`.pptx`)

**Tools exposed to agent:**
- `files.read_pdf`
- `files.read_docx`
- `files.read_pptx`

All three follow the same pipeline: Docling → Markdown (→ optional Vision enrichment) → route to scratchpad if body > 8000 chars.

**Graceful degradation:** If Docling is not installed, tools return a structured error with installation instructions rather than failing silently.

---

## 4. Vision Enrichment

After Docling ingestion, any extracted image files (charts, figures, diagrams) are submitted to the configured Vision LLM.

**Pipeline:**

1. Docling writes extracted images to a temp directory (`--image-export-dir`)
2. For each extracted image, Agentica calls the Vision LLM with the image (base64-encoded) and a prompt asking for a semantic description
3. Descriptions are injected back into the Markdown at the image reference location
4. The enriched Markdown is returned as the tool result

**Vision LLM requirements:** The active `LLMProvider` must support multimodal input. If the provider does not support vision, image descriptions are skipped and the Markdown image placeholder is left in place (with a note that vision enrichment was skipped).

**Opt-out:** Vision enrichment is optional per tool call via an `enrich_images=false` arg.

---

## 5. Output Rendering

### 5a. Free-Form Generation (Pandoc)

**Use case:** AI-generated documents where formatting is generic — reports, summaries, analyses.

**Pipeline:** AI produces Markdown → Pandoc converts to target format.

| Target | Command |
|--------|---------|
| DOCX | `pandoc input.md -o output.docx` |
| PDF | `pandoc input.md -o output.pdf` (via LaTeX or wkhtmltopdf) |

**LibreOffice fallback for DOCX → PDF:** When higher fidelity PDF is needed from a DOCX (e.g. for a document that was template-filled), LibreOffice headless is used:

```
libreoffice --headless --convert-to pdf output.docx
```

**Tools exposed:**
- `files.write_markdown` — write raw Markdown to disk
- `files.markdown_to_docx` — Markdown → DOCX via Pandoc
- `files.markdown_to_pdf` — Markdown → PDF via Pandoc; DOCX→PDF via LibreOffice

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
1. `files.read_docx` → Docling → Markdown stored in scratchpad *(agent reads and reasons — this path is read-only)*
2. Agent identifies which section(s) need changing and produces new content
3. `files.edit_docx` → docx4j:
   - Locate target paragraph block using one of three strategies (in order of preference):
     1. **Named SDT (Structured Document Tag):** find by `<w:sdtPr><w:tag w:val="..."/>` — most reliable, requires template authored with named content controls
     2. **Heading text match:** find `<w:pStyle val="HeadingN">` paragraph whose text matches the section name, then replace the paragraph block between that heading and the next same-or-higher heading
     3. **Proximity match:** plain text search within a paragraph range — last resort, less precise
   - For each matched paragraph run: replace text content; copy `<w:rPr>` (font, size, bold, colour) from the first existing run of the paragraph to all newly inserted runs, preserving visual style
   - Save to original path or a new output path

**Inline formatting:** A thin Markdown-to-runs converter handles paragraph-level inline formatting (`**bold**` → `<w:b>`, `*italic*` → `<w:i>`, plain text → unstyled run). Complex Markdown (tables, code blocks, images) is not supported in-place; use `files.fill_template` or `files.markdown_to_docx` for those.

**Tools exposed:**
- `files.edit_docx path=... section="..." content="..."` — replace a single named section
- `files.patch_docx path=... patches=[{section, content}, ...]` — replace multiple sections in one docx4j pass; more efficient than sequential single edits

### 5d. PPTX Editing and Generation (Apache POI XSLF — later)

**Read:** Available from Stage B via Docling.

**In-place edit:** Open existing `.pptx` with POI XSLF; find shape by slide index + shape name/title; replace `XSLFTextRun` content; save back. Same dual-layer principle as DOCX editing — Docling for reasoning, POI for writing.

**Generate new:** Accept slide structure from agent (title, bullet content per slide); generate `.pptx` from scratch via POI XSLF.

Both are deferred until the DOCX pipeline (Stages B–E) is stable. Tool surface: `files.edit_pptx`, `files.write_pptx`.

---

## 6. External Dependencies

| Dependency | Role | Install Required By User |
|------------|------|--------------------------|
| Docling | Document ingestion (read PDF/DOCX/PPTX → Markdown) | Yes — `pip install docling` |
| Pandoc | Free-form Markdown → DOCX / PDF | Yes — system package |
| LibreOffice | DOCX → PDF (high fidelity) | Yes — system package |
| docx4j | JVM library — DOCX template filling | No — Maven dependency |
| Apache POI (XSLF) | JVM library — PPTX generation (later) | No — Maven dependency |
| Vision LLM | Chart/image description | Depends on provider config |

**Detection:** At startup and on first use, Agentica checks for each external binary (`docling`, `pandoc`, `soffice`). Detection results are cached and surfaced in a `deps.check` tool and in the Settings UI. Missing tools degrade gracefully per-feature rather than failing the whole application.

---

## 7. Key Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Document ingestion engine | Docling (single engine) | Best semantic preservation across PDF/DOCX/PPTX; avoids maintaining three separate parsers |
| Invocation model | `ProcessBuilder` | Docling is Python; keeps JVM dependency graph clean |
| Canonical format | Markdown | Natural for LLMs; works for RAG, editing, prompting, and rendering |
| Free-form output | Pandoc | Mature, format-faithful, widely installed |
| Template output | docx4j | Programmatic control over DOCX structure; enterprise template fidelity |
| Round-trip editing | docx4j in-place OOXML manipulation | Pandoc DOCX→MD→DOCX destroys styles, images, tracked changes; docx4j preserves `<w:rPr>` and surrounding structure |
| Read layer vs write layer | Strictly separated (Docling reads, docx4j/POI writes) | Prevents the temptation to round-trip through Markdown; Markdown path is always read-only for existing documents |
| DOCX → PDF | LibreOffice headless | Highest fidelity for complex DOCX layouts; no vendor lock-in |
| Vision enrichment | Via existing `LLMProvider` | Reuses configured provider; no separate vision client needed |
| Scratchpad routing | Existing `SessionScratchpad` | Large document content (>8000 chars) stored as `$scratch/` refs; consistent with other tools |
| PPTX write/edit | Deferred (POI XSLF) | Read is available immediately; write complexity justified only after DOCX pipeline is proven |

---

## 8. Implementation Stages

See [tasklist.md](tasklist.md) Phase 3 — Document Tools for the detailed task breakdown.

**Stage A** — External dependency detection and graceful degradation  
**Stage B** — Document ingestion via Docling (`files.read_pdf`, `files.read_docx`, `files.read_pptx`)  
**Stage C** — Vision enrichment (image extraction → Vision LLM → Markdown injection)  
**Stage D** — Free-form generation (`files.write_markdown`, `files.markdown_to_docx`, `files.markdown_to_pdf`)  
**Stage E** — Template-based generation + in-place editing via docx4j (`files.list_templates`, `files.fill_template`, `files.edit_docx`, `files.patch_docx`)  
**Stage F** — PPTX in-place editing and generation via Apache POI XSLF (`files.edit_pptx`, `files.write_pptx` — deferred)  
**Stage G** — Integration tests across all stages  
