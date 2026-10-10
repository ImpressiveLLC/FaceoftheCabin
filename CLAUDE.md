# CLAUDE.md — FaceoftheCabin platform

This file gives Claude Code full context for every session — on any machine,
at any location.

**Canonical repo:** `https://github.com/ImpressiveLLC/FaceoftheCabin`
(Previously `smrekarfamilia-sudo/FaceoftheCabin` — always use the ImpressiveLLC URL.)

---

## Multi-machine workflow (IMPORTANT — core product tenet)

This platform is designed to be worked on across multiple machines. The rule is simple:

| Machine | Identity | Role |
|---------|----------|------|
| Windows PC, Minneapolis | `ilikethelights` | Primary dev — UI, backend, new features |
| Lenovo M920q at cabin | `m920q` / "at the cabin" | Cabin hub — Docker stack, Z2M, HA, integration testing |
| Any future machine | — | Clone repo, follow same steps |

**Before switching machines:**
1. `git push origin main` on the machine you're leaving
2. On the new machine: `git pull origin main`
3. Never commit directly on the M920q unless doing cabin-specific infra work — push from there immediately

**Single source of truth:** GitHub `ImpressiveLLC/FaceoftheCabin`. If something isn't there, it doesn't exist on other machines.

**If Claude can't find prior work:** it means it wasn't pushed before switching. Check `git log --oneline` on the originating machine before concluding work is lost.

**Decisions made where Claude can't commit (Claude web at the cabin — no
Claude Code on the M920q) need an explicit hand-back, not just a good
conversation.** Found 2026-08-06: a real, discussed, agreed decision about
`blinkbridge`'s architecture was made in a cabin Claude-web session and
then genuinely lost — nothing in the repo recorded it, and the next
Claude Code session had to reconstruct it from the user's memory alone.
The context-loading side of this was already solved (paste CLAUDE.md's
URL at the start of a cabin web session — see the cabin-context guidance
this file is meant to satisfy), but nothing closed the loop on the way
back out. **If you reach a real decision, plan, or agreement in a session
that can't commit to git directly, tell the user exactly what to paste
into `docs/DEFINITION_OF_DONE.md`'s "Next Session — Open Items" list
before the session ends.** A decision that exists only in a chat
transcript does not exist for the next session, Code or web.

---

## What this repo is

A Spring Boot + React monorepo that unifies **two physical locations** (cabin,
home) under one management UI. It is a **management layer on top of Home
Assistant + Zigbee2MQTT** — not an automation engine. HA handles all
automation logic. This platform handles device visibility, configuration,
telemetry dashboards, and (soon) a user-friendly Zigbee device manager.

**Never duplicate HA automation logic here.** The canonical automation
file lives in `ImpressiveLLC/CabinAutomations` (`automations/leak_freeze_automations.yaml`).

---

## Repo structure

```
cabin-orchestration-platform/
├── backend/                         Java 21 / Spring Boot 3.3.5
│   └── src/main/java/com/cabin/orchestrator/
│       ├── api/                     REST controllers (devices, camera, events,
│       │                            dashboard, presence, system health)
│       ├── automation/              AutomationRuleService (safety rules only)
│       ├── devices/                 DeviceRegistry, DeviceDescriptor, DeviceStatus,
│       │   └── display/             DeviceType, DeviceCapability, ProtocolAdapter,
│       │                            DeviceDisplayConfig(Service)
│       ├── events/                  CabinEvent record, CabinEventService (Postgres),
│       │                            AlertSeverityClassifier, NtfyAlertPublisher
│       │                            — see "Event pipeline & alerting" below
│       ├── family/                  ProfilesController, ChoresController,
│       │                            NotesController — Family Hub's backend surface
│       ├── integrations/
│       │   ├── cameras/             CameraIntegration (stub)
│       │   ├── google/              GoogleHomeIntegration (stub)
│       │   ├── homeassistant/       HomeAssistantAdapter (ha_rest — location-aware)
│       │   ├── kidde/               KiddeIntegration (stub)
│       │   └── zigbee/              Zigbee2MqttAdapter — Z2M bridge, the live
│       │                            13-device cabin Zigbee mesh runs through this
│       ├── kafka/                   EventPublisher → cabin.events.raw,
│       │                            EventConsumer ← persists to Postgres +
│       │                            triggers NtfyAlertPublisher on CRITICAL
│       ├── mqtt/                    MqttBridgeService (subscribes cabin/# —
│       │                            devices, cameras/Frigate, direct events)
│       ├── ontology/                OntologyController + OntologyLookupService
│       │                            (resolves ontology.yaml ids to display names)
│       ├── techid/                  TechIdController — Opportunity Map backend
│       └── workflow/                WorkflowRuleService (See→Think→Act engine —
│                                    trigger/clear matching, actions, executions),
│                                    RulesController, JdbcWorkflowRuleStore,
│                                    JdbcWorkflowVocabularyStore (DB-backed
│                                    trigger/action catalog, reseeded from code
│                                    on every boot, never user-editable)
│   └── src/main/resources/
│       └── application.yml          All env-var-driven config
│   └── src/test/                    First tests added 2026-08-06 — see
│                                    "Testing" section below before assuming
│                                    `mvn test` "just works" on a new host
├── ui/                              Vite + React + lucide-react
│   └── src/
│       ├── App.jsx                  Five-panel shell + LocationSwitcher
│       └── styles.css
├── infra/                           Docker Compose stack (cabin)
│   ├── docker-compose.yml           8 services — see below
│   ├── docker-compose.local.yml     Local dev override (skips HA + watchdog)
│   ├── mosquitto.conf
│   ├── frigate.yml                  Cabin cameras (disabled until installed)
│   ├── init-db/001_schema.sql
│   ├── grafana/provisioning/
│   └── watchdog/watchdog.py
├── locations/
│   └── home/                        Home-location overlays
│       ├── docker-compose.yml       Same stack, 16 GB mem limits
│       ├── frigate.yml              5× Reolink RLC-810A (192.168.1.20–24)
│       ├── application-home.yml     Spring profile overlay
│       └── .env.example
├── docs/
│   ├── ARCHITECTURE.md
│   ├── device-topic-contract.md     MQTT topic schema
│   ├── integration-strategy.md
│   ├── tailscale-remote-access.md
│   └── nodered-starter-flows.md
└── scripts/
    ├── bootstrap-ubuntu.sh
    └── check-ssd-health.sh
```

---

## Key models

