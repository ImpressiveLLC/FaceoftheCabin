# `dev-sft-r1` Phase 1: exact plan draft, every field marked

Code, Thu 19 Nov 2026. Status: **draft plan text, `NOT_RUN`, authorizes nothing.** Ordered on #244 (Cowork, 7029359145). Docs only: no training, export, quantization, routing, host work, purchase or account; no sealed or private data read. **This is not an execution key and does not bind or request either of the two keys** (training contract v1 section 4: a Code comment and a Codex comment, each bound to the exact plan). It becomes a candidate for those keys only when no field below is `PENDING-EVIDENCE` or `NEEDS-NATE`, and then only by a separate comment.

Replaces the plan in section 4 of [`intent-sft-training-contract-v1.md`](intent-sft-training-contract-v1.md) as the proposed text and corrects its stale pins (section 2 below). Read on main at `e3af4ba94a4c61e0fb2c18581e2b6ba873f3d330`.

**Field status vocabulary.** `PINNED`: a value fixed by content on main at the commit above, with its hash. `PENDING-EVIDENCE (what, who)`: a value that depends on a check not yet run; the evidence and its owner are named. `NEEDS-NATE (what)`: a decision only Nate can take. A field is `PINNED` only if I read the bytes; anything inferred or remembered is not.

## 1. What this plan is

Job `dev-sft-r1`, Phase 1 only (contract R9): a supervised LoRA fine-tune producing **an adapter and a development report**, nothing else. No merge, no quantization, no GGUF export, no Ollama import, no route change. The trainer receives approved TRAIN data only (R5); a separate development evaluator may read the approved, already-spent dev fixtures; neither receives the frozen test, the safety set or any Ask acceptance material.

## 2. Pins, corrected against main

Section 2 of the contract was written on 2026-11-14 and part of it is stale. Main now has:

