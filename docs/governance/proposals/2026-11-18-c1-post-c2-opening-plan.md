# First post-C2 C1 slice: checkpoint and bounded budget design

Status: **proposal for Cowork's ruling**, assigned on [#244 comment6986589596](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6986589596). Prepared from main `1a94b0be1f05c4d3a9e488cd9860032f8265d162`. This plan is docs only; owner acceptance and Cowork's written scope ruling are required before the proposed work starts. No new work-item IDs are allocated.

## Starting point

The [C1 opening proposal](2026-11-18-c1-evaluated-retrieval-opening-slice.md) named C1-S1: the real-backend delivery harness, public development rows, per-audience gates and a frozen baseline. Those landed in #499; #501 retired the old routing replica. Do not build another harness or overwrite the frozen `fd1f8676fe55dd1ee9c1efaed1e1784abf96ca6e` baseline.

C2 slices and tail fixes are merged through #507. The final reviewed tail evidence reports administrator whole delivery **95/98**, household **5/5**, zero audience leaks, zero over-budget rows and maximum5143characters at the fixed6000-character budget. The 150 coverage phrasings retain16unmatched IDs. These are spent development results, not answer-quality or held-out acceptance results. C2 operational closure still requires the #507 deployment/live check.

Exactly three development gaps remain: `cov_M04`, `install_gap_1` and `install_gap_2`. The [tail-plan ruling](2026-11-18-c2-tail-plan.md#installation-budget-ruling-2026-11-18) preserves the installation setup limits and parks a separately reviewed design, acceptance deficit46characters first, plan deficit953second. M04 remains measurement/ruling only.

## Proposed owner split

| Owner | Proposed branch | Bounded deliverable | Independent reviewer |
|---|---|---|---|
| Code, pending capacity ACK | `code/c1-post-c2-checkpoint` | One reproducible post-C2 checkpoint using existing `AskDeliveryHarnessTest`, pinned to the approved merged main; retain the old freeze and record the current report/input hashes and three remaining gaps. | Codex, then Cowork final gate |
| Codex | `codex/c1-context-budget-design` | One docs-only acceptance-gap design with a line/claim preservation matrix for the current minimum-inputs and clean-host-limitations sections; compare the smallest safe condensation or split, or recommend no change if preservation cannot be shown. | Code, then Cowork final gate |
| Cowork | #244 and each PR | Confirm owners and scope, settle design choice, own exact-head merge order and any later deployment decision. | Nate's applicable authorization remains required for execution outside this slice |

Suggested sequence: checkpoint READY first, then design READY using that checkpoint. Source-reading/design can proceed in parallel after approval, but the design's measurements must reference the accepted checkpoint. The two branches must not both edit the tail plan, corpus or fixtures. Any shared-file change is agreed on #244 before editing. Each author integrates main and renews exact-head READY after a head change; reviewers never push to the author's branch.

## Checkpoint scope and gates

- Use the existing real Java harness and public103-row development set, plus its150-phrasing report and existing120pins. No replica, new question set or threshold tuning.
- Record actual measured revision, LF-normalized input hashes, role policy,6000-character budget, selected categories, delivered whole sections, skipped sections and per-audience metrics. Preserve known-gap markers and expected fields.
- Reproduce the final C2 counts or report any discrepancy before design starts. Distinguish this new post-C2 checkpoint from the immutable pre-tail freeze and from later candidate results.
- Characterize the three residual gaps from the actual builder: category order, section sizes, remaining budget and omission. Do not silently fix them while measuring.
- Gates: required inputs fail closed, whole-text equality, zero administrator leaks across administrator/adult household/child/kiosk/service/null, zero over-budget, full credential guidance on existing negatives, unchanged120pins and150coverage IDs. Reuse existing checks; add test code only if a concrete reporting gap is agreed on #244.

## Design scope and gates

Start only with the46-character acceptance deficit. The current4560characters of setup limits plus1486characters of acceptance total6046. A proposed condensation must map every prerequisite, limitation, warning, role/access condition and operational caveat to retained wording. A proposed split must show that both setup limits and the requested acceptance text still reach the intended question; moving required limits out of delivery is not preservation.

The design is a reviewable proposal, not an edit to the guide, map or runtime. Include exact affected headings/anchors, proposed text or section boundaries, predicted character counts clearly labeled as design calculations, tradeoffs and rollback. Preserve stable `path#section` references or explicitly inventory every dependent reference before proposing a migration. Prefer the smallest change; no general retrieval rewrite for a46-character deficit. If no safe reduction exists, keep the gap and state why.

The953-character plan deficit and M04 get measurement summaries and candidate ordering only, not combined implementation. A later implementation requires a separate explicit scope ruling, before/after real-builder evidence, credential and cross-product negatives, whole-section delivery, unchanged role/budget boundaries, exact-head independent review and CI. Do not claim candidate counts as measured until that approved candidate is actually run.

## Cost, limits and decision requested

Estimated effort after approval: Code checkpoint20-30minutes; Codex bounded design30-45minutes; independent review15-20minutes per artifact. These are planning estimates, not guarantees. Existing local CPU/offline tooling is sufficient for this scope; no purchase, account, GPU, cloud compute or new credentials are requested. Stop and return a concrete decision if the measured facts or preservation requirements cannot fit this scope.

Cowork should approve or amend this owner split after Code's capacity ACK, then name the pinned checkpoint commit and merge order. Completion means the checkpoint is reproducible and the design receives an explicit accept/reject/hold ruling. It does not mean the budget gaps are fixed or that the model answers correctly.

No model call, training, sealed-set access, grading change, pgvector/embedding/chunking work, global budget increase, score workaround, host/device action, or work on #420/#421 is included. A1 remains NOT_MEASURED; training NOT_RUN; release_eligible false. Custody roles and open device/verifier requirements in #497 remain unchanged. C3 follows the user-prioritized C1 work only after its own assignment.
