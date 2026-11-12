# Proposal: relocatable devices (one identity, many sites)

Date: 2026-11-12 (America/Chicago). Status: **proposed; ontology entries are `candidate`; nothing in runtime changes; Cowork to ratify and score.**
Origin: Nate, 2026-11-12: Box 2 should be assignable as a movable device and recognized when it moves from the Home Home Assistant to the cabin one, by receiver, MAC or similar. Backlog: W-157.

## 1. Problem

Box 2 (`fotc-qtpy-box2`, MAC `90:DA:72:71:51:10`) is on the bench at Home today and goes to the cabin upstairs claim zone later. The platform has no way to say a device is expected to change sites:

- The ontology has `hub_location` (Cabin, Home) and `device_room`, but nothing on mobility. A search of `docs/ontology.yaml` and `docs/governance` for mobile, transient, roaming and relocation finds none.
- `HomeAssistantDiscoveryService` builds the id as `"ha-" + location + "-" + entityId` (line 100). The same box adopted by the cabin Home Assistant after Home would therefore appear as a second, unrelated device and leave the first one stale, orphaning its history.
- D28 names units with a location suffix (`SensorCollectionBox_Cabin_Upstairs`), which is wrong for a device that does not stay put.

## 2. Name

**Relocatable device**, property `device_mobility` with values `fixed` (default) and `relocatable`. UI label "Movable Device". Rejected: mobile (reads as a phone or tablet), portable (implies battery or handheld), roaming (a network term), transient (already used for data-quality). It means changing site, not live position tracking.

## 3. Proposed model

1. **Identity is the hardware address.** `device_hardware_address` (the Wi-Fi MAC) plays the role for ESPHome and other IP devices that `zigbee_ieee_address` plays for Zigbee. Home Assistant stores it in the device's `connections`, so the platform can read it the same way `HomeAssistantAdapter` already reads device `identifiers`. Randomized per-network MACs are not an identity.
2. **Sightings, not renames.** Each hub's entity rows keep their existing ids (D1: nothing is renamed or re-minted). A `device_sighting` links one hub's rows to the identity with a last-seen time.
3. **Current location is derived**: the hub with the newest fresh sighting. It is never stored as an edited field, so there is no manual step that can drift.
4. **Read model groups by identity.** Lists show one device with the freshest sighting's location; history queries union the sightings, so a chart follows the box across sites.
5. **Naming.** A relocatable unit gets a type-name without a location (`SensorCollectionBox` plus a short label); the location add-on is dynamic from the current sighting. This amends D28's derivation for `relocatable` units only.

## 4. Open questions for Cowork and Nate

1. **Freshness window.** Proposed: a sighting is stale after 3 missed reporting intervals or 30 minutes, whichever is longer. The old hub's rows then show unavailable, not deleted.
2. **Both hubs see it.** If both Home Assistants have the box adopted, newest sighting wins; the other is flagged `superseded` rather than shown as a duplicate. Better: adopt it in one Home Assistant at a time (remove from Home's when it goes to the cabin).
3. **Manual "I moved it" override** for the minutes before the first fresh reading. Proposed: none in v1.
4. **Automations** that reference the device while it is away (for example a cabin humidity rule). Proposed: they see it as unavailable, like any offline sensor.
5. **D1.** `entity_id` is minted at install and never renamed. Proposed: the relocatable identity is minted once, at first adoption, from the hardware address; per-hub rows are not renamed.

## 5. Acceptance criteria (for the later build slice)

- A backend test: the same hardware address seen on two hubs yields one device whose location follows the newer sighting.
- A backend test: stale sighting, equal timestamps, and a device with no hardware address (registered as fixed).
- A history query that returns both sites' readings in time order for one device.
- A live check with Box 2: adopt at Home, then at the cabin, confirm one device and continuous history.

## 6. What this is not

No schema or Java change, no id rename, no change to how fixed devices are discovered, no live-position tracking. The four ontology entries (`device_mobility`, `device_hardware_address`, `device_sighting`, `barometric_pressure`) are `candidate` definitions only.
