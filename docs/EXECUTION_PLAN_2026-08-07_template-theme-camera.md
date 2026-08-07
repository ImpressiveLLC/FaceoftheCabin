# Execution Plan — 2026-08-07 Template Configurability, Cross-App Theming, Configurable Place Cards, Camera Drilldown

> Written from a fresh, session-scoped clone (`/c/cc-work/FaceoftheCabin`,
> `main` @ `ac3e1f8`) — see the note at the bottom of `CLAUDE.md`'s
> multi-machine section: local clones outside the documented set (`ilikethelights`
> primary, M920q) are the user's own reference copies, not a workspace to
> commit in directly. This plan is the deliverable for this session; nothing
> below has been implemented yet. Review before any phase starts.
>
> Scope: four user-reported gaps, all grounded against real code/docs in this
> repo, not assumptions. Each section below names the exact file/line the
> finding came from. Ordered by what the user asked for; sequencing across
> the four is a separate decision to make once this plan is reviewed.

---

## 0. How this maps to existing docs (per the user's instruction: append to what exists, don't fork new structure without reason)

| User ask | Existing doc/phase it extends |
|---|---|
| #1 Dynamic hub/location templating + Grafana dashboards | `ROADMAP.md` Phase 1.5 (Location Context) + `REPLICATION.md` (currently find-and-replace, not runtime-config) + `DEFINITION_OF_DONE.md`'s top open item (Grafana has no working login) |
| #2 UI cleanup (rename, logo, theme carry-over, theme CI/CD sync) | New ground — no existing phase owns UI shell/branding/theming as a cross-app concern. Closest precedent: `ontology.yaml`'s `theme_preference` entity (single-app only today) |
| #3 Configurable, reorderable place cards | `ROADMAP.md` Phase 1.5 + the existing `useDraggableOrder` pattern (Device Manager, Monitoring panels) — same pattern, unapplied to `LOCATIONS`/place cards |
| #4 Camera Events showing device logs, DTM stamps, cold-storage drilldown, automation lineage | `ROADMAP.md` Phase 6 (Camera Video Viewing) — explicitly flagged there as unfinished: *"a browsable DTM timeline/scrubber UI is still a separate, unbuilt piece"* |

This plan is referenced from `ROADMAP.md`'s Priority Task List as **Phase 7**
(added in the same commit as this file) rather than duplicating the task
list here.

---

## 1. Dynamic hub/location templating + Grafana dashboards

### 1a. Grafana 403 / no dashboards

**Root cause is already fully diagnosed** in `MAINTENANCE.md`'s "Grafana —
Off-Tailscale Access via Cloudflare + Google OAuth" section and restated as
the **highest-priority open item** in `DEFINITION_OF_DONE.md`:

- Cloudflare Access isn't gating `grafana.unicornpingpong.com` at all (a
  cookie-free request reaches Grafana's real login page directly).
- Grafana's own Google OAuth 403s for `nhsmrekar@gmail.com` — same account
  that works fine for cabin-ui/family-hub through the same OAuth client. Not
  yet root-caused; one untested lead already written down (offline/refresh-
  token request shape vs. the simpler implicit flow cabin-ui uses).
- Because neither gate works, **password login was deliberately disabled**
  — nobody can log into Grafana at all right now, by design, not a bug on
  top of the 403.

Separately, and not yet documented anywhere: **even once login works, there
are no dashboards.** `infra/grafana/provisioning/` only has a
`datasources/timescale.yml` — no `dashboards/` provisioning directory exists
in this repo at all. So today's Grafana, even logged in, is an empty shell
pointed at a real TimescaleDB datasource with nothing built on top of it.

**Plan:**
1. Finish the two access-gate root-causes from `MAINTENANCE.md` (Cloudflare
   Access publish/propagation check; the OAuth request-shape lead). This is
   infra debugging against the live M920q, not something buildable from a
   local clone — needs a live session against the cabin host.
2. Once login works, add `infra/grafana/provisioning/dashboards/` — a
   provisioning config plus one dashboard-per-location JSON, **generated
   from a single template + the location config (see §1b)**, not
   hand-duplicated per location. This is the concrete deliverable for "a
   dashboard needs to be configured for each of Home and Cabin."
3. Dashboard content: derive panels from `docs/ontology.yaml` entities
   tagged with a location — i.e. the dashboard is ontology-driven (every
   `active` device/sensor entity for that location becomes a panel), not
   hand-picked metrics. This keeps Grafana honoring the same "ontology is
   the source of truth" principle the rest of the platform follows.

