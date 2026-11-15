# Proposal: ontology-defined Monitoring dashboard eligibility and per-device inclusion

Date: 2026-11-14 (America/Chicago). Status: **proposed; draft review notes and plan for Cowork to ratify, score and allocate IDs; nothing in runtime changes.**
Decisions: Nate answered four of the open questions on 2026-11-14; recorded in section 8.
Origin: Nate, 2026-11-14, while trying to put the NOAA aurora service (configured for the cabin) on Monitoring.
IDs: none allocated here. The D, W and UC numbers are allocated by the PR that introduces them (governance README, "ID allocation"). Placeholders below read `D-TBD`, `W-TBD`, `UC-TBD`.
Section 11 logs where the current ontology made this hard (Nate, 2026-11-14: record the difficulty so the sticky elements can be drilled into).
Evidence basis: code and `docs/ontology.yaml` read on `main` at `8827590` (`App.jsx` `kpiTileFor`, `DeviceType.java`, `DeviceDisplayConfig*.java`, `docs/ontology.yaml` header, D7, D13, D14). Not verified against the live site.

## 1. Problem

Whether a device gets a Monitoring tile is decided in JavaScript, per device type, by a hand-written switch. A person cannot configure it.

| Finding | Evidence |
|---|---|
| Eligibility is hard-coded. `kpiTileFor()` has a `case` for 15 of the 24 `DeviceType` values and returns `null` for the rest. | `App.jsx` `kpiTileFor`; `DeviceType.java` |
| The 9 types with no tile: `WATER_VALVE`, `DISHWASHER`, `WASHING_MACHINE`, `DRYER`, `ROUTER`, `UPS`, `GOOGLE_HOME_DEVICE`, `HOME_ASSISTANT_ENTITY`, `DASHBOARD`. Anything Home Assistant discovers that does not match a type rule lands in `HOME_ASSISTANT_ENTITY`, so it never gets a tile. | `HomeAssistantDiscoveryService.inferType()` falls through to `HOME_ASSISTANT_ENTITY` |
| Tiles have been added only after a user report, by editing code: four air-quality types (2026-08-27), three safety types (2026-09-19). Each comment in `kpiTileFor` records the report. | `App.jsx` comments |
| There is no inclusion control. An enabled device whose type has a tile is shown; the only way off the dashboard is to disable or ignore it in Devices. | `MnSeeView` builds the list from all devices |
| The lifecycle check ("in active use") is applied in only three branches (motion, contact, leak). The other tile types do not check it in `kpiTileFor`. Whether candidates are filtered upstream is not verified. | `deviceIsInActiveUse` call sites |
| Monitoring "Change" and "Add" render the same screen (`MnChangeView`), and "Remove" deletes display overrides, not the tile. The screen edits only display name, severity colour and state labels per presence profile. | `MonitoringPanel`, `MnRemoveView`, `DeviceDisplayConfig` |
| Tile content (label, unit, icon, value format) is also in the switch, and `DeviceType.telemetryFields()` is kept in step with `App.jsx` `SENSOR_FIELD_OPTIONS` by hand. | `DeviceType.java` doc comment |
| Tile order is stored in browser `localStorage` (`order.monitoring.<location>`), so it is per browser, not per household. | `MnSeeView` |

The aurora case: the NOAA integration's entities arrive as `HOME_ASSISTANT_ENTITY` (the visibility sensor has no device class), so they get no tile, and the D7 measurement vocabulary has no aurora type to give them one.

## 2. What Nate asked for (2026-11-14)

1. Monitoring configuration must be configurable, not code-only.
2. Some things cannot be eligible; most, maybe all, must be defined in the ontology.
3. Core metadata (the basic attributes and data elements) is locked.
4. Each defined item carries an "allowable for dashboard" binary.
5. There is a variable or toggle for the Monitoring dashboard when an item is a candidate to be added or removed.
6. A device can be removed from the dashboard view and stay enabled in Devices.
7. The NOAA aurora service, configured for the cabin, must be added, and named so a later Home instance is distinguishable (Cabin or Cumberland).

