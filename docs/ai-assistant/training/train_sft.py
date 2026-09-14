#!/usr/bin/env python3
"""QLoRA SFT fine-tune of llama3.2:3b on data/sft_dataset.jsonl (produced
by export_grades_to_sft.py). Saves a LoRA adapter to data/adapter/ --
does not merge or export to GGUF (see export_to_ollama.sh for that).

UNVERIFIED as of this write (2026-09-14): written from Unsloth/TRL's
documented APIs as of mid-2026, not from a real training run -- there was
no Python-ML environment available in the session that wrote this. Real
package versions, exact API signatures, and CPU training throughput all
need confirming on real hardware. Given the current dataset will be
single-digit examples (see README's "Known limitations"), this is
deliberately configured for a tiny dataset -- few epochs, small batch --
not tuned for the hundreds-to-thousands-of-examples case that framework
defaults usually assume.
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
    from trl import SFTConfig, SFTTrainer
    from unsloth import FastLanguageModel

    use_gpu = torch.cuda.is_available()
    print(f"CUDA available: {use_gpu} (this run will {'use' if use_gpu else 'NOT use'} a GPU)")

    examples = load_dataset_dicts()
    dataset = Dataset.from_list(examples)

    model, tokenizer = FastLanguageModel.from_pretrained(
        model_name=BASE_MODEL,
        max_seq_length=2048,
        load_in_4bit=True,  # QLoRA -- if bitsandbytes fails on this box, set False (see Dockerfile note)
    )

    model = FastLanguageModel.get_peft_model(
        model,
        r=16,
        lora_alpha=16,
        lora_dropout=0.0,
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
    )

    def format_example(example):
        return {"text": tokenizer.apply_chat_template(example["messages"], tokenize=False)}

    dataset = dataset.map(format_example)

    trainer = SFTTrainer(
        model=model,
        tokenizer=tokenizer,
        train_dataset=dataset,
        dataset_text_field="text",
        max_seq_length=2048,
        args=SFTConfig(
            output_dir=str(ADAPTER_OUT),
            per_device_train_batch_size=1,
            gradient_accumulation_steps=4,
            num_train_epochs=3,  # small on purpose -- tiny dataset, avoid overfitting further
            learning_rate=2e-4,
            logging_steps=1,
            save_strategy="no",  # we save the adapter explicitly below
            fp16=not use_gpu is False,  # let Unsloth/TRL pick a sane default per hardware
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
