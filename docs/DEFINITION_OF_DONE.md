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

- **2026-09-18 — PR #83 scheduling handoff:** [Retro theme refresh and optional HA console proposal](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/83) is authorized by Nate for merge. The implemented work is **aesthetic/presentation only**: Pac-Man Crackman display fonts, neon Baumans with bundled Oxanium fallback, and pink/mint/blue/yellow palette in both apps. No backend, orchestration, safety logic or device-control behavior is added. **HA-UX0–5 remain proposed, unscored, unratified and unscheduled**; do not start or mark the HA Operations Console complete from this merge. Preserve existing scheduling priorities; use [the WSJF references](ai-assistant/wsjf-backlog.md#optional-ha-operations-console-candidate--2026-09-18) for later explicit prioritization. Deployment/live verification is separate from merge; see [validation limits](retro-theme-validation.md).

*Kept short and current on purpose — this is a live punch list, not an
archive. Resolved items get removed, not marked "done" and left to
accumulate.*

- **Rules & Alerts box resizing — explicitly deferred, 2026-09-17.** User
  asked for box reorder (shipped, PR #76) "and if easily doable,"
  resizing so boxes reflow the CSS layout dynamically. Reorder reuses
  `FamilyHubPanel`'s existing `useDraggableOrder` pattern directly — real
  resize-and-snap (drag handles, span calculation, snap-to-neighbor) is a
  genuinely separate feature, not a small addition on top of that. Not
  started; flagged rather than silently dropped.
- **Kafka Topics box removed from Rules & Alerts, PR #76 — flagged for
  confirmation, not a closed decision.** It never fetched anything real
  (hardcoded topic list, hardcoded "localhost:9092" not matching the
  actual configured broker `cabin-kafka:9092`). Recommended against
  building live start/stop/reconfigure controls for it (message-bus
  lifecycle control in a resident/admin panel is a much larger blast
  radius than anything else this panel touches) and removed it from the
  render list; component kept, unreferenced, one-line revert if that
  call is wrong.
- **Two orphaned localStorage keys after the Status Checks merge (PR
  #76) — accepted, not migrated.** `collapsed.activeConditions` and
  `collapsed.automationAlerts` are replaced by one shared
  `collapsed.statusChecks` key; the old two are simply never read again.
  No user-visible harm (a single fixed key name, not growing per-item),
  and per this doc's own "wipe and reseed over a shim" guidance, not
  worth a migration for a boolean collapse preference — noted here
  rather than silently skipped.
- **Monitoring panel can't group tiles, only reorder one flat list — real
  gap, found 2026-08-25, not started.** User wants their temp/humidity
  tiles visually grouped together, out of the general device-status
  landing view (currently interleaved with unrelated device types, no
  way to see just one cluster without scrolling the full list). Root
  cause confirmed in code: `useDraggableOrder` (`App.jsx:4302`) has
  exactly two buckets — alarm-pinned (`isAlarm`) and everything else —
  no concept of a named section/group anywhere. Would need a real data
  model (group assignments + per-group order, not just one flat
  `savedOrder` array in localStorage) and UI for creating/naming groups,
  plus probably a collapse/show-fewer affordance for the landing view
  itself. Not scoped further this session — real feature, not a config
  tweak.

- **Live camera investigation, 2026-08-24 — three real findings, one
  self-correction.** Triggered by the user reporting missing clips at
  both locations.
  1. **`front_door` (cabin, Reolink) is still physically off-network** —
     `No route to host` on `192.168.2.200:554`, continuous watchdog
     crash-loop. Same known issue as above, reconfirmed live, still not
     fixable remotely.
  2. **`home_aldrich_front`'s clip gap is NOT an arm-state issue —
     correcting a wrong live-session claim.** Mid-session I told the user
     arming the driveway Blink camera would likely help, based only on
     seeing a diagnostic log line (`blink.py:155`) that logs `sync.arm`
     alongside `motion_detected`. I hadn't checked what a real comparison
     showed. One already exists: commit `96ccdb6` (2026-08-16) directly
     compared AldrichFront against the working driveway camera via
     blinkpy's own attributes and found them **identical** on
     subscription, sync-module arm state, motion_enabled, and sync
     availability — yet only driveway ever produces a clip. The
     differentiator lives entirely in Blink's own backend, invisible to
     any client API. Arming/disarming either camera is not expected to
     change anything. Don't repeat the arm-state guess in a future
     session without re-reading this.
  3. **The real designed fix for AldrichFront already exists and, as of
     2026-09-05, is live end-to-end.**
     `POST /api/webhooks/blink-motion` (added same commit,
     `BlinkMotionWebhookController.java`) triggers blinkbridge's
     proven-working manual liveview start off the Blink app's own push
     notification (which reliably fires even though the clip API
     doesn't). The original phone-side trigger for this was MacroDroid —
     validated working, but a third-party automation app the user has
     since uninstalled. It's been replaced by
     `cabin_security_publish_blink_motion`, a native HA automation on the
     HA Companion App's own Last Notification sensor, publishing over MQTT
     to `MqttBridgeService` instead of calling the webhook directly (see
     that automation's own file,
     `infra/cabin-security/homeassistant/cabin_security.yaml`). The
     webhook itself is kept as a manual/fallback trigger only.
     `BLINK_MOTION_WEBHOOK_API_KEY` is confirmed set on the live
     `cabin-backend` container (checked presence/length only, never the
     value, per this project's own credential-diffing rule).
  4. **Camera Events' "if possible" framing on motion-only clips
     (`ed1c60d`, 2026-08-18) is honestly hedged for intermittent-feed
     cameras (AldrichFront) but overstates uncertainty for
     continuous-feed ones (driveway, front_door-when-up)** — Frigate's
     `record.enabled=true` is unconditional, so a camera with a
     continuously-fed stream should almost always have a real clip; the
     UI currently gives every camera the same "tap to try, might not be
     there" treatment regardless. Small, well-scoped UX fix, not done
     this session — flagged, not started.
  5. **Presence detection re-confirmed working correctly, live** —
     `/api/presence` correctly resolved `AT_HOME` within ~20 minutes of
     the user's actual arrival home from the cabin, all signals
     (`device_tracker.nates_s23`, `person.natecabin`,
     `motion_entry_occupancy`) flipped `present:false` at cabin in sync.
     Not re-verified for the return-to-away transition (dog walk) before
     session's live-investigation thread ended — worth a quick spot
     check next live session if it comes up again.
- **Local-network-scan concern raised by the user re: a Family Hub
  "grandpa" actor login, 2026-08-24 — hypothesis only, not confirmed.**
  User reported the app seemed to ask to "check for devices local to
  [that] machine" after a different-browser/location login, and worried
  this could recur on every new browser/network (a public library, a
  tech demo) and pollute Device Manager with irrelevant candidates.
  Checked the code: there is no login-triggered or actor-triggered
  device-candidate scan anywhere in the frontend — `DeviceDiscoveryOverlay`
  only runs `discovery/run` when a person explicitly clicks "Re-check
  device info" or the equivalent Add-flow button, and all continuous
  device scanning (Zigbee2MQTT, HA polling) happens server-side against
  the M920q's own LAN, not whatever network the browser is on, so it
  can't be what was described. The one remaining candidate: `RulesPanel`
  still embeds Node-RED directly via `<iframe src="http://cabin-hub:1880">`
  (`App.jsx:3405`, a real private-LAN URL — Grafana's equivalent iframe
  was already removed 2026-08-08 for related reasons). Modern Chrome's
  "Local Network Access" permission prompt ("Allow this site to find
  devices on your local network?") fires per-origin whenever a page tries
  to fetch a private-IP URL, which would reproduce exactly the reported
  behavior and would indeed re-trigger on every new browser/origin,
  independent of who's logged in. **Not confirmed** — need the user to
  say whether what they saw was literally a browser permission dialog
  (supports this theory) or something inside the app's own UI (would mean
  this theory is wrong and needs more digging). If confirmed, the fix is
  likely lazy-loading the iframe only when RulesPanel is actually opened,
  or proxying Node-RED same-origin instead of embedding its LAN URL
  directly — not started.
- **Local/offline AI for the guide-assistant and monthly Opportunities
  web-lookup use cases — user raised as "consider," not requested to
  build.** Two distinct ideas: (a) a fully offline, containerized
  self-help/guide LLM per clone (cabin M920q, ilikethelights, a
  "Bluefin machine" not otherwise documented in this repo), retrained on
  the current ontology/docs/specs so users don't have to keep asking
  Claude for basic guidance; (b) a low-volume (sub-dozen instances,
  monthly cadence) web-search-capable LLM for the existing Opportunities
  feature to research current best-practice patterns (SSO, tunneling,
  etc.) without needing Claude for it. Perplexica (or a similar
  self-hosted RAG tool) was suggested as a starting point. Purely
  exploratory — no design or implementation started. Worth its own
  focused discussion given it's genuinely two different problems (fully
  offline retrieval-only vs. online search-augmented) with different
  infra needs, not one feature.
- **Codex-fork handoff (2026-08-08 → 2026-08-14) — reconciliation audit
  complete; surviving features still open.** Canonical `main` at
  `bba699e` was checked item-by-item and compared with the full fork-only
  commit range (15 commits). The evidence/disposition table now lives in
  [`docs/HANDOFF_2026-08-08_codex-fork.md`](HANDOFF_2026-08-08_codex-fork.md)
  §5. Current result: Item 1 is partial (HA check works; Zigbee/RTSP
  implementations are fork-only); Item 2 has a substantial fork-only
  shared-auth implementation (`20359f7`) but remains open on canonical;
  Item 3 is code-complete with live mobile/kiosk UX verification open;
  Item 4 is complete for its scoped location rendering; Item 5's central-
  brain architecture is decided but collector implementation/hardware is
  open; Item 6's severity bug is fixed by PR #2 while armed/presence,
  confidence controls, and truthful rule status remain open. Do not merge
  the old fork wholesale; surviving work needs scoped current-main PRs.
  **`BLINK_PASSWORD` rotation status is still unconfirmed** — no evidence
  either way was found, so that live action remains carried forward.
- **Production stack under version control — validated 2026-09-13,
  status corrected.** PR #20 (`REPLICATION.md` §10) and PR #21
  (production-stack deploy/smoke/rollback gate) — both listed here as
  "draft... neither merged or live" — were **merged 2026-08-15**
  (confirmed via `gh pr view`), a month before this file was last
  corrected on this point. Docker healthchecks (PR #18) and the deploy
  gate are live. **Still genuinely open**: Kuma's own notification
  wiring — confirmed live via direct DB query (`monitor_notification`
  table) that only 1 of 6 monitors (`Frigate driveway camera_fps`) has
  a channel attached; Homepage/HA/Frigate-cams/Node-RED/Tailscale can
  still go red with nobody told. Kuma config-as-code is still only a
  proven POC (PR #20's own body: "a repo-owned declarative spec," not
  built), still blocked on a real Kuma admin credential in the vault.
  `mediamtx`'s stale internal compose-project label is unchanged,
  still cosmetic-only.
- **Grafana embed resolved 2026-08-08 (real root cause, not the
  suspected one) — then replaced entirely by user decision.** The
  actual blocker was `hub_locations` seeded with unreachable
  Docker-internal URLs (fixed live + at the source); the SameSite
  cookie theory was a real but wrong dead end. Once fixed, the iframe
  did technically work — but the user decided a session-dependent
  iframe a "just want it to work" user has to learn to scroll inside
  isn't acceptable for a mixed-technical-skill multi-user product.
  Replaced with a native `CameraHealthPanel` (Prometheus-sourced,
  Tailscale/internal-only, no new exposure) + a plain Grafana link-out.
  Follow-up open: metric selection/reordering + real kiosk-vs-mobile
  layouts (today it's one metric, one flex-wrap layout, not yet
  visually verified on a real device). See `ROADMAP.md`'s matching
  entries and `docs/ontology.yaml`'s `camera_health_panel`.
- **App-wide Google OAuth gate + consistent landing page — new user
  directive, 2026-08-08, not built yet.** Auth today only gates Camera
  Events/Opportunities, not the app as a whole; the landing panel isn't
  consistent (user's report — checked the code, `activePanel` isn't
  actually persisted anywhere, so this is most likely browser tab/
  session restoration, not an app bug, but the UX problem is real
  either way). Full scope in `ROADMAP.md`'s matching entry — needs real
  design work (auth-before-render gate, Family Hub session reuse or a
  hard login wall, landing page = My Places per the user's stated
  assumption) before implementation.
- **Zigbee LQI signal-quality prototype needs evaluation** (built
  2026-08-08, `GET /api/signal-quality`) — confirmed still live and
  collecting 2026-09-13 (real per-device baselines, 18-20 samples each,
  all `anomalous:false` at check time) but still not wired to any alert
  path. There's now enough real sample history to actually do the
  correlation-with-reality check this item asks for — that evaluation
  itself still hasn't been done. `ANOMALY_DROP_RATIO` (30%) is still an
  untuned placeholder.
- **Alert/ontology UX retrenchment — current-state slice implemented in
  draft PR (2026-08-14).** The old browser-local opt-in/timer and aggregate
  OFFLINE inference are replaced by `GET /api/alerts/active`: only enabled,
  `ASSIGNED` devices can alert; current ALARM is CRITICAL and authoritative
  MISSED check-in is WARN; LATE, candidate, AVAILABLE, disabled, DEFERRED,
  and IGNORED never create badges. `GET /api/alerts/rules` also replaces the
  hardcoded sidebar with real backend rule/threshold ownership. **Still
  open:** audited edit controls (no fake toggle was added), HA/Node-RED flow
  inventory and run-state adapters, armed/presence wiring into the generic
  event severity classifier, native device-confidence evidence, and live
  mobile/kiosk UX validation after review. See `docs/ontology.yaml`'s
  `active_alert_condition` and `automation_rule_status` entities.
- **Reolink (`front_door`) camera still physically off-network** — needs
  on-site checking (power, WiFi re-pairing). Not fixable remotely.
- **`blinkbridge`'s no-clip crash fix — holding, checked 2026-09-13.**
  Container uptime confirmed live: started 2026-09-07, `RestartCount: 0`
  since — no crash-loop recurrence in 6 days of real operation. Stronger
  evidence than "only the one occurrence that prompted the fix," though
  still not an infinite guarantee against a future transient Blink API
  failure recurring differently.
- **Uptime Kuma had zero notification channels configured at all before
  2026-08-06** — every monitor in it (Homepage, Home Assistant, Frigate,
  Node-RED, Tailscale) could go red with nobody ever told. Added one
  ntfy channel (`ntfy - cabin alerts`, set as the account default) when
  building the driveway monitor above; not yet attached to the
  pre-existing monitors — worth doing so they stop being silent too.
- **Severity classifier (`docs/ontology.yaml`'s `event_severity`) doesn't
  consider armed/presence state yet** — confirmed still true 2026-09-13,
  `AlertSeverityClassifier.java`'s own doc comment is unchanged ("no
  armed/presence awareness yet"). A WARN-tier event (door open, low
  battery, tamper) scores the same whether the cabin is occupied or
  armed-away. Deliberate MVP scope cut, not forgotten — both signals are
  real and live, so this is purely a wiring task. **Nuance found while
  checking:** presence-awareness isn't entirely absent from the platform
  — `GET /api/alerts/rules`'s `WATER_PRESSURE_LOW`/`_HIGH` rules already
  do exactly this ("severity depends on cabin presence"), just via
  `WorkflowRuleService`/`AutomationRuleService`, a separate code path
  from this generic classifier. Worth deciding whether that pattern
  should extend here or stay rule-specific.
- **Liebherr fridge / Bosch dishwasher account linking** — both need the
  user's own account credentials (SmartDevice login; a Home Connect
  Developer OAuth client_id/secret + account consent). See
  `docs/ontology.yaml`'s `smart_appliance_*` entities for exact steps.
- **Real second-host replication test** — `REPLICATION.md` has never
  actually been run end-to-end against a fresh host.
- **WiFi RSSI presence detection (original idea) vs. Zigbee LQI
  prototype (built instead)** — spare C4000LG router available as an
  additional collection point if a WiFi approach is pursued later. See
  `grafana/dashboards/22019-wifi-scan/README.md`.
- **Device → one-to-many-services ontology hierarchy — scoped but not
  started, deferred by explicit user choice 2026-08-21 ("finish current
  work first").** User showed live HA screenshots of a Kidde CO/air-
  quality unit surfacing as ~9 disconnected HA entities with no visible
  tie back to "the Kidde unit," and asked that devices with multiple
  services be modeled so a user can pick either the parent device or a
  specific service, with the ontology/UI always showing the service's
  lineage back to its parent. **Elaborated with concrete examples in a
  later message the same session, worth preserving verbatim in intent:**
  (1) Kidde — many entities registered today read as separate "devices"
  when they're really one physical unit's several services; (2) the
  Zigbee water-leak sensors are the clearest case needing both a
  documented ontology shape AND real workflow/UI filtering — each
  physical sensor has *two* independent services: a detector that
  pushes a notification when water is present, and a built-in siren
  that the system can command separately (e.g. "turn on siren" as an
  *intrusion* workflow action, unrelated to leak detection) — both
  services belong to the same parent device and must be independently
  selectable as a trigger (leak) or an action target (siren) while
  staying discoverable as "services of this device," not just loose
  catalog entries; (3) a third, structurally different case already has
  its own planning doc (`grafana/dashboards/22019-wifi-scan/README.md`):
  a smart switch is the primary device, "WiFi repeater" is a secondary
  built-in function of that same hardware, and "presence/zone detection
  via signal-strength triangulation against nearby mesh WiFi devices" is
  a *further derived* function computed from the repeater's own data —
  i.e. this isn't always a flat device→service list, sometimes it's
  device→function→derived-function. Real design work is needed across
  `docs/ontology.yaml` (a parent/child or `belongs_to_device` relation
  ontology entities don't have today), `DeviceRegistry` (today's
  `haDeviceId` grouping is purely cosmetic, used only by Device
  Manager's client-side visual card — not exposed to the workflow
  engine or ontology at all), and the workflow creation form's
  trigger/action/device pickers (need a "device, or one of its
  services" affordance instead of one flat device list). Likely needs
  its own Plan Mode session given the scope (ontology schema, backend
  grouping semantics, and two frontend pickers all touched together).
- **`vault_ha_token` drift risk — likely closed, not fully confirmed
  (checked 2026-09-13).** This item's own premise (vault last-modified
  2026-08-19, predating the 08-21 live fix) no longer holds:
  `vault.yml`'s real mtime is now **2026-09-05**, matching that day's
  separate `HA_TOKEN` restoration incident (`MAINTENANCE.md`), whose own
  write-up states the fresh token was written into the vault "same
  sitting" as the live `.env`. Never diffed by raw value (this
  project's own rule), so not byte-confirmed, but the timeline plus
  that incident's own explicit claim make the old blank-value risk this
  item warned about unlikely to still be real. Worth a from-scratch
  `rotate-secrets.yml` dry run to fully close, not urgent.
- **`home_presence.yaml` found live on the M920q, not in git, root-owned
  — reconciliation status unknown.** Discovered while deploying the
  Kidde bridge (same `packages/` directory as the tracked
  `cabin_security.yaml`/`cabin_security_presence.yaml`), modified the
  same day, owned by `root` rather than `nate`. Deliberately not read,
  touched, or committed this session — flagged to the peer session via
  `SendMessage` instead. Their response, if any, wasn't seen before this
  session's summary point; check for it before assuming this is
  resolved.
- **Local `mvn spring-boot:run` hang, Part D (2026-08-21), still
  undiagnosed — re-test 2026-09-13 inconclusive, not a resolution.**
  Ran `mvn spring-boot:run` again on this dev machine: it failed fast
  (~18s) this time, but on `Connection refused` to Postgres, because no
  local Docker stack was up during this check — not the same
  precondition as the original report (against an *already-running*
  local stack with Postgres/Kafka reachable, hanging 30+ min without
  binding port 8080). Genuinely different failure mode, so this doesn't
  confirm the original hang is fixed *or* still present — re-test with
  the local stack actually up before closing this.
- **Two parallel WSJF backlogs exist with no cross-reference — a real
  liability, not yet a conflict (found 2026-09-13).** The Living Ontology
  artifact's own WSJF Priority Order/Discrepancy Log (D-decisions —
  Cabin/Family Hub platform features, reviewed by Cowork) and
  `docs/ai-assistant/wsjf-backlog.md` (C1–C4 — the Ollama/`llama3.2:3b`
  corpus/RAG/assistant track, reviewed by Codex) are scored
  independently, by different reviewers, and neither currently
  references the other. Not urgent — the two tracks haven't produced a
  conflicting instruction yet, and a first cross-link now exists (the
  artifact's new reconciliation pin, `wsjf-backlog.md`'s DEP09) — but
  whoever holds product priority (Nate/Cowork) should read both before
  assuming either one is the complete backlog.
- **`cabin-orchestration-platform/locations/home/docker-compose.yml` —
  correction 2026-09-13: already flagged, only the sweep remains.**
  Checked the live file directly: a deprecation header was already
  added 2026-09-11 (predates this item's own "needs a banner" framing
  by two days), explicitly calling the file's "Full Stack" design
  superseded by the collector-hub model and pointing to
  `MAINTENANCE.md`'s real Home Location section. No code depends on
  this file. The only real remaining action is the actual removal/
  restructure ("not yet swept," per the header's own words) — not
  adding a banner, that part's done.
- **Home LAN device discovery — resolved, superseded (2026-09-13).** An
  earlier session's manual ARP/port-scan audit flagged one unidentified
  device (`192.168.1.119`) as needing physical identification by the
  user. That approach is now superseded entirely by the shipped
  `network-scan-agent` (mDNS-based, phone-side — see MAINTENANCE.md's
  "Home network scan agent" section): 6 real devices live-verified
  2026-09-12 (`netscan-lg_webos_tv_oled42c5pua`,
  `netscan-brother_hl_l2480dw`, `netscan-slzb_mr5u`, `netscan-myrouter`,
  `netscan-retropie`, `netscan-oled42c5pua`), all visible as candidates
  in Device Manager. No outstanding manual-identification action
  remains.
- **Ontology D17 still awaiting Cowork ratification; D18/D19/D20 newly
  proposed in the shared artifact (2026-09-11 through 2026-09-13) — not
  yet reviewed against this repo's own docs.** D17 (device_room↔
  hub_location edge, `data_class: product` lineage tier, new
  `knowledge_node`/`knowledge_chunk_type` entities) is implemented in
  `docs/ontology.yaml` (commit `c925a03`) but not yet ratified. D18
  (camera-derived media entity naming), D19 (local telemetry
  backup/log-shipping, triggered by the 2026-09-13 Zigbee mesh outage),
  and D20 (Live MQTT tile read-only relay) were proposed by a parallel
  session and remain open in the artifact's Discrepancy Log — next
  session should read them before assuming the artifact's D-number
  sequence is caught up here.

---

**Last full session close-out:** 2026-09-13 — a reconciliation pass
across two work threads that had drifted apart in this doc: this
session's own Home-collector/ontology work (2026-09-10/11, the entry
below) and a parallel session's Home `network-scan-agent` + the
2026-09-13 Zigbee mesh outage response + Live MQTT tile mixed-content
fix (visible via `git log` and `docs/MAINTENANCE.md`, but not yet
cross-referenced from this file before now). Corrected this footer's
own prior claim — "Home has real deployed devices, which it does not
yet" — which is no longer true: Home now has 6 real network-scan-
discovered devices plus a live Zigbee mesh, both routing to
`cabin-backend`. Cross-referenced the two independent WSJF backlogs
(this artifact's D-decisions vs. `docs/ai-assistant/wsjf-backlog.md`'s
C1–C4/Ollama-corpus track) for the first time — see the shared
artifact's new reconciliation pin and `wsjf-backlog.md`'s DEP09.
Flagged, not fixed: `locations/home/docker-compose.yml`'s stale "Full
Stack" header (new Open Item above).

**Second pass, same day, direct validation not just re-reading —
2026-09-13.** Nate asked for every Open Item to be checked against real
git/code/live state, not assumed from this file's own text. Confirmed
stale and corrected: PR #20/#21 were actually merged 2026-08-15 (this
file still called them "draft," a month out of date); the Kidde CO-alarm
bridge is live (real events landing, most recent 34 min old at check
time) — removed as resolved; `vault_ha_token` likely reconciled
2026-09-05 (vault file's own mtime moved, matching that day's HA_TOKEN
incident's own claim); `locations/home/docker-compose.yml` already had
its deprecation banner as of 2026-09-11 (only the actual sweep remains,
not "needs a banner"); `blinkbridge` has run 6 days with zero restarts
since its fix. Confirmed still accurately open, unchanged: `front_door`
camera (`camera_fps: 0.0` live), the stale
`claude/frigate-password-recovery-9a026d` branch (still on `origin` per
`git ls-remote`), Uptime Kuma's notification gap (5 of 6 monitors still
have no channel attached, confirmed via its own DB), `home_presence.yaml`
(still root-owned, unreconciled), the app-wide OAuth gate (still
unbuilt), the Node-RED iframe's raw LAN URL, and the severity
classifier's missing armed/presence awareness (though `WATER_PRESSURE_*`
alert rules already do this via a different code path — worth deciding
whether to extend that pattern here). One re-test came back genuinely
inconclusive rather than resolved: the local `mvn spring-boot:run` hang
failed differently this time (no local Postgres running at all, not the
original "hangs against an already-reachable stack" precondition) — not
evidence either way. Removed the now-redundant "Monitoring runbook"
item — `MAINTENANCE.md`'s own Monitoring section already documents what
Kuma/Homepage check. See git log for the actual session-by-session
record — that's the authoritative history now, not this file.

