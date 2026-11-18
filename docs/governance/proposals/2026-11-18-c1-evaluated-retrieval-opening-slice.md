# C1 opening slice: evaluated retrieval (proposal for Cowork's ruling)

Drafted by Code on 2026-11-18 at Cowork's request on #244 ([6978686325](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6978686325)). This is a proposal. It authorizes nothing: no model run, no training, no ingestion, no host change, no sealed-set access. A1 stays NOT_MEASURED, `release_eligible` stays false, and training stays NOT_RUN.

## Where C1 stands today

| Fact | Source |
|---|---|
| C1a (deterministic context injection) was ratified 2026-09-07; C1b (pgvector) is conditional on a server-side run that beats 2 of 72 with no safety regression | [wsjf-backlog.md, C1](../../ai-assistant/wsjf-backlog.md) |
| Iteration 2 (whole-word routing, reviewed-document sections, role gating, 6000-character budget) is deployed; no answer-quality gain is claimed | [c1a-iteration-2.md](../../ai-assistant/rag/c1a-iteration-2.md) |
| C2-RUN-1: 12 of 72 PASS against 11 of 72 reference, HOLD. RUN-2: HOLD, edits reverted. RUN-3: inconclusive | [wsjf-backlog.md, C2 evidence log](../../ai-assistant/wsjf-backlog.md) |
| C2 slices 1 to 5 and the slice 8 phrases are merged; slices 6 and 7 are in review | #482, #487, #490, #491, #492, #495; #493, #494 |
| After C2, 13 of 20 new config and maintenance questions deliver their expected section whole to an administrator; 1 before | [ask-coverage-c2-check-2026-11-18.md](../../ai-assistant/corpus/ask-coverage-c2-check-2026-11-18.md) (#494) |

So we can now measure whether the right reviewed text reaches the model. We have never measured whether the model then answers correctly from it, with a set the authors cannot see.

## Proposed opening slice (C1-S1): measure retrieval and delivery offline

C1-S1 answers one question: **for a given question and role, does the reviewed section that answers it reach the prompt, whole, within budget?** It treats that separately from answer quality, which stays NOT_MEASURED.

1. **A committed delivery harness.** Turn the throwaway probe used for #494 into a small, reviewed test-side tool. It runs the real `AskContextBuilder` with a no-op knowledge-node repository, the docs root and the 6000-character budget. For each question and role it reports:
   - first category;
   - selected set (ties included);
   - the sections delivered and whether each arrived whole or truncated;
   - total characters.

   It needs no network, no model and no database.
2. **A development retrieval set.** Merge the slice case files already in `docs/ai-assistant/corpus/` into one set of question, role and expected `path#section` rows: recovery, stack map, capability routes, recreation, the identity and credential tests, and the 20 coverage-check questions. Label it **development, spent by design**. It is never reported as acceptance.
3. **Metrics reported per audience.** Whole-section delivery rate; first-category and selected-set agreement; no-category rate; truncation and skip rate; credential-location preservation (credentials-and-access delivered whole); and audience leaks, which must be 0. All are deterministic and repeatable from a commit.
4. **Gates as CI tests**, with no thresholds tuned on the development set. Existing pins unchanged. Credential-location negatives keep credentials-and-access. A household member or no role never receives an administrator section. Fixture mirrors stay byte-identical. These already exist in pieces across the slice tests; C1-S1 makes them one report.
5. **A baseline record.** One evidence file with the numbers at a pinned commit. Later C1 changes are measured against it.

**Not in C1-S1:** any model call; the 72-trial paired run; grading; any change to routing, aliases, matcher, boosts or budget. Whether known C2 gaps are fixed before the baseline freeze or after it is Cowork's decision 2; either way each fix is its own reviewed change.

## Where the gold sets come from

- **Development set (C1-S1):** only rows already public in the repository, authored from the reviewed guide sections. Because authors and reviewers have seen it, it guides the work and never accepts it.
- **Sealed acceptance set (later C1 slices, not this one):** the 120-question set described in the [public QA dataset register](../../ai-assistant/proposals/ask-public-qa-dataset-register.md) and the [custody design](../../ai-assistant/proposals/ask-custody-agent-and-cloud-r3-deferral.md):
  - roles and access stay separated as the custody design settled them: Cowork is the blind author of the sealed rows, and Codex holds the custody control (metadata) role under the owner waiver;
  - a hash lock, with aggregate-only disclosure;
  - no sealed rows in git;
  - no coding or training session ever receives sealed plaintext;
  - public datasets never enter it.

  C1-S1 does not need it and does not touch it. What remains before the sealed set can be used is what the custody design lists as open: name the domain verifier, the barrier verifier and any real-environment operator before freeze, and qualify the execution device. Any run needs its own approval.

## Metrics and gates for the later model-run slices (recorded, not run)

When a model-run slice is approved, it uses:
- the paired two-arm design already pinned by the [offline synthetic fixture](../../ai-assistant/eval-fixture/README.md);
- the existing failure codes in [context-eval-method.md](../../ai-assistant/rag/context-eval-method.md#failure-classification): `CONTEXT_NOT_USED`, `CONTEXT_NOT_RETRIEVED`, `REASONING_ERROR`, `CITATION_MISSING`, `SAFETY_VIOLATION`, `GOVERNANCE_FAILURE`, `FALLBACK_CORRECT` and `FALLBACK_INCORRECT`, unchanged;
- separately, the distinctions [wsjf-backlog.md's C1 entry](../../ai-assistant/wsjf-backlog.md) asks for: missing knowledge, failed retrieval, reasoning errors, citation failure and unavailable live state. Two of these, missing knowledge and unavailable live state, have no code in the current list. Adding codes for them would be a grading change that needs its own ruling; this proposal does not redefine grading.

Any safety, privacy or governance regression blocks, whatever the aggregate. Until that slice runs, every answer-quality figure stays NOT_MEASURED. C1-S1 supplies the retrieval side (`CONTEXT_NOT_RETRIEVED`) in advance, so a later model failure can be attributed to retrieval or to the model.

## What C1 needs from C2

1. **The seven named gaps** in #494 (C07, M02, M03, M04, M05, I02, I04), and the two mixed-installation phrasings Code flagged on #493 (6978458407). Each would need a bounded alias or tie fix, reviewed against the pins and credential negatives. Whether to fix them before the C1-S1 baseline is frozen, or to freeze first and measure them as known gaps, is decision 2 below. Fixing first is an option, not a dependency already imposed.
2. **A ruling on budget and tie delivery.** M04 shows a selected tie category that delivers nothing, because the first section uses the budget. Tied categories already share the top score, so ordering by score cannot help. Candidates for later review are a per-category minimum share of the budget, a distinct secondary criterion for ordering tied sections (for example the number of distinct phrases matched, or section length), or smaller sections. Each changes delivery for many questions. Like the gap fixes, M04 falls under decisions 2 and 3: nothing is implemented here.
3. **One replica or none.** `route.py` predates the W-151 matcher. Either update it or retire it in favour of the delivery harness, so evidence files stop needing a caveat.
4. **Stable section ids.** C1 rows key on `path#section`. A heading rename in C2 silently breaks a row. The harness should fail on any expected section that no longer resolves; the existing `everyFixtureDocumentReference_resolvesToARealSection` covers the fixture side only.

## What stays parked

- C1b pgvector, embeddings, chunking and top-k retrieval. Still conditional on a server-side run beating 2 of 72 with no safety regression, and only if measured context limits or latency require them.
- Any model change, fine-tuning or training: NOT_RUN without Nate's authorization. That includes the GPU-host purchase; the decision brief is merged and C1 is its first possible benefit, but nothing here depends on it.
- The 72-trial live run, grading, and sealed-set authoring and custody.
- Intent and slot polish, #420, #421, S2 and E3, unchanged from the iteration plan.

## Decisions for Cowork

1. Approve C1-S1 as scoped above: delivery harness, development set, per-audience report, one baseline file. Docs and test code only, with no runtime change.
2. Decide whether the seven gaps (M04 included) and the two installation phrasings are fixed as a C2 tail **before** the C1-S1 baseline is frozen, or the baseline is frozen first and they are measured as known gaps. Code's recommendation is to fix first, but this is an option pending Cowork's ruling.
3. Decide whether to measure the M04-style tie-delivery problem in C1-S1 and rule on a budget change afterwards (recommended), or rule now.
4. Decide whether to retire `route.py` or update it to the W-151 matcher.

If approved, the C1-S1 acceptance would be:
- the harness and the development set merged;
- the report reproducible from a pinned commit;
- the CI gates green;
- a baseline evidence file recording the per-audience numbers, with answer quality still NOT_MEASURED.
