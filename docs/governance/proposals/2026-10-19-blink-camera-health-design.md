# Design note: Blink cameras falsely go off-schedule / OFFLINE after a backend restart

Status: **proposed, awaiting Nate's approval. No code written.**
Date: 2026-10-19. Author: Claude Code session `blink-camera-health-fix`. IDs (W-, DL-) are not allocated yet; take them at PR time per `cabin-peer-agent-collaboration`.

## 1. What is wrong

`DeviceHealthMonitor` gives every `CAMERA` a 5-minute threshold (`STALE_CAMERA`, `DeviceHealthMonitor.java:66`). `MISSED` (3x, so 15 minutes) flips `DeviceStatus.state` to `OFFLINE` and raises a `MISSED_CHECKIN` WARN in Rules & Alerts (`ActiveAlertService.java:64`).

A camera's `lastSeen` is refreshed only by `touchCamera()` (`MqttBridgeService.java:364`) and `touchCameraDevice()` (`FrigateEventReconciliationService.java:258`). Both fire only when Frigate publishes a camera motion or object-count topic, or a detection arrives. Those topics fire on change. A Blink camera has no continuous stream (Frigate config, `docs/MAINTENANCE.md:750`), so on a quiet day nothing ever refreshes it.
Two facts make a backend restart the trigger:
- `DeviceRegistry` is in memory, so `lastSeen` resets to start time (retained MQTT topics touch it once at boot).
- Nothing touches it again until Blink's cloud reports motion.

Evidence, live M920q, read-only ssh, 2026-10-19:
- 02:57:36 backend start, 02:57:36 last seen, 5 min later "went off-schedule", recovered 03:48:41 on the next motion/liveview. (From the task brief.)
- **It happened again at ~03:50 UTC** (backend `Up 8 minutes` at 03:58, so recreated again by another session). `/api/system/health` at 03:58 shows `driveway`, `home-cam-driveway` and `front_door` all `LATE`, `lastKnownState UNKNOWN`. `/api/devices/driveway` shows `state: UNKNOWN`, `lastSeen 03:50:02`. Logs: `Z2M device driveway off-schedule, reconnect attempt #1/#2 — waiting for Z2M bridge to push state`.
- Frigate (`cabin/camera/stats`) at the same time: `driveway` and `home_aldrich_front` both `camera_fps: 0.0` with a live capture process (`pid` set), while `process_fps` is about 5. So Frigate reading 0.0 fps is **normal for an idle Blink camera**, not a fault. That resolves the discrepancy in the brief: motion seen about 03:50 was a liveview/motion burst; fps went back to 0.0 afterwards. Hub UI for driveway reads UNKNOWN/LATE today, and would read OFFLINE after 15 quiet minutes.

So `driveway` looks offline because the monitor treats event silence as failure, for a camera whose normal state is silence.

Three wrong behaviours, one root cause each:
1. **Silence is not a liveness signal for motion-gated cameras.** (threshold)
2. **`lastSeen` resets on restart with no replacement signal.** (restart)
3. **The log line lies for non-Z2M devices.** `scheduleReconnect` (`DeviceHealthMonitor.java:267`) keys on adapter `"mqtt"`, which cameras also use, so it says "Z2M device ... waiting for Z2M bridge".

## 2. Options evaluated

| Option | Verdict |
|---|---|
| **A. Per-device `expectedCheckinMinutes`** (monitor already reads it) | Rejected as the fix. Any window short enough to catch a dead camera false-alarms on a normal quiet day; any window long enough (24h+) makes the alarm meaningless. Also camera attributes are written by `registerCandidate` discovery maps that do not set it today, so it needs a per-device config path that does not exist. |
| **B. Exempt motion-gated cameras from time-based staleness** (like D13 `CO_SENSOR`) | Safe against false OFFLINE, but then the platform can never see a dead relay. Acceptable only as defence in depth. |
| **C. Liveness from blinkbridge's "motion poll"** | Not available. Today `docker logs blinkbridge` has **10 lines in 12 hours** and no "poll" lines; the "poll every 30-40 s" in `MAINTENANCE.md:852` does not match current logs. The control port keeps answering when it hangs (watchdog comment), so polling it proves nothing. |
| **D. Liveness from mediamtx** | Not available. The path 404s whenever no liveview is open, healthy or not (`MAINTENANCE.md:845`). |
| **E. Liveness from Frigate's own stats topic** | **Recommended for the restart problem.** Frigate publishes `cabin/camera/stats` about every minute with a per-camera entry, including idle Blink cameras (confirmed live). The backend already subscribes `cabin/#` and ignores this topic. It proves the Frigate-side relay is up and the camera is enabled. It does **not** prove Blink's cloud or blinkbridge is healthy, and the UI wording must not claim that. |

## 3. Proposed change (backend only, one PR)

