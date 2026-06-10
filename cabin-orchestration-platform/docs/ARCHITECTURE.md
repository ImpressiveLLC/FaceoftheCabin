# Cabin Orchestration Platform — Architecture Analysis & Expansion Plan

## What the Skeleton Gets Right

The submitted skeleton establishes a defensible starting point:

- **MQTT as the event bus** — correct choice; it's the lingua franca of DIY IoT (Tasmota, ESPHome, Zigbee2MQTT, Home Assistant all speak it natively).
- **Spring Boot 3.x / Java 21** — good on a Lenovo Tiny with 32 GB RAM; virtual threads (Project Loom) available for cheap async I/O.
- **Vite + React UI** — fast build, low overhead.
- **Postgres + Grafana in Compose** — right primitives for time-series and dashboarding.
- **Home Assistant as a bridge recommendation (docs)** — the integration strategy doc is exactly correct; don't re-invent HA's 3,000+ integrations.

---

## What Needs Expansion

### 1. Five Capability Surfaces (UI Navigation Model)

The skeleton has one flat view. You need five docked/expandable panels:

| Panel | Role |
|---|---|
| **Family Hub** | Iframe/embed of Smrekar Familia Hub (calendar, chores, parenting schedule) — read-only bridge |
| **Family Config** | Settings for family members, notification preferences, Google OAuth tokens, schedules |
| **Device Manager** | Add / edit / remove / activate devices; drag-and-drop device card ordering; connection status |
| **Monitoring** | Grafana-embed + live WebSocket telemetry tiles; pressure charts, thermostat trends, camera thumbnails |
| **Rules Engine** | Visual rule builder; alert thresholds; Kafka topic bindings; automation actions |

Each panel should support a **docked** (collapsed to a tab/icon rail) and **expanded** (full-width overlay) mode driven by a single `panelState` context. WebSocket connections are opened lazily when a panel expands and torn down when docked.

### 2. Device Registry — Needs a Capability Model

The current `DeviceType` enum is too flat. Replace with a **capability-based model**:

```java
// DeviceCapability.java
public enum DeviceCapability {
    TELEMETRY,        // device pushes time-series data
    COMMAND,          // device accepts commands
    STREAM,           // device provides a video/audio stream
    ALARM,            // device can fire safety alerts
    PRESENCE,         // device reports presence/occupancy
    CLIMATE,          // HVAC control
    ACCESS_CONTROL    // lock/unlock
}
```

A `DeviceDescriptor` registers itself with a set of capabilities plus a **protocol adapter** (MQTT native, HA REST, Google SDM, RTSP, HTTP poll). This lets you add a Shelly plug, a Zigbee contact sensor, a Nest thermostat, or a Bosch appliance without touching core logic — only a new adapter.

### 3. Integration Layers (in priority order)

#### Tier 1 — MQTT Native (best for DIY, real-time)
- **ESP32 pressure sensor** (already designed in May) → publishes to `cabin/device/water-main-pressure/telemetry`
- **Zigbee2MQTT** → all Zigbee devices (contact sensors, motion, door sensors) bridge to MQTT automatically
- **Tasmota devices** → any flashed Sonoff, generic relay, or plug → MQTT out of the box
- **ESPHome** → any ESP32/ESP8266 project including the cabin pressure monitor

#### Tier 2 — Home Assistant Bridge (broadest device compatibility)
- **Locks** (Schlage, Yale, Kwikset Z-Wave) → Z-Wave JS UI → HA → REST API
- **Bosch dishwasher, LG washer** → HA `bosch_custom` / `smartthinq_sensors` integrations → REST
- **Google Home / Nest thermostat** → HA `nest` integration via Google SDM API
- **Kidde smoke alarm** → HA `kidde` community integration (uses cloud polling) → REST
- **Smart thermostat (Ecobee, Honeywell, Nest)** → HA integration → REST

#### Tier 3 — Direct Cloud APIs (fallback / Google-specific)
- **Google Smart Device Management API** for Nest devices where HA bridge isn't adequate
- **Google Calendar API** (already in Smrekar Familia Hub — reuse existing OAuth tokens from smrekarfamilia@gmail.com)
- **Gmail API** for alert delivery (push notification emails)

