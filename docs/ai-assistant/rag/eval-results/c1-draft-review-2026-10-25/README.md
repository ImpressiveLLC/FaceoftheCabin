# C1 paired-run draft review package

**Unratified agent draft.** Codex prepared 144 trial judgments under one frozen rubric. Nathan/Cowork have not ratified the scores. This package is for reviewing the proposed scoring and selecting spot-checks; it does not establish C1 completion, a validated accuracy rate, model improvement, or C1b authority.

## Read these files

| File | Review use |
|---|---|
| [summary.json](summary.json) | Draft counts, question-repeat results, comparison, hashes and explicit null authoritative pass rate |
| [trial-scores.json](trial-scores.json) | All 144 dimension scores, sanitized reasons, confidence, counter-cases, risk flags and raw-answer hashes |
| [spotchecks.json](spotchecks.json) | Forty proposed review cases: all draft passes, risks/borderlines and twelve seeded other failures |

Raw answers, questions, household source records, exact context, credentials and session/runtime details are **not included**. The question definitions already exist in the [frozen r2 suite](https://github.com/ImpressiveLLC/FaceoftheCabin/blob/e0c8713/docs/ai-assistant/rag/cli-questions-r2.json). Use the [same r2 rubric](https://github.com/ImpressiveLLC/FaceoftheCabin/blob/e0c8713/docs/ai-assistant/rag/grading-r2.md) for both runs.

## Draft result, pending ratification

| Measure | Before | After |
|---|---:|---:|
| Planned/executed | 72/72 | 72/72 |
| Agent-draft PASS | 0/72 | 13/72 |
| Agent-draft FAIL | 72 | 59 |
| Agent-draft trial pass fraction | 0% | 18.1% |
| Questions passing all three repeats | 0/24 | 2/24 |
| Proposed safety/governance flags | 0 | 2 |
| Human-ratified pass rate | Pending | Pending |

The existing comparison tool calls the **draft** pair `regression` because the proposed after-run flags block acceptance despite increased pass count. Nathan/Cowork must confirm or amend those classifications. The historical 3/24 question tally is a different run/method and is not this paired baseline.

Priority checks: after Q02/1 (copy-before-refresh recovery order), after Q18/3 (required installation host/access inputs), every proposed PASS, and borderline completeness/citation scores. Before Q08's creation/activation/valve distinction also deserves direct review. A concise answer may be sufficient; the rubric is not a length test. A returned source alone does not prove the answer follows it.

## Access to the actual answers

The complete local package is named `c1-private-grading-20261025`, under the operator's `Documents/Resume and work/work` directory. Its `START-HERE.md` explains the private sidecars and claim-review file. `claim-review.jsonl` contains the 144 actual answers and source metadata; keep it private.

Codex has verified local read access. Cowork requires its own explicit folder connection/permission; posting a filesystem path in chat does not grant that permission. In Claude Desktop, connect that exact folder to the task and approve the fresh access prompt. If the prompt expired, request it again. Keep Claude Desktop open for local-file access. A missing or expired prompt is not an approval and should not be bypassed by changing operating-system permissions.

This Git package supports discussion of scores and review plans while folder access is resolved. It is **insufficient to ratify answer correctness without inspecting the private answers and sources**. Do not infer raw-answer contents from hashes or sanitized reasons.

## Provenance and boundaries

- The raw before/after file hashes match the committed [baseline evidence](../2026-09-21-v2-baseline-r1-context.md) and [after-run evidence](../2026-09-22-postdeploy-r2.md). Originals and existing sidecars were preserved.
- Suite, rubric, question hash, administrator role, endpoint, repeats and trial keys match; the comparison reports no manifest incompatibility. Exact model digests and model-rendered context are not fully attested.
- Document claims were checked against revision `e0c8713`; historical before-run KnowledgeNode content is not a fully archived context snapshot. Citation uncertainty remains visible.
- The judge has not been calibrated against a ratifier-approved grading set. All confidence notes are subjective, not calibrated probabilities.
- Risk flags are proposed classifications of advice, not proof that an action was executed or data was lost.
- No human approvals, new model requests, training, ingestion, host mutation, deployment or corpus re-sequencing occurred during this packaging step. C2 host evidence remains Code's lane; Cowork proposes priorities and Nathan decides.

Record human changes in a new review/ratification artifact with reviewer, trial ID/repeat, changed dimensions and reason. Preserve these drafts. Unreviewed/uncertain evidence must not become an automatic pass.

## Governance record

| Field | Value |
|---|---|
| Use case / backlog | UC-17; C1 paired grading, C2/C3 sequencing evidence |
| Requirement | #244 C1 draft-grade and spot-check request; frozen ask-rubric-r2 |
| User value | Reviewable draft evidence in Git while private folder permission is resolved |
| Validation | 144 unique run/question/repeat keys; dimension/count checks; draft flags; raw-answer exclusion; hash provenance; diff inspection |
| Release state | Documentation/review data only; unratified, no production change |
