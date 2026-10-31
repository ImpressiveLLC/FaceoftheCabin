# Blink cameras: support guide (driveway, AldrichFront)

For whoever answers "the Blink camera is offline". Plain steps first, details after.

## What family and staff see

| On screen | Meaning | Action |
|---|---|---|
| Camera black or "no picture" until you press Watch Live | **Idle.** Blink cameras only stream while watched or after motion | Press Watch Live; picture in ~10-20 s |
| Watch Live says "reconnecting" | Blink is slow or limiting requests | Wait 5 minutes, press once. Do not repeat |
| Camera Health tile red / bridge down | **Down.** The bridge itself stopped | Tell Nate; it normally restarts itself within ~10 minutes |
| Alert only on Blink motion, no video | Webhook worked, live view could not open | Same as "reconnecting" |

## Camera missing from the bridge

`curl -s http://<blinkbridge ip>:8811/healthz` lists `expected`, `live` and `missing`. A camera under `missing` means Blink stopped
listing it (Blink-side error). The bridge re-logs in by itself and reports unhealthy after 15 minutes, then the watchdog restarts it.
Check the log for `camera set shrank`.

## Motion did not start a live view

AldrichFront has no cloud clips, so the bridge cannot see its motion itself. Live view on motion comes from the phone push path.
`last_liveview_request_age_s` in `/healthz` shows when the bridge last got a start request for each camera.

## 30-second triage

1. **A Frigate 404 from mediamtx is normal for an idle Blink camera.** It only has a stream while someone is watching or motion started one.
2. Open the Camera Health tile (Monitoring). It shows whether the bridge is alive.
3. Press Watch Live on the camera. Wait 20 seconds.
   - Picture appears: all fine.
   - "Reconnecting": Blink is slow or throttling us. Wait 5 minutes; the bridge backs off on purpose. **Do not keep pressing.**
   - Nothing, tile says bridge down: go to "Bridge not answering".

## Bridge not answering (M920q)

```bash
docker ps --filter name=blinkbridge --format '{{.Status}}'     # Up 3 days (healthy | unhealthy)
docker logs -t --tail 20 blinkbridge
docker exec blinkbridge wget -qO- http://127.0.0.1:8811/healthz
```

| You see | Meaning | Do |
|---|---|---|
| `(healthy)`, `motion poll ok` every 30-40 s | bridge fine | camera/Blink side; check the Blink app |
| `motion poll failed ... throttled` / `leaving Blink alone for 300s` | Blink rate-limiting | wait; it retries by itself (5 to 30 min) |
| `blink login failed` repeating, `2FA required` | token expired or Blink wants a code | Nate: remove `/storage/services/blinkbridge/.cred.json`, restart once, complete 2FA per MAINTENANCE.md |
| `(unhealthy)` | loop stale 2 min, or no good Blink poll for 30 min | watchdog restarts it; if not installed, `docker restart blinkbridge` |
| last log line is hours old, no health status | legacy image (the hang) | `docker restart blinkbridge`, then cut over |
| `ffmpeg exited ... Broken pipe` once | mediamtx dropped the publisher | self-heals in seconds |

Never restart in a loop: each restart logs in to Blink again, and Blink throttles.

## Who gets told

The Blink heartbeat/health tile (PR #196/#235) and Uptime Kuma. Push alerts need `CABIN_ALERT_NTFY_TOPIC` (W-51).

## Facts

- Streams exist only during a liveview (max 5 min manual, 90 s motion). Idle = 404, by design (battery cameras).
- mediamtx paths: `outdoor_4_-_dhee` (driveway), `aldrichfront`.
- Control API `:8811` reachable from the compose network only; `/healthz` is on the same port.
- Runbook: `docs/BLINKBRIDGE_CUTOVER_RUNBOOK.md`. Source: `cabin-orchestration-platform/infra/blinkbridge/`.
