# PKG-2026-10-19-01: D28 hardware inventory delta (for future review)

| Field | Value |
|---|---|
| Status | **Held for review.** Backlog item [W-95](../backlog.md). Nothing here is ratified, canonical or a build instruction |
| Created | 2026-10-19, Cowork, at Nate's direction: "extra facts in extra inventory should be part of wsjf backlog in a package for future review" |
| Source | `docs/HARDWARE_INVENTORY_2026-10-16_sensor-relay-nodes-and-hvac.md`, added by #167 and retired by D28 Amendment A1 (this PR) |
| Canonical homes | Unit names and allocation: [D28](../decisions/ontology-decisions.md) (A1). Build scope, parts, integration, commissioning: [`docs/hardware/doc27-environmental-nodes.md`](../../hardware/doc27-environmental-nodes.md). Unit records: [`doc27-device-inventory.json`](../../hardware/doc27-device-inventory.json). Wiring boundaries: [`doc27-schematics.md`](../../hardware/doc27-schematics.md) |

## How to review

For each item in "Carried facts" below: confirm it against the hardware on hand and the canonical homes, then either move it into its canonical home in a normal PR, or drop it with a one-line reason here. The package closes (and this file is deleted) when every item is dispositioned.

## Facts in the retired doc that were wrong, already corrected by A1

Do not carry these forward. Each is ruled in [D28 A1](../decisions/ontology-decisions.md) and [DL-2026-10-19-01](../discrepancy-log.md).

| Retired doc said | Correct (A1) |
|---|---|
| Box 4 is a relay-only `UtilityRelayNode` with the owned ZBMINIR2, no sensor package | Box 4 is a `RelayControlBox`: sensor package (third SEN66, SHT45, QT Py) plus two MINI-ZB1GSP, USB plug-in power |
| Box 3 has 2× "MINI ONE PM" **and** 4× MINI-ZB1GSP go to Home | MINI ONE PM is the MINI-ZB1GSP; 4 bought, all Cabin (Box 3 exhaust and heat; Box 4 circulation and reserved) |
| 2× S61SZBTPB plugs at Home, TBD | Cabin outlets |
| 6th SNZB-02P is a Home spare | Cabin lower-level duct reference |
| Midea Cube in the Home basement (TBD) | Cabin, room set by drain route |
| Cloudline S6 at Cabin or Home; passive **or** active control | Cabin inter-level loop; power monitoring **and** speed control (two paths) |
| Grand total $660.96 | Doc 27's corrected receipt total ($709.07) is canonical |
| SLZB-MR5U "already live and relaying home camera data" | Home's Zigbee collector was offline 2026-09-19 to 2026-10-16 (W-82); the MR5U carries no camera |
| `Device.related_to[]` as its own property | Nate's term, stored as D27 `relates_to` (A1) |

## Carried facts (unreviewed, copied verbatim from the retired doc)

| # | Item | Proposed home if confirmed |
|---|---|---|
| C1 | Sensor-box block diagram and part roles (2.1) | `doc27-schematics.md` |
| C2 | Box 3 relay-half wiring sketch: manual toggle and relay as parallel paths to each fan (2.2). **Safety:** confirm against Sonoff's MINI-ZB1GSP manual-override wiring before energizing; this sketch is not an approved mains drawing | `doc27-schematics.md` |
| C3 | Amazon ASINs for the Home and relay parts (2.4) | `doc27-device-inventory.json` (purchase provenance), if wanted at all |
| C4 | Two-coordinator integration sketch (3). Note it predates A1's allocation | `doc27-environmental-nodes.md` architecture section, redrawn to A1 |
| C5 | Per-unit ontology placement checklist (4) | W-80 provisioning checklist |

### C1 and C2 (sections 2.1 and 2.2 of the retired doc)

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

### C3 (section 2.4 of the retired doc; allocation column superseded by A1)

### 2.4 Home Zigbee additions

| Part | Qty | SKU / ASIN |
|---|---|---|
| SONOFF SNZB-02P temp/humidity (2-pack) | 3 packs = 6 units | B0CLR7V3W7 |
| SONOFF MINI-ZB1GSP relay | 4 | B0H464X7RR |
| SONOFF S61SZBTPB smart plug | 2 | B0HDXX279N |

All pair to the home-location coordinator (SMLIGHT SLZB-MR5U, already live and relaying home camera data to the M920q — D23, no change to that device's identity).

### C4 (section 3 of the retired doc)

#### 3 — Integration and application architecture

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

### C5 (section 4 of the retired doc)

#### 4 — Ontology placement summary

Every unit above gets, at provisioning: `Device.capabilities[]` (D7), `measurement_type`/`reports_to[]` for each Service Entity it exposes (D7/D13/D15/D16), `Device.related_to[]` where it applies (D28 — currently only the two HVAC units), D28's `display_label`/`entity_id` pair, and, for `RelayControlBox`'s composite case, the `part_of` relation tying its sensor and relay Devices to the shared enclosure grouping (D27). None of this is build work by itself — it is the checklist Code's design notes (W-84/W-77/W-78) and the provisioning pass (W-80) work from.