#### Tier 4 — Camera (RTSP → Frigate)
- Generic RTSP cameras → **Frigate NVR** (Docker container, connects to Postgres or SQLite)
- Frigate publishes motion events to MQTT → `cabin/camera/{cameraId}/motion`
- WebRTC proxy (go2rtc) for low-latency live view in the browser
- Frigate is community-maintained, GPU/coral accelerated optional

### 4. Rules Engine Architecture

Current `AutomationRuleService` is a stub. Needs:

```
[MQTT Event] → [Kafka Topic: cabin.events.raw]
                    ↓
             [Rules Evaluator Service]
                    ↓
        [Action Dispatcher] → MQTT command / Email / SMS / HA webhook
```

**Community POC to build from:** Node-RED is the dominant DIY rules engine. It speaks MQTT natively, has a visual drag-and-drop flow editor, and connects to Kafka, HA, Google, email, and SMS without custom code. Run it as a sidecar container.

For Kafka: a single-broker Kafka (KRaft mode, no Zookeeper) on the Lenovo is appropriate. Topic partitioning at this scale is cosmetic — the value is the replay log, ordered delivery, and the ability to plug in a second consumer (alerting microservice) without touching the producer.

### 5. Microservice Health Check / Self-Healing

For the container watchdog pattern you described:

```
[Watchdog Sidecar] (cron every 60s)
  → checks: MQTT broker, Postgres, Kafka, HA REST, Spring Boot /actuator/health
  → on failure: docker pull known-good image tag, docker stop, docker run
  → publishes: cabin/system/health to MQTT for Grafana alerting
```

This is best implemented as a lightweight Python or Bash script in a container that has access to the Docker socket. The community tool **Ouroboros** or **Watchtower** handles image-level auto-update. For service restart from a pinned healthy image, a small custom script is cleaner than either.

### 6. Remote Access

