#!/usr/bin/env bash
set -euo pipefail

# Run the DocEvalTest suite in a loop for the local models we want to compare.
#
# Usage:
#   cd /home/samik/git/myprojects/agentica/backend
#   ./src/test/resources/run-eval-loop.sh
#
# The script updates src/test/resources/eval-config.json for each provider,
# runs the suite, and writes per-provider logs to logs/eval-<label>.log.
#
# Each MODELS entry is:  label|llmModel|llmBaseURL|llmAPIKey
# For remote providers (e.g. Valar), write the API key as a ${VAR} placeholder —
# the eval harness resolves it from the environment at runtime, so no secret is
# stored on disk. Example:
#   "valar-gpt5|gpt-5|https://api.valarhq.ai|\${VALAR_API_KEY}"
# then run with:  VALAR_API_KEY=sk-... ./run-eval-loop.sh
#
# To run only Valar (without editing this file), override MODELS on the command
# line — the secret still comes from the environment:
#   VALAR_API_KEY=sk-... \
#     MODELS=("valar-gpt5|gpt-5|https://api.valarhq.ai|\${VALAR_API_KEY}") \
#     ./src/test/resources/run-eval-loop.sh
#
# To run only Valar by editing this file, comment out the local entries and
# uncomment the Valar entry below, then run:
#   VALAR_API_KEY=sk-... ./src/test/resources/run-eval-loop.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
cd "${BACKEND_DIR}"

CONFIG_FILE="src/test/resources/eval-config.json"
LOG_DIR="logs"
mkdir -p "${LOG_DIR}"

# Preserve the original config so we can restore it when the loop finishes.
ORIGINAL_CONFIG="${LOG_DIR}/eval-config.json.orig"
cp "${CONFIG_FILE}" "${ORIGINAL_CONFIG}"
restore_config() {
    cp "${ORIGINAL_CONFIG}" "${CONFIG_FILE}"
}
trap restore_config EXIT

# Provider entries: label|llmModel|llmBaseURL|llmAPIKey
# llmAPIKey may be a ${VAR} placeholder resolved from the environment at runtime.
MODELS=(
    "local-qwen-3.5|qwen3.5-9b-mtp|http://192.168.2.126:1234|lm-studio"
    "local-ministral-3|mistralai/ministral-3-14b-reasoning|http://192.168.2.126:1234|lm-studio"
    "local-gemma4-it|google/gemma-4-12b|http://192.168.2.126:1234|lm-studio"
    "local-gemma4-e4b|google/gemma-4-e4b|http://192.168.2.126:1234|lm-studio"
    # "valar-glm-5.3|zai-org/GLM-5.3|https://api.valarhq.ai|\${VALAR_API_KEY}"
    # "valar-glm-5.3-flash|zai-org/GLM-5.3-Flash|https://api.valarhq.ai|\${VALAR_API_KEY}"
    # "valar-kimi-k3|moonshotai/Kimi-K3|https://api.valarhq.ai|\${VALAR_API_KEY}"
)

for entry in "${MODELS[@]}"; do
    label="${entry%%|*}"
    rest="${entry#*|}"
    model="${rest%%|*}"
    rest="${rest#*|}"
    base_url="${rest%%|*}"
    api_key="${rest#*|}"

    echo "========================================"
    echo "Provider: ${label}"
    echo "Model:    ${model}"
    echo "Base URL: ${base_url}"
    echo "========================================"

    CONFIG_PATH="${CONFIG_FILE}" EVAL_LABEL="${label}" EVAL_MODEL="${model}" \
        EVAL_BASE_URL="${base_url}" EVAL_API_KEY="${api_key}" python3 - <<'PY'
import json
import os

config_path = os.environ["CONFIG_PATH"]
with open(config_path, "r") as f:
    config = json.load(f)

if not isinstance(config, dict) or not config.get("documents") or not config.get("providers"):
    raise SystemExit('Use a run config object with non-empty "documents" and "providers" arrays.')
provider = dict(config["providers"][0])
provider["label"] = os.environ["EVAL_LABEL"]
provider["llmModel"] = os.environ["EVAL_MODEL"]
provider["llmBaseURL"] = os.environ["EVAL_BASE_URL"]
provider["llmAPIKey"] = os.environ["EVAL_API_KEY"]
config["providers"] = [provider]

with open(config_path, "w") as f:
    json.dump(config, f, indent=2)
    f.write("\n")
PY

    echo "Updated ${CONFIG_FILE}: label=${label}, llmModel=${model}, llmBaseURL=${base_url}"

    mvn test -Dsuites=agentica.eval.DocEvalTest 2>&1 | tee "${LOG_DIR}/eval-${label}.log"

done

echo "========================================"
echo "All provider runs complete."
echo "Logs: ${LOG_DIR}/"
echo "========================================"
