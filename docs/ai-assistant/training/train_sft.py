#!/usr/bin/env python3
"""QLoRA SFT fine-tune of llama3.2:3b on data/sft_dataset.jsonl (produced
by export_grades_to_sft.py). Saves a LoRA adapter to RUN_DIR/adapter/ --
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

CHANGED 2026-11-14 (P3, training contract R2/R3, #244): (1) the base model and tokenizer are pinned to one
revision and the tokenizer file hash is checked; (2) text is rendered by sft_tokenize.py from the served Ollama
template (no day-dependent "Today Date" line) instead of apply_chat_template; (3) loss covers assistant answers
only (every other token has label -100), replacing the single `text` field that trained on all tokens; (4) a seed
is set; (5) `--check-only` tokenizes and masks the data without importing torch or loading any model. The model
path below is NOT_RUN on any host: it has not been executed against the pinned dependency lock.

CHANGED 2026-11-20 (#543 section 2A, dev-sft-r1 run capture): a real run needs `--run-dir`, an existing directory
holding no earlier trainer output (the run_capture wrapper creates it and may already have written its pre-run
evidence there). The dataset is read once: the recorded hash is of the bytes that were parsed, and training aborts if
the file changed before it starts. The adapter and tokenizer go to RUN_DIR/adapter/ and the trainer's
own output to RUN_DIR/trainer/, so a second run can no longer overwrite the first one's evidence; the fixed
data/adapter/ path is not written. Before training it writes RUN_DIR/resolved-training-args.json (the resolved
TrainingArguments plus the pins and counts it checked); after training RUN_DIR/loss.jsonl (the trainer's log
history, also written if training fails). Every file is created exclusively and never replaced; paths inside RUN_DIR
are recorded as RUN_DIR/..., and the model load dtype is recorded. `--max-steps N` caps the run (default: unset, so
num_train_epochs applies). Hyperparameters, mask, template, seed and dtype are unchanged.
"""
import argparse
import hashlib
import json
from pathlib import Path

DATA_PATH = Path(__file__).parent / "data" / "sft_dataset.jsonl"
BASE_MODEL = "unsloth/Llama-3.2-3B-Instruct"  # NOT proven equal to the llama3.2:3b Ollama weights; see pins below
BASE_REVISION = "006f5dcd1393c3add266de40994ba96225e9689d"
TOKENIZER_SHA256 = "6b9e4e7fb171f92fd137b777cc2714bf87d11576700a1dcd7a399e7bbe39537b"  # tokenizer.json at BASE_REVISION
DEFAULT_SEED = 20261114
MAX_LENGTH = 2048


def load_dataset_dicts(path: Path = DATA_PATH) -> list[dict]:
    return load_dataset_snapshot(path)[0]


def load_dataset_snapshot(path: Path = DATA_PATH) -> tuple[list[dict], str]:
    """Reads the dataset once and returns (examples, sha256 of exactly those bytes), so the recorded hash is the hash of
    the bytes that were parsed and trained on, not of a later re-read."""
    if not path.exists():
        raise SystemExit(
            f"{path} does not exist -- run export_grades_to_sft.py first."
        )
    raw = path.read_bytes()
    examples = []
    for number, line in enumerate(raw.decode("utf-8").split("\n"), start=1):
        if not line.strip():
            continue
        try:
            examples.append(json.loads(line))
        except ValueError as exc:
            raise SystemExit(f"{path} line {number} is not valid JSON ({exc}); no examples were trained")
    if not examples:
        raise SystemExit(f"{path} is empty -- nothing to train on.")
    print(f"Loaded {len(examples)} training example(s) from {path}")
    if len(examples) < 20:
        print(
            "WARNING: fewer than 20 examples. This run is validating the "
            "pipeline mechanics, not producing a meaningfully improved model "
            "-- see README.md's Known limitations section."
        )
    return examples, hashlib.sha256(raw).hexdigest()


def check_dataset_unchanged(path: Path, expected_sha256: str) -> None:
    """Aborts before training if the dataset file no longer has the bytes that were loaded."""
    current = hashlib.sha256(path.read_bytes()).hexdigest() if path.exists() else None
    if current != expected_sha256:
        raise SystemExit(f"{path} changed after it was loaded (sha256 {expected_sha256} -> {current}); nothing was trained")


def load_pinned_tokenizer():
    """The `tokenizers` Tokenizer from the pinned revision, refused if its file hash differs from TOKENIZER_SHA256."""
    from huggingface_hub import hf_hub_download
    from tokenizers import Tokenizer

    path = Path(hf_hub_download(BASE_MODEL, "tokenizer.json", revision=BASE_REVISION))
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if digest != TOKENIZER_SHA256:
        raise SystemExit(f"tokenizer.json hash {digest} does not match the pin {TOKENIZER_SHA256}; refusing to train.")
    return Tokenizer.from_file(str(path))


