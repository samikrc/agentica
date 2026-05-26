#!/usr/bin/env bash
set -euo pipefail

# ── Configuration ──────────────────────────────────────────────
LLM_BASE_URL="${LLM_BASE_URL:-http://localhost:1234}"
LLM_MODEL="${LLM_MODEL:-mistralai/ministral-3-14b-reasoning}"
AGENTICA_DEV_TOKEN="${AGENTICA_DEV_TOKEN:-dev-token}"
AGENTICA_PORT="${AGENTICA_PORT:-8080}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/backend/target/agentica-backend-1.0-SNAPSHOT.jar"
export AGENTICA_UI_ROOT="$SCRIPT_DIR/ui"
# ────────────────────────────────────────────────────────────────

if [[ ! -f "$JAR" ]]; then
    echo "ERROR: Fat-jar not found at $JAR"
    echo "Build it first with:  cd backend && mvn package -DskipTests"
    exit 1
fi

export LLM_BASE_URL LLM_MODEL AGENTICA_DEV_TOKEN AGENTICA_PORT

echo "Starting Agentica backend..."
echo "Open your browser at: http://localhost:${AGENTICA_PORT}/?token=${AGENTICA_DEV_TOKEN}"
echo "Press Ctrl+C to stop."
echo

exec java -Djava.awt.headless=true -jar "$JAR"
