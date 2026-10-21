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

1. Acceptable evidence for phase 3: review all 42 gold, and how many of the 1,140 candidates before any SFT? (All 47 gold are now approved. Proposed answer, 2026-10-19: about 300 sampled candidates, not 1,140, plus authored families and a new 200-row frozen test; see [sft-data-path.md](sft-data-path.md).)
2. Who grades phase 5's conceptual set (it needs a definition of "correct" where no document states the answer)?
3. Is the 5-point plateau rule the right stop?

## Round log

| Round | Gold | Valid output | Intent | Slot F1 | Critical | Notes |
|---|---|---|---|---|---|---|
| r1 (prompt as of #171) | 41 reviewed | 75.6% | 41.5% | 0.510 | 18 | 10 invalid outputs; 8 had `concept` on a non-`express_need` intent |
| r2 (ordered checks, format rules, aligned to reviewed labels) | same 41 | 73.2% | 56.1% | 0.442 | 16 | `compare` flags METRIC DROP on valid output and slot F1 |
| r2 | all 47 on main (6 unreviewed `express_need`/`clarify` rows from #174) | 74.5% | 57.4% | 0.466 | 17 | host run 2026-10-17, tag `cabin-intent-slot-poc2-r2`; p50 9.0 s while the host was swapping |
| r2 | all 47, now **fully reviewed (gold v3)** | 74.5% | 57.4% | 0.466 | 17 | identical to the row above: Nate's four edits on 2026-10-18 changed only clarification wording, which the evaluator does not score. The ledger still warns "GOLD CHANGED"; that warning is correct by design, and here the comparison was checked by hand |
| r3 (`concept` removed from the general slots sentence) | gold v3, 47 | 74.5% | 61.7% | 0.492 | 17 | host run 2026-10-18, tag `cabin-intent-slot-poc2-r3`, p50 6.7 s. Valid output did **not** move: 12 invalid, 10 still `concept` on a non-`express_need` intent, 2 temperature-without-unit |
| r4 (per-intent schema, #189; Codex pre-merge run) | gold v3, 47 | 91.5% | 78.7% | 0.377 | 17 | `concept` leaks 10 to 0; slot F1 fell because eight newly valid outputs carry junk slots (`none`, `unknown`, slots on `clarify`/`answer_question`) |
| r5 (closed `unit` list + UTC-only RRULE `UNTIL`, schema only, #194) | gold v3, 47 | **100%** | **83.0%** | 0.368 | 17 | host run 2026-10-19, tag `cabin-intent-slot-poc2-r5`; **phase-1 goal (valid output at least 95%) met**; prompt and gold unchanged |
| r6 (no slots for `answer_question`/`clarify`/`propose_automation`, schema only, #197) | gold v3, 47 | 100% | 83.0% | **0.571** | **15** | host run 2026-10-19, tag `cabin-intent-slot-poc2-r6`; false-positive slots 94 to 32, intent unchanged; prompt and gold unchanged |
| r7-baseline (current `system.txt` incl. `humid`, **new gold v4, 49 rows**) | gold v4, 49 | 100% | 83.7% | 0.569 | 15 | host run 2026-10-20, tag `cabin-intent-slot-poc2-r7-baseline`, p50 5.8 s; no prompt change from main, so this is the comparable baseline for the new gold. `clarify` still 3/9; `humid` rows pass (express_need 17/17) |
| r7 (prompt-only `clarify` decision order + 4 new invented examples, Cowork handover HO-2026-10-19) | gold v4, 49 | **93.9%** | **79.6%** | 0.569 | **16** | host run 2026-10-20, tag `cabin-intent-slot-poc2-r7`, p50 5.5 s. **Did not help**: `clarify` 3/9 to 3/9 (gold-38 fixed, gold-16 lost to `unsupported`), `set_device_attribute` 1/3 to 0/3, `propose_automation` 5/5 to 4/5, three outputs invalid (gold-07, 15, 31). Ledger: `compare` reports REGRESSION gold-08, 16, 31, METRIC DROP on valid output and intent, NEW CRITICAL gold-16 and gold-31. The r7 prompt text (hash `3d76a3b1`) is archived in `rounds/2026-10-20-r7-prompt.txt` and is **not** in `system.txt` or its Java copy |
| r8-a, r8-b (**identical** inputs, run back to back; variance pair) | gold v5 (49, `1d33f622`, after gold-13/16 set to `requires_confirmation: false`) | 100% / 100% | 83.7% / 83.7% | 0.569 / 0.581 | 16 / 16 | host run 2026-10-21, tags `cabin-intent-slot-poc2-r8-a` and `-r8-b`, main `82680e0`. Same prompt (`15a94e07`), schema (`7c884202`), scorer (`evaluate.py` `fa7111da`), contract (`7e0c87a0`), model digest `9d69b2ad6823` (llama3.2:3b `a80c4f17acd5`), ollama 0.33.2, temperature 0. **Variance: predictions differed on 2 of 49 rows (gold-02, gold-03); intent and critical identical, slot tp 35 vs 36 (F1 +0.012).** |
| r9-a, r9-b (schema only: `clarify` gets its own branch with `requires_confirmation` fixed to `false`; prompt unchanged) | gold v5, 49 | 100% / 100% | 83.7% / 83.7% | 0.569 / 0.581 | **13 / 13** | host run 2026-10-21, tags `cabin-intent-slot-poc2-r9-a` and `-r9-b`, branch `e827b0b`, schema `59f63b92` (was `7c884202`); every other pin as r8. **Critical 16 to 13**: gold-16, 35, 39 (correct clarify answers) stop being scored as critical failures. Routing, validity and slots are unchanged from r8 (no row regressed; the a/b pair again differs only on gold-02 and gold-03). `clarify` is still 3/9 |

**Reading r7.** The decision-order text fixed one of the six failing `clarify` rows (gold-38, negation) and broke three that passed. The model still acts instead of asking on the pronoun, missing-room and card-conflict rows (gold-13, 15, 37, 40), so the rule did not reach its target. Two things limit the reading: this is one run with no repeat (run-to-run variance is unmeasured), and the change added both a rule and four examples, so which of the two caused the regressions is not separated. Per the plateau rule this is not yet a reason to train; the proposed next round is either the examples alone or the rule alone, not more text. 

**Scorer note (checked, not changed).** `evaluate.py` compares `requires_confirmation` exactly for every critical row, while `contract.proposal` and the sealed sets use `false` on `clarify`; gold-13 and gold-16 still say `true`. A model that answers those two rows correctly with `false` is scored a critical failure. It does not explain r7 (gold-13 routed to the wrong intent in both rounds), but it inflates the critical count by up to two. Fixing it is a gold edit through the grading screen or a scorer change, both for Nate and Cowork. `evaluate.py` also does not compare `clarification` text at all (confirmed in the source), so a clarify answer with any non-empty question passes.

**Reading r8 (run-to-run variance, then a scorer finding).** With temperature 0 and every input pinned, two runs differ on a couple of rows (2 of 49) and move slot F1 by about 0.01 and slot true positives by 1; intent accuracy and the critical count did not move. So **differences smaller than about 0.015 in slot F1 or one row of intent accuracy are within the variance observed across one pair of runs; that is not an established noise floor** (Cowork, 2026-10-21; [C6](../wsjf-backlog.md) measures the observed range from repeated runs), and r7 was well outside it (intent -4.1 points, three lost rows). One pair is a small sample; a third run would tighten it. **Finding:** the model emits `requires_confirmation: true` on all three `clarify` answers it gets right (gold-16, 35, 39), although it correctly emits `false` for `answer_question`, `get_device_state` and `unsupported` (checked across all 49 outputs in both runs). Gold is now `false` on all nine `clarify` rows (Nate, #221), so every correct clarify routing is scored a critical failure, and the critical count (16) cannot reach 0 on `clarify` whatever the routing does. This is why r7-baseline read 15 and r8 reads 16 (gold-16 flipped when its gold changed), not a model change. The fix is on the model side, not in the gold: a prompt line, or better the per-intent schema fixing `requires_confirmation` to `false` on the `clarify` branch (schema-only, as r4 to r6 did). Proposed as r9; not run.

**Reading r2.** Routing improved: `set_device_state` 0/4 to 4/4, `clarify` 0/8 to 2/8, `unsupported` stayed 4/4. The phase-1 goal (valid output at least 95%) was **not** met and went slightly the wrong way. Eleven of r2's twelve invalid outputs are the same defect as r1: the model puts `concept` on an intent other than `express_need`, even though the prompt now says not to. `answer_question`, `propose_automation` and `set_device_attribute` are all still 0 for that reason alone. A prose rule did not stop a 3B model; the original line that lists `concept` among the slots allowed for every intent is the likely cause and is the next single change (r3). If r3 does not move valid output, stop prompting for this defect and fix it structurally (a per-intent schema in the constrained-output format, or a deterministic strip of `concept` outside `express_need` before validation), which is a contract change for the owner of the contract.

**Reading r3 (2026-10-18).** Intent accuracy rose 57.4% to 61.7% and slot F1 0.466 to 0.492 (`clarify` 2 to 3 of 9, `set_device_attribute` 0 to 1 of 3), but valid output stayed at 74.5% and 10 of 12 invalid outputs are still the `concept` leak. This is the second prompt-only attempt on one defect, so the loop's own rule applies: **stop prompting for it** and fix it structurally. Recommended, for the contract owner (Codex lane, a contract change): (1) first choice, a per-intent schema in the constrained-output format so `concept` can only exist under `express_need`; (2) fallback, a deterministic strip of `concept` outside `express_need` before validation, logged so the rate stays visible. Either would turn about ten of these twelve outputs valid; the remaining two (temperature with no unit) are a separate prompt/gold question. Nothing else changes until that is decided.

**Reading r4 and r5 (2026-10-19).** Two schema-only changes took valid output from 74.5% to 100% with the prompt and gold unchanged: constraining `concept` by intent (#189) and then constraining `unit` and the RRULE `UNTIL` (#194). A first, looser RRULE pattern was dodged by the model (`23H59M-06`), so the pattern now fixes the exact shape; the lesson is that a decoder constraint must pin the format, not just exclude the observed bad character. Intent accuracy is 83.0%. **Still failing:** 17 critical fixtures, and slot F1 0.368 (94 false-positive slots against 32 true positives): the model fills slots on intents whose gold slots are empty (`clarify`, `answer_question`, `propose_automation`) and writes placeholders (`none`, `unknown`). Next single change (r6, schema only): empty `slots` for those three intents and a pattern rejecting placeholder strings. Then the `clarify` cases (gold-13, 15, 37, 38, 40 propose a command where the gold asks a question) are a prompt round, kept separate.

**Reading r6 (2026-10-19).** Schema constraints have done what they can do: valid output 100%, slot F1 0.368 to 0.571, critical failures 17 to 15, with the prompt and gold unchanged. Intent accuracy did not move (83.0%): the eight remaining wrong intents are judgement, not format. Seven are critical, and in six of them the gold says `clarify` and the model acts instead: gold-13 "Turn it down" (pronoun), gold-15 "Activate the light" (no room), gold-37 "Turn on the heater at home" (location differs from the selected card), gold-38 "Do not turn on the heater" (negation), gold-40 "Set the selected heater to 72" (no unit) and gold-47 "Wake me at 7 tomorrow" (schedule needs a clarification). The seventh, gold-14 "Set the thermostat to 19", is `set_device_attribute` and was sent to `set_device_state`; gold-48 (a command plus a feeling) is non-critical. **Next (r7, prompt only, one change):** a single decision rule for `clarify` placed ahead of rules 6 and 7: if the request has a pronoun or no named device, no room, no unit on a temperature, a negation, or a location different from the selected card, the answer is `clarify`, never a command. Measure on this gold; this is the first round where a prompt change is justified, because the format defects are closed.

**Gold v3 review (2026-10-18).** All 47 rows are approved; none is pending. Four answers were edited (gold-09, 14, 37, 40), all wording of the clarification, none an intent or slot, so no score moved. Two things Nate wrote in the notes are not gold and are recorded here so they are not lost:
- **gold-09 ("How can I recover a disconnected sensor?")**: Nate wants a *two-step* reply (ask which sensor, then answer once it is named). The contract has no `next_step` field, so the saved row stays `answer_question` with the clarifying question in `clarification`. Whether a clarify-then-answer chain belongs in the contract is a question for the contract owner; nothing was added.
- **gold-14 ("Set the thermostat to 19")**: the answer should acknowledge that the whole HVAC system, including the air-exchange fans between zones and the dehumidifier, affects how warm or cold it feels. That is Ask knowledge (an environmental-nodes guide topic), not an intent label. Not yet written.
- The review state is per grading instance. A new instance starts empty, so a fresh port makes the reviewer approve everything again; the next round should reuse the existing state file (or import the previous one) rather than start a new one.
