# HA Operations Console: optional presentation proposal

Status: proposed, not ratified or scheduled. Updated 2026-09-18.

## Provenance and purpose

This document reconstructs the proposal described in the **Identify HA Theme Source** conversation from its recoverable decisions. The earlier downloadable Markdown artifacts were not available through the conversation reference or the searched local files; this is not asserted to be a verbatim recovered artifact. Repository alignment was rechecked against main `e708946e863e6336bdf112d36179479cfd02e860`.

The primary intent is **“How's the cabin?”**: understand current conditions, notice exceptions, understand why they matter, and reach the existing safe next step. This is an optional Home Assistant presentation surface, not a committed redesign or replacement of Cabin UI or Family Hub. Its value must be demonstrated through comprehension, exception handling, reuse and governance, rather than aesthetic novelty.

## Existing contracts to preserve

- [Family Experience Journey Standard](../../UX_JOURNEY_STANDARD.md): **See → Think → Act → Recover**, useful unavailable states, visible return paths, keyboard operation and mobile access.
- [Product notes](../../PRODUCT_NOTES.md) and [product vision](../../PRODUCT_VISION.md): human intent, context and action remain connected.
- [Existing theme and camera execution plan](../../EXECUTION_PLAN_2026-08-07_template-theme-camera.md): reuse intent-bearing `?panel=CAMERA_EVENTS` navigation and cross-app `?theme=` handoff; preserve caller context.
- [ThemeProvider](../../../cabin-orchestration-platform/ui/src/ThemeProvider.jsx) and [Family Hub](../../../family-hub/family-hub.html): retain current theme IDs and preference behavior. The Operations Console is a separate candidate; this PR's retro-theme adjustments do not implement it.
- [Ontology](../../ontology.yaml) and [WSJF backlog](../wsjf-backlog.md): reuse upstream identity, configuration, roles, lifecycle and safety meanings. Proposed ontology entries remain proposals until ratified.

## Use cases and interaction design

| Intent and context | See / Think | Act / Recover |
|---|---|---|
| Household checks the cabin remotely | Overall state plus last update; distinguish healthy, unknown, stale, privacy-hidden and unavailable | Open the existing relevant cabin view without replacing the Family Hub context; retry or explain missing configuration |
| Operator investigates an exception | Highest-priority exception, location, source timestamp and supporting evidence; do not imply causation from a color alone | Deep-link to existing diagnostics or a reviewed procedure; preserve selected location and return path |
| Person checks environmental conditions | Current measurement, unit, freshness and governed threshold context | Open details; missing data is explicit rather than zero or healthy |
| Authorized operator needs an action | Explain permission, expected effect and availability using the existing action contract | Initially link to existing controls only; later in-console actions require separately reviewed authorization, confirmation, receipt and recovery |

The first delivery is read-only. Safety-critical actions, especially privileged valve reopen, must never be introduced as generic HA toggles. Manual-only and role restrictions remain enforced upstream; hiding a button is not authorization. No household exposure of credentials, private camera content or administrative topology.

## Visual and responsive specification

Use the supplied dark dashboard screenshot as the primary visual reference: near-black surface, restrained borders, thin separators, regular sans-serif text, clear hierarchy, prominent numerical values and small section labels. The precise source of that screenshot was not established. The earlier discussion identified [Mattias Persson's Lovelace configuration](https://github.com/matt8707/hass-config) as an architectural reference, not a verified exact match or an implementation to clone wholesale.

- **Top strip:** compact infrastructure freshness/health summary. Name each state; keep technical detail behind an operator drill-down.
- **Left rail:** location and intent navigation, with the current context always visible.
- **Center:** dominant cabin state and exception-first content. Healthy details recede; unknown data must not look healthy.
- **Right rail:** contextual environmental/security telemetry with units and timestamps; respect privacy permissions.
- **Wall/desktop:** three regions where space permits. **Tablet:** condense navigation and move secondary telemetry below the main region. **Phone:** one reading order, exception and next step first; collapse secondary sections with accessible headings and `aria-expanded`.
- No color-only meaning. Green denotes nominal, amber attention, red fault/destructive and cyan information, as mapped from governed states. Typography, labels and icons also convey status. Retro theme accent choices never redefine operational state.
- Target at least 44 CSS pixel touch controls, visible focus, readable contrast and 200% zoom. Motion is limited to informative transitions and honors reduced-motion preferences. Avoid decorative pulsing, nested scroll traps and hover/long-press-only controls.

## Implementation and configuration pattern

**Home Assistant owns presentation, not meaning.** Use a small reusable template layer, responsive CSS grid, conditional content and accessible detail dialogs. Evaluate `custom:button-card`, layout-card and card-mod against the actual deployed HA version during HA-UX0; compatibility and availability are not claimed by this proposal. Avoid a collection of individually styled, duplicated cards.

Map each visible item to an existing canonical entity and reviewed state contract. The mapping record should document identity/reference, location, label, unit, source/freshness semantics, authorized audience, severity source and existing detail/action destination. These are design requirements, not a new API schema or a license to invent entity IDs. Capture missing mappings as explicit gaps before coding.

Keep device discovery, thresholds, severity calculation, orchestration, role derivation and ontology upstream. Do not add a second automation engine in Lovelace. Use the repository's existing host/configuration conventions; do not hardcode deployment addresses, credentials, household identities or screenshot example entities. Unconfigured, denied, stale and disconnected states need independent acceptance cases.

## Independently scoreable delivery slices

| ID | Scope / deliverable | Dependency | Acceptance evidence |
|---|---|---|---|
| HA-UX0 | Inventory journeys, state contracts, permissions, current HA version and reusable template feasibility; record mapping gaps | None | Reviewed mapping matrix, baseline task observations and no invented entities or duplicated policy |
| HA-UX1 | Read-only overview with top strip, exception hero and telemetry | HA-UX0 | Healthy/stale/unknown/offline/privacy cases; timestamps and labels; no device commands |
| HA-UX2 | Context-preserving navigation, details and return paths | HA-UX1 | Existing panel/theme deep links; keyboard/touch operation; denied/unconfigured destinations recover usefully |
| HA-UX3 | Governed action entry points and receipts, only if separately authorized | HA-UX2 plus reviewed action contracts | Server-side role enforcement, confirmation, denial, failure/retry and manual-only safety restrictions; no duplicate execution |
| HA-UX4 | Reusable configuration/template packaging and drift checks | HA-UX1 mapping stable | Add a representative location using configuration; no copied business rules or secrets; document gaps |
| HA-UX5 | Responsive, accessibility and operational comprehension validation | HA-UX1; retest HA-UX2–4 as delivered | Wall/tablet/phone, zoom, keyboard, reduced motion, contrast, unavailable-state recovery and observed family task outcomes |

HA-UX0 precedes HA-UX1. Baseline accessibility and responsive behavior are required in every slice; HA-UX5 supplies broader validation rather than deferring basic usability. HA-UX3 is optional and must not block a useful read-only console.

## WSJF and decision gate

Apply the backlog's existing formula: `CoD = 2 × Safety + Usability + Extensibility + Time`, `WSJF = CoD / Job Size`; value dimensions 1–10, size 1/2/3/5/8/13. Score each slice after HA-UX0's evidence review. No score, delivery date, ratification or priority above C1–C4 is fabricated here. Record baseline and target for time to understand cabin state, correct recognition of stale/unknown states, steps to the relevant view, and successful recovery from unavailable service.

Proceed only when the mapping is reviewed, the outcome is worth the estimated size, and a named human owner authorizes the slice. Review code/configuration through PRs. This document grants no merge, deployment, live-device operation or HA configuration authorization.
