# blinkbridge rewrite: cutover and rollback runbook

Source: [`cabin-orchestration-platform/infra/blinkbridge/`](../cabin-orchestration-platform/infra/blinkbridge/).
Nothing here runs from CI; the merge deploys nothing (no workflow watches that folder except its own tests).
Run on the M920q, by Nate or an operator he names. Not a Windows task.

## What changes, what does not

Same: `BLINK_USERNAME` / `BLINK_PASSWORD`, `/storage/services/blinkbridge/config.json` (same keys), the saved
`.cred.json`, mediamtx paths (`outdoor_4_-_dhee`, `aldrichfront`), and the control API on `:8811`
(`POST /liveview/{name}/start|stop`, `GET /liveview/{name}/status`) that `BlinkLiveviewService` calls.

Different (on purpose):
- No looping "latest clip" publisher. Idle cameras 404 on mediamtx (they already did when idle and healthy).
  Footage is a liveview session: manual Watch Live, the `/api/webhooks/blink-motion` trigger, or
  `motion_liveview_enabled` cameras (AldrichFront). **Driveway motion still opens a liveview through the
  webhook; it no longer does so on its own from polling unless you add it to `motion_liveview_enabled`.**
- `/healthz` on `:8811`, a Docker HEALTHCHECK, bounded timeouts everywhere, throttle-aware backoff.
- ffmpeg publishes over RTSP/TCP.
- `/start` answers 200 with `{"mode":"reconnecting"}` when Blink is throttled (old: 404). 503 until first login.

## Before cutover (read-only, 5 min)

```bash
docker ps --filter name=blinkbridge --format '{{.Image}} {{.Status}}'
docker logs -t --tail 3 blinkbridge          # last line age = hung or alive
cat /storage/services/blinkbridge/config.json | python3 -c 'import json,sys; c=json.load(sys.stdin); print(c["cameras"], c["blink"].get("poll_interval"))'   # never print the password
```

## Cutover

Nate chooses the moment (a restart clears the current hang; nothing is lost, the old bridge is hung anyway).

```bash
cd ~/FaceoftheCabin-deploy/cabin-orchestration-platform/infra   # after the PR is merged and pulled
# 1. keep the old image runnable under a fixed tag
docker tag "$(docker inspect -f '{{.Image}}' blinkbridge)" blinkbridge:legacy
# 2. build and swap (same container name, same env, same volumes)
docker compose -f production-stack/docker-compose.yml -f blinkbridge/docker-compose.cutover.yml up -d --build blinkbridge
```

## Verify (within 2 min)

```bash
docker ps --filter name=blinkbridge --format '{{.Status}}'          # (healthy) after ~90 s
docker logs -t --tail 20 blinkbridge                                # "blink ready", then "motion poll ok" every 30-40 s
docker exec blinkbridge wget -qO- http://127.0.0.1:8811/healthz      # {"ok": true, ...}
curl -s -X POST "http://$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' blinkbridge):8811/liveview/AldrichFront/start"
sleep 15; docker exec frigate curl -s localhost:5000/api/stats | python3 -c 'import json,sys; print({k:v["camera_fps"] for k,v in json.load(sys.stdin)["cameras"].items()})'
curl -s -X POST "http://<ip>:8811/liveview/AldrichFront/stop"
```

Pass: healthy, poll lines on cadence, AldrichFront `camera_fps` > 0 during the test, 404/0 after stop.
Also open Watch Live on the driveway from the UI once.

## Rollback (any time, under 1 minute)

```bash
cd ~/FaceoftheCabin-deploy/cabin-orchestration-platform/infra
docker compose -f production-stack/docker-compose.yml up -d --build blinkbridge   # no override: legacy source at /storage/services/blinkbridge-src
# if that build fails, run the saved image directly with the same mounts/env:
#   docker tag blinkbridge:legacy <compose image name> && docker compose -f production-stack/docker-compose.yml up -d --no-build blinkbridge
```

Legacy source (`/storage/services/blinkbridge-src`) and `blinkbridge:legacy` stay in place until Nate deletes them
after a quiet week. The new bridge writes only `.cred.json` (same format); the old one reads it unchanged.

## Make unhealthy self-heal

Docker's `restart: unless-stopped` does not act on `unhealthy`. Install the existing host watchdog (it now restarts on
`unhealthy` first, and still falls back to log-silence for the legacy image):

```bash
sudo install -m 755 scripts/host/blinkbridge-watchdog /usr/local/bin/blinkbridge-watchdog
sudo cp scripts/host/blinkbridge-watchdog.service scripts/host/blinkbridge-watchdog.timer /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now blinkbridge-watchdog.timer
```

Independent of Docker, the bridge exits (code 70) if its event loop stops ticking for 10 minutes, and
`restart: unless-stopped` brings it back.

## Not changed here

`production-stack/docker-compose.yml` still builds the legacy source (its path is a host-only absolute path and
`deploy-production-stack.yml` would redeploy on edit). Fold the override into it in a follow-up once the cutover has held.
