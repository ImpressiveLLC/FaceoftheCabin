# HO-2026-10-16 — Cowork → Code: D28 hardware naming, Midea Cube / Cloudline S6 integration

**Read first:** [D28](../decisions/ontology-decisions.md), [UC-20](../use-cases.md), backlog **W-77 to W-81 and W-84**, and [`docs/HARDWARE_INVENTORY_2026-10-16_sensor-relay-nodes-and-hvac.md`](../../HARDWARE_INVENTORY_2026-10-16_sensor-relay-nodes-and-hvac.md) (the master device list and wiring reference). This hands over documentation and governance only — nothing here has been built. The hardware itself (doc-27 build, home Zigbee additions, Midea Cube, Cloudline S6) is ordered and inbound, not yet physically installed.

**What this is.** Nate asked for a naming convention where a hardware *type* name is inherited verbatim into both the technical (`entity_id`) and UI (`display_label`) names of every physical unit, instead of inventing a name per unit; and a `related_to[]` tag so a unit whose action serves several use cases (a duct fan that's heating-, cooling- and humidity-relevant) isn't forced into one. D28 resolves both from the existing ontology (D1's snake_case identity rule, D13/D14's display convention, D27's relation model) rather than adding anything new to the core model — the only genuinely new property is `Device.related_to[]`.

## What's ratified vs. open

- **Ratified (Nate, 2026-10-16):** the Device Type Name registry, the entity_id/display_label derivation rule, and `Device.related_to[]` with its two HVAC assignments.
- **Answered, Q-DN-1 (Nate, 2026-10-17): the S6 is active controls (Option B, 0–10V/PWM speed control).** The actuator is not on order yet. W-78 designs and chooses it; build no wiring or control code until that design note is accepted.

## Build items (score, then build — none of these ships without its own PR and governance record)

| # | Item | What it needs |
|---|---|---|
| W-84 | ESPHome/WiFi adapter design note for `SensorCollectionBox`/`RelayControlBox`'s sensor half | Decide ESPHome-native-via-`ha_rest` vs. a new MQTT contract. Recommendation in the doc is ESPHome-native — cheaper, no new topic schema. Not yet ratified by Cowork; score and confirm before building. |
| W-77 | Midea Cube MAD50PS1QGR — HA's local-LAN Midea integration, paired, surfaced to `ha_rest` as `HOME_ASSISTANT_ENTITY` | Device key/token extraction (one-time, physical access to the unit). No cabin-backend code change expected — same path as the existing mech-room heater (W-27/W-28). |
| W-78 | Cloudline S6 control-path design note, with a BOM/cost delta for both options | Q-DN-1 is answered (Option B, active speed control). The note chooses the 0–10V/PWM actuator and gives wiring, BOM and cost. Needed before any Cloudline wiring or code. |
| W-79 | `Device.related_to[]` — schema, API (`GET /api/devices/{id}`), JSON-LD context, UI chip badge | New controlled vocab, additive to `reports_to[]`/`topic_of`. Straightforward schema addition, no migration risk named in D28 beyond the usual additive-field pattern already used for D14/D15/D16. |
| W-80 | Provision the actual inbound hardware under D28 naming once installed | Needs Nate on-site for the TBD location/application calls (4× relay, 2× plug, the dehumidifier's final room, the duct controller's final room). Not blocked on any code change — this is Z2M pairing + ontology entry work. |
| W-81 | Keep the hardware doc current | No code; a documentation discipline item so this doesn't go stale like the unported video-lifecycle spec (see `backlog.md`'s note right below the W-27 row). |

## Documentation already updated in this handover (no further action needed unless the facts change)

- `docs/governance/decisions/ontology-decisions.md` — D28 added in full.
- `docs/governance/use-cases.md` — UC-20 added.
- `docs/governance/backlog.md` — W-77 through W-81 and W-84 added, unscored (Cowork to score before Code starts any of them).
- `docs/HARDWARE_INVENTORY_2026-10-16_sensor-relay-nodes-and-hvac.md` — new, full master list + wiring reference + architecture diagram.
- `docs/ai-assistant/user-guide/maintenance.md`, `configuration.md`, `ask-and-helpdesk.md`, `docs/ai-assistant/training/intent-slot/README.md` — each got a short, explicitly-scoped note saying this hardware exists but is not yet live, configurable, in the KB, or in the resolver's inventory. None of these notes claims anything is built.

## What Code should not do yet

- Do not add the new `DeviceType`/`DeviceCapability` enum values to `CLAUDE.md`'s tables — that file documents shipped code, and none of this is shipped. Update it from the actual PR that ships each piece, per the project's existing practice (see how `AssistantInventoryController` was added to `CLAUDE.md` only once W-67 slice 1 merged).
- Option B is the chosen shape (Q-DN-1, 2026-10-17), but build no actuator wiring or control code before W-78's design note is accepted.
- Do not provision (W-80) any unit's `entity_id` ahead of physical install — D1 means it can never be renamed once minted, so get the location/application right the first time.

_Cowork, 2026-10-16._