### 1b. Hub locations are hardcoded, not configurable

**Confirmed in code**: `cabin-orchestration-platform/ui/src/App.jsx`'s
`LOCATIONS` constant (lines ~33-55) is a literal JS object with exactly two
entries (`cabin`, `home`), each hardcoding its `apiBase`/`grafanaUrl`/
`noderedUrl`/`haUrl`/`frigateUrl`/`z2mUrl` as env-var-overridable defaults —
adding a third location means editing source, not configuration.
`REPLICATION.md` already documents the template-fork story (find-and-replace
domain/org values for a *whole separate instance*) but has no story for
*adding a location within one instance* — that's the actual gap #1 and #3
both point at.

**Plan:**
1. Promote `LOCATIONS` from a source constant to a backend-served resource:
   new `hub_location` ontology entity type (parallel to the existing
   `device`/`service`/`automation` types in `ROADMAP.md`'s Entity Types
   table) with fields matching today's hardcoded object
   (`id`, `label`, `apiBase`, `wsBase`, `grafanaUrl`, `noderedUrl`, `haUrl`,
   `frigateUrl`, `z2mUrl`) plus new ones needed for §3 (display fields,
   card order).
2. New `GET/POST/PUT/DELETE /api/locations` on `cabin-backend`, same shape
   as the existing `DeviceController` CRUD precedent. `LOCATIONS` in
   `App.jsx` becomes a fetched value, not a constant — same pattern already
   used for `devices`/`config`.
3. `LocationSwitcher`, `FamilyHubPanel`'s card grid, and the Grafana
   provisioning in §1a all read from this same list — one source, three
   consumers, instead of three places that can drift.
4. Update `REPLICATION.md` §2 to note that location values are now
   runtime-configurable (not find-and-replace) once this ships — the
   org/domain/host-name template values in that doc remain genuinely
   fork-time (they're about *which GitHub org/domain*, not *which
   locations*), so most of that doc is unaffected.

---

## 2. UI cleanup — naming, logo, cross-app theming

### 2a. "Family Hub" nav button is vestigial → rename to "My Places"

Confirmed: `FamilyHubPanel` (App.jsx ~865) renders the heading "Smrekar
Familia Hub" over a grid of location cards — it's a bridge/launcher panel,
not the actual Family Hub app (that's `family-hub/family-hub.html`,
reached via the "Open Family Hub ↗" button inside it). The nav label
"Family Hub" for this panel is what's confusing, not the panel's function.

**Plan:** rename the `PANELS` entry (`id: "FAMILY_HUB"` stays, `label`
changes to `"My Places"`), update the panel heading to match. Low-risk,
purely cosmetic — sequenced early regardless of what else this phase
prioritizes.

### 2b. Stray home icon in the nav rail

Confirmed: `NavRail` hardcodes `<div className="nav-logo">⌂</div>` (App.jsx
~2218) — a literal Unicode glyph, unrelated to the family crest. The actual
crest already exists and is documented: `ontology.yaml` ~3870-3899 defines
`source: hub_family_name + hodgson-crest.svg`, used today in
`family-hub/family-hub.html` (login screen, brand header, dashboard title)
but **never referenced from `cabin-ui` at all** — `cabin-orchestration-platform/ui`
has no crest asset and no `hub_family_name`-driven branding.

**Plan:**
1. Copy `hodgson-crest.svg` (or better: serve it from one shared static
   location both apps reference, avoiding a second copy to drift) into
   `cabin-orchestration-platform/ui`, replace the `⌂` glyph with it.
2. Extend the existing `hub_family_name` ontology entity so both apps read
   the *same* configured name/crest rather than `cabin-ui` hardcoding
   "Smrekar" anywhere new. This is the concrete implementation of "should
   carry over and is dynamically set by any user" — today only
   `family-hub.html` honors `hub_family_name`; `cabin-ui`'s "Smrekar Familia
   Hub" heading (App.jsx ~870) is a hardcoded string, same class of bug as
   the `⌂` glyph.
3. New ontology entity: `platform_branding` (id/logo asset ref, family
   name, tagline) — the parent entity `hub_family_name` currently lives
   under, generalized so a new instance (per `REPLICATION.md`) sets this
   once and both apps pick it up, instead of two separate hardcoded spots.

### 2c/2d. Theme doesn't carry over between apps; no reconciliation when a theme is added/removed

