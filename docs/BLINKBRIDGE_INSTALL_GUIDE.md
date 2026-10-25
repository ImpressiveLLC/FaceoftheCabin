# blinkbridge: host install guide (M920q)

One-time steps to run the rewritten bridge. Run on the M920q after PR #237 is merged and the deploy clone
(`~/FaceoftheCabin-deploy`) has pulled main. Cutover verification and rollback: [runbook](BLINKBRIDGE_CUTOVER_RUNBOOK.md).

## 1. Cut over

```bash
cd ~/FaceoftheCabin-deploy/cabin-orchestration-platform/infra
PROJ=$(docker inspect -f '{{index .Config.Labels "com.docker.compose.project"}}' blinkbridge)   # reuse the running stack's compose project, else mediamtx "name already in use"
docker tag "$(docker inspect -f '{{.Image}}' blinkbridge)" blinkbridge:legacy
docker compose -p "$PROJ" -f production-stack/docker-compose.yml -f blinkbridge/docker-compose.cutover.yml up -d --build --no-deps blinkbridge
```

Needs nothing new: `.env` already has `BLINK_USERNAME`/`BLINK_PASSWORD`; `/storage/services/blinkbridge/config.json` and
`.cred.json` are reused. Confirm `cameras.motion_liveview_enabled` lists AldrichFront (add the driveway only if you
want polling, not just the webhook, to start its liveview).

## 2. Restart on unhealthy (required; Docker does not do this)

```bash
cd ~/FaceoftheCabin-deploy
sudo install -m 755 scripts/host/blinkbridge-watchdog /usr/local/bin/blinkbridge-watchdog
sudo cp scripts/host/blinkbridge-watchdog.service scripts/host/blinkbridge-watchdog.timer /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now blinkbridge-watchdog.timer
systemctl list-timers blinkbridge-watchdog.timer
```

The watchdog checks every 5 minutes and restarts the container when Docker reports `unhealthy`. No autoheal container is
needed. Independently the bridge exits by itself if its loop dies for 10 minutes.

## 3. Check

`docker ps` shows `(healthy)` after ~90 s; `docker logs -t --tail 5 blinkbridge` shows `motion poll ok`;
Uptime Kuma can monitor `http://<blinkbridge ip>:8811/healthz` (200 = fine, 503 = stale).
