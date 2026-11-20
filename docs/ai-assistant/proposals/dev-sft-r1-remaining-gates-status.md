# dev-sft-r1 Phase 1: what still stands before the two keys (status, 2026-11-20)

Status: **status table only; no new design; authorizes nothing.** Assigned by Cowork on #244 (7037575183). Read on main at `257de1ae`. It reflects the [plan draft](dev-sft-r1-phase1-plan-draft.md) (content SHA-256 `6b135631...dd768`) after these merged documents:
- [#543 run capture](dev-sft-r1-run-capture-design.md);
- [#544 adapter scoring](dev-sft-r1-adapter-scoring-design.md);
- [#545 reviewer elections](s4-reviewer-election-mechanics.md);
- [#546 S2/S4 status](s2-s4-candidate-status-2026-11-20.md).

A merged design is not evidence. Every row below stays open until the work it names has run and been reviewed. Nothing here is a key, a request for a key, or permission to run anything.

**Gate order for the data** (from #546): S4 two-label review, then the S5 rulings and audit, then the S6 freeze, then the S7 dry run.

## Decisions only Nate can take (NEEDS-NATE)

| # | Plan field | Decision | Notes |
|---|---|---|---|
| N1 | Runtime (section 6) | Yes or no to Nate's Windows PC, CPU only, 4 cores, 32 GB | The M920q stays off unless Nate amends R4 for that purpose, which is not proposed |
| N2 | Sealed sets (section 2) | Place safety 80 and test 170 on the freeze machine | Needed for S6; Phase 1 never reads them |
| N3 | S5/S4 packet creation | Direct yes in Codex's own chat to create and distribute blind packets | Reading the sample was authorized and is done; packet creation is a separate yes |
| N4 | Served template hash | Direct yes in Code's chat for a read-only `ollama show --template` on the served host, hash only | Covers no prompt sending or token capture (#544 section 4) |
| N5 | Adapter scoring (section 5) | Pick A, B, C, D or E | #544 recommends B, a paired base-versus-adapter comparison in one harness |
| N6 | If B | Approve one constrained-decoding library in `requirements.lock` | The implementing PR names it and shows its schema coverage |
| N7 | If D, now or later | Whether converting the adapter to a GGUF LoRA counts as the forbidden "GGUF export" | |
| N8 | Calibration (#544) | Whether a base-versus-r14 gap blocks the report or is only stated | #544 recommends: only stated in Phase 1 |
| N9 | Hyperparameters (section 4) | Accept the POC1 values for the first run, or ask for a change informed by the dry run | Nate or Cowork |
| N10 | Validation loss (section 4) | Whether Phase 1 wants held-out validation loss, which needs a reviewed trainer change | Nate or Cowork |
| N11 | Checkpoints (section 9) | Whether intermediate checkpoints are wanted for diagnosis, which needs a reviewed trainer change | Nate or Cowork; the default is none |
| N12 | Two keys (contract section 4) | Not Nate's to give. They are a Code comment and a Codex comment, each bound to the final plan, posted only when every row here is closed. Nate's go on the exact plan is still required | |

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
| P9 | Approved TRAIN rows (S4) | Codex coordinates; elected labelers A and B | N3; eligibility screening before any row access; packet-builder implementation (#541); 155 legacy rows held until family resolution | #539, #541, #545, #546 |
| P10 | S5 rulings and the 20% audit, at 90% or better | Elected auditor, not the families' authors or labelers | P9 complete; auditor election with family-level conflict checks; precommitted sample | #545 |
| P11 | S6 freeze, C0 to C7 passing, `screen-train` over the train file | Code (tooling), Nate (N2) | N2; `round_ledger.py freeze` implemented; freeze machine | #527 |
| P12 | S7 tooling gaps G1 to G11 | Code (PRs), Codex and Cowork review | Each gap's own PR; none is implemented yet | #529, #536 (G1 build spec) |
| P13 | Train manifest (`train-manifest.json`) | Code | P10, P11, P12 | #529 |
| P14 | Adapter generation step and its comparability checks | Code builds, Codex reviews | N5, plus N6 if B; then the checks in #544 section 4. Served-token parity stays NOT_RUN until a separate capture go | #544 |
| P15 | Phase 2 items (quantized like the baseline, release evaluation) | n/a | Not part of Phase 1 | plan section 8 |

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
