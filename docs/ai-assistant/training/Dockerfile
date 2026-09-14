# CPU-first fine-tuning container for POC1. Never assumes a GPU --
# torch.cuda.is_available() gates any GPU-specific behavior in
# train_sft.py at runtime, so this same image is meant to work whether
# it's run on a CPU-only box (the common case: ilikethelights, a typical
# customer's home server) or a box with a real GPU (bluefin, if it turns
# out to have one), without a rebuild.
#
# UNVERIFIED as of this write (2026-09-14): this Dockerfile was authored
# from documented package names/install patterns, not from a real
# `docker build` run -- there was no Docker/Python-ML environment
# available in the session that wrote it. The first real build on actual
# hardware IS the verification step; expect to fix pinned versions here
# rather than assume they're already right. In particular, bitsandbytes'
# CPU-only support has historically been uneven -- if it fails to import,
# remove it and fall back to plain (non-quantized) LoRA via peft, and
# update this comment with what actually happened.
FROM python:3.11-slim

WORKDIR /app

# System deps for building any C-extension wheels that don't ship
# manylinux CPU wheels.
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential git \
    && rm -rf /var/lib/apt/lists/*

# CPU wheel of torch from PyTorch's own CPU index -- avoids pulling a
# multi-GB CUDA build on a box that will never use a GPU.
RUN pip install --no-cache-dir torch --index-url https://download.pytorch.org/whl/cpu

RUN pip install --no-cache-dir \
    unsloth \
    trl \
    peft \
    transformers \
    datasets \
    accelerate \
    bitsandbytes

COPY export_grades_to_sft.py train_sft.py ./

# No ENTRYPOINT/CMD -- every stage of the pipeline is an explicit,
# manually-invoked command (see README.md), not a long-running service.
