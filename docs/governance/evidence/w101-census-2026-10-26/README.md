# W-101 runtime census, 2026-10-26 (first PR, partial)

Owner Code, verifier Codex, semantic review Cowork. Evidence only: no secrets, no raw addresses, no telemetry values. Unresolved rows are allowed and each names what is missing.

## What this is

`census.csv` has one row for each of the 75 records in [measurement attention framework section 10](../../proposals/2026-10-22-measurement-attention-framework.md) (39 allocation, 13 concrete ontology Zigbee, 7 other ontology appliance/camera/network, 14 disabled code seeds, 2 historical recognitions). The rows come from Git sources. The runtime half (which of them exist today, under what device id, with which reporting fields and first/last observations) needs a read-only export from the cabin database; [`scripts/host/w101-census`](../../../../scripts/host/w101-census) produces it (identity columns and field names only, inside read-only transactions). It had not been run when this PR was opened: Tailscale SSH to the M920q was waiting for an approval. Rows depending on it are `unresolved` with `runtime_present = unknown`.

## Counts (denominator 75)

| Measure | Count |
|---|---|
| Records in the census | 75 |
| Resolved | 14 |
| Unresolved | 61 |
| `runtime_present = seed_only` (code descriptor, hardware not installed) | 14 |
| `runtime_present = unknown` (needs the host export) | 61 |
| Enrollment `disabled` | 14 |
| Enrollment `unreviewed_candidate` | 61 |
| Site cabin / home | 56 / 19 |
| Original 75 reconciled to a runtime device | 0 of 75 (14 settled as seed-only, 61 await the export) |
| Unmatched originals | 61 (not yet checked against runtime) |
| Duplicate parents found | not yet checked; needs the export |
| Rows needing alias or history repair | 0 confirmed, 61 `unknown`, 14 `no` |

The 14 resolved rows are the disabled seeds in [`DeviceRegistry.java`](../../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/devices/DeviceRegistry.java): each is registered with `enabled = false` and a `home` location, and each row cites its source line.

## Column notes

- `record_id`: the section 10.6 binding reference. `canonical_id`: the manifest, ontology or code id after the em dash in that row.
- `runtime_present`: `seed_only` (descriptor in code, nothing installed), `unknown` (not yet checked against the database), later `yes` or `no`.
- `parent_or_service`: for allocation rows, the enclosing box derived from the id suffix (`-sen66`, `-sht45`, `-mcu`, `-adapter`).
- `measurements`: fields the document declares, not fields a device is proven to report. `supported_vs_declared` says so per row.
- `same_device_as`: left empty. The document keeps the dehumidifier outlet and attic sensor association with ordered allocations unresolved; the export decides it.

## How to extend this

Run `scripts/host/w101-census` on the host, then fill `runtime_present`, `same_device_as`, `supported_vs_declared`, `enrollment` (against the lifecycle state), `alias_or_history_repair_needed` and `status` per row from its output, and add `evidence_ref` pointing at the export section. Rows stay unresolved until their own evidence exists.
