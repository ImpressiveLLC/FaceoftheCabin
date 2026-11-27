# W-167 C1: code census and migration acceptance

Status: **design review only**, 2026-11-27. Baseline main `d1fcdfb645d82844a2c2285bcc14c1c5763fd2ad`. Assigned by [Cowork 7221571296](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-7221571296). Supplements the [B1/B3/C1/C2 draft](2026-11-27-derived-services-monitoring-draft.md); no B2 decision, runtime change, device enrollment or live census is made here. W-167 is reused; no new ID.

## Current source behavior

Counts below were recomputed from the pinned Java enum and UI switch. They are code counts, not counts of installed or enabled devices.

| Surface | Observed behavior | Source |
|---|---|---|
| Type/renderer coverage | 24 `DeviceType` values; 15 cases in `kpiTileFor`; nine have no direct renderer | `devices/model/DeviceType.java`; `App.jsx` `kpiTileFor` |
| Pre-render projection | Electrical package projection runs first, then duplicate Zigbee services collapse. A source type need not equal its projected card type | `App.jsx` `MnSeeView`; `electricalMonitoring.js`; `zigbeeServices.js` |
| Enabled filtering | `LocationMonitoringSection` keeps rows with `enabled !== false` **or** `monitoringGroup`. Missing enabled is not equivalent to disabled | `App.jsx` `LocationMonitoringSection` |
| Lifecycle checks | Motion/contact/leak renderer branches call `deviceIsInActiveUse` and require their reporting fields. The electrical projection has its own lifecycle predicates. There is no single common lifecycle predicate for every rendered type in this path | `App.jsx` `kpiTileFor`, `deviceIsInActiveUse`; `electricalMonitoring.js` |
| Location | Single-location input keeps matching or unlocated rows. Both-location mode passes the shared ordered list into separate Cabin/Home sections, whose filters also accept unlocated rows | `App.jsx` `MnSeeView`, `LocationMonitoringSection` |
| Ordering | Browser key `order.monitoring.<activeLocation>`; saved IDs first, stale IDs ignored, new items appended. `ALARM`/`CRITICAL` items are pinned first and cannot be dragged into or out of the pinned segment | `App.jsx` `useDraggableOrder`, `MnSeeView` |
| Add/Change | Both currently render `MnChangeView` | `App.jsx` `MonitoringPanel` |
| Remove | Deletes a presence-profile display override; does not remove a tile or disable a device | `App.jsx` `MnRemoveView`; `DeviceController.deleteDisplayConfig` |
| Display overrides | Keyed by device, location and presence profile; store display name, state labels and severity override. This is not a global inclusion/order store | `DeviceDisplayConfigService` |
| Writes | Device GET/HEAD reads are allowed by the device-read rule; other methods proceed through credential checks. Demo tokens are denied non-GET/HEAD methods before route classification | `GoogleAuthInterceptor`; `DemoAccessFilter` |

Directly rendered types: `AIR_QUALITY_SENSOR`, `CAMERA`, `CO2_SENSOR`, `CONTACT_SENSOR`, `CO_ALARM`, `CO_SENSOR`, `HUMIDITY_SENSOR`, `LOCK`, `MOTION_SENSOR`, `POWER_METER`, `SMOKE_ALARM`, `TEMPERATURE_SENSOR`, `THERMOSTAT`, `WATER_LEAK_SENSOR`, `WATER_PRESSURE_SENSOR`.

No direct renderer: `DASHBOARD`, `DISHWASHER`, `DRYER`, `GOOGLE_HOME_DEVICE`, `HOME_ASSISTANT_ENTITY`, `ROUTER`, `UPS`, `WASHING_MACHINE`, `WATER_VALVE`. This does **not** mean every source row of those types is absent: an eligible smart-switch `HOME_ASSISTANT_ENTITY` can project to a `POWER_METER` group. Eligibility migration must evaluate the current presentation mapping rather than blindly apply an enum allowlist to source rows.

Source roots at the pinned baseline: [UI App.jsx](../../../cabin-orchestration-platform/ui/src/App.jsx), [electrical projection](../../../cabin-orchestration-platform/ui/src/electricalMonitoring.js), [Zigbee collapse](../../../cabin-orchestration-platform/ui/src/zigbeeServices.js), [type enum](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/devices/model/DeviceType.java), [display-config service](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/devices/display/DeviceDisplayConfigService.java), [device controller](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/DeviceController.java), [auth interceptor](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/security/GoogleAuthInterceptor.java), [demo filter](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/security/demo/DemoAccessFilter.java).

## Compatibility gaps to settle before implementation

1. **Group identity versus source enablement.** An electrical parent may be disabled while enabled children supply the group card. Existing projection marks it `monitoringGroup` and explains that source arrangement. A generic enabled-parent filter would mute valid child readings. Proposed C1 compatibility rule: inclusion attaches to the existing stable group/card identity, while visibility retains the current projection's source-eligibility proof. It must not enable the parent or its commands. This refines the earlier draft's shorthand “enabled device”; peer review must confirm it before implementation.
2. **Projection before inclusion.** Filter at the presentation level after existing duplicate collapse. Otherwise removing a parent card can resurrect its formerly collapsed child as a duplicate tile. Keep hidden member identity/provenance for explanations; removing a group changes one global inclusion preference, not source membership or telemetry.
3. **Lifecycle is not yet uniform.** Do not claim current behavior already implements the proposed common active-use rule. A future normalization needs explicit before/after cases and approval for any visible difference. C1 compatibility migration must not silently bundle that behavior change.
4. **Unlocated and both-location behavior.** An unlocated card can be rendered by both sections today. Do not silently assign Cabin or generate a second persistent device ID. Record this as an unresolved location-binding case; migration must report affected rows before changing their presentation.
5. **Shared order changes authority.** Today an unsigned browser can change its own local order. Server-side order is a household write and must require sign-in. Keep anonymous read access and alarm pinning; do not persist alarms' transient positions as the user's underlying preferred order.

