# Intent / slot training r1 — Ask and Tiny Helpdesk

Status: reconstructed implementation starter and proposal, 2026-10-14.
The prior chat's ZIP was unavailable in this Windows session. This is a new,
reviewable reconstruction of the described package, not a byte-identical recovery.
Source conversation: `6a78fe15-e978-83ea-8986-0c3a3ddcd045` (Cabin Home Assistant Login).
Repository baseline: `409e1804311b79eafcfa15f24aeac91b588b353c`.

## Architecture and boundaries

1. **RAG for facts:** retain permission-filtered retrieval, source citations,
   freshness and uncertainty for Ask / Tiny Helpdesk. Current
   `TinyHelpdeskService` uses word-overlap retrieval of knowledge nodes; this
   proposal does not claim a vector service is already deployed.
2. **Intent/slot SFT for language:** learn semantic request structure, not
   credentials, hardware inventory, MQTT topics or stale HA IDs. “It is stuffy”
   needs clarification, not an invented 74°F threshold or switch mapping.
3. **Live EntityResolver:** planned Java component joins permission-filtered,
   non-secret inventory with site, room, aliases and capabilities. Return exactly
   one supported match or request clarification. Unknown, ambiguous, stale,
   offline or unauthorized entities fail closed. Resolution is not implemented here.
4. **Policy and execution:** proposal → resolver → policy validation → explicit
   confirmation of the resolved action → existing executor. Recheck authorization,
   target availability and proposal version at execution; bind confirmation to
   the exact plan hash, principal and expiry. Model text cannot grant approval.
   No arbitrary HA service or MQTT publish path. FaceoftheCabin owns platform
   workflow interpretation and orchestration through its existing workflow engine
   (`WorkflowRuleService`); HA, MQTT and other adapters provide integration paths.
   Existing HA-owned automations remain coordinated with CabinAutomations.
   Assistant proposals must reuse these governed paths, not create a second engine.
5. **Behavior:** deterministic aggregation over authorized local telemetry can
   identify candidate patterns; the LLM explains a candidate and drafts a proposal.
   CASAS is deferred research, not a source of actual household habits.

Runtime routing, resolver, policy wiring, telemetry pattern detection and execution
tests are follow-on work in [IMPLEMENTATION-ISSUE.md](IMPLEMENTATION-ISSUE.md).
This package only runs local data preparation and evaluation.

## Files and contracts

- `intent-slot.schema.json`: strict semantic response shape. `contract.py` adds
  cross-field confirmation/clarification rules. `automation.schema.json` is the
  same nested automation shape for standalone review. No topic/service/ID fields.
- `import_sources.py`: Enfuse JSONL, concrete HA test YAML (on/off subset), Snips
  sample light-intent JSON. Unknown intents/slots and duplicate slots fail loudly.
- `sources.json`: upstream discovery, licensing notes, and deliberately unfilled
  revision fields; not a claim that datasets have been downloaded or approved.
- `synthesis-templates.json`, `generate_synthetic.py`: small deterministic
  cabin-style seed set. Every output starts unreviewed; names are synthetic.
- `prepare_sft.py`: human-review/provenance gate, duplicate/gold exclusion and
  stable 80/10/10 group-hash split, in the existing trainer's `messages` format.
- `system.txt`, `Modelfile.intent-r1`: shared policy prompt and optional baseline
  wrapper. The Modelfile does **not** contain trained weights.
- `gold.jsonl`, `evaluate.py`: 22 provisional gold fixtures, strict local Ollama
  schema output and offline scoring. Gold labels still need human review.
- `test_package.py`: contract, adapter, scoring, leakage and approval checks.

## Local preparation

From this directory, in an isolated Python environment:

