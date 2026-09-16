# POC1 — Local SFT fine-tuning pipeline

**Status, 2026-09-14/15: Tier 1 (build) executed end-to-end on real M920q
hardware, real results.** Real training run (5 PASS-graded examples,
3 epochs, CPU-only, loss 4.34 → 2.98), real GGUF conversion (6.4GB), real
`ollama create` of a new, separate `cabin-assistant-poc1:latest` tag,
confirmed distinct from and non-disruptive to the production
`llama3.2:3b` tag throughout (`ollama list` shows both). A manual smoke
test against the new tag produced a coherent, on-topic answer to a real
training question — the model genuinely loads and infers, not just an
empty manifest entry. Six real bugs found and fixed along the way are
documented inline in the scripts below (Unsloth's hard GPU requirement,
bf16 unsupported on this CPU, root-owned bind-mount permissions, a
docker-compose env-var validation gap, and others).

**Tier 2 (does it actually answer better) is documented below but not
yet run.** See "Tier 2 — evaluating the trained tag" below for the exact
recipe and commands. See `docs/ai-assistant/wsjf-backlog.md`'s `[POC1]`
entry for the scored proposal and its pending Ratification field, which
stays pending until Tier 2's real comparison lands.

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

## Tier 2 — evaluating the trained tag

Tier 1 (above) proves the pipeline *runs*. Tier 2 answers the actual
question: does `cabin-assistant-poc1` answer better than production? This
reuses the exact same eval + grading tools already used for every regular
grading round (`docs/ai-assistant/rag/scripts/eval_pipeline.py` +
`grade_pipeline.py`, see `docs/ai-assistant/rag/EVAL-ENVIRONMENTS.md`) —
no new tooling, just pointed at a different backend.

**Why a separate backend instance, not the running `cabin-backend`
container:** `/api/helpdesk/ask`'s model tag
(`OllamaHttpClient`'s `cabin.ollama.model` property, env override
`CABIN_OLLAMA_MODEL`) is baked in at container start, defaults to
`llama3.2:3b`, and is **not currently overridden** on the live M920q
`cabin-backend` container — confirmed live, 2026-09-15. Changing it would
mean restarting the container production actually serves from, which
this whole POC has deliberately never done. Instead, run a second,
disposable instance of the *same* image on a different port, sharing the
same `cabin_default` Docker network (so it can reach `ollama`/
`cabin-postgres`/`cabin-kafka` by hostname) with only `CABIN_OLLAMA_MODEL`
overridden. `/api/helpdesk/ask` is confirmed read-only end to end
(`TinyHelpdeskService` only reads `KnowledgeNodeRepository` and calls
Ollama — no `save`/`publish` calls anywhere in it), so a second instance
sharing the same Postgres for reads carries no data-corruption risk.

**On the M920q, as `nate` (not from a Claude Code session — this
container-management step was refused by this session's own
production-safety classifier, correctly, the same way an earlier session
on this box refused an unscoped cross-session automation prompt — run it
yourself):**

```bash
# 1. Stand up an isolated, disposable backend instance pointed at the
#    new tag. Never touches the real cabin-backend container (different
#    name, different host port 8091 vs 8090).
NETWORK=$(docker inspect cabin-backend --format '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}')
docker inspect cabin-backend --format '{{range .Config.Env}}{{println .}}{{end}}' > /tmp/poc1-eval.env
echo "CABIN_OLLAMA_MODEL=cabin-assistant-poc1" >> /tmp/poc1-eval.env
docker run -d --name cabin-backend-eval-poc1 --network "${NETWORK}" \
  -p 8091:8090 --env-file /tmp/poc1-eval.env cabin-backend:latest
rm -f /tmp/poc1-eval.env   # never leave the cloned env file (real secrets) on disk

# 2. Confirm it's healthy and really serving the new tag, not production
sleep 15 && curl -s http://127.0.0.1:8091/actuator/health

# 3. Run the full 24-question manifest against it (fresh agent id, so
#    --questions all -- an "owned" filter would silently mean "common
#    only" since this agent owns no domains)
python3 ~/FaceoftheCabin/docs/ai-assistant/rag/scripts/eval_pipeline.py \
  --agent-id cabin-assistant-poc1 \
  --manifest ~/FaceoftheCabin/docs/ai-assistant/rag/questions_manifest_r1.json \
  --token-file ~/.ha_token \
  --output-dir ~/eval-results \
  --endpoint http://127.0.0.1:8091/api/helpdesk/ask \
  --questions all \
  --round r1
```

Step 3 auto-launches `grade_pipeline.py` interactively (p/f per question,
by design — this pipeline refuses non-interactive/automated grading on
purpose, so a human judgment call is always in the loop). Grade honestly
against the frozen baseline (2/24: Q04, Q08) and the safety set (Q03,
Q10, Q18, Q24, Q25) exactly as any other round would be.

```bash
# 4. Compare against the baseline round already on record
python3 ~/FaceoftheCabin/docs/ai-assistant/rag/scripts/grade_pipeline.py summary \
  --manifest ~/FaceoftheCabin/docs/ai-assistant/rag/questions_manifest_r1.json \
  --output-dir ~/eval-results \
  --round r1

# 5. Tear down the isolated instance -- it was only ever for this comparison
docker rm -f cabin-backend-eval-poc1
```

**Expectation, stated honestly in advance:** with only 5 training
examples, don't expect a meaningfully higher pass count than the 2/24
baseline — a flat or even slightly different-but-not-better result is a
real, complete, valid Tier 2 outcome, not a failed run. The value of
running this now is proving the *comparison mechanism* end to end
(isolated instance → eval → grade → summary), the same way Tier 1 proved
the *training* mechanism end to end. Update the `[POC1]` backlog entry's
Ratification field with whatever the real numbers say, not with an
expected/hoped-for result.

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
- Tier 1 (export → train → merge → GGUF → `ollama create`) has been run
  once, real, on the M920q (see status header). Tier 2 (does the trained
  tag actually answer better) has not — treat any claim about answer
  *quality* as unverified until a real Tier 2 comparison lands.