def tokenize_examples(examples: list[dict], tokenizer, max_length: int = MAX_LENGTH) -> list[dict]:
    import sft_tokenize as st

    encode = lambda text: tokenizer.encode(text, add_special_tokens=False).ids  # noqa: E731
    bos_id = tokenizer.token_to_id(st.BOS)
    if bos_id is None:
        raise SystemExit("pinned tokenizer has no <|begin_of_text|> token")
    out = []
    for index, example in enumerate(examples):
        row = example.get("id", index) if isinstance(example, dict) else index
        try:
            if not isinstance(example, dict) or "messages" not in example:
                raise ValueError("row has no 'messages' field")
            st.check_segmentation(example["messages"], encode, bos_id)
            out.append(st.build_example(example["messages"], encode, bos_id, max_length))
        except ValueError as exc:
            raise SystemExit(f"row {row!r} (line index {index}) refused: {exc}; no examples were trained")
    return out


RESOLVED_ARGS_FILE = "resolved-training-args.json"
LOSS_FILE = "loss.jsonl"


def run_relative(value, run_dir: Path):
    """Records a path inside RUN_DIR as RUN_DIR/<relative> so the evidence carries no host path."""
    text = str(value)
    base = str(run_dir)
    if text == base:
        return "RUN_DIR"
    if text.startswith(base.rstrip("/\\") + "/") or text.startswith(base.rstrip("/\\") + "\\"):
        return "RUN_DIR/" + Path(text).relative_to(run_dir).as_posix()
    return value


def load_training_modules():
    """The heavy imports, in one place so tests can supply stand-ins without torch."""
    import torch
    from peft import LoraConfig, get_peft_model
    from transformers import AutoModelForCausalLM, Trainer, TrainingArguments, set_seed
    return dict(torch=torch, LoraConfig=LoraConfig, get_peft_model=get_peft_model,
                AutoModelForCausalLM=AutoModelForCausalLM, Trainer=Trainer,
                TrainingArguments=TrainingArguments, set_seed=set_seed)


