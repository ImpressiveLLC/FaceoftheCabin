# HO-2026-10-19: Cowork to Claude Code, fine-tuning data path (S1 to S7)

Baseline: `origin/main` 5519a08. Governs [`sft-data-path.md`](../../ai-assistant/proposals/sft-data-path.md) (#199, merged), S0 (#201, merged) and S1 (#202, open and clean). Nate asked Cowork to act as his proxy for SME validation and to get Code moving in parallel. Everything below is Cowork's review and direction; Nate keeps final say, and each default says who can overrule it. This file allocates no W-, D- or UC-IDs; Cowork maps accepted parts to existing work in a later PR.

**Hard line: no training in any of this.** Gate to any run stays section 9 of the proposal, after r7 and r8 plateau below the gates.

## 1. Decisions recorded (section 10 of the proposal)

| # | Decision | Status | Source |
|---|---|---|---|
| 1 | Regenerate candidates from pinned sources (S0) | **Answered yes**, done in #201 | Nate, 2026-10-19 (chat) |
| 2 | Track A "approved by agreement" (blind agent label matches source label, 10% audit) | **Answered yes** | Nate, 2026-10-19 17:53 CDT |
| 3 | Nate's time budget (about 2.5 h, re-planned after the first 20 items) | **Open.** Proceed on the estimate | |
| 4 | Cowork authors the frozen test and safety set (S3) | **Answered yes** | Nate, 2026-10-19 17:53 CDT |
| 5 | Hold OHF-Voice out whole for the test's `set_device_state` rows | **Cowork recommends yes**; Nate not yet asked. Proceed on yes; Nate may overrule | |
| 6 | Exclude Snips until its licence is verified | **Answered yes** (done in #201) | Nate |
| 7 | Sample size (see F-S1-3 below) | **Cowork recommends 200 families, not 300.** Proceed on 200; Nate may overrule | |

## 2. Vet of S1 (#202)

Verified on head 3359fe0: clean against main; `offline-tests` passes; docs and tooling only; ids, counts and hashes only (a test enforces no candidate text). The 941 Enfuse rows being 305 distinct wordings is the most important thing in the PR and changes the plan below.

| # | Finding | SME reading | What to do |
|---|---|---|---|
| F-S1-1 | 941 Enfuse rows = 305 wordings (211 singleton families, largest 55) | The candidates carry far less variety than the count suggested. Track A is smaller than planned | Report it as the headline in the data card; do not describe the pool as "941 rows" or "1,140" anywhere |
| F-S1-2 | Holding OHF-Voice out whole costs 103 more Enfuse rows (6 shared families). Pool: 838 | Correct and expected. Generic on/off commands that occur in both sources are test material | Keep. Record the 6 families by id in the manifest so S3 can reserve them |
| F-S1-3 | "About 300" is 300 of 305 families, so stratification selects almost nothing | A review sample that is almost the whole pool wastes reviewer and Nate time and defeats stratifying | **Regenerate with `--target 200`** on the #202 branch before merge. Track A audit then falls from about 30 to about 20 items |
| F-S1-4 | Pool is lights and living room (659 of 838 light; 506 of 838 living room); fans, locks, media, other rooms near absent | Authored commands must fill the gap, not repeat the candidates | See S2 spec: lean the 100 authored commands to fan, media, blinds, thermostat attributes, bedroom, bathroom, kitchen, office, garage, basement. **Locks, valves and covers stay out** (unsupported or critical by the contract) |
| F-S1-5 | 51 pool rows (35 in the sample) have a suspect source label | A blind relabel that "agrees" with a wrong source label would approve a bad row | Mark these rows `suspect_source_label` in the packet and **exclude them from "approved by agreement"**: they go to the second-reviewer queue regardless |
| F-S1-6 | Family detection is word overlap; device and attribute classes come from keyword lists | Good enough for sampling, not for leakage | Fine. Do not treat the families as the leakage control; section 5 of the proposal (normalized text plus token overlap, run at freeze) is |

Add to the #202 PR body: the target change and why, and that the family ids for OHF-Voice-shared families are listed.

## 3. Work order for Code

Each step is its own PR from fresh `main` with the DoD governance record. Steps 3 and 4 can run in parallel with Cowork's S3 and need nothing from it.

1. **Finish and merge #202** with `--target 200` and the suspect-label flag (F-S1-3, F-S1-5). Merge only with Nate's per-PR go and the peer-merge steps.
2. **S2: author training families**, clarify first (spec in section 4). Code and Codex author; neither reviews what they wrote.
3. **Review-packet tool and importer** (proposal section 8 item 1). S4 cannot start without it. Spec in section 5.
4. **Freeze command** in the round ledger (section 8 item 4) and the `prepare_sft.py` provenance gate plus family exclusion (item 2). The grading export gains the provenance fields (item 3).
5. **Reserve the held-out test rows** (section 6). Do this before S2 families are written so no authored family can collide.
6. **S6 and S7** only after Nate's S5 audits: leakage checks, freeze entry, then the `prepare_sft.py` dry run to produce splits and a data card. Nothing exported.
7. **Round r7** (the `clarify` prompt rule) runs when the M920q is not in use for anything else; this path never delays it.

## 4. S2 spec: authored training families

Counts are reviewed rows, as in section 5 of the proposal (clarify at least 150, unsupported 60, express_need 100, propose_automation 80, answer_question 60, about 100 authored commands).

- **Clarify first.** Eight trigger types, at least 15 rows each: pronoun with no antecedent, no room, no unit, negation, wrong card or site, schedule without a zone, conflicting site and room, and an ambiguous device. Every clarify row has a **minimal pair**: the same sentence with the missing detail supplied, labelled with the command intent. This is what teaches the boundary r6 gets wrong (clarify 3 of 9).
- **Contract fidelity.** Authored rows follow the current Java and Python contract, `requires_confirmation`, null `automation`, no entity IDs, services or topics. express_need uses the 11 needs in `concepts.json`, at least 8 each. Do not train `express_need` heater mappings: the model classifies language and the server maps the response.
- **Fictional names only.** No room, device, site, entity ID or host name from the live system. Use invented rooms and devices; the resolver maps them at runtime.
- **Families, not rows.** One paraphrase family lives in exactly one set. Give every family an id and record its set. Authored families must not reuse a wording that appears in `gold.jsonl` (dev) or in the reserved test rows.
- **Negatives and quoted instructions.** Include rows where negation, hypotheticals ("if I were cold"), quoted instructions and injected text must not produce an action. They are training for the behaviour the safety set measures, written in different words.
- **Authors:** Code and Codex. **Reviewers:** never the author of that row or family.

## 5. Review-packet tool spec

- Packets without the source label (Track A) or without any label (Tracks B and C), batched, with row ids replaced by packet-local ids.
- **Blindness is structural, not promised** (same rule as the taxonomy evaluation, F1 in [`HO-2026-10-19-cowork-to-code-w90-w91-vet.md`](HO-2026-10-19-cowork-to-code-w90-w91-vet.md)): the runner assembles each reviewer prompt from an allowlist only (the contract text, the intent definitions, and the single row), records `inputs_sha256`, and never loads source labels, the test set, `gold.jsonl` or other reviewers' output. Reviewers are clean-context sessions or local models.
- **Reviewer kind is recorded honestly:** `reviewer_kind` agent, `reviewer_model`, `track`, `second_reviewer`, `agreement` (agreed, ruled by Nate, audited). A row missing provenance is refused by `prepare_sft.py`.
- Importer computes agreement per track and builds the disagreement queue. Report agreement counts, not only percentages.
- Cowork is **not** a reviewer of Track C (it authors it). Reviewers for Track C are Codex plus a second agent of a different model family, with a local open model as a third non-ruling labeler (shared bias is assumed).

## 6. Reserving the held-out test rows (before S2)

- Reserve the OHF-Voice families (86 candidates, plus the 6 Enfuse-shared families) for the frozen test's 30 `set_device_state` rows. Record family ids only; **do not show the reserved text to Cowork, to Codex authoring, or to the training pipeline.**
- Cowork writes the other test and safety rows without seeing the reserved text. Code runs the near-duplicate check across sets at S6.
- At S6, the reserved rows join the test file; the freeze entry records the final hashes and counts.

## 7. How S3 reaches you (Cowork's lane)

- Cowork authors the safety set (80, all critical) first, then the frozen test (200), to the composition tables in section 4 of the proposal. Rows use the `gold.jsonl` envelope with added `family_id`, `set`, `trigger_type`, `author` fields.
- The rows are **sealed**: delivered to Nate as files, stored in a private folder outside any clone, never committed or pasted into a PR, issue, Project doc or chat Code can read. Cowork supplies Code with **counts and hashes only**.
- The freeze command (step 4) is where those hashes become the ledger entry; every later opening is recorded.
- Cowork's independence is declared in the freeze record: it has seen class-level r6 numbers only (clarify 3 of 9), not per-row dev failures or gold clarify fixtures.

## 8. Hosting and the M920q

- Authoring, tooling, S1, packets and PRs run on ilikethelights or any clean clone (`fotc-git-and-host-routing`).
- The M920q carries the model. Run r7 there when nothing else is running; **any fine-tune runs off the M920q** or inside a measured memory budget that cannot starve Frigate, Home Assistant or the backend (section 9). Candidate quantized the same way as baseline.
- Local reviewer models are small and run wherever there is headroom; record the host in the run manifest.

## 9. Do not

- Train, export, or change a prompt, gold row, schema or Ask behaviour as part of this work.
- Use the frozen test or safety set to tune a prompt or schema, or look at it before the freeze.
- Reuse a live room, device, entity ID, host name or credential in any authored row.
- Report 941, 1,042 or 1,140 as the candidate count; the authoritative numbers are in `candidates/manifest.json` and the S1 manifest.
- Substitute a per-class result for the pooled gate (per-class is directional at these sizes).

## 10. Report back with

The #202 re-run at target 200 (strata counts, families sampled), the 6 shared families by id, the packet tool and importer, the freeze command, the S2 `clarify` families with minimal pairs and counts by trigger type, the reservation of the OHF-Voice rows (ids only), and any decision Nate overruled.

## 11. Closing

This file is deleted in the PR that closes S7, together with the 2026-10-19 vet handover when its own items close.
