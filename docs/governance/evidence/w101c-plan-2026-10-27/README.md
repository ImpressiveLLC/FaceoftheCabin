# W-101c plan: Home cleanups from the W-101 census, 2026-10-27

Owner Code; verify Codex; semantics Cowork. **Plan only. Nothing was changed on the platform.** Every step needs Nate's approval before it runs. Source: the W-101 census of 2026-10-26 (raw output private) and `DeviceRegistry.java` seed defaults.

## Step 1: disable `home-cam-driveway` (Cowork ruling 6): WITHDRAWN, do not run

**Nate answered 2026-10-27: a camera does exist at Home behind this row. Do not disable it.** The plan below stays as the record of what would have run and is not to be applied. The open follow-up is the opposite repair: the code seed says disabled while the persisted row is enabled, and D23 says there is no Home hub, so how the camera is reached and whether the seed comment is stale go to Cowork (a documentation or seed correction, not a live change).

| Item | Value |
|---|---|
| Row | `home-cam-driveway`, site home, type CAMERA, protocol rtsp |
| Before | persisted, `enabled=true`, `lifecycleState=ASSIGNED`; no event or telemetry of any kind recorded; the code seed says `enabled=false` ("disabled until home-hub deployed") |
| After | `enabled=false`, `lifecycleState=ASSIGNED` (unchanged); name, type, capabilities and connection untouched |
| How | `PATCH /api/devices/home-cam-driveway/config` with `{"enabled": false}` by a signed-in administrator. `DeviceRegistry.saveConfiguration` keeps an ASSIGNED device ASSIGNED, so the row stays assigned and only stops being monitored |
| Rollback | the same PATCH with `{"enabled": true}`; no other field changes, so this restores the exact before state |
| Check before | `GET /api/devices/home-cam-driveway` shows `enabled=true`; the device list count at Home; no Frigate or Reolink camera is configured for it (D23: there is no Home hub) |
| Check after | the same GET shows `enabled=false`, lifecycle ASSIGNED; Home device count unchanged; the health monitor stops listing it as offline; no other Home row changed |
| Test | `DeviceRegistryTest`: saving `enabled=false` on an ASSIGNED device keeps it ASSIGNED and leaves every other attribute and every other device unchanged; saving `enabled=true` restores it |
| Answered | a camera exists at Home (Nate, 2026-10-27), so this step is withdrawn |
| Approval | Nate, this row |

Why the seed does not already make it disabled: the persisted record overrides the code seed at startup, so the seed's `false` is never read for this row.

## Step 2: Home television duplicates, hold (Cowork ruling 3)

`netscan-lg_webos_tv_oled42c5pua` and `netscan-oled42c5pua` are discovery candidates for one model with zero telemetry. No merge and no change now. Review them with the Home inventory when the Home collector is reviewed. Not a W-101 blocker.

## Step 3: typing fixes, no bulk change (Cowork ruling 7)

`front_door` (typed CAMERA), the main water valve and the dehumidifier (typed HOME_ASSISTANT_ENTITY) go through the W-93 redetect preview, one row at a time, person-approved. Nothing here edits a type.

## Decision for Nate

None open. Step 1 is withdrawn on Nate's answer.
