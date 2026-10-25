# Handover HO-2026-10-18: Code to Codex, per-intent output format for the intent/slot model

Written against `origin/main` after #187, 2026-10-18. Authorised by Nate in chat on 2026-10-18 ("hand it to Codex, per-intent format first"). This is a contract change, which is Codex's lane; Code has changed nothing in the contract.

## Why

Two prompt-only attempts (r2 #184, r3 #186/#187) did not move valid output off 74.5% on gold v3 (47 approved rows). Of r3's 12 invalid outputs, 10 are `concept` on an intent other than `express_need` (validator message "Concept belongs to express_need") and 2 are a temperature with no unit ("Temperature requires explicit C or F; otherwise clarify"). `sme-loop.md` says to stop prompting after a second failed attempt and fix the defect structurally. Evidence: `docs/ai-assistant/training/intent-slot/rounds/2026-10-18-r3.json` and, on the M920q, `~/eval-results/intent-slot/2026-10-18-r3/baseline.json`.

## The ask (first choice)

Make `concept` impossible outside `express_need` in the schema sent to Ollama as `format`, so the constrained decoder cannot emit it.

- Today `intent-slot.schema.json` has one `slots` object listing `concept` for every intent. Replace it with a per-intent shape (for example `oneOf`/`anyOf` branches keyed on `intent`, where only the `express_need` branch allows `slots.concept`, and the other branches set `additionalProperties: false` without it).
- Check first that the Ollama version on the M920q accepts that shape in `format`. If it ignores or rejects `oneOf`, write down what it does and use the equivalent it supports; do not assume.
- `automation` non-null only for `propose_automation` can be encoded the same way. Optional, but it is the second rule the prompt keeps stating in prose.
- Keep `contract.py` as the semantic validator (it enforces more than the schema). The schema change must not loosen anything `contract.py` or the backend rejects.

## Places that must change together

| Place | Why |
|---|---|
| `docs/ai-assistant/training/intent-slot/intent-slot.schema.json` | the source |
| `backend/.../assistant/IntentSlotContract.java` and `JsonSchemaLite.java` | the backend validates with a *subset* JSON Schema reader; it must support whatever keywords the new schema uses, or the model's valid output will be rejected in production |
| `IntentSlotContractTest`, `HumanNeedVocabularyParityTest`, `test_vocabulary.py` | parity tests that read the schema; extend them for the new shape |
| `backend/src/main/resources/assistant/system.txt` and `docs/.../system.txt` | keep the two copies in step; with the schema enforcing it, the prose rule about `concept` can be shortened (do that as a *separate* later round, so a change is attributable) |
| `scripts/host/intent-slot-baseline` FILES list and `test_intent_slot_baseline.sh` | only if a new file is added (the hash count is 9 today) |

## How to measure (the loop's rule: one change per round)

1. Change the schema only; leave `system.txt` byte-identical.
2. Merge it (docs and backend). A docs-only merge does not refresh `~/FaceoftheCabin-deploy`; the host needs `git pull --ff-only origin main` there.
3. Run `scripts/host/intent-slot-baseline` with a new tag (`cabin-intent-slot-poc2-r4`) and a new output directory, then `round_ledger.py record ... --round 2026-10-NN-r4 --gold gold.jsonl --prompt system.txt` and `compare` against `rounds/2026-10-18-r3.json`. The gold is unchanged, so the comparison is valid; the ledger will report the schema as the one change only if it records the schema hash (add that to the ledger if it does not).
4. Success: valid output at least 95% (the phase-1 goal). Expected, not promised: about 96% if only the `concept` leak is removed. Report failure honestly if it is lower.
5. Do not deploy the schema change to the Ask backend until the measured round is at least as good as r3 on intent accuracy and critical failures.

## Fallback (only if the schema route fails)

A deterministic strip of `concept` (and a logged counter) on any intent other than `express_need`, applied before validation in the backend and in `evaluate.py`. It hides the defect from the score, so record the pre-strip rate separately.

## Not part of this handover

- The 2 missing-unit failures: a separate prompt/gold question (the unit-required rule and gold rows that expect a clarify). Code will take it after this lands.
- The gold-09 two-step reply (clarify then answer): needs a `next_step` field and a decision from the contract owner; recorded in `sme-loop.md`.
- D27 A1 (admin unlock from Ask) stays unratified; no gold or prompt change for it.

## State at handover

Open PRs: none. Local grading screen for the finished gold: reuse `reviews-8770.json`; no human grading is needed for r4.
