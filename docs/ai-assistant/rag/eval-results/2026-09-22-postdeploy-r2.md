# Post-deploy run on r2 context — 2026-09-22

**Status: executed, not graded.** This is the paired follow-up to the [pre-change baseline](2026-09-21-v2-baseline-r1-context.md), captured immediately after [PR #94](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/94) (C1a iteration 2) merged and deployed. It records identity, coverage and aggregate facts only. **No pass rate exists**: all 72 trials are UNREVIEWED and `grade_ask.py compare` correctly reports `judgment: "not assessable"` until both sides are graded. No answer text is reproduced here. Grants no ratification, C1b authorization or completion claim.

Run with Nate's explicit approval to use the resident administrator session on the M920q (2026-09-21, reconfirmed 2026-09-22).

## Identity

| Field | Value |
|---|---|
| Suite / question file | `ask-cli-admin-questions-r2` — same [`cli-questions-r2.json`](../cli-questions-r2.json) as the baseline, SHA-256 `04586558d5e3233bd29c9020517030f1ec9592cf8736335d16cfb9bc911ea300` |
| Harness | `scripts/ask_eval.py`, SHA-256 `356b20e21af2f48b5553a8eacbda865d89d465100c95250b03fd87ea4f97bc0d` — **identical to the baseline's**, run from the same M920q clone, same flags, no `--use-context` |
| Started | 2026-09-22T04:40:33Z, completed before 04:52Z |
| Endpoint / role / repeats | `http://127.0.0.1:8090/api/helpdesk/ask`; server-derived administrator session; 3 — **identical to the baseline's** |
| Deployed backend | image tag `cabin-backend:e0c8713` (merge commit of PR #94), image id `sha256:baa871a012ebe918d7327f66251adf89fdc801b54df5405aac4262f569c1c595`. The id recorded at run start equals the id read after the run: no drift mid-run. |
| Context path exercised | `context-fixtures-r2.json`, 17 categories, document budget 6000 chars (container startup log line) — **this is the change under test** |
| Model | Same as the baseline: no `OLLAMA_*` override, so the code default `llama3.2:3b` (`a80c4f17acd5` in `ollama list`). Inferred from the default, not attested. `cabin-assistant-poc1:latest` remained installed, unused. |
| Private artifact | `~/eval-c1a-r2ctx-postdeploy-20260922.jsonl` on the M920q, mode 600, 59,318 bytes, SHA-256 `29c5b455baacd731ebcdc454146142f18c545158c7a9077db3231da95465f836`. **Not copied into the repo.** |
| Grading sidecar | `~/eval-c1a-r2ctx-postdeploy-20260922-grades-r2.json`, rubric `ask-rubric-r2` |
| Planned / executed / reviewed | 72 / 72 / **0** |

Same `checkout_sha` caveat as the baseline: the harness's own runtime field records the operator clone (`e708946…`), not the deployed commit. Use the image tag/id above.

## Comparability with the baseline (checked, not assumed)

`grade_ask.py compare` against the [baseline sidecar](2026-09-21-v2-baseline-r1-context.md) reports `"incompatible": []` — rubric, suite, question hash, role, url, repeats and planned all match, so this is a valid paired run once both sides are graded. `"judgment": "not assessable"` and `"changes": []` are correct and expected: both sidecars are freshly initialized with every trial `UNREVIEWED`, and `compare` only computes a real delta once both runs are `complete` (fully reviewed). Grading is still owed to Nate or a calibrated judge before this pair means anything.

## Aggregate facts (signal, not a grade)

- Transport: 72/72 HTTP 200. Unauthenticated denial check: HTTP 401, passed.
- `answeredByModel`: **72/72 true, 0 fallback** — a real change from the baseline's 69/3. All three of the baseline's Q01 no-context refusals now get a model-backed answer.
- Sources returned: 16 distinct entity refs; **102 of them are `doc:` (reviewed-document) sources**, versus **zero** in the r1-context baseline. Most-cited: `operations.md#capability-map` and `operations.md#device-onboarding` (12 each), `independent-installation.md#minimum-inputs` / `#verified-clean-host-limitations` (9 each), the three `ask-and-helpdesk.md` sections (9 each), `operations.md#users-and-family-data` (6). This is the routing and document-context change from PR #94 visibly taking effect; it says nothing about answer correctness.
- Latency (`elapsed_seconds`, 72 trials): median 4.03 s, p95 23.53 s, min 0.51 s, max 38.84 s — faster than the baseline's (median 6.44 s, p95 36.75 s) on this one run. Reported as observed; no causal claim, and no repeat-run variance was measured.
- A count-only scan for JWT-like strings, long opaque strings, `Bearer` values and `vault_` mentions flagged 5 trials (Q15 repeat 3, Q18 repeat 2, Q19 repeat 2, Q22 repeats 1 and 2) for a 40+-character alphanumeric span. **Checked, not left as a bare count**: all 5 matches contain two or more `/` characters and a `.java`/`.md`/`.yml`/`.sh`-shaped ending — source file or document path citations (exactly what the reviewed-document sections in these categories contain), not hashes, tokens or URLs. No unexplained opaque string. This is still a scan classification, not a full manual review of the surrounding sentences.

## What this is for, and what happens next

1. **Grade both sidecars** (this run and the [baseline](2026-09-21-v2-baseline-r1-context.md)) under [grading r2](../grading-r2.md) — same reviewer/judge, same rubric, ideally in one sitting to reduce drift.
2. **Re-run `grade_ask.py compare`** on the two graded sidecars. It will then report a real `pass_rate` delta, `changes` (per-trial before/after transitions), and `judgment` (improvement / regression / no change).
3. **Any safety, credential or fabricated-citation failure blocks C1b regardless of aggregate movement**, per [context-eval-method.md](../context-eval-method.md)'s gate. Cowork's Ratification field for C1 in [wsjf-backlog.md](../wsjf-backlog.md) is the only thing that can authorize C1b, and nothing here does that.
