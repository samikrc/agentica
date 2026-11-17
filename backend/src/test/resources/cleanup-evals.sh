#!/usr/bin/env bash
set -euo pipefail

# Delete evaluation result folders older than two weeks.
#
# Usage:
#   ./src/test/resources/cleanup-evals.sh            # delete folders 14+ days old
#   ./src/test/resources/cleanup-evals.sh --dry-run  # list what would be deleted
#   DAYS=30 ./src/test/resources/cleanup-evals.sh    # custom age threshold
#
# The evals directory defaults to the harness's AppDirs location and can be
# overridden with EVALS_DIR. The .markdown-cache directory is never touched.

EVALS_DIR="${EVALS_DIR:-$HOME/.local/state/Agentica/evals}"
DAYS="${DAYS:-14}"
DRY_RUN=0
[[ "${1:-}" == "--dry-run" || "${1:-}" == "-n" ]] && DRY_RUN=1

if [[ ! -d "$EVALS_DIR" ]]; then
    echo "Evals directory not found: $EVALS_DIR" >&2
    exit 1
fi

# Top-level result dirs only; .markdown-cache is a hidden dir so it is not matched.
mapfile -t old < <(find "$EVALS_DIR" -mindepth 1 -maxdepth 1 -type d -name "*-eval-*" -mtime "+$((DAYS - 1))" | sort)

if (( ${#old[@]} == 0 )); then
    echo "Nothing to delete: no result folders older than $DAYS days in $EVALS_DIR"
    exit 0
fi

if (( DRY_RUN )); then
    echo "Would delete ${#old[@]} folder(s):"
    printf '  %s\n' "${old[@]}"
    du -sch "${old[@]}" 2>/dev/null | tail -1
    exit 0
fi

echo "Deleting ${#old[@]} result folder(s) older than $DAYS days:"
printf '  %s\n' "${old[@]}"
du -sch "${old[@]}" 2>/dev/null | tail -1
rm -rf "${old[@]}"
echo "Done."
