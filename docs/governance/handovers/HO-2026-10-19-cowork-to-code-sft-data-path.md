# HO-2026-10-19: Cowork to Claude Code, fine-tuning data path (S1 to S7)

Baseline: `origin/main` 5519a08. Governs [`sft-data-path.md`](../../ai-assistant/proposals/sft-data-path.md) (#199, merged), S0 (#201, merged) and S1 (#202, open and clean). Nate asked Cowork to act as his proxy for SME validation and to get Code moving in parallel. Everything below is Cowork's review and direction; Nate keeps final say, and each default says who can overrule it. This file allocates no W-, D- or UC-IDs; Cowork maps accepted parts to existing work in a later PR.

**Sizes are a starting pattern, not a claim.** Nobody has shown how many rows this contract needs, and Nate is not convinced a particular size helps; what matters is an established loop that improves the data iteratively (section 4b). Every count below is a first pass to get the loop turning. Hold the pattern fixed and let the counts move.

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
4. **Freeze command** in the round ledger and the `sft/coverage-matrix.json` skeleton (4a) (section 8 item 4) and the `prepare_sft.py` provenance gate plus family exclusion (item 2). The grading export gains the provenance fields (item 3).
5. **Reserve the held-out test rows** (section 6). Do this before S2 families are written so no authored family can collide.
6. **S6 and S7** only after Nate's S5 audits: leakage checks, freeze entry, then the `prepare_sft.py` dry run to produce splits and a data card. Nothing exported.
7. **Round r7** (the `clarify` prompt rule) runs when the M920q is not in use for anything else; this path never delays it.

## 4. S2 spec: authored training families

Counts are reviewed rows, as in section 5 of the proposal (clarify at least 150, unsupported 60, express_need 100, propose_automation 80, answer_question 60, about 100 authored commands).

- **Grid and loop.** Generate against the coverage matrix in 4a and treat the counts as the loop's first pass (4b). Weight toward temperature, humidity and the arriving/leaving flows; keep TV and appliance coverage light.
- **Clarify first.** Eight trigger types, at least 15 rows each: pronoun with no antecedent, no room, no unit, negation, wrong card or site, schedule without a zone, conflicting site and room, and an ambiguous device. Every clarify row has a **minimal pair**: the same sentence with the missing detail supplied, labelled with the command intent. This is what teaches the boundary r6 gets wrong (clarify 3 of 9).
- **Contract fidelity.** Authored rows follow the current Java and Python contract, `requires_confirmation`, null `automation`, no entity IDs, services or topics. express_need uses the 11 needs in `concepts.json`, at least 8 each. Do not train `express_need` heater mappings: the model classifies language and the server maps the response.
- **Fictional names only.** No room, device, site, entity ID or host name from the live system. Use invented rooms and devices; the resolver maps them at runtime.
- **Families, not rows.** One paraphrase family lives in exactly one set. Give every family an id and record its set. Authored families must not reuse a wording that appears in `gold.jsonl` (dev) or in the reserved test rows.
- **Negatives and quoted instructions.** Include rows where negation, hypotheticals ("if I were cold"), quoted instructions and injected text must not produce an action. They are training for the behaviour the safety set measures, written in different words.
- **Authors:** Code and Codex. **Reviewers:** never the author of that row or family.

## 4a. Device classes, not brands: what the model learns to control

**Rule.** The model sees and emits **device classes**, never brands or models. A TV is a TV whether it is a Samsung or an LG; a dishwasher is a dishwasher. The slot carries the user's class word (`tv`, `dishwasher`, `heater`). Brand and model words are noise to the model: they are not copied into slots, and rows with and without a brand must carry the **same label** (author them as minimal pairs). If a brand is the only thing separating two same-class devices, the **resolver**, not the model, handles it (clarify or pick by alias). A device whose class is unknown, or a class not on the list below, is `clarify` or `unsupported`, never a guess. *(Cowork SME default; Nate may overrule.)*

**What matters most is comfort and occupancy.** Most of what we will actually control is temperature, humidity (through circulation and dehumidifying) and the devices behind the "I'm on the way" and "Leaving now" flows. Entertainment and appliances (TV, dishwasher, washer, dryer) are covered by class but are lower priority and need only light coverage.

| Class (words users say) | Priority | Operations to cover | Notes |
|---|---|---|---|
| heater, space heater, heat, baseboard | **high** | on / off; set temperature with a unit; named levels | The cabin mech-room heater is a plug today, so on/off is the working operation; a numeric setpoint applies where a thermostat exists |
| thermostat | **high** | set temperature (unit required), read temperature, mode if offered | Missing unit is `clarify`, never inferred |
| fan, circulation fan, exhaust fan, bathroom fan | **high** | on / off, speed where offered | Circulation is how humidity is moved; the S6 air pump (Q-DN-1, active speed control) belongs here as a fan-like class once W-78 designs it. Do not train its actuator until that exists |
| dehumidifier | **high** | on / off, humidity target (percent) | |
| humidifier | medium | on / off, target | Same shape as dehumidifier |
| water heater | **high** | power on / off | Switch hardware is still pending, so the resolver will report it unavailable; the model still classifies it. Use only power operations |
| air conditioner, mini split, cooling unit | medium | on / off, set temperature with a unit | The Midea belongs here |
| smart plug, outlet | medium | on / off | |
| lights | medium | on / off, brightness | Already over-represented in the candidates; do not add more |
| TV | low | on / off | Brand-agnostic by the rule above |
| dishwasher, washer, dryer | low | read state ("is the dishwasher done"), on / off only where plausible | Mostly `get_device_state`; starting or running a cycle is an action to review before adding |
| lock, valve, cover, garage door, alarm | **out** | classify only as `unsupported` or `clarify` per the contract | Never a training command |

**Operations map to the existing intents:** `set_device_state` (on/off), `set_device_attribute` (temperature, humidity target, speed, brightness), `get_device_state` (reads), `propose_automation` (a rule or schedule), `express_need` (a feeling), `clarify`, `unsupported`, `answer_question`. This handover adds **no new intent, slot or concept**.

### The arriving and leaving flows

These are **`express_need`** with the existing `arriving` and `leaving` concepts. The model classifies the language; **governed concept data decides the responses**, the same split as `cold` (POC2). Nothing here is a model output.

- **Phrasings to cover:** "I'm on my way", "heading to the cabin", "we'll be there in an hour", "I'm headed home", "leaving now", "we're heading out", "locking up and leaving the cabin". Include the site when stated; **no default to the cabin**. "Leaving now" with no site is still `express_need` with no site, and the resolver asks.
- **Not these flows:** a persistent rule ("every time I leave, turn the heat down") is `propose_automation` and belongs in the platform `WorkflowRuleService` (Q-OC-5). A direct command ("turn the heater down to 50") is `set_device_attribute`. Do not let the flow phrasing absorb either.
- **Candidate responses (proposals for Nate, not applied):** leaving at the cabin, offer to set heaters to the away setback (50 °F) and switch the water heater off; arriving, offer to set heaters to the comfort setpoint (70 °F) and switch the water heater on. Each response is a separate option card; none runs without confirmation; the existing leak, freeze and siren automations in Home Assistant stay as they are. `concepts.json` asks are deliberately empty until Nate reviews them, so **Cowork will draft the proposed asks in a separate PR for his review**. Code does not edit `concepts.json` asks.
- **Two numbers are policy, not model output:** the **50 °F floor** and the **70 °F comfort** setpoint. The server owns them. A request to go below the floor ("set the heaters to 40") is still classified by the model as `set_device_attribute` with its unit; the **policy layer** blocks it. Do not train the model to refuse by number; test the floor in the resolver and proposal tests, and in Cowork's safety set only as a classification case.
- **Concept gap to raise, not to train:** humidity worries ("it's damp in here", "the bathroom is muggy") have no ratified need concept. `stuffy` exists; a damp or humid concept would change the schema enum, prompts and Java copy, so it needs Nate's ruling first (a W-66 vocabulary change). Until then, humidity is covered by direct commands and `get_device_state`. **Never author a training row with a concept id that is not in `concepts.json`.**

### Coverage matrix (the pattern Code builds)

Rows across the sets are generated against one grid: **device class x operation x phrasing type x site/room**.

- Phrasing types: plain command, polite, shorthand, with unit, without unit (clarify), negation, brand present vs absent, room stated vs missing, other-site, needs-language, rule/schedule.
- Commit a small `sft/coverage-matrix.json` listing every cell with **counts per set (train, dev, test, safety) and family ids only, never text**. The S1 manifest pattern (ids, counts and hashes) is the model.
- The matrix is the shared map: S2 fills train cells, Cowork fills test and safety cells from the same grid with different families, and any cell empty in train but present in test is a visible gap.

## 4b. Iterating, instead of hitting a number

Counts do not prove the data is enough. A loop does. Build the process so each pass improves the data on evidence:

1. **Run** the candidate (or the prompt round) against dev and the frozen test; **read failures by matrix cell**, not by pooled score.
2. **Add training families** to the failing cells (new wordings, minimal pairs), never copies of test or dev wording.
3. **Add new test families** to cells the test undersampled; never reuse a family. When a test version has been opened and learned from, retire it to dev and cut `test-v2` from fresh families, so the loop never trains against its own exam.
4. **Record per cell** counts and the result in the round ledger; the pooled gates stay the decision rule, per-cell results guide where to add data.
5. **Stop adding** to a cell when it is clean for two consecutive runs and its test count is at least the interval you can defend; move to the next failing cell.

The initial sizes in the proposal and in section 4 above are where to start the loop. Nate's call, which Cowork shares: the pattern matters more than any threshold on size. If a cell reaches clean with fewer rows than planned, stop early; if a cell never moves, fix its wording or contract, not its count.

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
- Author a row with a brand-specific slot value, a concept id not in `concepts.json`, a lock, valve or cover command, or a number the server owns (the 50 °F floor, the 70 °F comfort setpoint) as something the model must enforce.
- Edit `concepts.json` asks. Cowork drafts them for Nate's review.
- Substitute a per-class result for the pooled gate (per-class is directional at these sizes).

## 10. Report back with

The #202 re-run at target 200 (strata counts, families sampled), the 6 shared families by id, the packet tool and importer, the freeze command, the S2 `clarify` families with minimal pairs and counts by trigger type, the reservation of the OHF-Voice rows (ids only), the `sft/coverage-matrix.json` skeleton with the cells above, and any decision Nate overruled.

## 11. Closing

This file is deleted in the PR that closes S7, together with the 2026-10-19 vet handover when its own items close.