| Item | Value | Status | Note |
|---|---|---|---|
| Repository commit | `e3af4ba94a4c61e0fb2c18581e2b6ba873f3d330` | PINNED | the plan is read at this commit; a new commit needs a re-read of every PINNED row |
| Base model | `unsloth/Llama-3.2-3B-Instruct` at `006f5dcd1393c3add266de40994ba96225e9689d` (`train_sft.py` `BASE_REVISION`) | PINNED | the contract's two shard hashes (`13cbd6d1...9f55`, `7b770216...e23c`) are from the 2026-11-14 metadata read; not re-verified by me today (PENDING-EVIDENCE: Code, a metadata read on the training host) |
| Tokenizer | `tokenizer.json` sha256 `6b9e4e7fb171f92fd137b777cc2714bf87d11576700a1dcd7a399e7bbe39537b` (`TOKENIZER_SHA256`, checked at load) | PINNED | |
| Chat template | rendered by `sft_tokenize.py` from the pinned Ollama template, blob sha256 `966de95ca8a62200913e3f8bfbf84c8494536f1b94b49166851e76644e966396` (`OLLAMA_TEMPLATE_SHA256`); `sft_tokenize.py` sha256 `5f312447b1b2668b865ef75e7e03319406273f18cfac59502ac455fef3ae875e` | PINNED for the file; **PENDING-EVIDENCE (Nate or Cowork: a read-only `ollama show --template` on the served host)** that the served template still has that blob hash | the contract's HF template (`chat_template.jinja` `5816fce1...`) is not used |
| Trainer | `docs/ai-assistant/training/train_sft.py` sha256 `42291422341309bd443db779d77265e0e009104d16356a1c280e1ee764898610` | PINNED | **the contract's `f19a48ba...` is stale** (the trainer changed on 2026-11-14 after the contract pin) |
| Tokenizer and mask tests | `test_sft_tokenize.py` sha256 `5078047b734cc754907a8b4204f293ecb60dcf30b7db6d17487fce8bf60705b1`; 96 passed, 5 skipped offline; runs in CI since #525 | PINNED; the 5 real-tokenizer tests are `PENDING-EVIDENCE (Code: run them with SFT_TOKENIZER_JSON set on the training host)` | |
| Dependency lock | `requirements.lock` sha256 `d0b537edd982f0fe49a58abbe9388d5d23ccc4c198653776af22b0d944e5c957` (942 `--hash=sha256` lines; torch 2.7.0+cpu, transformers 5.23.0, peft 0.21.3, accelerate 1.15.0, tokenizers 0.23.3, huggingface-hub 2.4.0, numpy 2.4.6); `requirements.in` `643e426a...9cea` | PINNED | `requirements.in` itself is unpinned except torch (the lock is the pin) |
| Container | `Dockerfile` sha256 `3393638e478f19323b4d9f6502a343be34812d66c93ae38918bbf360e0737aad` installs the lock with `--require-hashes` | PINNED for the file; **PENDING-EVIDENCE (Code or Codex: the base `python:3.11-slim` is a tag, not a digest, so its digest must be recorded at build)**; the image has never been built from this lock | |
| Dev gold | `gold.jsonl` 49 rows, sha256 `1d33f6226fb1a3bbff2d883c7cf325c00d32b039f71ba4659392dc63d05c8e62` | PINNED | unchanged since the contract |
| Dev scorer | `evaluate.py` sha256 `fa7111dab6ab5987d631cb5380d66cb300986d641e2b5adff979c718be660846`; `contract.py` `7e0c87a051d5698815aba0356713fe3eda340140c0d76b71aa36a9a3c7892a07`; `run_reports.py` `6cfc0e45eb6dc9fb106445ac73359003cf6098c433ad51ffbcbb950457887d37` | PINNED | **the contract's `evaluate.py` `7d452b78...` and `contract.py` `a9dce6fc...` are stale**; the values here are the ones the r11 to r14 rounds used |
| Prompt and schemas | `system.txt` `15a94e07fcfe04f936ded4643ea73b8a0a2da96739c18464566873d66b8f1254`; `intent-slot.schema.json` `59f63b920a66d85fb20a08d66d31b2bbc3268dea871e691debfdb50842ca15dd`; `request.schema.json` `2f1511ddcd51b4e312c75a0df81d1fa103f5ab0bda243d7323c5e7fd5c2dc0a4` | PINNED | unchanged |
| Data tooling | `prepare_sft.py` `cbaf4a23d6fc846385f8a4c437fc5c2ec15d8c7ace714ab00c7e973678e20282`; `grading/review_packets.py` `3557434a01e7d95d699e52d232f348b6e5ac2a9abb1795082ad80aeb07316922`; `generate_synthetic.py` `bbd35a44cf824e231c65032814ce2145103f7b67fae71a1dca547660cd368c6b`; `vocabulary.py` `d2c2e55e...bbb1`; `concepts.json` `845ba4af...97dc` | PINNED | these will change before a real run (S7 gap list, #529); the plan then needs a re-pin |
| Authored candidates | `synthesis-templates.json` `5e2cd18c87e2219487f4b729d2f709c1399eb200aa7e4c56db7f2517256472b0` (158 templates, 425 utterances, all UNREVIEWED) | PINNED (as candidates only) | not training data until reviewed and audited |
| Ledger | `round_ledger.py` `3d64ca12cc8ecaa9b5e37fc84f85fdd4fcff65939d1a1642fe242d145a60164c` (record and compare only; no freeze or open yet) | PINNED | S6 freeze and opening entries are design only |
| Sealed sets | safety80 `90bcaace67084cb673ee36159c1d8b06493209878cefe8a8a6bb6ddbb9def310`; authored test170 `df98ae133a9b9c060c7f03a9f177251b4461d0aaa14e810f0f8ed73ceb6a33f2` | hashes only, from the contract; **NEEDS-NATE (placement on the freeze machine)** | the sets are not read, copied or needed by Phase 1 |

## 3. Method and hyperparameters

| Field | Value | Status |
|---|---|---|
| Method | supervised fine-tune; every token outside an assistant answer has label -100 (`sft_tokenize.py`); template as in section 2 | PINNED |
| LoRA | r 16, alpha 16, dropout 0.0, target modules q_proj, k_proj, v_proj, o_proj, gate_proj, up_proj, down_proj, task CAUSAL_LM (`train_sft.py`) | PINNED |
| Schedule | 3 epochs, learning rate 2e-4, per-device batch 1, gradient accumulation 4, max length 2048 with refusal (not silent truncation) of an over-long row, `save_strategy` no | PINNED |
| Seed | `DEFAULT_SEED` 20261114 for `set_seed`, `seed` and `data_seed`; `--seed` overrides | PINNED (the run manifest records the value actually used) |
| Dtype | fp32 on CPU, fp16 on GPU, `bf16=False` (a 2026-09-14 real run on the M920q found the CPU rejects bf16) | PINNED as code; **PENDING-EVIDENCE (Code: a capability test of the dtype on the chosen host, contract R3)** |
| Optimizer, scheduler, warmup, weight decay | not set in the script: transformers 5.23.0 `TrainingArguments` defaults apply | **PENDING-EVIDENCE (Code: the resolved `TrainingArguments` dumped into the run manifest before the run; if a default is not acceptable the script needs a reviewed change)** |
| Hyperparameter change | none proposed here. The values come from the POC1 five-example run and were never tuned for this data; whether they suit a few hundred rows is unknown | **NEEDS-NATE or Cowork (accept as the first run, or ask for a dry-run-informed change)** |
| Validation split | `prepare_sft.py` writes a validation split but `train_sft.py` does no evaluation during training | PINNED as fact; **NEEDS-NATE or Cowork (is a held-out validation loss wanted in Phase 1? needs a reviewed trainer change)** |

## 4. Training data

| Field | Value | Status |
|---|---|---|
| Source | approved TRAIN rows only, produced by `prepare_sft.py` from rows promoted by `record-audit` (agent-agreed with a passed, linked audit by the elected auditor, labeled "not human audit") | PENDING-EVIDENCE (see below) |
| Manifest | the S7 post-audit `train-manifest.json` of the S7 gap list (#529): `manifest_id`, per-track cohort hashes, `train_ids_sha256`, `train_content_sha256`, `families_sha256`, `quarantine_ids_sha256`, `frozen_family_ids_sha256`, S6 `freeze_id` | **placeholders only: no manifest exists** |
| Rows | none approved today: S1 sample `reviewed: 0`; 425 authored utterances UNREVIEWED; S5 labeling blocked on Nate's authorization for the private sample | **PENDING-EVIDENCE (Codex: S5 first pass and audit; Nate: the authorization)** |
| Frozen test and safety exclusion | S6 freeze with C0 to C7 passing and `screen-train` over the train file | **PENDING-EVIDENCE (Code and Nate: S6 is design only, no freeze has run)**; `FROZEN_WITH_GAPS` does not satisfy this row |
| Tooling gaps before any real data | the eleven gaps in the S7 list (#529): exclusion of frozen families, internal split named `test`, near-duplicate gold overlap, Track B audit share, agent-auditor labeling, card in the audited identity, template pipeline, pair families vs splits, manifests, live-name check | **PENDING-EVIDENCE (Code: the code PRs, each reviewed by Codex and Cowork)** |
| Interim permitted data | invented validation data for tooling checks only, labeled as such, never a result | PINNED as a rule |

## 5. Dev evaluation

| Field | Value | Status |
|---|---|---|
| Fixtures and scorer | the 49-row gold and the scorer pins in section 2 | PINNED |
| Baseline | the contract's prompt-only round r9-b, and the same weights on the served tag `cabin-intent-slot-prompt-r1` (id `9d69b2ad6823`), re-measured in r14: valid-output 1.000, intent accuracy 0.837, slot micro F1 0.581, 13 critical failures, the same 8 failed fixtures (r14, `rounds/2026-11-17-r14-gold49.json`); not released, A1 `NOT_MEASURED` | PINNED (recorded rounds) |
| **How an unmerged adapter is scored** | **No path exists.** `evaluate.py` only contacts local Ollama (`http://127.0.0.1:11434/api/chat`, refuses any other endpoint) and sends the schema as `format` with temperature 0, so the output is schema-constrained. Phase 1 forbids merge, GGUF export and Ollama import, so it cannot produce a model that `evaluate.py` can score. A Hugging Face generation script would not use schema-constrained decoding, so its numbers would not be comparable with the baseline's | **NEEDS-NATE or Cowork (decide), then PENDING-EVIDENCE (Code and Codex: build and review)** |
| Options | (a) allow a Phase 1b step: merge and a throwaway Ollama tag on a host that can serve it, a stated exception to "no merge, no import" needing its own scoped decision; (b) write a separate HF-side scorer that applies the schema constraint (needs a constrained-decoding library, which is a new pinned dependency and a new review) and calibrate it on the base model against the r14 baseline before trusting it; (c) report only training loss and mask statistics in Phase 1 and move all scoring to Phase 2 | NEEDS-NATE or Cowork |
| Reporting | a development report against the baseline, never A1 acceptance, release eligibility or an intent SFT acceptance result (R7); the dev set is contaminated by prompt use | PINNED as a rule |

## 6. Runtime and resources

| Option | What it needs from Nate | Status |
|---|---|---|
| Nate's Windows PC, 4 cores, 32 GB, no CUDA GPU, CPU only | the yes; a measured memory dry run (fp32 3B weights are about 12 GB before activations and optimizer state); confirmation it is not needed for other work during the run | NEEDS-NATE |
| M920q | contract R4: training off the M920q, or inside a measured memory budget that cannot starve Frigate, Home Assistant or the backend. A real POC1 run happened there on 2026-09-14/15 with 5 examples | not proposed; NEEDS-NATE if wanted, with that measured budget |
| A GPU host or cloud | a host decision, a purchase or an account | not proposed; each is a separate NEEDS-NATE and not authorized by anything on file |

Wall time and resource limits: **PENDING-EVIDENCE (Code: a measured dry run on invented data on the chosen host; no estimate is made here).** Phase 1 sets the limits from that measurement before launch.

## 7. Outputs and manifest

All outputs are labeled development. The run writes the adapter directory (`data/adapter/`, with the tokenizer copy), immutable logs, and a run manifest recording: the plan id and the repository commit; every PINNED hash in section 2 as read on the host; the resolved `TrainingArguments`; the seed used; the container image digest and the base `python` image digest; `pip freeze` and its hash; the host capability test output (dtype, CPU model, memory); the data manifest id and hashes; the number of rows, tokens and trainable tokens; the loss curve; and the dev report. Nothing is merged, exported or routed. The adapter is not served.

## 8. Evidence required before launch (contract section 4)

| Evidence | State today |
|---|---|
| Lockfile | present on main (section 2); the image has not been built from it: `NOT_RUN` (Code or Codex) |
| Template equivalence check | `test_render_matches_ollama_shape_exactly` passes offline; the served blob's hash on the host is `NOT_RUN` (needs a read-only `ollama show --template`, Nate's go) |
| Mask test | present and in CI (96 passed); the 5 real-tokenizer tests `NOT_RUN` (Code, tokenizer file) |
| Dtype test on the host | `NOT_RUN` (Code, on the chosen host) |
| Train manifest hash | none exists; S4, S5 and S7 stages pending |
| Leakage check that no test, safety or Ask material is in the input | `NOT_RUN`; S6 is design only; sealed sets are not on any machine I have read |
| Dev evaluation path for an adapter | does not exist (section 5) |
| Section 9 data-path prerequisites | S6 done, test and safety frozen, audits at 90 percent or better, candidate quantized like the baseline (Phase 2): none is done |

## 9. Rollback

Stop the job and release its resources; preserve the immutable logs, manifests and the adapter directory for diagnosis; isolate the candidate outputs; confirm production unchanged by comparing the served tag's image id and the backend and UI image tags before and after (the baseline serving tag is `cabin-intent-slot-prompt-r1`, id `9d69b2ad6823`). Delete nothing without explicit scope. The primary condition is that nothing is deployed: Phase 1 produces no served artifact. **A note on checkpoints:** the contract's rollback says to preserve checkpoints, but `train_sft.py` uses `save_strategy="no"`, so there are none; if intermediate checkpoints are wanted for diagnosis, that is a reviewed trainer change (PENDING, Cowork or Nate to say).

## 10. What this does not do, and the open items in one list

It binds no key, requests no key, starts nothing and changes no file other than this note. A real run needs, at minimum: Nate's answers on runtime, on the hyperparameter acceptance and on the dev-evaluation option; S5 authorization and the audits; S6 freeze with C0 to C7 passing; the S7 code PRs; the dtype and container evidence on the chosen host; the template check on the served host; and the two keys bound to the final text. Training, export, quantization, routing, purchase, host configuration and sealed-file release all remain unauthorized.
