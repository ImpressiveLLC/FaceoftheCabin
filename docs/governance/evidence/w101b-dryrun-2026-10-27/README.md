# W-101b dry-run: identity and alias repair plan, 2026-10-27

Owner Code; verify Codex; semantics Cowork. **Read-only. Nothing was written to the platform, the database or Home Assistant.** Scope: Cowork rulings 1, 2, 4 and 5 on [#286](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/286#issuecomment-6430565624), Starlink first. Source: the W-101 census export of 2026-10-26 (raw output stays private; this plan carries only row ids, which are Home Assistant entity names or Zigbee friendly names, with any Zigbee hardware address shortened to its last six hex digits).

Files: [plan.csv](plan.csv) (one line per step: before, after, rollback, test, approval) and [children.csv](children.csv) (the 43 rows that point at a missing parent; they are not edited).

## What the plan does

| Ruling | Steps | Change |
|---|---|---|
| 4 (Starlink first, then Liebherr, sun, Zigbee2MQTT bridge, HA backup, router) | S01-S06 | Create the six missing parent rows (the router parent disabled and CANDIDATE, see D1). The 43 child rows already name them, so no child is edited. Starlink alone covers 21 rows. |
| 1 mechanical-room temperature | 7 steps | Set the Home Assistant temperature entity's parent to `z2m-temp_mech_room`, and flatten its six sibling entities onto the same parent. |
| 1 bathroom leak alarm | 5 steps | Point the five rows whose parent has no row at `z2m-leak_alarm_bathroom` instead of creating the missing switch row. |
| 2 Kidde | 2 steps | Link the group-B temperature row to group A's CO alarm; the group-B CO row is already linked (verify only). |
| 5 old dehumidifier id | 1 step | Needs code: no alias mechanism exists. |

The mechanism is the existing `parentDeviceId` attribute and the `ServiceHistory` fold from #275. No history moves; readings stay under both ids and reads fold.

## Three findings that change the plan

1. **The fold only reads direct `sensor.*` children of a parent.** A child left under the Home Assistant temperature entity (not under the Zigbee parent) would keep its own series. That is why ruling 1 flattens the six siblings. If Cowork prefers a two-level tree, the fold has to be extended instead.
2. **Ruling 5 has no mechanism today.** The old dehumidifier id (`z2m-Smart_Switch_Zigbee_|_Dehumidifier`, 646 readings, 2026-10-23 to 2026-10-24) has no device row, so nothing can attach to it, and `ServiceHistory.idsFor` folds only `ha-` sensor children. The plan proposes a `formerDeviceIds` attribute on the current parent read by `idsFor`. This is a small code change with a test; it needs Cowork's sign-off on the name first.
3. **The API cannot restore a dangling parent.** `DeviceRegistry.validateParentDeviceId` rejects a parent with no row. Rolling back the five bathroom-leak steps therefore needs a host-run statement that restores the recorded before value, not a PATCH. For the six created parent rows, rollback is a DELETE of the new row and restores the exact before state because no child is touched.

Also noted, no action planned: the Kidde group-B humidity id and the `-2` duplicate bridge ids have readings and no device row; a link cannot fold what has no row.  The Home television duplicates stay held (ruling 3).

## Rulings applied (Cowork, posted on this PR; Codex live-checked the links)

- **D1.** Create the missing parent rows, in ruling-4 order, Starlink first; no re-parenting alternative. A created parent copies `ASSIGNED` and `enabled` only where its group has an ASSIGNED and enabled child (Starlink 6, Liebherr 1, sun 2, bridge 1, backup 1). **Amendment: the router parent (S06) is created disabled and CANDIDATE**, because all three of its children are disabled and CANDIDATE and no enrollment is inferred from discovery. Nate approves each row at apply.
- **D2.** Flatten the six mechanical-room siblings onto `z2m-temp_mech_room`; no two-level tree, no fold extension.
- **D3.** Former ids go in a `formerDeviceIds` list on the current parent, read by `ServiceHistory`; history only, never a parent id, never a routing alias, no data movement. Code is in [#291](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/291).
- **Rollback.** Created rows and dangling parents are restored by a host-run statement with a dry-run and row counts before and after (see the rollback column and finding 3), not by the API: `DELETE /api/devices/{id}` only sets the lifecycle to IGNORED and keeps the row, and `PATCH` cannot restore a parent that has no row.

Verified by Codex against live read-only data: all 43 children match their recorded parent, type, lifecycle and enabled state; the 14 before-links match; the six created parents are absent. The Kidde and mechanical-room identity calls are Cowork's rulings (hypotheses until applied), not row facts.

## Tests to write before an apply (named in plan.csv)

- **T1.** After the change, `DeviceRegistry.validateParentDeviceId` accepts the new parent, there is no cycle, and no row has two parents.
- **T2.** Duplicate-parent guard: one physical device has exactly one canonical root, and a second root with the same hardware is rejected or flagged.
- **T3.** `ServiceHistory.idsFor(z2m-temp_mech_room)` returns the Zigbee id plus the folded entities, and the chart shows one temperature and one humidity series for the mechanical room; history row counts are unchanged.
- **T4.** The five bathroom-leak rows resolve to `z2m-leak_alarm_bathroom`, and the active-alert and card paths still find the leak sensor.
- **T5.** Kidde: the group-A series includes the group-B temperature readings, and no group-B reading is lost.
- **T6.** Each created parent row appears once, with its children listed, and the device list count rises by exactly one per created row.
- **T7.** The dehumidifier's old id folds into its current parent without changing any row count.

## Apply preconditions

Per-row approval from Nate. Create steps need one more read-only host step to read each created row's Home Assistant entity id (the census did not export connection strings); that read belongs in the apply step, not in Git. The API writes need a signed-in administrator session. Nothing here is run until the dry-run is Codex CLEAN and Cowork has ruled on D1 to D3.
