# Eval round history — what exists on the M920q, recorded in the repo

Recorded 2026-09-20 by reading `~/eval-results/` on the M920q (read-only). Until now these
results existed only on that host. Only grade-level facts are recorded here; the raw answer
JSONL files (which can contain sensitive household context) were **not** copied. This page
grants no ratification, C1b authorization or completion claim.

## Rounds

| Agent id | Round | Date | Endpoint | Scope | Pass / total (grader's own tally) | Notes |
|---|---|---|---|---|---|---|
| (operator) | c1a | 2026-09-07 | :8090 | Q01–Q25 without Q20, 3 repeats | 2 / 24 (Q10, Q13) — authoritative, see [c1a review](2026-09-07-c1a-review.md) | Already recorded in the repo. |
| claude-code | r2 (pre-fix) | 2026-09-08 | :8090 | 24 questions | 2 / 24 (Q03, Q10) | The raw run was later overwritten in place by the post-fix run (the pipeline's auto-increment reused label `r2`), so this grade cannot be re-checked against its answers. |
| claude-code | r2 post-fix | 2026-09-08 | :8090 | 24 questions | not tallied; Q03/Q10 returned a generic "I don't know" | Re-run after PR #45. Q18 still listed camera entities for a no-camera install; the file's recommendation was not to tick PR #45's Q18 box. |
| claude-code | r3 | 2026-09-08 | :8090 | 24 questions | 3 / 24 (Q04, Q08, Q18) | Graded by a Claude Code CLI session reading transcripts, not through the interactive prompt (r2's grader note says so). Refusal-type fails: Q03, Q10, Q24, Q25. |
| cabin-assistant-poc1 | r1 | 2026-09-16 | :8092 | not recorded | ungraded (no grades file) | Raw output only. |
| cabin-assistant-poc1 | r2 | 2026-09-16 | :8092 | 25 (Q01–Q25 without Q20, plus CC-01) | 9 / 25 (CC-01, Q04, Q07, Q08, Q13, Q16, Q17, Q18, Q22) | Consistency flags on Q13, Q15, Q17 (one of three repeats disagreed). Grader not recorded in the file. Run against `cabin-backend-eval-poc1` on :8092 (up 4 days at inspection). |

## How to read these numbers

- **The tools' "C1b gate cleared: true" (r3, poc1 r2) is not a decision.** It is `pass_count > 2`. The [C1a review](2026-09-07-c1a-review.md) documents that this gate can announce clearance without checking safety or comparing like with like; C1b requires Cowork's recorded decision and no required safety/governance regression.
- **Units and populations differ.** Question-level tallies over 24 or 25 questions are not the 72-trial rate the r1 method reports, and the poc1 population includes CC-01. Do not subtract them from each other.
- **No run recorded the model digest or deployed image** in `run-meta.json`, so none of these is an attested paired comparison.
- **The poc1 result is not evidence of generalization.** [POC1's README](../../training/README.md) says the tag was trained on already-graded PASS examples drawn from these same evaluation rounds (5 in the 2026-09-14 run). Whether the training examples overlap the questions it was then evaluated on was **not verified here**; until it is, and a held-out set is used, treat the 9 / 25 as unexplained.
- **All of these ran through the r1 context path** (deployed `context-fixtures-r1.json`, 34 of its fixture nodes absent from the database), so none reflects [C1a iteration 2](../c1a-iteration-2.md).

## Where the files are

`~/eval-results/<agent-id>/<date>-<round>/` on the M920q, each with `run-meta.json`, the raw JSONL and (where graded) `eval-<agent-id>-<round>-grades.json`. Two operator files, `docs/ai-assistant/rag/grading-runbook.md` and `docs/ai-assistant/rag/scripts/run_iteration.py`, were at inspection time untracked on the M920q rather than in git; they are not linked here because they do not exist on `main`.
