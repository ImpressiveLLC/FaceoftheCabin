#!/usr/bin/env python3
"""Offline synthetic harness for resumable chunks (Colab slice A). A tiny, randomly initialized Llama-shaped model and
invented token rows: no download, no network, no real data, no base model. It drives the SAME run_resumable_chunk()
that train_sft.py uses, so what it proves about resume is about the real code path, not a copy of it.

One invocation = one chunk in a fresh process:
  python resume_harness.py --store S --run-id R --run-dir D --total-steps T --chunk-steps N
It writes D/trace.json: per trained micro-batch, the global step and a hash of its token ids, plus the log history.

This is a mechanics proof on CPU. It says nothing about model quality, GPU memory or Colab.
"""
import argparse
import hashlib
import json
from pathlib import Path

import train_sft

SEED = 20261123
ROWS = 12
PAD_ID = 0
BINDINGS = {"base_revision": "synthetic-tiny-llama", "code_sha256": None, "lock_sha256": "synthetic",
            "data_sha256": None, "environment_id": "harness-cpu"}


def synthetic_rows(count=ROWS, seed=SEED):
    import random
    rng = random.Random(seed)
    rows = []
    for index in range(count):
        length = rng.randint(6, 14)
        ids = [rng.randint(1, 63) for _ in range(length)]
        split = rng.randint(2, length - 2)
        rows.append({"id": f"synthetic-{index:03d}", "input_ids": ids, "attention_mask": [1] * length,
                     "labels": [-100] * split + ids[split:]})
    return rows


def rows_sha256(rows):
    return hashlib.sha256(json.dumps(rows, sort_keys=True).encode()).hexdigest()


def tiny_model(seed=SEED, dropout=0.0):
    import torch
    from peft import LoraConfig, get_peft_model
    from transformers import LlamaConfig, LlamaForCausalLM
    torch.manual_seed(seed)
    config = LlamaConfig(vocab_size=64, hidden_size=16, intermediate_size=32, num_hidden_layers=2,
                         num_attention_heads=2, num_key_value_heads=2, max_position_embeddings=64)
    model = LlamaForCausalLM(config)
    return get_peft_model(model, LoraConfig(r=4, lora_alpha=4, lora_dropout=dropout, task_type="CAUSAL_LM",
                                            target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj",
                                                            "up_proj", "down_proj"]))


def run(store: Path, run_id: str, run_dir: Path, total_steps: int, chunk_steps: int, fault=None, seed=SEED,
        dropout=0.0, drop_rng_on_restore=False) -> dict:
    import torch
    import sft_tokenize as st
    from transformers import Trainer, TrainingArguments, set_seed

    rows = synthetic_rows()
    bindings = train_sft.run_bindings(base_revision=BINDINGS["base_revision"], data_sha256=rows_sha256(rows),
                                      environment_id=BINDINGS["environment_id"], total_steps=total_steps,
                                      seed=seed, use_gpu=False, lock_sha256=BINDINGS["lock_sha256"])
    run_dir.mkdir(parents=True)
    trainer_dir = run_dir / "trainer"
    trainer_dir.mkdir()
    set_seed(seed)
    model = tiny_model(dropout=dropout)
    trace = []
    holder = {}

    def collate(batch):
        padded = st.collate(batch, PAD_ID)
        return {key: torch.tensor(value) for key, value in padded.items()}

    def record(module, args, kwargs):
        # The trace comes from the forward pass, which only trained micro-batches reach. The DataLoader prefetches one
        # batch ahead, so a collate-side trace would also list a batch that was loaded at a chunk boundary but never
        # trained.
        if module.training and "input_ids" in kwargs:
            state = holder["trainer"].state
            trace.append({"global_step": state.global_step,
                          "ids_sha256": hashlib.sha256(json.dumps(kwargs["input_ids"].tolist()).encode()).hexdigest()})

    model.register_forward_pre_hook(record, with_kwargs=True)

    def build_trainer(max_steps, callbacks):
        holder["trainer"] = Trainer(model=model, train_dataset=rows, data_collator=collate, callbacks=callbacks,
                                    args=TrainingArguments(**train_sft.training_args_kwargs(
                                        trainer_dir, seed, use_gpu=False, max_steps=max_steps)))
        return holder["trainer"]

    if drop_rng_on_restore:  # test-only mutant: a resume that loses the RNG state
        import resumable as rs
        real_restore = rs.restore

        def restore_without_rng(record, checkpoint_dir):
            out = real_restore(record, checkpoint_dir)
            (out / "rng_state.pth").unlink()
            return out

        rs.restore = restore_without_rng
    manifest = train_sft.run_resumable_chunk(build_trainer=build_trainer, trainer_dir=trainer_dir, store=store,
                                             run_id=run_id, owner=run_dir.name, total_steps=total_steps,
                                             chunk_steps=chunk_steps, bindings=bindings, fault=fault,
                                             estimate_bytes=train_sft.lower_bound_checkpoint_bytes(model))
    out = {"manifest": manifest, "trace": trace,
           "log_history": holder["trainer"].state.log_history if "trainer" in holder else []}
    (run_dir / "trace.json").write_text(json.dumps(out, sort_keys=True, default=str), encoding="utf-8")
    return out


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--store", type=Path, required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--run-dir", type=Path, required=True)
    parser.add_argument("--total-steps", type=int, required=True)
    parser.add_argument("--chunk-steps", type=int, required=True)
    parser.add_argument("--seed", type=int, default=SEED)
    parser.add_argument("--dropout", type=float, default=0.0, help="LoRA dropout; >0 makes the run depend on RNG state")
    parser.add_argument("--drop-rng-on-restore", action="store_true", help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    run(args.store, args.run_id, args.run_dir, args.total_steps, args.chunk_steps, seed=args.seed,
        dropout=args.dropout, drop_rng_on_restore=args.drop_rng_on_restore)


if __name__ == "__main__":
    main()