## 3. Proposed model: three layers, each with one owner

| Layer | Owns | Changed by | Where it lives |
|---|---|---|---|
| A. Ontology (locked) | `dashboard_eligible` (boolean), `dashboard_default` (`on` or `off`), `dashboard_tile_spec` (which measurement, unit, label source, icon key, state-to-attention mapping), plus the existing identity, `measurement_type`, unit and source | A reviewed PR only | `docs/ontology.yaml`, served by the backend |
| B. Instance inclusion (toggle) | `dashboard_included` per device or service | A signed-in person, at runtime | New server-side table, audit columns |
| C. Presentation overrides (existing) | display name, severity colour, state labels | A signed-in person, per presence profile | `DeviceDisplayConfig`, unchanged |

Rule: **a tile shows only if eligible AND in active use AND included.** The three conditions are independent facts.

- Not eligible: the toggle is disabled and says why ("no dashboard tile is defined for this type").
- Eligible, not included: appears in Monitoring "Add" as a candidate.
- Included: appears in Monitoring "Remove". Removing sets `dashboard_included` to false. It does **not** change the device lifecycle, alerts, history, Sensor History charts or Devices listing (request 6).
- Disabling or ignoring a device in Devices still takes it off the dashboard, as today, because it is no longer in active use.

### 3.1 Locked versus editable

| Locked (ontology, change by PR) | Editable at runtime (signed in) |
|---|---|
| device id, type, `measurement_type`, unit, source, `reporting_mode`, `dashboard_eligible`, `dashboard_default`, tile spec | `dashboard_included` toggle, display name, severity override, state labels |

### 3.2 Eligible at device level or service level

D13 makes the service (device plus `measurement_type`) the atomic unit for every reporting context. The Kidde unit has CO, CO2, temperature and humidity services; hiding humidity while keeping CO is a reasonable wish. Proposal: **eligibility and inclusion are defined per service entity**, with a device-level toggle as the convenience that sets all its services. Today's combined temperature-plus-humidity tile stays a device-level tile until a decision changes it (Q4).

### 3.3 Eligibility for every current type

Nate, 2026-11-14: keep the defaults limited to the types and classes already in use; do not invent new default classes. Read here as: defaults are set only for the 15 types that have a tile today, plus the aurora service. The other types stay not eligible until a tile spec exists. Correct this if "already in use" meant something wider.

| Type | Proposed | Notes |
|---|---|---|
| `SMOKE_ALARM`, `CO_ALARM`, `WATER_LEAK_SENSOR` | eligible, default on | Safety. Hiding the tile never changes alerts (see 6.1). |
| `TEMPERATURE_SENSOR`, `HUMIDITY_SENSOR`, `CO2_SENSOR`, `AIR_QUALITY_SENSOR`, `CO_SENSOR`, `THERMOSTAT` | eligible, default on | Tiles exist today. |
| `WATER_PRESSURE_SENSOR`, `POWER_METER` | eligible, default on | Tiles exist today. |
| `LOCK`, `CAMERA` | eligible, default on | Tiles exist today. Read-only on the dashboard. |
| `MOTION_SENSOR`, `CONTACT_SENSOR` | eligible, default on | Signed-out viewers keep seeing "Sign in for status" (D14, occupancy). |
| `WATER_VALVE`, `DISHWASHER`, `WASHING_MACHINE`, `DRYER`, `UPS`, `ROUTER` | not eligible yet | No tile spec exists. Per Nate (2026-11-14), defaults stay limited to the types already in use; these become eligible only when someone defines a tile spec for them. Deferred, not rejected. |
| `HOME_ASSISTANT_ENTITY` (generic) | **not eligible** | Becomes eligible only when it is given a real type or service spec. This is the gate that keeps unknown entities off the dashboard. |
| `GOOGLE_HOME_DEVICE`, `DASHBOARD` | **not eligible** | Platform objects with no reading to show. |
| NOAA aurora service (section 5) | eligible, default off | The one new addition, because it is the request. It is added by the person, not by default. |

