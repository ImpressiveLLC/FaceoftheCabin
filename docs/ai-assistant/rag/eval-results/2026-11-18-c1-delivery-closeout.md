# C1 delivery close-out evidence (2026-11-18)

Status: **development-set document delivery milestone complete; full C1 ratification remains open.** This is not an ITERATION 2 CLOSE-OUT. It reports deterministic document selection and whole-section delivery, not the quality of generated answers. Answer quality **NOT_MEASURED**, A1 **NOT_MEASURED**, training **NOT_RUN**, `release_eligible` false.

## Scope and reproduction

Cowork assigned this report on [#244, comment 6992192161](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6992192161). The measured source is merge **6d3360fd844c7aefbbc59489f9c6da5a4a457f77** (#516). A fresh offline run of the existing [AskDeliveryHarnessTest](../../../../cabin-orchestration-platform/backend/src/test/java/com/cabin/orchestrator/helpdesk/AskDeliveryHarnessTest.java) passed all **6 tests**, with zero failures, errors or skips. No test code, model call, grading, sealed material, live question, training or runtime change was needed for this report.

From that revision's repository root:

```sh
mvn -o -q -Dtest=AskDeliveryHarnessTest -f cabin-orchestration-platform/backend/pom.xml test
```

The generated evidence is `cabin-orchestration-platform/backend/target/c1-s1-delivery-report.json`; it includes the measured revision, input fingerprints and row-level results. Offline reproduction requires the existing Maven dependencies to be cached. The source revision, rather than this later report commit, is the measurement pin.

## Measured result

The frozen [baseline](2026-11-18-c1-s1-baseline.md) and [post-C2 checkpoint](2026-11-18-c1-post-c2-checkpoint.md) remain unchanged. Their values below are historical records; the final column is the fresh run at the full revision above.

| Measure | Frozen fd1f8676 | Post-C2 1a94b0be | Delivery close-out 6d3360fd |
|---|---:|---:|---:|
| Administrator rows | 98 | 98 | 98 |
| Expected section delivered whole | 88 | 95 | **98** |
| Expected category in selected set | 89 | 89 | 88 |
| First category agrees with frozen expectation | 84 | 84 | 84 |
| Administrator rows with no category | 2 | 0 | 0 |
| Administrator selected but not delivered | 1 | 1 | 0 |
| Household rows | 5 | 5 | 5 |
| Household expected section delivered whole | 4 | 5 | **5** |
| Audience leaks | 0 | 0 | 0 |
| Contexts over the 6000-character cap | 0 | 0 | 0 |
| Largest delivered context, characters | 5143 | 5143 | 5960 |
| Coverage-map phrasings with no category | 16/150 | 16/150 | 16/150 |

All 103 development rows now receive their expected section whole. Household selection agreement remains 4/5; household selected-but-not-delivered and no-category counts are both zero. The expected category labels are frozen: newly added categories can deliver the same expected section without matching the old label. **98/98 is not a classification-accuracy or answer-quality score.**

The three remaining checkpoint gaps closed in sequence:

| Development row | Reviewed implementation | Administrator delivery total afterward | Final delivered context |
|---|---|---:|---|
| `install_gap_2` | [#512](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/512), acceptance selection plus bounded guide condensation | 96/98 | Minimum inputs, clean-host limitations and acceptance record: 5053 characters after #516's additional condensation |
| `cov_M04` | [#514](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/514), qualified external dead-man selection | 97/98 | External dead-man monitoring: 1760 characters |
| `install_gap_1` | [#516](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/516), approved opening-paragraph condensation plus plan selection | 98/98 | Minimum inputs 1611 + clean-host limitations 1956 + complete plan 2393 = 5960 characters |

The plan has **40 characters of remaining capacity**; its regression test preserves that reserve. The acceptance bundle has 947 characters of capacity at this pin. The expected sections and frozen baseline were not rewritten to manufacture these outcomes; implementation PRs cleared their resolved `knownGap` markers.

## Residual risks and limits

- **Development evidence only.** These public, repeatedly used development rows are spent evaluation material. This result is not held-out or sealed-set generalization evidence, nor proof that arbitrary user questions work end to end.
- **Coverage remains incomplete.** The separate 150-phrasing map still has 16 unmatched rows: `C19-1`, `C19-2`, `D1-2`, `D2-1`, `D3-1`, `D4-1`, `D4-2`, `D5-1`, `D8-2`, `E8-2`, `E13-1`, `E13-2`, `E14-1`, `E15-2`, `E16-2`, `E18-1`. All 103 development rows matching a category does not resolve those 16 rows.
- **Mixed guest-link questions can lose sharing context.** The acceptance and plan phrase additions can outrank existing sharing selection. #516 explicitly pins this accepted limitation, following [Cowork's option A ruling](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6991093994). Code owns the separately assigned measured, tie-preserving option 2 design. No such repair is included in this result.
- **External dead-man precedence has compound-question tradeoffs.** Qualifying that subject can displace general recovery or camera context in mixed questions. These disclosed changes were accepted with #514; 98/98 does not prove preservation of every multi-subject context.
- **Guide condensation changes emphasis.** The two shorter opening paragraphs keep the substantive setup tables, limitations, security requirements and complete nine-step plan. The camera example is now implicit and answer-first summaries are thinner. Generated-answer quality after these edits remains unmeasured.
- **Credential compound questions remain bounded.** The tested credential-plus-plan question retains complete credential guidance and both condensed setup sections, but still omits the plan. This is not a claim that all desired sections fit every compound request.
- **Capacity is narrow and not a model-context measurement.** Future source growth can consume the plan's 40-character reserve. Zero cap overruns does not show that selected sections are sufficient, that the model's usable token window fits the corpus, or that latency and cost are acceptable. The builder can skip sections to obey its cap.

## What remains unmeasured and gated

| Item | Status at this pin | Required next evidence |
|---|---|---|
| Generated-answer correctness, completeness and citation grounding | NOT_MEASURED | Separately authorized server-side answer evaluation with frozen inputs, identities, model digest, roles and endpoint |
| Safety and governance of generated answers | NOT_MEASURED by this delivery harness | Existing evaluation contract's safety checks; no safety regression allowed |
| Model context-window sufficiency, latency and cost | NOT_MEASURED | Corpus token count versus usable window and measured latency/cost on the approved evaluation path |
| C1 ratification | OPEN | Authorized paired server-side evaluation and recorded Cowork ratification |
| C1b / pgvector need | UNDETERMINED | Existing conditional gate, not inferred from zero character-cap overruns |
| Training / sealed evaluation / release eligibility | NOT_RUN / NOT_RUN / false | Their separate governance and authorization requirements remain in force |

The [context evaluation method](../context-eval-method.md) requires the server-side 72-trial path without `--use-context`, improvement over its historical 2/72 baseline, and no safety regression. Historical comparability must be checked against the frozen identities before treating a new run as paired evidence. Its C1b gate also requires a measured context-limit or latency/cost constraint and Cowork agreement. The [backlog](../../wsjf-backlog.md) keeps evaluated C1 ahead of integrated C3. This report neither authorizes those runs nor ratifies C1, C3, training or an iteration close-out.

## Input fingerprints

SHA-256 values below come directly from the fresh harness report and use LF-normalized input text.

| Input | SHA-256 |
|---|---|
| Development set | `640efd512be304940e205ad60c87ec5de581020ca1929fda4e65acdc29171155` |
| Routing map, identical docs and backend copies | `a3b861dc58c67c337d556dd270bc3b6cb6cdf0408fe54e64c529ffeb7b82ac15` |
| 150-phrasing coverage map | `316d3c6a61991a10055ab0342045035fc532988b328a5b0436c1bafebeb5b6e2` |
| Independent-installation guide | `9419d846c6b52c61f6f285861ea482ab6fab90996fe1c4755abb5012dbb0349b` |

The other seven consumed guide fingerprints are identical to the table in the post-C2 checkpoint. The generated report includes all eight guide fingerprints and all row results for exact comparison.

## Deployment verification

At **2026-11-18 15:36 CST**, after both [backend run 44402088183](https://github.com/ImpressiveLLC/FaceoftheCabin/actions/runs/44402088183) and [host run 44402088317](https://github.com/ImpressiveLLC/FaceoftheCabin/actions/runs/44402088317) succeeded, bounded read-only SSH verified the deployed `cabin-backend:6d3360fd` image running with health UP. The deployed guide and packaged routing fixture match the two fingerprints above. Existing alert/schema/access/UI safeguards also passed; there were zero active or critical alerts. Evidence is posted on [#244, comment 6992371140](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6992371140).

This verifies deployed artifacts and service health, not generated-answer quality. No live Ask/model call or state mutation was performed.
