# HO-2026-10-25: training readiness for the intent/slot model (Llama 3.2 3B, Ollama)

Authority: Nate, 2026-10-25 (away, delegated proxy-PO and SME rights to this session). Requested assignments; acknowledgement or start is not implied. Git is canonical. Temporary: delete when the S6/S7 outcome is linked and signed off.

Governs [`sft-data-path.md`](../../ai-assistant/proposals/sft-data-path.md) and [HO-2026-10-19-cowork-to-code-sft-data-path](HO-2026-10-19-cowork-to-code-sft-data-path.md). Host rules: the `fotc-git-and-host-routing` skill (training and eval on the M920q, all other git work elsewhere, never stack both).

## Verdict: no training run is possible today

Training prep is done as far as an agent can take it. A run is blocked by data and access, not by tooling. Nothing was trained, exported, quantized, deployed or routed.

| Blocker | Evidence | Owner | Unblocks |
|---|---|---|---|
| Zero human-reviewed training rows | S1 manifest: `reviewed: 0`, `training_run: false`; 300 of 305 families sampled, Cowork asked for 200 (HO-2026-10-19 F-S1-3), manifest still says 300 | Nate (S5 audits), Code (S2 authoring, packet tool) | S4 review, S6 freeze |
| Sealed safety (80) and authored test (170) files are held by Nate and are not in git | HO-2026-10-19 sealed handovers; #215 records the OHF overlap check as NOT_RUN | Nate places files on the freeze machine; Code runs the check | S6 freeze, `prepare_sft.py` exclusions |
| Gold is below every threshold | r8/r9 on gold hash `1d33f622`: valid output 1.00, intent 0.837, slot micro F1 0.569 (a) / 0.581 (b); gates are intent 0.90, slot F1 0.90 | Code (prompt/contract), Cowork (gold) | A fair baseline to beat |
| No agent path from the cloud to the M920q | Tailscale unreachable from the container; device bridge has no connected folder | Code on ilikethelights or the M920q | Any run |

r7 (clarify rule) did not help and r8/r9 are repeats of the same baseline. Observed: the r8/r9 pair differs by about 0.01 slot F1 (0.569 vs 0.581) on gold hash `1d33f622`. **Provisional inference only:** that one pair suggests small differences may be noise, but a single pair is not a justified detection threshold. A threshold needs controlled repeated runs with identical data, prompt and model settings and a stated comparison method. Equal gold hashes do not show identical prompt or model conditions. Until such runs exist, treat any small difference as unresolved, neither a result nor proven noise. Observed metrics and hashes above are unchanged (Codex review, #259, 2026-10-25).

## Do not repeat

- Do not train on the 103 Enfuse rows S1 dropped until they pass the four readmission checks in `sft/s1/README.md` (Cowork, 2026-10-21).
- Do not count an agent label as a person review. Track A "approved by agreement" needs the 10 percent Nate audit at 90 percent or better.
- Do not tune on the frozen test or safety set, and do not edit gold v3.
- `train_sft.py` hardcodes `data/sft_dataset.jsonl` and `data/adapter/`; use an isolated copy of the training directory, a new `cabin-intent-slot-*` tag, and never the production `llama3.2:3b` tag or the POC1 data.

## Next actions in order

1. **Code (ilikethelights):** regenerate S1 with `--target 200` and the `suspect_source_label` flag (F-S1-3, F-S1-5) if not yet done on `main`; then S2 authored families (clarify first), the review-packet tool and importer, and `sft/coverage-matrix.json` (ids, counts, hashes only). Each its own PR from fresh `main`.
2. **Nate:** S5 reviews (about 2.5 hours total, measured after the first 20 items) and place the sealed files on the freeze machine. The order matters less than starting; the packet tool is the gate.
3. **Code (M920q, idle only):** S6 leakage checks and freeze entry (test 200, safety 80, disjoint, each with its own hash), then the `prepare_sft.py` dry run to produce splits and a data card. Nothing exported.
4. **Training readiness check (M920q), all must hold before a run:** `ollama ps` empty and no eval running; record RAM, disk and service load; the private exporter copy and candidate tag exist; the untuned base goes through the same quantized export path as the candidate; memory budget cannot starve Frigate, Home Assistant or the backend (otherwise train off the M920q). Proposal section 9 is the authoritative list.
5. **Codex:** review the exporter copy and the memory budget before the run, and keep the W-105-style rule that unrun evidence is `NOT_RUN`, not inferred. Status 2026-10-25: Codex exporter and memory review is `NOT_RUN`, pending prerequisites. Main has only the POC1 `export_to_ollama.sh` (`cabin-assistant-poc1/data` paths); do not run it for this lane. **Code supplies before sign-off:** a private exporter copy for a `cabin-intent-slot-*` candidate, the candidate tag, equivalent baseline export and quantization evidence, and a measured host memory and service-load budget. Nothing was trained, exported or routed.

## Notification path used

This session had no live peer session (ListAgents empty) and no write path to the M920q. Handoff is this file plus a comment on #244 for Codex, who is watching GitHub. Nate is notified by phone only for the three decisions he alone holds: S5 review time, placing the sealed files, and MA-16 to MA-18.

## S5 first pass by Codex (Nate, 2026-10-25 23:10 CDT)

Codex takes the first pass on the S5 review sample, then Nate reviews in person directly through Codex. Rules, all binding on Codex:

1. **Agent labels stay agent labels.** Codex's first pass is recorded as `agent`, never as Nate's review, and never counts toward "approved by agreement" without Nate's audit (90 percent or better on his 10 percent).
2. **Double-qualify every assertion.** Each label or claim carries a second, independent check (a different reading of the same source row, or a stated counter-case) and a confidence note. Disagreement between the two checks sends the row to Nate, not to a default.
3. **Find patterns on its own** (systematic label errors, family or source clusters, near-duplicate rows, rows where the model prompt and the label disagree) and report them as provisional inferences with counts, not as findings, until Nate confirms.
4. **Suspect rows** (`suspect_source_label`) are never approved by agreement and are queued for a second reviewer.
5. **No sealed rows in Git, an issue, a PR or chat.** Report counts, hashes and row ids only. The private sample stays outside any clone; Code gives Codex the path and import format.
6. **Output to Nate:** a short synthesis (patterns, disagreements, rows needing his eyes first), a quality grade with its limits stated, and the importer-ready labels file kept private.
7. Unrun checks are `NOT_RUN`. Nothing here authorizes training, export or routing.

## Governance record

| Field | Content |
|---|---|
| Use case | UC-17 (Ask), UC-21 not affected |
| Requirement / decision | sft-data-path sections 7 and 9; HO-2026-10-19 |
| WSJF linkage | Ask backlog POC1 and the intent/slot SFT lane; no new scored item |
| User value | Nate sees one place that says why training has not run and what unblocks it |
| System value | No wasted M920q load; no training on unreviewed data |
| Deviations | none |
| QA validation | numbers read from `rounds/*.json` and `sft/s1/s1-manifest.json` on `main` 75a09b7; no code, no tests |
| Release state | documentation only |
