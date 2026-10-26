# C1 paired-run review package

**Final Codex review, 2026-10-26, under Nathan's direct instruction to finalize the review data:** before **0/72 PASS**, after **11/72 PASS (15.3%)**, with **61/72 after-run FAIL**. All 144 retained trials are scored; the denominator is unchanged. Q12/1 remains PASS with a contradictory-opening defect note. Q05/1 and Q07/1 are now FAIL because their memory-only platform-import explanation conflicts with executable source in both evaluated revisions. The source correction is applied symmetrically, with no frozen question removed or rewritten.

This is **Codex's finalized agent review**, not a claim that Nathan performed human review. It does not accept the pair, establish C1 completion/current production performance, call the pair improved, or authorize C1b. Both existing risk flags remain. The comparison tool's final label is **regression** under its risk gate, despite increased pass count.

The original draft (13/72 after) and [intermediate two-dispute recheck](review-2026-10-26.json) are retained as history. The final files below supersede their provisional totals; no original raw answer, original sidecar, draft score or Cowork record was overwritten.

## Read these files

| File | Review use |
|---|---|
| [final-review-20261026.json](final-review-20261026.json) | Final Codex counts, comparison, changed dimensions and authority/acceptance limits |
| [final-trial-scores.json](final-trial-scores.json) | All 144 final agent-review trial scores and sanitized reasons |
| [source-errata-20261026.json](source-errata-20261026.json) | Versioned source precedence and persistence correction applied to both runs |
| [summary.json](summary.json) | Draft counts, question-repeat results, comparison, hashes and explicit null authoritative pass rate |
| [trial-scores.json](trial-scores.json) | All 144 dimension scores, sanitized reasons, confidence, counter-cases, risk flags and raw-answer hashes |
| [spotchecks.json](spotchecks.json) | Forty proposed review cases: all draft passes, risks/borderlines and twelve seeded other failures |
| [review-2026-10-26.json](review-2026-10-26.json) | Separate eleven-trial recheck, Q12/1 defect note, and two code-versus-guide disputes with historical source links |

Raw answers, questions, household source records, exact context, credentials and session/runtime details are **not included**. The question definitions already exist in the [frozen r2 suite](https://github.com/ImpressiveLLC/FaceoftheCabin/blob/e0c8713/docs/ai-assistant/rag/cli-questions-r2.json). Use the [same r2 rubric](https://github.com/ImpressiveLLC/FaceoftheCabin/blob/e0c8713/docs/ai-assistant/rag/grading-r2.md) for both runs.

## Original draft result, preserved for history

| Measure | Before | After |
|---|---:|---:|
| Planned/executed | 72/72 | 72/72 |
| Agent-draft PASS | 0/72 | 13/72 |
| Agent-draft FAIL | 72 | 59 |
| Agent-draft trial pass fraction | 0% | 18.1% |
| Questions passing all three repeats | 0/24 | 2/24 |
| Proposed safety/governance flags | 0 | 2 |
| Human-ratified pass rate | Pending | Pending |

The existing comparison tool calls both the original draft and final reviewed pair `regression` because the after-run flags block acceptance despite increased pass count. The historical 3/24 question tally is a different run/method and is not this paired baseline.

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

Record any subsequent human changes in a new review artifact with reviewer, trial ID/repeat, changed dimensions and reason. Preserve original drafts and the final Codex review. Unreviewed/uncertain evidence must not become an automatic pass.

## Governance record

| Field | Value |
|---|---|
| Use case / backlog | UC-17; C1 paired grading, C2/C3 sequencing evidence |
| Requirement | #244 C1 draft-grade and spot-check request; frozen ask-rubric-r2 |
| User value | Reviewable draft evidence in Git while private folder permission is resolved |
| Validation | 144 unique run/question/repeat keys; dimension/count checks; draft flags; raw-answer exclusion; hash provenance; diff inspection |
| Release state | Final Codex agent review; original drafts preserved; no human ratification claimed, C1 acceptance, C1b authority or production change |
