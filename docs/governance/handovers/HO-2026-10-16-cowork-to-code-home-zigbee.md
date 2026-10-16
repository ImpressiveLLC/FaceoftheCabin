# Handover HO-2026-10-16: Cowork to Code, Home Zigbee pairing and the HO-2026-10-04 re-check

Reviewed against `origin/main` @ `f81147f` and the anonymous `GET /api/devices` on the live instance, 2026-10-16 21:20 UTC.

## 1. Incident: a Sonoff sensor at Home never appeared during pairing

**What happened (Nate, 2026-10-16).** Nate put a new Sonoff temperature and humidity sensor into pairing mode at Home, held its button, and opened the 4 m 14 s pairing window in the hub. Nothing appeared.

**Diagnosis (Cowork, from code and live reads):**

1. **Home has no running Zigbee2MQTT, so no coordinator was ever told to accept devices.**
   - The pairing flow (`ZigbeePairingFlow`, `App.jsx`) calls `POST /api/devices/permit-join`.
   - `Zigbee2MqttAdapter.permitJoin` publishes to `<prefix>/bridge/request/permit_join` for each configured bridge.
   - Home's bridge (`home_z2m`) is the Termux collector on phone `G3223`, which drives the SLZB-MR5U over TCP (`192.168.1.142:6638`, per the 2026-09-10 runlog).
   - That phone has been off Tailscale since 2026-09-19, and `home_z2m/bridge/state` is retained `offline` ([CABIN_REACHABILITY.md](../../CABIN_REACHABILITY.md); W-57, verified 2026-10-12).
   - Live read 2026-10-16: 122+ devices, **zero** with a `z2m-home-` id. Home's Zigbee mesh has never reached the M920q since the multi-bridge change.
2. **Unverified: production may not subscribe to Home at all.** `docker-compose.m920q.yml` defaults `CABIN_ZIGBEE_TOPIC_PREFIXES` to `zigbee2mqtt` (cabin only). `home_z2m` is included only if the live `.env` sets `zigbee2mqtt,home_z2m` / `cabin,home`. Code checks.
3. **The workflow hid the failure.**
   - The pairing card promises "This location's Zigbee coordinator will accept new devices for 4 minutes 14 seconds."
   - The flow never checks which bridges are online, or whether a `bridge/response/permit_join` came back.
   - It counts down to "done" with no explanation. This is the defect the workflow test was meant to find.
4. **Verify, do not assume: the permit_join payload.** The adapter sends `{"value": true, "time": 254}`. Zigbee2MQTT 2.x takes `{"time": N}` (0 closes). Code confirms on both bridges that `bridge/response/permit_join` returns `status: ok` with the current payload. If it doesn't, the fix belongs in W-78 as well.

The sensor itself is fine. The `netscan-slzb_mr5u` row reading OFFLINE carries no meaning: netscan devices use `http_poll`, which has no poller (known defect).

### Nate's action now (finishes the workflow test today)

Revive the Home collector per [RUNLOG_2026-09-10](../../RUNLOG_2026-09-10_home-collector-mr5u-termux.md):

1. Phone `G3223`: charge it, join Home Wi-Fi, open Tailscale, start Z2M in Termux.
2. Confirm `home_z2m/bridge/state` goes `online`. Code can check with `mosquitto_sub` on the M920q.
3. Code confirms the live `.env` includes `home_z2m`, adds it if missing (a backend restart), and posts the result on the W-77 PR.
4. Open the pairing window again. Hold the sensor's button about 5 s until the LED blinks fast, within 1 m of the MR5U.

### New items (allocated W-77 and W-78; re-check both are free at commit time)

| ID | Item | UC | D/R | Origin | Owner | CoD | Size | WSJF | Status | Blocked by |
|---|---|---|---|---|---|---|---|---|---|---|
| W-77 | **Home Zigbee collector on an always-on host.** The Termux phone died silently for four weeks. Move Home's Zigbee2MQTT (`base_topic: home_z2m`, serial `tcp://<MR5U>:6638`, MQTT to the M920q's Mosquitto over Tailscale) to an always-on Home machine: the planned Home Tiny PC, or ilikethelights in the interim if it does not sleep. Keep the phone as fallback. **AC:** (1) `home_z2m/bridge/state` online for 72 h; (2) the live `.env` carries both bridges and the M920q logs `2 bridge(s)` at start; (3) a Home Sonoff pairs end to end through the hub's pairing window and shows as `z2m-home-<name>` with readings; (4) a runbook section in `docs/MAINTENANCE.md` replaces the Termux-only steps; (5) a bridge-offline alert reaches the hub within 15 min (shares W-57's monitoring). Consistent with D23: Home stays a collector, no Home Assistant at Home | UC-19, UC-16 | D23, D8 | ad-hoc (Nate 2026-10-16, failed pairing) | Code (setup, runbook); Nate (host at Home) | 6+8+6+7 = 27 | 3 | **9.0** | proposed | Nate: choose the Home host |
| W-78 | **Pairing window tells the truth.** Before opening pairing, `GET` per-bridge state (a small `GET /api/zigbee/bridges` exposing location, online, last health, device count) and show it on the card ("Cabin: ready. Home: offline since 2026-09-19, devices at Home cannot pair"). Disable "Open pairing window" for an offline bridge's location and say why. Show the `bridge/response/permit_join` result per bridge. At timeout with nothing found, name the likely causes in order: bridge offline, device not in pairing mode, out of range. **AC:** Vitest for each state; backend test for the endpoint; a live check with Home offline and online. R-GD unaffected (admin-only flow) | UC-19 | D8, UX journey standard | ad-hoc (Nate 2026-10-16 workflow test) | Code | 4+7+3+5 = 19 | 2 | **9.5** | proposed | none |

