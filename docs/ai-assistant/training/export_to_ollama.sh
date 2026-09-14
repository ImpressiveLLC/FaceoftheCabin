#!/usr/bin/env bash
# Merges the LoRA adapter (data/adapter/) into the base model, converts to
# GGUF, and creates a NEW, separately-tagged Ollama model
# (cabin-assistant-poc1) -- never overwrites whatever tag production
# actually serves from.
#
# CORRECTED 2026-09-14 against real hardware: Ollama on this stack runs as
# a Docker container (ollama/ollama:latest, container name "ollama"), not
# a host-installed CLI -- there is no `ollama` binary on the host PATH at
# all. Its data lives in a named volume (infra_ollama_data:/root/.ollama),
# not a host bind-mount, so a GGUF/Modelfile produced on the host has to
# be copied INTO the container (docker cp) before `ollama create` can see
# it -- `docker exec ollama ollama create ...` running against a host path
# would silently fail to find the file. OLLAMA_CONTAINER below is
# configurable in case a different install uses a different name.
#
# LLAMA_CPP_DIR below must point at a llama.cpp checkout whose commit
# matches (or is compatible with) whatever build Ollama has bundled on
# the target machine -- check `docker exec ollama ollama --version` there
# rather than assuming this script's default is right.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ADAPTER_DIR="${SCRIPT_DIR}/data/adapter"
MERGED_DIR="${SCRIPT_DIR}/data/merged"
GGUF_PATH="${SCRIPT_DIR}/data/cabin-assistant-poc1.gguf"
LLAMA_CPP_DIR="${LLAMA_CPP_DIR:-$HOME/llama.cpp}"
NEW_TAG="cabin-assistant-poc1"
PRODUCTION_TAG="${PRODUCTION_OLLAMA_TAG:-llama3.2:3b}"
OLLAMA_CONTAINER="${OLLAMA_CONTAINER:-ollama}"

if [ "${NEW_TAG}" = "${PRODUCTION_TAG}" ]; then
  echo "REFUSING: new tag would collide with the production tag (${PRODUCTION_TAG})." >&2
  exit 1
fi

if [ ! -d "${ADAPTER_DIR}" ]; then
  echo "No adapter found at ${ADAPTER_DIR} -- run train_sft.py first." >&2
  exit 1
fi

if [ ! -d "${LLAMA_CPP_DIR}" ]; then
  echo "llama.cpp not found at ${LLAMA_CPP_DIR}." >&2
  echo "Clone it and match Ollama's bundled build, or set LLAMA_CPP_DIR." >&2
  exit 1
fi

echo "Merging adapter into base model..."
python3 - "$ADAPTER_DIR" "$MERGED_DIR" <<'PYEOF'
import sys
from peft import AutoPeftModelForCausalLM
from transformers import AutoTokenizer

adapter_dir, merged_dir = sys.argv[1], sys.argv[2]
model = AutoPeftModelForCausalLM.from_pretrained(adapter_dir)
model = model.merge_and_unload()
model.save_pretrained(merged_dir)
AutoTokenizer.from_pretrained(adapter_dir).save_pretrained(merged_dir)
PYEOF

echo "Converting to GGUF..."
python3 "${LLAMA_CPP_DIR}/convert_hf_to_gguf.py" "${MERGED_DIR}" --outfile "${GGUF_PATH}"

CONTAINER_GGUF="/root/${NEW_TAG}.gguf"
CONTAINER_MODELFILE="/root/Modelfile.${NEW_TAG}"
MODELFILE="${SCRIPT_DIR}/data/Modelfile"
cat > "${MODELFILE}" <<EOF
FROM ${CONTAINER_GGUF}
EOF

echo "Copying GGUF + Modelfile into the '${OLLAMA_CONTAINER}' container..."
docker cp "${GGUF_PATH}" "${OLLAMA_CONTAINER}:${CONTAINER_GGUF}"
docker cp "${MODELFILE}" "${OLLAMA_CONTAINER}:${CONTAINER_MODELFILE}"

echo "Creating Ollama tag '${NEW_TAG}' (production tag '${PRODUCTION_TAG}' left untouched)..."
docker exec "${OLLAMA_CONTAINER}" ollama create "${NEW_TAG}" -f "${CONTAINER_MODELFILE}"

echo
echo "Done. Verify the new tag is real and distinct from production:"
echo "  docker exec ${OLLAMA_CONTAINER} ollama list | grep -E '${NEW_TAG}|${PRODUCTION_TAG}'"
