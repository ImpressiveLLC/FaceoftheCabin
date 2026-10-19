# Fine-tuning data path: candidate review with proxy reviewers, and a larger holdout

Status: **proposed 2026-10-19 (Code, at Nate's direction).** Allocates no W-, D- or UC-IDs; Cowork maps accepted parts to existing work. Proposal only: it **trains nothing**, exports nothing, changes no prompt, no gold row and no Ask behavior, and does not start phase 4 of [the SME-loop plan](sme-loop.md). It answers that plan's open question 1 ("how many of the 1,140 candidates before any SFT?") and the README's call for a 200-example holdout.

## 1. What this decides, and what it does not

**Decides (once accepted):** which data a fine-tune would use, who reviews it and how independence is kept, how large and how clean the frozen holdout is, and what has to be true before any training run is even scheduled.

**Does not decide:** whether to fine-tune at all. Phase 4 still starts only after two prompt rounds plateau below the gates. Round r7 (the `clarify` prompt rule) comes first; this path is prepared in parallel so that data is not the long pole if r7 and r8 plateau.

## 2. Where we stand (measured, `main` 43bc9de)

| Fact | Value | Source |
|---|---|---|
| Gold | 47 rows, all human-approved (gold v3) | `gold.jsonl`, #185 |
| Gold mix | express_need 15, clarify 9, set_device_state 5, propose_automation 5, answer_question 4, unsupported 4, set_device_attribute 3, get_device_state 2; 35 of 47 critical | counted |
| Latest round r6 | valid 100%, intent 83.0%, slot F1 0.571, critical failures 15 | `rounds/2026-10-19-r6.json` |
| r6 intent accuracy by class | express_need 15/15, answer_question 4/4, propose_automation 5/5, unsupported 4/4, get_device_state 2/2, set_device_state 4/5, set_device_attribute 2/3, **clarify 3/9** | same file |
| Authored training templates | 26 families, 54 utterances (clarify 1 family, 2 utterances) | `synthesis-templates.json` |
| External candidates | 1,140 unapproved (Enfuse 1,042, OHF-Voice 86, Snips 12), 988 quarantined | `grading/README.md`, 2026-10-16 |
| Where the candidate files are | **Not in git, and not on this workstation.** They are a private output of `collect_sources.py`; they must be regenerated from pinned revisions | search 2026-10-19 |

## 3. The finding that shapes the plan: the candidates do not cover the failing classes

What each class needs, against what the external sources can supply (`import_sources.py` maps only these intents):

| Class | r6 result | External candidates can supply | Needs |
|---|---|---|---|
| set_device_state, set_device_attribute, get_device_state | 8 of 10 | Yes: Enfuse and OHF-Voice are commands and state queries | A sample, for wording and slot-filling variety |
| clarify | **3 of 9** | **No** | Authored data: pronouns, no room, no unit, negation, wrong card or site, schedules without a zone |
| unsupported | 4 of 4 | No | Authored |
| express_need | 15 of 15 | No | Authored (done for the 11 needs; more paraphrase) |
| propose_automation | 5 of 5 | No | Authored |
| answer_question | 4 of 4 | No | Authored, from real Ask phrasing |

Six of the eight remaining intent errors are `clarify` fixtures where the model acts instead of asking. Reviewing all 1,140 candidates would spend most of the review effort on classes that already work. **So the plan reviews a stratified sample of the candidates, not all of them, and puts the authored `clarify` boundary data first.** Slot F1 (0.571) is the one metric the command candidates may help, and that is what the sample is for.

## 4. The four data sets, and the rules that keep them honest

| Set | Purpose | Size (target) | Who may look at it |
|---|---|---|---|
| **Train** | what a fine-tune learns from | about 800 to 1,000 reviewed rows | everyone |
| **Dev** | gold v3, the 47 fixtures | 47 (may grow) | prompt rounds use it; **it is contaminated by that use** and is reported as a secondary number only |
| **Frozen test** | the number that decides a model | **200 reviewed, new** | nobody during prompt or training work; opened once per candidate model, each opening recorded in the ledger |
| **Safety set** | critical behaviors only | **80 reviewed, new** | same as the frozen test |

Rules:
1. **Families, not rows, are held out.** A paraphrase family lives in exactly one set. `prepare_sft.py` already excludes gold text and gold groups from training; it is extended to exclude every test and safety family (section 8).
2. **The frozen test is never used to tune a prompt or a schema.** Dev is for that. Today's prompt rounds have all looked at gold v3, which is why it cannot be the test.
3. **Authors differ by set.** Cowork authors the frozen test and safety set; Codex and Code author training families. Nobody reviews what they generated (section 6).
4. **One source is held out whole.** The OHF-Voice tests (86 candidates, all on/off commands, a different source from training) are reserved for the test set's `set_device_state` rows, so the test also measures generalization to an unseen wording style.
5. **Leakage checks before every freeze:** exact and near-duplicate text across sets (normalized text plus a token-overlap check), prompt text against test text, and family IDs.
6. **No site, room, device name or entity ID from the live system is trained** (the existing rule): fictional rooms and devices only, mapped by the resolver at runtime.

### Frozen test composition (200)

| Class | n | Note |
|---|---|---|
| set_device_state | 30 | 74 command-class rows in all (this and the next two lines). The held-out OHF-Voice source supplies on/off commands only, so it covers this line; the other two are authored |
| set_device_attribute | 24 | includes units and out-of-range values |
| get_device_state | 20 | |
| express_need | 30 | all 11 needs, at least 2 each |
| clarify | 30 | at least 5 of each trigger type: pronoun, no room, no unit, negation, wrong card or site, schedule without a zone |
| propose_automation | 28 | all four trigger kinds, at least 5 each |
| answer_question | 24 | real Ask phrasing (the RAG question sets) |
| unsupported | 14 | |

### Safety set composition (80)

Clarify triggers 30; unsupported (secrets, bypass, disabling alarms, unlocking, opening a valve) 25; prompt injection in the text or in a device name 15; ambiguous needs and conflicting card or site 10. Every row is critical.

### What these sizes can and cannot show

At 90% pooled accuracy on 200 rows the 95% interval is about plus or minus 4 points. Per class (about 25 rows) it is about plus or minus 12 points, so **per-class numbers are directional, and the gates stay pooled.** Zero critical failures on the 80-row safety set bounds the true rate below about 3.7% (95%); on 200 rows, below 1.5%. That is evidence, not proof, and it never replaces the resolver and policy layer.

## 5. Training data targets (rows reviewed, not rows generated)

| Class | Reviewed rows | Where from |
|---|---|---|
| clarify | at least 150 | authored: 8 trigger types, at least 15 each, **with minimal pairs** (the same sentence with and without the missing detail) |
| unsupported | 60 | authored |
| express_need | 100 | authored, at least 8 per need |
| propose_automation | 80 | authored, 4 trigger kinds, 20 each |
| answer_question | 60 | authored from real Ask phrasing |
| set_device_state, set_device_attribute, get_device_state | about 300 | **stratified sample of the candidates** (by source, intent, device type and room wording) plus about 100 authored |

About 850 rows at minimum (the sum of the lines above). These counts are judgement, not a measured requirement: nobody has shown how few rows a 3B LoRA needs for this contract. A small, clean set with a measured test beats a large unreviewed one, and the first training run, if it ever happens, doubles as the experiment that tests this assumption.

## 6. Reviewing with proxy reviewers

Nate's ruling (2026-10-16): a human or a human proxy (Cowork, Codex) satisfies "human review" for training data, with Nate spot-checking the safety and clarify subsets.

**Roles and independence**
- **Generator is never the reviewer** of the same row or family.
- **Reviewers are named honestly.** A proxy is recorded as an agent with its role and model, never as a person.
- **Shared bias is assumed** (the same caution as R6 in the taxonomy plan): agreement between agents is weaker evidence than agreement between a person and an agent, so Nate's audit is the check and a local open model may serve as a third, non-ruling labeler.

**Three tracks**

| Track | Rows | Method | Nate sees |
|---|---|---|---|
| A: plain commands from the candidates | about 300 sampled | One proxy reviewer relabels **blind** (text only, not the source label). Agreement with the source label approves the row ("approved by agreement"). A disagreement goes to a second proxy; if they still differ, to Nate. | all unresolved disagreements, plus a 10% audit |
| B: authored training rows (hard classes, plus the 100 authored commands) | about 550 | **Two** proxy reviewers label blind, independently. Agreement approves. Any disagreement goes to Nate. | all disagreements, plus a 20% audit sample |
| C: frozen test and safety set | 280 | Authored by Cowork. Two proxy reviewers (not Cowork) label blind. | **100% of the safety set** and a 25% sample of the test |

**Nate's time, as an estimate to be measured:** safety set 80, test sample 50, Track B audit about 110, Track A audit about 30, expected disagreements about 80: roughly 350 items. At about 25 seconds an item in the grading screen that is about two and a half hours. Measure the first 20 items and re-plan; if it is more than three hours, shrink Track A first.

**Provenance.** `prepare_sft.py` requires `reviewer`, `reviewed_at`, `source`, `revision`, `sha256` and `license` on every approved row. A proxy row additionally records `reviewer_kind` (human or agent), `reviewer_model`, `second_reviewer`, `agreement` (agreed, ruled by Nate, audited) and `track`. These fields are added to the grading export (section 8); a row without them is refused.

## 7. Pipeline, owners and exit criteria

| Step | What | Owner | Exit |
|---|---|---|---|
| S0 | **Regenerate the candidates** from the pinned revisions (a download from three public sources; needs Nate's explicit yes). Record counts, hashes and licenses. **Exclude Snips (12 rows) until its dataset license is verified.** | Code | `manifest.json` committed (hashes only, never the files) |
| S1 | Coverage audit: map the candidates to classes, de-duplicate, draw the stratified sample | Code | sample list with strata counts; the rest stays unreviewed |
| S2 | Author the training families (clarify first, with minimal pairs) | Codex and Code | families committed in `synthesis-templates.json` form |
| S3 | Author the frozen test and safety set | Cowork | sealed: committed as hashes and counts only until freeze |
| S4 | Blind proxy review, Tracks A, B and C | Cowork and Codex as reviewers | agreement report per track; disagreement queue |
| S5 | Nate's rulings and audits | Nate | disagreement queue empty; audit agreement at least 90% on each sample |
| S6 | **Freeze**: leakage checks pass, test and safety hashes recorded in the ledger | Code | freeze entry in `round_ledger` |
| S7 | `prepare_sft.py` dry run to produce splits and a data card. **No training.** | Code | splits and data card, nothing exported |

S2 can start now. S0 waits for Nate's yes on the download. S3 should not be written by anyone who has seen the dev failures in detail beyond the classes named here.

## 8. Tooling needed (small, tested, not built in this proposal)

1. A **review packet** tool: batches of rows without the source label (Track A) or without any label (B, C), and an importer that takes a reviewer's labels, computes agreement and builds the disagreement queue.
2. `prepare_sft.py`: refuse rows without the proxy provenance fields; exclude test and safety families as it excludes gold families today.
3. The grading screen's export: write the new provenance fields.
4. A **freeze** command in the ledger: records the test and safety set hashes and counts every later opening.

## 9. Before any training run (all must hold)

Data: S6 done, test and safety frozen, Nate's audits at 90% or better. Engineering, from the earlier review of the POC1 trainer: a private copy of the export script with a new `cabin-intent-slot-*` candidate tag; the **candidate quantized the same way as the baseline** (and ideally the untuned base through the same export path, so tuning is not confused with quantization); training **off the M920q** or inside a measured memory budget that cannot starve Frigate, Home Assistant or the backend; assistant-only loss masking checked; the existing RAG answer and citation regression run separately. Decision: r7 and r8 have plateaued under the gates (the 5-point rule), or Nate chooses the larger-model comparison first.

## 10. Open decisions for Nate and Cowork

1. **The download (S0).** Regenerate the candidates from the pinned public sources?
2. **"Approved by agreement" for Track A.** Is a blind agent label that matches the source label enough, with a 10% audit?
3. **Nate's time budget.** About two and a half hours in total, measured after the first 20 items.
4. **Cowork authors the frozen test and safety set.** Agreed?
5. **Hold the OHF-Voice source out whole** for the test's `set_device_state` rows?
6. **Exclude Snips** until its dataset license is verified?
7. Should the answer to "how many candidates before SFT" be **about 300 sampled, not 1,140**, given section 3?

## 11. Not doing

Training, exporting, quantizing, deploying or routing to any model; editing gold v3; using the frozen test for any tuning; training on live inventory, entity IDs or secrets; counting an agent as a person.

## Traceability

UC-17; D27; W-75 and the POC2 handoff; [SME-loop plan](sme-loop.md) phases 3 and 4 and its open question 1; the README's holdout guidance; Nate's human-proxy ruling of 2026-10-16; the review of the POC1 trainer and the SFT plan (Code, 2026-10-16, in chat).
