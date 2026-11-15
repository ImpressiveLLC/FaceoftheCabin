# Ontology drives device adds: reconciliation of the north star with the code

Written by Code on 2026-11-14 at the owner's direction. This is a fact-finding and question document. It decides nothing and changes no code. Cowork has the instructional role: research, clarify in documentation, and bring the open questions in section 5 to the owner.

## 1. The north star

The owner states that **the ontology drives everything** on this platform, as a north star of the project. For devices that means:

1. Every device add, and every device check-in, is driven by an ontology entry.
2. What stays outside the ontology is only what is specific to one physical unit: its check-in or registry id, its hardware address, and the readings it reports. Those are the only things telemetry should carry for the device.

This is recorded as the target, not as a description of today.

## 2. Correction to an earlier answer

On 2026-11-14 Code told the owner that `docs/ontology.yaml` is "governed documentation rather than the thing that creates devices". That was too strong. The accurate statement is in section 3: the yaml is read by the running backend in two places and is tied to the code by a few parity tests, but it does not decide what type a device is, and it is not consulted when a device is added.

## 3. What is true on `main` today

| Area | Source of truth today | Evidence |
|---|---|---|
| Entity display names, type labels | `docs/ontology.yaml`, read fresh on every lookup by `OntologyLookupService`; falls back to a humanized id if the file is not mounted | `ontology/OntologyLookupService.java`; its javadoc notes `docs/` is bind-mounted into the container, not baked into the image |
| Candidate workflow triggers | `docs/ontology.yaml` `trigger_*` entities with `lifecycle_status: candidate`, merged live into the rules vocabulary | `OntologyLookupService`, `RulesController` |
| Device type, category, capabilities, reported fields | The Java enum `DeviceType` (category, `telemetryFields()`); `DeviceRegistry.withOntologyMetadata()` serializes these onto every device read. The enum's own comment calls it "the one canonical place" for reported fields | `devices/model/DeviceType.java`, `devices/DeviceRegistry.java` |
| Which type a newly seen Home Assistant entity gets | A heuristic on the entity's HA domain and `device_class` (`inferType`), with a generic fallback. No ontology lookup | `integrations/homeassistant/HomeAssistantDiscoveryService.java` |
| Measurement types | Two copies: a JSON schema under `docs/ontology/schema/` and the Java class `D7MeasurementTypes`. A test asserts the two are the same set | `D7MeasurementTypesTest` |
| Ontology file integrity | A test checks that ids are unique and a few named entities exist. It does not compare ontology entities with `DeviceType` | `OntologyYamlTest` |
| Telemetry rows | `telemetry(time, device_id, metric, value, unit, meta)`. The only link to the ontology is through the device row's type | database schema on the cabin hub |
| Device rows | `device` rows are written when someone acts on a device (assign, ignore). Home Assistant discovery otherwise holds candidates in memory | live check on the cabin hub, 2026-11-14 |
| Check-in | `/api/devices/checkin-status` and `/checkin-details` exist. What they key on is not yet read or documented | `api/DeviceController.java` |

## 4. The gap, stated plainly

- **Adds are not ontology-driven.** A new device is classified by a heuristic, not by looking up an ontology entry. An unknown device is guessed, not refused or held.
- **There are two type systems.** The ontology's entities and the Java `DeviceType` enum describe overlapping things, and no test ties them together. They can drift.
- **Telemetry is not ontology-scoped.** Nothing at write time checks that a device's metric is one its ontology entry says it reports.
- **A worked case: Box 3.** Box 3 reports 27 live entities in cabin Home Assistant. It has no device row, no ontology entry, and no type beyond what the heuristic would assign, so its metrics are not recorded and it reads as not active. The `SensorCollectionBox` type is described in `docs/hardware/doc27-environmental-nodes.md` and the W-84 backlog row gates its enrollment. The relocatable-device entries merged in PR 435 (`device_mobility`, `device_hardware_address`, `device_sighting`) are `lifecycle_status: candidate`, so nothing reads them yet.

## 5. Open questions for Cowork to research and clarify, then bring to the owner

Cowork is asked to answer what it can from the records, write the answers into documentation, and bring only the real decisions to the owner.

1. **Direction of the source of truth.** Should `ontology.yaml` generate the Java types, validate them with a parity test (as D7 does for measurement types), or be loaded at runtime to decide them? Each has a different effect on the build and on offline use.
2. **What a lookup keys on.** When a new entity or device appears, what finds its ontology entry: integration and manufacturer/model, an ESPHome project or device name, a hardware address, or something the device declares at check-in?
3. **Unknown devices.** If no ontology entry matches, should the platform hold the device as a candidate and refuse to activate it, or keep today's heuristic fallback with a visible flag?
4. **What stays outside the ontology.** Confirm the list: check-in or registry id, hardware address (`device_hardware_address`), current location (derived from sightings), and the readings themselves. Is anything else per-unit?
5. **Runtime dependency.** `docs/` is bind-mounted into the backend container today, and a missing mount degrades to guessed labels. If the ontology decides device types, that fallback is no longer acceptable. Should the ontology ship in the image, or be loaded from a pinned source with a failure mode that refuses to enroll?
6. **Check-in.** What do `/api/devices/checkin-status` and `/checkin-details` key on today, and what should an ontology-driven check-in send and receive? Code has not read these endpoints yet.
7. **Telemetry scoping.** Should a telemetry write be checked against the device's ontology-declared reported fields, and what happens to a reading that is not declared?
8. **Sequencing with W-84 and relocatable devices.** Does Box 3 enrollment wait for this, or enroll under W-84 as a first instance of the ontology-driven path?

## 6. What happens next, and what does not

- Cowork researches and documents the answers to section 5. Code supplies code facts on request through #244.
- The owner decides the questions that are decisions. Nothing is built until they are answered.
- Until then, Box 3's metrics remain unrecorded by the platform. The owner can choose to accept that, or to assign its entities in Device Manager as an interim measure, knowing the interim rows would later need reconciling with the ontology path.

## 7. What this document is not

Not a decision, not a design approval, and not authorization for any code, migration, device assignment or ontology edit. Omission is not permission: anything not stated here stays as it was.
