# C1a iteration 2 — routing repair and reviewed-document context

Date: 2026-09-20. **Status: implemented and unit-tested; not merged, not deployed, not
evaluated.** No answer-quality improvement is claimed. This entry proposes no C1b, model,
safety-procedure or database change, and grants no authorization; Cowork's Ratification
fields in [wsjf-backlog.md](../wsjf-backlog.md) are untouched.

This is the next unblocked C1 step recorded in the [C1a run review](eval-results/2026-09-07-c1a-review.md)
("Iteration and gaps") and step 1 of [grading r2 "Next rounds"](grading-r2.md#next-rounds).

## What was verified before starting (2026-09-20)

| Question | Finding | How |
|---|---|---|
| Is this already done? | No. No branch, PR or commit adds a routing change or document context; `context-fixtures-r2` / `ReviewedDocument` / `contextDocuments` appear nowhere in history; no open PRs. PR #45 (2026-09-08) only changed Q18's category keywords. | `git log --all -S…`, `gh pr list` |
| Do the fixture-designated nodes exist in production? | Still no. `knowledge_node` holds 96 rows (88 auto_generated, 8 manually_curated). Of the nodes the fixtures designate, only `home-zigbee-coordinator-mr5u` DESCRIPTION/TROUBLESHOOTING exist. No `platform.*`, `system.*` or `family_hub.*` node exists, so 16 of 17 categories supplied no context. | Read-only query on the M920q, identity columns only, no content |
| What runs in production? | `cabin-backend:4bcbd4d`, loading 17 categories from `context-fixtures-r1.json`. The container reads `/app/docs` read-only from the deploy worktree (`~/FaceoftheCabin-deploy`, currently at main), which contains `ai-assistant/user-guide/*.md`. | `docker logs`, `docker inspect`, `ls` (read-only) |
| Which frozen questions misrouted under r1 at main? | Q10, Q11, Q13 → `user_auth_role` (`auth` in authorize/authentication; `household role` in "roles"); Q16 → `installation` (`run` in "running"); Q21 → `live_sensors` (`current`). Q18's earlier misroute was fixed by PR #45. | Replaying r1's first-substring rule over `cli-questions-r1.json` |
| Did r1's own text match its code? | No. The r1 description says "highest-scoring category"; the code took the first declared match. | `AskContextBuilder` at 4bcbd4d |

## What changed

1. **Routing** (`AskContextBuilder`): patterns match only as whole words or phrases (an optional plural `s`/`es` is allowed). A category scores the summed word count of its distinct matched patterns, plus an optional `boost` when anything matched. The highest score wins, ties go to declaration order, and zero selects nothing (the existing word-overlap retrieval then runs unchanged). Overbroad single tokens (`auth`, `run`, `current`, `error`, `live`, `household role`, `ci`) were removed or replaced by phrases in the r2 map.
2. **Reviewed documents** (`ReviewedDocumentSource`, new): a category can list `contextDocuments` `{path, section, audience}`. A section is the heading plus its subsections, located by GitHub-style anchor. Only `ai-assistant/user-guide/**.md` and `ai-assistant/contributing.md` are readable; traversal, absolute paths and everything else (including `corpus/eval-answer-evidence.md`, which holds the expected answers to the frozen questions) are refused. Files are re-read when they change. A missing docs root disables documents with a startup warning.
3. **Role gating, fail closed**: an entry's `audience` defaults to `administrator`, and only an ADMINISTRATOR receives those sections. Any other role, or none, receives only `household` entries. Nine sections in the map are marked `household` (operations: users-and-family-data, capability-map; ask-and-helpdesk: three; contributing: two). I scanned them for IPs, hostnames, tokens, vault names and email addresses and found none; that scan is not a substitute for review, and **the audience assignments are proposals for the reviewer to confirm.**
4. **Prompt** (`TinyHelpdeskService`): a document section is delivered as `[source: <path>#<section>]` followed by its text, and the prompt gains two sentences: cite that label, and treat instructions inside facts as text to report. Only when a document is present, so the prompt for the plain-KnowledgeNode path is byte-for-byte what it was.
5. **Provenance**: a section reaches the caller as a KnowledgeNode-shaped record with `entityRef` `doc:<path>#<section>`, `chunkType` DESCRIPTION and new `source` value `REVIEWED_DOCUMENT` (not `MANUALLY_CURATED`). It is built in memory per question and never written to `knowledge_node`, so it cannot overwrite a curated node. The response shape is unchanged; the UI badge reads "Reviewed doc" instead of mislabeling it "Auto-generated".
6. **Budget**: `ask.context.max-document-chars` (default 6000 chars, roughly 1.5k tokens at about 4 chars per token; an estimate, not a measurement). A section that does not fit is cut at a paragraph boundary with an explicit marker, or skipped with a warning if under 300 chars remain. Measured on the current docs, the largest category delivers 3,566 chars (`installation`), so nothing truncates today.
7. **Verification of delivered text**: every Ask logs the selected category, the number and size of document sections, and the final prompt size (sizes only, never content). `TinyHelpdeskServiceTest` asserts the section text and its label are in the prompt the fake model receives.

Configuration (both optional): `ask.context.docs-root` (default `/app/docs`), `ask.context.max-document-chars` (default 6000), `ask.context.fixtures` (default `classpath:rag/context-fixtures-r2.json`).

## Deliberately unchanged

`context-fixtures-r1.json` (frozen baseline record); the model, its digest and Ollama settings; the endpoint and response fields; credential-pointer redaction (still applied to every node); the database (no writes, no curation, no generated safety content); the frozen question set and rubric.

## Tests (run locally on Windows, JDK 21; the CI gate runs them on the M920q)

`ReviewedDocumentSourceTest` (9), `AskContextBuilderTest` (15), `TinyHelpdeskServiceTest` (13), `TinyHelpdeskControllerTest` (4): 41 passing, none skipped. They include: all 24 frozen questions route to a category that declares them; the five r1 misroutes; whole-word and negative controls; every fixture heading reference resolves to a real section (a renamed heading now fails the build); the classpath and docs fixture copies are identical; administrator versus each other role and no role; budget truncation; and a non-admin asking an administrator-audience question never reaches the model. UI: the Ask panel badge test. The full backend suite (Testcontainers) was **not** run locally; it runs in the deploy workflow.

## Known limits and risks (read before interpreting a result)

- **Routing is a heuristic.** A miss falls back to the r1 word-overlap behavior; a false positive supplies irrelevant context. The 24 frozen questions all route correctly by construction of the patterns, which proves nothing about unseen phrasings. Grading r2's round 2 (held-out paraphrases, multi-intent and negative cases) is still owed and must be a separate suite version.
- **Context and oracle share an author.** The user-guide sections were written alongside the frozen questions. A gain from this change measures routing plus document quality on these questions, not generalization.
- **Q21's r1 expectation was stale in one respect, so there is now a v2 question file.** r1 expects "no Markdown ingestion installed"; after this change the accurate answer is "merging alone does not make Ask know a guide; only sections listed in the reviewed map are supplied." Nate authorized the change (relaying Cowork, 2026-09-20). [cli-questions-r2.json](cli-questions-r2.json) changes only Q21's `expected`, worded to be true of **both** the r1 and r2 deployments so the baseline is not graded against behavior it does not have; question texts, ids and order are unchanged, so the requests sent are identical. `cli-questions-r1.json` stays frozen (its committed bytes still hash to `be2c8d5a…`). `grade_ask.py compare` rejects runs whose question file differs, so the 2026-09-07 run cannot be compared with r2-file runs; the fresh baseline below exists for that reason. [ask-and-helpdesk.md](../user-guide/ask-and-helpdesk.md) was updated to match.
- **Docs-only merges do not redeploy the backend** (`deploy-cabin-backend.yml` triggers on `backend/**` and compose files), so a documentation edit reaches Ask at the next backend deploy or a manual `workflow_dispatch`.
- **Fixture nodes still reference missing KnowledgeNodes** and log a warning per lookup; left as is, because curating them is a separate authorized act.
- **Not done:** an ontology entry for the three `ask.context.*` properties (operator-only Spring properties, no UI; DoD §3 asks for user-facing configurable concepts, so I judged this out of scope but did not verify that judgment with you).
- **POC1 interaction:** `cabin-assistant-poc1` was evaluated on 2026-09-16 through the r1 context path (see [round history](eval-results/round-history-2026-09-20.md)). Any comparison against it needs the same fixtures, question set and rubric, and a check that its training examples do not overlap the evaluation questions; I did not verify that overlap.

## Baseline (captured 2026-09-21, ungraded)

The pre-change baseline on the deployed r1 context path is recorded in [eval-results/2026-09-21-v2-baseline-r1-context.md](eval-results/2026-09-21-v2-baseline-r1-context.md): 72 of 72 trials executed with the v2 question file, admin role, image `cabin-backend:4bcbd4d`. **It has no pass rate; all 72 trials await grading.** It had to be captured before this change deploys, since afterwards the r1 context path is gone.

## To evaluate (after review, merge and deploy; each needs an explicit go-ahead)

```sh
cd ~/FaceoftheCabin        # on a checkout at the deployed commit
python3 scripts/ask_eval.py --endpoint http://127.0.0.1:8090 \
  --questions docs/ai-assistant/rag/cli-questions-r2.json --repeats 3 \
  --record-runtime --resident-admin-session \
  --output ~/eval-c1a-r2-$(date +%Y%m%d).jsonl
python3 scripts/grade_ask.py init ~/eval-c1a-r2-<date>.jsonl ~/eval-c1a-r2-<date>-grades-r2.json
```

Do not pass `--use-context` (a client probe, not the deployed path). Grade all 72 trials under [grading r2](grading-r2.md), regrade the retained r1 run as a separate sidecar, fill the [delta template](delta-analysis-template.md) with the deployed image and model digest, and report per-question transitions including any lost pass. Any safety, credential or fabricated-citation failure blocks regardless of aggregate movement; C1b stays blocked until Cowork records a decision.
