# Smrekar Platform — Strategic Roadmap & Architecture Brief

> **Prepared:** 2026-07-27  
> **Review checkpoint:** 2026-08-27 _(30 days — reassess phase status, update discovery flags, verify unicornpingpong.com tunnel)_  
> **Audience:** Claude Code handoff · CIO · Product Lead · Data Architect · Ontology Lead  
> **Source:** Architecture Brief v1.0 · unicornpingpong.com · Multi-role review completed 2026-07-27

---

## Northstar Goals

1. Unified family experience across cabin display, home machine, and phone via single domain (`unicornpingpong.com`)
2. All elements and attributes must have a rationale supporting user-centric design principles: **See → Think → Act**
3. Living ontology as the platform's primary differentiator — every data point is findable, traceable, actionable across all platform verticals and horizontal architecture and data derivatives
4. FAIR data principles throughout: Findable, Accessible, Interoperable, Reusable — enabling AI/RAG/LLM at any layer
5. Event-driven architecture: camera → detection → normalized event → automation → action, with full audit trail
6. Self-improving discovery: the platform actively monitors for new integrations based on ontology definitions for any non-deprecated device or service; scheduled rechecks via online resources including APIs, webhooks, and alternatives for owned devices; proactively notifies the owner of alternatives or new comparable products and services

---

## Product Architecture

### 3.1 Family Hub — `hub.unicornpingpong.com`

- Ambient full-screen display: clock, parenting schedule, Google Calendar events, Google Photos slideshow
- Kids' chore tracking with per-child reward progress (Sam age 9, Emma age 6)
- Parenting schedule logic: March 13 2026 anchor, 14-day cycle, kids-home-days: `[0,1,4,5,8,9,12,13]`
- Admin tab: edit rewards, chore list, reward rules (min chores/night, qualifying nights/week) — no code required
- Google OAuth via GSI token client — owner: `nhsmrekar@gmail.com`, calendar/photos: `smrekarfamilia@gmail.com`
- **Known resolved:** overlay z-index stacking context bug — backdrop-filter on `#dashboard-overlay` compositing conflict. Fix: settings-btn/panel moved to end of body (DOM paint order fix)
- **Pending:** PWA manifest for phone install; offline clock/chores fallback

### 3.2 FaceOfTheCabin — `cabin.unicornpingpong.com`

Conceptually unified cabin intelligence service, technically decomposed:

| Container | Role |
|-----------|------|
| `faceofthecabin-api` | REST + WebSocket gateway |
| `faceofthecabin-automation` | Rules engine, YAML ontology consumer |
| `faceofthecabin-camera-adapter` | Normalizes Frigate detection events to platform event schema |
| `frigate` | AI object detection on camera streams |
| `mediamtx` | RTSP stream management |
| `mosquitto` | MQTT broker / event bus |
| `node-red` | Flow automation and integration glue |
| `home-assistant` | Device state, UI overrides, appliance integrations |
| `zigbee2mqtt` | Zigbee sensor ingestion (14 devices paired) |
| `sensor-adapters` | Bosch dishwasher, LG ThinQ washer, Liebherr fridge |

### 3.3 Shared Platform Services — `api.unicornpingpong.com`

Spring Boot backend + React frontend (lives in `FaceoftheCabin` — `cabin-backend` + `cabin-orchestration-platform/ui`):

- Identity and roles (Google OAuth, `nhsmrekar@gmail.com`)
- YAML ontology — canonical entity definitions, naming contract, discovery flags
- Event bus — MQTT / Mosquitto, normalized platform event schema
- Notifications — push to dad's phone, alert routing
- Audit/history — event log, clip index, state change trail
- API gateway — single entry point for all platform services
- Common UI shell — DeviceManagerPanel, RulesEnginePanel (reductive status-first UI), MonitoringPanel

### 3.4 Infrastructure

| Component | Detail |
|-----------|--------|
| Domain | `unicornpingpong.com` (Porkbun ~$10/yr) |
| DNS | Cloudflare free tier — nameservers from Porkbun |
| Public access | Cloudflare Tunnel (`cloudflared` container on M920q) — bypasses Starlink CGNAT |
| Private mesh | Tailscale — cabin ↔ home, admin/SSH. M920q stable IP: `100.77.44.113` |
| Git | `nhsmrekar` + `smrekarfamilia-sudo` GitHub accounts, `ImpressiveLLC` org for shared repos |
| Monitoring | Uptime Kuma + Homepage on M920q |
| Primary server | Lenovo M920q · Ubuntu · Docker Compose at `/storage/containers/compose/cabin/` |

