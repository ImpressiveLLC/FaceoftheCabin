# Cabin Orchestration Platform

Skeleton platform for a Lenovo Tiny / micro-PC cabin control hub.

Target devices:
- Generic IP cameras / RTSP cameras
- Water pressure sensors
- Smart thermostats
- Kidde Wi‑Fi smoke alarm integration placeholder
- Google Home / compatible locks integration placeholder
- Existing Java family dashboard integration

Recommended deployment:
- Proxmox host
- Ubuntu VM or LXC
- Docker Compose stack
- MQTT as the common device/event bus

## Architecture

```text
Devices / Integrations
  ├─ Cameras / RTSP
  ├─ Water pressure sensors
  ├─ Thermostats
  ├─ Kidde smoke alarm placeholder
  └─ Google Home / locks placeholder
        ↓
MQTT event bus
        ↓
Java Spring Boot Orchestrator API
        ↓
Postgres / Timescale-compatible storage
        ↓
React UI shell + embedded existing Java dashboard
```

## Quick start

```bash
docker compose -f infra/docker-compose.yml up -d
cd backend
./mvnw spring-boot:run
cd ../ui
npm install
npm run dev
```

## Main ports

- Backend API: http://localhost:8080
- UI shell: http://localhost:5173
- MQTT: localhost:1883
- Postgres: localhost:5432
- Grafana: http://localhost:3000

## Next steps

1. Add actual device credentials to environment variables.
2. Replace placeholder integrations with real vendor APIs or local MQTT bridges.
3. Mount your existing Java family dashboard under `/family-dashboard`.
4. Add Tailscale for remote access.
5. Add UPS monitoring before deploying at the cabin.
