# S4 authored-candidate review plan

Status: **proposal only; execution NOT_RUN**. Hub assignment 7031619513. This document plans Track B review of the authored training candidates, not Track A private-source review or protected Track C. It creates no packet, labels, elected reviewer, approval, audit result, keys, split or training authorization. Author: Codex/Noether, an author of these candidate families, not an independent reviewer.

Source checkout: `3e441f5b9793a606e3183d2a738f2570635b929b`. Authority: [SFT data path sections 5â€“9](sft-data-path.md), [training contract section 5 owner amendment](intent-sft-training-contract-v1.md#5-owner-amendment-codex-elects-the-auditors-6874199353), [S6 freeze design](s6-freeze-command-and-ledger-spec.md), [S7 gap list](s7-prepare-sft-gap-list.md), and [S7 G1/G2 build specification](s7-g1-frozen-exclusion-build-spec.md). The public [package contract](../training/intent-slot/system.txt) and executable schema/validators govern representation; recorded owner/Cowork rulings must accompany the rubric where they resolve a boundary.

## 1. Pinned planning ceiling, not admitted data

The current [template source](../training/intent-slot/synthesis-templates.json) contains **190 templates / 495 UNREVIEWED candidates**. Exact Git-blob SHA-256:

`a21267edad5122fcc524cb09d5a51b9ad6037463e1c17338144f03adddcc5eb2`

| Authored intent (not independently approved) | Rows |
|---|---:|
| clarify | 153 |
| express_need | 118 |
| propose_automation | 80 |
| unsupported | 60 |
| set_device_state | 57 |
| get_device_state | 19 |
| set_device_attribute | 6 |
| answer_question | 2 |
| **Total** | **495** |

Every admitted row receives **two independent blind labels** under Track B, including ordinary commands. No review sampling substitutes for this census. If all 495 are admitted, the workload is **990 initial labels**. If all agree and remain eligible, the separate 20% audit is **99 rows**, not another 990 labels. Disagreements, rework and audit add work; these figures are not a time estimate or an approved cohort.

The two inherited answer_question rows are held under the current no-approved-source ruling (7031280620/7029753443); their presence in the inventory does not admit them. The 495/990/99 figures are conditional ceilings, not a claim that all rows are currently eligible.

Admission may shrink through quarantine, unresolved semantics, provenance gaps or family gates. Report ceiling, excluded/held counts and admitted counts separately, with reason and immutable row identity. Never fill a quota with an invented approval. Do not infer that repository review or previous lexical-screen passes establish semantic approval.

## 2. Family commitments and the legacy admission gate

These are exact current Git-blob hashes under `training/intent-slot/sft/`. Overlay files overlap intentionally; their row counts cannot be summed.

| Family-map file | Keys | Listed IDs | SHA-256 |
|---|---:|---:|---|
| s2-batch2-families.json | 5 | 150 | 8ca544c4759a6953035dec768c1bc9eb379bcd62af5ef2ed819814e1881a7054 |
| s2-batch3-families.json | 3 | 72 | 5a3b10034237daa7258cd112b2ea87cc7e4b48d0c94b73758b20feab005e37ad |
| s2-batch4-families.json | 9 | 48 | 3b351a44729dc7d6838df980b5960209a7ecced6fa2bdfead665ba36f455071e |
| s2-batch5-families.json | 4 | 60 | 1909a9c9c5d2e384abc95f614ae893b7cce05d89a2efb700d60af50fe1d2e68c |
| s2-batch6-families.json | 6 | 36 | 9688f5ebdb53fe00594492619dd13b5cce4d73f1e9d948ca01703ee985d3a826 |
| s2-batch7-families.json | 4 | 31 | 2f94cbd24e11c2de3381c88ed32b63eb46425feb2b70df9a5f0412d27c38659a |

Compose batch4 -> batch6 -> batch7 by **replacing complete overlapping entries under the same canonical keys**; an ID-deduplicated union preserving all declared links is equivalent. Retain the disjoint batch2, batch3 and batch5 maps. Never concatenate duplicate ownership lists or split negatives/context counterparts from their original families.

Effective mapped coverage is **21 canonical families / 340 unique rows**:

| Family group | Effective row sizes |
|---|---|
| batch2 ambiguous-device, hypothetical, medical, wrong-card-site, schedule-no-zone | 30 each (150) |
| batch3 secret, life-safety, unlock | 18, 18, 36 (72) |
| batch4+6+7 humid, hot, dark, arriving, leaving, bedtime, nap, wake_up, tv_time | 3, 9, 6, 7, 7, 7, 6, 4, 9 (58) |
| batch5 predicate, time_once, time_recurring, state_changes_to | 19, 4, 19, 18 (60) |

**155 older rows across 75 templates are outside those canonical maps.** Their generator template groups exist, but do not prove cross-template family independence or bind earlier minimal pairs. Hold these rows from admission until a reviewed complete family map resolves their links, or explicitly exclude them with counts/reasons. Do not silently treat every old template as an independent canonical family. The mapped 340 are not automatically admitted either: cross-batch links, provenance and eligibility still require checking. Similarity detection may add links, never erase declared ones, including polarity-sensitive pairs that a lexical matcher misses. No private S1 family map is read or incorporated by this plan.

## 3. Eligibility before row access

Maintain a protected identity/exposure register per family: author and reviser identities, agents/models/sessions, source-label exposure, earlier labeling/audit/training exposure, relevant packet access, conflicts and the decision/evidence for eligibility. Screen both proposed S4 labelers and the separate auditor **before any row access**. A fresh session, new agent name or different model alone does not establish independence. Unknown exposure means HOLD, not an eligible-by-default assignment.

The current Noether author and parent/coauthor are ineligible to review or audit their own families. Their source-target exposure also prevents a blind assignment for those rows. They may coordinate protected manifests and format checks without contributing independent labels. No named eligible reviewer or auditor is asserted here. Resolve legacy authorship before appointing reviewers for legacy rows.

S4 labelers A and B must independently label every row assigned to them, without seeing the source target, each other's outputs or adjudication. They must not coach one another. Use another screened labeler for a conflict, preserving two eligible independent labels per row. Shared model bias remains a limitation even with screened agents.

S5 audit is a **separate role**. Codex must record an explicit auditor election and family-level conflict screening. An auditor cannot audit families they authored, labeled or trained on. Reassign conflicts to another screened elected auditor; never remove conflicted families from the audit obligation. Every audit result must use this exact attribution: **agent auditor(s) elected by Codex under owner amendment, not human audit**. S4 labeler appointments do not confer S5 auditor eligibility. Nate retains disagreement authority and the right to inspect/revoke under the amendment; this document supplies no election or execution keys.

## 4. Admission, blinding and immutable evidence

Before labeling, pin source commit/bytes, prompt, schemas, vocabulary, generator/binder/tool versions and hashes, family-map inputs and composed mapping, provenance/authorship register, admission decisions, policy/rubric version and cohort counts by intent/family/context. Retain the full ceiling-to-admitted reconciliation. Machine-invalid, unmapped, unresolved-policy or provenance-conflicted rows are held, not repaired silently during review. Prior or later quarantine stays excluded; no S6 readiness is implied by S4 admission.

The **only per-row reviewer projection is `{id, text, card_target}`**, with explicit null for absent card context. Text and trusted card context must be faithful. Use opaque review IDs with a protected, committed source-ID crosswalk: current IDs such as `need-hot` and `negated` leak labels. Do not expose semantic template/family names, authored labels, earlier labels, source/batch tags, rationale or source browsing links in the blind packet. Independently shuffle packet order under recorded seeds; family binding remains hidden coordinator metadata. Keep both reviewers' sealed submissions immutable before comparison.

Separately retain a **hidden versioned commitment** containing source/review identity, text, card context, target and provenance/family bindings. Use the S7 versioned row payload and separately committed crosswalk/metadata rather than conflating its target-bearing hash with the projection hash. Record projection, row, cohort and audit-sample hashes under a specified canonical serialization. A card/text/target change invalidates the affected evidence; no reuse of stale audit links. The coordinator verifies hidden commitments; labelers/auditors verify the blind projection hash, never receive the target merely to recompute a commitment. No such packet, commitment payload or key is generated here.

Packet batching may reduce session size but never reduce coverage. Intake must reject missing/duplicate/unknown IDs, unexpected fields, changed projection bytes and incomplete submissions. No reviewer-side retrieval of training templates or label-bearing plans is permitted. General public rubric access must be screened so it does not expose these rows or their source labels.

## 5. Rubric, statuses and disagreement handling

Each labeler independently supplies a complete five-field target and a separate review disposition/reason. The disposition is review metadata, not an extra model-output field. Pin allowed dispositions and comparison rules before access.

| Dimension | Required assessment |
|---|---|
| Intent and boundary | Distinguish question, current state, command, need, schedule, clarification and unsupported request from meaning; politeness alone is not a question. |
| Need and negation | No device/action from a need, no opposite concept from absence. Apply the bounded 7030100381 negated-need ruling; do not generalize it to arbitrary medical/hypothetical language. |
| Safety and invention | No invented capabilities, credentials, entity IDs, service calls, thresholds, authorization or completion. Preserve clarify versus unsupported boundaries, including valve opening and policy bypass. |
| Context | Preserve stated site/room, units and trusted card; no default site, target substitution or fabricated inventory. Distinguish textual ambiguity from several authorized matches that the request cannot represent. |
| Automation | Explicit dates/offsets and recurrence/dwell semantics, semantic targets, valid trigger/conditions/actions, confirmation; no guessed timezone or device behavior. |
| Envelope and clarification | Correct slots, confirmation and null fields; question must resolve the actual ambiguity without leading to an invented action/cancellation. |

Machine validity (schema, contract with card, vocabulary) and semantic disposition are separate. Suggested semantic states are `LABELLED`, `AMBIGUOUS_HOLD`, `POLICY_HOLD`, `UNSAFE_INVENTION` and `UNLABELABLE`; a passing schema cannot override a hold. Missing or machine-invalid submissions remain incomplete, with logged revisions and no automatic agreement.

Compare the complete independently produced targets using precommitted JSON key-order canonicalization only; do not normalize away slot, unit, polarity, confirmation or clarification differences. Different clarification wording is a disagreement until adjudicated, not an automatic semantic equivalence. Both agreeing labels are compared with the hidden author target only after submissions lock. A shared correction is recorded as a proposed correction, not silently substituted into the source; hold source-label conflicts for Nate/authorized adjudication and a new committed derivative.

Any A/B disagreement, abstention, hold or unresolved policy question enters the Nate queue. Never approve by majority, by author preference, by source-label match, or by schema success. Keep original submissions and decision rationale. S4 states are `PENDING`, `AGREED_PENDING_AUDIT`, `DISAGREEMENT_HOLD` or `EXCLUDED`; none means training-ready. Ruled/revised rows require explicit recorded disposition and renewed validity/evidence checks. Keep them outside the current agreed cohort until resolved; admission to a later audit cohort requires a new precommit, not an after-the-fact denominator change.

## 6. Separate 20% blind audit

Before S4 starts, approve the audit-selection algorithm and strata definitions. After agreement/admission locks, but **before audit access or scoring**, precommit the exact cohort hash, denominator M, intent/family/context counts, selection seed, quotas, projection/commitment hashes, sample IDs hash, elected auditor assignments and conflict decisions. Use **ceil(0.20 Ã— M)** rows (zero only for an empty cohort, which cannot pass). If M=495, n=99. The ceiling is not automatically the audit denominator.

Proposed deterministic stratification: allocate n proportionally across agreed intent strata, with at least one per nonempty stratum; use largest-remainder allocation with a documented lexical tie-break. Within each intent, cycle through canonical families in seeded order, choosing seeded rows without replacement to spread coverage. Record coverage of card-dependent rows, safety/clarification boundaries, need categories, all four trigger kinds and declared minimal-pair families. Precommit any mandatory coverage constraints before labels; if n cannot meet them, increase the sample transparently rather than dropping coverage or choosing convenient rows. Overlapping strata are metadata, not double-counted rows. Report realized counts and omissions, including small-family limits, before scoring.

Auditors independently label the blind projection before any agreed/source label is disclosed. The coordinator then compares against the locked agreed target. Audit agreement is accepted matches divided by **all sampled rows**; missing, abstained, invalid or unresolved audit results are non-matches, not removed denominators. Require at least **90%**, so 99 audited rows require at least 90 accepted matches. Report the pooled Track B result and every separately commissioned auditor/cohort sample; do not pool away a failed sample. Subtype rates remain descriptive, not invented per-subtype acceptance thresholds.

All audit disagreements go to Nate/hold even if the numerical rate passes. Below 90% fails the cohort gate. Preserve the original result; never relabel the sample, replace difficult cases or silently shrink the denominator to manufacture a pass. Remediation creates a versioned cohort and a separately precommitted follow-up audit with eligibility rechecked. No promotion while required disagreement disposition is unresolved. Record matched/mismatched counts, sample/cohort hashes, exact attribution, election references, identities/models and remediation history separately from the S4 agreement report.

## 7. Tooling gates and downstream custody

The work tracked through **#529/#536 remains pending implementation/validation**; published proposals are not completed gates. Current `grading/review_packets.py` projects id/text without card, commits targets without card, uses a 10% audit constant and has person-name restrictions for auditors. `prepare_sft.py` likewise verifies a 10% audit and incomplete row identity. Semantic IDs additionally need blinding. Do not run these unchanged and call the result compliant Track B evidence.

Required before execution: card-complete blind projections and commitments; opaque-ID crosswalk; full family binding/admission reconciliation; two-label census and immutable disagreement import; Track B 20% sizing and precommitted strata; explicit elected-agent attribution/eligibility; split pre-/post-audit manifests and tamper rejection. Validate improvements with synthetic guard data, including no target leakage, card mutation invalidation, conflicting-family rejection, overlay replacement, incomplete labels and failed audit handling. This proposal implements none of them.

S6 leakage/custody and S7 preparation remain distinct gates. Sealed text and screening keys stay on the authorized freeze machine; this plan neither opens them nor requests them. Later admission/promotion must respect authoritative quarantine and matching screen-train evidence. A changed train artifact needs matching screening; map declarations cannot be recomputed away. S7 G1/G2 exclusions, near-overlap handling, provenance checks and split naming still require their own implementation/evidence. `FROZEN_WITH_GAPS` does not satisfy S6 without the required separate owner amendment. No S4 or S5 success authorizes a freeze, host action, training, export, routing or protected Track C access.

## 8. Handoff and current status

Execution handoff must contain: reconciled train-only admitted manifest; complete family mapping and hashes; screened labeler appointments and separate auditor election; pinned rubric/rulings; blind projection and hidden-commitment contracts; two-label coverage/validity report; immutable disagreement queue; precommitted audit selection; audit report and unresolved holds; downstream gate status. Public reporting uses counts/hashes and honest attribution, never private/sealed text or credentials.

This author inspected only permitted public process/tooling and training metadata/maps. Checks for this document are source-byte hashes/counts, overlay coverage arithmetic, local links and diff whitespace. No packets or rows were generated, no labeling/audit/tests/models were run, and no private/sealed data or keys were accessed. Parent validation recomputed the committed Git-blob hashes and intent counts; publication of this proposal changes no execution state. **S4 execution, S5 audit, S6 freeze/leakage and S7 dry run: NOT_RUN by this task.** The only deliverable is this proposal, ready for process review with eligibility, legacy-family admission and tooling gates still open.
