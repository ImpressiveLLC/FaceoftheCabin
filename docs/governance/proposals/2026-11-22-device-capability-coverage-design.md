# Capability coverage: what a device can do versus what the hub shows (W-169)

Status: **proposal, design only; authorizes nothing.** Written by Cowork on 22 Nov 2026 from Nate's chat statement that he can reach only some of a device's capabilities through Home Assistant and that devices keep changing. It builds on the device catalog (D29, S2 merged in #569) and does not change it.

## The problem as Nate states it

A device is reachable in Home Assistant for only part of what it can do, and the set of devices and their firmware or integrations keeps changing. Today nobody can say, for a given device, which of its capabilities the hub surfaces, which it hides, and why.

## What the repository shows today (read from main at 0fd8dd63, not from a live host)

- Zigbee devices: `Zigbee2MqttAdapter` reads the device's declared `exposes` list, infers capabilities and a type from it, and builds the reported-field list from what the device exposes.
- Home Assistant devices: `HomeAssistantAdapter` discovers entity states and attributes and resolves entity-to-HA-device registry IDs through `deviceIdsByEntity()`. `HomeAssistantDiscoveryService` supplies that shared identity for Device Manager grouping. This does not prove complete coverage of every entity, including disabled or hidden entities, belonging to a physical device.
- Catalog S2 records which types exist and which readings each type reports, from `DeviceType.java` and the seed. The planned S6 (W-164) measures the opposite direction: readings a device sends that its type does not declare.

Nobody measures the forward direction: **what a device offers that the hub does not surface.** That is the gap. This note names it and proposes how to measure it before anything changes.

## Proposal: a read-only coverage report, then a decision

Three layers, each from a different source, compared per device:

1. **Offered:** what the source says the device can do. For Zigbee this is `exposes` from the Zigbee2MQTT bridge device list. For Home Assistant this is every entity registered to the same physical device (the device registry groups them), including entities that are disabled or hidden. For a vendor-app-only device it is the datasheet or the vendor's integration documentation, recorded as a cited fact, never inferred.
2. **Registered:** what the hub has as descriptors for that device (the entity ids and fields it actually reads).
3. **Shown:** what the UI and the workflow vocabulary expose (tiles, charts, triggers, actions, Ask inventory).

The report lists, per device, each offered capability with one of: shown, registered but not shown, offered but not registered, or offered and not mappable (no ontology row yet). It is **measure only**: no device is changed, no entity is enabled, no command is sent. Enabling a hidden Home Assistant entity or adding a descriptor is a separate reviewed change per device, in the same spirit as S6's "measure, never drop."

Why measure first: Nate's observation is general. The report turns it into a ranked list (which devices lose the most, and which gaps matter for safety types), so the next change is chosen from evidence and a new device or firmware change shows up as a diff instead of a surprise.

## How it ties to the ontology and taxonomy work

- **Measurement rows and capability rows (W-168, chain B):** an "offered but not mappable" finding is exactly a missing capability row. The report feeds the ontology instead of a hand-maintained list.
- **Catalog (W-160 to W-166):** the report adds one new check type to the catalog's parity story: a device's offered set against its type's declared set. It does not change S3 to S8 order.
- **Monitoring configuration (W-167):** a capability can only be offered to the dashboard if it is registered and has a measurement row; the report says which are blocked at which step.
- **Change tracking:** each run is stored with the catalog hash and the date, so a new device, a firmware update or an integration change appears as a diff against the previous run.

## Boundaries

- Read-only on every host. No Home Assistant configuration change, no entity enabling, no MQTT write, no backend deploy from this note.
- The M920q (the head) is read through the existing backend and Home Assistant read paths only; any host configuration change needs Nate's go.
- Safety types (leak, valve, lock, CO, smoke) are reported like the rest but nothing about them is changed by this work. Existing HA safety automations stay in Home Assistant.
- Nothing here reverses a decision. Interim assignment of entities in Device Manager (D29 N3) remains Nate's call.

## Steps and owners (proposed)

| Step | Work | Owner | Needs |
|---|---|---|---|
| C1 | Prototype the report as a script over a captured, redacted Zigbee2MQTT device list and a Home Assistant device and entity registry export; no live write | Code (any machine, including nhsmrekar and ILikeTheLights) | Nate's yes to export the registry read-only from the head (the export itself is a host read) |
| C2 | Review the report format and the "not mappable" categories against the ontology | Codex, Cowork | C1 output |
| C3 | Decide per device which gaps to close, in order of safety value, with each fix its own reviewed change | Nate, Cowork | C2 |
| C4 | Fold the check into the catalog parity test as a non-blocking report | Code | C3 and W-161 |

## Not claimed

No device has been measured. The statement that Home Assistant exposes only part of each device is Nate's, not a measurement. Which devices lose which capabilities is unknown until C1 runs.
