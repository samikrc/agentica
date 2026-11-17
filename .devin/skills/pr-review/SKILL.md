---
name: pr-review
description: >
  Review workspace changes, a branch vs. its base, a commit, or a PR using
  the open-code-review delegate workflow: file selection via git, then a
  per-file evidence-bound review by the agent's own model with
  severity-tagged findings. Use when the user asks to review changes/PR,
  run a code review, or check a diff before merging.
---

# PR Review (delegate mode — host model does the review)

Adapted from `alibaba/open-code-review`'s delegate skill so the review runs
on the host agent's model with zero external LLM credentials.

## Step 1 — Determine the review target

| User says | Range |
|-----------|-------|
| "review my changes" | workspace: staged + unstaged + untracked |
| "review this branch / PR" | `merge-base` of `main` (or PR base)..`HEAD` |
| "review commit X" | that commit only |
| "review PR N" | `gh pr diff N` or the PR's base..head range |

## Step 2 — List reviewable files

If `ocr` is on PATH, prefer:
```bash
ocr delegate preview --format json [--from main]   # + ocr delegate rule for per-language checklists
```

Otherwise use git directly:

```bash
git --no-pager merge-base main HEAD                # → BASE
git --no-pager diff --name-status BASE...HEAD
```

Exclude generated files, lockfiles, and binaries. Build a checklist of every
reviewable `(path, status)` — coverage is mandatory: each ends as `reviewed`
or `skipped` with a reason.

## Step 3 — Review each file

Get the diff per file:

```bash
git --no-pager diff --no-ext-diff --no-textconv --no-color BASE..HEAD -- "<path>"
# untracked files: read them directly (entire file is new)
```

Review prompt (from OCR's `main_task_system.md`):

- `-` = deleted, `+` = added; **focus on issues in newly added code**.
- Be objective; when context is unclear, read the surrounding code rather
  than assume (`git show <ref>:<path>`, grep for callers, read neighbors).
- Avoid commenting on correct code, unchanged code, or deleted code.
- Focus on code logic and functionality — not comments, metadata, or
  generated indicators.
- Cross-file observations encouraged: look for inconsistencies and broken
  contracts across the changed set; findings must still target changed code.
- Give **every** file its own pass — do not stop at the first high-severity
  issue; a small/config/test file still gets reviewed.

## Step 4 — Findings format

Each finding:

| Field | Values |
|-------|--------|
| path, start_line–end_line | location in the new file |
| category | bug, security, performance, maintainability, test, style, documentation, other |
| severity | critical, high, medium, low |
| content | what's wrong + evidence; include a suggestion when concrete |

Verify before claiming: confirm the code actually does what you think — the
bar is "would a senior reviewer be confident posting this?" Discard likely
false positives silently.

## Step 5 — Report

```markdown
## Code Review Results
**Files**: N reviewed, M skipped (coverage: 100%)
**Findings**: X critical, Y high, Z medium

### Critical / High
- **`path:line`** [category] — issue
  > Fix: concrete suggestion

### Medium
- ...

### Skipped
- `path` — reason
```

- Critical/High: always report. Medium: report with context. Low: only if
  clearly valuable — nitpicks go unsaid.
- Every checklist file accounted for.

## Step 6 — Optional actions (only when asked)

- **Fix**: if the user said "review and fix", apply Critical/High fixes
  directly, describe Medium fixes, skip Low. Verify tests after fixing.
- **Post to PR**: use `gh api repos/{owner}/{repo}/pulls/{n}/comments` to
  create inline review comments at the finding's `path`/`line`, or
  `gh pr review` for a summary comment.
