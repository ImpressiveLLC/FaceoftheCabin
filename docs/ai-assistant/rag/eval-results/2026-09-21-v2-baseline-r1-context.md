# v2 question-file baseline on r1-context production — 2026-09-21

**Status: executed, not graded.** This is the pre-change baseline for [C1a iteration 2](../c1a-iteration-2.md), captured on the deployed **r1** context path before r2 can deploy. It records identity, coverage and aggregate facts only. **No pass rate exists**: all 72 trials are UNREVIEWED and the grading sidecar says so. No answer text is reproduced here. Grants no ratification, C1b authorization or completion claim.

Run with Nate's explicit approval to use the resident administrator session on the M920q (2026-09-21), after Cowork's relayed authorization (2026-09-20).

## Identity (fields of the [D template](../delta-analysis-template.md))

| Field | Value |
|---|---|
| Suite / question file | `ask-cli-admin-questions-r2` — [`cli-questions-r2.json`](../cli-questions-r2.json), committed-byte SHA-256 `04586558d5e3233bd29c9020517030f1ec9592cf8736335d16cfb9bc911ea300` (only Q21's `expected` differs from r1) |
| Harness | `scripts/ask_eval.py`, SHA-256 `356b20e21af2f48b5553a8eacbda865d89d465100c95250b03fd87ea4f97bc0d` (identical to the 2026-09-07 run's), run from the M920q clone at `main` `4bcbd4d`, flags `--record-runtime --resident-admin-session`, **no** `--use-context` |
| Started / completed | 2026-09-21T16:47:39Z / observed finished before 17:02Z |
| Endpoint / role / repeats | `http://127.0.0.1:8090/api/helpdesk/ask`; server-derived administrator session; 3 |
| Deployed backend | image tag `cabin-backend:4bcbd4d`, image id `sha256:20d18a68a8bc3893b1a7483e2fa332627fb18b71806833f0b2b62f1e9de4b88c`. The id the harness recorded at start equals the id read after the run, so the image did not change mid-run. |
| Context path exercised | `context-fixtures-r1.json`, 17 categories (container startup log line), i.e. **before** C1a iteration 2 |
| Model | The container sets no `OLLAMA_*` override, so it uses the code default `llama3.2:3b` (digest `a80c4f17acd5` in `ollama list`). **Inferred from the default, not attested**; the harness records only the model list. `cabin-assistant-poc1:latest` (`c88a5b63989f`) is also installed and was not used. |
| Private artifact | `~/eval-c1a-r1ctx-v2-baseline-20260921.jsonl` on the M920q, mode 600, 74,490 bytes, SHA-256 `b22590d53ecf65767e89114510e15353b8537508ab969c498ddf1a0886afccc2`. **Not copied into the repo.** |
| Grading sidecar | `~/eval-c1a-r1ctx-v2-baseline-20260921-grades-r2.json`, rubric `ask-rubric-r2`, initialized by `scripts/grade_ask.py init` |
| Planned / executed / reviewed | 72 / 72 / **0** |

**Caveat on the harness's own runtime field:** it recorded `checkout_sha` `e708946…`, which is the operator checkout `~/FaceoftheCabin` (on a stale feature branch), **not** the deployed commit. Use the image tag and id above for what was deployed.

## Aggregate facts

- Transport: 72/72 HTTP 200. Unauthenticated denial check: HTTP 401, passed.
- `answeredByModel`: 69 true, 3 false. The three false are **Q01 repeats 1–3**, each with no sources: the no-context refusal path, not evidence Ollama was unreachable.
- Sources returned: 29 distinct entity refs, all from the r1 word-overlap path (most often `home-zigbee-coordinator-mr5u`, `cabin_event`, `zigbee2mqtt_bridge`, `telemetry_recovery_pipeline`, `z2m-main_water_valve`). **Zero `doc:` sources**, as expected before r2. Only the Home-collector fixture nodes exist in the database (see the [C1a review](2026-09-07-c1a-review.md) and [c1a-iteration-2.md](../c1a-iteration-2.md)).
- Latency (`elapsed_seconds`, 72 trials): median 6.44 s, p95 36.75 s, min 0.04 s, max 54.17 s.
- A count-only scan of answer text for JWT-like strings, long opaque strings, `Bearer` values and `vault_` mentions found none. This is not a privacy review; grading still must check credential, safety and citation behavior.

## What this is for, and what happens next

1. **Grade all 72 trials** under [grading r2](../grading-r2.md) (Nate, or a judge calibrated on Nate's examples), keeping the sidecar private. Until then this baseline supports no comparison.
2. **After C1a iteration 2 is reviewed, merged and deployed**, run the identical command with a new output path against the r2 deployment and grade it the same way. `grade_ask.py compare` requires the same suite, question hash, role, endpoint and repeats, which this run was created to satisfy.
3. **Not comparable:** the 2026-09-07 run and the later claude-code rounds used the r1 question file, so `compare` rejects them against this one by design. The r1 results stay valid on their own terms.
4. If another backend change deploys before r2, the r1 context path is unchanged by it, but record the new image id when comparing.
