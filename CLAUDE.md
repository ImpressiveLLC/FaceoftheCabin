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
│       ├── api/                     REST controllers
│       ├── automation/              AutomationRuleService (safety rules only)
│       ├── devices/                 DeviceRegistry, DeviceDescriptor, DeviceStatus,
│       │                            DeviceType, DeviceCapability, ProtocolAdapter
│       ├── events/                  CabinEvent record
│       ├── integrations/
│       │   ├── cameras/             CameraIntegration (stub)
│       │   ├── google/              GoogleHomeIntegration (stub)
│       │   ├── homeassistant/       HomeAssistantAdapter (ha_rest — location-aware)
│       │   └── kidde/               KiddeIntegration (stub)
│       ├── kafka/                   EventPublisher → cabin.events.raw
│       └── mqtt/                    MqttBridgeService (subscribes cabin/# and home/#)
│   └── src/main/resources/
│       └── application.yml          All env-var-driven config
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

| Container | Image | Port(s) | Notes |
|---|---|---|---|
| `cabin-mqtt` | eclipse-mosquitto:2 | 1883, 9001 WS | MQTT broker |
| `cabin-postgres` | timescale/timescaledb:latest-pg16 | 5432 | TimescaleDB |
| `cabin-kafka` | confluentinc/cp-kafka:7.6.1 | 9092 | KRaft, no Zookeeper |
| `cabin-grafana` | grafana/grafana | 3000 | TimescaleDB datasource provisioned |
| `cabin-homeassistant` | ghcr.io/.../home-assistant:stable | 8123 | `network_mode: host` (Linux only) |
| `cabin-nodered` | nodered/node-red | 1880 | |
| `cabin-frigate` | ghcr.io/blakeblackshear/frigate:stable | 5000, 1984 | |
| `cabin-watchdog` | python:3.12-slim | — | Polls + restarts dead containers |

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

---

## MQTT topic contract

```
cabin/device/{deviceId}/state
cabin/device/{deviceId}/telemetry
cabin/event/{severity}
cabin/camera/{cameraId}/motion
cabin/system/health
zigbee2mqtt/bridge/devices           ← Zigbee2MQTT device list
zigbee2mqtt/bridge/state             ← bridge health heartbeat
zigbee2mqtt/{friendly_name}          ← per-device state
zigbee2mqtt/{friendly_name}/set      ← commands to device
zigbee2mqtt/bridge/request/permit_join
```

---

## API endpoints (current)

| Method | Path | Controller |
|---|---|---|
| GET | `/api/devices` | DeviceController |
| GET | `/api/devices/{id}` | DeviceController |
| POST | `/api/devices` | DeviceController |
| PUT | `/api/devices/{id}` | DeviceController |
| DELETE | `/api/devices/{id}` | DeviceController |
| POST | `/api/devices/{id}/command` | DeviceController |
| GET | `/api/devices/meta/types` | DeviceController |
| GET | `/api/dashboard/config` | DashboardController |
| GET | `/api/events` | EventController (stub) |
| GET | `/actuator/health` | Spring Actuator |

---

## UI panels

- **Family Hub** — Familia Hub iframe
- **Family Config** — Google OAuth, Tailscale info, platform info
- **Device Manager** (`DeviceManagerPanel`) — device grid, add/remove/command
- **Monitoring** (`MonitoringPanel`) — KPI tiles + Grafana embed + live MQTT log
  — has **LocationSwitcher** (Cabin / Home / Both) in the toolbar
- **Rules & Alerts** (`RulesPanel`) — Node-RED embed + Kafka topic browser

---

## Current feature state (as of 2026-07-26)

All items below are **complete and pushed to GitHub**:

- `Zigbee2MqttAdapter.java` — Z2M bridge, auto-discovery, `exposes` capability inference
- `DeviceHealthMonitor.java` — stale detection, exponential backoff, `/api/system/health`
- `DeviceManagerPanel` L1→L2→L3: See / Change / Add (254s pairing countdown) / Remove
- `MonitoringPanel` L1→L2→L3: See (KPI tiles + Grafana) / Change/Add (DisplayConfigForm) / Remove
- `PresenceProfile` (AT_HOME / AT_CABIN / AWAY / BOTH_OCCUPIED) + toolbar toggle
- `DeviceDisplayConfig` — per-device display overrides keyed by `(deviceId, location, profile)`
- `AutomationRuleService` — presence-aware lock/motion rules; safety rules parallel to Node-RED
- Nav rail alert state machine: unconfigured → watching → warn (<20 min) → critical (≥20 min)
- `useDraggableOrder` — HTML5 DnD reorder, localStorage, ALARM auto-pin; works in both panels
- **ThemeProvider** — 7 presets: Modern, LCARS, Monolith, Retro-CRT, Bluefin-mono, Mad Science, Deep Space (HAL 9000)
- **Family Notepad** (`family-hub/family-hub.html`) — slide-in/out overlay, right edge, docked with `#chores-card`/`#dashboard-fab`/`#settings-btn`. Full behavioral spec in [`docs/PRODUCT_NOTES.md`](docs/PRODUCT_NOTES.md) § "2026-07-30 — Family Hub: Family Notepad Overlay". `localStorage`-backed (single-device, not synced — see limitation noted there).

**Pending next:**
- Wire real M920q entity IDs into `DeviceRegistry` default seeds
- Swap `notify.mobile_app_YOUR_PHONE` in `CabinAutomations` for real HA mobile app service name
- Notification service (email/SMS from automation alert events)
- home-hub deployment
- Production Docker Compose with env-var secrets
- CI/CD deploy automation for `family-hub` (spec below, not yet built)
- If Family Notepad needs to sync across devices: `/api/notes` endpoint + poll/WebSocket push (see limitation in PRODUCT_NOTES.md)

---

## To-do (spec only): CI/CD for family-hub deploy

Today, deploying a `family-hub.html` change to the cabin M920q is the manual
Quick Start in `README.md`: SSH in, `git pull`, `docker compose ... up -d --build family-hub`.
That's fine for one person on one machine, but per the multi-machine workflow
above, this should eventually be automatic on every push to `main`.

**Not built yet.** Spec, for whoever picks this up:

- **Trigger:** push to `main` on `ImpressiveLLC/FaceoftheCabin` that touches
  `family-hub/**` (path filter — no need to redeploy the whole stack for a
  static-HTML-only change).
- **Mechanism (proposed):** GitHub Actions workflow (`.github/workflows/deploy-family-hub.yml`)
  running on a **self-hosted runner on the M920q** (simplest — no need to
  open the Tailscale mesh to GitHub's cloud runners, no SSH secret to manage).
    - Job: `git pull` (or checkout is redundant since runner is on-box —
      just `docker compose -f infra/docker-compose.yml -f infra/docker-compose.m920q.yml up -d --build family-hub`)
    - Alternative if a self-hosted runner is undesirable: GitHub Actions
      `ssh-action` from a cloud runner into the Tailscale IP, using a
      dedicated deploy-only SSH key (not the personal `nate` key) added to
      `authorized_keys` on the M920q with a forced command restricting it to
      the deploy script only.
- **Why Ansible over a raw shell script:** idempotent, and the same playbook
  can extend to `home-hub` once that deployment happens (see "Pending next"
  above) — one playbook, `--limit cabin` / `--limit home` per target, instead
  of two divergent scripts. Structure:
    - `ansible/inventory.yml` — hosts `cabin-hub` (100.77.44.113), `home-hub` (TBD), both reached over Tailscale
    - `ansible/deploy-family-hub.yml` — playbook: git pull → docker compose up -d --build family-hub → health check (curl `/family-hub.html` returns 200)
    - `ansible/roles/docker-service/` — reusable role, parameterized by service name, so the same role later covers `cabin-ui`, `cabin-backend`, etc.
- **Rollback:** keep it simple — `git revert` + re-run the playbook. No blue/green needed for a single-kiosk static file.
- **Out of scope for v1:** backend (Spring Boot) and UI (React) deploys — those need a build step (`mvnw`/`npm run build`) the playbook would also need to own; start with `family-hub` since it's the lowest-risk (static file, no build) and prove the pipeline before extending.

---

## Related repos

| Repo | Contents |
|---|---|
| `ImpressiveLLC/FaceoftheCabin` | **This repo** — platform code (UI + backend + infra) |
| `ImpressiveLLC/CabinAutomations` | HA automations YAML (`3dbc5ca` current), pairing guide |
| `ImpressiveLLC/CabinSensorAutomationDetection` | Sensor detection utilities |

---

## Design constraints

- No Python beyond the existing `watchdog.py`
- No ARM/Pi hardware — both hubs are x86_64 Lenovo ThinkCentre M920q
- Tailscale only — no WireGuard, no DuckDNS
- Do not replicate HA automation logic in this platform
- Do not hardcode single-location assumptions — all components must be location-aware
