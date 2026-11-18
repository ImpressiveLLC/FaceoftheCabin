# C1 residual-gap measurement and ordering: M04 and the installation plan (2026-11-18)

Status: **measurement and ordering only; nothing implemented.** Assigned by Cowork on #244 ([6988147873](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6988147873)), promised in plan #509. Measured on `main` ef1481d56a629bc7911cf38453bd8ef7b10a3187 with the real `AskContextBuilder` (no knowledge nodes, reviewed docs, 6000-character budget, administrator role). Category scores were read from the builder's own `score` method in a throwaway probe that was not committed. No guide, routing map, matcher, budget, test or runtime change. No budget raise and no score workaround is proposed. A1 NOT_MEASURED, `release_eligible` false, training NOT_RUN.

This covers the two larger residual gaps from the [post-C2 checkpoint](../../ai-assistant/rag/eval-results/2026-11-18-c1-post-c2-checkpoint.md). The third, the 46-character acceptance gap, has its own accepted design (#510) and implementation draft (#512).

## cov_M04: selected, but the budget skips its section (408 over)

Question: "What does the external dead-man monitor do if the hub goes silent?"

| Order | Category (declaration index) | Score | Why it matches | Section | Characters | Delivered |
|---|---|---|---|---|---|---|
| 1 | `self_healing_recovery` (36) | 1 | "dead-man" | `maintenance.md#what-detects-and-what-recovers` | 4648 | yes, whole |
| 2 | `recovery_deadman` (56) | 1 | "dead-man" | `operations-recovery-reference.md#external-dead-man-monitoring` | 1760 | **no**: 1352 left, 1760 needed |

- **Why it fails:** both categories match only the one-word keyword "dead-man", so they tie at 1. The tie keeps the earlier-declared category first, and its section uses 4648 of the 6000 budget. The second category's section must fit whole; it does not, by 408 characters.
- **Section structure:** neither section has subsections (no H3 headings), so there is no smaller existing anchor to map.
- **How much depends on the large section:** of the 253 measured questions (150 coverage-map phrasings plus 103 development rows), 3 deliver the 4648-character section to an administrator. All 3 receive it alone.

**Ranked candidate options (each needs Cowork's ruling; none is implemented):**

1. **A qualified selection phrase in its own zero-boost category** mapping the existing dead-man section, for example "dead-man monitor" (two words). It would score 2 against 1, so the dead-man section (1760) is delivered whole and the general self-healing section is no longer selected for this question. This follows the C07, M02 and M03 pattern. Cost: about 20 minutes plus the usual delivered-content, six-role, credential and cross-product checks. It needs a ruling on whether dropping the general detect-and-recover section is acceptable for a question that names the external monitor specifically. Unlike the installation case, that section does not hold setup limits.
2. **Narrow `self_healing_recovery` by removing its "dead-man" keyword.** `recovery_deadman` would then win alone at 1. This is simpler, but it edits an existing category, and every other dead-man wording would stop receiving the self-healing section. It needs a check of those wordings first.
3. **Condense the 4648-character maintenance section by at least 408 characters**, so the tie delivers both sections (at most 4240 + 1760 = 6000). This changes text that 3 measured questions receive. 408 characters is about 9 % of the section, so it needs a full preservation matrix like #510's. The margin would be 0, so not recommended.
4. **Split the maintenance section** into anchored subsections and remap `self_healing_recovery`. This changes what its other questions receive and adds anchor ownership. Not recommended for this gap.
5. **A global tie or budget policy** (for example, a minimum share per tied category). It would affect every tied question and was already ruled out as a quick fix. Lowest rank; record only.

## install_gap_1: not selected, and the section would not fit (953 over; 897 after #512)

Question: "What is the installation plan for a clean machine?"

| Category (declaration index) | Score | Why it matches | Sections | Characters | Selected |
|---|---|---|---|---|---|
| `installation` (27) | 3 | "clean machine" (2) + "install", matched inside "installation" (1) | `minimum-inputs` + `verified-clean-host-limitations` | 2130 + 2430 = 4560 | yes |
| `recreation_plan` (85) | 2 | "installation plan" | `establish-the-actual-installation-plan` | 2393 | **no**: lower score |

- **Two blockers**, as for the acceptance row:
  1. **Selection:** the plan category scores 2 against 3, so it is not selected.
  2. **Capacity, conditional on selection:** 4560 + 2393 = 6953, **953 over**. After the #512 condensation, `minimum-inputs` is 2074 and the bundle is 6897, still **897 over**.
- **Section structure:** the plan section has no subsections.
- **The setup limits stay:** the two `installation` sections carry the setup limits that the ruling says must be delivered. A plan category that won outright would drop them, which is rejected by ruling 6984259185.

**Ranked candidate options (each needs Cowork's ruling; none is implemented):**

1. **Keep it a known gap until the acceptance work lands** (#512 plus its separately ruled selection candidate). Then re-measure. Recommended: no change can close this gap without a structural edit to a guide section.
2. **Split the plan section into anchored subsections** and map a tie-selected category only to the part a person needs first. After #512 there are 1496 characters free; today there are 1440. This changes the frozen row's expected section, so the development set's expectation would need a recorded amendment, and the split needs a preservation matrix. Highest effort here; worth designing only if Cowork wants this row closed in C1.
3. **A tie-selection phrase alone**, as designed for the acceptance row. It does not close this gap: the plan section would be selected and then skipped, 897 over even after #512. Not recommended on its own.
4. **Plan wins outright.** This drops the setup limits. Rejected by ruling.
5. **A global budget raise.** Ruled out.

## Proposed order

1. **Acceptance row (46):** #512, plus the separately ruled selection candidate from #510. Smallest deficit; the design is already accepted.
2. **cov_M04 (408):** option 1 above, a qualified zero-boost dead-man phrase. The selection already exists, the fix needs no guide text change, and it follows four merged precedents. It needs the one ruling on dropping the general section for this question.
3. **install_gap_1 (953 / 897):** stays a known gap. Revisit only if Cowork wants it closed in C1, through a split design (option 2).

## Reproduce

From `cabin-orchestration-platform/backend` at ef1481d5:
- For the categories, run `AskContextBuilder.categoryIdsFor(question)`.
- For the delivered sections, run `buildContext(question, HouseholdRole.ADMINISTRATOR)`.
- For the scores, call the compiled categories' `score` on the lower-cased, whitespace-collapsed question, as `select` does. The probe read them by reflection and is not committed.
- Section sizes are also reported by `AskDeliveryHarnessTest` for the two gap rows.
