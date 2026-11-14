# CPU-first fine-tuning container for POC1. Never assumes a GPU --
# torch.cuda.is_available() gates any GPU-specific behavior in
# train_sft.py at runtime, so this same image is meant to work whether
# it's run on a CPU-only box (the common case: ilikethelights, a typical
# customer's home server) or a box with a real GPU (bluefin, if it turns
# out to have one), without a rebuild.
#
# CORRECTED 2026-09-14, twice, against real runs on the M920q:
# 1. The original unpinned `pip install torch` grabbed whatever the
#    latest torch release is as of build time, newer than unsloth's own
#    upper bound (torch<=2.7.0,>=2.4.0) -- pip's resolver backtracked
#    through EVERY historical unsloth release (observed: 2025.7.8 down
#    through 2025.2.9, still unresolved after 23+ minutes) looking for
#    one that would accept the too-new torch. Fixed by pinning torch.
# 2. unsloth's FastLanguageModel then turned out to hard-require an
#    actual NVIDIA/Intel GPU at import time (NotImplementedError, no CPU
#    fallback exists in the library) -- directly contradicts this POC's
#    CPU-first goal. Switched train_sft.py to plain
#    transformers.AutoModelForCausalLM + peft.LoraConfig instead, which
#    both unsloth AND bitsandbytes (its CUDA-only 4-bit quantization,
#    no longer used since load_in_4bit was dropped) are now genuinely
#    unused dead weight for -- removed both rather than leave ~1GB+ of
#    unused NVIDIA CUDA wheels, xformers, and triton installed.
FROM python:3.11-slim

WORKDIR /app

# System deps for building any C-extension wheels that don't ship
# manylinux CPU wheels.
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential git \
    && rm -rf /var/lib/apt/lists/*

# Dependencies come from requirements.lock (resolved with `uv pip compile --generate-hashes` for Python 3.11,
# linux x86_64, from requirements.in; regenerate that way, never hand-edit). The CPU wheel of torch comes from
# PyTorch's own CPU index, so that index is added; every file is hash-checked. NOT_RUN: this layer has not been
# built against the lock yet (P3, #244). Network timeouts on large wheels were seen on the M920q, hence the long timeout.
COPY requirements.lock ./
RUN pip install --no-cache-dir --default-timeout=300 --require-hashes -r requirements.lock     --extra-index-url https://download.pytorch.org/whl/cpu

COPY export_grades_to_sft.py train_sft.py sft_tokenize.py merge_and_convert.py ./

# No ENTRYPOINT/CMD -- every stage of the pipeline is an explicit,
# manually-invoked command (see README.md), not a long-running service.
