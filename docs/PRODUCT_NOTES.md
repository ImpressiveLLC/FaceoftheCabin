# FaceoftheCabin — Product Notes

> Standing record of product decisions, design rationale, and user research.
> Not a README. Not a spec. The "why" behind what gets built.
> Append-only — decisions are dated and attributed, never overwritten.

---

## 2026-07-26 — Foundational Session

### North Star Principal: See → Think → Act

Every feature decision is evaluated against a single interaction model:

- **See** — the right information is visible at the right level of detail without requiring navigation
- **Think** — context is sufficient to understand what the information means and whether action is needed
- **Act** — the correct action is reachable in one or two taps from the point of understanding

This is not a UI pattern. It is a product contract. A feature that enables seeing but not acting is incomplete. A feature that enables acting without seeing is dangerous.

---

### Audience Personas (from user test group session)

**Mom** — casual user, one or two properties, wants things to work, does not want to learn the platform. Success means she never sees a technical term, never sees a snake_case identifier, and always knows what to do next. Her primary interaction is a notification → one action.

**Tech Analyst** — manages two properties including aging family members' homes. Watches both locations on the same platform. Needs operational metrics (battery %, last_seen age, signal quality) visible without drilling down. Needs action receipts for accountability with caregivers and service providers. Lives the See→Think→Act model in reverse (acts on notification, then needs context to validate the action was right).

**Insurance Adjuster** — evaluates risk and liability. Requires: alert delivery receipts, sensor confidence indicators, consequence-language on destructive actions, and an immutable audit log. Promoted audit log from Phase 2 to core requirement. Silence/bypass actions must include disclosure copy — not optional.

**Technician** (platform owner/developer) — needs full technical view including raw identifiers, IEEE addresses, Z2M friendly names, firmware versions. The only audience that should ever see snake_case names or protocol-layer identifiers.

---

### Ontology Decision

All named things in the platform — hardware identifiers, MQTT topics, API fields, UI labels, infrastructure hostnames, code constructs — are data elements with definitions, layer assignments, primitive/derived flags, and audience-specific rendering contracts.

The ontology lives in `docs/ontology.yaml`. It is:
- Layer-agnostic (describes hardware through UI through infra)
- Git-tracked (changes are versioned and reviewable)
- Machine-parseable (will power the data dictionary UI and formatAttribute utility)
- The authoritative source for the data dictionary help UI

It is NOT a database schema, a frontend data model, or a backend API contract. Those are derived from it.

**Key principle from session**: `z2m_friendly_name` (e.g. `door_second_contact`) is a primitive element constrained to snake_case by MQTT protocol. It is invisible to all non-technical audiences. `display_name` is derived from it and is what every other audience sees. The snake_case constraint is a protocol fact, not a product decision.

---

### Name Management Decision

**Z2M owns hardware identity.** Friendly names in Z2M are protocol primitives — snake_case, immovable at the MQTT layer.

**FaceoftheCabin owns display identity.** A device metadata layer in Postgres holds: `displayName`, `room`, `photoUrl`, `installNotes`, `expectedBehavior`. This is set once at install time and editable anytime.

**At first device discovery**, the platform prompts: "Give this device a name and take a photo of where you installed it." The prompt defaults to a cleaned version of the friendly_name (underscores → spaces, title case). The user can accept or change it. This is the only moment snake_case appears in a non-technical user flow, and only as a pre-filled hint.

**Photo fallback hierarchy**: user install photo → spec sheet image from Z2M device database (keyed by model+vendor) → generic device type icon.

**Semantic layer toggle**: all data elements render per audience context (semantic / systems / technical / compliance). Persistent in localStorage. Default is semantic for new users. This is a progressive disclosure mechanism, not a filter — technical view adds information, never removes it.

---

### Action Model Decision

All device commands route through **Home Assistant**, not direct Z2M MQTT. Rationale: HA is the system of record for device state, automations are aware of manual commands, and the action is logged in HA's own history in addition to the FaceoftheCabin audit log.

The single new backend surface: `POST /api/devices/{deviceId}/command` with body `{"command": "domain.service", "payload": {...}}`.

**Destructive/safety actions require consequence-language confirmation dialogs.** Examples:
- Silence alarm: *"Silencing this alarm means water damage may go undetected. The sensor will not alert again until re-armed."*
- Unlock door: *"This will unlock the front door remotely. Confirm you intend to grant access."*
- Bypass sensor: *"This sensor will stop alerting until you re-arm it. Monitor the situation manually."*

This requirement is non-negotiable. Source: adjuster persona, user test group session 2026-07-26.

**Audit log is a core requirement**, not Phase 2. Every command, state change, alert, and notification delivery is appended with timestamp, actor, target device, action, payload, and outcome. Immutable — append only. Required before the command endpoint ships.

---

### Detail Sheet Interaction Model

Tile tap → sheet slides up over dashboard (preserves context). Three zones:

1. **Header** — display name, room, device type, current state badge, install photo thumbnail
2. **Live metrics** — primary reading large (temperature, contact state, motion), secondary row (battery, signal, voltage, last_seen with staleness color)
3. **Actions** — device-type-aware action buttons, each with appropriate confirmation copy. Escape hatches at bottom: "Open in Z2M ↗" and "View in HA ↗"

Last_seen staleness coloring on the tile itself (not only in sheet): green < 1hr, yellow 1–6hr, red > 6hr. Battery badge on tile at < 20%.

Sheet loads with cached data instantly, refreshes from API in background. No per-device WebSocket in Phase 1 — 5-second poll on open sheet.

---

### Build Order (current)

1. Z2M availability topic handling → device online state (DONE 2026-07-26)
2. Temperature/humidity KPI tiles (DONE 2026-07-26)
3. Device metadata table (Postgres migration) + displayName/room/photoUrl fields
4. First-discovery enrichment prompt in UI
5. formatAttribute(key, value, roleContext) utility — semantic rendering layer
6. DeviceDetailSheet component — three-zone layout, renders through semantic layer
7. POST /api/devices/{deviceId}/command endpoint + HA adapter routing
8. Audit log table + append on every command
9. Action buttons per device type with confirmation copy
10. Tile metadata layer — battery badge, last_seen staleness indicator, location badge
11. Semantic layer toggle (localStorage, settings panel)
12. Data dictionary UI (/settings/dictionary, sourced from ontology.yaml)
13. Alert delivery receipts (HA notification + email/SMS fallback)
14. QA orchestration — Node-RED schedule, POST /api/qa/results, QA status tile

---

### On Naming READMEs

- `cabin-orchestration-platform/README.md` — technical onboarding for developers. Stack, ports, how to run, architecture diagram. Already written.
- `docs/PRODUCT_NOTES.md` — this file. Product decisions and rationale. Audience: product lead, stakeholders, future contributors who need to understand why, not just what.
- `docs/ontology.yaml` — semantic contract. Audience: anyone building on or extending the platform.
- `CLAUDE.md` — AI assistant context. Machine and human workflow instructions. Not a product document.
- A future `docs/DEPLOY.md` already exists covering operational deployment.

Each document has one job and one audience. They do not duplicate each other.
