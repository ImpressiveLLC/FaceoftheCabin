# D — Ask run delta analysis

Copy this template for each comparison. Source schema: `scripts/ask_eval.py`
(`run`, `denial_check`, `trial`, `completed`) plus [grading r2](grading-r2.md).

## Identity and comparability

| Field | Before | After |
|---|---|---|
| Run ID / timestamp / private artifact SHA-256 | TODO | TODO |
| Question suite / question_sha256 | TODO | TODO |
| Rubric version / reviewer or calibrated judge version | TODO | TODO |
| Harness SHA / grading sidecar SHA | TODO | TODO |
| Endpoint / authenticated role / repeats | TODO | TODO |
| Source checkout / deployed image / build evidence | TODO | TODO |
| Model name + digest / request context settings | TODO | TODO |
| Corpus/fixture version + digest / client probe flag | TODO | TODO |
| Planned / executed / reviewed | TODO | TODO |

Comparability: **eligible / not assessable**. Explain changed or missing fields.
Compare the same question/repeat population under the same rubric. If legacy
grades cover questions rather than repeats, report that unit explicitly and do
not subtract it from a trial rate. Preserve old sidecars when regrading. A client
probe, unit test, or later runtime snapshot is not a deployed ratification run.

## Aggregate movement

| Measure | Before | After | Delta |
|---|---|---|---|
| Trial PASS / planned (72 in frozen r1) | TODO | TODO | percentage points or not assessable |
| Execution coverage / grading coverage | TODO | TODO | TODO |
| Stable questions (all repeats PASS) / 24 | TODO | TODO | TODO |
| MODEL / NO_CONTEXT / FACTS_FALLBACK | TODO | TODO | TODO |
| Transport / contract failures | TODO | TODO | TODO |
| Evidence-backed safety / governance violations | TODO | TODO | TODO |
| Median / p95 latency | TODO | TODO | TODO |

Do not treat HTTP 200 as accuracy or `answeredByModel=false` as an outage. Report
all planned cases; do not replace missing trials with passes or drop advisories.

## Per-question changes

| Question | Before PASS repeats / status | After PASS repeats / status | Transition | Evidence / diagnosis |
|---|---|---|---|---|
| Qxx | 0/3, FAIL | 3/3, PASS | fail→pass | supporting assertion and source |
| Qyy | 3/3, PASS | 1/3, FAIL | pass→fail | exact failed repeat and claim |
| Qzz | status | ADVISORY | new advisory | explicit scope reason; retained in denominator |

Include unchanged questions in the complete table or linked evidence. List
new/removed questions separately. Distinguish: source unavailable, routing miss,
supplied context ignored, reasoning error, incomplete answer, unsupported citation,
contract error, and actual unsafe/governance claim. Unknown causes remain unknown.

**Summary judgment: regression / improvement / no change** (or **not assessable**
if the comparison is ineligible). Explain gains and losses even at zero net
movement. Any previously passing case lost or observed violation takes precedence
over a positive aggregate for the regression judgment. Identify uncertainties,
proposed next atomic change, and the remaining Cowork decision; this line grants
no deployment, C1b, physical-action, or completion authorization.