### DeviceDescriptor (static config)
```java
record DeviceDescriptor(
    String deviceId, String name, DeviceType type,
    Set<DeviceCapability> capabilities,
    String protocolAdapter,   // "mqtt" | "ha_rest" | "rtsp" | "http_poll"
    String connectionString,  // HA entity_id, MQTT topic, RTSP URL
    boolean enabled,
    String location           // "cabin" | "home"
)
```

### DeviceStatus (runtime state)
```java
record DeviceStatus(
    String deviceId, DeviceType type, String name,
    String state,             // ONLINE | OFFLINE | ALARM | UNKNOWN
    Instant lastSeen,
    Map<String, Object> attributes,
    String location
)
```

### CabinEvent (event pipeline — distinct from DeviceStatus.state above)
```java
record CabinEvent(
    String eventId, String sourceDeviceId, String eventType,
    String severity,          // INFO | WARN | CRITICAL — see below
    Instant timestamp,
    Map<String, Object> payload
)
```
**`DeviceStatus.state` is device health** (is it online, is it in an alarm
condition right now). **`CabinEvent.severity` is per-event, computed by
`AlertSeverityClassifier.classify(attrs)`** from that event's own payload —
a device can be `ONLINE` while its most recent event was `CRITICAL` (an
active water leak). Don't conflate the two axes; see
`docs/ontology.yaml`'s `event_severity` entity for the full rationale.
Classification rule (MVP, no armed/presence awareness yet — deliberate
scope cut, see `docs/DEFINITION_OF_DONE.md`'s punch list):
`water_leak`/`smoke`/`alarm` true → `CRITICAL`; `tamper`/`battery_low`
true or `contact` false → `WARN`; everything else → `INFO`.

### DeviceCapability enum
`TELEMETRY, COMMAND, STREAM, ALARM, PRESENCE, CLIMATE, ACCESS_CONTROL, APPLIANCE, POWER_MONITOR`

### DeviceType enum
`SMOKE_ALARM, CO_ALARM, WATER_LEAK_SENSOR, CAMERA, LOCK, MOTION_SENSOR,
CONTACT_SENSOR, THERMOSTAT, TEMPERATURE_SENSOR, HUMIDITY_SENSOR,
WATER_PRESSURE_SENSOR, POWER_METER, DISHWASHER, WASHING_MACHINE, DRYER,
ROUTER, UPS, GOOGLE_HOME_DEVICE, HOME_ASSISTANT_ENTITY, DASHBOARD`

---

## Two locations, two hubs

| | Cabin | Home |
|---|---|---|
| Hardware | Lenovo ThinkCentre M920q, Ubuntu 24.04, 32 GB RAM | Lenovo ThinkCentre M920q, Ubuntu 24.04, 16 GB RAM |
| Tailscale hostname | `cabin-hub` | `home-hub` |
| HA URL | `http://cabin-hub:8123` | `http://home-hub:8123` |
| Grafana | `http://cabin-hub:3000` | `http://home-hub:3000` |
| Frigate | `http://cabin-hub:5000` | `http://home-hub:5000` |
| Node-RED | `http://cabin-hub:1880` | `http://home-hub:1880` |
| Zigbee2MQTT UI | `http://cabin-hub:8080` | N/A (cabin only so far) |
| Spring Boot API | `http://cabin-hub:8080` | `http://home-hub:8080` |

Both hubs are x86_64. No ARM/Pi hardware anywhere. Tailscale is the only VPN.

**Current reality, 2026-10-04 (read this before trusting the Home column).**
The Home column describes the original plan. No Home hub is deployed: Home's
Zigbee and network-scan data come from an Android/Termux collector that
publishes to the M920q over MQTT, and the M920q is the head for both
locations (`docs/MAINTENANCE.md`, "Home Location", decision 2026-10-01;
D23, drafted in PR #116, is not ratified yet). The code agrees:
`HOME_HUB_DEPLOYED` defaults to `false` (`HaTokenHomeHealthIndicator`),
`HOME_HA_URL` defaults to `http://home-hub:8123` but `HOME_HA_TOKEN` is blank
on purpose, and `application.yml` reports Home's HA as out of service by
design. Do not treat `home-hub` URLs as live. This table stays until D23 is
ratified, then it should be rewritten, not patched.

---

## Deployed cabin stack (as of 2026-07-25)

The cabin M920q is **live**. Home Assistant + Zigbee2MQTT are running.
**13 Zigbee devices are paired and confirmed working** (2026-07-25 session).
Coordinator: Sonoff Dongle Plus V2, `ember` adapter. HA discovery confirmed.

**Paired entity IDs (live on M920q):**

| Entity ID | Role |
|---|---|
| `leak_mech_room` | Leak sensor, mechanical room |
| `leak_alarm_fridge` | Leak sensor/siren, fridge |
| `leak_alarm_dishwasher` | Leak sensor/siren, dishwasher |
| `leak_alarm_bathroom` | Leak sensor/siren, bathroom |
| `temp_mech_room` | Temp/humidity, mech room |
| `temp_kitchen` | Temp/humidity, kitchen |
| `temp_outside_lowest` | Outdoor low temp probe |
| `heater_mech_room` | Smart plug — mech room heater |
| `main_water_valve` | Zigbee valve actuator — main shutoff |
| `door_front_contact` | Front door contact sensor |
| `door_second_contact` | Second door contact sensor |
| `motion_entry` | Entry motion sensor |
| `smart_switch_breaker_box` | Smart switch — breaker box |

**Still pending hardware (not yet installed):** entry light, deterrent plug,
RF tripwire routers, spare siren, water heater switch.

**HA networking constraint:** HA runs `network_mode: host` on M920q. Other
containers must reach it via LAN IP (`192.168.2.46:8123`) or Tailscale IP
(`100.77.44.113:8123`) — NOT by container hostname.

Z2M coordinator: SONOFF ZBDongle-E (USB on M920q).

**Important device note:** The THIRDREALITY leak sensor's `water_leak_buzzer`
property is independent of `water_leak` — `leak_spare_siren` uses the buzzer
as an intrusion siren without triggering false leak alerts. The platform's
Zigbee adapter must handle this sub-property generically.

---

## Docker services (cabin stack)

**Two compose files, two different jobs — don't read either alone.**
`infra/docker-compose.yml` is the template for a *fresh* single-machine
deployment (8 services: mqtt, postgres, kafka, grafana, homeassistant,
nodered, frigate, watchdog — no `cabin-backend`/`cabin-ui`/`family-hub` at
all). `infra/docker-compose.m920q.yml` is the actual M920q overlay: it
**disables** mqtt/homeassistant/nodered/frigate/watchdog from the base file
(a separately-managed Mosquitto/HA/Node-RED/Frigate stack already existed
on that host before this project did) and **adds** `cabin-backend`,
`cabin-ui`, and `family-hub`, which exist only in this overlay.

**What's actually running on the M920q** (confirmed via `docker ps`,
2026-08-06 — this is the ground truth, not either compose file read in
isolation):

| Container | Role |
|---|---|
| `cabin-backend` | This project's Spring Boot API (port 8090) |
| `cabin-ui` | This project's React device-manager UI |
| `family-hub` | Static Family Hub HTML |
| `cabin-postgres` | TimescaleDB — devices, events, notes, chores, profiles, tech-id findings |
| `cabin-kafka` | KRaft, no Zookeeper — `cabin.events.raw` topic |
| `cabin-grafana` | Dashboards (currently no working login — see `docs/MAINTENANCE.md`) |
| `mosquitto` | MQTT broker — pre-existing, not this project's `mqtt` service |
| `homeassistant` | Pre-existing, `network_mode: host` |
| `nodered` | Pre-existing — owns real automation logic, see warning at top of this file |
| `frigate` | Pre-existing — camera detection/recording |
| `zigbee2mqtt` | Pre-existing — the 13-device cabin mesh's coordinator bridge |
| `blinkbridge` | Pre-existing — bridges Blink's cloud API into Frigate/MQTT. Has a known no-self-heal failure mode after a transient Blink API error (fixed twice by a manual `docker restart blinkbridge`, 2026-08-03 and 2026-08-06) — see `docs/MAINTENANCE.md`'s punch list, an Uptime Kuma monitor for this is still open |
| `mediamtx` | Pre-existing — RTSP/stream relay Frigate and blinkbridge both use |
| `cloudflared` | Pre-existing — Cloudflare Tunnel for public `unicornpingpong.com` access |
| `uptime-kuma` | Pre-existing — monitoring |
| `homepage` | Pre-existing — dashboard/link launcher |

**On Windows/Docker Desktop:** HA uses `network_mode: host` which maps to
the Docker VM, not the Windows host. Use `docker-compose.local.yml` override
to skip HA and watchdog for local dev (see Dev setup below).

---

## Dev setup (local Windows machine)

### 1 — Docker stack (without HA)
```powershell
cd "H:\My Drive\cabin-orchestration-platform-expanded\FaceoftheCabin\cabin-orchestration-platform\infra"
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d
```

### 2 — Spring Boot backend
```powershell
cd "..\backend"
.\mvnw spring-boot:run
# API live at http://localhost:8080
# Kafka/MQTT failures are logged as warnings — app still starts
```

### 3 — React UI
```powershell
cd "..\ui"
npm install   # first time only
npm run dev
# UI live at http://localhost:5173
```

### 4 — Point UI at local backend (create ui/.env.local)
```
VITE_CABIN_API_BASE=http://localhost:8080
VITE_CABIN_WS_BASE=ws://localhost:9001
VITE_CABIN_GRAFANA_URL=http://localhost:3000
VITE_CABIN_NODERED_URL=http://localhost:1880
VITE_CABIN_FRIGATE_URL=http://localhost:5000
```

### 5 — On the cabin M920q (Linux — partial stack only)

The M920q already runs its own Docker project with: `mosquitto` (1883),
`homeassistant` (host network), `nodered` (1880), `frigate` (5000),
`zigbee2mqtt` (8080), `homepage` (3000), `uptime-kuma` (3001), `mediamtx`.

**Do NOT run `docker compose up` with the base file alone — it will conflict.**
Use the M920q overlay which skips everything already running and only starts
Postgres and Kafka (the two things the existing stack doesn't have):

```bash
cd ~/repos/FaceoftheCabin/cabin-orchestration-platform/infra
cp .env.m920q.example .env          # fill in passwords first
docker compose -f docker-compose.yml -f docker-compose.m920q.yml up -d
# starts: cabin-postgres (5432), cabin-kafka (9092), cabin-grafana (3002)
# skips:  mqtt, homeassistant, nodered, frigate, watchdog (already running)
```

Then run backend and UI directly:
```bash
cd ~/repos/FaceoftheCabin/cabin-orchestration-platform/backend
./mvnw spring-boot:run
# connects to: existing mosquitto:1883, existing HA:8123, new postgres:5432

cd ~/repos/FaceoftheCabin/cabin-orchestration-platform/ui
npm install && npm run dev
```

---

## Env vars

| Var | Used by | Notes |
|---|---|---|
| `POSTGRES_PASSWORD` | postgres, backend | |
| `GRAFANA_PASSWORD` | grafana | |
| `HA_TOKEN` | HomeAssistantAdapter | Cabin HA long-lived token |
| `HA_URL` | HomeAssistantAdapter | Default: `http://localhost:8123` |
| `HOME_HA_TOKEN` | HomeAssistantAdapter | Home HA long-lived token |
| `HOME_HA_URL` | HomeAssistantAdapter | Default: `http://home-hub:8123` |
| `MQTT_URL` | MqttBridgeService | Default: `tcp://localhost:1883` |
| `KAFKA_BOOTSTRAP` | EventPublisher | Default: `localhost:9092` |
| `CAMERA_PASSWORD` | Frigate | Reolink admin password |
| `FAMILY_DASHBOARD_URL` | DashboardController | Familia Hub URL |
| `GOOGLE_CLIENT_ID/SECRET` | GoogleHomeIntegration | OAuth |
| `CABIN_PRESENCE_STALE_AFTER_HOURS` | PresenceController | Hours of phone silence after which `/api/presence` reports `stale: true` and the badge reads "Presence unknown". Default `6`. Keep equal to Node-RED's `PRESENCE_STALE_HOURS` (siren gate, W-33) so the badge and the siren agree |
| `CABIN_ALERT_NTFY_TOPIC` | NtfyAlertPublisher | ntfy.sh topic for CRITICAL-severity push. Empty = no-op (events still persist). The topic functions as a shared secret (ntfy.sh topics aren't access-controlled) — real value lives only in `infra/.env` on the M920q, never committed; `infra/.env.m920q.example` has the placeholder + full reasoning |

---

## MQTT topic contract

```
cabin/device/{deviceId}/state
cabin/device/{deviceId}/telemetry
cabin/event/{severity}
cabin/camera/{cameraId}/motion
cabin/system/health
cabin/kidde/co_alarm                 ← Kidde CO alarm push bridge (HA automation →
                                        MQTT, added 2026-08-21; ON/OFF payload only,
                                        validated against MqttPublish.py's ALLOWED
                                        dict on the HA side before it ever reaches
                                        this topic)
{location}/presence/#                ← MqttBridgeService subscribes `+/presence/#`
                                        (both `cabin/presence/*` and `home/presence/*`)
{location}/presence/{person}/last_seen
                                     ← retained ISO-8601 UTC time of the phone's last
                                        Companion-app report to HA (W-34, 2026-10-04).
                                        Not a presence value: it only says how old the
                                        presence signal's source is. Absent until the
                                        HA heartbeat automation (W-23) is applied --
                                        the hub and the siren gate treat "no heartbeat"
                                        as "age unknown", never as stale
{location}/security/armed_away       ← MqttBridgeService subscribes `+/security/armed_away`
zigbee2mqtt/bridge/devices           ← Zigbee2MQTT device list
zigbee2mqtt/bridge/state             ← bridge health heartbeat
zigbee2mqtt/{friendly_name}          ← per-device state
zigbee2mqtt/{friendly_name}/set      ← commands to device
zigbee2mqtt/bridge/request/permit_join
home_z2m/...                         ← Home's own Zigbee2MQTT instance,
                                        same shape as zigbee2mqtt/... above,
                                        distinct base_topic so it doesn't
                                        collide with cabin's bridge on the
                                        shared broker (added 2026-09-11 —
                                        see docs/MAINTENANCE.md's Home
                                        Location section)
home/network-scan/request            ← cabin-backend → Home's Termux
                                        phone: opens/closes a time-boxed
                                        mDNS scan window (added 2026-09-11,
                                        see docs/MAINTENANCE.md — mDNS
                                        can't cross Tailscale, so the scan
                                        has to run phone-side, not in
                                        cabin-backend)
home/network-scan/results            ← phone → cabin-backend: one message
                                        per device the scan finds
```

---

## API endpoints (current — verified against source 2026-08-06, not carried
forward from an earlier session's list)

| Method | Path | Controller |
|---|---|---|
| GET | `/api/devices` | DeviceController |
| GET | `/api/devices/{id}` | DeviceController |
| POST | `/api/devices` | DeviceController |
| PUT | `/api/devices/{id}` | DeviceController |
| DELETE | `/api/devices/{id}` | DeviceController |
| POST | `/api/devices/{id}/command` | DeviceController |
| GET | `/api/devices/meta/types` | DeviceController |
| POST | `/api/devices/permit-join` | DeviceController |
| POST | `/api/devices/network-scan` | DeviceController — opens/closes a time-boxed mDNS scan window on Home's LAN via the phone-side scan agent (added 2026-09-11) |
| GET | `/api/devices/{id}/config` | DeviceController |
| GET | `/api/devices/display-config` | DeviceController |
| GET | `/api/devices/{id}/display-config` | DeviceController |
| DELETE | `/api/devices/{id}/display-config` | DeviceController |
| GET | `/api/dashboard/config` | DashboardController |
| GET | `/api/events` | EventController — real, Postgres-backed (`?camera=&limit=&window=`). No longer a stub as of 2026-08-04; the "(stub)" note in earlier versions of this file was stale |
| GET | `/api/presence` | PresenceController — auto-derived from real MQTT presence signals when any exist (`autoDerived`/`signals[]`), manual fallback otherwise (added 2026-08-08). W-34 adds `lastSeen`, `signalAgeSeconds`, `stale`, `staleAfterHours` (all null/false until a phone heartbeat has been seen); the UI shows "Presence unknown" when `autoDerived && stale` |
| PUT | `/api/presence` | PresenceController — manual override |
| GET | `/api/security` | SecurityController — armed/disarmed per location, keyed by location, from `cabin/security/armed_away` (added 2026-08-08) |
| GET | `/api/signal-quality` | SignalQualityController — prototype, Zigbee LQI trend/anomaly per device, not wired to any alert path yet (added 2026-08-08) |
| GET | `/api/system/health` | SystemController |
| GET | `/api/camera/list` | CameraMediaController |
| GET | `/api/camera/events/{frigateEventId}/snapshot` | CameraMediaController |
| GET | `/api/camera/events/{frigateEventId}/clip` | CameraMediaController |
| GET | `/api/camera/{cameraName}/live` | CameraMediaController |
| POST | `/api/camera/{cameraName}/liveview/start` \| `/stop` | CameraMediaController |
| GET/POST | `/api/notes` | NotesController — Family Hub notepad |
| GET/POST | `/api/profiles` | ProfilesController |
| PATCH/DELETE | `/api/profiles/{id}` | ProfilesController |
| GET | `/api/chores/completion` | ChoresController |
| POST | `/api/chores/completion/toggle` | ChoresController |
| GET | `/api/ontology/entities` | OntologyController — reads `docs/ontology.yaml`, bind-mounted read-only |
| POST/GET | `/api/tech-id/findings` | TechIdController — Opportunity Map |
| PATCH | `/api/tech-id/findings/{id}` | TechIdController |
| POST/GET | `/api/tech-id/findings/{id}/actions` | TechIdController — action audit log |
| GET | `/api/rules/vocabulary/triggers` \| `/actions` | RulesController — DB-backed trigger/action catalog (added 2026-08-21, Part D), `supported:false` candidates merged in from `docs/ontology.yaml` at read time |
| GET/POST | `/api/rules/workflows` | RulesController — workflow CRUD |
| GET/DELETE | `/api/rules/workflows/{id}` | RulesController |
| POST | `/api/rules/workflows/{id}/activate` \| `/deactivate` | RulesController |
| POST | `/api/rules/workflows/{id}/fire` | RulesController — manual fire (real as of this session's Part A, was a stub before) |
| GET | `/api/rules/workflows/{id}/executions` | RulesController — execution history |
| GET | `/api/rules/executions/recent` | RulesController — unviewed executions |
| POST | `/api/rules/executions/{id}/view` \| `/clear` | RulesController |
| GET | `/api/kb/nodes` \| `/nodes/{entityRef}` | KnowledgeNodeController — Tiny Helpdesk's own KB read path (`CREDENTIAL_POINTER` content redacted for any non-ADMINISTRATOR caller). Added to this table 2026-09-13 — was live and in use since Sprint 1 (2026-08-30) but missing from this list entirely; documentation drift, not a new endpoint |
| POST | `/api/kb/curate` | KnowledgeNodeController — the only way to write `SETUP`/`TROUBLESHOOTING`/`CREDENTIAL_POINTER` chunks (KB Generator v1 only ever auto-writes `DESCRIPTION`/`RELATIONSHIP`); body `{entityRef, chunkType, content}`, `source` is ignored and always forced to `MANUALLY_CURATED` server-side regardless of what's sent |
| GET | `/actuator/health` | Spring Actuator — what `deploy-cabin-backend.yml`'s health-check gate polls |

**Route groups missing from the table above** (added 2026-10-04, read from
the controllers and `WebConfig`; this replaces an earlier "Not yet built:
`/api/alerts/active`" note, which went stale when `AlertController` shipped).
`WriteGateAuditTest` fails `mvn test` (which gates the backend deploy) if a state-changing route is neither
interceptor-gated nor on its documented allowlist, so the "who may call"
column for writes is enforced, not just described. Reads follow D14: several
`GET`s are open on purpose.

| Routes | Controller | Who may call |
|---|---|---|
| `GET /api/alerts/active`, `/rules`, `/acknowledgments`; `POST /acknowledgments`; `DELETE /acknowledgments/{alertKey}` | AlertController | `GET` open; acknowledge and snooze need a signed-in session |
| `POST /api/devices/{id}/discovery/run`, `/apply`; `GET .../discovery/latest` | DeviceDiscoveryController | writes need a signed-in session (W-31, merged 2026-10-03, live-verified 2026-10-04); `GET` open |
| `GET`/`POST /api/access-tokens`, `DELETE /{id}` | CabinAccessTokensController | signed-in admin only; never a guest token |
| `/api/managed-users` (list, create, `/{id}/deactivate`, `/reactivate`, `/invite`, `/magic/{token}/consume`) | ManagedUsersController | admin; `consume` is exempt because the single-use magic-link token is the credential |
| `POST /api/auth/session`, `POST /api/auth/session/revoke` | AuthController | `session` needs a Google token, managed session or existing CabinSession; `revoke` is open on purpose (holding the token is the proof) |
| `GET /api/cross-domain/cabin-status-summary` | CrossDomainController | gated, plus a per-route household-role check (D11) |
| `POST /api/helpdesk/ask` | TinyHelpdeskController | any signed-in session; a guest token gets 403; `CREDENTIAL_POINTER` redaction happens inside the answer |
| `GET /api/platform-import/records`, `/{platform}/proposals`; `POST /{platform}/confirm` | PlatformImportController | `records`: administrator or adult household member; the rest administrator only |
| `GET /api/opportunities`, `PATCH /{id}/status` | OpportunitiesController | administrator only, every method |
| `/api/schedule/rules`, `/api/schedule/holidays` | ScheduleRulesController, HolidaysController | gated |
| `/api/chores/assignments`, `/api/chores/definitions` (CRUD, `reorder`, `restore`) | ChoreAssignmentsController, ChoreDefinitionsController | gated (under `/api/chores/**`) |
| `GET /api/presence/activity` | PresenceActivityController | gated (under `/api/presence/**`) |
| `GET /api/frigate-metrics` | FrigateMetricsController | no interceptor pattern: open `GET` (per-camera health from Prometheus) |
| `GET /api/context/cabin-context.jsonld`, `GET /api/devices/{id}/jsonld` | JsonLdController | open `GET` |
| `POST /api/webhooks/blink-motion` | BlinkMotionWebhookController | its own shared-secret header (`cabin.blinkMotionWebhook.apiKey`); 503 or 401 when the key is unset or wrong |
| `GET`/`POST /api/locations`, `PATCH /{id}`, `POST /reorder`, `DELETE /{id}` | LocationsController | **writes are open, a known gap: W-32.** The UI sends signed-in users' tokens to the `apiBase` this controller stores |

Also corrected: `POST /api/devices/**` and every other non-`GET` under
`/api/devices/**` need a signed-in session as of W-31. The `/api/devices` rows
in the first table above describe the routes, not their gate.

---

## UI panels

> Corrected 2026-08-07 (DoD review) — this list had drifted from
> `PANELS` in App.jsx on two counts: the "Family Hub" nav label (a
> cabin/home launcher grid, not the actual Family Hub app — renamed to
> "My Places" in Phase 7 §2a) and two panels (Camera Events,
> Opportunities) that existed in code but were never added here.

- **My Places** (`FamilyHubPanel`) — cabin/home launcher grid with
  quick-links to each location's admin tools + a link out to the actual
  Family Hub app (`family-hub/family-hub.html`); reorderable (Phase 7 §3)
- **Config** (renamed from "Family Config" 2026-08-08 — it configures
  the whole instance, not just family settings) — Google account
  (real, switchable `useGoogleAuth()` sign-in, not a hardcoded email),
  notification preferences, Remote Access, and Platform (both backed by
  real deploy-time config, `CABIN_INSTANCE_PLATFORM`/
  `CABIN_INSTANCE_REMOTE_ACCESS` — see `docs/REPLICATION.md`)
- **Device Manager** (`DeviceManagerPanel`) — device grid, add/remove/command.
  Group-by sections (Type/Source/Room/Status/Lifecycle/Workflow) collapse
  individually or via one "Collapse all" toolbar button (2026-09-16); a group
  containing an ALARM/CRITICAL device force-expands and its caret disables,
  regardless of the saved collapse preference. Filtering is two independent,
  composable controls (2026-09-16) — a **Parent devices only** toggle
  (structural: top-level device vs. one of its services) and a **State**
  multi-select (`LIFECYCLE_FILTER_OPTIONS`: Candidates/Available/Assigned/
  Saved for later/Ignored, any combination), replacing the old single
  `in_scope`/`parents_only`/`candidates`/`previous` enum that couldn't
  express "parent devices only AND not Candidate." A third composable facet,
  **Imported from** (2026-09-20), appears only while at least one
  platform-imported device (SmartThings/Ring, D10) is listed; it filters on the
  durable `importedFrom` attribute and, like Parent-only/State, is ignored by
  **Review previously exposed**. **Review previously exposed** stays its own toggle (it fetches a different data source) and
  overrides both while checked. See `docs/PRODUCT_NOTES.md`'s 2026-09-16
  entry for the full design reasoning.
- **Monitoring** (`MonitoringPanel`) — KPI tiles + Grafana embed + live MQTT log
  (collapsible per location, 2026-09-16) — has **LocationSwitcher**
  (Cabin / Home / Both) in the toolbar
- **Rules & Alerts** (`RulesPanel`, layout settled 2026-09-17 after three
  same-week passes) — `.rules-cards-row` (`display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr))`) holds
  three responsive columns: `ActiveConditionsCard` (live
  `/api/alerts/active` conditions, each row expandable via "See more" and
  linkable to its device via "Open device" — `ActiveAlert.sourceDeviceId`
  + `pendingDeviceFocus` context, consumed by `DeviceManagerPanel`) +
  `AutomationAlertCard` (last-24h automation alerts); `KafkaStatus` +
  `WorkflowRulesCard` (its workflow list capped at `max-height: 320px`
  with internal scroll, `.workflow-rows-list`, same pattern as
  `.active-conditions-list`); `OptimizationOpportunitiesCard` +
  `BuiltinRules`. No per-card `max-width` anymore — the grid column is
  the width constraint, so cards actually fill whatever space the
  responsive column gives them instead of leaving a gap inside it. The
  Node-RED embed renders full-width **below** this row (its own
  `.rules-layout`/`.rules-nodered-single`/`-split`), not sharing a column
  with anything, since it's the one thing that genuinely wants full width
  once it's loaded. Both alert cards keep the ALARM/CRITICAL force-expand
  floor. (Two earlier same-week layouts — alerts+sidebar in one flex-grow
  row above full-width Node-RED, then Node-RED sharing the alerts'
  column — each fixed one problem and left another; see
  `docs/PRODUCT_NOTES.md`'s 2026-09-16/17 entries for why each didn't
  hold up.)
- **Camera Events** (`CameraEventsPanel`) — authenticated camera
  snapshots/clips/live view, DTM-stamped (Phase 7 §4b)
- **Opportunities** (`OpportunityMapPanel`) — Tech ID Service findings as
  actionable, ontology-linked cards (See/Think/Act) — see
  `docs/PRODUCT_NOTES.md`'s 2026-08-03 review

---

## Current feature state (as of 2026-07-26)

All items below are **complete and pushed to GitHub**:

- `Zigbee2MqttAdapter.java` — Z2M bridge, auto-discovery, `exposes` capability inference
- `DeviceHealthMonitor.java` — stale detection, exponential backoff, `/api/system/health`
- `DeviceManagerPanel` L1→L2→L3: See / Change / Add (254s pairing countdown) / Remove
- `MonitoringPanel` L1→L2→L3: See (KPI tiles + Grafana) / Change/Add (DisplayConfigForm) / Remove
- `PresenceProfile` (AT_HOME / AT_CABIN / AWAY / BOTH_OCCUPIED) — auto-derived from real MQTT presence signals when any exist (`PresenceSignalRegistry`, N-people x M-locations), manual toolbar toggle as fallback (2026-08-08)
- `SecurityStateRegistry` — armed/disarmed per location from `cabin/security/armed_away`, toolbar `SecurityBadge` (2026-08-08)
- `DeviceDisplayConfig` — per-device display overrides keyed by `(deviceId, location, profile)`
- `AutomationRuleService` — presence-aware lock/motion rules; safety rules parallel to Node-RED
- Nav rail alert state machine: unconfigured → watching → warn (<20 min) → critical (≥20 min)
- `useDraggableOrder` — HTML5 DnD reorder, localStorage, ALARM auto-pin; works in both panels
- **ThemeProvider** — 10 synchronized presets across Cabin UI and Family Hub, including Asteroid City
- **Family Notepad** (`family-hub/family-hub.html`) — slide-in/out overlay, right edge, docked with `#chores-card`/`#dashboard-fab`/`#settings-btn`. Full behavioral spec in [`docs/PRODUCT_NOTES.md`](docs/PRODUCT_NOTES.md) § "2026-07-30 — Family Hub: Family Notepad Overlay". Postgres-backed via `cabin-backend`'s `/api/notes` as of 2026-08-01 (cross-device, Google-token gated) — `localStorage` is now an offline mirror/fallback, not the source of truth. Note authorship requires an explicitly-selected "Who am I?" actor as of 2026-08-02 (see PRODUCT_NOTES.md) — never silently defaults.

- **Event severity tiering + ntfy alerting** (2026-08-06) — `AlertSeverityClassifier`
  computes INFO/WARN/CRITICAL per event from its own payload, replacing a
  hardcoded `"INFO"` literal every publish site used to pass regardless of
  content. `NtfyAlertPublisher` pushes a phone notification via ntfy.sh for
  CRITICAL events only (reuses the existing overnight-camera-alert's ntfy
  topic, doesn't stand up a second channel). Also: `Zigbee2MqttAdapter` was
  silently never writing to `cabin_event` at all before this — it updated
  `DeviceRegistry`'s live state but never called `EventPublisher.publish()`,
  so Zigbee motion/contact/etc. activity never reached event history. Fixed
  in the same pass. See `docs/ontology.yaml`'s `event_severity` entity and
  `docs/PRODUCT_NOTES.md`'s 2026-08-06 entry for the full design reasoning.
- **This project's first automated tests** (2026-08-06) — unit tests for the
  classifier and ntfy publisher, plus a Testcontainers integration test
  covering the full Kafka→Postgres event durability path. See "Testing"
  below — getting these to actually run surfaced two pre-existing build
  gaps (Surefire silently running zero tests; a BOM precedence issue
  pinning stale Testcontainers) that had nothing to do with the new tests
  themselves, just never had cause to trip before now.
- **Tested, health-checked, auto-rollback CI/CD for `cabin-backend`**
  (2026-08-06) — see "CI/CD" below. `deploy-family-hub.yml` (below) already
  existed; this is the backend's equivalent, deliberately separate per that
  workflow's own comment about `cabin-backend` needing "considered rollout."
- **See→Think→Act workflow engine, real triggers/actions/vocabulary**
  (2026-08-21) — `WorkflowRuleService` matches real device events (leak,
  door/motion/tamper/battery, plug on/off, freeze-risk, Blink motion,
  armed/presence changes, a generic pull-based HA-entity-changed trigger,
  and a real push bridge for Kidde's CO alarm) against user-created
  workflows, with manual fire, execution history, and Active→Reset /
  Recent→Undo all wired end-to-end (`RulesController`). The creation
  form's trigger/action dropdowns are no longer hardcoded JS — they're
  fetched from `JdbcWorkflowVocabularyStore` (DB-backed, reseeded from
  code on boot, never user-editable) merged with `docs/ontology.yaml`
  `lifecycle_status: candidate` entries shown disabled. See
  `docs/ontology.yaml`'s new `delivery_mode: push|pull|both` field —
  Kidde/Liebherr are each pollable (`pull`) for ordinary state but have a
  separate push alert channel; only Kidde's push channel is bridged so
  far (a real HA automation on the M920q → `cabin/kidde/co_alarm`).
  Liebherr's door-open alert is confirmed to have no corresponding HA
  entity at all (checked both the live HA UI and `entity_registry`
  directly) — hard blocked until the integration itself exposes one, not
  a code gap on this side.
- **Collapsible sections with an alarm/critical safety floor** (2026-09-16)
  — Device Manager group headers, `ActiveConditionsCard`,
  `AutomationAlertCard`, the Monitoring live event log, and the Rules &
  Alerts sidebar's Workflows/Recent-firings lists all gained a
  localStorage-persisted disclosure caret (`useCollapsedSections`,
  `App.jsx`), prompted by a live report that six alerts alone were eating
  roughly a third of the viewport with no ceiling, and Device Manager
  offered no way to collapse a 100+-device Group-by section. A group or
  card holding an active `ALARM`/`CRITICAL` item force-expands regardless
  of the saved preference (mirrors the existing auto-pin-alarm-devices
  behavior in Device Manager's reorder mode) — collapsing for density
  must never be able to hide the exact condition this dashboard exists to
  surface. Also renamed Device Manager's "Show" filter to "Filter" (was
  easy to misread as a second grouping control next to "Group") and
  replaced the "Groups ↔ / Groups ↕" arrow-glyph toggle with explicit
  "Layout: Side-by-side" / "Layout: Stacked" text. Same-day follow-up:
  Device Manager's Filter became two composable facets instead of one
  enum (**Parent devices only** toggle + a **State** multi-select
  dropdown, `LIFECYCLE_FILTER_OPTIONS`/`filterDeviceManagerDevices` in
  `App.jsx`), and `ActiveConditionsCard` gained `max-width: 520px` after
  direct feedback that it stretched full page width for content that
  didn't need it. Second same-day correction: the width cap's single-line
  ellipsis truncation made real content unreadable with no way to reach
  it (a `title` attribute is hover-only, not keyboard/touch-reachable) --
  replaced with a real "See more" expand toggle and an "Open device"
  action (`sourceDeviceId` + `pendingDeviceFocus`, see the Rules & Alerts
  panel entry above). `RulesPanel`'s sidebar (Kafka/Workflows/
  Opportunities/Backend Rules) then went through two shapes same day: a
  flex-grow row beside the alerts (left a dead center gap and idle
  vertical space -- wrong) corrected to Node-RED sharing the alerts'
  own column, sidebar alongside the whole thing (see the Rules & Alerts
  panel entry above for the shape that stuck). Also capped
  `AutomationAlertCard`'s empty state, missed in the first width-cap
  pass. See `docs/PRODUCT_NOTES.md`'s 2026-09-16 entry for the full
  design reasoning; covered by new Vitest cases in `App.test.jsx`.
- **Rules & Alerts layout, third and final pass** (2026-09-17) — replaced
  the "Node-RED shares a column with the alerts" shape with
  `.rules-cards-row`, a responsive 3-column grid holding every compact
  card (alerts, Kafka+Workflows, Opportunities+Backend Rules); Node-RED
  moved out to its own full-width section below. Direct user framing:
  Current Conditions had already proven a compact, internally-scrolling
  card works, so extend that to the rest instead of letting an unloaded
  Node-RED placeholder's width sit empty next to a pushed-out sidebar.
  Dropped the per-card `max-width: 520px` (the grid column is the
  constraint now) and gave `WorkflowRulesCard`'s workflow list the same
  capped-height/scroll treatment as `.active-conditions-list`. See
  `docs/PRODUCT_NOTES.md`'s 2026-09-17 entry.
- **History range contract (W-21, 2026-09-24)** — `GET
  /api/events/telemetry-history` now returns `TelemetryHistoryResponse`
  (`requestedDays`, `effectiveDays`, `clamped`, `points`), not a bare
  array, so a caller can tell it got a shorter range than it asked for
  instead of silently rendering a shorter chart. A new
  `cabin.history.max-days` property (default 60) caps every caller, demo
  and authenticated alike -- previously only the demo path had any
  ceiling at all. D22 Q-DM-2 answered the same day: `cabin.demo.max-
  history-days`' own default moved from 1 to 60 to match, so a demo
  viewer's history now aligns with the normal view instead of clamping
  to the present day. Each `TelemetryDailyPoint` also carries a new
  `partial` flag (the earliest day in a window almost never starts at
  a day boundary, so it never has a full 24 hours of samples; the
  boundary is asked of the database, since `date_trunc('day', ...)`
  buckets by the JDBC session's timezone, not UTC) -- the UI
  labels it "(partial)" rather than silently averaging it in as
  equivalent to a full day. `SensorHistoryPanel`'s Range dropdown drops
  its old 90-day option (nothing ever honored it past the new shared
  ceiling) and shows a "Showing last N days" notice whenever a response
  comes back clamped. See `docs/governance/decisions/ontology-
  decisions.md`'s D22 section (R-DM-8, Q-DM-2, required test 9) and
  `docs/governance/backlog.md`'s W-21 row for the full record, including
  its relationship to PR #106 (which first drafted this same ID and
  scope but shipped no implementation).

**Pending next:**
- Wire real M920q entity IDs into `DeviceRegistry` default seeds
- Swap `notify.mobile_app_YOUR_PHONE` in `CabinAutomations` for real HA mobile app service name
- Armed/presence-aware severity escalation (deliberate MVP scope cut, see `event_severity`'s notes)
- home-hub deployment
- Production Docker Compose with env-var secrets
- If Family Notepad needs to sync across devices: `/api/notes` endpoint + poll/WebSocket push (see limitation in PRODUCT_NOTES.md)

---

## CI/CD (built — the pipelines below are self-hosted-runner GitHub Actions
workflows on the M920q itself; nothing inbound, no SSH secrets in GitHub,
the runner polls GitHub over its own outbound Tailscale connection)

**`deploy-family-hub.yml`** — triggers on push to `main` touching
`family-hub/**` or `cabin-orchestration-platform/**`. Build + restart
`family-hub` and `cabin-ui` (both stateless static builds). No test gate.
Deliberately excludes `cabin-backend` — that service is "stateful/sensitive
enough to warrant their own considered rollout," per that file's own
comment, which is exactly what the next workflow is.

**`deploy-cabin-backend.yml`** (added 2026-08-06) — triggers on push to
`main` touching `cabin-orchestration-platform/backend/**` or the compose
files. Three real properties, not just "rebuild and hope":
1. **`mvn test` gates the deploy.** A failing test means the job stops
   before anything is built or touched — the running container is simply
   never replaced. That's the "revert" behavior: nothing to revert because
   nothing changed.
2. **Images are tagged by git short SHA**, not `:latest` — a new explicit
   `image: cabin-backend:${IMAGE_TAG:-latest}` field on the compose service
   makes this possible (previously unset, so compose auto-named it
   `infra-cabin-backend:latest` and could only ever hold one version).
3. **Health-checked before being trusted**: polls `/actuator/health` for up
   to 60s. On failure, the previous image is automatically redeployed
   *through `docker compose`* (not a hand-built `docker run` — an early
   draft of this did that and would have silently dropped every runtime
   env var, since those live in the compose files, not image metadata;
   caught in review before it ever ran for real). The job still fails
   (visible in the Actions tab) even when the rollback itself succeeds.

**Other workflows (not described above until 2026-10-04):**
- **`deploy-production-stack.yml`** — triggers on push to `main` touching
  `cabin-orchestration-platform/infra/production-stack/**`,
  `docker-compose.m920q.yml` or the workflow, and on manual dispatch.
  Validates, deploys, smoke-tests (MQTT device path, Frigate availability
  topic) and rolls back to the last-known-good compose file, Frigate config
  and images on failure. **It overwrites the live Frigate config
  (`/storage/services/frigate/config.yml`) from git** on a manual run, with no
  prior SHA, or when a merge touches `production-stack/docker-compose.yml`,
  `production-stack/frigate/config.yml` or the workflow; the
  `docker-compose.m920q.yml` trigger alone is validation and smoke only. So a
  hand-edited live Frigate file is reverted by the next such merge. The camera
  smoke test checks Frigate's availability topic, not each camera.
- **`deploy-cabin-discovery.yml`** — triggers on `discovery-service/**`,
  `docker-compose.m920q.yml` or the workflow, and on manual dispatch.
- **`rotate-secrets.yml`** — monthly (06:00 UTC on the 1st) and on manual
  dispatch; automated secrets rotation (see `docs/MAINTENANCE.md`, Secrets).
- No workflow triggers on `pull_request`. The first CI execution of a change
  is the post-merge gate on the M920q, so run touched tests locally before
  opening a PR.

**Manually deploying `cabin-backend` outside this pipeline** (e.g. testing
a change before pushing) still works exactly as before — see "Manual
(cabin-backend)" in `docs/MAINTENANCE.md`.

---

## Testing

**First tests in this project's history, added 2026-08-06** —
`backend/src/test/java/com/cabin/orchestrator/events/`:
`AlertSeverityClassifierTest`, `NtfyAlertPublisherTest` (pure unit, no
Docker needed), `EventPipelineIntegrationTest` (Testcontainers — Postgres
+ Kafka, exercises the real `EventPublisher → Kafka → EventConsumer →
CabinEventService → Postgres` path).

**Before assuming `mvn test` "just works" on a new host**, know that
getting it running on the M920q (where CI actually executes it) surfaced
three real, pre-existing environment/build gaps — none caused by the tests
themselves, they just never had anything to trip on before:

1. **No `maven-surefire-plugin` version was pinned.** Without
   `spring-boot-starter-parent` (this `pom.xml` only imports
   `spring-boot-dependencies` for dependency versions, not plugin
   management), Maven silently fell back to Surefire 2.17 — pre-dates
   JUnit 5, discovers zero tests, reports `BUILD SUCCESS` anyway. Pinned
   to 3.2.5 in `pom.xml`.
2. **The M920q's system Java is JRE-only** (`openjdk-25-jre-headless`,
   no `javac`, no `--release 21` cross-compilation data). A portable
   Temurin 21 JDK lives at `~/.jdks/jdk-21.0.12+8` on that host
   specifically for this (user-space install, no sudo, doesn't touch
   system Java) — `deploy-cabin-backend.yml`'s test step sets `JAVA_HOME`
   to it explicitly and fails with a clear message if it's ever missing.
3. **Testcontainers' own Docker-detection probe hardcodes API version
   1.32**, independent of any configured docker-java client version or
   `DOCKER_HOST`/`DOCKER_API_VERSION` env var — this host's Docker Engine
   (`MinAPIVersion: 1.40`) rejects it outright. Fixed via
   `src/test/resources/docker-java.properties` (`api.version=1.44`) — a
   classpath config file the probe *does* honor, unlike env vars. Not a
   guess: confirmed via raw `curl` against the daemon socket that explicit
   1.44 and unversioned requests both succeed, only the hardcoded 1.32
   probe fails.

---

## Related repos

| Repo | Contents |
|---|---|
| `ImpressiveLLC/FaceoftheCabin` | **This repo** — platform code (UI + backend + infra) |
| `ImpressiveLLC/CabinAutomations` | HA automations YAML (`3dbc5ca` current), pairing guide |
| `ImpressiveLLC/CabinSensorAutomationDetection` | Sensor detection utilities |

---

## Design constraints

- No Python beyond `watchdog.py` **and** the `docs/ai-assistant/` corpus/
  eval tooling (`scripts/build-corpus-inventory.py`,
  `rag/scripts/{eval_pipeline,grade_pipeline}.py`, `scripts/ask_eval.py`) —
  updated 2026-09-10, this constraint had drifted stale as those shipped.
  Both are deliberately narrow, tooling-only exceptions; no Python in the
  actual runtime request path (backend stays Java/Spring Boot, frontend
  stays JS/React).
- No ARM/Pi hardware for the two *production* "main brain" hubs — both
  are x86_64 Lenovo ThinkCentre M920q running the full stack (Postgres,
  Kafka, the Spring Boot backend, Grafana, HA, Node-RED, Frigate), and
  that stays true for Cabin/Home. This does **not** apply to the
  lightweight *local collector hub* role: `ROADMAP.md`'s Phase 8
  (Accessible Hardware Program) is a deliberate, planned ARM/Pi/Android
  target for edge devices that collect metrics and route them to a
  central brain — not a replacement for the M920q-class "brain" role
  itself. **No longer just a plan**: a real bench POC (SMLIGHT SLZB-MR5U
  + Termux on Android, 2026-09-10) confirmed Android/Termux viable for
  this role — see `docs/RUNLOG_2026-09-10_home-collector-mr5u-termux.md`.
  Reconciled here rather than left as a silent contradiction.
- Free/personal-tier remote access only — Tailscale (device platform, cameras/locks/valve)
  and Cloudflare Tunnel on a free personal account (public-facing Family Hub) are both fine.
  This isn't a "Tailscale only" covenant — the goal is keeping the whole template usable
  below enterprise pricing so it stays free to replicate. Paid tiers are a bridge to cross
  only if volume or interop needs force it, not a default to avoid on principle.
- Do not replicate HA automation logic in this platform
- Do not hardcode single-location assumptions — all components must be location-aware
