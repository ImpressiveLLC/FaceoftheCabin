# C1 installation-plan gap: condensation versus splitting

Status: **design only; no guide, fixture, matcher, budget or runtime change.** Assigned by [Cowork on #244](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6990059450), after #512. Source pin: `cbb87010777813b14bdf73d801648760876f36a2`. The [residual-gap ordering](2026-11-18-c1-residual-gap-ordering.md) keeps both condensation and splitting open until measured. This proposal compares them and records the separate selection decision needed before implementation.

## Recommendation and current blockers

Recommend condensing only the two introductory paragraphs that repeat their section tables. Keep both tables, their closing paragraphs, the non-secret-answer sentence, every heading/link and all nine installation-plan steps unchanged. This saves **937 characters**, reducing the conditional setup-plus-plan bundle from 6897 to **5960**, with **40 characters** of headroom under the unchanged 6000 budget. A split is not necessary to establish text fit.

For "What is the installation plan for a clean machine?", the current real builder selects only `installation`: "clean machine" scores 2 and "install" matches the ending in "installation" for 1. The existing `recreation_plan` phrase "installation plan" scores 2 and loses. Even if it were selected, current setup inputs and limitations total 4504, leaving 1496 for a 2393-character plan. The deficit is **897**, after #512's 56-character saving from the earlier 953.

Condensation alone changes capacity, not selection. A separate selection ruling remains required. The probe below demonstrates a bounded tie candidate and also exposes a guest-link context loss outside the measured corpus. **Do not authorize that candidate implicitly by accepting the text design.**

## Exact candidate text

In [independent-installation.md](../../ai-assistant/user-guide/independent-installation.md), replace only the first paragraph under `## Minimum inputs` (line 7 at the source pin) with:

> **Short answer.** Ask only for inputs the selected capabilities need. Never collect secrets in chat or reuse source-instance credentials, seed data or deployed paths as defaults.

Replace only the first paragraph under `## Verified clean-host limitations` (line 50) with:

> **Short answers.** Neither the M920q overlay nor the development bootstrap is a complete blank-host install.

These are complete paragraph replacements. Nothing moves to another section. Their detailed requirements remain in the same delivered sections' unchanged tables and closing paragraphs. The camera example becomes implicit in the selected-capability rule and device/site rows; the GPS and zone-presence example remains explicit. This is a reviewable wording tradeoff, not a claim that the removed introductions contained no information.

## Preservation matrix

Line numbers refer to the pinned source. The tables and closing paragraphs are unchanged byte for byte in the candidate.

| Source obligation or claim | Where it remains |
|---|---|
| Ask only for selected capabilities; household needs determine services and acceptance tests; no unnecessary personal history or advance collection of optional integrations | New minimum-inputs opening and input row 1 (line 13) |
| Authorized target host and operator access before inspection/provisioning | Input row 2 (line 14); unchanged plan step 1 still forbids mutating an unidentified host |
| Independent repository/instance identity, deployment paths and runner target | Input row 3 (line 15) and unchanged closing paragraph (line 20) |
| Access model, domain and account ownership before exposing services/auth | Input row 4 (line 16) and closing paragraph |
| Only enabled integrations/devices; site details only for selected features | Input rows 5-6 (lines 17-18), including the zone-presence example; new capability-selection rule also applies to cameras |
| No SSH private keys, login/DNS/API tokens, OAuth client secrets or vault passwords in chat | New blanket prohibition and unchanged explicit input rows 2, 4 and 5 |
| Use secure setup and vault pointers; never reuse source-instance credentials, personal seeds or deployed-path defaults | Input rows 3 and 5 plus new opening |
| Retain confirmed non-secret answers; show assumptions for correction | Separate sentence on line 9 unchanged |
| No precise location committed to shared corpus | Input row 6 unchanged |
| Separate repository/domain/accounts/host, conditional GPS; verify provider pricing/setup before purchase/account setup; no current pricing promise | Closing paragraph on line 20 unchanged, including its source link |
| Development bootstrap does not install Docker/Compose or deploy the platform | Clean-host table row 1 (line 54), now summarized by the shorter opening |
| Host role installs Docker/Compose, updates checkout and registers runner; external DNS/tunnel/OAuth and application setup remain separate; audit current runner/platform prerequisites | Clean-host table row 2 (line 55); unchanged plan steps 4 and 7 reinforce the distinct procedures |
| Overlay disables duplicate services, joins external cabin_default, assumes support stack/network; blank host needs explicit reviewed arrangement | Clean-host table row 3 (line 56) unchanged |
| Production stack needs host bind mounts and local Blink build; supply required content/build context or omit capability through supported plan | Clean-host table row 4 (line 57) unchanged |
| Local override is not a self-contained replacement for application definitions | Clean-host table row 5 (line 58) unchanged |
| Initial model pull and model/digest verification are separate setup steps | Clean-host table row 6 (line 59) unchanged; no model operation authorized here |
| Approved instance-default substitutions and browser check against own endpoints | Clean-host table row 7 (line 60) unchanged |
| Installation-engineering gaps do not delay the corpus; no standalone overlay or provisioning executor delivered | Closing paragraph on line 62 unchanged |
| Independent secrets, selected-service state, authorized deployment identity, actual integration proof, Node-RED authentication before household access, operator/recovery handoff | All nine plan steps (lines 24-32) unchanged, including links and warnings |
| Acceptance record, isolation requirements, clean-room NOT RUN status, optional presence and source references | All remaining guide text unchanged |

Preservation depends on delivering complete sections, including their tables. A future implementation must not substitute only the short openings, truncate a section, or move prerequisites outside the delivered bundle.

## Measured capacity and real-builder prototype

All counts include section headings, LF-normalized lines and stripped section-edge whitespace, matching `ReviewedDocumentSource` and Java character counts. The relevant text contains only basic-multilingual-plane characters. Source guide SHA-256 (LF normalized): `5c75d23be75103b8e62e4ec2e6042ed7a054c28d6279e95a24d6cd60693e2202`. Candidate guide SHA-256: `9419d846c6b52c61f6f285861ea482ab6fab90996fe1c4755abb5012dbb0349b`.

| Text | Current characters | Candidate characters | Saved |
|---|---:|---:|---:|
| Minimum-inputs opening | 641 | 178 | 463 |
| Minimum-inputs whole section | 2074 | 1611 | 463 |
| Clean-host opening | 582 | 108 | 474 |
| Clean-host whole section | 2430 | 1956 | 474 |
| Required setup sections together | 4504 | 3567 | 937 |
| Installation plan | 2393 | 2393 | 0 |
| Setup plus plan | 6897 | 5960 | 937 |
| Setup plus acceptance | 5990 | 5053 | 937 |

A temporary real-builder probe used copied guide files and a copied fixture outside tracked source files. No production guide or fixture was edited. The selection candidate was a new zero-boost category with the three-word phrase **"installation plan for"**, mapped only to the existing administrator plan section and appended after existing categories. Its score ties `installation` at 3 for the exact gap question. Do not add it to `recreation_plan`, where scores would accumulate and displace setup limits.

| Prototype variant | Exact gap question result |
|---|---|
| Current source | Only installation selected; two setup sections, 4504 characters; plan absent |
| Condensation only | Only installation selected; two setup sections, 3567 characters; plan still absent |
| Selection only | Installation and candidate selected; setup sections consume 4504, plan still skipped |
| Both candidate changes | Installation and candidate selected; all three sections delivered with exact source-text equality, 5960 characters |

The same probe compared 253 questions (150 coverage-map phrasings plus 103 development rows) across all six roles. Apart from the exact target's administrator delivery, document references and complete context matched after normalizing only the two proposed paragraph substitutions. Category selections remained unchanged for the other 252 questions. Other roles receive no installation content. Existing acceptance/recreating questions remain intact. This is prospective source-delivery evidence on a throwaway candidate, not a shipped result, model answer or held-out evaluation. Current published delivery remains the #512 result at this source pin: administrator 96/98 and household 5/5; M04's separately sequenced PR does not alter these pinned measurements.

### Selection caveats requiring a ruling

The candidate is **not universally isolated** by the 253-question comparison:

- For "What is the installation plan for a Zigbee device / a rule / Home Assistant / Frigate?", the first category changes from `recreation_plan` to the candidate, but the delivered plan section is identical to current source.
- For "What is the installation plan for a guest link?", current source selects `access_sharing` and `recreation_plan`, delivering both sharing sections and the plan. The candidate selects only the plan category and drops both sharing sections. This is a measured context loss outside the two corpora, analogous to #512's recorded guest-link limitation. It needs explicit acceptance or a separately designed remedy before implementing this selection candidate. Do not hide it by weakening existing expectations.
- Password, token and secret variants of "Where is the ... in the installation plan for a clean machine?" keep `credential_redaction` first and deliver its entire guidance plus both condensed setup sections (5494 total). The plan is still omitted because only 506 characters remain. The target fix is not a claim that every compound credential/plan question can carry all sections within 6000.

A future implementation must pin actual trigger-containing cross-product questions, credential overlaps, existing recreation questions and the 253-question comparison. Preserve 120 routing pins and all six role boundaries. Only clear `install_gap_1` when its whole-section gate passes; retain the frozen baseline and expected fields. Add a whole-bundle regression and prove growth beyond the 40-character margin fails visibly.

## Split alternative: measured fit is not complete delivery

For a concrete size comparison, divide the unchanged plan's steps 1-4 and 5-9 under hypothetical H2 headings `Installation plan preparation` and `Installation plan execution and verification`. Their heading-inclusive lengths would be 918 and 1514 characters. Current setup sections plus only the preparation part fit at 5422, but omit independent-secret provisioning, selected-service preparation, authorized deployment, actual integration/Node-RED authentication and recovery handoff. Delivering both parts with current setup totals 6936, which still exceeds 6000.

Adding H3 subheadings beneath the current H2 does not shrink its mapped text: the source reader includes descendants until a heading of equal or higher level. Mapping just one child would omit the rest; replacing the H2s would also require anchor migration. Neither is a free capacity fix. Selecting a partial plan requires a product decision for a staged interaction and a recorded expectation change, not merely clearing the existing full-plan gap.

Prefer the two-paragraph condensation for capacity because it preserves the complete plan, existing anchors and current expectations. Keep splitting available if preservation review rejects condensation or if a staged installer interaction is explicitly requested. Do not present condensation as universally safe before independent review.

## Requested decisions and later implementation boundary

1. Accept, amend or reject the two exact paragraph replacements and preservation matrix. Forty characters is modest headroom; no later prose growth may silently omit a required section.
2. Separately accept or reject the three-word tie candidate, including its measured guest-link context loss and compound credential limit, or assign a different selection design. Text approval alone does not close the selection gap.
3. If both are accepted, authorize one isolated implementation PR after #514, with source/hash evidence, unchanged-baseline comparisons and exact-head reciprocal review. Keep guide edits, both fixture copies, focused tests and the one gap-marker removal together. Cowork remains merge/deploy owner.

Estimated later implementation and focused verification: 30-45 minutes plus independent review. A split/staged-interaction design would take more work and requires product and expectation decisions first. No paid compute, accounts, credentials or hardware are needed. Rollback for any future combined implementation restores both paragraphs, removes its candidate category from both mirrors, and restores the gap marker together; this proposal itself has only a documentation rollback.

A1 NOT_MEASURED; training NOT_RUN; release_eligible false. No model call, sealed material, grading change, live device action or deployment is authorized by this document.