**Root cause, confirmed in code, is more specific than "needs syncing" —
it's two separate problems:**

1. **Cross-origin storage partitioning.** `family-hub.html` and `cabin-ui`
   are deployed on different subdomains (`hub.unicornpingpong.com` vs
   `cabin.unicornpingpong.com` per `ROADMAP.md` §3.1/3.2). Both use
   `localStorage['cabin-theme']` (same key name — `ThemeProvider.jsx`'s
   `THEME_KEY` and `family-hub.html`'s `THEME_KEY` at line ~4229, literally
   commented `"mirrors ThemeProvider.jsx"`) but browser `localStorage` is
   origin-scoped. Matching key names across two different origins share
   nothing — this is why "last-active theme" doesn't survive a link-out,
   not a bug in the theme-apply logic itself.
2. **Theme *definitions* are hand-duplicated, not shared.** `ThemeProvider.jsx`
   defines 7 presets; `family-hub.html` maintains its own separate `THEMES`
   object mapping cabin-ui's vars to family-hub's own CSS vars (~4232),
   built and kept in sync by hand. No CI/CD step reconciles them — the
   user's ask #2d (new/removed themes should propagate automatically) has
   no supporting mechanism today; `.github/workflows/` has three workflows
   (`deploy-cabin-backend.yml`, `deploy-family-hub.yml`, `rotate-secrets.yml`),
   none touching shared frontend config.

