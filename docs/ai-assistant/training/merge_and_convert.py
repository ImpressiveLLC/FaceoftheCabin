#!/usr/bin/env python3
"""Merges the LoRA adapter (data/adapter/) into the base model and
converts the result to GGUF. Runs INSIDE the trainer container (needs
peft/transformers/gguf) -- export_to_ollama.sh calls this via
`docker compose run`, then handles the docker cp + `ollama create` step
on the bare host afterward, since that part needs host Docker access
this container deliberately doesn't have.

ADDED 2026-09-14: split out of export_to_ollama.sh once it became clear
that script's Python steps (need peft/transformers/gguf, only installed
in the container) and its docker cp/exec steps (need host Docker access,
not available inside the container) can't run in the same context.
"""
import subprocess
import sys
from pathlib import Path

DATA_DIR = Path(__file__).parent / "data"
ADAPTER_DIR = DATA_DIR / "adapter"
MERGED_DIR = DATA_DIR / "merged"
NEW_TAG = "cabin-assistant-poc1"
GGUF_PATH = DATA_DIR / f"{NEW_TAG}.gguf"
MODELFILE_PATH = DATA_DIR / "Modelfile"
LLAMA_CPP_CONVERT_SCRIPT = Path("/opt/llama.cpp/convert_hf_to_gguf.py")
# Path as seen INSIDE the ollama container after export_to_ollama.sh's
# `docker cp` step -- not this container's own path to the GGUF file.
CONTAINER_GGUF_PATH = f"/root/{NEW_TAG}.gguf"


def main():
    if not ADAPTER_DIR.is_dir():
        sys.exit(f"No adapter found at {ADAPTER_DIR} -- run train_sft.py first.")
    if not LLAMA_CPP_CONVERT_SCRIPT.is_file():
        sys.exit(
            f"{LLAMA_CPP_CONVERT_SCRIPT} not found -- docker-compose.yml's "
            "LLAMA_CPP_DIR mount must point at a real llama.cpp checkout."
        )

    print("Merging adapter into base model...")
    from peft import AutoPeftModelForCausalLM
    from transformers import AutoTokenizer

    model = AutoPeftModelForCausalLM.from_pretrained(str(ADAPTER_DIR))
    model = model.merge_and_unload()
    MERGED_DIR.mkdir(parents=True, exist_ok=True)
    model.save_pretrained(str(MERGED_DIR))
    AutoTokenizer.from_pretrained(str(ADAPTER_DIR)).save_pretrained(str(MERGED_DIR))

    print("Converting to GGUF...")
    result = subprocess.run(
        [sys.executable, str(LLAMA_CPP_CONVERT_SCRIPT), str(MERGED_DIR), "--outfile", str(GGUF_PATH)],
        check=False,
    )
    if result.returncode != 0:
        sys.exit(f"convert_hf_to_gguf.py failed with exit code {result.returncode}")

    print(f"GGUF written to {GGUF_PATH}")

    # Written here, not by export_to_ollama.sh on the host, because this
    # container runs as root -- files it creates under the bind-mounted
    # ./data show up root-owned on the host, and the host-side script
    # (running as a regular user) can't write a NEW file into that same
    # directory. Found via a real "Permission denied" on a real run.
    MODELFILE_PATH.write_text(f"FROM {CONTAINER_GGUF_PATH}\n")
    print(f"Modelfile written to {MODELFILE_PATH}")


if __name__ == "__main__":
    main()