---

## Event Pipeline — Camera → Action

```
Camera (Reolink PoE, RTSP via MediaMTX)
  ↓
Frigate / detection service  →  raw detection event
  ↓
faceofthecabin-camera-adapter  →  normalized platform event
  ↓
MQTT event bus  (topic: platform/events/presence.detected)
  ↓
faceofthecabin-automation  →  evaluates rules against event
  ↓
Actions: notify / retain_clip / update_state / alert / dashboard update
```

**Design principle:** Frigate owns detection. FaceOfTheCabin owns what to do about it. The normalized event is the handshake. Keep capability together, separate the containers.

### Canonical Event Schema

```yaml
event:
  type: presence.detected
  source: camera.driveway
  location: cabin.exterior.driveway
  timestamp: 2026-07-27T14:22:00Z
  attributes:
    object_type: person
    confidence: 0.91
    direction: approaching
```

### Canonical Automation Schema

```yaml
automation:
  id: driveway_arrival
  trigger:
    event_type: presence.detected
    source: camera.driveway
  conditions:
    - attribute: object_type
      equals: person
    - state: cabin.occupancy
      equals: vacant
  actions:
    - notify: dad
    - retain_clip:
        duration_before: 20s
        duration_after: 60s
    - update_state:
        entity: cabin.security
        value: attention_required
```

---

## Ontology — The Special Sauce

> **Northstar:** The ontology is the platform's primary differentiator. Every data point — primitive, derived, conceptual, or searched — must be findable, traceable, and actionable from anywhere in the platform by any user, service, or AI agent.

The ontology is not a schema registry. It is a **living, self-extending knowledge graph** that answers four questions for every entity:

1. **What is this?** (definition, type, lineage, origin)
2. **Where does it come from and where does it go?** (provenance, lineage, upstream/downstream trace)
3. **What can I do with it?** (available actions, integrations, automation hooks)
4. **What workarounds exist?** (DIY hacks, Reddit tricks, custom integrations, OCR workarounds, audio detection fallbacks — non-standard integration paths)
5. **What update schedule keeps this current?** (discovery gap checks, deprecation watches, new context schedules per entity)
6. **What might be possible that I don't know about yet?** (discovery flags, new service checks, alternative paths)

### Entity Types

| Type | Examples |
|------|---------|
| Primitive data elements | `temp_mech_room`, `camera.driveway.frame` |
| Derived elements | `cabin.occupancy` (derived from `door_front_contact` + `motion_entry`) |
| Conceptual attributes | `cabin.security.status` (from camera + motion + door sensors) |
| Devices | Reolink RLC-820A, SONOFF SNZB-05P |
| Services | Frigate, Google Calendar, Kidde cloud API |
| Automations | First-class ontology entities with trigger/condition/action graph |
| Events | Typed, versioned entities with schema and lineage |
| Locations | `cabin.exterior.driveway`, `cabin.interior.mechanical_room` |
| **Candidates** | Discovered-but-not-yet-integrated devices (e.g. Kidde CO alarm before purchase) |

### Canonical Ontology Entry Schema

```yaml
ontology_entry:
  id: camera.driveway                      # snake_case canonical ID
  type: device.camera
  label: "Driveway Camera"
  description: "Reolink RLC-820A PoE, exterior driveway"
  location: cabin.exterior.driveway
  status: active                           # active | deprecated | candidate
  data_class: raw                          # raw | derived | conceptual | composite
  version: "1.0"
  changelog:
    - date: 2026-07-01
      change: "Initial registration"

  # Lineage (DAG reference structure)
  produces:
    - event: presence.detected
    - stream: rtsp.driveway
  consumed_by:
    - service: frigate
    - automation: driveway_arrival
  derived_from: []                         # raw device, no upstream
  # DAG format when applicable:
  # derived_from:
  #   - entity_id: motion_entry
  #     relationship_type: triggers
  #     transformation: presence_inference

  # Relationship vocabulary
  # replaces | extends | complements | monitors | controls | notifies
  # derives_from | triggers | depends_on
  relationships:
    - type: complements
      entity: frigate
    - type: depends_on
      entity: mediamtx

  # Integration state
  integration:
    protocol: rtsp
    adapter: faceofthecabin-camera-adapter
    poe_switch: tp-link-sg605p.port_1
    api_available: false
    local_only: true

  # Discovery flags
  discovery:
    check_for_new: true
    check_schedule: monthly              # weekly | monthly | quarterly | annually
    last_checked: 2026-07-01
    new_alternatives: false
    other_options: false
    new_api_available: false
    deprecated: false
    notes: "Watch for Reolink HomeKit or ONVIF profile expansion"

  # FAIR metadata
  fair:
    findable:
      tags: [camera, security, exterior, reolink, poe, presence]
      search_aliases: [driveway, front camera, outside camera]
    accessible:
      roles: [admin, parent]
      ui_surfaces: [cabin.dashboard, family.hub, ha.lovelace]
    interoperable:
      protocols: [rtsp, mqtt, frigate-api]
      schema_version: "1.0"
    reusable:
      reuse_count: computed               # derived from automation/service reference graph
      shareable: false
```

