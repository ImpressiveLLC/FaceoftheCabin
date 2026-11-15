# S8: last-known-good device snapshots, ontology stamp, and conflict detection

Scoped by Code on 2026-11-15 at the owner's direction ("scope S8"). It extends the roadmap in [PR 449](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/449) (`2026-11-14-ontology-drives-device-adds-roadmap.md`) after the owner's N1 ruling. This is a scope and acceptance document. It decides nothing and changes no code, schema, ontology entry or device.

## 1. Need

From the owner's N1 answer (recorded on PR 449): each time a device is adjudicated, the ontology it was matched against must be checked against the ontology in the run tables; the same check runs over all devices as a gate; new devices are noted periodically as part of testing; and when a conflict happens (numbering or registry clash, a bad insert, a UI overwrite lost to a connectivity hiccup) the platform recovers to the last known good values, which trace back to the ontology (table, ontology, back to table).

## 2. Findings that shape the scope

1. **The ontology version number is not maintained.** `docs/ontology.yaml` says `version: "0.6.0"` and `last_updated: 2026-10-04`. The version string was last changed on 2026-11-03, and the file has had four commits since (2026-11-09, 11-11, and two on 11-13) with no bump. A check on that number alone would pass while the content changed. **S8 therefore stamps a content hash of the ontology (and of the catalog) and treats the version number as a label.** Whether to also enforce a version bump in CI is question Q4.
2. **What exists today.** `DeviceRedetectController` (W-93) applies a type and capabilities change and returns a rollback object, and `POST /api/devices/{id}/redetect/rollback` restores it. The values live in the response and, per its own comment, in memory for the last apply; they are not stored per device and carry no ontology stamp. W-101 and W-101b give immutable canonical ids, aliases and dry-run repair plans. The `device` table has `version`, `modified_by` and `updated_at`; `JdbcDeviceRepository` increments `version` on update (this draft did not verify whether any caller checks it before writing).
3. **Not found on `main`:** a stored last-known-good snapshot, a temporary-id mechanism, an ontology stamp on device rows, or any scheduled scan that lists new or conflicting devices.
4. **Table creation pattern.** There is no migration framework; repositories create or alter their tables at start-up with `IF NOT EXISTS` (for example `JdbcDeviceRepository`). S8 follows that pattern.

## 3. Scope

### In scope

**A. Ontology stamp (depends on S3).** Every device row and every device read carries the ontology content hash and the catalog hash it was last adjudicated against, and the time. Adjudication (assign, redetect apply, the S4 gate) refuses to proceed if the hash it was matched with differs from the hash currently loaded, and reports why.

**B. Last-known-good snapshot.** A new table `device_known_good` (device id, captured at, reason, snapshot of the device's type, capabilities, name, area, parent, lifecycle state, ontology hash, catalog hash, actor). A snapshot is written when an adjudication or admin write succeeds and the post-write validation passes. Keep the last 3 per device. No secrets, tokens or credentials are ever stored in a snapshot.

**C. Conflict detection, read-only first.** A scheduled scan (default every 6 hours, also callable by an admin) that reports, without changing anything:
- two ids or aliases that resolve to the same physical device, or one id minted twice;
- a service whose parent device row does not exist;
- a telemetry `device_id` with no device row;
- a device whose type is not in the catalog (the N2 "unmatched" count);
- a device whose ontology stamp differs from the current one;
- a device present now that was not in the previous scan (the "new device" note).
The report is stored, logged, and its counts appear on the status board.

**D. Admin restore.** An administrator-only endpoint that previews and then restores a device from a chosen snapshot through the existing `DeviceRegistry.replaceConfiguration` path, with an audit event, generalizing the re-detect rollback. The snapshot carries its ontology stamp, so a restore shows whether the snapshot is itself older than the current ontology.

### Out of scope (a later slice, S9, only after S8 shows what real conflicts look like)

Temporary-id assignment, automatic restore, and any change that writes a device without an administrator. Also out: the catalog (S2), the shadow read (S3), the unknown-device gate (S4), box enrollment (S7), the W-157 relocatable runtime, and any deploy.

## 4. Acceptance rows

| # | Acceptance |
|---|---|
| 1 | A device adjudicated against ontology hash X is refused when the loaded hash is Y, with a message naming both. |
| 2 | Every successful adjudication writes a snapshot; a failed post-write validation writes none. Only the last 3 snapshots per device are kept. |
| 3 | A snapshot contains no field named like a secret, token or password (test over the serializer). |
| 4 | The scan, run over a fixture with each conflict listed in C, reports each exactly once and changes no row. |
| 5 | The scan reports a device that appears between two runs as new. |
| 6 | Restore is refused for a non-administrator, previews before applying, writes an audit event, and restores type, capabilities, name, area, parent and lifecycle exactly as snapshotted. |
| 7 | Leak sensor, water valve, lock and CO alarm types are never changed by anything in S8 without an administrator action. |
| 8 | Existing assigned devices are unchanged by S8's introduction except for the added stamp. |
| 9 | Backend suite passes; new routes carry their demo-access entry and class-level `@CrossOrigin` (existing guard tests). |

## 5. Order and dependencies

S8 follows S3 (it needs the catalog and ontology hash and the stamp on reads) and builds A and B first, then C, then D. S2 supplies the catalog the hash is taken from. N2 (ruled) supplies the unmatched-device meaning used by C. Cowork reads each S8 PR against these rows.

## 6. Risks

1. **Restoring over a legitimate change.** D previews and requires an administrator and a confirm; snapshots are labeled with reason and actor so a deliberate change is recognizable.
2. **Snapshot growth.** Bounded by the keep-3 rule.
3. **A stale stamp blocking enrollment after a harmless ontology edit.** Intended by N1; the refusal message must say which hash differs and that re-adjudication is the fix, so it is not mistaken for a fault.
4. **Detection noise.** Start read-only and log only; do not page on the first runs.

## 7. Questions for the owner and Cowork

1. **Q1, retention.** Is keeping the last 3 snapshots right?
2. **Q2, who restores.** Administrator only? (Recommended.)
3. **Q3, scan cadence.** Every 6 hours, or tied to deploy and CI only?
4. **Q4, version number.** Enforce a bump of `version` in `docs/ontology.yaml` on every ontology change in CI, in addition to the hash? Recommended: yes, as a label for humans; the hash remains the check.
5. **Q5, automatic restore for safety devices.** Recommended: never automatic for leak, valve, lock or CO types, even in S9.

## 8. What this is not

Not a decision, not a design approval, not authorization for any code, schema, ontology edit, restore, deploy or device change. Omission is not permission: anything not stated here stays as it was.