**Plan:**
1. **Extract theme definitions to one shared source.** New
   `shared/themes.json` (or `.js`) at repo root, generated/maintained once;
   both `ThemeProvider.jsx` and `family-hub.html`'s build step (`family-hub`
   already has its own `Dockerfile`/`package.json` — a real build step
   exists to hook into) consume it instead of hand-authoring two copies.
   This directly satisfies #2d's "CI/CD action on checkin to push any new
   themes" — the mechanism is a shared build-time source, not a runtime
   sync job, which is simpler and matches this repo's existing
   build-time-config pattern (`REPLICATION.md` §1: "host-driven config baked
   at build time").
2. **Theme removal semantics**: per the user's ask, a removed theme is
   disabled/hidden, not deleted — add a `status: active | retired` field to
   each theme entry in the shared source (same pattern `ontology.yaml`
   already uses for entities: `active | deprecated | candidate`). Retired
   themes stay in the file (re-introducible later) but are filtered out of
   `renderThemeGrid()`/the cabin-ui theme picker.
3. **Cross-app handoff for the actual carry-over behavior** (not just
   shared definitions — the *live* theme choice must travel): every
   link-out between the two apps (`family-hub.html`'s "How's the cabin?"
   button → `cabin-ui`'s `?panel=CAMERA_EVENTS` pattern already exists at
   App.jsx ~2254; `cabin-ui`'s "Open Family Hub ↗" button) appends
   `?theme=<id>` to the destination URL. Destination app reads that query
   param once on load, applies it, and persists it to *its own*
   origin-scoped `localStorage` going forward — solves "retain the theme
   currently used in the source app and set it as current in the app being
   navigated into" without needing shared cookies/domains, consistent with
   the two apps intentionally living on separate subdomains.
4. **New ontology entities**: `platform_theme_catalog` (the shared source
   file itself — what it is, where it lives, who reads it) and extend the
   existing `theme_preference` entity's `notes` to document the cross-app
   handoff mechanism and the origin-partitioning root cause, so this
   doesn't get re-diagnosed from scratch next session.
5. **CI check** (new, small step in an existing or new workflow): fail the
   build if `ThemeProvider.jsx` and `family-hub.html` define a theme id
   the shared source doesn't know about — cheap guardrail against the two
   files drifting again after step 1, without building a full bidirectional
   sync job the user's ask floated as one option.

---

## 3. Configurable, reorderable place cards

Confirmed: `FamilyHubPanel` maps `Object.entries(LOCATIONS)` straight into
`FamilyHubLocationCard` (App.jsx ~873-877) — fixed field set per card
(online/offline counts, up to 3 temperature readings, 4 fixed quick-link
buttons), fixed order (object key order), no per-user customization, no
concept of "add a new place." This is the same `LOCATIONS` object §1b
already plans to move server-side — this section is the UI layer on top of
that data change.

**Plan (depends on §1b's `hub_location` API existing first):**
1. **Card field configurability**: extend the `hub_location` entity (§1b)
   with a `display_fields` list (which of: online/offline counts, specific
   sensor IDs, quick-link set) — admin-configurable per location, matching
   the existing `DeviceDisplayConfig` precedent (`ROADMAP.md`'s Phase 1
   feature list: "per-device display overrides keyed by
   `(deviceId, location, profile)`" — same pattern, applied to locations
   instead of devices).
2. **Card ordering**: reuse `useDraggableOrder` as-is — it's already
   built and used for Device Manager and Monitoring panel reordering
   (CLAUDE.md's Current Feature State list). Wiring it to the place-card
   grid is applying an existing pattern, not new engineering.
3. **Responsive grid**: CSS grid with `auto-fit`/`minmax` sized so 9 cards
   fit without scrolling at desktop/kiosk width, degrading to 1-card-wide
   scroll on mobile (`resize_window` mobile preset = 375px in this
   project's existing verification tooling) — a CSS-only change once the
   card list is data-driven and reorderable, no new component logic.
4. **"Add a place" flow**: a form driving the same `POST /api/locations`
   from §1b — surfaced in `FamilyConfigPanel` (already the home for
   platform/location-level settings) rather than a new panel.

---

## 4. Camera Events panel showing device logs instead of camera events; DTM stamps; cold-storage drilldown; automation lineage

### 4a. Camera Events shows all device events, not camera events — confirmed root cause

`CameraEventsPanel` (App.jsx ~354-358) calls:
```js
fetch(`${apiBase}/api/events?limit=30&window=24h`)
```
`EventController.recentEvents` (`EventController.java`) **already supports**
a `camera` query parameter (`@RequestParam(name = "camera", required =
false)`) that scopes results — the panel simply never passes it, so it gets
every event type (leak, temp, motion, zigbee state changes) mixed with
camera events. This is a one-parameter fix, not a missing capability: the
backend filtering already exists and is unused by this one caller.

**Plan:**
1. Pass every enabled camera name (already fetched into `cameras` state via
   `refreshCameraList`, App.jsx ~375-390) as filter criteria, or — cleaner —
   add a small backend change: an `eventType`-based filter (`DETECTION_*`/
   `MOTION_*`, per `cabin_camera_event`'s documented `eventType` values in
   `ontology.yaml` ~1315) so the panel doesn't need to enumerate camera
   names client-side and stays correct as cameras are added/removed.
   Decide between these two during implementation; both are small.
2. Verify against a real signed-in session (this repo's own house rule —
   `DEFINITION_OF_DONE.md`'s "verification evidence travels with the
   change" — `curl` won't show the mixed-event-type bug, only a real
   browser session with real recent device + camera activity will).

### 4b. DTM (date/time) stamp on images

`cabin_camera_event_clip`'s notes (`ontology.yaml` ~1500-1553) describe an
authenticated blob-fetch thumbnail/clip player — no mention of an on-image
timestamp overlay. Two real options, to decide during implementation rather
than guess here:
- If Frigate's own snapshot/clip already burns in a timestamp (common
  Frigate config option, `ffmpeg.output_args` or an existing overlay
  setting) — verify against the *live* Frigate config
  (`GET /api/config`, the same "verify against real installed source, not
  generic docs" rule `REPLICATION.md` §6 already establishes for every
  device integration in this project) before adding a second overlay.
- If not enabled, either turn on Frigate's native overlay (simplest, no new
  code) or render the event's already-known `timestamp` field
  (`CabinEvent.timestamp`, already returned by `/api/events`) as an
  on-page caption next to each thumbnail/clip in `CameraEventsPanel` —
  no new data needed, this is a rendering change only.

### 4c. Access to a reasonable number of past captures; drilldown into cold storage (continuous/DTM recording on the M920q)

This is **explicitly identified as the one unbuilt piece of Phase 6**:
`ROADMAP.md` Phase 6, item (c) — *"Not done: real pagination/filtering
across full history (still capped at the most recent 30)"* — and
`cabin_camera_continuous_recording`'s ontology notes (~1458) — *"a
browsable DTM timeline/scrubber UI is still a separate, unbuilt piece —
`CameraEventsPanel` only shows event-anchored clips + live view, not the
full continuous timeline."* Both docs already name this as scoped-out, not
forgotten.

Also relevant and already flagged as unresolved in `DEFINITION_OF_DONE.md`:
continuous recording retention is currently `continuous.days: 5`, decided
conservatively because real GB/day for the 4K `driveway`/`front_door`
camera was never measured (camera is currently off-network). "Cold storage"
capacity planning depends on that number existing.

**Plan:**
1. Pagination/filtering on `/api/events` (cursor or offset-based, `camera`/
   `eventType`/date-range filters) — extends the same endpoint §4a already
   touches.
2. A genuinely new UI surface: a continuous-recording timeline/scrubber
   view, backed by Frigate's own recordings API (`GET
   /api/{camera}/recordings` per Frigate's real API — verify against the
   live M920q instance per the same "real installed source" rule, not
   assumed from generic docs) proxied through `CameraMediaController`
   alongside the existing snapshot/clip/live proxying.
3. **Template/replication requirement** (explicitly called out by the
   user): cold storage must be dynamically configurable, since a new
   instance (`REPLICATION.md`) may use different retention hardware/cloud
   storage entirely, not assume the M920q's local SSD. Model this the same
   way `hub_location` (§1b) generalizes location config: a
   `cold_storage_backend` ontology entity (type: `local_disk | s3_compatible
   | other`, connection details) that `CameraMediaController` reads instead
   of assuming a local path — this is the concrete answer to "know that new
   implementations will be on an entirely set of devices/cloud platforms."
4. Re-measure real GB/day once `front_door` (formerly `driveway`) is back
   on-network (`DEFINITION_OF_DONE.md` open item, not this plan's to fix)
   before finalizing retention/cold-storage sizing assumptions.

### 4d. Event metadata: why collected, duration, and linked automation/workflow; ontology-linked drilldown

This is the most architecturally significant of the four asks — it's asking
for the **Opportunity Map's own pattern** (`PRODUCT_NOTES.md`'s 2026-08-03
review; `OntologyLookupService`, `GET /api/ontology/entities`; "every
interaction logged," "lineage chips resolved to real device names") applied
to camera events instead of Tech ID findings. That pattern already exists
and already works end-to-end for one feature — this is reuse, not new
architecture.

**Plan:**
1. **Trigger reason + duration**: `CabinEvent` already carries `eventType`
   (`DETECTION_NEW|DETECTION_UPDATE|DETECTION_END|MOTION_ON|MOTION_OFF`)
   and `timestamp`; clip duration is already fixed/known
   (`pre_capture: 15` / `post_capture: 60`, `ROADMAP.md` Phase 6(b)). No
   new data collection needed — render what's already captured, which the
   panel doesn't currently surface as a caption ("motion-triggered,"
   "15s before–60s after").
2. **Linked automation/workflow**: this is new. Today, nothing records
   *which* Node-RED flow or HA automation reacted to a given camera event
   — `cabin-security` HA automations
   (`cabin-orchestration-platform/infra/cabin-security/`) and Node-RED
   flows (`infra/cabin-security/nodered/flows.json`) exist and react to
   events, but there's no `lineage` record connecting a specific
   `CabinEvent` to the specific automation run it triggered. `ROADMAP.md`
   Phase 3 already names this exact gap generally: *"Implement lineage
   record on every state-change: `{ from_event, via_automation, to_state,
   timestamp }`"* — unstarted. This ask is the concrete, motivating use
   case for finally building that Phase 3 item, not a new idea.
3. **"Take me to the automation's definition in the source system"**:
   once the lineage record exists, resolve `via_automation` to a real
   deep-link — HA automation editor URL (`{haUrl}/config/automation/edit/{id}`,
   pattern already used elsewhere for HA deep-links, e.g.
   `FamilyConfigPanel`'s `${haUrl}/config/integrations`) or Node-RED's flow
   editor URL. Same "reuse an existing pattern" note as above.
4. **Ontology definition of terms like "non-recognized person"**: this maps
   to the existing "See" interaction model
   (`ROADMAP.md`'s See·Think·Act section: *"Lineage is one click away"*) —
   once an event links to `via_automation`, the automation's own ontology
   entry (already required to exist per `docs/ontology.yaml`'s coverage
   rules) supplies the definition. No new mechanism, but it does require
   automations to actually be registered as ontology entities with real
   `description`/condition fields — check `cabin_security_automation`-type
   entries exist for the current HA/Node-RED automations; if not, that's
   itself a DoD §9 gap ("every entity a user can Create/Read/Update/Delete
   has an ontology entry") to close as part of this work, not skip.
5. **Disable-and-auto-rearm from an alert**: the user wants a signed-in
   user to disable an automation directly from an alert, with it
   re-arming per the automation's own "arm automatically when ___"
   condition. This needs a real `PATCH`/enable-disable endpoint against HA
   automations (HA's REST API already supports toggling an automation's
   `enabled` state) plus a scheduled re-check against that automation's
   arm condition — closest existing precedent is `PresenceService`
   evaluating conditions on an interval. New work, not a reuse case like
   the rest of §4d, and the riskiest item in this whole plan since it
   writes to live security automations — sequence this last within Phase
   7 and require a live-tested rollback path before shipping.

---

## 5. Ontology schema gap — per-element lifecycle status + provenance (user-flagged, checked against real schema this session)

The user raised this from a prior (undocumented in git) conversation: every
ontology element needs a **lifecycle status** (used / deprecated-unused /
candidate / derived concept) and **provenance** (when it was first used —
release/commit/other reference — or when it was deprecated), so cross-
referencing "is this thing still real" doesn't require asking the user.

**Checked against the actual schema, not assumed:** `docs/ontology.yaml`'s
real v0.3.0 schema (header comment, ~lines 29-76) already has:
- `migration_status: complete | partial | pending | planned` — tracks
  progress migrating *the entry itself* to the current schema version, not
  whether the underlying concept is still in use.
- `first_verified_live` (added 2026-08-07) — a date + what was concretely
  checked to confirm the concept is real. Close to "first used" but answers
  "when did we confirm this works," not "when did this enter the system"
  or "when did it stop being used."
- A `status: master | candidate | deprecated` field exists, but only on the
  `location_vocabulary_term` sub-schema (~line 762) — not a universal
  per-element field. The aspirational `Canonical Ontology Entry Schema` in
  `ROADMAP.md` (§ Ontology — The Special Sauce) documents a top-level
  `status: active | deprecated | candidate` field, but real entries in
  `ontology.yaml` don't carry it — that section is a design target, not
  what's actually implemented.

**Confirmed gap**: no universal per-element field for lifecycle status, no
`first_used`/introduced-in provenance, no `deprecated_date`. This is exactly
what the user described.

**Plan — schema addition (v0.4.0), same migration pattern already
established for v0.3.0:**
1. Add three new per-element fields to the schema:
   - `lifecycle_status: used | unused | deprecated | candidate | derived_concept`
     — distinct from `data_class` (raw/derived/conceptual/composite, about
     data *nature*) and from `migration_status` (about the entry's own
     documentation currency).
   - `first_used: { date, ref }` — `ref` is free text: a commit hash, a
     `ROADMAP.md` phase name, a `PRODUCT_NOTES.md` session date — whatever
     is the actual most-specific pointer available, same "free text, not
     enum" precedent as `TechIdFinding.provider` (`ROADMAP.md`'s Tech ID
     Provider Model — deliberately unconstrained so it never blocks on a
     missing enum value).
   - `deprecated_date` — set only when `lifecycle_status` is `deprecated`
     or `unused`.
2. **Do not backfill all ~150+ existing elements in this pass.** Follow the
   existing v0.3.0 migration precedent exactly: add the schema fields,
   track a pending count, migrate in priority order (highest-consumer-count
   entities first — reuse the same `migration_priority` ranking logic
   already in `ROADMAP.md`'s Ontology Migration Review table), reviewed
   monthly alongside the existing schedule. Add a new row block to that
   table for v0.4.0 rather than resetting the v0.3.0 counts.
3. Every **new** entity created from this point forward (including every
   new entity this Phase 7 plan itself proposes — `hub_location`,
   `platform_branding`, `platform_theme_catalog`, `cold_storage_backend`)
   gets these three fields from day one — no second-class treatment for
   new entities while old ones catch up, per `DEFINITION_OF_DONE.md`'s
   item 9.
4. Once populated for a meaningful subset, this directly answers the
   user's actual ask: "is X still real, and since when/until when" becomes
   a grep against `ontology.yaml`, not a question back to the user.

---

## Sequencing note

Not decided in this plan — the user asked for this document first,
sequencing second. Two structural dependencies worth flagging regardless of
final order:
- §3 (place cards) depends on §1b (`hub_location` API) existing first.
- §1a step 2 (per-location Grafana dashboards) depends on §1b existing
  first, for the same reason.
- Everything in §4 is independent of §1/§2/§3 and could be sequenced first
  if the user's stated urgency (confusing Blink notifications, happening
  today) outweighs the architectural work.
