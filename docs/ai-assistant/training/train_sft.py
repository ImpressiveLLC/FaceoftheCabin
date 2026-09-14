#!/usr/bin/env python3
"""QLoRA SFT fine-tune of llama3.2:3b on data/sft_dataset.jsonl (produced
by export_grades_to_sft.py). Saves a LoRA adapter to data/adapter/ --
does not merge or export to GGUF (see export_to_ollama.sh for that).

CORRECTED 2026-09-14 against a real run on the M920q: the original
Unsloth-based version (FastLanguageModel) hard-fails with
`NotImplementedError: Unsloth currently only works on NVIDIA GPUs and
Intel GPUs` -- there is no CPU fallback inside Unsloth's device-detection
code at all, it's not a config flag. That directly contradicts this
POC's whole CPU-first design goal, so this now uses plain
transformers.AutoModelForCausalLM + peft.LoraConfig instead -- the same
underlying LoRA fine-tune, just without Unsloth's GPU-only kernel
optimizations. Also drops 4-bit quantization (load_in_4bit) --
bitsandbytes' CUDA kernels have the same GPU-only limitation -- in favor
of loading the frozen base model in bf16 (halves memory vs fp32; only
the small LoRA adapter needs gradients/optimizer state since the base
model stays frozen, so this is not the memory hit a full fine-tune would
be). Given the current dataset is single-digit examples (see README's
"Known limitations"), this is deliberately configured for a tiny
dataset -- few epochs, small batch -- not tuned for the
hundreds-to-thousands-of-examples case framework defaults usually assume.
"""
import json
from pathlib import Path

DATA_PATH = Path(__file__).parent / "data" / "sft_dataset.jsonl"
ADAPTER_OUT = Path(__file__).parent / "data" / "adapter"
BASE_MODEL = "unsloth/Llama-3.2-3B-Instruct"  # mirrors the llama3.2:3b tag Ollama serves


def load_dataset_dicts() -> list[dict]:
    if not DATA_PATH.exists():
        raise SystemExit(
            f"{DATA_PATH} does not exist -- run export_grades_to_sft.py first."
        )
    examples = [json.loads(line) for line in DATA_PATH.open() if line.strip()]
    if not examples:
        raise SystemExit(f"{DATA_PATH} is empty -- nothing to train on.")
    print(f"Loaded {len(examples)} training example(s) from {DATA_PATH}")
    if len(examples) < 20:
        print(
            "WARNING: fewer than 20 examples. This run is validating the "
            "pipeline mechanics, not producing a meaningfully improved model "
            "-- see README.md's Known limitations section."
        )
    return examples


def main():
    import torch
    from datasets import Dataset
    from peft import LoraConfig, get_peft_model
    from transformers import AutoModelForCausalLM, AutoTokenizer
    from trl import SFTConfig, SFTTrainer

    use_gpu = torch.cuda.is_available()
    print(f"CUDA available: {use_gpu} (this run will {'use' if use_gpu else 'NOT use'} a GPU)")
    # bf16 halves the frozen base model's memory footprint vs fp32; the
    # base weights are frozen (LoRA only trains the small adapter), so
    # this doesn't carry the precision-stability concerns a full
    # fine-tune's optimizer state would have in bf16.
    dtype = torch.bfloat16 if not use_gpu else torch.float16

    examples = load_dataset_dicts()
    dataset = Dataset.from_list(examples)

    tokenizer = AutoTokenizer.from_pretrained(BASE_MODEL)
    model = AutoModelForCausalLM.from_pretrained(BASE_MODEL, torch_dtype=dtype)

    model = get_peft_model(
        model,
        LoraConfig(
            r=16,
            lora_alpha=16,
            lora_dropout=0.0,
            target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
            task_type="CAUSAL_LM",
        ),
    )
    model.print_trainable_parameters()

    def format_example(example):
        return {"text": tokenizer.apply_chat_template(example["messages"], tokenize=False)}

    dataset = dataset.map(format_example)

    trainer = SFTTrainer(
        model=model,
        processing_class=tokenizer,
        train_dataset=dataset,
        args=SFTConfig(
            output_dir=str(ADAPTER_OUT),
            max_length=2048,
            per_device_train_batch_size=1,
            gradient_accumulation_steps=4,
            num_train_epochs=3,  # small on purpose -- tiny dataset, avoid overfitting further
            learning_rate=2e-4,
            logging_steps=1,
            save_strategy="no",  # we save the adapter explicitly below
            bf16=not use_gpu,
            fp16=use_gpu,
            report_to="none",
        ),
    )

    trainer.train()

    ADAPTER_OUT.mkdir(parents=True, exist_ok=True)
    model.save_pretrained(str(ADAPTER_OUT))
    tokenizer.save_pretrained(str(ADAPTER_OUT))
    print(f"Adapter saved to {ADAPTER_OUT}")


if __name__ == "__main__":
    main()
