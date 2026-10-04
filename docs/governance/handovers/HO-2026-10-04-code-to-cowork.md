# Handover, Code to Cowork, 2026-10-04

Read this from GitHub. It records what Code verified against code on `main` (`8485b7c`), what Code ruled, and what is left for Nate or Cowork. Every claim names its evidence; anything not checked says so.

## Authority for the rulings below

On 2026-10-04 Nate told Code that Code is the authoritative assistant in GitHub for resolving documentation against committed code. Code used that for four discrepancy rulings and a set of status corrections. Each is marked "ruled by Code" in the file it changes. Cowork may amend any of them by PR; none changes code. Code did **not** use it to reverse a Nate decision (see "Not ruled").

## PR map

| PR | State | What |
|---|---|---|
| [#119](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/119) | open | Camera runbook, ontology camera entities, user-guide section, stale "off-network" lines |
| [#120](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/120) | open | Triage rows (W-15 done, W.1 to W.5, DEP.1 to DEP.4, DOC-6), backlog status corrections, discrepancy rulings, this file |
| [#123](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/123) | open | `CLAUDE.md` reconciled with merged code |
| [#105](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/105) | closed unmerged | Redundant: the camera was reconnected out of band. Branch kept for its Wi-Fi-join safety design |
| [#97](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/97) | open, draft | **Do not merge until W.1 lands.** It edits `production-stack/frigate/config.yml`, which the deploy copies over the live file |

Ids `W.1` to `W.5` and `DEP.1` to `DEP.4` are provisional (sub-id form requested by Nate). Ratify or renumber to the next free `W-nn` / `DEP-nn`; #117 and #118 already hold W-33 and W-34.

## Verified against code and the live host

| Claim | Evidence |
|---|---|
| W-31 (the `/api/devices` write gate) is merged, deployed and works | #115 merged `8485b7c`; the M920q runs `cabin-backend:8485b7c`; 2026-10-04 anonymous `POST /api/devices/<nonexistent>/command` returned 401 and anonymous `GET /api/devices` returned 200. `GoogleAuthInterceptorTest` and `WriteGateAuditTest` exist on `main` |
| W-2b (nginx token masking) is done | `cabin-orchestration-platform/ui/nginx.conf` has `log_format cabin_masked` (path only) and `map $uri $cabin_log_uri` replacing the token segment of `/view/*` and `/demo/*`. Shipped inside #102. `docker logs cabin-ui`: 0 lines with `?t=` or a raw token path (a negative check: the log window also held no masked lines). The backlog row named a path that does not exist (`cabin-ui/nginx.conf`) |
| W-21, DOC-1 were still "in review" | #108 merged 2026-10-03 (`9efff1b`); #98 merged 2026-09-24 (`cf66781`) |
| W-6 and W-9 were blocked on #96 | #96 merged 2026-09-24 (`eacf105`) |
| W-4 needs no change | `GuestDashboard` renders no adjuster, mediator or insurance text; only comments and admin-only copy contain them |
| Tier 1 `/api/devices` is unscoped and unredacted | No guest handling in `DeviceController`; `DemoRedactor` acts on demo tokens only; `GuestDashboard` renders every returned device with its `location` (static read, no live Tier 1 link used) |
| `CLAUDE.md` said `/api/alerts/active` was not built | `AlertController` serves it; see #123 for the 16 other route groups the table never listed |
| The camera is back, detection is off | `cabin_outside_reolink`: `camera_fps` about 5, recordings each day since 2026-09-29; `detect.enabled` False, `objects.track` `['person']`, `detection_fps` 0.0 (details in #119 and W-15, W.1) |

## Rulings made by Code

| Entry | Ruling | Effect |
|---|---|---|
| DL-2026-09-23-06 | `ratified deviation` | W-4 closed, no code change. Nate can reopen W-4 with different admin copy |
| DL-2026-09-23-08 | `defect`, closed | Fixed by #102; W-2b closed |
| DL-2026-09-23-11 | `defect` (in W-2b's text), closed | Same fix |
| DL-2026-09-23-09 | `defect` | Tier 1 must be scoped to the cabin and redacted before a link goes to a third party. New item W.4 |

## Not ruled, and why

**DL-2026-09-23-10** (anonymous `GET /api/devices` shows presence-class devices). The code and D22 Q-DM-3 already agree, so no document needs amending. Whether to redact for anonymous callers reverses part of D14, which Nate decided on 2026-09-04 and asked not to see re-added without a conversation, and Q-DM-3 waits on him explicitly. Code recommends redacting presence-class devices for callers who are not signed-in household principals, with a kiosk-compatible exemption, because D14's own test is failed by the owner's phone entities and the Home locks. That is W.5, `held`, **Nate to decide**.

## Needs Nate

1. **Camera password (W.1).** The live RTSP URLs hold a literal password that differs from the vault-fed `FRIGATE_RTSP_PASSWORD`. Confirm the real one and align the vault and `.env`; only then can the config-sync PR be written.
2. **Egress path (W.2).** `eno2` now carries the default route (metric 100) ahead of Wi-Fi (600). `never-default`, or accept.
3. **D14 on anonymous device reads (W.5).**
4. **How the camera was reconnected, and who renamed it.** The records show the cable going live 2026-09-29 17:47 and the config edit 2026-09-30 02:46; intent is Nate's to confirm.

## Needs Cowork

- Score W.1 to W.5 (no CoD components given) and ratify or renumber the provisional ids.
- Answer the four questions in #120 (DEP.2 intent, DOC-6 rework or close, id form, scoring).
- Correct the Task 3 row for #105 in `HO-2026-10-03.md` (on #112, not on `main` yet): it says "Awaiting the camera's current IP", which is stale.
- Decide whether `GET /api/frigate-metrics` (per-camera health) and the JSON-LD routes should stay open; Code documented them as open and made no judgement (#123).
- When D23 (#116) is ratified, rewrite `CLAUDE.md`'s "Two locations, two hubs" table; #123 only adds a "Current reality" note above it.

## Not verified

- Route gates in #123 were read from source and the allowlists in `WriteGateAuditTest`, not exercised live (except the two probes above).
- The duplicate `main_water_valve` row and the Recent Readings field choice in DL-2026-09-23-09 need a live Tier 1 link; Code did not mint one.
- `W-1` (live-verify the four guest scopes) and `W-3` (shared R-GD handler for Tier 1) were not re-examined; the only `GUEST_READ_ONLY` code found is in `DemoAccessFilter`.
- #97 and #119 both rewrite the stale "zero git history" paragraph in `docs/MAINTENANCE.md`; whichever merges second conflicts. #119's version is the corrected one.
