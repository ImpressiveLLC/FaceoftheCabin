#!/usr/bin/env bash
# Runs merge_and_convert.py inside the trainer container (needs
# peft/transformers/gguf), then copies the resulting GGUF into the
# Ollama container and creates a NEW, separately-tagged model
# (cabin-assistant-poc1) -- never overwrites whatever tag production
# actually serves from.
#
# CORRECTED 2026-09-14 against real hardware, three times:
# 1. Ollama on this stack runs as a Docker container
#    (ollama/ollama:latest, container name "ollama"), not a
#    host-installed CLI -- there is no `ollama` binary on the host PATH
#    at all. Its data lives in a named volume
#    (infra_ollama_data:/root/.ollama), not a host bind-mount, so a
#    GGUF/Modelfile has to be copied INTO the container (docker cp)
#    before `ollama create` can see it. OLLAMA_CONTAINER below is
#    configurable in case a different install uses a different name.
# 2. The merge (peft/transformers) and GGUF conversion (gguf package)
#    steps need Python packages that only exist inside the trainer
#    container, but this script's docker cp/exec steps need host Docker
#    access the container deliberately doesn't have -- split into
#    merge_and_convert.py (runs in-container via `docker compose run`)
#    plus the host-level handoff below, run from THIS script directly on
#    the host, not inside any container.
# 3. The trainer container runs as root, so files it writes under the
#    bind-mounted ./data (including the GGUF itself) are root-owned on
#    the host -- this script couldn't write a NEW file (Modelfile) into
#    that same directory ("Permission denied", found on a real run).
#    merge_and_convert.py now writes the Modelfile itself (it already has
#    write access there); this script only reads it.
#
# Requires: EVAL_RESULTS_DIR and LLAMA_CPP_DIR set (see README.md) --
# same already-expanded-absolute-path rule as every other script here.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GGUF_PATH="${SCRIPT_DIR}/data/cabin-assistant-poc1.gguf"
NEW_TAG="cabin-assistant-poc1"
PRODUCTION_TAG="${PRODUCTION_OLLAMA_TAG:-llama3.2:3b}"
OLLAMA_CONTAINER="${OLLAMA_CONTAINER:-ollama}"

if [ "${NEW_TAG}" = "${PRODUCTION_TAG}" ]; then
  echo "REFUSING: new tag would collide with the production tag (${PRODUCTION_TAG})." >&2
  exit 1
fi

echo "Running merge + GGUF conversion inside the trainer container..."
( cd "${SCRIPT_DIR}" && docker compose run --rm trainer python merge_and_convert.py )

MODELFILE="${SCRIPT_DIR}/data/Modelfile"
if [ ! -f "${GGUF_PATH}" ] || [ ! -f "${MODELFILE}" ]; then
  echo "Expected ${GGUF_PATH} and ${MODELFILE} but at least one is missing -- merge_and_convert.py must have failed silently." >&2
  exit 1
fi

CONTAINER_GGUF="/root/${NEW_TAG}.gguf"
CONTAINER_MODELFILE="/root/Modelfile.${NEW_TAG}"

echo "Copying GGUF + Modelfile into the '${OLLAMA_CONTAINER}' container (host-level step, not the trainer container)..."
docker cp "${GGUF_PATH}" "${OLLAMA_CONTAINER}:${CONTAINER_GGUF}"
docker cp "${MODELFILE}" "${OLLAMA_CONTAINER}:${CONTAINER_MODELFILE}"

echo "Creating Ollama tag '${NEW_TAG}' (production tag '${PRODUCTION_TAG}' left untouched)..."
docker exec "${OLLAMA_CONTAINER}" ollama create "${NEW_TAG}" -f "${CONTAINER_MODELFILE}"

echo
echo "Done. Verify the new tag is real and distinct from production:"
echo "  docker exec ${OLLAMA_CONTAINER} ollama list | grep -E '${NEW_TAG}|${PRODUCTION_TAG}'"
