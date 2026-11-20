# S2/S4 candidate status and remaining data gates

Snapshot: 2026-11-20 04:48 CST, clock-tool time. Source main: `2abd6fe313e91b8b01c99e6578d6a044acdbad2f`. Assigned on #244 comment7037575183. Status only, no new design, private read, appointment, packet or execution.

## Authored inventory, not reviewed data

Recomputed from committed [synthesis-templates.json](../training/intent-slot/synthesis-templates.json): **190 templates, 495 candidate utterances, zero completed independent S4 reviews and zero approved Track B rows from this candidate pool**. Source content SHA-256 (raw `git show` bytes, not Git object ID): `a21267edad5122fcc524cb09d5a51b9ad6037463e1c17338144f03adddcc5eb2`.

| Authored target intent | Candidate rows | Independently reviewed |
|---|---:|---:|
| clarify | 153 | 0 |
| express_need | 118 | 0 |
| propose_automation | 80 | 0 |
| unsupported | 60 | 0 |
| set_device_state | 57 | 0 |
| get_device_state | 19 | 0 |
| set_device_attribute | 6 | 0 |
| answer_question | 2 | 0 |
| **Total** | **495** | **0** |

Counts describe authored targets, not independently accepted semantics. PR review, passing schema checks and lexical screening do not count as row review. Zero-review status is from the execution record and [S4 plan](s4-authored-candidate-review-plan.md), not something inferred from JSON shape alone.

Whole-key composition of the six public batch maps (batch4 then batch6 then batch7 replacing their shared keys, retaining batches2/3/5) yields **21 canonical families covering 340 distinct rows**, with no duplicate ID ownership. **155 legacy rows across 75 templates remain held** for complete family resolution or explicit exclusion; the mapped 340 are not automatically admitted. The two inherited answer_question rows are within the held inventory and additionally lack an approved source; do not subtract them twice. Admission/provenance, cross-batch family links and eligibility still need reconciliation.

## What S4 needs before access

The merged [review plan](s4-authored-candidate-review-plan.md), [packet-builder specification](s4-packet-builder-spec.md) and [election mechanics](s4-reviewer-election-mechanics.md) are design/process evidence, not implemented gates or completed appointments. Before S4 starts:

- Pin the admitted train-only cohort, complete families, authorship/provenance and rubric; preserve every hold and exclusion reason.
- Implement and validate the required packet/import integrity changes: opaque IDs, protected crosswalk, `{id,text,card_target}` projection with explicit null, versioned hidden commitments, immutable submissions and tamper checks. Existing tooling unchanged cannot establish compliant Track B evidence.
- Screen and appoint two eligible independent blind labelers per admitted row and separately elect eligible auditors. Demonstrate delivery isolation and obtain applicable source/output authority before access. No candidate is appointed by these documents.
- Precommit audit rules and coverage; after agreement/admission locks, precommit the exact sample before auditor access/scoring. Track B requires two labels for every admitted row, then a separate `ceil(0.20 * M)` audit with at least 90% agreement. Disagreements remain held for governed disposition; no source-author self-approval.

If all 495 became eligible and agreed, that would mean 990 initial labels and 99 audited rows needing 90 accepted matches. These are conditional planning figures, not current admission or completed work.

## Current work queue versus formal gate order

The hub currently calls the private-source sample/packet-preparation lane **S5**. That is a task label, not evidence that the formal audit gate precedes blind review. The [data-path pipeline section 7](sft-data-path.md#7-pipeline-owners-and-exit-criteria) orders **S4 blind review -> S5 rulings/audit -> S6 freeze -> S7 preparation dry run**. The [contract section 5 amendment](intent-sft-training-contract-v1.md#5-owner-amendment-codex-elects-the-auditors-6874199353) changes auditor eligibility, not that dependency order. Track A private-source preparation also does not substitute for Track B authored-row reviews.

| Queue/gate | Owner and next action | Waiting on / exit evidence |
|---|---|---|
| Current hub S5 preparation task | Codex; Code checks permitted counts/hashes only | Read-only task completed per hub7033597518. Packet creation NOT_RUN; direct packet-creation permission pending, checkpoint7036813827. Then implementation, eligibility and delivery-isolation gates apply. No private content or metadata reproduced here. |
| S4 blind review | Codex coordinates eligible independent labelers; authors cannot review their own families | Admitted manifest/families, reviewed tooling, separate access authority and screened appointments. Exit: complete independent labels, immutable agreement report and disagreement queue per track. Currently NOT_RUN. |
| Formal S5 rulings and audit | Nate resolves disputes; Codex elects independent train-data auditors under the amendment | Locked S4 evidence and precommitted sample. Track A 10% and Track B 20%, each at least 90% agreement; required disagreement dispositions remain necessary. Protected Track C has its own qualified access plan. Current authored-pool audit NOT_RUN. |
| S6 freeze/leakage | Code, under authorized custody and freeze-machine procedure | Applicable reviews/audits/dispositions, authorized sealed placement and implemented validated freeze/screen tooling. Exit: leakage checks pass and exact frozen identities/hashes recorded. [Freeze specification](s6-freeze-command-and-ledger-spec.md) is design only; `FROZEN_WITH_GAPS` is not an ordinary passing gate. |
| S7 preparation dry run | Code, after valid S6 evidence | Reviewed implementation of the [remaining gaps](s7-prepare-sft-gap-list.md), including [frozen-family exclusion](s7-g1-frozen-exclusion-build-spec.md), matching screen result, complete family binding and audited provenance. Exit: verified splits, manifests and data card; no training. |

Thus the immediate queue can prepare the hub's S5 packet lane and S4 tooling, but **no S5 audit passes before its relevant blind-review evidence exists**, and neither documents nor a read-only permission advance S6/S7. Runtime selection, template evidence, scoring-option decisions and the two execution keys remain separately tracked in the [Phase 1 plan](dev-sft-r1-phase1-plan-draft.md); none is granted here.

Every agent audit result retains the exact label: `agent auditor(s) elected by Codex under owner amendment, not human audit`. The sample read, merged designs and this status note establish no independent labels, audit pass, frozen set, training run or release eligibility.

Validation for this note: recomputed template/intent counts and source SHA-256 from the pinned commit; recomposed public family maps; cross-checked existing plan, pipeline and amendment. No private inputs, models or runtime tests used.