def parse_args(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--check-only", action="store_true", help="tokenize and mask the data, then stop; no torch, no model")
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    parser.add_argument("--data", type=Path, default=DATA_PATH)
    parser.add_argument("--run-dir", type=Path, default=None,
                        help="existing run directory with no earlier trainer output (made by run_capture.py); required unless --check-only")
    parser.add_argument("--max-steps", type=int, default=None, help="cap the number of optimizer steps (default: unset)")
    args = parser.parse_args(argv)
    if args.max_steps is not None and args.max_steps < 1:
        parser.error("--max-steps must be at least 1")
    if not args.check_only and args.run_dir is None:
        parser.error("a real run needs --run-dir (an existing run directory); use --check-only for a dry check")
    return args


TRAINER_OUTPUTS = ("adapter", "trainer", "resolved-training-args.json", "loss.jsonl")


def prepare_run_dir(run_dir: Path) -> tuple[Path, Path]:
    """Checks RUN_DIR is a real directory with none of this trainer's outputs in it, then creates adapter/ and trainer/
    and returns them. Files the run_capture wrapper wrote first (repo.json, image-facts.json, host facts and so on) are
    left alone. Only RUN_DIR itself and the names below are checked; the wrapper owns the exclusive creation of RUN_DIR."""
    if run_dir.is_symlink() or not run_dir.is_dir():
        raise SystemExit(f"--run-dir {run_dir} must be an existing directory, not a symlink; nothing was trained")
    present = [name for name in TRAINER_OUTPUTS if (run_dir / name).exists() or (run_dir / name).is_symlink()]
    if present:
        raise SystemExit(f"--run-dir {run_dir} already holds trainer output {present}; refusing to overwrite earlier evidence")
    adapter_dir, trainer_dir = run_dir / "adapter", run_dir / "trainer"
    adapter_dir.mkdir()
    trainer_dir.mkdir()
    return adapter_dir, trainer_dir


def training_args_kwargs(trainer_dir: Path, seed: int, use_gpu: bool, max_steps=None) -> dict:
    """The TrainingArguments values this script sets. Everything else is the transformers default, recorded at run
    time in the resolved-arguments file."""
    kwargs = dict(
        output_dir=str(trainer_dir),
        per_device_train_batch_size=1,
        gradient_accumulation_steps=4,
        num_train_epochs=3,  # small on purpose -- tiny dataset, avoid overfitting further
        learning_rate=2e-4,
        logging_steps=1,
        save_strategy="no",  # we save the adapter explicitly below
        use_cpu=not use_gpu,  # required explicitly -- transformers doesn't auto-detect "no GPU present"
        bf16=False,  # this box's CPU doesn't support bf16 training (found on a real run)
        fp16=use_gpu,
        report_to="none",
        seed=seed,
        data_seed=seed,
        remove_unused_columns=False,
    )
    if max_steps is not None:
        kwargs["max_steps"] = max_steps
    return kwargs


def write_new(path: Path, text: str) -> None:
    """Creates PATH; refuses if it already exists, so evidence is never replaced."""
    with open(path, "x", encoding="utf-8", newline="\n") as handle:
        handle.write(text)


def write_resolved_args(run_dir: Path, resolved: dict, meta: dict) -> Path:
    path = run_dir / RESOLVED_ARGS_FILE
    write_new(path, json.dumps({"training_arguments": resolved, **meta}, sort_keys=True, indent=2, default=str) + "\n")
    return path


def write_loss(run_dir: Path, log_history: list) -> Path:
    path = run_dir / LOSS_FILE
    write_new(path, "".join(json.dumps(entry, sort_keys=True, default=str) + "\n" for entry in log_history))
    return path


def main(argv=None):
    args = parse_args(argv)

    examples, dataset_sha256 = load_dataset_snapshot(args.data)
    tokenizer = load_pinned_tokenizer()
    tokenized = tokenize_examples(examples, tokenizer)
    trained = sum(1 for e in tokenized for label in e["labels"] if label != -100)
    total = sum(len(e["labels"]) for e in tokenized)
    print(f"Tokenized {len(tokenized)} example(s): {total} tokens, {trained} trainable (assistant-only), seed {args.seed}")
    if args.check_only:
        return
    adapter_dir, trainer_dir = prepare_run_dir(args.run_dir)

    modules = load_training_modules()
    torch = modules["torch"]
    LoraConfig, get_peft_model = modules["LoraConfig"], modules["get_peft_model"]
    AutoModelForCausalLM, Trainer = modules["AutoModelForCausalLM"], modules["Trainer"]
    TrainingArguments, set_seed = modules["TrainingArguments"], modules["set_seed"]

    import sft_tokenize as st

    set_seed(args.seed)
    use_gpu = torch.cuda.is_available()
    print(f"CUDA available: {use_gpu} (this run will {'use' if use_gpu else 'NOT use'} a GPU)")
    # CORRECTED 2026-09-14 against a real run: this box's CPU doesn't
    # actually support bf16 training (transformers' own validation
    # rejects it -- "Your setup doesn't support bf16/gpu"). Plain fp32
    # for CPU avoids that entirely, and avoids a dtype mismatch between
    # frozen base weights and the LoRA adapter's fp32 params. Costs more
    # memory (~12GB vs ~6GB for the frozen 3B weights) but there's ~21GB
    # available on this machine (see the Firefox-memory-pressure fix
    # earlier this session), so fp32 is the safer first-run choice.
    dtype = torch.float32 if not use_gpu else torch.float16

    model = AutoModelForCausalLM.from_pretrained(BASE_MODEL, revision=BASE_REVISION, torch_dtype=dtype)
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

    pad_id = tokenizer.token_to_id("<|finetune_right_pad_id|>")

    def collate(batch):
        padded = st.collate(batch, pad_id)
        return {key: torch.tensor(value) for key, value in padded.items()}

    trainer = Trainer(
        model=model,
        train_dataset=tokenized,
        data_collator=collate,
        args=TrainingArguments(**training_args_kwargs(trainer_dir, args.seed, use_gpu, args.max_steps)),
    )

    resolved = {key: run_relative(value, args.run_dir) for key, value in trainer.args.to_dict().items()}
    write_resolved_args(args.run_dir, resolved, {
        "seed": args.seed,
        "max_steps": args.max_steps,
        "base_model": BASE_MODEL,
        "base_revision": BASE_REVISION,
        "tokenizer_sha256": TOKENIZER_SHA256,
        "model_load_dtype": str(dtype),
        "dataset_name": args.data.name,
        "dataset_sha256": dataset_sha256,
        "examples": len(tokenized),
        "tokens": total,
        "trainable_tokens": trained,
        "use_gpu": use_gpu,
    })

    check_dataset_unchanged(args.data, dataset_sha256)
    try:
        trainer.train()
    finally:
        # A mid-training failure still keeps the partial loss curve as evidence.
        write_loss(args.run_dir, trainer.state.log_history)
    model.save_pretrained(str(adapter_dir))
    tokenizer.save(str(adapter_dir / "tokenizer.json"))
    print(f"Adapter saved to {adapter_dir}")


if __name__ == "__main__":
    main()