**Tailscale** (already recommended in the skeleton's README) is the right answer. Key configuration:
- Install on the Lenovo host (not inside containers)
- Enable subnet routing: `tailscale up --advertise-routes=192.168.x.0/24` to expose the whole cabin LAN
- Enable MagicDNS so `cabin-hub.tailnet` resolves from your phone
- Tailscale ACLs: allow smrekarfamilia@gmail.com → cabin-hub → all services
- This gives you access to Grafana, Node-RED, HA, the Spring Boot API, and the React UI from anywhere without port-forwarding or dynamic DNS

For Google service access from smrekarfamilia@gmail.com: Tailscale + the existing Google OAuth tokens in the Familia Hub are sufficient. No new OAuth app needed for calendar/Gmail; the tokens already issued to the Familia Hub can be used here if you keep the same `client_id`.

### 7. Persistent Storage Strategy

| Data Type | Store | Retention |
|---|---|---|
| Device telemetry (PSI, temp, humidity) | TimescaleDB hypertable (Postgres extension) | 90 days rolling |
| Device state snapshots | Postgres `device_state` table | Forever |
| Events / alerts | Kafka log (primary) + Postgres (indexed copy) | 30 days Kafka / 1 year Postgres |
| Camera motion clips | Frigate local filesystem | 7 days rolling |
| Rules / automations | Postgres `automation_rules` table | Forever |
| Grafana dashboards | Grafana `grafana.db` SQLite or Postgres backend | Forever |

---

## Revised Stack Summary

```
Remote Access
  └─ Tailscale (host-level, subnet router)

UI Layer (React / Vite, port 5173)
  └─ Five docked panels (WebSocket lazy connections)
  └─ Grafana embed (port 3000)
  └─ go2rtc WebRTC proxy (port 1984)

API Layer (Spring Boot, port 8080)
  └─ DeviceController (CRUD + capability query)
  └─ EventController (Kafka consumer → SSE/WS to UI)
  └─ RulesController (CRUD rules, trigger manual eval)
  └─ DashboardController (familia hub config, Google OAuth proxy)
  └─ ActuatorHealth (for watchdog)

Rules / Automation Layer
  └─ Node-RED (port 1880) — visual flow editor, MQTT + Kafka + HA + Gmail
  └─ AutomationRuleService (Java, backup/simple rules)

Messaging
  └─ Mosquitto MQTT (port 1883 / 9001 WS)
  └─ Kafka KRaft single-broker (port 9092)

Device Bridge
  └─ Home Assistant (port 8123)
      ├─ Z-Wave JS UI (port 8091)
      ├─ Zigbee2MQTT (MQTT)
      ├─ Bosch / LG / Kidde / Nest integrations
      └─ Google SDM (Nest thermostat)
  └─ Frigate NVR (port 5000) — RTSP cameras
  └─ go2rtc (port 1984) — WebRTC live view

Storage
  └─ Postgres + TimescaleDB (port 5432)
  └─ Grafana (port 3000)

System Health
  └─ Watchdog container (Docker socket, cron)
  └─ Watchtower (auto-pull image updates, optional)

Host: Lenovo Tiny, Ubuntu 24.04, 32 GB RAM, 1 TB Kingston SSD
Hypervisor option: Proxmox (recommended) or bare Docker Compose
```

---

## Smrekar Familia Hub Integration Contract

The Familia Hub (family activities / calendar / parenting schedule) stays **standalone**. The Cabin Orchestration Platform connects to it in two ways only:

1. **Iframe embed** in the Family Hub panel — no code coupling, URL-only.
2. **Google Calendar read access** — the platform can read the shared family Google Calendar using the same smrekarfamilia@gmail.com OAuth token already in the Hub. It uses this to suppress overnight alerts (don't alarm about camera motion at 3am if a cabin trip is on the calendar), and to display the parenting schedule in the monitoring panel for context.

No shared database. No shared auth system. The Hub is a separate process on a separate port. The Cabin platform calls it like any external service.

---

## Immediate Next Steps (Prioritized)

1. **Expand `docker-compose.yml`** — add Home Assistant, Kafka, Node-RED, Frigate, go2rtc, Watchdog, TimescaleDB.
2. **Expand `DeviceRegistry`** — implement capability model and `ProtocolAdapter` interface.
3. **Add Kafka dependency to pom.xml** and wire `AutomationRuleService` to consume from `cabin.events.raw`.
4. **Rewrite `App.jsx`** — five-panel nav with docked/expanded state, WebSocket consumer for live tiles.
5. **Write `MqttBridgeService.java`** — subscribes to all `cabin/device/#` topics and routes to `DeviceRegistry` + Kafka.
6. **Add HA REST adapter** — `HomeAssistantAdapter.java` to poll/push HA entities and translate to `DeviceStatus`.
7. **Node-RED starter flows** — pressure alert, smoke alarm escalation, freeze risk rule.
8. **Tailscale install doc** — one-page runbook for cabin deployment.

---

## Community Projects to Pull From

| Project | Use | Link |
|---|---|---|
| **Zigbee2MQTT** | Zigbee device bridge to MQTT | zigbee2mqtt.io |
| **Frigate NVR** | Camera motion detection, RTSP → clips → MQTT | frigate.video |
| **go2rtc** | WebRTC proxy for browser-native camera view | github.com/AlexxIT/go2rtc |
| **ESPHome** | ESP32 firmware for pressure sensor, any sensor | esphome.io |
| **Node-RED** | Visual rules/automation engine | nodered.org |
| **Watchtower** | Auto image update for Docker containers | containrrr.dev/watchtower |
| **Tailscale** | Zero-config mesh VPN, remote access | tailscale.com |
| **TimescaleDB** | Time-series extension for Postgres | timescale.com |
| **HA bosch_custom** | HA community integration for Bosch appliances | github.com/imsnif/bosch-smart-home-cli |
| **HA smartthinq** | LG appliance integration for HA | github.com/ollo69/ha-smartthinq-sensors |
| **HA kidde** | Kidde smoke alarm cloud polling | HA integration library |
