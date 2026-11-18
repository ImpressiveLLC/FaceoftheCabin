# C1 acceptance-gap design: remove one repeated clause, preserve the limits

Status: **design only; not implemented**. Authorized for read-only source analysis and a proposal by [Cowork's scope ruling](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6987067644). Source pin: `1a94b0be1f05c4d3a9e488cd9860032f8265d162`. Measured facts are reconciled with [Code's post-C2 checkpoint](https://github.com/ImpressiveLLC/FaceoftheCabin/blob/5f93e539aa0f294679ff8addef7a4f950c8d23d2/docs/ai-assistant/rag/eval-results/2026-11-18-c1-post-c2-checkpoint.md) at PR #511 head `5f93e539aa0f294679ff8addef7a4f950c8d23d2`, measuring the same source pin. Codex's reciprocal checkpoint review is [recorded on #244](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6987328429); Cowork retains the final gate. No guide, fixture, matcher, budget or test code changes are included.

## Recommendation and the two blockers

Recommend a single future wording change in `independent-installation.md#minimum-inputs`: remove the repeated selected-capabilities clause from one sentence, while retaining the non-secret-answer and assumption requirements. It saves **56 characters**, enough to turn the conditional acceptance bundle from 6046 into **5990 characters**, with 10 characters of headroom under the unchanged 6000-character budget. Do not split headings for this small deficit.

This is only a capacity design. **Condensation alone does not fix the reported question.** For "What goes in the installation acceptance record when I install on a blank machine?", the current builder selects only `installation`; `recreation_acceptance` is not selected. There are two separate blockers:

1. Actual selection miss: `installation` scores 3 ("install" plus "blank machine"); `recreation_acceptance` scores 2 ("installation acceptance"). Only the two setup-limit sections are delivered.
2. Conditional capacity shortfall: if the acceptance section were also selected, the three current sections would total 6046 characters, exceeding the budget by 46.

M04 differs: its expected category is already selected in a tie, but the section is not delivered. Do not describe all three residual gaps as selected-but-omitted or claim a routing improvement from a text-only edit. The current known-gap markers remain in place.

## Exact proposed text change

Source: [independent-installation.md](../../ai-assistant/user-guide/independent-installation.md#minimum-inputs), line 9 at the pinned revision. Replace only this sentence:

> Ask only for choices required by selected capabilities, retain confirmed non-secret answers and show assumptions for correction.

With:

> Retain confirmed non-secret answers and show assumptions for correction.

The removed clause is already expressed in line 7 ("Ask only for what the selected capabilities need") and the unchanged input table's conditional-feature rows. No requirement is delegated to an unselected section. The candidate is a 128-character sentence replaced by 72 characters; capitalization changes because the retained clause becomes its own sentence.

## Preservation matrix

Line numbers refer to the source pin, not this proposal. All rows except the repeated clause in line 9 remain byte-for-byte unchanged in the proposed guide edit.

| Source location | Requirement or claim | Candidate disposition |
|---|---|---|
| Minimum inputs, line 7 | Ask only for selected capabilities; authorized target/operator; independent repository/identity; access model/domain/account ownership; enable only selected integrations/devices | Retained verbatim in the same selected section |
| Minimum inputs, line 7 | Camera/GPS/site inputs are conditional; never collect SSH keys, tokens, OAuth secrets or vault passwords in chat; use secure setup/vault pointers; never copy source credentials, seed data or deployed paths as defaults | Retained verbatim |
| Minimum inputs, line 9 | Ask only for choices required by selected capabilities | Duplicate clause removed; identical obligation remains in line 7 and conditional rows 17-18 |
| Minimum inputs, line 9 | Retain confirmed non-secret answers and show assumptions for correction | Retained verbatim apart from sentence-initial capitalization |
| Table row 13 | Capability/household needs choose services and acceptance tests; no unnecessary personal history or advance collection of optional integrations | Retained verbatim |
| Table rows 14-16 | Authorized host before inspection/provisioning; independent identity before deployment paths/runner; account ownership before exposure/auth; no private keys/tokens/source seed defaults/secrets in chat | Retained verbatim |
| Table rows 17-18 | Only enabled integrations and required site data; secure provider-credential handling; no precise location committed to shared corpus | Retained verbatim |
| Minimum inputs, line 20 | Separate repository/domain/accounts/host; conditional GPS; provider pricing/setup requires current verification before purchase/account setup; no current pricing promise; source link | Retained verbatim |
| Clean-host limitations, line 50 | Existing support stack/external network; bind mounts/local Blink build; blank-host arrangement required; bootstrap is development-only; provisioning, accounts and deployment are separate | Entire section unchanged |
| Clean-host table, rows 54-57 | Bootstrap limitations; host role and external DNS/tunnel/OAuth steps; audit runner prerequisites; existing overlay/network assumptions; required production content/build context or supported capability omission | Retained verbatim |
| Clean-host table, rows 58-60 | Local override is not self-contained; initial model pull/digest verification required; instance-specific substitutions and browser endpoint check required | Retained verbatim |
| Clean-host limitations, line 62 | These are installation-engineering gaps; this documentation introduces no standalone overlay or provisioning executor | Retained verbatim |
| Acceptance record, lines 64-78 | Per-capability state/evidence ownership; independent resources; browser/auth; persistence; actual selected integration data; Ask evidence; controlled isolated recovery; restore rehearsal; no destructive live-cabin proof; NOT RUN status | Entire section unchanged |

The document heading, section headings, anchors, links, tables, role mappings and source references remain unchanged. References to `#minimum-inputs`, `#verified-clean-host-limitations` and `#acceptance-record` need no migration.

## Size calculation and scope of the evidence

The source reader joins LF-normalized lines and strips section-edge whitespace; the Java builder uses string character counts. Static extraction at the pinned source reproduces these section sizes. All involved characters are within the basic multilingual plane, so the calculated UTF-16 counts match Java string lengths. These are **design calculations**, not a candidate-builder run or model evaluation.

| Section or bundle | Current characters | Proposed characters |
|---|---:|---:|
| Minimum inputs | 2130 | 2074 |
| Verified clean-host limitations | 2430 | 2430 |
| Both required setup-limit sections | 4560 | 4504 |
| Acceptance record | 1486 | 1486 |
| Setup limits plus acceptance | 6046 | 5990 |
| Headroom under 6000 | -46 | 10 |

The checkpoint confirms current section sizes and actual selection/delivery, but it does not run this proposed text or selection candidate. The public development result remains 95/98 administrator and 5/5 household, with all three gaps still open.

Source guide LF-normalized SHA-256: `7d193a436585ae265d435dddf0c70f477f16b09d9d2d869362ebbc7c4676aff7`. The narrow margin makes a future whole-bundle regression essential: later prose growth must fail visibly rather than silently dropping acceptance. Do not truncate a section or remove a requirement to recover headroom.

## Selection decision required before any implementation

The existing no-score-workaround ruling remains in force. The design cannot mark the acceptance gap fixed without a separately approved selection remedy. One candidate for Cowork to assess is an **isolated, zero-boost category** for the qualified phrase "installation acceptance record", mapping the existing acceptance section. Its three-word score would tie `installation` at 3 for the exact question, preserving both setup-limit sections before adding acceptance. Adding that phrase to `recreation_acceptance` would accumulate with its existing phrase and score 5, displacing the limits; reject that approach.

This candidate is stated to make the dependency reviewable, not to authorize a new category or evade the prohibition. Cowork must explicitly approve, amend or reject it as a separate bounded selection design. If the ruling remains no selection change, recommend the text candidate only as a preservation improvement and **keep the delivery gap open**. No boost, declaration-order, matcher or global-budget change is proposed.

## Alternatives, limits and rollback

- **Keep current text:** no risk or implementation effort, but both selection and conditional capacity blockers remain. This is valid if preservation or selection approval is not obtained.
- **One-sentence condensation:** smallest affected surface, stable anchors, no moved prerequisites, 56-character saving. Recommended capacity candidate, conditional on independent preservation review. It leaves only 10 characters of margin and does not solve selection by itself.
- **Split the setup sections:** adds headings/reference ownership and selection complexity without removing the requirement to deliver all setup limits. Moving required text out of the delivered bundle would violate the ruling. Reject for this 46-character case.

The plan question's 953-character deficit is not solved by this candidate: even reusing the 56-character saving, its conditional bundle is 6897 characters, still 897 over budget. The checkpoint measures M04 as 4648 characters delivered plus a selected but omitted 1760-character section: 6408 total, a 408-character deficit. M04 receives no content or routing proposal here; its priority and remedy need a separate ruling. Both larger gaps remain behind the acceptance case for measurement/design ordering only.

For any later approved implementation, use one isolated PR and record the exact before and candidate heads. Reuse the real harness; prove the exact acceptance question gets all three complete source sections within 6000, every preserved claim remains, credential guidance stays whole, all six roles retain their boundaries, and unrelated selection/context changes are disclosed over the existing development/coverage inputs. Preserve 120 pins, stable expected fields, immutable baseline and mirrors. Add cross-product/negative phrasing checks for the actual proposed selection phrase. Only remove the one known-gap marker after the measured whole-bundle result passes independent review. Rollback restores the original sentence and any separately approved mapping together, then reruns the same checks.

## Requested ruling

Approve or reject the one-sentence capacity candidate; independently rule on whether the described qualified tie-selection candidate may be designed/implemented next. Code reviews preservation against its checkpoint, then Cowork makes the final decision. This proposal itself implements neither candidate. Estimated later implementation and focused validation: 20-30 minutes, plus independent review; no purchase, account, GPU or new credentials. A1 NOT_MEASURED; training NOT_RUN; release_eligible false. No model call, sealed material, grading, host or device action.
