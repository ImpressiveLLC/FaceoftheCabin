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
6. Self-improving discovery: the platform actively monitors for new integrations based on ontology definitions for any non-deprecated device or service; scheduled rechecks — monthly for active devices — against industry-standard vendor sources and DIY/pro community pages, surfacing "next best idea" opportunities per component (upgrade paths, better-utilized configurations, new comparable products) rather than just flagging new purchases. See [The Tech ID Service](#the-tech-id-service) — fully specified, not yet built.
7. Radical device flexibility: no device, protocol, or vendor is a hard architectural assumption — every integration goes through the same ontology-first pattern (register the entity, document what's blocking full integration, verify against real installed source, only mark complete after a live test). See `docs/REPLICATION.md`'s device onboarding section for the concrete, repeatable process, evidenced by this project's own Zigbee/Frigate/HA-integration/appliance work.

---

## Product Architecture

### 3.1 Family Hub — `hub.unicornpingpong.com`

- Ambient full-screen display: clock, parenting schedule, Google Calendar events, Google Photos slideshow
- Kids' chore tracking with per-child reward progress (Sam age 9, Emma age 6)
- Parenting schedule logic: versioned rules (see `docs/ontology.yaml`'s
  `parenting_schedule_rule_version`) — March 13 2026 original anchor,
  14-day cycle, kids-home-days `[0,1,4,5,8,9,12,13]`, **superseded July 27
  2026** by a 50/50 split (`[2,3,4,5,9,10]` = Wed/Thu/Fri/Sat week A,
  Wed/Thu week B; Rachel has Mon/Tue always, Sun alternates). Historical
  dates always compute under whichever version was actually in effect at
  the time, not today's rule applied retroactively. Holidays override
  per-date via `holiday_override`. See the Environment & Credentials
  Reference table below for the full current detail.
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
| Domains | `unicornpingpong.com` — platform (hub · cabin · api subdomains) |
| | `impressive.llc` — org domain, aligns with ImpressiveLLC GitHub org |
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

> **Status: fully specified, not yet built.** Everything in this section
> is a design, not a running capability — Phase 4 below has zero
> completed items. Flagged explicitly here because the prose below reads
> confidently enough that it's easy to mistake for a shipped feature; it
> isn't one yet. This is the platform's most concrete answer to "AI-driven
> tech opportunity updates" — the user's own framing, 2026-08-03: checking
> "industry standard and DIY pro pages" on a schedule for "next best
> ideas" per component, so every cataloged device/service gets evaluated
> for whether it's being under-utilized or has a better integration path
> available now than when it was first registered.

A background platform service that:

- Maintains a registry of all ontology entities with `check_for_new: true`
- Runs scheduled checks per entity schedule against: official vendor
  API/changelog pages, community integration hubs (HACS, Home Assistant's
  own integration release notes), and DIY/pro forums and build logs
  (r/homeassistant, r/homeautomation, vendor-specific communities) —
  "industry standard and DIY pro pages," in the user's own framing —
  looking specifically for whether an owned device/service could be
  better utilized than its current configuration, not just whether a
  newer product exists
- Updates discovery flags in the ontology when new information is found
- Publishes `ontology.entity.updated` platform event when flags change
- Surfaces discoveries as actionable notifications: _"Reolink released a new webhook API — 2 of your devices may benefit"_
- Grows the ontology by identifying candidate entities from browsing context, device purchases, and integration logs

**Ontology growth events:** `ontology.entity.created` · `ontology.entity.updated` · `ontology.entity.deprecated`
**Ontology is append-only for history** — deprecated entities are flagged, never deleted.

### Tech ID Service — Provider Model

> **Status: the ingestion API is real and live** (`POST`/`GET`/`PATCH
> /api/tech-id/findings`, `com.cabin.orchestrator.techid` package,
> `tech_id_finding` Postgres table). The scheduled-scan *provider* side —
> a routine that actually calls out to an AI/research service and POSTs
> results here — is not yet wired up for any provider. Everything below
> this line describes the model this API was built to support.

The scanning work itself (deciding what's new, reading a vendor
changelog, judging whether a DIY forum post describes something real)
was never meant to live inside `cabin-backend` — that would hard-wire
one AI vendor's account into every deployment of this platform, which
directly contradicts the "replicable by other, unrelated instance
owners" goal (`docs/REPLICATION.md`). Instead the platform defines one
normalized **findings contract** any provider can fill in, and stays
agnostic about who did the research:

```
TechIdFinding { entityId, provider, findingType, summary, confidence, sources[], status, checkedAt, createdAt }
```

`provider` is free text, not an enum — accepting insights "from
anywhere possible" (the point of the finding record's design) means the
platform never needs a code change to recognize a new source. Three
providers are expected to exist side by side:

1. **The reference Claude Code routine** — this repo's own scheduled
   scan (see "Scheduled kickoff" below), included at no extra cost as
   the default/reference implementation. Anyone standing up a fresh
   instance from this template gets this for free.
2. **An operator-run, higher-tier scanning service** — the commercial
   path: an instance owner who wants deeper or more frequent coverage
   than the free reference routine pays the platform operator for a
   richer scan (more source categories, tighter schedule, human-curated
   query tuning) that still just POSTs `TechIdFinding` rows to their own
   instance's `/api/tech-id/findings`. From the API's point of view this
   is indistinguishable from provider 1 or 3 — it's a business-model
   choice, not an architectural one.
3. **Bring-your-own-AI** — an instance owner who'd rather run their own
   scan (a cheaper, volume-priced model instead of Claude, an in-house
   script, a different vendor's research API entirely) points that
   pipeline at their own instance's endpoint instead. The platform's job
   stops at *ingesting and adjudicating* findings, not at mandating who
   produces them.

**Submission (any provider):** `POST /api/tech-id/findings` with header
`X-Tech-Id-Api-Key: <cabin.techid.apiKey>`. One shared secret per
instance gates *whether* a caller may submit at all; `provider` is a
self-reported label, not a checked identity — there's no per-provider
credential registry, by design, since that would recreate the
single-vendor lock-in this model exists to avoid. Unset by default
(returns `503`) — an operator must opt in.

**Reading:** `GET /api/tech-id/findings?entityId=&limit=` is open,
matching the existing `/api/events`/`/api/devices` precedent — findings
aren't more sensitive than event/device data.

**Adjudication:** `PATCH /api/tech-id/findings/{id}` (`status`: `new` →
`reviewed`/`actioned`/`dismissed`) requires a signed-in human via
`GoogleAuthInterceptor` — this is the "decision making" step a machine
provider hands off to a person. A finding landing in the table is a
*claim*, not an automatic action; nothing here writes back into
`docs/ontology.yaml`'s `discovery:` fields automatically. Reconciling an
adjudicated finding into the versioned ontology (today: a human editing
`ontology.yaml` directly, as done for `nvr_frigate` and the two camera
entities this session) is a deliberate, separate, slower step — fast/
live findings vs. slow/versioned ontology history, on purpose.

**Scheduled kickoff, as its own trigger type:** the reference routine
runs on a recurring cron schedule (a `RemoteTrigger` cloud routine, or
any equivalent scheduler an instance operator prefers), not on a git
push, PR, or CI event — "kickoff" here means *time-based*, decoupled
from whether any code changed. This matters because the platform's
existing automation triggers (`docs/ontology.yaml`'s event-driven
automations, GitHub Actions in `.github/workflows/`) are all git- or
sensor-driven; a monthly tech-opportunity scan needed a genuinely new
trigger *category* — see `docs/REPLICATION.md`'s Tech ID Service setup
section for how an instance operator configures one.

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
- [x] Cross-device backend on `cabin-backend` (Postgres-backed, matches the existing
      `api.unicornpingpong.com` shared-services scope in §3.3). **Done 2026-08-01.**
      Poll-based delivery (20s `setInterval`, matches the file's existing pattern), gated
      by `GoogleAuthInterceptor` (valid Google access token required — first auth-gated,
      first write, endpoints on this backend). Verified against a real local Postgres +
      running backend, two isolated browser contexts standing in for two devices, both
      directions, zero errors. Note the scope actually shipped: the raw per-chore
      completion record syncs now; the `chore_daily_success`/`chore_weekly_success`
      *derived* threshold flags (3+/day, 4+/week) are still computed live client-side
      from that synced data, not separately persisted server-side — see those entities'
      notes in `docs/ontology.yaml` for why that's a deliberate, smaller remaining gap,
      not an oversight. `family_profile` itself also still isn't synced (localStorage/
      per-device) — degrades gracefully (see `family_note`'s ontology notes), not fixed
      here.
- [ ] **[BLOCKER]** Push current M920q `docker-compose.yml` to CabinAutomations repo (currently local-only at `/storage/containers/compose/cabin/`)
- [x] **[FOUND 2026-07-30, FIXED 2026-08-02]** `cabin-postgres` was running on
      the fallback dev password (`cabin_dev_password`), a literal string in the
      public repo. Fixed live against the running container: `ALTER USER cabin
      WITH PASSWORD '...'` (Postgres only applies `POSTGRES_PASSWORD` at first
      volume init, so an env-var change alone would've done nothing), then
      `.env` updated to match so a future recreate doesn't drift back. Verified
      the *real* auth path, not the deceptive one — `docker exec ... -h
      localhost` hits `pg_hba.conf`'s `trust` rule for loopback and succeeds
      with any password, old or new; the actual dependent services
      (`cabin-backend`, `cabin-grafana`) connect over the Docker network alias
      and correctly hit the `scram-sha-256` rule. Confirmed both reconnected
      successfully with the new password (`cabin-backend`'s `/actuator/health`
      showed `db: UP`, Grafana's `CabinDB` datasource health check returned
      `Database Connection OK`) before considering this closed.
      **Bonus fix found in the same pass:** `cabin-grafana` never actually
      received `POSTGRES_PASSWORD` as an env var, despite its own provisioning
      YAML referencing `${POSTGRES_PASSWORD}` — Grafana only expands
      provisioning-file env vars present in its own container, not anywhere
      else in the compose file, so the `CabinDB` datasource had likely never
      authenticated correctly. Added the missing env var alongside the
      password rotation.
- [x] Register `unicornpingpong.com` at Porkbun, add to Cloudflare free tier —
      confirmed live: all three subdomains resolve and serve real responses.
- [x] Add `cloudflared` container to M920q docker-compose, configure tunnel to
      `hub/cabin/api` subdomains — confirmed 2026-08-01 via direct `curl`:
      `hub.` serves family-hub.html, `cabin.` serves cabin-ui (200), `api.`
      `/actuator/health` reports `{"status":"UP"}`.
- [x] Update Google OAuth authorized origin from `http://127.0.0.1:5500` to
      `https://hub.unicornpingpong.com` — done live during an earlier session's
      debugging (root-caused a "doesn't comply with OAuth 2.0 policy" error to
      the bare apex domain being authorized instead of the exact `hub.` origin).
- [ ] Family Hub PWA manifest — installable on phone, offline clock/chores fallback

### Phase 1.5 — Location Context & Vocabulary Feedback Loop

- [ ] Build `location_vocabulary_term` table in Postgres (id, value, applies_to, parent_room_types, status, usage_count, source)
- [ ] Seed master terms: common room names + zone/qualifier lists per room type
- [ ] Build `candidate_term_submission` table + API endpoint (POST /ontology/vocabulary/candidates)
- [ ] Wire soft-enum dropdowns in device setup UI: dropdown + "Other" → free text → auto-POST candidate on save
- [ ] Build admin review queue UI: pending candidates ranked by submission_count, promote/reject actions
- [ ] Build `device_location_formatted` derivation service: L1+L2 → snake_case / camelCase / PascalCase / label variants
- [ ] Wire formatted output into Z2M friendly_name seed and fotc_device_id generation
- [ ] NLP dedup check on candidate submission (embedding similarity vs existing master terms)
- [ ] Auto-promotion rule: submission_count >= N (threshold TBD) triggers auto-promote candidate → master

### Phase 2 — Ontology Foundation

- [ ] Create `/ontology` directory in CabinAutomations repo
- [ ] Create `ontology.yaml` root with `ontology_version: "1.0"` and entity schema (see Section above)
- [ ] Seed with all 14 paired Zigbee devices, 3 candidate Reolink cameras, and key platform services
- [ ] Add relationship vocabulary: `replaces`, `extends`, `complements`, `monitors`, `controls`, `notifies`, `derives_from`, `triggers`, `depends_on`
- [ ] Define `candidate` entity type for discovered-but-not-yet-integrated devices
- [ ] Wire `derived_from` as DAG reference structure: `{ entity_id, relationship_type, transformation }`
- [ ] Add `data_class` field: `raw | derived | conceptual | composite`
- [ ] **[FOUND 2026-08-02]** `platform_secret` entity added to `docs/ontology.yaml`
      (infra layer) — the definition-first model for every credential the
      platform depends on (POSTGRES_PASSWORD, GRAFANA_PASSWORD, HA_TOKEN,
      GOOGLE_CLIENT_ID, GitHub runner registration token). Documents current
      state honestly (entirely manual — ad hoc generation, plain `.env`
      storage, hand-run rotation) and names the target: an Ansible Vault (or
      equivalent) role that generates/stores/templates these values, so
      rotation is "run the role" instead of SSHing in by hand — see the
      `cabin-postgres` rotation (2026-08-02) for what the manual version
      costs. **Built and verified live, 2026-08-02** (same day, later
      session): `ansible/roles/secrets` + `ansible/playbooks/rotate-secrets.yml`
      — a real end-to-end rotation ran against the M920q (generate → `ALTER
      USER` → re-encrypt vault → re-template `.env` → restart dependents →
      validate `db: UP`), see `ansible/README.md`'s Secrets section for the
      exact commands and the two real bugs found running it for the first
      time. `GRAFANA_PASSWORD`/`HA_TOKEN` rotation remains manual — different
      mechanics needed (Grafana admin API, HA's own token UI), documented as
      a follow-up, not silently skipped.

### Phase 3 — Event Pipeline

- [ ] Formalize Mosquitto topic structure: `platform/events/{event_type}/{source}`
- [ ] Build `faceofthecabin-camera-adapter`: consume Frigate MQTT events, publish normalized platform events
- [ ] Build `faceofthecabin-automation`: consume platform events, evaluate YAML automation rules, publish actions
- [ ] Implement lineage record on every state-change: `{ from_event, via_automation, to_state, timestamp }`

### Phase 4 — Tech ID Service

> **Ingestion API built; scanning/provider side not started.** See "Tech
> ID Service — Provider Model" above for what's real vs. designed. The
> `TechIdFinding` record, `tech_id_finding` table, and `POST`/`GET`/
> `PATCH /api/tech-id/findings` endpoints exist and compile/run today.
> No provider (reference routine, operator tier, or bring-your-own-AI)
> is actually wired up to call `POST` yet — the ontology's `discovery:`
> fields on `nvr_frigate`/the two camera entities were populated by hand
> this session (real WebSearch findings, manually transcribed), not by
> this API. This is the platform's concrete implementation of Northstar
> Goal #6 (self-improving discovery / "next best idea" scanning).

- [x] Define provider-agnostic findings schema (`TechIdFinding`)
- [x] Build findings ingestion API (`POST`/`GET`/`PATCH /api/tech-id/findings`, shared-secret-gated submission, Google-auth-gated adjudication)
- [ ] Wire up the reference Claude Code routine as an actual `RemoteTrigger` scheduled scan that POSTs real findings (blocked on the user granting claude.ai GitHub App access to `ImpressiveLLC/FaceoftheCabin` — see `docs/MAINTENANCE.md` Known Issues)
- [ ] Implement Kidde use case as first end-to-end integration test (browse → scan → finding → adjudicate → ontology update)
- [ ] Build the reconciliation step: adjudicated (`actioned`) findings write back into `docs/ontology.yaml`'s `discovery:` fields — today this is manual
- [ ] Publish `ontology.entity.updated` events when discovery flags change
- [ ] Connect to notification service: push alert when `new_api_available` or `new_alternatives` flip true
- [ ] Admin UI: findings review queue (list `status: new`, adjudicate inline) and `check_for_new` schedule configurable per entity (not YAML-only)

### Phase 5 — Platform UI

- [ ] Build see/think/act UI shell in `cabin-orchestration-platform/ui` React frontend
- [ ] Implement entity search: full-text across ontology IDs, labels, tags, and `search_aliases`
- [ ] Implement lineage trace view: upstream (`derived_from`) and downstream (`consumed_by`) per entity
- [ ] Implement discovery panel: entities with `check_for_new: true`, schedule, `last_checked`, current flags
- [ ] RulesEnginePanel reductive UI: Active→Reset, Recent→Undo, filterable by time — for non-technical users managing automations

### Phase 6 — Camera Video Viewing

> Planned 2026-08-02, **built later the same day** once the user confirmed
> they wanted to proceed. Grounded in Frigate's *actual current config* and
> *actual installed source* throughout (pulled live via `GET /api/config`
> and by reading `frigate/api/media.py` directly on the M920q, not assumed
> from generic docs). See `docs/ontology.yaml`'s `cabin_camera_live_view` /
> `cabin_camera_event_clip` / `cabin_camera_continuous_recording` entities
> for full detail, including what's still genuinely unverified (a real
> signed-in browser session hasn't rendered a thumbnail/clip/live view yet
> — no qualifying recent event existed at deploy time to test against).
>
> Key finding that reframed the scope, confirmed correct: **Frigate already
> did most of the underlying video engineering** — this phase ended up
> being mostly configuration plus a proxy/viewing layer in
> cabin-backend/cabin-ui, not a video pipeline built from scratch.

- [x] **(b) Event clip pre/post buffer** — changed to `pre_capture: 15` /
      `post_capture: 60`, both `alerts` and `detections` review tiers
      (decided: both, not just `alerts`).
- [x] **(d) Continuous (DTM) recording** — enabled at `continuous.days: 5`
      — deliberately conservative, decided directly with the user, not the
      originally-floated 14-30 days: `driveway`'s record role turned out
      to use its 4K main stream (missed at planning time), which at
      realistic 4K bitrates could be 80-160+ GB/day once that camera
      reconnects (currently off-network). Re-measure real GB/day before
      extending retention, don't trust this as more than a starting
      estimate.
- [x] **(a) Live view "like Blink"** — built via Frigate's plain MJPEG
      endpoint (`GET /api/{camera}`, simpler than the MSE/jsmpeg/go2rtc
      mechanism assumed at planning time, no go2rtc config needed),
      proxied through cabin-backend and rendered as a plain `<img>` tag in
      cabin-ui. One real wrinkle: `<img>` can't set an Authorization
      header, and this stream is unbounded so it can't be blob-fetched —
      `GoogleAuthInterceptor` now also accepts the token as an
      `?access_token=` query param for this one case.
- [x] **(c) Persisted-event review UI**, mostly — `CameraEventsPanel` now
      shows a real thumbnail (authenticated blob-fetch) and an expandable
      clip player per event. **Not done**: real pagination/filtering
      across full history (still capped at the most recent 30). Also
      fixed a real gap found while building this: `MqttBridgeService`
      never captured Frigate's own event id at all, so even stored clips
      would have been permanently unreachable — now captured as
      `frigateEventId`. Scope question resolved: video/clip access stays
      cabin-ui-only, authenticated (not added to Family Hub's public
      widget) — gated behind `GoogleAuthInterceptor`, matching
      notes/chores/profiles.
- [x] Update `README.md`'s camera-activity security note to also cover
      video/clip storage and retention.
- [x] Not blocked on the `driveway` camera coming back online — confirmed
      camera-count-agnostic in practice: the whole pipeline (event
      capture, clip config, media proxy) applies uniformly regardless of
      which cameras are actually live.

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
| Parenting schedule | Versioned rules (see `docs/ontology.yaml` → `parenting_schedule_rule_version`): March 13 2026 original (`[0,1,4,5,8,9,12,13]`) superseded by July 27 2026 50/50 split (`[2,3,4,5,9,10]` = Wed/Thu/Fri/Sat wk A, Wed/Thu wk B; Rachel Mon/Tue always, Sun per week). Holidays override per-date via `holiday_override`. Historical dates always compute under whichever version was actually in effect. |
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
| 2026-07-28  | n/a           | +8 entries: location_context class, L1–L3 properties, device_location_formatted, location_vocabulary_term, candidate_term_submission |
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
