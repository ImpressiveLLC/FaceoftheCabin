# Handover, Code to Cowork, 2026-10-04

> **Ids converted 2026-10-04** by [HO-2026-10-04-cowork-to-code.md](HO-2026-10-04-cowork-to-code.md) section 1: `W.1` is W-39, `W.2` W-40, `W.3` W-41, `W.4` W-42, `W.5` W-43, `W.6` W-44. `DEP.1` to `DEP.4` stay provisional (Codex's list). The text below is left as written.

Read this from GitHub. It records what Code verified against code on `main` (`8485b7c`), what Code ruled, and what is left for Nate or Cowork. Every claim names its evidence; anything not checked says so.

## Authority for the rulings below

On 2026-10-04 Nate told Code that Code is the authoritative assistant in GitHub for resolving documentation against committed code. Code used that for four discrepancy rulings and a set of status corrections. Each is marked "ruled by Code" in the file it changes. Cowork may amend any of them by PR; none changes code. Code did **not** use it to reverse a Nate decision (see "Not ruled").

## PR map

| PR | State | What |
|---|---|---|
| [#119](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/119) | open | Camera runbook, ontology camera entities, user-guide section, stale "off-network" lines |
| [#120](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/120) | open | Triage rows (W-15 done, W.1 to W.6, DEP.1 to DEP.4, DOC-6), backlog status corrections, discrepancy rulings, this file |
| [#123](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/123) | open | `CLAUDE.md` reconciled with merged code |
| [#105](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/105) | closed unmerged | Redundant: the camera was reconnected out of band. Branch kept for its Wi-Fi-join safety design |
| [#97](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/97) | open, draft | **Do not merge until W.1 lands.** It edits `production-stack/frigate/config.yml`, which the deploy copies over the live file |

Ids `W.1` to `W.6` and `DEP.1` to `DEP.4` are provisional (sub-id form requested by Nate). Ratify or renumber to the next free `W-nn` / `DEP-nn`; #117 and #118 already hold W-33 and W-34.

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

**DL-2026-09-23-10** (anonymous `GET /api/devices` shows presence-class devices). The code and D22 Q-DM-3 already agree, so no document needs amending. Whether to redact for anonymous callers reverses part of D14, which Nate decided on 2026-09-04 and asked not to see re-added without a conversation, and Q-DM-3 waits on him explicitly. Code first recommended redacting presence-class devices for callers who are not signed in, with a kiosk exemption. **Nate corrected that on 2026-10-04:** it needs concurrent multi-user sessions and presence-based roles that do not exist, so Code now recommends holding at the status quo. D14's own test is still failed by the owner's phone entities and the Home locks, so this stays visible as W.5 (`held`), blocked on W.6.

**W.6 is Nate's own proposal, for later evaluation.** His stated use case, for Cowork to turn into a UC: the admin signs in with OAuth and is the primary user, and any other user signed in while the admin is present gets the same CRUD. When the admin is away and the daughter brings friends, she can sign in (first or later) with a household role of her own, not admin; the friends can see, think and act (presence-based control) but cannot change CRUD or users from the kiosk until a primary-role user authorizes it. Nate will work specific use cases. Code read the current state from code (one principal per session; `CHILD` and `KIOSK_DISPLAY` roles exist without derivation; no concurrent-session model, no presence-to-authorization link) and listed the open questions in the W.6 row. Code has not proposed a design.

## Needs Nate

1. **Camera password (W.1).** The live RTSP URLs hold a literal password that differs from the vault-fed `FRIGATE_RTSP_PASSWORD`. Confirm the real one and align the vault and `.env`; only then can the config-sync PR be written.
2. **Egress path (W.2).** `eno2` now carries the default route (metric 100) ahead of Wi-Fi (600). `never-default`, or accept.
3. **Specific use cases for W.6**, which W.5 now waits on, then the D14 ruling (W.5).
4. **How the camera was reconnected, and who renamed it.** The records show the cable going live 2026-09-29 17:47 and the config edit 2026-09-30 02:46; intent is Nate's to confirm.

## Needs Cowork

- Turn W.6 into a UC (candidate UC-1, UC-5) and score it with W.1 to W.5 (no CoD components given) and ratify or renumber the provisional ids.
- Answer the four questions in #120 (DEP.2 intent, DOC-6 rework or close, id form, scoring).
- Correct the Task 3 row for #105 in `HO-2026-10-03.md` (on #112, not on `main` yet): it says "Awaiting the camera's current IP", which is stale.
- Decide whether `GET /api/frigate-metrics` (per-camera health) and the JSON-LD routes should stay open; Code documented them as open and made no judgement (#123).
- When D23 (#116) is ratified, rewrite `CLAUDE.md`'s "Two locations, two hubs" table; #123 only adds a "Current reality" note above it.

## Not verified

- Route gates in #123 were read from source and the allowlists in `WriteGateAuditTest`, not exercised live (except the two probes above).
- The duplicate `main_water_valve` row and the Recent Readings field choice in DL-2026-09-23-09 need a live Tier 1 link; Code did not mint one.
- `W-1` (live-verify the four guest scopes) and `W-3` (shared R-GD handler for Tier 1) were not re-examined; the only `GUEST_READ_ONLY` code found is in `DemoAccessFilter`.
- #97 and #119 both rewrite the stale "zero git history" paragraph in `docs/MAINTENANCE.md`; whichever merges second conflicts. #119's version is the corrected one.

## Outcomes after Cowork's answers (Code, 2026-10-04)

Cowork's file is [HO-2026-10-04-cowork-to-code.md](HO-2026-10-04-cowork-to-code.md). It reached Code as a patch from Nate (Cowork cannot post to GitHub) and was committed on this PR with Cowork as author. Its section 6 task list, in order:

| # | Task | Outcome |
|---|---|---|
| 1 | Apply the id conversion and scores to #120 and #117 | **Done.** #120: W.1 to W.6 are W-39 to W-44 with Cowork's scores, W-15's score confirmed. #117: scores for W-33 to W-38, and W-32's 35 / 2 = 17.5 confirmed. #112: W-23's score (27 / 3 = 9.0). W-39 was re-checked free on every remote branch before converting |
| 2 | Post the conditions on #118 and #122, the merge-order note on #121 and #124; merge #126, #121, #124 after CI | **Conditions and notes posted. Nothing merged.** C-118-1 is in #118's body (rewritten: the sync commit changes the repo flow from dry-run to live sirens, which I verified against the files, `d: true` on `main`) and Nate is asked to acknowledge it there; C-122-1 is built (a test fails if the real phone config is tracked; mutation-checked); C-118-2's safe-test path is in the README. **Merges are held for Nate's go**: each deploys to production (#121 redeploys the backend and UI), and there is no PR CI yet (W-29), so "CI green" can only mean the post-merge gate. One correction to Cowork's file: #121 and #124 conflict in `App.test.jsx` (both extend the same import list), not in `App.jsx`, which merges automatically |
| 3 | Close #97 and #99 with a comment linking Cowork's file | **Done.** Both closed unmerged with comments; both branches kept |
| 4 | Open the W-39 draft | **Done: [#127](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/127), draft.** Key `cabin_outside_reolink`, `detect.enabled: true`, `snapshots.enabled: true`, the same object classes as the other cameras, the `{FRIGATE_RTSP_PASSWORD}` placeholder, Person On Foot additions carried over, `home_aldrich_front` restored to `enabled: true` per Cowork's default. Merge waits on Q1. **Finding:** the hand-written live block had no `detect.enabled`, no `objects.track` and no snapshots, which is why detection never ran and the motion tuning in #97 could never have shown up |
| 5 | W-32, same day | **Done: [#128](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/128).** `/api/locations/**` gated for writes (reads stay open), the add-a-place UI call through `authedFetch`, `WriteGateAuditTest`'s known-gap list now empty, mutation-checked |
| 6 | Governance PR: vault D-NEW and DL entry | **Done: [#129](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/129).** D25 and DL-2026-10-04-01, plus the explicit non-vault-path check and the run id in the commit message, tested by running the shipped shell block against a throwaway origin (13 checks). The playbook itself was not run: it rotates the real Postgres password |
| 7 | Tailscale read-only prototype | **Not run.** Nate's direction on 2026-10-04 supersedes the green light: the phone's Tailscale should not be in the presence loop (remote approvals only). Recorded on W-36 and W-37 with its consequence (below). Nate/Cowork: say if the read-only report is still wanted for completeness |
| 8 | Update #105's row on #112 | **Done** |
| 9 | Wait on Nate for Q1, Q4, Q7 to Q9, Q11, and D23's Q-HD-1 and Q-HD-2 | **Waiting.** Nate's note on receipt was "use the md as guide for now"; that is not the explicit "accept Cowork's recommendations" the file asks for, so the Nate-decides items are treated as guidance and nothing irreversible depends on them. The credential (Q1) in particular is Nate's to align; Code cannot write to the host from sessions where the permission layer refuses remote writes |

**New finding for Cowork, from Nate's constraint (recorded on W-36, W-37 and in `PRESENCE.md` on #122).** With HA not internet-reachable, a phone off the cabin LAN and off Tailscale cannot deliver GPS `leave` events, so GPS presence is arrival-only. The WiFi backup's `not_home` is suppressed while the (now stale) tracker still says `zone.cabin`, so presence can stay `home` after leaving, and after 6 hours W-33's rule makes the gate push-only. In that state there is no siren while away. Absence has to come from the hub's own LAN view (W-37, now the critical path, and blocked by the unconfirmed private-MAC question) or from a source that needs nothing from the phone's Tailscale. This needs a decision, not a code fix.

**Closure list.** Cowork's file lists outcome PRs through #126. Add **#127, #128 and #129**, and **#97 and #99** as closed-unmerged outcomes, to the list the last-merging PR uses when it deletes both files.
