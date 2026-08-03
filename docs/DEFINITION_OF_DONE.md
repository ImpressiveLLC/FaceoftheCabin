# Definition of Done — FaceoftheCabin / Family Hub Sessions

> Canonical, but scoped to **sessions, not the app.** This is not a claim
> that the Family Hub is finished — it's the bar every individual work
> session must clear before being considered closed, the same way a sprint
> has an exit checklist independent of whether the whole product is done.
> Treat each session as its own iteration cycle: re-run this checklist at
> the end of any substantial one, not just when asked, regardless of how
> much backlog (see "Next Session — Prioritized Plan" below) remains
> unbuilt. Update the "Last verified" line each time; don't let this doc go
> stale itself.

## HANDOFF (2026-08-02, end of a long session — read this first)

This session ran from the `cabin-postgres` password rotation through a
full four-phase camera-event pipeline. **Everything below is committed
and pushed to `origin/main`** (local HEAD `5260bab` at handoff time,
confirmed zero drift vs. origin via `git fetch` + `git log
HEAD..origin/main` / `origin/main..HEAD`, both empty). No uncommitted
changes, no stray local processes left running (checked `netstat` for
the ports used during testing — clean).

**What's actually live on the M920q right now:** family-hub, cabin-ui,
and cabin-backend all rebuilt and verified against the real public
endpoints (`hub.`/`cabin.`/`api.unicornpingpong.com`) as of Phase 4. The
M920q's own clone was last confirmed at commit `4920440` (see below) —
**docs-only commits after that (including this one) will NOT auto-deploy**,
since the CI/CD workflow only triggers on `family-hub/**` or
`cabin-orchestration-platform/**` changes. That's expected, not a gap —
nothing in the docs-only tail needs deploying.

