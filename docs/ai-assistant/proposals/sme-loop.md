# Ask as the platform's subject-matter expert: scope, phases, goals and the improvement loop

Status: proposed 2026-10-17 (Code, at Nate's direction). Allocates no W-, D- or UC-IDs; Cowork maps accepted phases to existing work. Proposal only: it changes no Ask behavior and authorizes no training.

## Scope

**In scope.** Answer why and how to build, amend, operate and maintain FaceoftheCabin and the cabin orchestration, for two kinds of question:
- *Canonical*: the answer is written down (docs, decisions, runbooks, code). Ask cites it.
- *Conceptual*: the answer follows from the ontology and decisions (for example, why Ask cannot open a valve, or what a new bridge needs). Ask reasons from cited premises and says which parts are inference.

It must also investigate and troubleshoot using read-only evidence, and record the path it took.

**Out of scope.** Executing actions; stating live state without an authorized, fresh observation; changing production; training on unreviewed data; ratifying decisions. These stay behind the existing gates (D27, C5, the Doc 27 / POC2 handover).

**Evidence tiers.** Every claim names its tier: user assertion, Git design, code, or live-verified. A lower tier never silently stands in for a higher one (the ordered-is-not-online rule).

## The pathway record

Every round and every investigation leaves one entry: question or fixture set, evidence consulted (path and commit), prompt/model/gold fingerprints, result by class, failure class, the single change made, and the next result. For intent/slot rounds this is `training/intent-slot/round_ledger.py` (tested): `record` writes `rounds/<round>.json` from an evaluator report, `compare` prints per-intent deltas and exits non-zero on any drift warning. The round-1 entry is committed.

## Phases and measurable goals

| Phase | Goal (measured on the same 42 fixtures unless noted) | Exit test |
|---|---|---|
| 0 Instrument | Ledger exists and round 1 is recorded | `round_ledger.py compare` runs; 44 package tests pass |
| 1 Format validity (round 2) | valid_output_rate 76.2% to at least 95%; zero `concept` on non-`express_need`; `express_need` stays 11/11 | compare shows no regression warning |
| 2 Routing (round 3) | intent accuracy at least 90%; clarify at least 5/6; propose_automation at least 4/5; critical failures 0 (round 1: 19) | all four offline gates pass |
| 3 Review and expand gold | 42 of 42 labels human-reviewed; held-out families kept isolated; no gold text in any prompt | UI shows 42 approved; leak check clean |
| 4 SFT, only if needed | Start only after two prompt rounds plateau below the gates; train on human-approved, non-gold data; same held-out set | beats the best prompt-only round with no new critical failure |
| 5 Q&A as SME | Canonical and conceptual question sets graded 0 to 3 (grounded, cited, honest about uncertainty); citation correctness at least 90%; zero invented live state | existing `eval_pipeline` + `grade_pipeline` rounds, results in GitHub |
| 6 Operate | One review per week; ledger trend published; every accepted change traces to a W-item | weekly summary linked from the backlog |

## Feedback: classify every failure, then apply the matching first response

| Class | First response |
|---|---|
| Format (schema-invalid) | Add or tighten a format rule in the prompt; read the evaluator's `error` text first |
| Routing (valid but wrong intent) | Add a contrast example (invented, never from gold) or reorder the decision checks |
| Safety (critical) | Fix in the resolver or policy layer, not only in the prompt; the prompt cannot be the only guard |
| Label (gold looks wrong) | Correct it in the grading UI with a note; never edit gold silently; re-baseline the previous prompt on the new gold |
| Leak (prompt contains gold text) | Remove it and rerun; scores from a leaked round are marked |

One change per round, so a result can be attributed.

## Self-healing: out-of-sync signals and the instinctive recovery

Each row happened during the 2026-10-16/17 work.

| Signal | Recovery |
|---|---|
| Report rejected as "gold hash does not match" | Compare raw bytes first. Cause was CRLF vs LF; `.gitattributes` now pins LF (#172). Fingerprints are bytes, not content |
| Host script refuses: tag exists | Use a new `cabin-intent-slot-*` tag; never replace evidence |
| Host docs behind `main` | Docs-only merges do not deploy. Check `git -C ~/FaceoftheCabin-deploy log -1` before running anything that reads it, then `pull --ff-only` |
| Script fails after a contract change | A new file dependency (`request.schema.json`) missed the copy list. Run the script's own shell test whenever `contract.py` changes |
| Prompt already contains a gold phrase | Leak check (gold text in `system.txt`) before every round. Found: gold-23 ("I am cold") in round 1; flagged, not hidden |
| My counts disagree with the evaluator's | The evaluator is the authority (schema-invalid is wrong even when the label matches); the ledger now reads its `details` |
| A round changes nothing | `compare` warns PROMPT UNCHANGED; check the host used the new `system.txt` |
| A previously passing fixture fails | `compare` warns REGRESSION; revert the last change before iterating |
| Metric moves less than 5 points for two rounds | Stop prompting. Reclassify the failures: wrong label, fixture too hard for the model, or a larger-model comparison (a measured step, per the handover) |
| Pressure to train, ship or execute early | Re-read the gates. Return to the current phase; no SFT with 0 approvals, no execution from Ask |

## Open questions for Nate and Cowork

1. Acceptable evidence for phase 3: review all 42 gold, and how many of the 1,140 candidates before any SFT?
2. Who grades phase 5's conceptual set (it needs a definition of "correct" where no document states the answer)?
3. Is the 5-point plateau rule the right stop?
