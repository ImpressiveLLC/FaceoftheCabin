# B2 ratification and Cabin enrollment decisions

Recorded 2026-11-27 by Codex under W-168/W-167. Authority: [Cowork's record of Nate's 12:40 CST decisions](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-7225748072). This adopts the decisions below; it does not attest uncollected source or live evidence. No new governance ID is allocated.

## B2: ratified

Nate approved all three recommendations in the [B2 decision packet](../proposals/2026-11-27-ontology-readiness-decisions.md):

1. Threshold profiles remain separate, versioned records referenced by ontology measurement rows. Preserve stable IDs, applicability, source evidence and approval state. No numeric threshold or source applicability is ratified merely by choosing this representation.
2. Derived inputs default to explicitly bound services from the same physical parent. Cross-source joins require explicit bindings and freshness bounds, with source observation identity/time retained in lineage. Missing age/skew policy, stale/missing observations or ambiguous binding yields unavailable; no implicit nearest/newest-source fallback.
3. CO concentration and CO alarm state remain distinct semantics. Never infer numeric concentration from an alarm state, turn clear into zero, relabel CO as CO2 or change stable IDs without a reviewed compatibility migration.

These replace the packet's pending B2 disposition. B1/B3 may proceed through their remaining review gates; the existing draft is not automatically an executable policy bundle. The Sensirion SEN6x, AirNow, WMO and Vaisala source claims still require the independent verification pass before B1 measurement rows land. No formula, threshold, freshness limit, real binding or live commissioning evidence is invented by this ratification.

## C2: Cabin aurora enrollment default

Nate selected **ON at Cabin enrollment** for the aurora ExternalFeed tile. This replaces the draft's off-at-enrollment proposal and open question. In the C2 implementation, enrollment at Cabin initializes inclusion on; subsequent explicit user choices must be preserved. It does not enroll Home, create a currently unsupported tile, override eligibility or bypass an alarm-visibility floor.

The existing client projection remains eligibility authority. Provider/entity mapping, observation/forecast/fetch-time provenance, location binding, stale/unavailable behavior and the final tile presentation still need the C2 slice and its acceptance evidence. This record changes the enrollment default; it does not claim aurora was enrolled or verified live today.

## C1 and run authority

Nate raised no objection to the three-PR C1 split or deterministic server-order bootstrap, and approved shared-preferences activation through the normal reviewed UI deployment path. The necessary Docker/Compose build-flag connection must exist before activation. Enabled-mode acceptance must cover both locations; roll back on hidden alarm tiles, incorrect order or write failures. Default-off code delivery in #621/#622/#623 is distinct from activation and live acceptance.

Nate separately authorized run 8. Nate alone launches it from the [pinned packet](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-7225805132); this is not authority for an agent to start training, alter rung rules or change the host. Run-7 container closure is Nate-supplied evidence recorded in [7225509643](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-7225509643). No run-8 outcome exists in this record.

## Remaining gates

Independent exact-head review, applicable tests, deployment verification and source applicability still apply. W-84/D29 commissioning and any other named live-source holds are not waived by B2. This record adds no measurement rows, runtime binding, source enrollment, formula change or host operation. The enrollment default belongs in C2 implementation; the C1 build flag belongs in its reviewed deployment configuration change.
