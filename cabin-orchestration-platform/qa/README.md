# FaceoftheCabin QA Runner

Device-aware validation suite for the FaceoftheCabin platform. Stored in git, runs on any machine (Windows dev box or Linux cabin hub) once Python 3.8+ is available.

## Quick start

```bash
# from the repo root or anywhere inside it
python3 cabin-orchestration-platform/qa/cabin_qa.py
```

On Windows (Git Bash or PowerShell):
```powershell
python cabin-orchestration-platform\qa\cabin_qa.py
```

## Options

| Flag | Description |
|------|-------------|
| `--fix` | Attempt auto-remediation of safe failures (npm install, git pull, etc.) |
| `--group GROUPS` | Comma-separated subset: `system,git,env,auth,network,containers,backend,ui` |
| `--machine HOSTNAME` | Override machine detection (e.g. `ilikethelights`, `nates-little-m920q`) |
| `--json` | Machine-readable JSON output — pipe to `reports/` for cross-device comparison |
| `--list` | Print check groups and exit |

## Check groups

| Group | What it tests |
|-------|---------------|
| `system` | Java 21, Maven, Node.js, npm, Docker, Git, Python |
| `git` | Remote URL (ImpressiveLLC/FaceoftheCabin), branch, clean tree, .env in .gitignore, sync |
| `env` | `.env` file, MQTT_URL, HA_URL, HA_TOKEN, POSTGRES_PASSWORD, ui/.env.local, port correctness |
| `auth` | Tailscale state + account, HA token validity, cabin hub reachability |
| `network` | MQTT TCP 1883, MQTT WebSocket 9001, HA 8123, Z2M 8080, Grafana 3002, Node-RED 1880 |
| `containers` | Docker ps, expected FaceoftheCabin containers, port conflict detection |
| `backend` | Spring Boot health, /api/devices count, z2m- device registration, Z2M bridge state |
| `ui` | node_modules, Vite server, .env.local port correctness, backend roundtrip |

## Machine profiles

The script auto-detects by hostname:

- **ilikethelights** → `dev` role (Windows), expects backend on localhost pulling MQTT from cabin Tailscale IP
- **nates-little-m920q** → `cabin-hub` role (Linux), expects full FaceoftheCabin Docker stack alongside the existing cabin containers

Unknown hostnames get a best-effort profile from the `--group` you specify.

## Cross-machine comparison

```bash
# On each machine, save a JSON report:
python3 qa/cabin_qa.py --json > qa/reports/$(hostname)-$(date +%Y%m%d).json

# Diff them to spot environment drift:
diff qa/reports/ilikethelights-*.json qa/reports/nates-little-m920q-*.json
```

## Auth interoperability model

| Layer | Mechanism |
|-------|-----------|
| Network | Tailscale (account: nhsmrekar@gmail.com) — all machines on same tailnet |
| Cabin services | HA Long-Lived Token in each machine's `.env` (gitignored) |
| Token renewal | HA → Profile → Security → Long-Lived Access Tokens; update `.env` then restart backend |
| Cross-validate | `cabin_qa.py --group auth` checks Tailscale account + validates HA token live |

## Known fixes (from deployment history)

| Problem | Resolution |
|---------|-----------|
| `mvnw` calls missing `mvn` | Install Maven separately; mvnw in this repo is a stub |
| PowerShell blocks `npm` | `Set-ExecutionPolicy RemoteSigned -Scope CurrentUser` |
| Backend connects to localhost MQTT | Check `.env` has `MQTT_URL=tcp://100.77.44.113:1883` |
| Port 8090 already in use | `Stop-Process -Id (Get-NetTCPConnection -LocalPort 8090).OwningProcess -Force` |
| Z2M link opens localhost | Check `VITE_CABIN_Z2M_URL` in `ui/.env.local` |
| No z2m- devices registered | Backend didn't connect to cabin MQTT; restart after fixing MQTT_URL |
| Live tiles don't update | Enable WebSocket on mosquitto (port 9001) |
| cabin-hub hostname not resolving | Enable Tailscale MagicDNS in Tailscale tray |
