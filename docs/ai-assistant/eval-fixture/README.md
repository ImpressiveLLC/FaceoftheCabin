# Offline synthetic fixture and pinned analysis

Scripts and tests for the paired two-arm Ask evaluation analysis, built before any real evidence exists so the analysis is pinned and tested first. Everything here is invented data. No sealed item, no frozen-suite question and no corpus text is read or reproduced, nothing here calls a model, and the analysis does no network access.

| File | Role |
|---|---|
| `analyze_run.py` | Verifies a run directory against its frozen manifest behind an unmasking barrier, then computes the paired answerable PASS-fraction difference, the 95% paired question-block percentile bootstrap and a verdict: `GAIN_MET`, `GAIN_NOT_MET` or `HOLD`. |
| `synthetic_fixture.py` | Builds synthetic runs: a hand-written sentinel set with a known answer, and random two-arm runs. |
| `test_fixture_analysis.py` | 47 offline tests. |
| `requirements.txt` | The one dependency, NumPy, pinned. The analysis is only reproducible with this version. |

Run from this directory: `python3 -m pip install -r requirements.txt`, then `python3 -B -m unittest discover -s . -p 'test_*.py' -v`. CI: `.github/workflows/eval-fixture-tooling.yml`.

## Contracts the tests pin (Codex HOLD 6804141788, issue 244)

- **A1 gates, absolute and per arm.** Evaluated separately for control and treatment: flags = 0 over every retained trial; unsupported claims = 0 over probe-unsupported trials; wrong facts at most 10% of retained answerable trials; p95 latency strictly under 45 s over every retained trial. Equal counts in both arms do not rescue a failed gate. Boundary cases (exactly 45 s, 8.3% vs 16.7% wrong facts) are tested.
- **Frozen bootstrap.** `numpy.random.Generator(PCG64(20261109))`, 10,000 draws of question indices, mean paired difference per draw, `numpy.quantile` with the default linear interpolation at 0.025 and 0.975. Checked against analytic cases that need no generator, an independent linear-interpolation reference, and pinned first draws for the pinned NumPy version.
- **Unmasking barrier.** Two independent frozen grade files (`grades_a.json`, `grades_b.json`) and a frozen `adjudication.json` must all be present and match the manifest before the key is opened; a test spies on file reads to show the key is untouched when any is absent or changed. The final grade is the shared grade, or the adjudicated one for a disagreement; adjudication for an agreed trial is refused; an unadjudicated disagreement is `HOLD`.
- **Grading is complete and well-formed before the key opens.** Every grade and adjudication record must have exactly the four fields, a `PASS` or `FAIL` grade and real booleans: a missing, null or non-boolean value fails closed rather than counting as false. Both graders must cover exactly the grader pack, and the adjudication must hold exactly the disagreements. An empty, partial or unresolved set is an error with zero key reads (tests spy on reads, including refrozen empty and partial files).
- **Grader pack contract.** Each record carries `blinded_id`, `question_id`, `question_text`, `answer_text` and the frozen gold (`expected_behavior`, `gold_facts`, `source_support`), and nothing that names an arm or a trial. The pack must be in exact one-to-one correspondence with the key and trials, and each record must match its trial and the frozen gold. An empty, short, duplicated, foreign or altered pack fails closed even when refrozen.
- **Classes.** `answerable`, `partial` and `probe_unsupported`. Fallback answers are ordinary graded answers: non-PASS (not a wrong fact) on an answerable question, PASS on a probe.
- **Also:** missing trial is `HOLD`; duplicate id, altered files and a missing manifest are errors; technical errors count as non-PASS and the trial is retained; swapped arm mapping is caught by the key hash or negates the result when refrozen; two-copy key recovery.

## What this does not establish

- **Not host isolation.** Host and account isolation, external-egress, log, cache and exception sentinel scans, and unauthorized-reader checks are separate required evidence from the custody environment. The socket test here is a Python-level unit check only, and directory separation of the key is layout, not custody.
- Not an execution go, a release decision or an acceptance of any sealed item.
- Some inputs are Code's reading until kit r4 is available to Code: the pack field names, the p95 method (nearest rank), and the partial-class definition. The real denominators (216 answerable, 72 probe-unsupported, 360 all trials per arm) come from the real design, not the small synthetic sizes.
