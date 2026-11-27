# dev-sft-r1 Phase 1: what still stands before the two keys (status, 2026-11-21)

Status: **status table only; no new design; authorizes nothing.** Assigned by Cowork on #244 (7037575183). Read on main at `257de1ae`. It reflects the [plan draft](dev-sft-r1-phase1-plan-draft.md) (content SHA-256 `6b135631...dd768`) after these merged documents:
- [#543 run capture](dev-sft-r1-run-capture-design.md);
- [#544 adapter scoring](dev-sft-r1-adapter-scoring-design.md);
- [#545 reviewer elections](s4-reviewer-election-mechanics.md);
- [#546 S2/S4 status](s2-s4-candidate-status-2026-11-20.md).

Refreshed 2026-11-21 by Cowork (docs only) after the implementation merges #548 to #555; the changes are marked in the rows below. A merged implementation PR is reviewed code with synthetic tests, not run evidence.

A merged design is not evidence. A decision row closes when its decision is recorded; an evidence row closes only when the work it names has run and been reviewed. Nothing here is a key, a request for a key, or permission to run anything.

**What must close before the keys.** Only the applicable pre-key rows: every decision row that applies to the chosen options, and evidence rows P1 to P14 on the chosen path. Three kinds of entry are not part of that set:
- **Conditional rows** close only if their condition holds: N6 only if option B is chosen; N7 only if option D is chosen, now or later. Choosing B needs no GGUF-export ruling and no Phase 2 execution.
- **Later-phase rows:** P15 is outside Phase 1 and never blocks the keys.
- **The keys and go milestone** ([Keys and go](#keys-and-go)) is the step after the applicable rows close, not a row that must already be closed.

**Gate order for the data** (from #546): S4 two-label review, then the S5 rulings and audit, then the S6 freeze, then the S7 dry run.

## Decisions (NEEDS-NATE, or Nate-or-Cowork where marked)

| # | Plan field | Decision | Notes |
|---|---|---|---|
| N1 | Runtime (section 6) | **Superseded 23 Nov by the CPU-first workaround.** The host decision no longer waits on another machine. Cowork ruled (#244, 7091727691) and Codex set policy (7096272556) that qualification starts CPU-first with the unchanged fp32 model on the pinned base, using a memory ladder on Nate's Windows PC (ILikeTheLights): token-length rungs 128, 256, 512, 1024, 2048, with explicit memory reserves and stop conditions, and an out-of-memory result recorded as failure evidence, never a pass. The ladder waits on Nate's yes to raise the WSL memory limit and his choice of workloads to close, given in Code's chat; nothing had run as of 23 Nov (history; superseded by the 27 Nov update below). The earlier reading (22 Nov measurement of 16.2 GB visible to the container, about 15.8 GiB in WSL, 14.21 GiB peak on tiny rows, a dry-run host only) and Nate's 22 Nov statement that he would provide another machine are kept as dated history. The other machine (nhsmrekar, 15 GiB, no swap) and M910 cannot hold the unchanged fp32 3B model and are support roles only. A bounded rented GPU run is the fallback and needs Nate's account, spend and data-placement approval. **Update 27 Nov (Cowork, docs only):** the ladder has run. Runs 5 and 6 are failure baselines; run 7 and run 8 (C-gc, gradient checkpointing in the ladder rung only; #244 7215983049 and 7227011509 for the grades) both met the prospective usefulness reading 7209507803. Run 7 completed rungs 128 and 256 and was interrupted at 512 (no rung result for 512); run 8 completed rungs 128, 256 and 512, then stopped at 1024 on the memory ceiling (by 0.04 percent). Every completed rung in both runs still carries the v1 paging FAILURE record. Standing grade: useful mechanical evidence, non-qualifying. The plan stays BLOCKED for qualification and for production trainer adoption, `train_sft.py` is unchanged, and no capacity, host-maximum or 1024 claim follows. The next step is a written prospective v2 paging-policy proposal (Code, #244 7227014902) before any new run; a new run needs Nate's separate go. [r9 paging rule](../../governance/proposals/2026-11-27-ladder-r9-paging-v2-proposal.md): D1 to D4 ratified 27 Nov (#244 7229666326), with a repeat run required before anyone relies on a pass. The harness change is not yet merged and run 9 is not authorized | The M920q stays off unless Nate amends R4 for that purpose, which is not proposed. Raising the WSL cap creates no RAM. Historical statement attributed to the 22 Nov record (#571 and #244 7076004727), preserved |
| N2 | Sealed sets (section 2) | Place safety 80 and test 170 on the freeze machine | Needed for S6; Phase 1 never reads them |
| N3 | S5/S4 packet creation | Direct yes in Codex's own chat to create and distribute blind packets | Reading the sample was authorized and is done; packet creation is a separate yes |
| N4 | Served template hash | **Closed 2026-11-20, per Cowork's record.** Code reports that it read Nate's "920 is ready" in its chat as the go and ran the read-only `ollama show --template` check (#244 comment 7057603298). The authorization is Code's interpretation as reported; Codex did not observe a separate explicit grant, and Code's factual confirmation of this wording is pending | Covers no prompt sending or token capture (#544 section 4). Code disclosed one transient `/tmp` write on the host; the check was reported by Code, and Codex compared the hash to the pins without repeating the host read |
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
| P1 | Base shard hashes re-verified | Code | **Both base shards at revision `006f5dcd` matched the contract pins and the tokenizer was verified by `train_sft.py` on ILikeTheLights, 22 Nov (Code's report, 7082670963; not independently re-read).** Repeat on the final Phase 1 host if it differs | none needed |
| P2 | Served template equals pinned copy `966de95c...6396` | Code | **Reported MATCH** for the base and served tags (Code, 7057603298); pin comparison by Codex (7057682150). Not an independent host read. Served-token parity stays NOT_RUN | none needed |
| P3 | 5 real-tokenizer tests (`SFT_TOKENIZER_JSON` set) | Code | N1, and the tokenizer file on the training host | none needed |
| P4 | Container: base digest pinned, image built from the lock | Code (digest PR), Codex (independent digest read), Code (build on host) | **Digest pin merged (#548)** with Codex's independent registry read. The capture-script COPY is merged (#562, Dockerfile sha256 `a2cf37f19f89f692f91dcdeec0889c7d8575893dd67135bc1f5b9181b42b5763` at main 2804186f, proxy CLEAN, Codex confirmation pending). **Image built 22 Nov** on ILikeTheLights (WSL Docker 29.5.3, from a byte-exact LF `git archive` of main `b0d22379`, 103 s), image id `sha256:9b2f61a3...010643`, Dockerfile and lock hashes equal the pins, base pulled by digest; apt inventory recorded in the bundle's `dpkg.txt`. This is Code's report (#244 comment 7082670963), not yet independently re-read by Codex. The apt layer stays unpinned by design (#543 keeps it as a reproducibility limitation and records dpkg evidence), so no apt-pin gate is added here | #543 section 3 |
| P5 | Dtype capability test on the chosen host | Code | **Measured on ILikeTheLights only (i5-1135G7, 8 threads, 16.2 GB visible to the container, torch 2.7.0+cpu, CUDA False): fp32 passes; bf16 and fp16 not tested on CPU.** The chosen Phase 1 host still needs its own test; it is not named yet. Code's report 7082670963 | #543 section 2C |
| P6 | Resolved `TrainingArguments` dumped | Code | **Trainer-change PR merged (#549)** with synthetic tests; no run. **Stage 2 dumped the resolved arguments on ILikeTheLights (22 Nov, run `stage2-20261122T1702Z-b0d22379`, invented rows, 2 steps; transformers 5.23.0 defaults recorded).** Still open: the same on the Phase 1 host | #543 section 2A |
| P7 | Run manifest and log capture | Code builds, Codex verifies | Trainer change (#549), digest pin (#548), `run_capture.py` (#556) and `verify_run_bundle.py` with Stage 1 CI (#558) are merged, synthetic evidence only: completion requires exit 0 and every trainer artifact, and failure bundles verify only as aborted or failed. The verifier now also refuses symlinks, drive-letter paths and dot-dot segments (#563, proxy CLEAN, Codex confirmation pending). **Stage 2 ran 22 Nov on ILikeTheLights: `run_capture` completed and `verify_run_bundle` returned `INTEGRITY_OK status=completed` against bundle hash `85aa4ce7...c9008`, which Code posted as the tamper anchor and verified against; an earlier aborted attempt also verifies as `aborted_preflight`. Code's report, not independently re-run.** Still open: the same on the chosen Phase 1 host. Remaining non-blocking test gaps (directory, run-directory and bundle.sha256 symlinks) are with Code | #543 |
| P8 | Wall time and resource limits | Code | **Measured on ILikeTheLights with invented rows, 2 steps: `train_runtime` 34.6 s (about 17 s per step, 4 rows, 207 tokens in total as reported), capture wall time 555 s including the roughly 6.4 GB weight download, and peak sampled container memory 14.21 GiB of about 15.8 GiB (82 samples at 5 s), about 1.5 GiB of headroom on tiny rows.** Real rows are longer, so a full run on this host would be slow and near out-of-memory. This measures the pipeline only and does not qualify any host for Phase 1. Limits for Phase 1 still need a run on the real training host | #543 section 4 |
| P9 | Locked S4 label and agreement evidence, pending the S5 audit and dispositions (not approved data until P10 closes) | Codex coordinates; elected labelers A and B | N3; eligibility screening before any row access; the rest of the packet-builder implementation (#541: the versioned in-memory core #550, JSONL intake fixes #551 and #552 and the read-only path preflight #555 are merged; filesystem finalization, recipient isolation and import/audit integration are not); 155 legacy rows held until family resolution | #539, #541, #545, #546 |
| P10 | S5 rulings and the 20% audit, at 90% or better | Elected auditor, not the families' authors or labelers | P9 complete; auditor election with family-level conflict checks; precommitted sample | #545 |
| P11 | S6 freeze, C0 to C7 passing, `screen-train` over the train file | Code (tooling), Nate (N2) | Real freeze: N2; completed applicable S4 and S5 evidence (P9, P10) with governed dispute closure; `round_ledger.py freeze` implemented; freeze machine. Synthetic-data tooling work needs none of those, and is not a freeze | #527 |
| P12 | S7 tooling gaps G1 to G11 | Code (PRs), Codex and Cowork review | Each gap's own PR. G2 (split renamed to holdout) merged (#553). G1 merged as a gated, unused library with synthetic tests only (#554, Cowork ruling on #244 comment 7061638768): not complete and not validated until S6 and `screen-train` exist; the CLI refuses every frozen-aware run. Open and required before any frozen-aware use: G8 (pair-family binding before the split) and the approved-input read mechanism (#536 section 3.7, not met by the library path); flipping `G8_BOUND_FAMILIES` needs its own PR, a fresh Cowork ruling and Codex review. The rest are not started | #529, #536 (G1 build spec) |
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

- Stage 2 evidence recorded 22 Nov is Code's own report on ILikeTheLights with invented rows. It is not a Phase 1 run, a model quality result, a host qualification, or a key.

- Nothing listed as a design is implemented.
- No Phase 1 run on real, sealed or S1 data, and no packet, label, audit or freeze, is recorded as having happened. Host activity recorded in this table is the N4/P2 read-only template check and the invented-row Stage 2 run above.
- The count of open items is the count above. It is not an estimate of time.
