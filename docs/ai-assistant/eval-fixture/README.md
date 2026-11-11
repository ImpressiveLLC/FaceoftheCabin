# Offline synthetic fixture and pinned analysis

Scripts and tests for the paired two-arm Ask evaluation analysis, built before any real evidence exists so the analysis is pinned and tested first. Everything here is invented data. No sealed item, no frozen-suite question and no corpus text is read or reproduced, nothing here calls a model, and the analysis opens no network connection.

| File | Role |
|---|---|
| `analyze_run.py` | Verifies a run directory against its frozen manifest, then computes the paired answerable PASS-fraction difference, the 95% paired question-block percentile bootstrap (10,000 draws, seed 20261109) and a verdict: `GAIN_MET`, `GAIN_NOT_MET` or `HOLD`. |
| `synthetic_fixture.py` | Builds synthetic runs: a hand-written sentinel set with a known answer, and random two-arm runs. |
| `test_fixture_analysis.py` | 20 offline tests. |

Run: `python3 -B -m unittest discover -s . -p 'test_*.py' -v` from this directory. CI: `.github/workflows/eval-fixture-tooling.yml`.

## What the tests pin

- **Sentinel set:** four answerable questions with control passes 0, 1, 2, 3 of 3 and treatment 3, 3, 3, 3, so the mean paired difference is exactly 0.5.
- **Two fake arms:** a clearly better arm meets the gain; identical arms do not.
- **Mapping and key isolation:** only `key.json` names an arm; the grader pack carries blinded fields only; a swapped mapping negates the difference when refrozen and is caught by the key hash when not; leak scan finds injected arm labels and the key canary.
- **Missing trial:** `HOLD`, never a silent pass. **Duplicate id, altered grades, altered trial log, missing manifest:** evidence error.
- **Technical error:** counts as non-PASS and the trial is retained. **Failed wrong-fact or latency gate:** `HOLD` even with a large gain.
- **Deterministic bootstrap:** same seed, same interval; a different seed differs; the interval for the sentinel set is pinned.
- **Network boundary and file access:** the analysis runs with sockets disabled and reads only inside the run directory.
- **Two-copy key recovery:** either intact copy is enough; neither is an error.

## What this is not

- Not an execution go, a release decision or an acceptance of any sealed item.
- The gate rules (wrong facts not up, unsupported claims not up, flags not up, p95 latency under a cap) are an **operational reading** with settings in `design.json`. The values frozen in the sealed-set kit replace them before any real run; the denominators in a real run (216 answerable, 72 probe-unsupported, 360 all trials per arm) come from the real design, not from the small synthetic sizes used here.
- Offline analogues only: custody, hosted transit, key-holder split and who may author sealed items are protocol questions for the owner and Codex, not something this fixture decides.