### Discovery Flag Schedule by Entity Type

| Entity Status | check_schedule |
|--------------|----------------|
| `active` devices | monthly |
| `candidate` entities | weekly (actively being evaluated) |
| services/APIs | quarterly |
| `deprecated` entities | never |

### The Tech ID Service

A background platform service that:

- Maintains a registry of all ontology entities with `check_for_new: true`
- Runs scheduled checks (web search + vendor API polling + community feeds like HACS) per entity schedule
- Updates discovery flags in the ontology when new information is found
- Publishes `ontology.entity.updated` platform event when flags change
- Surfaces discoveries as actionable notifications: _"Reolink released a new webhook API — 2 of your devices may benefit"_
- Grows the ontology by identifying candidate entities from browsing context, device purchases, and integration logs

**Ontology growth events:** `ontology.entity.created` · `ontology.entity.updated` · `ontology.entity.deprecated`
**Ontology is append-only for history** — deprecated entities are flagged, never deleted.

### The Kidde CO Alarm — Canonical Discovery Use Case

> _This is the platform's canonical demo scenario for the Tech ID Service._

1. User browses kidde.com — browser extension or tab context captures product interest signal
2. Tech ID Service queries ontology: finds 2 Kidde devices already registered
3. Service checks Kidde's current API surface, developer portal, IFTTT/webhook listings — finds new cloud API released Q1 2026
4. Ontology queried for integration paths: official API (new, available), local Zigbee (no native support), camera OCR (viable), audio trigger (viable)
5. Platform generates structured response: _"Here are your options — ranked by integration quality and your existing ecosystem"_
6. _"You already have 2 Kidde devices. A new Kidde API is available that could unify all 3. Alternatively, a camera pointed at its display can read CO levels. Your existing Frigate setup already supports both camera OCR and audio classification — no new hardware needed for fallback paths."_
7. Discovery result written back to ontology: `new_api_available: true`, `other_options: true`, complementary devices listed

### See · Think · Act — User Interaction Model

Every data point must support this three-state interaction from any UI surface:

**See**
- Any primitive, derived, or conceptual element is visible with current value, source, and last-updated timestamp
- Lineage is one click away: "where did this come from?"
- Usage is one click away: "what uses this?"

**Think**
- Every entity shows its discovery status: `check_for_new`, `last_checked`, `new_alternatives`, `other_options`
- AI/RAG layer answers natural language: _"what else can I do with cabin.security.status?"_
- Cross-entity reasoning: _"these 3 sensors could combine into cabin.water_risk_level"_

**Act**
- Every entity has contextual actions based on its type and current state
- Actions are ontology-driven — not hardcoded in UI, derived from the entity's action surface
- New discovery actions: "Investigate Kidde API", "Add as candidate integration", "Schedule for next Tech ID check"
- Admin actions are separate from family-facing actions — roles enforced at the ontology layer
- `check_for_new` schedule is user-configurable per entity from Admin UI — not YAML-only

### FAIR Principles Applied

| Principle | Implementation |
|-----------|---------------|
| **Findable** | Canonical snake_case ID, human label, tags, `search_aliases`. Full-text search returns entity + action surface |
| **Accessible** | Roles per entity (`admin`, `parent`, `child`). UI surfaces listed. API via platform gateway. No orphaned data |
| **Interoperable** | Protocols declared per entity. Schema versioned. All adapters normalize to same event schema |
| **Reusable** | `reuse_count` computed from automation graph. Derived entities reference source lineage. Automations parameterized by entity ID |

### AI/RAG Readiness

- Each entity is a self-describing document — ideal for embedding and retrieval
- Event log is a structured time-series corpus — LLM can answer _"what happened at the cabin last Tuesday night?"_
- Discovery flag updates are events — RAG can answer _"what has the Tech ID Service found recently?"_
- Natural language automation authoring: _"notify me when someone approaches the cabin after dark when we're away"_ → ontology resolves entities, generates YAML automation draft
- **Retrieval strategy decision (pending):** start with YAML + embedding, migrate to graph DB (e.g. Neo4j) when horizontal trace queries become complex

