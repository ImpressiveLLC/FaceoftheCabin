# Definition of Done — FaceoftheCabin / Family Hub Sessions

> Canonical, but scoped to **sessions, not the app.** This is not a claim
> that the platform is finished — it's the bar every individual work
> session must clear before being considered closed, the same way a
> sprint has an exit checklist independent of whether the whole product
> is done. Run this checklist at the end of any substantial session, not
> just when asked.
>
> **What belongs here**: pass/fail checks, one line each. **What does
> not belong here**: postmortems, decision rationale, or narrative
> "what happened and why." That content belongs in
> [`MAINTENANCE.md`](MAINTENANCE.md)'s Known Issues log (operational
> lessons), [`PRODUCT_NOTES.md`](PRODUCT_NOTES.md) (design decisions), or
> git commit messages (which already carry the *why* for each change) —
> not duplicated here. This file was allowed to grow into a 50KB
> narrative journal once; that was a mistake, corrected 2026-08-03.

---

## 1. Git is reconciled — one source of truth, no drift

- [ ] Every local clone that gets worked in is either current with
      `origin/main` or its drift is understood and intentional.
- [ ] No clone has uncommitted changes that only exist on one machine.
- [ ] Stale/duplicate clones are identified and either brought current or
      explicitly retired.

## 2. Everything is checked into git — available and current everywhere

- [ ] All working-tree changes relevant to the session are committed.
- [ ] Commit messages describe *why*, not just *what*.
- [ ] Nothing environment-specific or secret is committed.
- [ ] Push happens only with explicit user go-ahead.

## 3. Docs & ontology accurately reflect current state

- [ ] `README.md`, `ROADMAP.md`, `CLAUDE.md` don't describe removed/
      renamed things, and do describe what actually exists now.
- [ ] `docs/ontology.yaml` has an entry for every user-facing configurable
      concept, matching the real storage keys/functions in code.
- [ ] Docs written *about* a change ship in the *same* commit as the
      change.

## 4. Local storage is optimized — no accumulating cruft

- [ ] Every `localStorage` key in use is either actively read, or has an
      explicit, time-bounded migration path that removes the old key.
- [ ] If the simplest correct answer is "wipe and reseed," do that
      instead of writing compatibility shims.

## 5. CI/CD pipeline is documented and current

- [ ] The pipeline definition (workflow YAML, playbook) is itself in git.
- [ ] A runbook exists that lets someone with zero prior context recover
      or rebuild the pipeline from scratch (`MAINTENANCE.md`).
- [ ] Docs match the actual YAML.

## 6. CI/CD (incl. Ansible) is actually wired to pick up changes

- [ ] A push to `main` results in the pipeline running without manual
      intervention, for the paths it's scoped to.
- [ ] The mechanism is appropriate to the network topology.
- [ ] The reasoning for the mechanism is written down (`MAINTENANCE.md`).

## 7. All specs are cross-checked for accuracy

- [ ] README's product/subdomain table matches ROADMAP's.
- [ ] ROADMAP's architecture section matches what's actually deployed.
- [ ] CLAUDE.md's design constraints match current decisions.
- [ ] Ontology's `source:`/`technical:` fields match real code.

## 8. Deployment reaches the target host; quickstart works as a template

- [ ] Changes reach a running container without manual `ssh` + `git pull`
      for the automated paths.
- [ ] The same quickstart works as a template for a second host (still
      genuinely untested as of this writing — no second host has run it).
- [ ] A placeholder exists for a non-self-hosted deployment target, even
      if unused today.

## 9. Ontology is fully "baked" — CRUD + data definitions for every entity

- [ ] Every entity a user can Create/Read/Update/Delete has an ontology
      entry describing that capability, not just its shape.
- [ ] `transformation` fields name the actual functions.
- [ ] New CRUD surfaces get the same depth as older entities — no
      second-class documentation for newer features.

## 10. New testable logic ships with a real, CI-gated test — no loose code

> Added 2026-08-07 per explicit user directive: "no loose code without
> test-driven proof of quality." This is stricter than section 6 (CI/CD is
> *wired*) — this is about whether new logic has a test *in* the suite CI
> already runs, not just whether the pipeline itself works.

- [ ] New backend logic (Spring service/controller methods with real
      branching/SQL) gets a test under `backend/src/test/` — Testcontainers
      against real Postgres/Kafka for anything JdbcTemplate/SQL-dependent
      (`EventPipelineIntegrationTest`, `HubLocationServiceTest` are the
      reference pattern), plain JUnit for pure logic
      (`AlertSeverityClassifierTest`).
- [ ] New frontend logic gets a test in whichever suite already covers
      that surface — `cabin-orchestration-platform/ui`'s Vitest suite
      (`src/*.test.jsx`, added 2026-08-07) for cabin-ui, `family-hub/test/
      run.js`'s Playwright checks for family-hub.html. Prefer extracting
      the actual logic into a small, pure, exported function
      (`isCameraEvent`, `mergeHubLocations`, `resolveInitialThemeId`) over
      testing it only indirectly through a full component/page render —
      cheaper to write, cheaper to keep passing.
- [ ] The test actually runs as part of the existing CI gate before
      anything deploys — `mvn test` (`deploy-cabin-backend.yml`), the
      Family Hub Playwright suite AND cabin-ui's Vitest suite (both in
      `deploy-family-hub.yml`, since that workflow rebuilds both). A test
      file that exists but isn't wired into a workflow doesn't satisfy
      this item.
