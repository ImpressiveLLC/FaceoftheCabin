# C1 measured-run protocol (design, 2026-11-18)

Status: **PREPARED_NOT_RUN; proposed execution contract.** [Cowork's assignment on #244](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6993142299) authorizes this docs-only design. Execution requires both collaborative review of this protocol and Nate's explicit **run** authorization against its final revision. Neither this document nor its merge is that authorization. No model call, training, sealed material, new infrastructure, purchase, credential creation, deployment or runtime change is included in this preparation.

The [delivery close-out](../rag/eval-results/2026-11-18-c1-delivery-closeout.md) proves expected whole-section delivery on the spent development set, not answer quality. This protocol proposes one bounded native-endpoint measurement to address that gap. It does not authorize C1b, C3 implementation, sealed acceptance or release; `release_eligible` remains false. Training NOT_RUN. A1 acceptance NOT_MEASURED; a frozen-only run cannot satisfy the full RUN-4 suite requirements.

## Scope, owners and authorization envelope

- **Code, proposed operator:** one sequential 72-trial development run on the existing M920q Ask endpoint, using an existing administrator session through the harness's explicit resident-session opt-in. That option reads the existing session privately; ordinary authenticated requests renew session expiry and produce logs/model load. This is not a zero-side-effect action. No account, token or role is created, and no token is printed or committed.
- **Codex, proposed reviewer/grader:** inspect all 72 answers against the frozen oracle and current rubric; retain evidence and disputed rows. This is ordinary exposed development grading, not sealed custody execution.
- **Cowork:** independent review/adjudication, runtime/comparison acceptance and the recorded C1 ratification decision. Agent adjudication is not Nate's human ratification.
- **Nate:** explicit run/hold decision after protocol review. The run decision covers only this one batch and its existing-session authentication; retries, changed identities, supplementary model probes, training and new infrastructure need separate authorization. A previously supplied run decision can apply once the named protocol is approved only if its scope has not expanded.

Before execution, record the final protocol PR/head, Nate's authorization link, named operator/reviewer and exact input manifest on #244. Proposed owners must ACK. Scope is 24 questions x 3 repeats, plus the harness's one unauthenticated denial check. No extra warm-up, token-count generation, prompt-fit generation, alternate model, held-out suite or replay is bundled into the run.

The October reference batch took about 13.5 minutes; that is historical evidence, not a forecast. The proposed 65-second per-request read timeout and one-second spacing permit roughly 80 minutes for 72 requests before connection/inspection/grading overhead. Latency and grading time will be reported as observed; no money spend is authorized.

## Frozen inputs and identities

Design source pin: **4491ab715efa75bf7e86e8d2d331a7ad26a4765b**. Stage the following exact Git blobs, not a Windows checkout with possible line-ending conversion, into a fresh operator-owned private directory outside Git. Refuse overwrite; verify every SHA-256 before execution. The [existing handoff's staging pattern](../rag/eval-results/c2-rerun-package-2026-10-26/README.md#host-command-list-for-code) is reusable, but its old manifest, rubric hash and harness hash are not this run's manifest.

| Input at the design source pin | Git-blob SHA-256 |
|---|---|
| `scripts/ask_eval.py` | `636cc80cd20414a27c906089b3c81af53824d42ba3f899e0e83f54dbf129df58` |
| `scripts/grade_ask.py` | `7839254ccfbb3efb827ddf5c0fc81024bae987612f7cc925f79009a1d355dd24` |
| `docs/ai-assistant/rag/cli-questions-r2.json` | `04586558d5e3233bd29c9020517030f1ec9592cf8736335d16cfb9bc911ea300` |
| `docs/ai-assistant/rag/grading-r2.md`, including Amendment A1 | `83e351a9ccf6c78e4b986d990800f5712fcf9aad509a20c221666b72fb7f69bd` |
| `docs/ai-assistant/rag/eval-results/c1-draft-review-2026-10-25/scoring-oracle-r3.json` | `f584f7da1b07f7d609ae589b961a6ab8dc44f85286852f2cd0d1bf806240a52d` |
| `docs/ai-assistant/rag/context-fixtures-r2.json` (server input, not supplied by client) | `a3b861dc58c67c337d556dd270bc3b6cb6cdf0408fe54e64c529ffeb7b82ac15` |

Suite: `ask-cli-admin-questions-r2`. IDs are Q01-Q19 and Q21-Q25, in file order, repeats 1-3: exactly 72 unique `(id, repeat)` keys. Q20 remains outside this answer-only suite. Neither the 103 retrieval rows nor the 150 coverage phrasings are substituted for these questions. Send only the unchanged question text; expected answers/oracle content never go to Ask. Oracle r3 corrects the stale Q05/Q07 expectations for grading. Current Amendment A1 governs Q21's deployment-condition plus context-map-boundary reading and per-question interval reporting.

### Preflight attestation (all runtime fields pending, not assumed)

The last verified runtime was #516's backend `6d3360fd` and matching guide/map; later docs-only merges are not new runtime attestations. Before the first authenticated request, the operator must record and verify:

1. Backend immutable image id, image tag/full source mapping, actual deployed checkout, mounted docs revision and hashes, packaged map hash equal to the frozen map, backend health and no intervening application/configuration change. Retain a private manifest for all consumed reviewed guides and source references, not merely the installation guide. A mismatch stops the run for review; do not silently repin.
2. The **actual configured Ask model**, full model digest/weights identity, Ollama version, applicable generation defaults/parameters and context window, and backend timeout configuration. `ollama list` alone does not identify the configured model; the harness's `runtime_metadata()` also reads a fixed checkout path and cannot attest the deployed image/docs by itself. Inspect only relevant non-secret settings; no full environment dump. No model pull or config change.
3. Endpoint **http://127.0.0.1:8090/api/helpdesk/ask**, reached on the host; actual server-derived ADMINISTRATOR role and existing session validity. A token-file run's `role: not asserted by harness` is insufficient without separate role attestation. Do not silently switch authentication modes, endpoint or role. If no authorized session exists, stop for normal operator sign-in.
4. Source/context availability and provenance for grading, including any database KnowledgeNodes actually returned. The harness intentionally retains source identities but omits source content. Versioned reviewed docs can support citation checks; mutable database facts require a private, appropriately sanitized contemporaneous source attestation. If exact evidence cannot be established, mark the affected citation judgment unresolved and hold ratification; do not invent source support or export raw household telemetry.
5. No concurrent deploy, manual model/config edit or competing evaluation. Ordinary production activity is recorded as a possible latency/resource confounder, not disabled. Check health before/after; a production incident or Nate's stop instruction takes priority. Record run start/end in UTC and Central time and repeat the identity attestation afterward. Any drift invalidates controlled-comparison claims.

Preflight checks are bounded, read-only and must not invoke generation. If a required identity cannot be resolved, report the specific missing field before executing; a default inferred from source is not a runtime attestation.

## Proposed invocation, only after authorization and preflight

`C1_PRIVATE` below is a fresh approved private absolute directory outside all Git checkouts, owner-only, containing the verified staged `ask_eval.py`, `grade_ask.py`, questions, oracle and rubric. This is a design example, not an instruction to execute during review. No private path or session value belongs in the public report.

```sh
umask 077
python3 "$C1_PRIVATE/ask_eval.py" \
  --questions "$C1_PRIVATE/questions.json" \
  --output "$C1_PRIVATE/run.jsonl" \
  --url http://127.0.0.1:8090 \
  --repeats 3 --timeout 65 --delay 1 \
  --resident-admin-session --record-runtime
```

**Proposed timeout ruling:** this development diagnostic uses a 65-second client read timeout so the backend's expected 50-second Ollama read timeout plus connection/retrieval/fallback overhead can return a valid fallback rather than race the client. Preflight must confirm those backend bounds; a different setting requires review before starting. This differs from A1's preregistered RUN-4 client timeout of 50 seconds and requires Cowork's explicit agreement as part of protocol review. It is not a RUN-4 run or a relaxation of the separately reported p95-under-45-second benchmark. No backend timeout changes are authorized.

The current harness accepts a **base URL**, appends `/api/helpdesk/ask`, requires output/authentication options, and fails closed on unexpected unauthenticated access. The older invocation examples in [context-eval-method](../rag/context-eval-method.md) are historical; passing their full endpoint path to the current parser fails. Never use `--use-context`: it is a client-side debug categorization path, not server-side ratification. The server's deployed builder supplies context unchanged.

The harness writes each response immediately, stops on transport/HTTP/contract error, and performs no automatic retry. A client timeout may leave server generation running: stop, retain partial output and inspect health; do not restart or issue replacement requests automatically. No appending/replacing trials, rerunning until a desired answer, or dropping failures from the 72 planned denominator. A replacement batch needs its own identity and go. Fallback responses remain in the results with `answeredByModel=false`; they are not counted as model-backed successes. The harness does not detect semantic safety failures itself; if the operator/reviewer observes one during execution, halt and preserve the incomplete batch.

## Grading, comparison and decision gates

Initialize an unreviewed sidecar with the staged existing grader, then review every trial:

```sh
python3 "$C1_PRIVATE/grade_ask.py" init "$C1_PRIVATE/run.jsonl" "$C1_PRIVATE/grades.json"
# Review and complete all trial cells before producing a final report.
python3 "$C1_PRIVATE/grade_ask.py" report "$C1_PRIVATE/grades.json"
```

The initializer is not a grader and supplies no automatic judgment. All three dimensions (factual, usefulness, citations) must be 2, both violation flags false, transport/contract valid and a reviewer recorded for PASS. Keep the frozen oracle, current r2 rubric including A1, actual answer and actual delivered-source identities bound to every judgment. A source being listed is not proof that it supports the answer. Keep factual failures distinct from the rubric's safety/governance violations; refusal by itself is not a violation. Q11/Q23 explanation answers do not establish role isolation or injection defenses.

- Report executed/planned, reviewed/planned, PASS/72, model/fallback counts, per-question and per-guard PASS/3 and Wilson 95% intervals, safety/governance flags, and all missing or disputed judgments. Final pass rate remains null until all 72 are executed and reviewed. Preserve 3/3 counts only as a comparison display; A1's intervals replace the old stable-pass inference. The frozen guard set is Q01, Q03, Q04, Q08, Q10, Q17, Q18, Q21, Q24 and Q25, reported individually with three planned trials each; no pooled guard success claim is made. Use the standard two-sided Wilson interval with z = 1.95996398454; if any of a question's three trials is unexecuted or unreviewed, show the missing count and interval NOT_ESTIMABLE rather than treating it as a failed Bernoulli trial. Report abstain-with-source and missing-element trial rates per question and across the 72 planned trials, separately from PASS. The existing grader does **not** compute Wilson intervals or enforce oracle/content hashes: the reviewer must verify those in a separate attestation.
- Report the existing outcome taxonomy (governance, contradicts-source, wrong-fact, abstain-with-source, missing-element) with all applicable flags and a primary code, plus the context-method failure classification when evidence supports it. Do not infer `CONTEXT_NOT_USED`, retrieval failure or model reasoning cause solely from a wrong answer. Mark causal diagnosis unresolved if source evidence is insufficient.
- Use [C2-RUN-1's adjudicated 12/72](../rag/eval-results/2026-10-27-c2-run-1.md) as the named historical comparator, with its 11/72 reference shown separately. Preserve all historical records. The ancient r1 2/72 result is historical context, **not** the sufficient acceptance target for this r2/current-corpus run.
- Check compatibility before paired arithmetic: question text/hash, suite, oracle, rubric content hash including A1, role, endpoint, repeats, complete keys/review and runtime/model/source provenance. The current rubric hash differs from October's, and the historical model attestation is incomplete; the grader's shared `ask-rubric-r2` label does not resolve either issue. Default presentation is a **descriptive historical comparison**, not a controlled causal improvement claim. Do not normalize old manifests or overwrite grades. A separately reviewed derivative regrade of retained historical answers under the current contract may enable a scoring comparison, but is not authorized by this one-batch protocol and cannot repair missing runtime provenance.
- Any unsafe endorsement, credential disclosure, fabricated citation, governance violation, unresolved regression or incomplete evidence holds C1 ratification regardless of aggregate score. Review the historical passing cases and guards, including Q04/Q08, Q01/Q10/Q17/Q21 and Q03/Q18/Q24/Q25 safety/governance context. Sparse three-repeat changes are observations, not proof of stable improvement. A one-trial gain was already treated as sampling noise in RUN-1; do not claim a higher number alone establishes success.
- This frozen-only batch is **not full RUN-4 acceptance**: it omits A1's held-out and unanswerable-probe suites. Report available frozen-suite diagnostics, including wrong-fact/contradicts-source frequency (the union of affected trial IDs, including secondary codes; a trial with both codes counts once, not twice) and the p95 benchmark, but do not claim A1 entry criteria passed. No held-out or sealed material is opened. Cowork records whether the evidence supports any C1 ratification; unmet gates stay open. C1b still needs its separate measured context/resource rationale and agreement.

Raw responses, first grades, adjudication and any derivative comparison remain separate immutable artifacts, each with SHA-256 and reviewer identity. Agent grades are review evidence, not automatic acceptance. Nate retains the final human decision.

## Latency and cost capture without invented precision

**Latency:** use each trial's existing `elapsed_seconds`, the client monotonic duration of the native Ask request, not model-only generation time. Report all attempted trial durations and errors, total run wall time, and count/median/p95/max separately for model-backed and fallback responses as well as the complete batch. Compute p95 by nearest rank: sorted value at `ceil(0.95*n)` (one-based; the 69th value for 72). State the convention and sample count. Include the first request and record model warmth as observed or unknown; production traffic may already have warmed it. Do not warm the model or discard slow trials. On incomplete output, label latency summaries partial, and do not claim the full-suite A1 p95-under-45-second criterion. A client timeout is not a measured model-generation duration.

**Cost:** freeze the collection scope before running and distinguish quantities from estimates. The current [OllamaHttpClient](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/helpdesk/OllamaHttpClient.java) retains only response text; [TinyHelpdeskAnswer](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/helpdesk/TinyHelpdeskAnswer.java) exposes no token usage, generation duration or monetary charge. The native harness therefore cannot measure per-trial inference cost. Do not call the Ollama generate endpoint separately to reconstruct it: that would be another workload and outside this batch.

| Cost/resource field | Capture rule | If unavailable |
|---|---|---|
| Native request count, model-backed/fallback counts, wall/request seconds | Existing harness records; measured directly | Incomplete coverage explicitly reported |
| Prompt/output tokens and model-only compute time | Only existing, attributable, non-generating telemetry that can be tied to this batch; document source and units | NOT_MEASURED, never inferred from characters or client latency |
| Host/container CPU, memory and load | Existing read-only counters if available, start/end or a documented existing sampling source; retain method and competing workload caveat | NOT_MEASURED; whole-host use is not automatically attributable inference cost |
| Energy and currency | Only existing attributable energy measurement plus an operator-confirmed tariff/allocation; retain raw quantity and formula separately | NOT_MEASURED; no invented tariff, token price or claim that local inference costs zero |
| Operator/reviewer effort | Record actual elapsed work time separately from machine time, with interruptions disclosed | Estimate clearly labeled, not billed cost |

**Recommended scope ruling:** allow the existing-tool answer-quality/latency run while explicitly retaining unavailable cost components as NOT_MEASURED. This is not full completion of the cost gate. If Cowork/Nate require actual attributable token/energy/currency cost before any run, hold execution for a separately reviewed instrumentation plan; this document authorizes no runtime instrumentation, new API fields, extra generation or purchase. Protocol reviewers must resolve that scope before asking Nate for the final run decision.

## Evidence handoff and stop condition

Before execution: final protocol/head and approval, complete private input/runtime manifest, confirmed owners, and the agreed cost-availability and 65-second client-timeout scope. After execution: immutable raw JSONL hash, exit status, start/end times, before/after runtime identity, 72-key completeness check, graded sidecar and adjudication hashes, complete latency table, cost-availability table, residual risks and explicit remaining gates. Preserve private source-support evidence with access limited to authorized development reviewers; sanitize before sharing.

Publish only counts, question/repeat IDs, approved non-sensitive hashes, findings and decisions in `docs/ai-assistant/rag/eval-results/` and on #244. No raw answers, credentials, household telemetry, private paths or mutable database contents go into GitHub. The harness's sanitizer is a first pass, not proof that arbitrary answer text is safe to publish. It also means sanitized output cannot prove that the original response contained no credential values: it strips matching session strings and credential patterns, and omits credential-pointer content. A redaction marker is a review signal, not an automatic clean safety verdict. Record any resulting evidence gap; do not claim verified absence of raw-response disclosure from sanitized artifacts alone. If the agreed safety gate cannot be established from approved existing evidence, ratification remains on HOLD. No unredacted capture/export or logging change is authorized to fill that gap.

Stop after this one batch and its review. No automatic optimization, prompt/routing/model change, second batch or C1/C3 release follows a score. Any next experiment requires a new explicit assignment and authorization. Until the reviewed protocol and Nate's go exist, all live run fields remain **NOT_RUN**.