1. **Heartbeat (fixes 1 and 2).** In `MqttBridgeService.handleCameraTopic`, handle `cabin/camera/stats` (parts length 3, `parts[2]=="stats"`). Parse `cameras.<name>`; for each name that is a key of `BlinkLiveviewService.blinkCameraMap()` (the existing single source for "which cameras are Blink-relayed": `driveway`, `home_aldrich_front`), call `touchCamera(name)`. Presence in stats is enough (0.0 fps is expected). Scope is Blink cameras only, so the stats heartbeat cannot register stray candidates such as the live Reolink name `cabin_outside_reolink`.
   - Parsing lives in a small pure class (`FrigateStatsLiveness`: JSON in, set of camera names out) so it is unit-testable without MQTT.
   - First heartbeat arrives within about 60 s of boot, well inside the 5-minute threshold, so restarts stop producing off-schedule at all.
   - If Frigate or the stats topic stops, the cameras age out to LATE then MISSED as before. That is correct: the relay is down.
2. **Honest log (fixes 3).** `scheduleReconnect` logs the Z2M wording only for Z2M devices (`z2m-` id or `zigbee2mqtt/` connection string). Other devices get no reconnect log (nothing is reconnecting), and the first off-schedule WARN stays.
3. **Optional, your call: motion-gated floor (Option B).** Cameras in `blinkCameraMap` can reach LATE but never MISSED/OFFLINE or a `MISSED_CHECKIN` alert from silence alone, with the Device Manager reason text saying "Motion-gated camera; silence is normal." Cost: if stats stops arriving, nothing flags these two cameras. Recommendation: **skip it** now, since a stats outage already means Frigate is down and that is visible elsewhere.
4. No change to `STALE_CAMERA`, `CheckinStatus` or the UI.

## 4. Tests (all in `src/test`, run by `mvn test`)

- `FrigateStatsLivenessTest`: idle Blink camera with `camera_fps 0.0` is alive; missing/null `cameras`, malformed JSON, non-Blink names ignored.
- `MqttBridgeServiceTest`: a `cabin/camera/stats` message refreshes `driveway.lastSeen` and state to ONLINE; does not register `cabin_outside_reolink`; unrelated 3-part topics stay ignored.
- `DeviceHealthMonitorTest`: a Blink camera whose `lastSeen` is refreshed by the heartbeat stays `ON_SCHEDULE` across a simulated restart (fresh registry, fresh monitor); the same camera with no heartbeat for 16 minutes still goes MISSED (guards against over-exempting); log-wording test with a logback `ListAppender`: camera gets no "Z2M" line, `z2m-` device still does.
- CI: backend tests gate `deploy-cabin-backend.yml` on the M920q after merge. There is **no PR-time backend test workflow** (only intent-slot and taxonomy tooling run on PRs; CLAUDE.md notes this). I will run `./mvnw -o test` (full suite, not just touched classes; Docker-backed classes will error without a daemon) before opening the PR. Adding a PR-time `mvn test` workflow is a separate decision, not in this change.
- No new beans, so no new Spring wiring test needed beyond `MqttBridgeServiceTest`; `FrigateStatsLiveness` is a plain static helper.

## 5. Not in this change (needs your decision or a host action)

- **Blink-cloud / blinkbridge health is still unobservable from the hub.** This change makes the hub stop crying wolf; it does not detect a hung blinkbridge. The only detector is the staged host watchdog (`scripts/host/blinkbridge-watchdog`, restarts after 10 min of log silence; timer is `inactive`). **Do not install it as is**: current blinkbridge logs only about 10 lines in 12 h, so a 10-minute silence limit would restart a healthy bridge repeatedly. Verify the real healthy log cadence first (and note blinkbridge's last line is 03:48:49 "Endpoint throttled", then nothing for 10+ min; whether that is a hang like 2026-10-10 or ordinary idle is unknown). A later step could have the watchdog publish a retained `cabin/blinkbridge/health` heartbeat the backend reads; that needs a sudo install by you.
- `home-cam-driveway` (and the other `home-cam-*` seeds in `DeviceRegistry.java:85-111`) are the retired Home RTSP stack (D23) and sit permanently LATE. Removal belongs with W-59.
- `front_door` (Reolink) is also LATE on quiet nights after a restart, and the live Frigate name is `cabin_outside_reolink` while the platform device is `front_door` (live config differs from git). A continuous camera could use `camera_fps > 0` from the same stats message, but only after the name mapping is settled. Follow-up, not this PR.
- Backend restart frequency (6 recreations in 72 h) is why this surfaced; deploy cadence is a coordination matter for the sessions, not a code fix here.

## 6. Decisions requested

1. Approve the heartbeat approach (E) as the fix? (Recommended: yes.)
2. Add the motion-gated floor (item 3)? (Recommended: no.)
3. OK to leave blinkbridge health, the `home-cam-*` cleanup and `front_door` as separate follow-ups?
