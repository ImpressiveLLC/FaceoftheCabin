# Hardware inventory and integration reference — doc 27 build + home Zigbee + HVAC addendum

**Date:** 2026-10-16. **Decision record:** [D28](governance/decisions/ontology-decisions.md). **Use case:** [UC-20](governance/use-cases.md). **Backlog:** W-77 to W-81 and W-84 ([backlog.md](governance/backlog.md)). **Status:** hardware ordered/inbound, not yet physically installed or provisioned in HA/Z2M. This doc is the master reference Nate asked for — full inventory, wiring/schematic reference, naming per D28, and ontology placement per unit. Keep it current per W-81 rather than opening a new dated doc for the next hardware change.

**Source order record (claude.ai project, not this repo):** `claim/2026-10-15-doc27-sensor-relay-node-live-carts-built.md` — DigiKey $313.58, Adafruit $100.05, Amazon ×2 ($98.19 + $147.44), Home Depot $1.70. Grand total $660.96 for the build below, plus the Midea Cube and Cloudline S6 addendum (ordered separately, not in that total).

---

## 1 — Master device list (standalone, hub-location-based)

| Unit | D28 type | Location | `display_label` | Protocol | Status |
|---|---|---|---|---|---|
| Box 2 | `SensorCollectionBox` | Cabin — upstairs claim zone | `SensorCollectionBox_Cabin_Upstairs` | WiFi/ESPHome (W-84) | inbound |
| Box 3 | `RelayControlBox` | Cabin — bathroom | `RelayControlBox_Cabin_BathroomExhaustHeat` | composite: WiFi/ESPHome (sensor) + Zigbee (2× relay) | inbound |
| Box 4 | `UtilityRelayNode` | Cabin — mech room / breaker box | `UtilityRelayNode_Cabin_MechRoomBreaker` | Zigbee (existing Z2M path) | inbound |
| Home sensor ×5 + 1 spare | `ZigbeeTempHumiditySensor` | Home — mech room, laundry, kitchen/dishwasher-sink, garage, basement storage, +1 spare | `ZigbeeTempHumiditySensor_Home_{Location}` | Zigbee, via SLZB-MR5U | inbound |
| Home relay ×4 | `ZigbeeMiniRelay` | TBD (Nate to assign at install) | `ZigbeeMiniRelay_{Location}_{Application}` | Zigbee | inbound |
| Home plug ×2 | `ZigbeeSmartPlug` | TBD | `ZigbeeSmartPlug_Home_TBD{n}` | Zigbee | inbound |
| Dehumidifier | `SmartDehumidifier` | Home — basement (TBD confirm) | `SmartDehumidifier_Home_Basement` | WiFi, HA-native (W-77) | inbound, addendum |
| Duct fan controller | `DuctFanSpeedController` | TBD (cabin or home whole-home HVAC) | `DuctFanSpeedController_{Location}_WholeHomeHVAC` | Q-DN-1 answered 2026-10-17: active control (Option B); W-78 designs the actuator | inbound, addendum |
| Coordinators (owned, not new) | `ZigbeeCoordinator` | Cabin (SONOFF ZBDongle-E) / Home (SMLIGHT SLZB-MR5U) | unchanged — no rename (D1) | USB-serial / network | already live |

**Box 1** (lower-level claim-zone `SensorCollectionBox`) is **deferred** — tariff timing and the circulator fan's air-mixing effect on that zone's readings, per the order record. It is the fourth member of the `SensorCollectionBox` type family and will take the next free location slot when it proceeds.

## 2 — Per-unit bill of materials and wiring reference

### 2.1 `SensorCollectionBox` (Box 2) and the sensor half of `RelayControlBox` (Box 3)

Shared sensor package:

| Part | Role |
|---|---|
| Adafruit QT Py ESP32-S3 | MCU, WiFi, runs the sensor firmware |
| Sensirion SEN66 (+ SEN6x breakout, + jumper cable) | Particulate / VOC / NOx / CO₂* / temperature / humidity, all-in-one module |
| Sensirion SHT45 | Secondary high-accuracy temperature/humidity reference |
| 3× Qwiic cables | I²C daisy-chain: QT Py → SHT45 → SEN6x breakout |
| Mean Well IRM-05-5 | 5V hardwired power module (both Box 2 and Box 3 are hardwired, not battery) |
| Otdorpatio IP65 enclosure | Weatherproof project box |
| Acrylic rod stock | Light pipe for the QT Py's status LED through the enclosure wall |
| IEC C14 power inlet | Mains entry point into the enclosure |

*CO₂ is read by the SEN66 but is **not** a reportable `measurement_type` on this platform (D13) — carried as raw telemetry only, never surfaced as a service entity under that name.

```
                 ┌───────────────── SensorCollectionBox ─────────────────┐
  IEC C14 ──AC── │  Mean Well IRM-05-5 ──5V DC──▶ QT Py ESP32-S3          │
  (mains in)     │                                   │ I²C (Qwiic)       │
                 │                                   ├──▶ SHT45           │
                 │                                   └──▶ SEN6x bkout──▶SEN66
                 │                                   │ WiFi               │
                 └───────────────────────────────────┼───────────────────┘
                                                       ▼
                                           ESPHome API → Home Assistant
                                           (W-84 — adapter decision)
```

