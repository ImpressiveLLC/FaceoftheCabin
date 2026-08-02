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
`H:\My Drive\cabin-orchestration-platform-expanded\FaceoftheCabin` is
**still stale** (commit `e4e3251`, unchanged since last check-in — far
behind `main`) and still has an uncommitted `CLAUDE.md` edit plus untracked
files (`.vscode/`, `ui/vite.config.js`). Nothing about this changed this
session; re-verified, not re-decided. **Open decision for the user,
unchanged:** delete the `H:\...\FaceoftheCabin` clone, or reset it to match
`origin/main` and stop editing files there. Not touched by an agent without
confirmation — destructive action outside the scope of a routine check-in.

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
