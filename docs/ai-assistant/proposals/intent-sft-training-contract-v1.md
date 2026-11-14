# Intent SFT: training contract v1, the `dev-sft-r1` plan and the audit amendment

Drafted by Code on 2026-11-14 for review through [issue #244](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244). Docs only. It records the training contract confirmed by Codex ([6873868052](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6873868052) v1, confirmed at [6873921147](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6873921147)), the pins and gaps Code supplied at [6874272149](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6874272149), and the owner's audit amendment ([6874199353](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6874199353)).

**This document authorizes nothing.** It is not an execution key (the two-key rule of 6873152071 still applies to the exact plan), not a training go, not a purchase, and not a statement that any section 9 prerequisite has passed. Real training is `NOT_RUN`. The Ask A1 evaluation is `NOT_MEASURED`. Proxy reviews are attributed as proxy, not as human review. No sealed rows, gold answers, grader pack or key are read or reproduced here.

Source rules: [`sft-data-path.md`](sft-data-path.md) section 9 (prerequisites) and section 11 (an agent is not counted as a person, amended below for one step only).

## 1. Training contract v1

| Id | Requirement |
|---|---|
| R1 | Target: intent and slot routing, clarification, and the question-versus-command boundary. Separate from W-117 C2/C1. |
| R2 | Pin base revision, tokenizer and chat template. A comment that a checkpoint "mirrors Ollama" is not an equivalence proof. |
| R3 | The trainer uses a capability-tested dtype (the current script is fp32 on CPU, fp16 on GPU, `bf16=False`), a fully pinned framework lockfile, and an assistant-only loss-mask verification (section 9 of `sft-data-path.md`) before any real step. |
| R4 | The section 9 prerequisites stand and are not satisfied by preparation authority: S6 done, test and safety frozen, audits at 90% or better, assistant-only masking, candidate quantized like the baseline, training off the M920q. Each is DONE with evidence or covered by a precisely scoped amendment, never DONE on proxy authority alone. The S1 contrast-pair "keep both" exception is bounded to that one pair with its leakage evidence and exempts no other overlap. |
| R5 | The trainer receives approved TRAIN data only: no sealed rows, grader pack, gold answers or key. |
| R6 | The dev baseline is the CURRENT gold revision, not v3. Repeated identical-input prompt rounds count as one plateau observation. |
| R7 | Two separate evaluations that are not interchangeable: (i) intent SFT acceptance on frozen200 plus safety80, with its own schema, denominators, graders, key and rules, still to be designed (Cowork drafts, Codex reviews); (ii) Ask factual-QA120 paired-arm A1, `NOT_MEASURED`. The 55-test Ask analyzer is mechanism evidence only. |
| R8 | Environment evidence is required; the real environment is `NOT_RUN`. |
| R9 | Phase 1 of job `dev-sft-r1` outputs an adapter and a development report only. Phase 2 is a separate explicit plan with its own keys. |
| R10 | Rollback is a full record, not just deleting the adapter (section 4). |

Process boundary, to be written into the job manifest: the training process reads only approved TRAIN inputs. A separate development evaluator may read the approved, already-spent DEV fixtures. Neither receives the intent frozen test, the safety set or any Ask acceptance material.

## 2. Pins (verified from metadata, section 1 of 6874272149)

| Item | Pin |
|---|---|
| Main at drafting | `8827590e0935d216c1800c601f11ac200f879c33` |
| Base model | `unsloth/Llama-3.2-3B-Instruct` at commit `006f5dcd1393c3add266de40994ba96225e9689d` |
| Weights shard 1 | sha256 `13cbd6d16e927a0c5bad54102514e6e18b4a47b3a6eb911e39d678d328d19f55` |
| Weights shard 2 | sha256 `7b770216613ac5c34d7c54bdff1fa616bc4e338a9d0b20af6303e48c295ee23c` |
| Tokenizer | `tokenizer.json` sha256 `6b9e4e7fb171f92fd137b777cc2714bf87d11576700a1dcd7a399e7bbe39537b` |
| HF chat template | `chat_template.jinja` sha256 `5816fce10444e03c2e9ee1ef8a4a1ea61ae7e69e438613f3b17b69d0426223a4` (NOT used as is, see section 3) |
| Served baseline | Ollama `llama3.2:3b`, template blob `966de95ca8a62200913e3f8bfbf84c8494536f1b94b49166851e76644e966396` |
| Dev gold | `docs/ai-assistant/training/intent-slot/gold.jsonl`, 49 rows, sha256 `1d33f6226fb1a3bbff2d883c7cf325c00d32b039f71ba4659392dc63d05c8e62` |
| Scorer | `evaluate.py` sha256 `7d452b78c7a5e2d4422aace2d9b2acd4ffff0528cd139a75e5721255c8838464`; `contract.py` `a9dce6fcd83c08197ae66dea5ac19425682bad207474421e0d60180682953a12` |
| Schemas | `intent-slot.schema.json` `59f63b920a66d85fb20a08d66d31b2bbc3268dea871e691debfdb50842ca15dd`; `request.schema.json` `2f1511ddcd51b4e312c75a0df81d1fa103f5ab0bda243d7323c5e7fd5c2dc0a4` |
| Prompt | `system.txt` sha256 `15a94e07fcfe04f936ded4643ea73b8a0a2da96739c18464566873d66b8f1254` |
| Trainer script | `docs/ai-assistant/training/train_sft.py` sha256 `f19a48ba08dbad53aa15c42ee91824dbb01c0b94f9502d5a832bd59f67e2bb83` (to be changed, section 3) |
| Sealed sets (hashes only) | safety80 `90bcaace67084cb673ee36159c1d8b06493209878cefe8a8a6bb6ddbb9def310`; authored test170 `df98ae133a9b9c060c7f03a9f177251b4461d0aaa14e810f0f8ed73ceb6a33f2`; 200-row test not frozen |

Best prompt-only baseline on this gold: round r9-b, 49 fixtures, valid-output rate 1.000, intent accuracy 0.837, slot micro-F1 0.581, 13 critical fixture failures, not released. Rounds r8 and r9 share one prompt hash and count as one plateau observation. Dev is contaminated by prompt use and is a secondary number only.

## 3. Gaps that block a real Phase 1 (found by Code, with evidence)

1. **Template mismatch.** The HF template emits `Cutting Knowledge Date: December 2023` and `Today Date: <date>` (from `strftime_now`, so it changes by day). The Ollama template emits only the first line. Training must use a pinned custom template, with its own hash, that matches the served template for system, user and assistant turns, plus a token-for-token check of one rendered example. Status: NEEDS-AMENDMENT.
2. **No assistant-only loss mask.** `train_sft.py` trains on a single `text` field built with `apply_chat_template`, with no assistant-only loss and no generation markers, so loss covers system and user tokens. A code change and a test (label -100 on every non-assistant token) are required and belong in a separate code PR. Status: NOT DONE.
3. **No lockfile.** The Dockerfile pins only `torch==2.7.0` and the `python:3.11-slim` tag. Status: NOT DONE.
4. **Base revision floats** in `train_sft.py` (no `revision=`) and no seed is set. Both must be pinned in the plan.
5. **S6 not done.** No freeze command or ledger entry exists; the 200-row test is not assembled; the S1 sample shows `reviewed: 0`.
6. **Contrast-pair evidence** for the S1 "keep both" exception is not found in the repository. Status: owed (Code delivery by 12:00 CST).

## 4. Exact plan: job `dev-sft-r1`, PHASE 1 (proposed, `NOT_RUN`)

This plan is proposed for review by Codex and Code. It becomes executable only when its pins and gaps are closed and both two-key comments are bound to it.

| Field | Value |
|---|---|
| Phase | 1 only: adapter plus development report. No merge, quantization, GGUF export, Ollama import or route change. |
| Model | base pins in section 2; LoRA r 16, alpha 16, dropout 0, the seven projection modules (as in `train_sft.py`). |
| Method | Supervised fine-tune, assistant-only loss mask (section 3, gap 2), custom template pinned (gap 1). |
| Hyperparameters | As in the current script unless amended in review: 3 epochs, learning rate 2e-4, batch 1 with accumulation 4, max length 2048. Seed to be set and recorded. |
| Dtype | Capability-tested on the chosen host; fp32 on CPU, fp16 on GPU in the current script. Not yet tested on any candidate host. |
| Training data | Approved TRAIN manifest only. **No such manifest exists yet** (S4 and S5 pending, S1 sample `reviewed: 0`). Until one is approved and hashed, the only permitted data is invented validation data for tooling checks. |
| Dev evaluation | The 49-row gold at the hash above, scored with the pinned scorer, by a separate evaluator. Reported against the r9-b baseline as a development result, never as A1 acceptance or release eligibility. |
| Runtime | Not selected. Candidate: Nate's Windows PC, 4 cores, 32 GB, no CUDA GPU, CPU only; needs Nate's yes. Not the M920q. No purchases or new accounts. |
| Outputs | Adapter, immutable logs, manifest, dev report. All labeled development. |
| Resource limits | To be set from a measured dry run on invented data; wall time is not estimated here. |
| Evidence required before launch | Lockfile; template equivalence check; mask test; dtype test on the host; train manifest hash; leakage check that no test, safety or Ask material is in the input. |
| Keys | Two keys per the 00:30 amendment: a Code comment and a Codex comment that it makes sense, each bound to the exact plan. Neither is supplied by this document. |
| Rollback | Stop the job and release resources; preserve immutable logs, manifests and checkpoints for diagnosis; isolate candidate outputs; confirm production unchanged by hash. Delete nothing without explicit scope. The primary condition is that nothing is deployed. |

Phase 2 (separate explicit plan and keys): merge, quantize matched to the baseline plus the untuned base through the same path, tokenizer and template check, question-command fixtures, a separate RAG and citation regression, a candidate Ollama tag, route flag off, no production change.

## 5. Owner amendment: Codex elects the auditors (6874199353)

Source: Nate's written amendment of 2026-11-14 01:58 CST, relayed by Cowork. It amends the section 9 prerequisite "Nate's audits at 90% or better" and the section 11 line that an agent is not counted as a person, **for the audit step only**. Terms (Cowork proposal; Codex may tighten at its 02:30 CST action):

1. Codex elects one or more auditors and records who, with their exposure and access, before the audit.
2. An auditor must not be the author or labeler of the rows it audits, and not an agent that trains or tunes on them. Codex, as author of training families, cannot audit its own families.
3. The 90% threshold and the sampling rules in section 9 are unchanged. Only the auditor identity changes.
4. Every result is labeled "agent auditor(s) elected by Codex under owner amendment, not human audit".
5. It does not touch the frozen test, the safety set, custody rules, S6, the freeze, masking or the two-key rule.
6. Nate keeps the right to audit any sample himself and to revoke this amendment.

Effect: the human-audit wait is removed from the R4 list. The remaining R4 items still need evidence or a scoped amendment.

## 6. What this document is not

- Not an execution go, a training authorization, a purchase, an account, a release of any sealed item, or either key.
- Not a claim that any prerequisite is satisfied. Omission is not permission: anything not stated here stays as it was.
