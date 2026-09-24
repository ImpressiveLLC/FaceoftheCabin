# Decisions

The ratified D-decision record lives in [`ontology-decisions.md`](ontology-decisions.md), imported verbatim from the Living Ontology artifact on 2026-09-23.

## Status vocabulary

Each decision carries exactly one status. These are the only allowed values.

| Status | Meaning |
|---|---|
| `flagged` | A gap or question is recorded; no proposal yet |
| `proposed` | A resolution is written and awaiting review |
| `implemented — awaiting ratification` | Code shipped it; Cowork has not ruled |
| `ratified` | Cowork accepted it; Nate did not object |
| `superseded by Dn` | Replaced; the replacing decision is named |

## Index

| ID | Title | Status as of import | Section |
|---|---|---|---|
| D1 | Identity | ratified | [link](ontology-decisions.md#d1--identity) |
| D2 | Observation Model | ratified | [link](ontology-decisions.md#d2--observation-model) |
| D3 | Formalism | ratified | [link](ontology-decisions.md#d3--formalism) |
| D4 | Governance | ratified | [link](ontology-decisions.md#d4--governance) |
| D5 | KB Freshness | ratified | [link](ontology-decisions.md#d5--kb-freshness) |
| D6 | Multi-location | ratified | [link](ontology-decisions.md#d6--multi-location) |
| D7 | Sensor Naming & Reporting Context | ratified | [link](ontology-decisions.md#d7--sensor-naming--reporting-context) |
| D8 | Zigbee Runtime & Catalog Strategy | ratified | [link](ontology-decisions.md#d8--zigbee-runtime--catalog-strategy) |
| D9 | Tiny Helpdesk KB Policy | ratified | [link](ontology-decisions.md#d9--tiny-helpdesk-kb-policy) |
| D10 | External Platform Import Pipeline | ratified | [link](ontology-decisions.md#d10--external-platform-import-pipeline) |
| D11 | Family Hub / Cabin Orchestration Boundary | ratified | [link](ontology-decisions.md#d11--family-hub--cabin-orchestration-boundary) |
| D12 | Guest Access & Non-Google Auth | ratified; route-shape deviation under conditional ruling ([SO-2026-09-23-r1](../signoffs/SO-2026-09-23-r1.md) §3.1) | [link](ontology-decisions.md#d12--guest-access--non-google-auth) |
| D13 | Service-Level Data Lineage | ratified | [link](ontology-decisions.md#d13--service-level-data-lineage) |
| D14 | Kiosk Auth Model — Public Read Tier + CabinSession | ratified | [link](ontology-decisions.md#d14--kiosk-auth-model--public-read-tier--cabinsession) |
| D15 | Energy Device Ontology | ratified | [link](ontology-decisions.md#d15--energy-device-ontology) |
| D16 | Reporting Topics IA | ratified | [link](ontology-decisions.md#d16--reporting-topics-ia) |
| D17 | Place/room graph edge; `data_class: product` lineage tier | implemented — awaiting ratification | [Open Pins](ontology-decisions.md#open-pins) |
| D18 | Camera-derived media entity naming + download/access-log provenance | proposed | [Open Pins](ontology-decisions.md#open-pins) |
| D19 | Local telemetry backup / log-shipping for `cabin_event` hot tier | implemented — awaiting ratification (A+B deployed 2026-09-14) | [Open Pins](ontology-decisions.md#open-pins) |
| D20 | Live MQTT tile: read-only SSE relay | implemented — awaiting ratification (deployed 2026-09-14) | [Open Pins](ontology-decisions.md#open-pins) |
| D21 | Device self-discovery lookup: keyless web search | ratified | [Open Pins](ontology-decisions.md#open-pins) |

D-numbering note: the retired claude.ai project copy (`ontology/cabin-ontology-decisions.md`, 2026-09-05) numbers Energy as D14 and Reporting Topics as D15. This record is authoritative: D14 is Kiosk Auth, D15 Energy, D16 Reporting Topics. Logged as [DL-2026-09-23-03](../discrepancy-log.md).

## How a decision changes

1. Open a PR editing `ontology-decisions.md` (or the per-decision file after DOC-2). State the status transition in the PR title.
2. Include the governance record from [definition-of-done.md](../definition-of-done.md#governance-record-required-on-every-pr).
3. Cowork rules in a review comment and, when the ruling gates a release, in a sign-off.