- [ ] If a test genuinely can't be run in the current working environment
      (e.g. no Docker available for a Testcontainers test in an agent
      sandbox), that's said explicitly, not silently assumed passing —
      compiling clean or "looks right" is not the same claim as "the test
      ran and passed." State which environment it WAS verified in (a real
      CI run, a different machine) if any.

---

## (i) Additional practices — load-bearing, not always explicitly requested

- **Verification evidence travels with the change.** If a live
  browser/UI check wasn't possible, say so explicitly rather than imply
  full verification happened. A passing test suite is evidence of logic
  correctness, not of "I saw it render correctly." `curl` cannot
  validate real-browser behavior (CORS preflight is the concrete example
  that burned this project once — see `MAINTENANCE.md`).
- **No silent scope narrowing.** If a requested item can't be done, it's
  named as blocked/open — never quietly dropped.
- **Secrets never get committed to make CI "just work."**
- **Never diff a secret by its raw value** — compare by presence/hash,
  never let the value itself land in a transcript or log.
- **Legacy migrations clean up after themselves** — a migration that
  leaves old data sitting around forever isn't finished.
- **This document itself is versioned like code** — update it in the
  same commit as whatever changed its status.
- **Reconcile drift the moment it's spotted, not at session end.** If a
  config value, name, or piece of state looks unexplained or
  inconsistent with what's documented mid-session, stop and reconcile it
  right then — check git history, check docs, ask the user — rather than
  noting it and moving on. Found 2026-08-07: a stray discovery (comparing
  an ntfy topic hardcoded in a Node-RED flow against the one actually in
  use) surfaced that the flow had been silently pushing armed-away
  intrusion alerts to an unsubscribed topic for over a week, undetected.
  The user's own diagnosis, worth keeping verbatim: reconciling *close to
  where the actual git/context break happened chronologically* is far
  cheaper and more reliable than reconstructing it later. Same underlying
  gap as the "Decisions made where Claude can't commit" section of
  `CLAUDE.md` — that one closes the loop on undocumented decisions, this
  one closes it on undetected drift.

---

## Next Session — Open Items

*Kept short and current on purpose — this is a live punch list, not an
archive. Resolved items get removed, not marked "done" and left to
accumulate.*

- **Reolink (`front_door`) camera still physically off-network** — needs
  on-site checking (power, WiFi re-pairing). Not fixable remotely.
- **`blinkbridge`'s root cause is fixed (2026-08-06) — verify the fix
  holds over the next real transient failure.** The actual bug
  (permanently disabling a camera after 3 failures with no retry path,
  `main.py`) is patched and deployed — see `MAINTENANCE.md`'s Blink
  section for the full diagnosis and fix. Not yet proven against a real
  failure in production, only against a clean redeploy. The Uptime Kuma
  monitor recommended here is now built (`Frigate driveway camera_fps`,
  JSON-query against `http://frigate:5000/api/stats`, alerts via the
  same ntfy topic `cabin_critical_event_alert` reuses) — verified live,
  correctly evaluating `camera_fps > 0`. Separately open: whether to
  decouple reliable clip-recording from the RTSP-live-relay entirely
  (the user's recalled prior direction, not yet scoped or built).
- **Uptime Kuma had zero notification channels configured at all before
  2026-08-06** — every monitor in it (Homepage, Home Assistant, Frigate,
  Node-RED, Tailscale) could go red with nobody ever told. Added one
  ntfy channel (`ntfy - cabin alerts`, set as the account default) when
  building the driveway monitor above; not yet attached to the
  pre-existing monitors — worth doing so they stop being silent too.
- **Severity classifier (`docs/ontology.yaml`'s `event_severity`) doesn't
  consider armed/presence state yet** — a WARN-tier event (door open,
  low battery, tamper) scores the same whether the cabin is occupied or
  armed-away. Deliberate MVP scope cut (see that entity's `notes`), not
  forgotten — wire it to `cabin/security/armed_away` and
  `cabin/presence/*` once the MVP badge/push behavior is proven stable.
- **Grafana has NO working login at all right now** — password login
  deliberately disabled (open internet exposure, neither access gate
  confirmed working); Cloudflare Access isn't gating the hostname
  despite being configured, and Grafana's own Google OAuth 403s for a
  reason not yet found. See `MAINTENANCE.md`'s Grafana section,
  "Current real state," for the full diagnosis and next-session order
  of operations. Highest-priority item for next session — this is a
  real outage, not a nice-to-have.
- **Liebherr fridge / Bosch dishwasher account linking** — both need the
  user's own account credentials (SmartDevice login; a Home Connect
  Developer OAuth client_id/secret + account consent). See
  `docs/ontology.yaml`'s `smart_appliance_*` entities for exact steps.
- **Monitoring runbook** — Uptime Kuma + Homepage are running, but what
  they actually check and alert on isn't documented yet
  (`MAINTENANCE.md`).
- **Real second-host replication test** — `REPLICATION.md` has never
  actually been run end-to-end against a fresh host.

---

**Last full session close-out:** 2026-08-04 (ended with a known,
documented outage — see punch list above; not a clean close). See git log for the actual
session-by-session record — that's the authoritative history now, not
this file.
