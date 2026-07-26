# FaceoftheCabin — Deployment Cookbook

One dashboard, multiple machines, any location. The platform runs wherever
you have Docker. The UI is accessible via Tailscale from any device.

---

## Platform concept

```
┌─────────────────────────────────────────────────────────────┐
│               FaceoftheCabin Dashboard (UI)                  │
│  "The face" — monitor cabin, home, or both from anywhere     │
└──────────────────────┬──────────────────────────────────────┘
                       │ via Tailscale
        ┌──────────────┴──────────────┐
        ▼                             ▼
  cabin-hub (M920q)             home-hub (M920q)
  cabin-backend :8090           home-backend :8090
  cabin-ui :4080                home-ui :4080
        │                             │
  existing cabin stack          existing home stack
  mosquitto / HA / Z2M          mosquitto / HA / devices
  nodered / frigate             nodered / cameras
```

The UI baked into `cabin-hub` shows cabin + home (the location switcher).
The UI baked into `home-hub` works the same way in reverse.
From a travel machine you point a browser at either Tailscale IP.

---

## Port allocation — M920q (cabin-hub)

| Port | Service | Owner |
|------|---------|-------|
| 1880 | Node-RED | existing cabin stack |
| 1883 | Mosquitto MQTT | existing cabin stack |
| 3000 | Homepage dashboard | existing cabin stack |
| 3001 | Uptime Kuma | existing cabin stack |
| **3002** | **Grafana** | **FaceoftheCabin** |
| 5000 | Frigate NVR | existing cabin stack |
| **5432** | **TimescaleDB / Postgres** | **FaceoftheCabin** |
| 8080 | Zigbee2MQTT | existing cabin stack |
| 8123 | Home Assistant | existing cabin stack |
| **8090** | **Spring Boot backend** | **FaceoftheCabin** |
| **9001** | **MQTT WebSocket** | existing mosquitto (must enable) |
| **9092** | **Kafka** | **FaceoftheCabin** |
| **4080** | **React UI (nginx)** | **FaceoftheCabin** ← dashboard URL |

**Dashboard URL (cabin):** `http://100.77.44.113:4080` or `http://cabin-hub:4080`

---

## Prerequisites (any hub machine)

```bash
# Docker + Compose plugin
sudo apt update && sudo apt install -y docker.io docker-compose-plugin
sudo usermod -aG docker $USER   # logout and back in

# Confirm
docker --version && docker compose version
```

No Java or Node.js needed on the host — everything runs in Docker.

---

## Deploy on the M920q cabin hub

### 1 — Clone the repo (first time only)

```bash
git clone https://github.com/ImpressiveLLC/FaceoftheCabin.git ~/repos/FaceoftheCabin
cd ~/repos/FaceoftheCabin/cabin-orchestration-platform/infra
```

### 2 — Configure secrets

```bash
cp .env.m920q.example .env
nano .env    # fill in POSTGRES_PASSWORD, HA_TOKEN, GRAFANA_PASSWORD
```

Get the HA token: Home Assistant → Profile (bottom-left avatar) → Security tab → Long-lived access tokens → Create Token.

### 3 — Enable MQTT WebSocket on the existing Mosquitto

The UI uses WebSocket (port 9001) for live KPI tile updates. Edit the Mosquitto config:

```bash
# Find the mosquitto config — usually one of:
docker inspect mosquitto | grep -i source | grep -i conf
# Then add to the config file:
#   listener 9001
#   protocol websockets
docker restart mosquitto
```

### 4 — Check the mosquitto Docker network name

```bash
docker inspect mosquitto | grep -i networkmode
# or
docker network ls
```

Update `mosquitto_net.name` in `docker-compose.m920q.yml` if it differs from `mosquitto_default`.

### 5 — Build and start FaceoftheCabin services

```bash
cd ~/repos/FaceoftheCabin/cabin-orchestration-platform/infra
docker compose -f docker-compose.yml -f docker-compose.m920q.yml up -d --build
```

This starts: `cabin-postgres`, `cabin-kafka`, `cabin-grafana` (port 3002), `cabin-backend` (port 8090), `cabin-ui` (port 4080).