**Session close-out, 2026-09-17.** Four independent PRs landed on `main`
as one verified batch, per explicit user instruction to confirm they
wouldn't conflict before merging rather than assume it: #73 (eval
pipeline's auto-launched grader never passed `--criteria`, so every past
grading round through the normal flow was missing its own rubric), #74
(device-lifecycle facts consolidated into `MAINTENANCE.md`/
`REPLICATION.md` instead of a new doc, correcting two stale claims found
against live code — `registerPersistentCandidate()` already existed,
the manifest still called it an open gap), #75 (an ontology design
discussion, explicitly not ratified, revised after review rejected its
first draft and left as a discussion artifact), #76 (Rules & Alerts:
merged two separate alert boxes into one Status Checks card, added
persisted box reorder, removed the non-functional Kafka Topics box).
Verified responsibly, not assumed: a real local sequential dry-run merge
of all four (`git merge --no-ff`, one at a time, each committed before
the next) produced zero conflicts, confirmed again by GitHub's own
`mergeStateStatus` after the real merges landed, and the frontend suite
(331/331) was run against the actual combined `main`, not just each
branch alone. `claude-code/poc1-local-finetuning-pipeline` (#64) was
deliberately left out of this batch — it stays gated on the user's own
review per an earlier, explicit boundary in this same session — and was
separately confirmed (both by local dry-run and GitHub's real
recomputed status) to still merge cleanly against the new `main`, so
this batch doesn't block or complicate that decision whenever it's
made.
