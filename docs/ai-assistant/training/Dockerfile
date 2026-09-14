# CPU-first fine-tuning container for POC1. Never assumes a GPU --
# torch.cuda.is_available() gates any GPU-specific behavior in
# train_sft.py at runtime, so this same image is meant to work whether
# it's run on a CPU-only box (the common case: ilikethelights, a typical
# customer's home server) or a box with a real GPU (bluefin, if it turns
# out to have one), without a rebuild.
#
# CORRECTED 2026-09-14 against a real build on the M920q: the original
# unpinned `pip install torch` grabbed whatever the latest torch release
# is as of build time, which is newer than unsloth's own upper bound
# (`torch<=2.7.0,>=2.4.0`) -- pip's resolver then backtracked through
# EVERY historical unsloth release (observed: 2025.7.8 down through
# 2025.2.9 and still going after 23+ minutes) looking for one whose torch
# constraint happened to accept the too-new torch already installed.
# Fixed by pinning both torch and unsloth explicitly so there's nothing
# left to backtrack over. If bitsandbytes fails to install/import on this
# CPU-only image (its CPU support has historically been uneven), remove
# it and fall back to plain (non-quantized) LoRA via peft, and update
# this comment with what actually happened.
FROM python:3.11-slim

WORKDIR /app

# System deps for building any C-extension wheels that don't ship
# manylinux CPU wheels.
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential git \
    && rm -rf /var/lib/apt/lists/*

# CPU wheel of torch from PyTorch's own CPU index -- pinned to exactly
# unsloth's documented upper bound (torch<=2.7.0,>=2.4.0) so pip's
# resolver has nothing to backtrack over during THIS layer.
#
# CONFIRMED on the real build (2026-09-14): this pin gets overridden
# anyway -- unsloth 2025.7.8's own sub-dependencies (unsloth_zoo,
# xformers, triton) require a newer torch than 2.7.0, so the final
# `pip install unsloth` layer below uninstalls this 2.7.0+cpu and
# installs torch-2.12.1 instead. That's fine functionally (still gated
# by torch.cuda.is_available() at runtime, still CPU-only behavior) --
# keeping this layer anyway because it still avoids the *initial*
# resolver backtrack this fix was written for; the version that actually
# ends up installed is whatever unsloth's own dependency chain needs.
RUN pip install --no-cache-dir torch==2.7.0 --index-url https://download.pytorch.org/whl/cpu

# unsloth pinned to a specific known release for the same reason. The
# rest are left unpinned -- transformers/trl/peft/datasets/accelerate
# have much wider compatibility ranges and weren't part of the observed
# backtracking explosion.
#
# Split into separate RUN layers (not one combined install) and given a
# longer pip timeout: found on the M920q that unsloth's dependency chain
# unconditionally pulls several hundred MB of nvidia-*-cu13 CUDA wheels
# even on this CPU-only box (they're just unused dead weight here, never
# loaded since torch.cuda.is_available() is False) -- one of those
# (nvidia_cufft, 214MB) hit a slow network patch and timed out at
# 191/214MB. Splitting into layers means a retry doesn't have to re-fetch
# everything that already succeeded.
RUN pip install --no-cache-dir --default-timeout=300 transformers datasets accelerate peft
RUN pip install --no-cache-dir --default-timeout=300 trl
RUN pip install --no-cache-dir --default-timeout=300 bitsandbytes
RUN pip install --no-cache-dir --default-timeout=300 unsloth==2025.7.8

COPY export_grades_to_sft.py train_sft.py ./

# No ENTRYPOINT/CMD -- every stage of the pipeline is an explicit,
# manually-invoked command (see README.md), not a long-running service.