First build takes 3–5 minutes (downloads Java 21 + Node 20 build images).

### 6 — Verify

```bash
# All five FaceoftheCabin containers running
docker ps | grep cabin

# Backend health
curl http://localhost:8090/actuator/health

# Dashboard
# Open browser to http://100.77.44.113:4080
```

### 7 — Update (after git push from any machine)

```bash
cd ~/repos/FaceoftheCabin
git pull origin main
cd cabin-orchestration-platform/infra
docker compose -f docker-compose.yml -f docker-compose.m920q.yml up -d --build
```

Docker only rebuilds changed layers — subsequent updates are fast.

---

## Deploy on the home hub (future)

The home hub follows the same pattern. Create `docker-compose.home.yml` (same structure as `docker-compose.m920q.yml`) adjusted for the home hub's existing stack port layout.

```bash
# On home-hub after cloning repo
cd ~/repos/FaceoftheCabin/cabin-orchestration-platform/infra
cp .env.m920q.example .env    # fill in HOME hub HA token
docker compose -f docker-compose.yml -f docker-compose.home.yml up -d --build
```

---

## Deploy for local dev (Windows / ilikethelights)

```powershell
# Start infra only (no HA — Docker Desktop can't host-network on Windows)
cd "C:\dev\FaceoftheCabin\cabin-orchestration-platform\infra"
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d

# Backend (in a terminal)
cd "..\backend"
.\mvnw spring-boot:run    # runs on :8090

# UI dev server (in another terminal)
cd "..\ui"
npm install
npm run dev    # http://localhost:5173 with HMR
```

---

## Access from anywhere (travel machine)

No install required on the travel machine — just a browser and Tailscale.

1. Install Tailscale on the travel machine and log in as `nhsmrekar@gmail.com`
2. Open browser to `http://100.77.44.113:4080` (cabin dashboard)
3. Use the location switcher in the toolbar to toggle between Cabin / Home / Both

The dashboard is fully functional from any Tailscale-connected device — phone, tablet, laptop, or another desktop.

---

## Multi-machine workflow (keeping everything in sync)

| When | Action |
|------|--------|
| Finishing work on any machine | `git push origin main` |
| Starting work on a different machine | `git pull origin main` |
| After push that changed backend/UI | Run `docker compose ... up -d --build` on affected hub |
| At the cabin without Claude Code | Paste CLAUDE.md URL into Claude web chat for context |

**Single source of truth:** `https://github.com/ImpressiveLLC/FaceoftheCabin`

---

## Service dependency map

```
cabin-ui (nginx :4080)
  └─ proxies /api → cabin-backend (:8090)

cabin-backend (:8090)
  ├─ mosquitto (:1883)       existing cabin container
  ├─ homeassistant (:8123)   existing cabin container (host network)
  ├─ cabin-postgres (:5432)  FaceoftheCabin container
  └─ cabin-kafka (:9092)     FaceoftheCabin container

cabin-grafana (:3002)
  └─ cabin-postgres (:5432)
```

---

## Troubleshooting

**Dashboard shows no devices / API errors**

```bash
curl http://localhost:8090/actuator/health
docker logs cabin-backend --tail 50
```

**Backend can't reach Home Assistant**

HA runs in host network mode. The backend container reaches it via the Docker bridge gateway (`172.17.0.1`). Confirm:
```bash
docker exec cabin-backend wget -qO- http://172.17.0.1:8123/api/ --header="Authorization: Bearer $HA_TOKEN"
```

**MQTT live tiles not updating**

WebSocket requires port 9001 on Mosquitto. Check it's enabled:
```bash
docker exec mosquitto mosquitto_pub -h localhost -p 9001 -t test -m hello
```

**Port conflict on startup**

```bash
ss -tlnp | grep <port>    # find what owns it
docker ps --format "table {{.Names}}\t{{.Ports}}"
```

**Rebuild after code change**

```bash
docker compose -f docker-compose.yml -f docker-compose.m920q.yml up -d --build cabin-backend
docker compose -f docker-compose.yml -f docker-compose.m920q.yml up -d --build cabin-ui
```
