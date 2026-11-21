# dev-sft-r1 Phase 1: what still stands before the two keys (status, 2026-11-20)

Status: **status table only; no new design; authorizes nothing.** Assigned by Cowork on #244 (7037575183). Read on main at `257de1ae`. It reflects the [plan draft](dev-sft-r1-phase1-plan-draft.md) (content SHA-256 `6b135631...dd768`) after these merged documents:
- [#543 run capture](dev-sft-r1-run-capture-design.md);
- [#544 adapter scoring](dev-sft-r1-adapter-scoring-design.md);
- [#545 reviewer elections](s4-reviewer-election-mechanics.md);
- [#546 S2/S4 status](s2-s4-candidate-status-2026-11-20.md).

A merged design is not evidence. A decision row closes when its decision is recorded; an evidence row closes only when the work it names has run and been reviewed. Nothing here is a key, a request for a key, or permission to run anything.

**What must close before the keys.** Only the applicable pre-key rows: every decision row that applies to the chosen options, and evidence rows P1 to P14 on the chosen path. Three kinds of entry are not part of that set:
- **Conditional rows** close only if their condition holds: N6 only if option B is chosen; N7 only if option D is chosen, now or later. Choosing B needs no GGUF-export ruling and no Phase 2 execution.
- **Later-phase rows:** P15 is outside Phase 1 and never blocks the keys.
- **The keys and go milestone** ([Keys and go](#keys-and-go)) is the step after the applicable rows close, not a row that must already be closed.

**Gate order for the data** (from #546): S4 two-label review, then the S5 rulings and audit, then the S6 freeze, then the S7 dry run.

## Decisions (NEEDS-NATE, or Nate-or-Cowork where marked)

| # | Plan field | Decision | Notes |
|---|---|---|---|
| N1 | Runtime (section 6) | Yes or no to Nate's Windows PC, CPU only, 4 cores, 32 GB | The M920q stays off unless Nate amends R4 for that purpose, which is not proposed |
| N2 | Sealed sets (section 2) | Place safety 80 and test 170 on the freeze machine | Needed for S6; Phase 1 never reads them |
| N3 | S5/S4 packet creation | Direct yes in Codex's own chat to create and distribute blind packets | Reading the sample was authorized and is done; packet creation is a separate yes |
| N4 | Served template hash | Direct yes in Code's chat for a read-only `ollama show --template` on the served host, hash only | Covers no prompt sending or token capture (#544 section 4) |
| N5 | Adapter scoring (section 5) | Pick A, B, C, D or E | #544 recommends B, a paired base-versus-adapter comparison in one harness |
| N6 | Conditional: only if B | Approve one constrained-decoding library in `requirements.lock` | The implementing PR names it and shows its schema coverage |
| N7 | Conditional: only if D, now or later | Whether converting the adapter to a GGUF LoRA counts as the forbidden "GGUF export" | |
| N8 | Calibration (#544) | Whether a base-versus-r14 gap blocks the report or is only stated | #544 recommends: only stated in Phase 1 |
| N9 | Hyperparameters (section 4) | Accept the POC1 values for the first run, or ask for a change informed by the dry run | Nate or Cowork decides; this is decision closure only, no run is implied |
| N10 | Validation loss (section 4) | Whether Phase 1 wants held-out validation loss, which needs a reviewed trainer change | Nate or Cowork decides; decision closure, and any trainer change is separate reviewed work |
| N11 | Checkpoints (section 9) | Whether intermediate checkpoints are wanted for diagnosis, which needs a reviewed trainer change | Nate or Cowork decides; the default is none, and any trainer change is separate reviewed work |

## Keys and go {#keys-and-go}

The two execution keys (contract section 4) are not Nate's to give. They are one Code comment and one Codex comment, each bound to the final plan. They are posted only after every applicable pre-key row above is closed, and Nate's go on the exact plan is still required. **The final plan must be re-read and re-pinned first.** The draft's candidate pins and counts still describe 425 rows and the repository has changed since; neither this table nor the merged designs satisfy that re-pin, and no plan rewrite is requested in this PR.

## Evidence still to produce (PENDING-EVIDENCE)

| # | Plan field | Owner | Unblocked by | Design on main |
|---|---|---|---|---|
| P1 | Base shard hashes re-verified | Code | N1, then a metadata read on the training host | none needed |
| P2 | Served template equals pinned copy `966de95c...6396` | Code | N4 | none needed |
| P3 | 5 real-tokenizer tests (`SFT_TOKENIZER_JSON` set) | Code | N1, and the tokenizer file on the training host | none needed |
| P4 | Container: base digest pinned, image built from the lock | Code (digest PR), Codex (independent digest read), Code (build on host) | The digest PR has no gate, because a registry read is not a host change; the build waits on N1 | #543 section 3 |
| P5 | Dtype capability test on the chosen host | Code | N1, then #543 Stage 2 | #543 section 2C |
| P6 | Resolved `TrainingArguments` dumped | Code | The trainer-change PR (`--run-dir`, `--max-steps`, args and loss files), then Stage 2 | #543 section 2A |
| P7 | Run manifest and log capture | Code builds, Codex verifies | PRs for the trainer change, `run_capture.py` and `verify_run_bundle.py` with Stage 1 CI, and the Dockerfile COPY and digest; then Stage 2 on N1 | #543 |
| P8 | Wall time and resource limits | Code | Stage 2 dry run on invented rows, after N1 | #543 section 4 |
| P9 | Locked S4 label and agreement evidence, pending the S5 audit and dispositions (not approved data until P10 closes) | Codex coordinates; elected labelers A and B | N3; eligibility screening before any row access; packet-builder implementation (#541); 155 legacy rows held until family resolution | #539, #541, #545, #546 |
| P10 | S5 rulings and the 20% audit, at 90% or better | Elected auditor, not the families' authors or labelers | P9 complete; auditor election with family-level conflict checks; precommitted sample | #545 |
| P11 | S6 freeze, C0 to C7 passing, `screen-train` over the train file | Code (tooling), Nate (N2) | Real freeze: N2; completed applicable S4 and S5 evidence (P9, P10) with governed dispute closure; `round_ledger.py freeze` implemented; freeze machine. Synthetic-data tooling work needs none of those, and is not a freeze | #527 |
| P12 | S7 tooling gaps G1 to G11 | Code (PRs), Codex and Cowork review | Each gap's own PR; none is implemented yet | #529, #536 (G1 build spec) |
| P13 | Train manifest (`train-manifest.json`) | Code | The completed data gates P9, P10, P11 and the S7 tooling P12 | #529 |
| P14 | Adapter generation step and its comparability checks | Code builds, Codex reviews | N5, plus N6 if B; then the checks in #544 section 4. Served-token parity stays NOT_RUN until a separate capture go | #544 |
| P15 | Phase 2 items (quantized like the baseline, release evaluation) | n/a | Later phase: not applicable to Phase 1, never blocks the keys | plan section 8 |

## What can move without any Nate decision

- **Code:**
  - the Dockerfile digest-pin PR (P4, the registry read only);
  - the trainer-change PR, plus `run_capture.py` and `verify_run_bundle.py` with Stage 1 CI (P6, P7);
  - the S7 gap PRs starting with G1 (P12);
  - `round_ledger.py freeze` tooling for S6 on synthetic data (P11).

  All are code PRs with tests, reviewed by Codex and Cowork, and none runs anything on a host.
- **Codex:** the packet-builder implementation (#541) and eligibility-screening records on synthetic data (#545). Actual packets wait on N3.

Cowork assigns which of these start next.

## Not claimed

- Nothing listed as a design is implemented.
- No host read, build, packet, label, audit, freeze or run has happened.
- The count of open items is the count above. It is not an estimate of time.
