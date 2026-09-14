#!/usr/bin/env bash
# Merges the LoRA adapter (data/adapter/) into the base model, converts to
# GGUF, and creates a NEW, separately-tagged Ollama model
# (cabin-assistant-poc1) -- never overwrites whatever tag production
# actually serves from.
#
# UNVERIFIED as of this write (2026-09-14): written from documented
# llama.cpp/Ollama conversion steps, not from a real run. In particular:
# LLAMA_CPP_DIR below must point at a llama.cpp checkout whose commit
# matches (or is compatible with) whatever build Ollama has bundled on
# the target machine -- check `ollama --version` there rather than
# assuming this script's default is right.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ADAPTER_DIR="${SCRIPT_DIR}/data/adapter"
MERGED_DIR="${SCRIPT_DIR}/data/merged"
GGUF_PATH="${SCRIPT_DIR}/data/cabin-assistant-poc1.gguf"
LLAMA_CPP_DIR="${LLAMA_CPP_DIR:-$HOME/llama.cpp}"
NEW_TAG="cabin-assistant-poc1"
PRODUCTION_TAG="${PRODUCTION_OLLAMA_TAG:-llama3.2:3b}"

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

MODELFILE="${SCRIPT_DIR}/data/Modelfile"
cat > "${MODELFILE}" <<EOF
FROM ${GGUF_PATH}
EOF

echo "Creating Ollama tag '${NEW_TAG}' (production tag '${PRODUCTION_TAG}' left untouched)..."
ollama create "${NEW_TAG}" -f "${MODELFILE}"

echo
echo "Done. Verify the new tag is real and distinct from production:"
echo "  ollama list | grep -E '${NEW_TAG}|${PRODUCTION_TAG}'"
