# Ladder run 9: prospective paging rule `v2-stall` (proposal)

Status: proposed, docs only. Code, 27 Nov 2026, per Cowork's direction on #244 (7227014902), drafted on Nate's go in Code's chat. This note authorizes no run, no code, no launcher, host, WSL or Docker change and no trainer change. A run needs Nate's separate go.

Runs 5 to 8 stay exactly as graded. Nothing here relabels them. Their figures below are evidence for choosing a rule. None of them is a `v2-stall` verdict, and their sealed records lack the trace this rule would judge (section 5).

## 1. Decisions requested

| # | Question | Code recommends |
|---|---|---|
| D1 | May kernel stall time (PSI) judge a rung? Today it is evidence only (7202263773, #614). | Yes, in the training phase, under a new labelled rule |
| D2 | What stall exposure is tolerated? | At most 1 percent of the training phase with at least one cgroup task stalled on memory, and the same for IO, judged separately |
| D3 | Accept that load and save are not judged for paging? | Yes, with the cost stated in section 4 |
| D4 | Is one passing run enough to record a qualified rung for this exact configuration? | Yes; a reviewer may require a repeat before any use of it |

Order: Codex peer review, Cowork review, then Nate. Merging this note decides none of D1 to D4.

## 2. Evidence base

| Run | Configuration | Source | `manifest.sha256` hash | Standing grade |
|---|---|---|---|---|
| 5 `20261126T192335Z` | C, v1 | `c70c43b1` | `7a687ec38c13a60799f64578cbaa80baa669f4c5b7c9daf03c743af93c48b384` | Failure baseline (7202263773) |
| 6 `20261127T034325Z` | C, v1, evidence mode | `3ab6b495` | `4478959e0a0bc145cd1ac890775893549fa0abdd619e84b3ea1410d2daf8aaf6` | Failure baseline (7209507803) |
| 7 `20261127T082426Z` | C-gc, v1, evidence mode | `b7c7617c` | `b6b8526128526f47d91080623f61bb3a64efe840b5b7a39b858393b0af772c60` | Interrupted, useful, non-qualifying (7215983049) |
| 8 `20261127T190750Z` | C-gc, v1, evidence mode | `de01c53a` | `4a7395ad2994ec645a9488ca5dc2c1aad98143b50e2207e529f478104df70b71` | Failure, useful, non-qualifying (7227011509) |

Training phase of every rung that completed 3 steps. Stall figures are the PSI `some` delta divided by the phase's elapsed time. Run 5 predates PSI recording.

| Run, rung | Training seconds | Major faults, whole rung / training | Worst file refaults per second | Memory stall | IO stall |
|---|---|---|---|---|---|
| 5, 128 | 100.5 (trace) | 28,729 / 235 | 115.7 | not recorded | not recorded |
| 6, 128 | 99.5 | 28,767 / 234 | 94.7 | 0.083% | 0.035% |
| 7, 128 | 144.8 | 28,851 / 300 | 98.8 | 0.029% | 0.020% |
| 7, 256 | 256.6 | 22,477 / 307 | 508.8 | 0.019% | 0.017% |
| 8, 128 | 142.1 | 28,771 / 302 | 98.1 | 0.030% | 0.024% |
| 8, 256 | 253.6 | 20,901 / 340 | 592.9 | 0.020% | 0.015% |
| 8, 512 | 553.0 | 19,532 / 319 | 482.7 | 0.018% | 0.013% |

Rungs stopped by the memory ceiling had short training phases with higher shares: run 6 rung 256, 1.53 percent memory over 5.6 s; run 8 rung 1024, 0.37 percent memory over 31.6 s. No completed rung recorded an anonymous-page refault in training.

## 3. Why neither existing rule

- **v1** counts major faults over the whole rung against 200. Code set that number and it was never ruled. In run 5, 92.9 percent of the faults were in load. Training alone also exceeds 200 on every completed rung, so moving v1 to the training phase would not pass at that number.
- **The refault-rate `v2` in the code** has no ruled limit and has judged no run. A limit of 500 per second was proposed (7138532800), rejected by Codex because refault throughput does not establish stall or throughput cost (7138721525), and withdrawn (7139254479). Since then, the two highest worst-window rates (508.8 and 592.9 per second) came with training memory stall shares of about 0.02 percent. A new limit placed just above 592.9 would be fitted to run 8 and is not proposed.

## 4. Proposed rule `v2-stall`

The label differs from `v2` so that no reader confuses it with the refault-rate rule.

**What it limits:** the fraction of the training phase with at least one cgroup task stalled on memory, or on IO. Other tasks may still make useful progress during that time, so the figure is not lost training time.

A rung fails the paging rule if any of these holds in its **training** phase:

1. memory PSI `some` delta is more than 1 percent of the phase's elapsed time;
2. IO PSI `some` delta is more than 1 percent of the phase's elapsed time;
3. any anonymous-page refault;
4. the evidence in section 5 is missing or invalid.

Exactly 1 percent passes. The comparison is unrounded. Memory and IO are never added together. The existing swap rule is unchanged: any swap use, or incomplete swap evidence, fails.

Reported and not judged: major faults, refault rates, PSI `full`, and load and save stalls.

**Why `some` and not `full`.** `some` is the time during which at least one task waited on the resource; `full` is the time during which all non-idle tasks waited at once ([kernel documentation](https://docs.kernel.org/accounting/psi.html)). Training uses several threads, and one waiting thread can hold up the others without counting in `full`. `some` is never smaller than `full`, so it is the stricter choice. The two are nearly equal in the table's runs.

**Where 1 percent comes from.** It is an exposure tolerance, not a measured constant: in a qualified rung, at least one cgroup task was stalled on memory for at most 1 percent of the training phase, and on IO for at most 1 percent. For a training phase as long as the 20-minute rung limit, 1 percent is 12 s of such time. **Disclosure:** it is proposed after seeing the data. The highest completed-rung figures are about 12 times (memory) and 29 times (IO) below it. A limit of 0.5 percent or 5 percent would also sit above every completed rung, so the recorded data cannot choose among the three. The choice is how much stall exposure is acceptable.

**Limitations.** A share over the whole phase can hide a short burst. Start-of-training stalls weigh more in a short phase (see the stopped rungs in section 2). PSI share is neither lost training time nor a measured slowdown against an unconstrained run. Three optimizer steps say nothing about a long training job.

**Cost of not judging load and save (D3).** Across the completed rungs of runs 6 to 8, load stalls were 5.9 to 12.0 percent (memory) and 14.4 to 22.5 percent (IO), and save IO stalls 2.0 to 2.8 percent. Both phases would exceed 1 percent if judged. The proposal excludes them because each happens once per job, and the memory ceiling, host floor, swap rule and rung timeout still cover them. A reviewer who wants load protected needs a separate limit with its own reason.

## 5. Valid samples, freshness and coverage

**Harness change needed.** PSI totals reach `rung.json` only as a summary written by the rung process, so a grader cannot recompute them as it can for memory peaks and paging. Under `v2-stall` the rung writes `pressure-trace.csv`, one row per watcher sample including both samples at each phase boundary, and the manifest seals it. The parent judges from that file. Its columns:

| Column | Content |
|---|---|
| `read_start_ns` | The rung process's monotonic clock, read immediately before the two pressure files |
| `phase` | `load`, `train` or `save` |
| `memory_some_us`, `memory_full_us`, `io_some_us`, `io_full_us` | The kernel's cumulative `total=` values |
| `read_end_ns` | The same clock, read immediately after the two files |

**One interval for numerator and denominator.** Let S be the first row labelled `train` and E the last. Both are boundary samples taken at the phase change. For each resource, the numerator is E's `some` total minus S's. The denominator is E's `read_start_ns` minus S's `read_end_ns`. Both come from those two rows only: no row from load or save is borrowed, and no duration is taken from `rung.json` or another clock. The counters were each read somewhere inside their row's two timestamps, so this denominator is the shortest the interval can have been. The share can only be overstated, never understated, by the time spent reading.

A rung passes rules 1 and 2 only if every check below holds and, for each resource, `numerator_us * 100000 <= denominator_ns`, in integer arithmetic. The verdict is written as a condition to pass, never as "fail if over", so a missing or unusable value cannot pass.

The training evidence is valid only if all of these hold. Otherwise the rung fails.

- Every line has exactly these columns. A cut or extra line invalidates the trace.
- Every timestamp and every total is a nonnegative whole number in plain digits. Empty, NaN, infinite, negative, decimal or non-numeric values are invalid.
- Time runs forward: in each row `read_end_ns` is not before `read_start_ns`, and no row starts before the previous row ends.
- No total goes backwards.
- Phases appear once each, in the order load, train, save.
- At least two training rows, and a denominator that is positive and at least 5 s.
- No gap over 5 s, measured from one row's start to the next row's end, including across both boundaries. Samples are normally about 0.5 s apart.
- Both pressure files were readable for every training row. An absent or malformed file is a failure, never zero.
- The trace and the `rung.json` pressure summary agree on the training start and end totals.
- The existing refault-counter coverage checks pass, because rule 3 depends on them.

**Save coverage.** The existing refault coverage reads false for save in runs 5 to 8 for a structural reason: it requires a phase to span 5 s, and save lasted 1.15 to 1.52 s in runs 6 to 8. `v2-stall` judges training only, so run 9 needs **no harness change for save**. Save stalls stay recorded and labelled as not judged. Judging save would need a different minimum and a closing sample after it, which is not proposed.

## 6. Acceptance criteria, fixed before any run

**Useful:** rungs 128, 256 and 512 each complete 3 steps at or under the computed ceiling with checkpointing shown active, and each gets a `v2-stall` verdict, pass or fail, from complete and valid evidence.

**Qualifying, per rung:** the rung is PASS under `v2-stall` and every other gate; every shorter rung is PASS in the same run; the manifest verifies with exit 0; intake, an independent grade that recomputes peaks, paging and stall shares from the traces, and adjudication are posted. Review decides qualification; `summary.json` never does. A qualification covers that rung length, this exact configuration on this PC and a trainer that uses gradient checkpointing.

**Stop:** the ladder stops at the first FAILURE. That includes the memory ceiling, host floor, swap, an OOM event, an anonymous refault, a stall share over the limit, missing or invalid evidence, checkpointing not active, the rung timeout and a launcher error. An interrupted rung gets no verdict. No tuning, limit change or further run without a new ruling.

Rung 1024 crossed the memory ceiling in run 8 by 6,271,796 bytes, so a stop there is plausible. A later failure does not remove an earlier rung's PASS.

**Run 9 would not establish:** a host maximum; capacity at 1024 or 2048; anything about `train_sft.py` or a trainer without checkpointing; trainer adoption; long training runs, real data or model quality.

## 7. Configuration

Same as run 8: configuration C-gc; pinned `unsloth/Llama-3.2-3B-Instruct@006f5dcd1393c3add266de40994ba96225e9689d`, float32, LoRA rank 16, effective batch 4; invented rows only; 3 optimizer steps per rung; rungs 128, 256, 512, 1024, 2048; 20-minute rung timeout; ceiling metric `anon_shmem`; budget fixed once as the lower of 22 GiB and Windows available minus 4 GiB; container limit equal to that budget minus 2 GiB, with no swap and no network; ceiling 90 percent; 4 GiB Windows floor; unchanged start gate; checkpointing non-reentrant.

| Difference from run 8 | Why |
|---|---|
| Paging rule `v1` becomes `v2-stall` | Sections 3 and 4 |
| Evidence mode off | It is defined for `v1` only and never qualifies. A qualifying run must stop at the first failure |
| `pressure-trace.csv` written | Section 5 |
| Source commit is main after the code change | The rule does not exist in the code today |

## 8. Code change, if D1 to D3 are accepted

A separate PR with tests and no run. This note does not authorize it.

- `ladder.py`: add `v2-stall`; fixed limits of 1 percent (or the ruled figure) with no command-line override; judge from `pressure-trace.csv`; allow gradient checkpointing with `v2-stall` as well as `v1` (it is refused today); keep evidence mode `v1` only.
- `ladder_rung.py`: write the trace when the rule is selected.
- `run_ladder.ps1`: accept `-PagingRule v2-stall`, including with `-GradientCheckpointing`. No change to budget, ceiling, floor, start gate, timeout or watchdog.
- Tests: exactly at the limit passes and one microsecond over fails; each invalid-evidence case in section 5 fails, including empty, NaN, infinite, negative and decimal values, time running backwards, a zero, negative or under 5 s denominator, and a decreasing total; load and save rows and the `rung.json` duration do not change the result; a trace and summary mismatch fails; `v1` and `v2` outputs unchanged byte for byte; evidence mode with `v2-stall` refused; launcher tests on Windows PowerShell 5.1 and PowerShell 7. Synthetic fixtures only; the new rule is not applied to any recorded run.

The unused refault-rate `v2` stays as it is; removing it is separate work.