### Ontology Root Structure

```yaml
ontology_version: "1.0"          # Add this — migration tooling needs a version anchor
# reuse_count: computed           # Never manually set — derived from automation/service ref graph
# derived_from: DAG structure     # { entity_id, relationship_type, transformation }
```

---

## Priority Task List

> Ordered by dependency and impact.

### Phase 1 — Foundation

- [x] Fix Family Hub overlay z-index bug
- [x] All platform code unified in `FaceoftheCabin` (`smrekar-platform` deprecated)
- [ ] **[BLOCKER]** Push current M920q `docker-compose.yml` to CabinAutomations repo (currently local-only at `/storage/containers/compose/cabin/`)
- [ ] Register `unicornpingpong.com` at Porkbun, add to Cloudflare free tier
- [ ] Add `cloudflared` container to M920q docker-compose, configure tunnel to `hub/cabin/api` subdomains
- [ ] Update Google OAuth authorized origin from `http://127.0.0.1:5500` to `https://hub.unicornpingpong.com`
- [ ] Family Hub PWA manifest — installable on phone, offline clock/chores fallback

### Phase 2 — Ontology Foundation

- [ ] Create `/ontology` directory in CabinAutomations repo
- [ ] Create `ontology.yaml` root with `ontology_version: "1.0"` and entity schema (see Section above)
- [ ] Seed with all 14 paired Zigbee devices, 3 candidate Reolink cameras, and key platform services
- [ ] Add relationship vocabulary: `replaces`, `extends`, `complements`, `monitors`, `controls`, `notifies`, `derives_from`, `triggers`, `depends_on`
- [ ] Define `candidate` entity type for discovered-but-not-yet-integrated devices
- [ ] Wire `derived_from` as DAG reference structure: `{ entity_id, relationship_type, transformation }`
- [ ] Add `data_class` field: `raw | derived | conceptual | composite`

### Phase 3 — Event Pipeline

- [ ] Formalize Mosquitto topic structure: `platform/events/{event_type}/{source}`
- [ ] Build `faceofthecabin-camera-adapter`: consume Frigate MQTT events, publish normalized platform events
- [ ] Build `faceofthecabin-automation`: consume platform events, evaluate YAML automation rules, publish actions
- [ ] Implement lineage record on every state-change: `{ from_event, via_automation, to_state, timestamp }`

### Phase 4 — Tech ID Service

- [ ] Build `tech_id_service` container: reads ontology, runs scheduled discovery per entity
- [ ] Implement Kidde use case as first integration test
- [ ] Publish `ontology.entity.updated` events when discovery flags change
- [ ] Connect to notification service: push alert when `new_api_available` or `new_alternatives` flip true
- [ ] Admin UI: `check_for_new` schedule configurable per entity (not YAML-only)

### Phase 5 — Platform UI

- [ ] Build see/think/act UI shell in `cabin-orchestration-platform/ui` React frontend
- [ ] Implement entity search: full-text across ontology IDs, labels, tags, and `search_aliases`
- [ ] Implement lineage trace view: upstream (`derived_from`) and downstream (`consumed_by`) per entity
- [ ] Implement discovery panel: entities with `check_for_new: true`, schedule, `last_checked`, current flags
- [ ] RulesEnginePanel reductive UI: Active→Reset, Recent→Undo, filterable by time — for non-technical users managing automations

---

## Environment & Credentials Reference

| Item | Value |
|------|-------|
| M920q Tailscale IP | `100.77.44.113` |
| Docker Compose path | `/storage/containers/compose/cabin/` |
| Google OAuth owner | `nhsmrekar@gmail.com` |
| Calendar / Photos | `smrekarfamilia@gmail.com` |
| OAuth current origin | `http://nates-little-m920q.tailb20f8b.ts.net:4081` _(update to unicornpingpong.com)_ |
| Zigbee coordinator | `/dev/serial/by-id` → `/dev/ttyACM0` · adapter: ember |
| Parenting anchor | March 13 2026 · kids-home-days: `[0,1,4,5,8,9,12,13]` |
| Domain registrar | Porkbun — `unicornpingpong.com` |
| DNS | Cloudflare free tier |

---

## Appendix — Paired Zigbee Devices

All 14 devices paired and renamed to canonical snake_case ontology IDs:

| Ontology ID | Device | Location |
|-------------|--------|----------|
| `motion_entry` | SONOFF SNZB-03PR2 SenseGuard Motion Gen2 | cabin entry |
| `door_front_contact` | SONOFF SNZB-04P | front door |
| `door_second_contact` | SONOFF SNZB-04P | secondary door |
| `temp_outside_lowest` | SONOFF SNZB-02WD | outside back door |
| `temp_kitchen` | SONOFF SNZB-02WD | upstairs/kitchen |
| `leak_mech_room` | THIRDREALITY leak sensor | basement floor |
| `leak_alarm_fridge` | THIRDREALITY leak sensor | fridge area |
| `leak_alarm_dishwasher` | THIRDREALITY leak sensor | dishwasher area |
| `leak_alarm_bathroom` | THIRDREALITY leak sensor | bath/toilet/tub |
| `temp_mech_room` | SONOFF SNZB-02WD | basement breaker box area |
| `heater_mech_room` | THIRDREALITY smart plug | plumbing heater auto-switch |
| `main_water_valve` | Zigbee clamp-on actuator | 3/4" main water shutoff |
| `smart_switch_breaker_box` | THIRDREALITY smart plug | fan/heater for mech room humidity |
| `water_leak_buzzer` | THIRDREALITY buzzer | repurposed 120dB intrusion siren |

---

## Review Checkpoints

### Platform Review — 2026-08-27
- [ ] `unicornpingpong.com` registered and Cloudflare tunnel live
- [x] All platform code in `FaceoftheCabin` (smrekar-platform deprecated 2026-07-27)
- [ ] M920q docker-compose.yml committed to CabinAutomations
- [ ] Phase 1 blockers cleared
- [ ] Tech ID Service discovery flags updated for any new device purchases
- [ ] Ontology schema version still at `1.0` or bumped with changelog

### Ontology Migration Review — Monthly (recurring until 0 pending remain)

`docs/ontology.yaml` contains entries with `migration_status: pending` that must be upgraded
to v0.3.0 schema (`entity_type`, `ui_display_name`, `data_type`, `constraint`,
`rendering` replacing `label`, `relationships` replacing `derives_from`/`used_by`).
Work in `migration_priority` order. Update the count below after each session.

| Review Date | Pending Count | Notes |
|-------------|---------------|-------|
| 2026-07-27  | 24 of 24      | v0.3.0 schema established; Family Hub (32 entries) complete |
| 2026-07-28  | 0 of 24       | All 24 legacy entries migrated; ontology fully at v0.3.0 |
| 2026-08-27  | —             | _(scheduled review — verify no new pending entries)_ |
| 2026-09-27  | —             | _(scheduled)_ |
| 2026-10-27  | —             | _(scheduled)_ |

**Priority order (migrate highest first):**

| Priority | ID | Reason |
|----------|----|--------|
| 1 | `display_name` | Active in UI, 4 consumers |
| 2 | `device_state` | Active backend + UI, 4 consumers |
| 3 | `temperature_celsius` | Active sensor, 4 consumers |
| 4 | `humidity_percent` | Active sensor, 4 consumers |
| 5 | `battery_percent` | Active sensor, 4 consumers |
| 6 | `contact_state` | Active sensor, 4 consumers |
| 7 | `fotc_device_id` | Active API + UI, 4 consumers |
| 8 | `tailscale_ip` | Active infra, 4 consumers |
| 9 | `z2m_friendly_name` | Active integration, 3 consumers |
| 10 | `zigbee_ieee_address` | Active hardware FK, 3 consumers |
| 11 | `linkquality` | Active sensor, 3 consumers |
| 12 | `mqtt_topic` | Active protocol, 3 consumers |
| 13 | `device_command` | Active/planned backend, 3 consumers |
| 14 | `audit_log` | Partially implemented, 3 consumers |
| 15 | `zigbee_model` | Active hardware, 3 consumers |
| 16 | `mqtt_availability_topic` | Active protocol, 2 consumers |
| 17 | `zigbee_vendor` | Active hardware, 2 consumers |
| 18 | `device_room` | Planned metadata table, 4 consumers |
| 19 | `device_install_photo` | Planned, 3 consumers |
| 20 | `data_dictionary_ui` | Planned UI, 3 consumers |
| 21 | `semantic_layer_toggle` | Planned UI, 4 consumers |
| 22 | `action_confirmation_copy` | Planned safety UI, 2 consumers |
| 23 | `docker_network_cabin_default` | Infra-only, 2 consumers |
| 24 | `machine_hostname` | QA runner only, 2 consumers |