These are design findings, not claims of a live incident or authorization to alter the current behavior.

## Proposed migration sequence and rollback

1. Build a reviewed mapping from existing rendered/projected card identity to ontology tile specification. Reuse the existing projection functions as the compatibility reference; do not introduce a parallel device registry. Golden fixtures compare the exact visible card set, IDs, values, labels, units, masking and alarm pinning before and after. Ambiguous mapping is a reported blocker, not a generic fallback tile.
2. Add separate global inclusion/order persistence with stable keys, audit actor/time and revision checks. Preserve `device_display_config` unchanged for profile-specific presentation. Migration/backfill is idempotent and initializes only the already-rendered compatibility set. An explicit user inclusion choice must never be overwritten by a later backfill.
3. Shadow-read the new projection/configuration without changing the rendered UI. Report missing specs, collisions and before/after differences; resolve or explicitly accept each difference before cutover. No telemetry is dropped or relabeled.
4. Replace Monitoring Add/Remove semantics with inclusion changes, while retaining a separately named reset-display-overrides action for the old Remove capability. Do not reuse the existing DELETE endpoint to mean inclusion=false. Changing display preferences must not change global membership.
5. Move order to the server with a reviewed bootstrap rule. There is no single canonical order to import from several browsers. Proposed default: deterministic existing-server-input order with alarm pinning, then an explicit signed-in reorder; alternatively a one-time user-confirmed browser import. **Choice still open.** No silent last-browser-wins import. Treat the `both` browser order as a migration case, not an independent source of location identity.
6. Rollback disables the new read/write path and restores the existing renderer/order behavior while retaining new preference rows for investigation. Keep old display overrides and raw observations intact. New clients must not keep sending shared writes after rollback. No rollback toggles alerts, enrollment or physical devices.

## Acceptance matrix for the build PR

These are required future tests, **not tests executed by this documentation PR**. Existing `App.test.jsx`, `electricalMonitoring.test.js`, Zigbee-service tests, `WriteGateAuditTest` and `DemoAccessTest` are the starting suites.

| Case | Required result |
|---|---|
| Each of 24 types, including every existing projection | Type coverage complete; baseline visible set and content preserved. Generic unknown entities do not become tiles simply because inclusion was requested |
| Disabled electrical parent with enabled, eligible children | Existing group remains representable without parent activation; readings retain child provenance; disabling all supplying services removes the compatible projection |
| Zigbee parent and duplicate HA service, including empty parent value | One card and current fill behavior retained; hiding the card does not reveal a duplicate; raw source rows/history unchanged |
| Ambiguous services and aliases | No new arbitrary source choice, parent rewrite, merged history or implicit enrollment |
| Enabled missing, false, candidate, ignored, deferred, removed | Characterize each current path first; do not assume all paths behave identically. Any intended normalization is separately reviewed |
| Global include/remove from two authenticated browsers | Same inclusion state after reload, independent of presence profile. Removed item remains in Devices with history and enrollment unchanged |
| Smoke, CO and leak removal | Alert evaluation and notification behavior identical before/after; confirmation explains that alerts stay active |
| Profile display reset versus tile removal | Old override-reset capability retained; neither operation silently performs the other |
| Missing/stale observation on included tile | Stale/unavailable presentation; inclusion does not fabricate a current value or ONLINE state |
| Reorder with ALARM/CRITICAL cards | Alarm pinning preserved; transient alarm changes do not overwrite saved base order; signed-in write required |
| Stale revision and simultaneous updates | Conflict response with refetch/retry, no silent lost update; duplicates/unknown/cross-location IDs rejected rather than corrupting order |
| Anonymous and demo writes by every exposed method | Denied before persistence; unchanged row counts and revisions. Demo reads use approved projection; no real occupancy leak |
| Authenticated ordinary household member | Inclusion/order write allowed under the recorded ruling; no new admin-only restriction. Locked metadata edits and arbitrary fields rejected |
| Read-only/shared token attempting a write | No write-scope escalation; existing token and managed-access rules still apply |
| Public motion/contact reads | Existing sign-in prompt and neutral icon preserved; hidden state must not leak through new eligibility/reason/order metadata |
| Unknown device ID, missing location, stale identity | No default-to-Cabin write or new registry row. Resolve canonical identity explicitly or reject with a useful reason |
| Repeated backfill, restart, rollback, second browser | Preferences stable, no double-created rows; fallback is visible and no write silently claims success |

## Gates and next owner

This slice needs neither B2 profile placement nor a training run. It prepares W-167's C1 build but does not start it. Cowork peer review should rule on the group-identity compatibility refinement and shared-order bootstrap before a backend/UI implementation slice is assigned. Unlocated rows and any lifecycle normalization need explicit disposition, not an inferred fix. B2, source review and W-84/D29/S7 remain gates for the derived/Box consumers; aurora still follows the mechanism and its enrollment decision. No device or host was inspected live.
