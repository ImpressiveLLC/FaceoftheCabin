# POC1 — Local SFT fine-tuning pipeline

**Status: code proposed, not yet executed or verified end-to-end.** This
directory was written and pushed from a Windows/cloud Claude Code session
that cannot run Docker/GPU/CPU training workloads itself — every script
here needs a real run, on real hardware, before anyone treats it as
working. See `docs/ai-assistant/wsjf-backlog.md`'s `[POC1]` entry for the
scored proposal and its pending Ratification field.

Closes the loop from the existing human/AI-actor grading pipeline
(`docs/ai-assistant/rag/scripts/grade_pipeline.py`) into an actual SFT
fine-tuning round on `llama3.2:3b`, served as a **new, separate** Ollama
tag. Never touches the production tag `/api/helpdesk/ask` actually serves
from.

## What this does NOT do

- Does not touch the production Ollama model tag.
- Does not fine-tune on anything except already human-graded PASS content
  from `~/eval-results/`.
- Does not run automatically — every stage is an explicit, manual invocation,
  run by a person on a machine they chose.
- Does not require a GPU (CPU-first; auto-uses one if `torch.cuda.is_available()`).
- Does not open a PR or push anything on its own — this code does not
  self-execute against production, and this README does not authorize
  anyone or anything to run it. That decision belongs to whoever is
  physically present at the machine deciding to invoke it.

## Pipeline

```
grade_pipeline.py output (existing)
        |
        v
export_grades_to_sft.py   ->  data/sft_dataset.jsonl
        |
        v
train_sft.py (QLoRA/Unsloth)  ->  data/adapter/
        |
        v
export_to_ollama.sh (merge -> GGUF -> ollama create)
        |
        v
`cabin-assistant-poc1` Ollama tag (new, separate from production)
```

## Running it — three ways, same container

### 1. Any machine you can Docker into over Tailscale

```bash
export DOCKER_HOST=ssh://nate@<target-host>.tailb20f8b.ts.net
# Both must be already-expanded absolute paths -- compose does not
# expand ~, and there's deliberately no hardcoded default for either.
export EVAL_RESULTS_DIR="$HOME/eval-results"
export LLAMA_CPP_DIR="$HOME/llama.cpp"  # clone from ggml-org/llama.cpp first
cd docs/ai-assistant/training
docker compose build
docker compose run --rm trainer python export_grades_to_sft.py \
  --agent claude-code --round 20260908-r3
docker compose run --rm trainer python train_sft.py
./export_to_ollama.sh
```

Intended to work unchanged whether `<target-host>` is the M920q itself,
`ilikethelights`, or `bluefin` — the container isn't meant to know or care
which box it's on. **Not yet verified on any of them.**

### 2. Directly on a box with Docker installed

Same commands, just unset `DOCKER_HOST` first.

### 3. Free-tier cloud GPU (Colab / Kaggle)

Open a fresh Colab or Kaggle notebook (both offer a free GPU quota).
Paste in order:

```python
!pip install unsloth trl peft transformers datasets
# then paste the contents of export_grades_to_sft.py and train_sft.py
# as notebook cells, or clone this repo path and run them as scripts
```

Download the resulting `data/adapter/` directory and run
`export_to_ollama.sh` locally against it.

## Data provenance

Every line in `sft_dataset.jsonl` carries `source: "human-graded"`,
`agent_id`, `round`, and `graded_at` — never train on anything without
these fields. This is a hard requirement (see the evidence-ledger rule in
project memory): a training example must be traceable back to its grading
record, never silently anonymized into an unattributed fact.

## Known limitations, stated honestly

- As of this writing only 3 graded rounds exist
  (`claude-code/20260908-r2`, `-r2-postfix`, `-r3`), with a handful of
  PASS-graded questions across them. **The first real run of this pipeline
  will train on a single-digit number of examples.** That's expected — the
  pipeline is the deliverable; meaningful quality improvement comes from
  accumulating more graded rounds over time.
- `Dockerfile`'s exact pinned dependency versions have not been verified
  against a real `pip install` — in particular, `bitsandbytes`' CPU-only
  support has historically been uneven. If it fails to install or import
  cleanly, fall back to plain `peft` LoRA without 4-bit quantization and
  document that substitution here rather than silently working around it.
- `export_to_ollama.sh`'s GGUF conversion step assumes a `llama.cpp`
  checkout matching whatever build Ollama has bundled — check
  `ollama --version` / its embedded llama.cpp commit on the target
  machine rather than assuming a version.
- None of the three scripts in this directory have been executed even
  once. Treat every claim above about what they do as a design intent
  until someone runs them and reports the real result.