**⚠️ Found at handoff, not reviewed:** three commits exist on `main`
(`4eb757b`, `86e7e24`, `4920440`) that this session did not make —
authored directly by the user (not `Co-Authored-By: Claude`), timestamped
during this same session, adding a NEW, previously-undiscussed
`cabin-orchestration-platform/infra/cabin-security/` directory (a
Home Assistant "cabin intrusion" automation package, Node-RED flows, and
a Python MQTT-publish bridge script). **This session never reviewed,
tested, or cross-checked these against the MQTT event pipeline built the
same day** (`MqttBridgeService` in `cabin-backend`, which now
subscribes to `cabin/camera/#` and has specific expectations about
Frigate's topic shapes) — there is a real, unverified possibility of MQTT
topic overlap or interaction between the two. **First thing a new session
should do**: read those three commits' diffs, understand what they do,
and check for any collision with `cabin/camera/#` or other topics
`MqttBridgeService` handles.

**Open items, in priority order:**
1. ~~**Ansible not installed on the M920q**~~ — **done, 2026-08-02, second
   check-in.** User ran `sudo apt install -y ansible-core` (confirmed
   `ansible-core 2.20.1`). Vault created and populated from the plaintext
   secrets staged earlier (`/tmp/vault_plaintext.yml`, now deleted).
   `site.yml --tags secrets` and a full real `rotate-secrets.yml` run both
   executed successfully against the live M920q — self-validated (`db:
   UP`), no data loss confirmed independently. Found and fixed two real
   bugs (`ansible/ansible.cfg` for the role search path,
   `env.j2`'s undefined-variable comment) that only surfaced by actually
   running it. **One process note, not a technical gap:** a `git diff`
   run to sanity-check the password rotation printed both the old and new
   `POSTGRES_PASSWORD` values in plaintext into the session transcript —
   caught immediately, fixed by rotating again right after (so the
   exposed value is no longer the live credential) and being careful to
   use presence/absence-only comparisons from then on. Worth remembering
   for any future credential-adjacent debugging: diff by boolean/hash, not
   by raw value, even when it feels like "just checking a diff."
2. **Google OAuth authorized origin** — `cabin.unicornpingpong.com` needs
   adding as an authorized JavaScript origin on the same OAuth client
   `hub.unicornpingpong.com` already uses (Google Cloud Console → APIs &
   Services → Credentials). Without this, cabin-ui's "Sign in with Google"
   (Camera Events panel) shows Google's own origin-mismatch error — not a
   code bug, a one-click console fix.
3. **`driveway` Reolink camera still off the network** — confirmed via a
   full subnet ping sweep (only the router and the M920q itself answer on
   192.168.2.0/24), not just a wrong-IP guess. User confirmed it's
   powered; likely a lost WiFi association. Needs physical presence at
   the cabin (checking for a camera-broadcast setup network, the router's
   own client list, or a manual re-pair via the Reolink app) — explicitly
   paused until the user is back on-site.
4. **Camera video viewing (Phase 6)** — fully planned (`ROADMAP.md` Phase
   6, three new `planned`-status ontology entities), zero code written,
   deliberately deferred to a session with more budget. Read those
   ontology entities and the Phase 6 checklist before starting — they
   contain real, already-gathered facts (Frigate's actual live config
   values, disk capacity) that would otherwise need re-discovering.
5. **`cabin-postgres` default password** — actually resolved this session
   (see Tier 1 #3 below), just listed here for visibility since it was
   the session's starting point.
6. ~~**`CabinAutomations` has two divergent clones on the M920q**~~ —
   **resolved 2026-08-02.** User confirmed the unpushed commit
   (`f44a544`, "add cabin docker-compose with all services") was wanted;
   pushed to `origin/main`. Hit one snag doing it:
   `/home/nate/CabinAutomations`'s remote was HTTPS with no cached
   credentials (`fatal: could not read Username for 'https://github.com'`
   — non-interactive, can't prompt), while the sibling clone
   `/home/nate/repos/CabinAutomations` already used SSH successfully —
   switched this clone's remote to the same
   `git@github.com:ImpressiveLLC/CabinAutomations.git` and the push went
   through clean (confirmed fast-forward, no conflicts, verified before
   pushing). Both clones are now fast-forwarded to `f44a544` and in sync
   with origin. **Still true, lower priority:** two clones of the same
   repo on one host is redundant — worth consolidating to one canonical
   path at some point, but not urgent now that both are in sync and
   neither has drift.

**How to orient in a fresh session:** `docs/ontology.yaml` is current and
fully valid YAML (three pre-existing parse bugs fixed this session) —
every entity touched this session is `migration_status: complete` or
`planned` accurately, with a `notes:` field explaining exactly what's
built vs. not and why. `ROADMAP.md`'s Phase 1/2/6 checkboxes reflect
reality. This file's Tier lists below are current as of this handoff.
Don't re-derive context that's already sitting in these three files.

---

### SECOND CHECK-IN (2026-08-02, later the same day — full §1-§9 re-walk)

Prompted by the direct question "does that include the DoD at the end of
each session?" — the HANDOFF section above covers git/deploy/open-items,
but §1-§9's individual `Status:` blocks hadn't all been re-verified in
this pass. Went through all nine; most concrete outcomes:

- **Corrected a real error in this very file:** §1 previously claimed the
  `H:\My Drive\...\FaceoftheCabin` clone was "deleted." Actually checked
  this time (`ls`, not memory) — it's still physically on disk. What's
  true instead: it was never a functioning git repo (`.git` is an empty
  `info/` folder, no `HEAD`/`objects`/`refs`), so there's nothing to drift.
  Harmless leftover, not a git-reconciliation problem. Lesson: an earlier
  session's "confirmed deleted" was apparently never independently
  verified before being written down — worth remembering that a status
  block is a claim, not automatically a fact, even one written by a prior
  Claude Code session.
- **Ansible install attempted, still blocked** — tried `sudo apt-get
  install -y ansible-core` over the same Tailscale SSH session used all
  along; `nate` does not have passwordless sudo, so it fails with "a
  terminal is required to authenticate." This is not fixable from an
  agent session (no interactive password prompt available, and entering
  a sudo password on the user's behalf isn't something to do even if it
  were). **Still needs the user to run it themselves, interactively:**
  `sudo apt install -y ansible-core`.
- **Verified the camera pipeline is genuinely live**, not just
  committed: `docker port cabin-backend` on the M920q showed the real
  mapping (`8090`, not the `8080` first guessed), and
  `curl http://localhost:8090/api/events` returned real current data —
  live `DETECTION_NEW`/`MOTION_ON` rows for `outdoor_4` (the Blink
  camera) with recent timestamps. Full detail in §5/§6's updated Status
  blocks.
- `docs/ontology.yaml` re-validated clean (80 elements, all camera/secret
  entities present by `id`) and §3/§9 updated with the actual entity-by-
  entity detail of what was added and why. §4 confirmed via `git diff`
  that zero new `localStorage` keys were introduced across the whole
  camera-pipeline commit range. §7 found and fixed one real drift:
  `README.md` line 3 still said "(in progress)" despite line 79 already
  correctly saying "live" — line 3 was missed in an earlier pass.

None of this changes the "Open items" priority list above — Ansible and
Google OAuth origin are still both user-action blockers, `driveway` is
still paused pending physical access, Phase 6 is still unbuilt by design.

---

### THIRD CHECK-IN (2026-08-02, still later the same day — a lot shipped)

Everything below is committed and pushed through `535045f`, confirmed
zero drift across `C:\dev\FaceoftheCabin`, `origin/main`, and the M920q's
clone (all three at `535045f`, clean working trees). In rough order:

1. **Ansible fully closed out** — user ran the apt install; a real
   end-to-end secrets rotation ran against the live M920q and
   self-validated. Two real bugs found/fixed running it for the first
   time (`ansible.cfg` role search path, an undefined template var). One
   process note: a `git diff` briefly printed both old and new
   `POSTGRES_PASSWORD` values into this session's transcript — caught,
   fixed by rotating again immediately, and saved to memory as a standing
   rule (never diff secrets by raw value).
2. **`CabinAutomations` unpushed-commit finding resolved** — a second,
   divergent clone on the M920q had a real unpushed commit
   (`docker-compose.cabin.yml`); user confirmed it was wanted, pushed.
3. **External identity/attribution review, verified and acted on** — the
   user shared a review (from a separate process/tool) of Family Hub's
   identity model. Verified its core claims directly against the code
   before trusting them (found they were accurate): note authorship
   silently defaulted to the first profile with no "Who am I?" selected,
   and could diverge from/outlive the acting context's expiry. Fixed:
   composer now blocks with a "Who's leaving this note?" prompt until an
   actor is chosen, never discards typed text, revalidates at submit
   time. Actor timeout corrected 5min → 3min per user confirmation.
4. **Family profile backend built** (the review's P1) — `/api/profiles`
   on `cabin-backend`, same JdbcTemplate pattern as notes/chores, with a
   one-time migration safeguard so any already-existing local-only
   profile (e.g. a Friends & Family guest from earlier testing) gets
   pushed up instead of silently overwritten by the first sync. Deployed
   and verified: table auto-seeds correctly, auth gate returns 401
   without a token. **Not yet verified**: full authenticated CRUD
   round-trip — deliberately not tested by disabling auth on the live
   public API to do so; needs a real signed-in browser session.
5. **Cabin-security presence detection built** — WiFi-based presence for
   `person.natecabin` (Android phone, MAC-based LAN check via
   `command_line` + `nmap`, since the Starlink router has no HA-supported
   integration and HA's own `nmap_tracker` is config-flow-only in this
   HA version). Deployed, confirmed live and correctly reporting
   `not_home` (matches reality). Found and documented, not fixed
   (user-owned, out of scope to touch uninvited): the live Node-RED
   intrusion-alarm flow has drifted from git — a different arm-state
   topic than the committed HA automation publishes to, live siren
   outputs no longer disabled — user confirmed this was intentional
   (enabled directly in Node-RED).
6. **Phase 6 camera video viewing, built** — Frigate config: pre/post
   capture widened to 15s/60s (both tiers), continuous recording enabled
   at a deliberately conservative 5-day retention (driveway's record
   role turned out to be full 4K, changing the storage math significantly
   from what was assumed at planning time — decided directly with the
   user given the real risk of filling a disk other services depend on).
   New `CameraMediaController` on cabin-backend proxies Frigate's real
   snapshot/clip/live endpoints (confirmed by reading Frigate's installed
   source directly, not assumed) — Frigate reached by Docker hostname
   since cabin-backend is already on its network. Found and fixed a real
   gap: `MqttBridgeService` never captured Frigate's own event id, so
   even stored clips would've been permanently unreachable. cabin-ui's
   Camera Events panel now shows real thumbnails and an expandable clip
   player, plus a live-view button per camera. **Not yet verified**: a
   real signed-in session actually rendering media — no qualifying
   recent event existed at deploy time (`outdoor_4` is currently
   crash-looping on `mediamtx` 404s, a pre-existing issue confirmed via
   logs from hours before this session touched anything, not a
   regression). One real mistake worth remembering: backticks inside a
   double-quoted SSH command got expanded by the *local* shell before
   transmission, splicing local disk stats into the Frigate config and
   breaking its YAML — caught immediately (restored from backup, redid
   the edit via a locally-written file instead of an inline heredoc), no
   lasting damage, but a sharp reminder to never use backticks inside a
   double-quoted outer string when building remote commands.
7. **Liebherr fridge / Bosch dishwasher — documented, not registered.**
   Both integrations exist in the installed HA version, but neither is
   configured — both need account credentials/OAuth consent only the
   user can provide (SmartDevice login for Liebherr; a Home Connect
   Developer OAuth client_id/secret + account consent for Bosch). Exact
   steps given to the user; ontology entities added either way.

**Update, same session, right after the above:** the "`front_door` camera
showed up as a disabled stub, previously unknown" item was resolved
immediately — user clarified both real cameras (Reolink + Blink) sit next
to the front door and cover the driveway from different angles, neither
name device-preferential, one will eventually move to cover the rear
(no ETA). Renamed accordingly: Reolink `driveway` → `front_door`, Blink
`outdoor_4` → `driveway`, deleted the disabled `.201` stub entirely (it
was based on a mistaken "third camera, not yet purchased" assumption).
Applied to the live Frigate config (backed up first), restarted, verified
healthy with no new errors beyond the already-known pre-existing ones.
Historical CabinEvent rows keep their old sourceDeviceId values by
design — not migrated, user confirmed that's fine. See
`docs/ontology.yaml`'s `cabin_camera_event` entity for the full detail.

**Open items, carried forward:** Google OAuth authorized origin for
`cabin.unicornpingpong.com` (still needed for cabin-ui sign-in to work at
all — camera events *and* profile sync both depend on this); the Reolink
camera (now `front_door`) still off-network, paused pending physical
access; Liebherr/Home Connect account linking (user action); real
signed-in verification of both the profile sync and the camera media
proxy; the Blink camera's (now `driveway`) ongoing mediamtx crash-loop
(pre-existing, not new, but still unresolved).

---

**Last verified:** 2026-08-01, this session's work committed and pending push/deploy
(see §2/§6/§8 for the actual outcome of that step). Building on the prior
`73850da` checkpoint (CI/CD runner closed, Tier 1 #1), this session: unified
the calendar/chores-card accent color per theme, fixed Pac-Man/Retro-CRT
heading-font jaggedness and widened the LCARS font, added Pac-Man red
accents, enlarged/gold-accented the Dashboard button in place (a bottom-
center relocation was tried and reverted — real collision risk with
`#bottom-bar` on short-content mobile pages), added a "Friends & Family"
actor role (full parity, per explicit user decision) with a searchable
~90-emoji avatar pack, **built and verified the Tier 1 #2 cross-device
backend** (`/api/notes` + `/api/chores` on `cabin-backend`, Postgres-backed,
`GoogleAuthInterceptor`-gated — see Tier 1 #2 below for full detail), and
added visible "requires Tailscale" hints to `cabin-ui`'s admin-surface
embeds/links per an explicit decision to keep those Tailscale-only. Also
surfaced two real findings that weren't asked for but are load-bearing:
camera *viewing* doesn't actually exist in `cabin-ui` yet (`frigateUrl` is
defined in config, never rendered — status tiles only, no video), and the
`cabin-postgres` default-password gap is now higher-stakes than when first
flagged (public write endpoints landed on that same DB this session) — user
explicitly pinned that fix to the next session. **Not closed out:** stale
`H:\...\FaceoftheCabin` clone still unresolved (Tier 1 #4); camera public
viewing is a real next feature, not yet scoped.

---

## 1. Git is reconciled — one source of truth, no drift

- [ ] Every local clone that gets worked in is either current with `origin/main`
      or its drift is understood and intentional (a feature branch, not orphaned).
- [ ] No clone has uncommitted changes that only exist on one machine.
- [ ] Stale/duplicate clones are identified and either brought current or
      explicitly retired — never left as an ambiguous second "maybe current"
      copy.

**Status — re-checked 2026-08-01:** `C:\dev\FaceoftheCabin` was exactly in
sync with `origin/main` before this session's commit (`git fetch` showed
zero commits either direction) — the only diffs were this session's own
working-tree changes, now committed (see §2).
`H:\My Drive\cabin-orchestration-platform-expanded\FaceoftheCabin` —
**corrected 2026-08-02, second check-in:** an earlier status here claimed
this was deleted; that was wrong — actually checked this time (`ls`, not
assumed) and the directory still physically exists on disk (Drive hasn't
synced a deletion). What's actually true: it was never a functioning git
clone to begin with — `H:\My Drive\cabin-orchestration-platform-expanded\.git`
contains only an empty `info/` folder (no `HEAD`, no `objects`, no `refs`),
so `git status`/`git remote -v` both fail with "not a git repository" at
both the outer Drive root and the `FaceoftheCabin` subfolder. Zero drift
risk either way — there's no working `.git` there to diverge from
`origin/main`. Leftover files on a Drive path Claude Code's environment
still reports as its default working directory, but not a git concern.
Not re-flagging as an open item; if the user wants the stale files
actually removed from disk that's a separate, low-priority cleanup, not a
git-reconciliation problem.

**Re-checked again at end-of-session 2026-08-02** (camera-event-pipeline
work): `git fetch` + `git log HEAD..origin/main` / `origin/main..HEAD`
both empty at every commit boundary through `28569aa` — zero drift
maintained across the whole four-phase build, not just checked once at
the start.

---

## 2. Everything is checked into git — available and current everywhere

- [ ] All working-tree changes relevant to the session are committed.
- [ ] Commit messages describe *why*, not just *what* (the diff already shows what).
- [ ] Nothing environment-specific or secret is committed (tokens, `.env`
      with real values, long-lived credentials).
- [ ] Push happens only with explicit user go-ahead — local commits are
      "done" for review purposes; `git push` is a separate, confirmed step.

**Status — 2026-08-01:** All of this session's work committed on `main`
(`6c7b3cc`, `d769022`) and **pushed**, on explicit request ("perform end of
session DoD steps... confirm when all is live"). `git fetch` before
committing showed `C:\dev\FaceoftheCabin` was already exactly in sync with
`origin/main` (zero drift either direction) — the two new commits are
additive, not a reconciliation. Nothing environment-specific or secret in
either commit — checked the staged diff before committing, only source/docs/
config-with-placeholder-defaults.

---

## 3. Docs & ontology accurately reflect current state at last check-in

- [ ] `README.md`, `ROADMAP.md`, `CLAUDE.md` don't describe removed/renamed
      things, and do describe what actually exists now.
- [ ] `docs/ontology.yaml` has an entry for every user-facing configurable
      concept, matching the real storage keys/functions in code.
- [ ] Docs written *about* a change ship in the *same* commit as the change,
      not as a follow-up someone has to remember.

**Status:** `ROADMAP.md`'s Phase 1 checklist updated this session — checked
off the cross-device backend, domain/Cloudflare Tunnel registration, and the
Google OAuth origin fix (all actually done, previously left unchecked), and
refreshed the `cabin-postgres` password note to match the elevated-priority
framing agreed this session. `docs/ontology.yaml` updated: `chore_completion_state`
now documents the real sync path (was localStorage-only), `chore_daily_success`/
`chore_weekly_success` notes clarify what did vs. didn't ship, new `family_note`
entity added with full CRUD/transformation detail. `README.md`'s Documentation
Index and product table were checked, not touched — already accurate.
Known pre-existing gap (not from this session, not yet fixed): one YAML
entity (`custody_status`) has an invalid `semantic:` value (concatenated
quoted strings) that breaks strict YAML parsers — flagged, not fixed, since
it predates this work and needs its own review pass.

**Update, 2026-08-02 (camera pipeline session):** that `custody_status` gap
plus two more of the same shape (a "Back to Dad's/Mom's" entity, a
rewards-progress entity) were all fixed this session — all three changed
from concatenated `"A" / "B"` strings to a single quoted `"A / B"` value.
Five new entities added across the camera-pipeline commits: `platform_secret`
(`f47c8e5` — Ansible Vault; `migration_status: partial`, honestly reflects
that Postgres rotation is built but Grafana/HA token rotation isn't yet),
`cabin_camera_event` (`2865b90` — the real MQTT→Kafka→Postgres→API pipeline;
`migration_status: complete`, it's actually built and live), and three
Phase 6 planning-only entities `cabin_camera_continuous_recording` /
`cabin_camera_event_clip` / `cabin_camera_live_view` (`5260bab`; all three
correctly marked `migration_status: planned` — no code exists yet, see
ROADMAP.md Phase 6). Re-validated end to end
just now: `python3 -c "import yaml; yaml.safe_load(...)"` parses clean,
80 elements, all of `platform_secret` / `cabin_camera_event` /
`cabin_camera_continuous_recording` / `cabin_camera_event_clip` /
`cabin_camera_live_view` / `family_note` / `chore_completion_state`
confirmed present by `id`.

---

## 4. Local storage is optimized — no accumulating cruft

- [ ] Every `localStorage` key in use is either actively read, or has an
      explicit, time-bounded migration path that **removes the old key**
      once migrated — never kept "just in case" indefinitely.
- [ ] If the simplest correct answer is "wipe and reseed," do that instead
      of writing compatibility shims for data no one needs preserved.

**Status:** No new localStorage keys introduced this session — the
cross-device backend deliberately *reuses* the existing
`smrekar_family_notes`/`smrekar_chore_completion` keys as an offline mirror
(populated from the server response, not a parallel key), so the key
inventory from the last check-in is unchanged (still 11 keys, still none
orphaned). What changed is what those two keys *mean*: they're now a cache
of server state when signed in and CABIN_API_URL is configured, falling
back to being the source of truth when signed out or unreachable — same
key, same shape, different provenance depending on runtime state.

**Update, 2026-08-02 (camera pipeline session):** checked, not just
assumed — `git diff 6c7b3cc..28569aa` across `App.jsx` and
`family-hub.html` (the whole camera-pipeline range) has zero new
`localStorage.setItem`/`getItem`/`removeItem` calls. The new
`cabinActivityDetail` toggle (full/coarse/off) lives on the existing `CFG`
settings object and rides along in whatever single key that object already
persists under — not a new key. cabin-ui's Google auth token lives in React
state only (lost on refresh, by design — matches "standalone sign-in," not
a persistent session). Key inventory unchanged from last check-in.

---

## 5. CI/CD pipeline is documented and current

- [ ] The pipeline definition (workflow YAML, playbook) is itself in git,
      not just described in prose.
- [ ] A README/runbook exists that lets someone with zero prior context
      recover or rebuild the pipeline from scratch.
- [ ] Docs match the actual YAML — no describing a step that isn't there.

**Status:** No CI/CD pipeline existed anywhere in either clone as of the
session that built it, despite being referenced as already having "a
placeholder." See `.github/workflows/deploy-family-hub.yml` and
`ansible/README.md` — self-hosted-runner pattern (see §6 for why),
documented for recovery.

**Gap surfaced this session (2026-08-01):** the workflow's own comments
explain it deliberately excludes `cabin-backend`/`postgres`/`kafka`/`grafana`
from every automated push — deliberately, because those are stateful and
warrant considered rollout, not a blanket rebuild. That reasoning still
holds, but this session's core work (the cross-device backend) *lives* in
`cabin-backend`, so it does **not** deploy automatically — it needs a manual
`docker compose ... up -d --build cabin-backend` (see README's Quick Start)
every time, same as before this pipeline existed. Not resolved here — an
explicit choice to widen the pipeline's scope (even just to build-and-notify
without auto-restarting) is a call for the user, not something to silently
bake in.

**Update, 2026-08-02 (camera pipeline session):** same gap, same reasoning,
still not resolved — the entire Phase 2 camera event pipeline
(`MqttBridgeService`, `EventConsumer`, `CabinEventService`, `EventController`)
lives in `cabin-backend`, so none of it auto-deployed via the workflow.
It *was* manually rebuilt and deployed on the M920q, and this session
verified it's genuinely live, not just committed: `nate@` checkout is at
`4920440` (2 commits behind local `HEAD`, both doc-only — no code drift),
`cabin-backend` container shows a real restart at `2026-08-02 13:53:12`,
and `curl http://localhost:8090/api/events` from the host (correct port —
first attempt at `8080` 404'd, `docker port cabin-backend` showed the real
mapping is `8090`) returned real, current data: live `DETECTION_NEW` /
`MOTION_ON` events for `outdoor_4` (the Blink camera) with timestamps
within the last few hours, confirming the full Frigate → MQTT → Kafka →
Postgres → API round-trip works against real hardware, not just test
fixtures. Also visible in that same response: a handful of older rows
shaped like `{"eventType":"MOTION_DETECTED","sourceDeviceId":"events",...}`
with the raw Frigate `before`/`after` payload still nested inside — this is
data written by the *pre-fix* `MqttBridgeService` (back when it
misparsed the `events` topic as a camera ID), sitting harmlessly alongside
correctly-parsed rows. Expected, not a bug: the fix is prospective, and
nothing reads `sourceDeviceId` in a way that would break on the old shape.

---

## 6. CI/CD (incl. Ansible) is actually wired to pick up changes

- [x] A push to `main` (or a tag, per the workflow's trigger) results in the
      pipeline running without manual intervention.
- [x] The mechanism is appropriate to the network topology — a target
      behind CGNAT/Tailscale-only needs a different approach (self-hosted
      runner, pull-based deploy) than a publicly reachable host (push-based
      SSH deploy).
- [x] The plan for *why* this mechanism was chosen is written down, not just
      the mechanism itself.

**Status — closed out 2026-08-01.** M920q is Tailscale-only, behind
Starlink CGNAT — a GitHub-hosted runner cannot reach it, and pushing SSH
keys/secrets to GitHub for a home server is more attack surface than
needed for a free/personal-tier project. **Chosen approach: a self-hosted
GitHub Actions runner installed on the M920q itself**, connected over
Tailscale, polling GitHub — no inbound ports, no secrets stored in GitHub.

Registered and running as a systemd service (`nate` ran the one step that
genuinely needs root — `sudo ./svc.sh install && ./svc.sh start` — nothing
scripted around that boundary). **Verified with a real end-to-end test,
not just "the service is active":** the first two actual runs both
failed — caught and fixed two real gaps that only surfaced by actually
running the pipeline rather than trusting it was correct on paper:
- `CABIN_REPO_PATH` was never set, so the workflow fell back to the
  documented default (`/opt/FaceoftheCabin`), which doesn't exist — same
  root-needs-`/opt` issue already hit in the Ansible runner-install path.
  Set via `gh variable set`; also corrected the workflow's fallback
  default to match.
- The deploy step ran plain `docker compose` with no `-f
  docker-compose.m920q.yml` override and assumed `family-hub/` had its
  own compose file — neither true. Would have tried starting a second
  mosquitto/HA/etc. fighting the real ones already running in the
  separately-managed "cabin" stack for ports. Corrected to the exact
  scoped command used for every manual deploy tonight.

Third run (triggered by the fix itself, `73850da`) succeeded end to end —
confirmed against the actual live served page, not just the workflow's
own "success" status.

**2026-08-01, this session's close-out:** pushed `6c7b3cc`, confirmed via
SSH that the self-hosted runner picked it up automatically and rebuilt
`family-hub`/`cabin-ui` within seconds. **Found a real gap doing this:**
`cabin-backend` — where this session's actual core work lives — is
deliberately excluded from the automated workflow (see §5), so despite the
container showing a fresh restart time (a side effect of being `cabin-ui`'s
Compose dependency picking up a changed env var), it was still running the
*old* image — confirmed directly: `GET /api/notes` returned `404`, not
`401`, meaning the new controllers weren't in the running jar at all.
Rebuilt and redeployed `cabin-backend` manually (same command as README's
Quick Start), then re-verified: `401` on `/api/notes` and
`/api/chores/completion` with no token, `200` on an unrelated endpoint,
both confirmed against `api.unicornpingpong.com` directly, not just
`localhost` on the M920q.

**Second real gap found in the same pass:** `hub.unicornpingpong.com`'s
`host-config.js` — the file carrying `GOOGLE_CLIENT_ID`/`ADMIN_EMAILS`/the
new `CABIN_API_URL` — was being cached by Cloudflare for 7 days as
`immutable`, because nginx's blanket `.js` cache rule matched it too (fixed
for every other static asset, wrong for a file regenerated per deploy).
Fixed origin-side (`d769022`, exact-match `no-cache` location block,
verified against `localhost` on the M920q directly). **Not fully closed:**
Cloudflare's existing cached copy (missing `cabinApiUrl`, `cf-cache-status:
HIT`) is still being served publicly as of this check-in — an origin header
change doesn't retroactively invalidate an already-cached edge response.
Real, current impact: any real visitor to `hub.unicornpingpong.com` right
now gets `CABIN_API_URL=''`, silently disabling notes/chores sync for them
specifically (degrades to the existing offline/localStorage-only path, not
a crash) until either the cache naturally expires or is purged. **No
Cloudflare API access from this environment to purge it directly** — needs
a manual purge (Cloudflare dashboard → Caching → Configuration → Purge
`host-config.js`, or purge everything) to take effect immediately instead
of waiting out the remaining ~6.9-day TTL.

---

## 7. All specs are cross-checked for accuracy

- [ ] README's product/subdomain table matches ROADMAP's.
- [ ] ROADMAP's architecture section matches what's actually in
      `docker-compose.yml` / the repo structure.
- [ ] CLAUDE.md's design constraints match current decisions (not stale
      ones from an earlier phase).
- [ ] Ontology's `source:` and `technical:` fields match the actual
      `localStorage` keys / function names in code, not what they used to be.

**Status:** Cross-checked this session — see §3. Subdomain list (`hub` /
`cabin` / `api`) is consistent between README and ROADMAP, and now confirmed
*live* (not just documented) as of this session's fresh `curl` checks — see
§8. `docker-compose.m920q.yml` and `.env.m920q.example` cross-checked against
each other for the new `CABIN_API_URL` (family-hub) and `GOOGLE_CLIENT_ID`
(cabin-backend) wiring — both present and consistent. One open
reconciliation from a previous session remains unresolved: the user recalled
`monitoring`/`qa`/`config`/`familyhub` subdomains that aren't actually
documented anywhere — still needs a human decision, not something to
silently resolve by picking one.

---

## 8. Deployment — pipeline reaches the M920q; quickstart is a reusable template

- [x] Changes reach a running container on the M920q — automated as of
      2026-08-01 (see §6). Manual `ssh` deploys from earlier in the session
      are kept below as history, not because they're still how this works.
- [x] No manual `ssh` + `git pull` needed once set up — **true now.** The
      commit that fixed the workflow (`73850da`) deployed itself, through
      the pipeline, with zero manual intervention — verified against the
      live served page afterward.
- [ ] The same quickstart works as a **template** for a second host machine
      (e.g. the "home" location), parameterized rather than hardcoded to
      the cabin. Still genuinely untested — the Ansible role takes an
      `ansible_user`/inventory-group parameter for this, but no second host
      has actually run it.
- [ ] A placeholder exists for web-hosted (non-self-hosted) deployment
      targets, even if unused today — so the template doesn't have to be
      rewritten if that need shows up later.

**Status — closed out 2026-08-01:** registered the runner (§6), then
actually exercised the pipeline instead of assuming a green checkmark
meant it worked. First two real runs failed — `CABIN_REPO_PATH` was unset
(fell back to a nonexistent `/opt/...` default) and the deploy step didn't
use the M920q compose override, so it would have fought the already-running
"cabin" stack for ports. Both fixed by actually reading the failure logs,
not guessing. Third run, triggered by the fix commit itself, succeeded
fully automated — confirmed against `curl` on the live served page, same
verification bar as every manual deploy earlier tonight.

**Status — updated 2026-07-30, 17:25 CDT:** Tailscale was already
authenticated and connected on this machine (`ilikethelights`), and the
M920q runs **Tailscale SSH** (identity-based, no separate key management) —
reachable as user `nate` via `ssh nate@nates-little-m920q.tailb20f8b.ts.net`.
With the user's explicit go-ahead, used it for a real deploy: pushed
`main` (`e1ec494..462b692`) to `origin/main`, fast-forward pulled on
`/home/nate/FaceoftheCabin` (the clone backing the "infra" compose
project — confirmed via `docker compose ls`, distinct from the "cabin"
project that runs HA/cameras/water valve — this deploy touched neither),
rebuilt only the `family-hub` image, recreated only that container.
**Verified against the actual served response** (`curl` on the container's
real mapped port, not just container status) that the live page now
contains the new features (Holidays & Vacations, Observations, the
versioned schedule). This satisfies "changes are usable at the current
endpoint" for this round of work. Still open: the self-hosted-runner
automation from §6 was never registered, so *this* deploy was manual and
the next one will be too unless that's set up.

<details><summary>Earlier status (2026-07-30, superseded — kept for the record, don't delete history from this doc)</summary>

an agent in this environment has **no network path to the M920q** (no SSH
keys configured, no Tailscale client session, no remote-exec tool).
Nothing described in this doc has actually been *run* against the M920q —
only written, reviewed by logic tests where possible, and committed. This
turned out to be wrong — see the current status above.

</details>

---

## 9. Ontology is fully "baked" — CRUD + data definitions for every entity

- [ ] Every entity a user can Create/Read/Update/Delete through the UI has
      an ontology entry describing that capability, not just its shape.
- [ ] `transformation` fields name the actual functions (e.g. `saveReward()`,
      `deleteHoliday()`) so the doc stays traceable to code, not just prose.
- [ ] New CRUD surfaces (Rewards, Holidays, Family Profiles, Schedule Rules)
      are represented at the same level of detail as older, pre-CRUD
      entities — no second-class documentation for newer features.

**Status:** New `family_note` entity added this session with full CRUD/
transformation/relationship detail (create via `sendNote()`, read via
`refreshNotesFromServer()`/`loadNotes()`, matching the depth of older
entities — no delete surface exists in the UI, so none documented).
`chore_completion_state` updated in place rather than superseded — same
entity, corrected `source`/`transformation` fields to name the real
functions (`toggleChoreCompletion`, `refreshCompletionFromServer`) instead
of the old localStorage-only description. `family_profile` (the entity
`family_note.authorId` references) was reviewed, not changed — still
localStorage-only, a known and documented gap, not silently left
undocumented.

**Update, 2026-08-02 (camera pipeline session):** five more entities added,
same discipline applied. `platform_secret` documents the Vault-backed
CRUD (create via `rotate-secrets.yml`, read via `env.j2` templating on
deploy, no UI surface — it's an ops entity, not user-facing, and says so).
`cabin_camera_event` documents the real pipeline: create via Frigate
publishing to MQTT (`handleFrigateDetectionEvent()`), read via
`GET /api/events` (`CabinEventService.recent()`), no update/delete surface
(events are append-only by design, matches the `ON CONFLICT DO NOTHING`
insert in code) — `transformation` field names both real functions, not
prose. The three Phase 6 entities (`cabin_camera_continuous_recording`,
`cabin_camera_event_clip`, `cabin_camera_live_view`) are deliberately
**not** written as if built — each states plainly in `notes` that this is
planned capability with a ROADMAP.md pointer, rather than describing
hypothetical functions as if they exist. This keeps the "no second-class
documentation" bar without crossing into documenting fiction as fact.

---

## (i) Additional practices — added by Claude Code, not explicitly requested but load-bearing

- **Verification evidence travels with the change.** If a live browser/UI
  check wasn't possible (sandbox restriction, no access to the target
  device), say so explicitly rather than imply full verification happened.
  A passing Node logic-test suite is evidence of logic correctness, not of
  "I saw it render correctly."
- **No silent scope narrowing.** If a requested item can't be done (no
  remote access, ambiguous requirement, conflicting prior decision), it's
  named as blocked/open in this doc — never quietly dropped.
- **Secrets never get committed to make CI "just work."** Runner
  registration tokens, OAuth client secrets, etc. are one-time manual steps
  or environment variables on the target host — not baked into any
  committed YAML.
- **Legacy migrations clean up after themselves** (see §4) — a migration
  that runs but leaves the old data sitting around forever isn't finished,
  it's half-done.
- **This document itself is versioned like code.** Update it in the same
  commit as whatever changed its status; don't let "Last verified" drift
  from reality.

---

## Next Session — Prioritized Plan

> Ordered by leverage, not by request order. "Foundational" items unlock or
> de-risk other work; doing them late means paying for the same gap twice
> (once now, once when a dependent feature needs it). Sequencing notes
> exist because some of these touch the same infrastructure — doing them
> together avoids two separate risky changes to the same system.

### Tier 1 — Foundational (do these first; everything else gets cheaper or safer after)

1. ~~**Register the CI/CD self-hosted runner.**~~ **Done 2026-08-01.**
   Runner registered and running as a systemd service; pipeline verified
   with a real automated deploy (`73850da`), not just a green checkmark.
   Two real bugs found and fixed in the process (`CABIN_REPO_PATH`
   default, missing M920q compose override) — see §6/§8.
2. ~~**Build the cross-device backend** (`/api/notes` + `/api/chores` on
   `cabin-backend`, Postgres-backed).~~ **Done 2026-08-01.** `FamilyNoteService`
   /`ChoreCompletionService` + `NotesController`/`ChoresController` (new
   `family/` package), gated by `GoogleAuthInterceptor` (valid Google access
   token required, checked against Google's tokeninfo endpoint — the first
   auth-gated, first *write*, endpoints this backend has). family-hub.html's
   `sendNote()`/`toggleChoreCompletion()` now sync through it, with
   localStorage kept as an offline mirror when signed out or cabin-backend is
   unreachable. Verified against a real local Postgres + a real running
   backend, two isolated browser contexts standing in for two devices — both
   directions, both features, zero errors. Raw completion data syncs; the
   `chore_daily_success`/`chore_weekly_success` *derived* threshold flags
   (ontology) are still computed live client-side from that synced data, not
   separately materialized server-side — that narrower piece is still
   planned, not required for the cross-device gap this closes. Also NOT yet
   synced: `family_profile` itself (still localStorage/per-device) — see
   `family_note`'s ontology notes for the resulting (graceful, non-fatal)
   edge case with a freshly-added Friends & Family profile.
3. ~~**Resolve the `cabin-postgres` default-password finding.**~~ **Done
   2026-08-02.** `ALTER USER cabin WITH PASSWORD '...'` against the live
   container, `.env` updated to match, `cabin-backend` + `cabin-grafana`
   recreated to pick it up. Verified against the connection path that
   actually matters — `cabin-postgres`'s `pg_hba.conf` `trust`-authenticates
   anything from loopback (127.0.0.1/::1), which is why a naive `docker exec
   ... -h localhost` test would have "passed" with the *old* password too;
   the real dependent services connect over the Docker network alias and
   correctly require `scram-sha-256`, confirmed via both `cabin-backend`'s
   health endpoint (`db: UP`) and Grafana's datasource health check
   (`Database Connection OK`) — not just "the command didn't error."
   Bonus fix in the same pass: `cabin-grafana` never actually received
   `POSTGRES_PASSWORD` as an env var despite its provisioning YAML
   referencing it, so the `CabinDB` datasource had likely never
   authenticated correctly at all — fixed alongside the rotation.
   Unplanned side effect worth knowing about: `docker compose up -d
   cabin-backend cabin-grafana` also recreated `cabin-postgres` itself (a
   `depends_on` config-drift side effect, same class of thing as
   `GOOGLE_CLIENT_ID` reaching `cabin-backend` via `cabin-ui`'s dependency
   chain in an earlier session) — confirmed the data volume was untouched
   (all 8 tables present, including this session's `family_notes`/
   `chore_completion`) before treating this as resolved. **Still entirely
   manual** — no Ansible role or automation generates/rotates this password;
   the only Ansible role in this repo (`ansible/roles/cabin_host`) installs
   the GitHub Actions runner, nothing secrets-related. If "Ansible-managed
   secrets" is wanted going forward, that's new work (e.g. Ansible Vault +
   a role that generates and templates the password into `.env`), not
   something already wired up that this rotation used.
4. ~~**Resolve the stale `H:\...\FaceoftheCabin` clone**~~ **Done
   2026-08-02.** User confirmed no longer using Google Drive for git at all;
   deleted (content fully removed — CLAUDE.md, untracked `.vscode/`/
   `vite.config.js`, `.git` — an empty folder shell remains at that path,
   locked by Drive's sync process with nothing inside it, harmless).

### Tier 2 — Near-term supporting work (moderate value, no blocking dependencies)

5. **Real-device verification pass** — an actual Android phone/tablet for
   the layout fixes, and specifically a touch-only device (no physical
   keyboard) for the notepad's `visualViewport` keyboard-handling, which
   Playwright cannot simulate. This is verification, not construction — low
   effort, closes out confidence gaps flagged throughout tonight's session.
6. **Confirm `cabin.`/`api.` Cloudflare subdomain status.** `hub.` is
   confirmed live; whether the other two ROADMAP-planned subdomains are
   already configured through the same tunnel is unverified, not assumed
   done or not-done.
7. **CLAUDE.md / README.md drift audit** against everything shipped this
   session (schedule versioning, holidays, themes, notepad, host-driven
   config) — housekeeping, keeps future sessions from re-deriving context
   that's already been established.
8. **Mark completed ROADMAP checklist items done** — the Google OAuth
   authorized-origin update happened live during tonight's debugging and
   should be checked off, not left showing as pending.

### Tier 3 — Stretch / explicitly deferred (real, but not next)

1. **Personalization / dynamic UI ranking** (auth-identified per-user
   interaction history driving which card renders first). Hard-blocked on
   Tier 1 #2 — per-user history can't live in per-device storage. Also
   explicitly tabled by the user pending a design discussion ("panel of
   professionals") on what counts as a usage signal and how ranking should
   decay over time — that conversation should happen before any code, not
   after.
2. **Mobile layout philosophy formal review.** Already aligned in practice
   (content-driven single-column flow, confirmed correct by the user
   tonight) — revisiting is about confirming the decision formally, not
   because there's an open disagreement driving urgent work.
3. **Web-hosted (non-self-hosted) deployment target.** A placeholder role
   already exists in the Ansible playbook for this. No current need drives
   it — everything runs self-hosted today. Build only if an actual
   cloud-hosting requirement shows up.
4. **Camera video viewing** (live view, event clips with pre/post buffer,
   continuous DTM recording review) — explicitly deferred by the user to a
   session with more budget. Fully planned first, per their explicit
   request: see `ROADMAP.md` Phase 6 and `docs/ontology.yaml`'s
   `cabin_camera_live_view`/`cabin_camera_event_clip`/
   `cabin_camera_continuous_recording` entities, all grounded in Frigate's
   actual live config (pulled via its real `/api/config`, not assumed) —
   continuous recording is currently disabled (0-day retention), event
   clips currently use 5s/5s pre/post (not the requested 15s/60s), and
   `/storage` is confirmed as a separate 931.5GB disk (likely the WD
   drive), 826GB free as of 2026-08-02. Not started — planning only.