```sh
python -m pip install -r requirements.txt
python -m unittest discover -s . -p 'test_*.py' -v
python generate_synthetic.py /tmp/intent-r1/candidates.jsonl
python import_sources.py enfuse /path/to/source.jsonl /tmp/intent-r1/enfuse.jsonl --revision UPSTREAM_COMMIT --license Apache-2.0
python import_sources.py ha /path/to/concrete-test.yaml /tmp/intent-r1/ha.jsonl --revision UPSTREAM_COMMIT --license CC-BY-4.0
python import_sources.py snips /path/to/lights_dataset.json /tmp/intent-r1/snips.jsonl --revision UPSTREAM_COMMIT --license REVIEWED_DATASET_LICENSE
```

Use Windows paths instead of `/tmp` on Windows. Never use placeholder revision
or license strings in a real run. Dependencies are unpinned development tooling;
capture `pip freeze`, interpreter version, input hashes and Git SHA with each run.
No dataset download happens automatically. Inspect the exact upstream revision,
retain required notices/attribution and review the license of each selected file.

Supported source formats were checked against the [HA intents repository](https://github.com/OHF-Voice/intents),
[Enfuse dataset card](https://huggingface.co/datasets/enfuse/joint-intent-slot-smarthome)
and [Snips light example](https://github.com/snipsco/snips-nlu/blob/master/sample_datasets/lights_dataset.json).
HA templates need upstream expansion; this adapter consumes concrete test cases.
Snips library licensing does not establish terms for every external dataset.

Review each candidate for label correctness, ambiguity, units, privacy and safety.
Add `reviewer`, `reviewed_at` and `review_status: approved` under `provenance` only
after human review. Imported labels alone are not approval. Keep source revision,
input SHA-256, license and record locator intact. Unitless temperature requests
must be relabeled `clarify`; corpus labels must not override this policy.

Assign `group` to the original template/paraphrase family **before splitting**.
Imports conservatively group a whole source file until a reviewer assigns real
families; the exporter fails if that leaves a split empty. Review cross-source
near-duplicates as well: the tool catches exact normalized text and shared group
leakage, not arbitrary semantic paraphrases. Keep the held-out families separate.

```sh
python prepare_sft.py /path/to/reviewed.jsonl /tmp/intent-r1/splits
```

The small seeds are not a production dataset. Expand reviewed normal, ambiguous,
negated, multi-turn, unavailable, wrong-site, security and adversarial examples.
Freeze a representative holdout (suggested minimum 200 independently reviewed
examples plus a dedicated safety set) before tuning; keep class/site breakdowns.

## M920q execution handoff

The [existing POC1 pipeline](../README.md) documents one CPU-only 3B build/export
run; it does not establish intent quality or current available memory. Start with
that 3B family and benchmark before considering larger models. Ollama serves the
exported model; `train_sft.py` performs LoRA training with Transformers/PEFT.

1. On a chosen training host, record current RAM/disk, running service load,
   base model revision/license, Python dependencies and dataset hashes. Schedule
   CPU/RAM budget so training cannot starve the cabin's production services.
2. Use an isolated copy of the existing training directory and data volume.
   Its trainer hardcodes `data/sft_dataset.jsonl` and `data/adapter/`; copy only
   the reviewed **train** split to that isolated data path. Retain provenance.
   Do not overwrite the existing POC1 data or mix held-out rows into training.
3. Use the existing trainer/merge/GGUF process, reviewing its paths and model
   tag before execution. The existing exporter targets POC1; adapt a private copy
   to a new candidate tag such as `cabin-intent-slot-r1`, never a production tag.
4. Benchmark prompt-only base, prompted baseline and trained candidate on the
   identical frozen holdout. Record model digest, adapter/export hashes, latency
   p50/p95, peak RSS, timeout count and raw outputs; no training runs occur in this PR.
5. Run the existing RAG answer/citation regression suite separately and obtain
   maintainer review before any runtime routing change. Rollback keeps the
   previous model and existing read-only Ask path available.

Optional prompt-only baseline (manually on the intended local Ollama host):

```sh
ollama create cabin-intent-slot-prompt-r1 -f Modelfile.intent-r1
python evaluate.py --model cabin-intent-slot-prompt-r1 --output /tmp/intent-r1/baseline.json
python evaluate.py --model cabin-intent-slot-r1 --output /tmp/intent-r1/candidate.json
```

The API request supplies the JSON schema using [Ollama's chat format field](https://docs.ollama.com/api/chat).
Only localhost port 11434 is accepted. Offline predictions use JSONL records
`{"id":"gold-01","output":{...}}` with `--predictions PATH` instead of `--model`.
Missing/malformed responses count as failures, not dropped samples. Output
includes raw predictions, per-case results, holdout hash and request timing.
Exit 0 means the offline fixture thresholds passed, **never** permission to deploy.

### Running the baseline and the shadow period on the M920q (HO-2026-10-16 steps 4 and 5)

The ollama container publishes no host port, so `evaluate.py` cannot reach it from the host. The runner
`scripts/host/intent-slot-baseline` does the README steps above for the prompt-only tag: it refuses a
non-`cabin-intent-slot-*` tag or an active training/eval, records the host first, copies this package into an
isolated directory, creates the candidate tag, runs `evaluate.py` in a throwaway Python container that shares the
ollama container's network, samples peak container memory, and writes `evidence.md` for the PR. It deletes nothing
and downloads nothing from the model registry. As the operator on the M920q (SSH needs Tailscale's check approved):

```sh
cd ~/FaceoftheCabin-deploy && git log -1 --oneline        # confirm the deploy worktree has the merge you mean
bash ~/FaceoftheCabin-deploy/scripts/host/intent-slot-baseline
```

Then paste `~/eval-results/intent-slot/<date>-r1/evidence.md` on the PR. It reports structured-output rate, intent
accuracy, slot micro F1, critical fixture failures, first-request / p50 / p95 / max latency, timeouts, peak memory,
the model's ollama id and the raw-output path. The 22 fixtures measure the tooling, not the model's quality, and the
integration gates stay `NOT_MEASURED`.

**Shadow period (step 5).** `POST /api/helpdesk/propose` stays off until `infra/.env` on the host sets
`CABIN_ASK_INTENT_ENABLED=true` and the backend is recreated (`docker compose -f docker-compose.yml -f
docker-compose.m920q.yml up -d cabin-backend`). It is administrator only and returns proposals; there is no executor, so
nothing runs. Every call writes one redacted JSON line on the `assistant.proposal.audit` logger. After a few days:

```sh
docker logs cabin-backend --since 72h 2>&1 | grep assistant.proposal.audit > /tmp/audit.log
python3 -B ~/FaceoftheCabin-deploy/docs/ai-assistant/training/intent-slot/run_reports.py shadow /tmp/audit.log
```

The report counts calls by intent, status, refusal code and resolver outcome, gives model and whole-request latency,
and lists every target that resolved (a person marks each right or wrong; the tool cannot) and every refused or
ambiguous one. The live heater step needs Nate's explicit go after that report.

## Evaluation gates

| Gate | Threshold | Evidence required |
|---|---|---|
| Structured output | ≥95% | Full schema plus semantic contract; denominator includes all requests |
| Intent accuracy | ≥90% | Exact canonical intent on frozen holdout |
| Slot micro F1 | ≥90% | Exact key/value pairs after case/outer-whitespace normalization; extras are false positives |
| Critical fixture failures | 0 | Wrong intent/slots/confirmation/automation on any critical fixture |
| Accepted invented entities | 0 | Integration tests against known inventory; unknown and cross-site identities rejected |
| Critical runtime safety failures | 0 | Safety-policy integration suite including stale/offline/ambiguous targets |
| Execution without required confirmation | 0 | Executor spy/audit evidence; forged, expired, replayed and changed-plan confirmations rejected |
| RAG regression | No lost approved safety/citation cases | Existing answer and evidence grading workflow |

`evaluate.py` measures the first four only. Its report always leaves integration
gates `NOT_MEASURED` and `release_eligible: false`. Schema rejection of an
`entity_id` field cannot prove resolver correctness; `requires_confirmation: true`
cannot prove executor enforcement. Small fixtures measure tooling, not deployment
readiness. Human review of fixtures, expanded holdout, runtime gates and hardware
results remain open. Ratification and release authority stay with existing governance.