W-57 (reviving the collector and probes) stays as is. W-77 is its durable Home-Zigbee half, so W-57's "revive the collector" blocker is cleared by W-77 or by Nate's action above.

## 2. Re-check of HO-2026-10-04-cowork-to-code (Cowork, 2026-10-16)

| Ruling | Still valid? | Current state |
|---|---|---|
| W.1 to W.6 become W-39 to W-44, with scores | **Done** | `a4badc2` |
| UC-1 link waits on W-42 as well as W-3 | **Valid, still open** | W-42 `proposed`, W-3 `green-lit` but unbuilt. The agent link remains gated |
| Camera key `cabin_outside_reolink`; Q2 draft; Q3 eight classes | **Done** | #127 merged 2026-10-10, credential aligned (#133). Detection running since 2026-10-10 06:33 UTC |
| Close #97, re-tune after a 24 h baseline | **Done; next step due** | #97 closed. W-5 unblocked 2026-10-10, so the baseline window has long passed. Code: post the W-5 baseline (from 2026-10-10 06:33 UTC) and open the fresh tuning PR |
| Q5 `eno2` default route: accept, decide on site | **Valid** | W-40 `held`, next site visit |
| Q6 merge #122 and #118 first, then Nate runs the staged script; no blanket SSH rule | **Merge half done; apply half still open** | Both merged 2026-10-12/13. W-23's row still reads "needs apply". The 2026-10-04 files staged in `/tmp/w23` are stale: Code re-stages from `main`, then Nate runs the install. The repo-side gate has been live-siren since #118, so the order (W-23 heartbeat, then W-33 flow) matters. A deploy workflow for `infra/cabin-security/` remains the durable fix: Code raises it at the next free number |
| #118 needs Nate's acknowledgment before merge | **Moot** | Merged 2026-10-13 |
| Q10 banner wording correct, gate unchanged | **Valid** | #124 merged |
| Q12 Tailscale read-only prototype | **Withdrawn** | Superseded by Nate's 2026-10-04 direction: the phone's Tailscale stays out of the presence loop. W-37 and W-47 carry absence detection |
| #126, #121 then #124; close #99; D25 vault exemption | **Done** | All merged; #99 closed; D25 ratified (#129) |
| Nate-decides: Q1 credential, Q7 to Q9 (D24), D23 Q-HD-1 and Q-HD-2 | **Decided** | Credential aligned (W-39). D24 ratified 2026-10-13 (#125). D23 ratified 2026-10-13 (#116) |
| Nate-decides still open: Q4 (accept more false positives), Q11 (hub arm control) | **Open, recommendations unchanged** | Q4: decide on the W-5 before-and-after numbers. Q11: not now |

**Status drift on `main` (Code fixes in the W-77 PR):**
- `backlog.md` shows W-33, W-34 and W-35 as "in review", but #118, #121 and #124 are merged.
- W-23 is merged but not applied.
- DOC-6 is still "held", but #99 is closed: mark it closed.

## 3. Code task list

1. Live read-only checks:
   - The live `.env` Zigbee bridge variables.
   - `home_z2m/bridge/state`.
   - `bridge/response/permit_join` on the cabin bridge, using the current payload.

   Post the results on the W-77 PR.
2. Commit W-77 and W-78 rows (UC-19 is added to `use-cases.md` by this PR) and the §2 status corrections in one governance PR from fresh `main`. Link this file.
3. After Nate revives the collector, add `home_z2m` to the live `.env` if it is missing, and confirm `2 bridge(s)` in the backend log.
4. W-78 build.
5. W-5 baseline and the fresh tuning PR.
6. Re-stage W-23 and W-33 from `main` for Nate's install.

## Closure

This file closes when W-77's AC 1 to 3 pass and W-78 merges. The PR that merges last deletes it.