## 4. Where it goes, UX and API

**Devices.** In See and Change, a device or service detail shows "Show on Monitoring" with the toggle, the eligibility reason when disabled, and a row chip "On dashboard". The new Devices search gains derived fields `dashboard:on`, `dashboard:off`, `dashboard:eligible` so the candidates are one query away.

**Monitoring.**
- Add: eligible and not included, each with "Add to dashboard".
- Remove: included, each with "Remove from dashboard".
- Change: presentation overrides only (today's screen). The current duplicate of Change under Add goes away.

**API.**
- `GET /api/devices` adds `attributes.dashboard = { eligible, included, effective, reason }`, computed beside `category` and `reportsFields` in `DeviceRegistry.withOntologyMetadata()`.
- `PUT /api/devices/{id}/dashboard` with `{ included: boolean }`; signed-in write path per D14; read-only in the demo view (D22).
- New table `device_dashboard_inclusion(device_id, location, included, updated_by, updated_at)`, by the same migration mechanism as the display-config table.

**Single source.** Eligibility and tile spec are served from one backend definition (the ontology), and `kpiTileFor` becomes a renderer of the spec. The current per-type logic is kept as named renderers so behavior does not change on day one. A drift test pins `ontology.yaml` against the Java enum, following the existing UI drift-test pattern (`SurfaceLayersDrift`, `AttentionCuesDrift`).

## 5. The NOAA aurora service, as the first consumer

- It is an **external feed**, not hardware: its values come from a service configured with the site's coordinates. Its location is a configuration fact, so the naming suffix can be derived from the site and not typed by hand.
- D7's measurement vocabulary is closed (14 types). Aurora visibility (percent) and the aurora alert (binary) need a **D7 amendment** adding the types, not free text.
- **Decided (Nate, 2026-11-14): the name carries "Cabin", derived from the location the service was set up for.** "Cabin" here is not any cabin; it is the location Nate selected as administrator when he configured the integration in Home Assistant. Setup is per location, so a Home instance is a separate configuration that will carry "Home". Names: "Aurora Visibility, Cabin" and "Aurora Alert, Cabin". No place-name field is needed now ("Cumberland" is not used).
- **Documentation is part of the work.** Setup and configuration, and maintenance, are written into the existing docs, not a new stand-alone doc: setup in `docs/REPLICATION.md` (select the location when adding the integration; how entities map to the site; how the name is derived), upkeep in `docs/MAINTENANCE.md` (what to check when the feed stops, visibility threshold, expected update rhythm, how to re-add for a new location).
- Discovery: `HomeAssistantDiscoveryService.inferType()` maps the integration's entities to the new types (the visibility sensor has no device class, so it maps by entity id or name, the same last-resort technique already used for the Kidde CO-level entity, scoped narrowly and commented).

## 6. Review notes and risks

1. **Safety must not depend on the dashboard.** Alert evaluation (`ActiveAlertService`) must not read `dashboard_included`. Required test: remove a smoke, CO or leak tile and confirm the alert and its banner still fire.
2. **Migration must change nothing visible.** On deploy, every device that has a tile today is backfilled `included = true`. Required test: the tile set before and after deploy is identical.
3. **Closed vocabulary.** Eligibility without a matching D7 type invites free-text types. New types go through a D7 amendment.
4. **Hand-synced duplication.** `DeviceType.telemetryFields()` and `SENSOR_FIELD_OPTIONS` are already two copies of one fact; this proposal should remove that, not add a third.
5. **Two "active use" definitions.** The UI helper `deviceIsInActiveUse` and the backend `lifecycleState().allowsActiveUse()` should be one, served from the backend.
6. **Tile order is per browser.** Tiles added from Devices will have no saved order on a second browser. Moving order server-side is out of scope here but is the same class of fix; see Q8.
7. **Public tier.** `/api/devices` is public (D14), so `dashboard_included` is public-readable. It reveals what the household watches, not whether anyone is home. Occupancy tiles keep their signed-out masking.
8. **Fit with open work.** W-157 (relocatable devices) means inclusion should key on identity when it lands, not on a hub row; W-159 (placement field) and UC-21 (measurement attention) mean tile state should come from the attention framework, not new per-tile code.

## 7. Phasing

| Phase | Content | Visible change |
|---|---|---|
| P0 | D-TBD decision text and ontology entries (`candidate`): `dashboard_eligible`, `dashboard_default`, `dashboard_tile_spec`, `dashboard_included`; D7 amendment for aurora types | none |
| P1 | Table, API, `attributes.dashboard`, tiles filtered by `included`, backfill, drift test | none (backfill makes it identical) |
| P2 | Devices toggle, chip and search fields; Monitoring Add and Remove screens | the controls |
| P3 | Deferred: tile specs for valve, appliances, UPS, router (not needed now; the types stay not eligible until then) | none |
| P4 | Aurora types, discovery mapping, tile, site-derived naming "Cabin", plus the setup and maintenance documentation | aurora tile, default off until Nate adds it |

## 8. Decisions and open questions

**Decided by Nate, 2026-11-14:**

| Q | Decision |
|---|---|
| 1. Default for newly eligible devices | Keep it limited to the types and classes already in use; do not reinvent. Per-type `dashboard_default` for the 15 tiled types only (section 3.3). |
| 2. Safety tiles removable | Yes, with a confirmation that alerts stay active. |
| 5. Aurora naming | "Cabin", derived from the location set up as administrator. Per location; Home is a separate setup. Needs setup and maintenance documentation. |
| 6. Aurora stopgap | No. Build the configurable mechanism first (P1, P2), then aurora (P4). |

**Still open:**

3. Per presence profile or global: should inclusion differ by profile like display overrides do? Proposed: global in v1.
4. Service level: hide humidity but keep CO on one device; should the combined temperature-plus-humidity tile split into two? Proposed: service level in the model, today's combined tile unchanged until decided.
7. Who may toggle: signed-in administrator only, or any signed-in household member?
8. Tile order: move it server-side in this work, or leave it per browser?

## 9. Acceptance criteria (for the build slices)

- A backend test per rule: not eligible cannot be included; eligible plus included plus in active use shows; removing sets only `included`.
- A backend test that alert evaluation is identical with `included` true and false for a smoke, a CO and a leak device.
- A migration test: the visible tile set before equals the set after.
- A drift test: every `DeviceType` has an eligibility entry in the ontology, and the ontology names no type the enum lacks.
- A UI test: Devices toggle writes `included`; Monitoring Add lists eligible-not-included only; Remove returns a device to Add and leaves it enabled in Devices.
- A UI test: a signed-out viewer still gets the masked occupancy tile.
- A test that the aurora name is derived from the location the service was set up for (cabin gives "Cabin"; a home setup gives "Home"), with no typed suffix.
- The setup and maintenance documentation exists in `docs/REPLICATION.md` and `docs/MAINTENANCE.md` and is linked from the aurora ontology entry.
- A live check on the cabin: add the aurora visibility tile, remove it, confirm the device stays enabled in Devices and its alert still evaluates.

## 10. Traceability

Use case: a household member chooses which devices and readings appear on Monitoring without a code change (`UC-TBD`; relates to UC-21). Decision: `D-TBD` (amends D7, D13; relies on D14, D22). Work items: `W-TBD` per phase, plus the aurora item. All allocated by the PR that introduces them.

## 11. Ontology fit log: where expressing this was hard

Method: parsed `docs/ontology.yaml` (245 elements, 467 relationship edges) and tried to place the proposal's properties and the elements they need. Refs O1 to O14 are local to this proposal; any that Cowork moves into the discrepancy log get a DL id by PR. Counts are from the file on `main` at `8827590`; "observed" means read from code, not checked live.

**Verdict.** The ontology is not yet good enough to make this easy. The graph backbone is healthy (467 edges, only 3 point at an element that does not exist). But most of the specific elements this proposal needs are missing, and a few decisions that matter live only in decision text and Java.

**What already works.** `device_candidate_lifecycle` defines the five lifecycle states. `applies_to_device_type` and `applies_to_capability` (29 entries) are a ready precedent for scoping a property to a type. `device_mobility` (candidate, 2026-11-12) is a clean template for how a new enum property is written, with constraint and edges. `device_reports_fields` names `DeviceType.telemetryFields()` as the canonical source and records the hand-sync with the UI.

| Ref | Area | What is hard or unclear | Evidence | Effect on this proposal | Drill-down |
|---|---|---|---|---|---|
| O1 | Device type and category | No element for `DeviceType` (24 values) or `DeviceCategory`. 17 of the 24 values are never referenced. `applies_to_device_type` names 8 values, one of them (`RF_TRIPWIRE`) not in the enum. `device_workflow_affiliation` derives from `device_type`, which does not exist. | ontology scan; `DeviceType.java` | `dashboard_eligible` has no natural owner keyed by type | Mint both classes with the enum as a constraint; add a drift test |
| O2 | Service entity (D13) | `display_label`, `reports_to` and `reporting_mode` appear 0 times in `ontology.yaml`; they live in D13 text and code. `environmental_measurement_service` is a candidate with one edge. The reporting-relationship JSON schema has no display label field, yet the UI reads `displayLabel`. | grep; `device-reporting-relationship.schema.json` | Per-service eligibility (3.2) has no element to attach to | Promote ServiceEntity to a first-class class with D13's properties |
| O3 | Measurement vocabulary (D7) | The closed 14-type list is in D7 text and `D7MeasurementTypes.java`, not an element. The ontology has per-quantity properties under other names (`temperature_celsius`, `carbon_dioxide_concentration`, `barometric_pressure`, `volatile_organic_compound_index`) against D7's `temperature`, `co2`, `pressure`, `air_quality_index`. Whether each pair is the same concept is not stated anywhere I found. | ontology scan; D7, D13 | Aurora types and tile units have no vocabulary to extend | One MeasurementType class holding the 14 values plus unit; state the pairings |
| O4 | The Monitoring dashboard | No element for the Monitoring panel, a KPI tile, a display config or a tile order. `DisplayConfig` has 0 mentions. `ui_device_manager_panel` exists; `ui_dashboard_overlay` belongs to Family Hub. | grep | The `dashboard_*` properties have no class to apply to | Mint MonitoringDashboard, TileSpec, DashboardInclusion, DisplayConfig |
| O5 | Class versus instance | 19 hardware-layer entries are single named devices (`zigbee_temp_kitchen`, `hw_router_c4000lg`) modeled as classes, next to `smart_appliance_*`. `extends` appears 9 times, so there is almost no is-a edge from these to a type. | ontology scan | Cannot say whether eligibility sits on a type, a catalogued unit or a runtime registry row, or how it inherits | Decide the three levels (type, catalogued unit, registry row) and add `is_a` |
| O6 | "Locked" | No field says who may change an element (locked, instance-editable, user-editable). Closest are `primitive`, `data_class`, `constraint`. | key survey of all 245 entries | Request 3 (core metadata locked) has no representation | Add a `mutability` field and a convention; backfill the locked core first |
| O7 | "Enabled" | Two meanings. The lifecycle enum (`ASSIGNED`, `AVAILABLE`, and so on) and a separate `attributes.enabled` toggled from the row; for a candidate the toggle means "accept and assign". 28 mentions of `enabled` in the ontology, none relating the two. | `deviceLifecycleState`, `DmRowEnableToggle` (observed) | "Remove from dashboard but leave enabled" is ambiguous until one meaning is chosen | Define enabled as a derived fact of lifecycle, or document both |
| O8 | External feed | A site-configured outside service has no element. One already exists in code, the outdoor weather service (`weather:` config), with 0 ontology mentions. | `application.yml`; `OUTDOOR_WEATHER_BASELINE.md` | Aurora and weather should share one ExternalFeed pattern, set once | Mint ExternalFeed; retrofit weather as its first member |
| O9 | Site identity | `hub_location` carries a display label and service URLs. `location.schema.json` is a device's room (`location_id`, `area`), not a site. No place name ("Cumberland") or coordinates element. | ontology; schema | The derived name suffix is the label "Cabin", which is what Nate chose, so no change is needed now (resolved for this proposal) | Place name and coordinates deferred until a use needs them (D6 and D24 area) |
| O10 | Attention state | "Attention" appears 5 times in `ontology.yaml`; the framework that maps a reading to a state is a proposal, not ontology elements. | grep; measurement framework proposal | A tile's state mapping has nothing to point at | Land the framework's classes before tile specs reference them |
| O11 | Relationship vocabulary | The header lists 11 edge types; 20 are in use (`derives_from` 103 against `derived_from` 12, `related_to` 15 against `relates_to` 8, plus `scoped_to`, `part_of`, `distinct_from`). `consumed_by` (171) and `depends_on` (40) are near inverses with no stated rule. 3 dangling targets: `device_type`, `device_descriptor` (twice). | edge count | Direction cannot be trusted for graph queries until normalized | Normalize the edge vocabulary, then graph |
| O12 | Maturity and header | 86 of 245 entries are still at schema 0.1.0; 122 have no `lifecycle_status`; 213 have no `first_verified_live`. File header says 0.6.0, last updated 2026-10-04, though entries were added 2026-11-12 and 2026-11-13. `fullstack` (7 entries) is not in the header's layer list. 4 entries are `primitive: true` with a `data_class` other than raw. | ontology scan | New entries sit beside stale ones; trust varies by entry | Migrate by priority as the dashboard classes are touched |
| O13 | Where definitions live | Four homes: `ontology.yaml`, governance decisions, JSON schemas under `docs/ontology/schema`, and Java enums. D7's vocabulary and D13's service properties exist only in decision text and code. `docs/ontology/DECISIONS.md` is a redirect, which is correct. | file survey | Section 4's "single source" must choose a home | Choose ontology as the home; decisions and schemas link to it |
| O14 | Display names | 13 entries share "(not shown)"; "Humidity" and "Kiosk mode" each appear twice. Legal under the header's uniqueness rule, but tile labels built from display names would clash. | ontology scan | Minor; tiles use D13's per-service label instead | Note only |

**Target graph to validate (a first sketch, not a decision).** Nodes: Site, Device, ServiceEntity, MeasurementType, DeviceType, DeviceCategory, TileSpec, DashboardInclusion, ExternalFeed. Edges:

| From | Edge | To |
|---|---|---|
| Device | `is_a` | DeviceType |
| DeviceType | `in_category` | DeviceCategory |
| Device | `located_in` | Site |
| ServiceEntity | `derived_from` | Device |
| ServiceEntity | `measures` | MeasurementType |
| DeviceType, ServiceEntity | `has_tile_spec` | TileSpec |
| ServiceEntity | `included_on` | DashboardInclusion (instance data, not ontology) |
| ExternalFeed | `scoped_to` | Site |
| ExternalFeed | `provides` | ServiceEntity |

**Suggested drill-down order** (so the sticky parts are settled before anything is graphed): (1) O1 and O5, the type and instance levels; (2) O2 and O3, service and measurement vocabulary; (3) O4, O6 and O7, the dashboard classes, the locked field and the meaning of enabled; (4) O8 and O9, external feed and site; (5) O11, normalize edges, then draw the literal graph. O10, O12 to O14 follow as those entries are touched.
