# CLAUDE.md — smrekar-platform (FaceoftheCabin)

This file gives Claude Code full context for every session — on any machine,
at any location.

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

## Deployed cabin stack (as of 2026-07)

The cabin M920q is **live**. Home Assistant + Zigbee2MQTT are running.
Hardware is **ordered but not yet paired** (SONOFF + THIRDREALITY devices,
order #sn42122, 2026-07-18). Cabin also lost power/connectivity 2026-07-18,
so pairing is on hold.

Zigbee devices to be paired (see `ImpressiveLLC/CabinAutomations/PAIRING_GUIDE.md`
for full sequence and friendly name assignments):

| Friendly name | Device | Role |
|---|---|---|
| `door_front_contact` | SNZB-04P | Front door contact |
| `door_second_contact` | SNZB-04P | Second door contact |
| `motion_entry_occupancy` | SNZB-03PR2 | Entry motion |
| `leak_bosch_washer` | SNZB-05P | Leak under Bosch washer |
| `leak_lg_washer` | SNZB-05P | Leak under LG washer |
| `leak_liebherr_fridge` | SNZB-05P | Leak under fridge |
| `temp_mech_room` | SNZB-02WD | Temp/humidity, mech room |
| `temp_kitchen` | SNZB-02WD | Temp/humidity, kitchen |
| `probe_bathroom_wall` | SNZB-02LD | Pipe probe, bathroom outer wall |
| `light_entry` | ZBMINIR2 | Entry light switch (router) |
| `leak_alarm_mech_room` | THIRDREALITY Drip Detect | Mech room siren |
| `leak_alarm_bathroom` | THIRDREALITY Drip Detect | Bathroom siren |
| `leak_spare_siren` | THIRDREALITY Drip Detect | Intrusion deterrence siren |
| `heater_mech_room` | THIRDREALITY Smart Plug | Mech room heater |
| `deterrent_radio_light` | THIRDREALITY Smart Plug | Radio + lamp strip |
| `router_tripwire_a` | THIRDREALITY Smart Plug | RF tripwire node A |
| `router_tripwire_b` | THIRDREALITY Smart Plug | RF tripwire node B |
| `main_water_valve` | Zigbee valve actuator | Main water shutoff |

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

### 5 — On the cabin M920q (Linux, full stack)
```bash
cd ~/cabin-orchestration-platform/infra
docker compose up -d                    # all 8 services
cd ../backend
./mvnw spring-boot:run
```
Point Zigbee2MQTT at the SONOFF ZBDongle-E: `serial.port: /dev/ttyUSB0` in Z2M config.

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

## Next task: Zigbee Device Manager

**Full brief:** `ImpressiveLLC/CabinAutomations/CLAUDE_CODE_BRIEF_device_manager.md`

Summary:
1. **`Zigbee2MqttAdapter.java`** — subscribes `zigbee2mqtt/bridge/devices`,
   per-device state topics; publishes permit_join and `/set` commands;
   translates Z2M device JSON → `DeviceDescriptor` via `exposes` capabilities;
   handles sub-properties (e.g. `water_leak_buzzer`) generically
2. **`DeviceHealthMonitor.java`** — exponential backoff reconnect; last-known-good
   cache with `staleSince` timestamp; `GET /api/system/health`; unknown device
   capability → `UnknownDevice` passthrough (never crash the list)
3. **New API endpoints** — `POST /api/devices/permit-join`,
   `GET|PATCH /api/devices/{id}/config`, `GET /api/system/health`
4. **`DeviceManagerPanel` extension** — L1→L2→L3 nav: See / Change / Add / Remove;
   Add flow has 254s countdown + live "new device found" list;
   on pair success: 3 explicit choices (Add another / Configure it now / See all);
   Remove has confirmation; Advanced link opens Z2M iframe with overlay back button
5. **`ThemeProvider.jsx`** — independently-selectable palette + font;
   CSS custom properties (`--bg`, `--surface`, `--accent`, `--font-display`,
   `--font-mono`, `--success`, `--warning`, `--danger`); presets: LCARS,
   Monolith, Retro-CRT, Bluefin-mono, Modern; persisted to localStorage

**Build order:** See → health badge → Change → Add → Remove → ThemeProvider

**Test against:** live cabin M920q via Tailscale (`cabin-hub:8080`, Z2M at `cabin-hub:8080`)

---

## Related repos

| Repo | Contents |
|---|---|
| `smrekarfamilia-sudo/FaceoftheCabin` | **This repo** — platform code |
| `ImpressiveLLC/CabinAutomations` | HA automations YAML, pairing guide, device brief |
| `smrekarfamilia-sudo/CabinSensorAutomationDetection` | Referenced in CabinAutomations README |

---

## Design constraints

- No Python beyond the existing `watchdog.py`
- No ARM/Pi hardware — both hubs are x86_64 Lenovo ThinkCentre M920q
- Tailscale only — no WireGuard, no DuckDNS
- Do not replicate HA automation logic in this platform
- Do not hardcode single-location assumptions — all components must be location-aware
