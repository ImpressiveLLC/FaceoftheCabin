# S2 parity check 7: a golden table of today's per-device types (design, 2026-11-22)

Status: **design only; nothing captured, nothing run.** This is item (a) of Cowork's S2 follow-ups on #244 (7075465576). It records the remaining acceptance row of the [S2 design](2026-11-17-s2-device-catalog-v0-design.md) (section 4, check 7) and of the [roadmap](2026-11-14-ontology-drives-device-adds-roadmap.md) S2 row: "golden table of today's per-device types for the live inventory". No host read, no live call and no export happens in this PR.

**Tracked item:** this needs a work id. Cowork allocates W-ids (W-160 to W-168 went to the ontology track in #564), so the id is requested in the PR, not assigned here.

## 1. What check 7 proves, and what it does not

Checks 1 to 6 in `DeviceCatalogParityTest` prove that the catalog agrees with the code: the enum, the ontology, the `inferType` branch counts and fixtures written from reading each branch. They do not prove that the catalog's match rules give **the same type the platform gives today** for the devices that actually exist.

Check 7 closes that gap offline. A committed snapshot of today's devices records, for each one, the inputs its `inferType` site reads and the type the platform assigned. The test runs every snapshot row through the catalog's match rules and asserts that the result equals the recorded type.

It is a regression fence for S3 to S5 (shadow read, gate, check-in from the catalog). It is not a live check: a device added after the snapshot is not covered until the snapshot is refreshed. Disagreements found in live traffic stay S3's job.

## 2. The inputs each site reads (main at `0fd8dd63`)

| Site | Method | Inputs a row must carry |
|---|---|---|
| Home Assistant discovery | `HomeAssistantDiscoveryService.inferType(String domain, Map attrs)` | `domain`, `attrs.device_class`, plus every other attribute the method reads, such as the unit fallback |
| Zigbee2MQTT | `Zigbee2MqttAdapter.inferType(JsonNode definition, Set<DeviceCapability> caps, String friendlyName)` | `definition.model`, `definition.vendor`, `definition.description`, `definition.exposes`, the capability set, and `friendlyName`, which the valve and smart-switch predicates use |
| MQTT bridge | `MqttBridgeService.inferType(Map data)` | the payload **keys** (`psi`, `temp_f`, `alarm`, `locked`, `motion` and the rest) and `alarm`'s value; never other payload values |

The implementation must re-read each method and list every field it touches. The table above is a reading, not a contract.

## 3. Snapshot format

Proposed file: `docs/ontology/fixtures/live-inventory-snapshot.json`, validated by a schema next to the catalog schema.

- **Header:** capture time (UTC), source revision (main SHA), capture tool and its content SHA-256, the row count per site, and the platform's device count at capture, so that missing rows are visible.
- **One row per device:**
  - `site` (`ha`, `z2m` or `mqtt`);
  - `row_id`: the SHA-256 of the platform device id, so the file names no device;
  - `inputs`: exactly the fields from section 2;
  - `assigned_type`: the `DeviceType` the platform holds today;
  - `lifecycle`: only rows that are ASSIGNED or AVAILABLE count; candidates and ignored rows are recorded but excluded from the assertion.
- **Privacy rule:** no names, rooms, sites, IPs, MAC or IEEE addresses, and no telemetry values. The one exception is `friendlyName` for Z2M rows, because `inferType` reads it. Two options, and Cowork or Nate decides:
  - **(i)** Store `friendlyName` as is. It's simplest and exact, but household naming lands in the repo.
  - **(ii)** Store only the keyword features the valve and switch predicates derive from the name. That's private, but the test then trusts a feature extractor that has to mirror the predicates, and it needs its own test.

  The recommendation is (ii), with the extractor taken from the same constants the predicates use, so the two cannot drift.

## 4. Capture (later, gated)

The capture is a **read-only** export on the M920q. It reads the platform's device list (the current type and lifecycle), the Home Assistant states (domain and attributes), the retained Zigbee2MQTT `bridge/devices` definitions, and the MQTT bridge's last payload keys. It writes no device, topic or setting.

It needs Nate's go in the capturing session's own chat, as with every host read. The capture tool is committed and reviewed first; the snapshot is a separate PR, reviewed for the privacy rule before merge.

## 5. Test behaviour

In `DeviceCatalogParityTest`:
- Load the snapshot (fail, not skip, under `REQUIRE_ONTOLOGY_DOCS`).
- Run every counted row through the catalog's match rules for its site.
- Assert the type equals `assigned_type`.

A disagreement fails the test and lists each `row_id`, site, rule result and assigned type. The fix is either a rule change or an entry in a small reviewed `known_differences` list with a reason. A difference is never fixed by editing the snapshot. A header row count that doesn't match the rows also fails.

## 6. Acceptance for the implementing PRs

1. The capture tool, with tests on synthetic inputs, and proof that it writes nothing.
2. The snapshot, under the privacy rule, with the header counts matching the platform count at capture.
3. Check 7 in the parity test. A mutation that changes one match rule's type fails it.

Each PR is reviewed by Codex, or the proxy, and by Cowork.

## 7. Not claimed

No snapshot exists. Nothing was read from any host, no `inferType` method was re-read field by field for this note beyond section 2's reading, and no check is implemented here.