### 2.2 `RelayControlBox` (Box 3) — relay half

| Part | Role |
|---|---|
| 2× Sonoff MINI ONE PM (Zigbee) | Bathroom exhaust fan relay + heat-fan relay, each with power monitoring |
| Manual toggle switch | Hardwired override, independent of the Zigbee control path — a fail-safe if Z2M/HA is down |

```
  Mains ──┬──▶ [Manual toggle] ──▶ Exhaust fan
          └──▶ Sonoff MINI ONE PM #1 (Zigbee) ──▶ Exhaust fan (auto path)
  Mains ──┬──▶ [Manual toggle] ──▶ Heat fan
          └──▶ Sonoff MINI ONE PM #2 (Zigbee) ──▶ Heat fan (auto path)
```

Both relay modules pair to the cabin Z2M coordinator independently of the sensor half's ESPHome/WiFi link — see D28 "Composite enclosures" for why this is modeled as two Devices `part_of` one enclosure grouping rather than one cross-protocol Device.

### 2.3 `UtilityRelayNode` (Box 4)

| Part | Role |
|---|---|
| Sonoff ZBMINIR2 (owned, Zigbee) | Relay for the mech room / breaker box |
| Vented plug-and-play enclosure | No sensor package, USB-powered, no Mean Well module |

Simplest unit in the build — pairs directly to the existing cabin Z2M bridge like any other Zigbee relay; no new adapter work.

### 2.4 Home Zigbee additions

| Part | Qty | SKU / ASIN |
|---|---|---|
| SONOFF SNZB-02P temp/humidity (2-pack) | 3 packs = 6 units | B0CLR7V3W7 |
| SONOFF MINI-ZB1GSP relay | 4 | B0H464X7RR |
| SONOFF S61SZBTPB smart plug | 2 | B0HDXX279N |

All pair to the home-location coordinator (SMLIGHT SLZB-MR5U, already live and relaying home camera data to the M920q — D23, no change to that device's identity).

### 2.5 HVAC addendum

| Part | Role | Integration |
|---|---|---|
| Midea Cube MAD50PS1QGR | WiFi dehumidifier, direct | HA local-LAN Midea integration (W-77) — `SmartDehumidifier`, `related_to: [humidity, climate, comfort_air]` |
| Cloudline S6 "Quiet" 6 in. inline duct fan speed controller | HVAC booster fan, humidistat-driven variable speed | **Q-DN-1 answered 2026-10-17: active speed control (Option B), W-78 designs it.** No native network interface, so it needs an added 0–10V/PWM actuator for true speed control; not yet on order. A passive power-monitor plug/relay (Option A) is only the interim fallback |

## 3 — Integration and application architecture

```
                         ┌─────────────────────────────┐
                         │   M920q — the head (D23)     │
                         │  HA · Z2M · Postgres · Kafka  │
                         │  cabin-backend · Grafana      │
                         └───────────────┬───────────────┘
            Zigbee (cabin coordinator)   │   Zigbee (home coordinator, SLZB-MR5U, D23)
   ┌───────────────┬──────────┬──────────┤──────────┬──────────────┬───────────────┐
   │               │          │          │          │              │               │
Box3 relays   Box4 relay  (existing   Home temp/   Home relay  Home smart    Midea Cube /
(2× MINI ONE  (ZBMINIR2)  13-device   humidity ×6  ×4 (TBD)    plug ×2       Cloudline S6
PM)                       mesh)                                             (WiFi / open,
                                                                              not Zigbee)
                                     │
                           WiFi / ESPHome (W-84, new adapter path)
                      ┌──────────────┴──────────────┐
                 Box2 sensor package          Box3 sensor package
             (SEN66 + SHT45 + QT Py)       (SEN66 + SHT45 + QT Py)
```

Every unit lands on the platform through an adapter that already exists in `cabin-orchestration-platform/backend/.../integrations/`, except the ESPHome/WiFi sensor path (W-84, new) and the Midea Cube (W-77, new HA-side integration, existing `ha_rest` adapter on this platform's side once paired) and the Cloudline S6 (open, W-78). No new coordinator (D8) and no new cabin-backend adapter class beyond the one W-84 scopes.

## 4 — Ontology placement summary

Every unit above gets, at provisioning: `Device.capabilities[]` (D7), `measurement_type`/`reports_to[]` for each Service Entity it exposes (D7/D13/D15/D16), `Device.related_to[]` where it applies (D28 — currently only the two HVAC units), D28's `display_label`/`entity_id` pair, and, for `RelayControlBox`'s composite case, the `part_of` relation tying its sensor and relay Devices to the shared enclosure grouping (D27). None of this is build work by itself — it is the checklist Code's design notes (W-84/W-77/W-78) and the provisioning pass (W-80) work from.

## 5 — Open items

- **Q-DN-1** (D28): Cloudline S6 control shape. **Answered by Nate, 2026-10-17: active speed control (Option B).**
- **Location/application TBDs**: 4× `ZigbeeMiniRelay`, 2× `ZigbeeSmartPlug`, `SmartDehumidifier`, `DuctFanSpeedController` — confirmed at physical install (W-80).
- **W-84 design note**: ESPHome-native vs. new MQTT contract for the WiFi sensor boxes — recommendation is ESPHome-native, not yet ratified by Cowork.
