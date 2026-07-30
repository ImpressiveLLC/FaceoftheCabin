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

---

## 2026-07-27 — Four-Role Architecture Review

_Multi-role review of the platform architecture brief completed before Claude Code handoff. Full brief in [`ROADMAP.md`](../ROADMAP.md)._

### CIO — Strategic & Operational Review ✓

The platform architecture is appropriately scoped for a family-scale self-hosted deployment. The M920q as a single-node host is a deliberate and reasonable trade-off — operational simplicity over HA. The Cloudflare Tunnel + Tailscale dual-layer network model is sound: public access for family surfaces, private mesh for admin.

**Highest-priority remediation:** `smrekar-platform` is not yet in version control. Push to GitHub from `ilikethelights` before any Claude Code work on that codebase begins.

Actions:
- Push `smrekar-platform` to GitHub immediately
- Register `unicornpingpong.com` at Porkbun
- Document M920q `docker-compose.yml` in CabinAutomations repo (currently local-only)
- Risk noted: Single point of failure on M920q — acceptable for now, revisit for redundancy

### Product Lead — User Experience & Feature Review ✓

The see/think/act model is the right product framing for the ontology. The Admin tab is a strong product decision — configuration without code is essential for long-term family adoption. The Kidde use case is the canonical demo scenario for the platform's discovery capability.

Key decisions:
- The `check_for_new` schedule must be user-configurable per entity from Admin UI — not YAML-only
- Phone PWA for Family Hub should be scoped early — kids checking chores on phone is a key engagement driver
- Ontology UI must use see/think/act vocabulary consistently — never expose `check_for_new` to family users; translate to "Discovery: Checking monthly"
- RulesEnginePanel reductive UI (Active→Reset, Recent→Undo, filterable by time) is the right pattern for non-technical users

### Data Architect — Schema, Lineage & FAIR Review ✓

The canonical entity schema is well-formed and extensible. Three required additions before implementation:

1. `ontology_version: "1.0"` at the ontology root level — migration tooling needs a version anchor
2. `reuse_count` must be a **computed** field, not manually maintained — derived from querying the automation graph at read time
3. `derived_from` must be a **DAG reference structure** — `{ entity_id, relationship_type, transformation }` — not a flat list. This enables horizontal trace across services.
4. Add `data_class` field per entity: `raw | derived | conceptual | composite`
5. Populate `search_aliases` generously including common misspellings — essential for LLM retrieval
6. Every automation producing a state change must write a lineage record: `{ from_event, via_automation, to_state, timestamp }`

### Ontology Lead — Knowledge Graph & Discovery Review ⭐

**What is right:**
- `check_for_new` as a first-class boolean on every entity is the right primitive — opt-in with clear default
- The Tech ID Service as a named platform service (not a cron job) elevates discovery to product-level concern
- Events as first-class ontology entities (not just schemas) means event schemas are versioned, discoverable, and traceable
- The Kidde use case perfectly illustrates the ontology's job: bridge "I'm interested in this" to "here is what you can actually do with your specific ecosystem"

**What needs strengthening:**
- The ontology needs a **relationship vocabulary**: `replaces`, `extends`, `complements`, `monitors`, `controls`, `notifies`, `derives_from`, `triggers`, `depends_on` — these enable horizontal trace
- Add **candidate entity type**: a device/service identified but not yet integrated. Prevents discovery findings from being lost
- Entities must version themselves — each entity needs `version + changelog` so the audit trail can answer "when did `camera.driveway` gain `new_api_available: true` and why?"
- **AI/RAG retrieval strategy decision pending:** start with YAML + embedding; migrate to graph DB when horizontal trace queries become complex

**Discovery check schedule by entity type:**
- Active devices → monthly
- Candidate entities → weekly (actively being evaluated)
- Services → quarterly
- Deprecated entities → never

**Ontology growth model:**
- Grows three ways: (1) manual addition by admin, (2) automated discovery by Tech ID Service, (3) implicit inference by AI layer from usage patterns
- Every growth event is itself an ontology event: `ontology.entity.created`, `ontology.entity.updated`, `ontology.entity.deprecated`
- **Append-only for history** — deprecated entities flagged, never deleted

**The ultimate vision (Ontology Lead final note):** The platform's long-term value is not its automations or its cameras or its family calendar. It is the accumulated knowledge graph of how this specific family's physical and digital environment works, what they've tried, what connects to what, and what they don't know yet. That knowledge graph — versioned, FAIR, AI-queryable, and actively maintained by the Tech ID Service — is what makes this platform irreplaceable. No commercial product can replicate it because it is built from their specific ecosystem, their specific devices, their specific history. That is the Northstar.

---

## 2026-07-30 — Family Hub: Family Notepad Overlay

### Spec

A scrolling family notepad overlay on the right edge of `family-hub.html`, docked next to the existing right-side interaction elements (`#chores-card`, `#dashboard-fab`, `#settings-btn`).

**Default state:** slid-in (collapsed). Shows a "last note received" indicator only — sender avatar/name, message snippet, relative timestamp.

**New note arrival:** default action is to slide out (expand) automatically, showing the last 12 message lines. This persists until either a user selects the slide-in control, or 24 hours have elapsed since the note arrived — whichever comes first. The 24-hour rule is a hard override: even if no user interacts with the control, the panel is forced back to slid-in once a note has been visible for 24 hours.

**Manual control:** any authorized (signed-in) user can slide in/out freely at any time, independent of the auto-expand/auto-collapse rules above.

**Sizing rule:** slid-out width = the width of the *largest* interaction-element box on the right side of the UI; slid-in width = the width of the *smallest* interaction-element box on the right side of the UI. Measured at runtime via `getBoundingClientRect()` against `#chores-card` / `#dashboard-fab` / `#settings-btn` (see `computeNotepadWidths()`), not hardcoded — so it stays correct as those elements' CSS changes per breakpoint.

**History:** slid-out view shows the last 12 messages. A "View old notes ▸" link opens a full scrollable history of the last 50 messages. Anything beyond 50 is dropped (oldest first). An inconspicuous caption near the history link confirms "The last 50 notes are saved."

### Implementation notes

Shipped as a self-contained addition to `family-hub/family-hub.html` (CSS + markup + vanilla JS), consistent with the file's existing single-file, no-build-step pattern. Storage is `localStorage` (`smrekar_family_notes` for messages, `smrekar_notepad_state` for open/collapsed + 24h-timer state), matching how chores, profiles, and settings already persist in this file.

**Known limitation:** because storage is `localStorage`, notes and open/collapsed state are per-browser/per-device, not synced across family members' phones or the wall display. There is no backend note-sync surface yet (no `/api/notes` endpoint on `cabin-backend`). Acceptable for v1 since the display is primarily a single kiosk surface; revisit if/when the Family Hub is meant to be a true multi-device shared notepad — that would need a `/api/notes` endpoint + polling or WebSocket push, analogous to the existing device/event architecture.
