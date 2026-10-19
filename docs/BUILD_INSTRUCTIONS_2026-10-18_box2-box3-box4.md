# Build instructions — Box 2, Box 3, Box 4 (doc-27 sensor/relay nodes)

**Date:** 2026-10-18. **Trigger:** all DigiKey/Adafruit/Amazon/Home Depot parts for the three proceeding boxes have physically arrived (per Nate, 2026-10-18). **Builds on:** [`HARDWARE_INVENTORY_2026-10-16_sensor-relay-nodes-and-hvac.md`](HARDWARE_INVENTORY_2026-10-16_sensor-relay-nodes-and-hvac.md) (master BOM, wiring reference, D28 naming) and [D28](governance/decisions/ontology-decisions.md). **Status:** step-by-step assembly walkthrough — nothing in this doc existed before today; the inventory doc had a parts list and a block-diagram wiring *reference*, not a build sequence.

**Scope.** Physical assembly of the three doc-27 enclosures only: Box 2 (`SensorCollectionBox`), Box 3 (`RelayControlBox`), Box 4 (`UtilityRelayNode`). **Out of scope:** the Midea Cube (W-77) and Cloudline S6 (W-78) — neither is a doc-27 enclosure build, the S6's actuator isn't even on order yet, and Nate's "yes" that prompted this doc was in response to a question scoped to "the 3 proceeding boxes." Box 1 stays deferred per the inventory doc.

**What is and isn't ratified here.** The naming (D28), the BOM and the wiring *topology* (which wire goes where) are ratified and followed exactly. The QT Py's **firmware/software path is not** — W-84 (ESPHome-native vs. a new MQTT contract) is "not yet scored — Cowork to score," recommendation only. Section 4 below is written as *the recommended path, pending ratification* — flash it if you want the boxes talking to Home Assistant now, but know Cowork could still land on the MQTT-contract alternative instead, in which case this section's firmware gets replaced, not the wiring above it.

---

## 0 — Before you start

**Tools:** Phillips/flathead screwdrivers, a drill with step bit (sized to the IEC C14 inlet's cutout and the acrylic rod's diameter), a hacksaw or rotary tool (light-pipe rod length, enclosure cable glands if not pre-punched), wire strippers, a multimeter, a soldering iron *only if* the Mean Well module's input/output leads need shortening (they usually don't — screw terminals), heat-shrink or wire nuts, a USB-C cable (QT Py flashing/power-up).

**Common failure mode to avoid:** mains wiring (IEC C14 → Mean Well IRM-05-5 input, and the two relay circuits in Box 3) is line-voltage. Work with the circuit unpowered and verify with a multimeter before first power-up, every time — this isn't covered per-step below to avoid repeating it, but it applies to every mains connection in this doc.

**Per-unit BOM** is in the inventory doc §2.1–2.3; it isn't repeated here except where a step needs a specific part called out.

---

## 1 — Box 2 (`SensorCollectionBox`, cabin upstairs claim zone, sensor-only)

Simplest of the three: one sensor package, no relays.

1. **Dry-fit before drilling.** Lay the QT Py ESP32-S3, SHT45, SEN6x breakout (with SEN66 clipped in), and Mean Well IRM-05-5 inside the Otdorpatio enclosure before cutting anything. Confirm the IRM-05-5's screw terminals face a wall you can reach with the lid on, and pick a spot for the light pipe that's visible from outside without lining up with a seam or gasket.
2. **Drill the IEC C14 inlet cutout** in the wall you picked, sized to the inlet's own cutout template (check the part's datasheet — these are not all the same cutout size). Mount the C14 inlet; it's a snap-in or screw-flange part, not wired yet.
3. **Drill the light-pipe hole**, sized to the acrylic rod's diameter, directly above/in front of where the QT Py's onboard status LED will sit once mounted. Cut the acrylic rod to length (enclosure wall thickness + enough to sit flush with the QT Py's LED on the inside, flush or slightly recessed on the outside) with a hacksaw, then sand the cut end lightly — a rough-cut acrylic end scatters less light than a polished one, counterintuitively, so don't over-polish it.
4. **Mount the Mean Well IRM-05-5** to the enclosure's standoffs or a mounting plate. Wire IEC C14 → IRM-05-5 AC input (L/N, plus ground to the inlet's ground pin and the enclosure's ground point if it has one — the IRM-05-5 is a Class II module in most configs; check its datasheet for whether it expects a ground at all before assuming one).
5. **Wire IRM-05-5 5V DC output → QT Py's 5V/VIN pin** (not the USB-C port — that's for flashing/debug only, see §4). Confirm polarity before connecting; reversed polarity on a buck/linear 5V rail is a common way to kill a QT Py on first power-up.
6. **I²C (Qwiic) chain, in this order: QT Py → SHT45 → SEN6x breakout.** Qwiic connectors are keyed and can only go in one way, so the main risk isn't wrong polarity, it's wrong *order* mattering for addressing:
   - SHT45 and SEN66 (via the SEN6x breakout) use fixed, different default I²C addresses out of the box — they do not collide, so chain order doesn't matter electrically. Chain them in whichever physical order the enclosure layout makes easiest to route; QT Py → SHT45 → SEN6x breakout is just the order that keeps the shortest cable runs for most Otdorpatio layouts.
   - Use all 3 Qwiic cables as supplied (don't substitute a longer cable here without checking Qwiic's bus length guidance — I²C over long unshielded runs gets unreliable past a foot or two at these pull-up strengths).
   - **Do not daisy-chain through the SEN6x breakout's pass-through port if it doesn't have one** — check the breakout's actual silkscreen; some SEN6x breakouts are drop-through (STEMMA QT in one side, out the other) and some are a dead end. If it's a dead end, the SHT45 and SEN6x breakout both need their own Qwiic run back toward the QT Py (a Qwiic splitter/multiport, not included in this BOM — flag to Nate if the breakout turns out to be a dead-end part; don't silently order one).
7. **Mount the acrylic rod** in its drilled hole so its inner end sits against (or very close to) the QT Py's status LED, and the outer end sits flush with the enclosure wall. A dab of clear silicone around the rod (not glue that yellows or clouds) holds it in place and seals the hole against weather.
8. **Close the enclosure, don't power up yet.** Go to §4 (firmware) before first mains power-up, so the QT Py has known-good firmware before it's sealed and mounted upstairs.
9. **Mount Box 2** in the cabin upstairs claim zone once firmware (§4) is confirmed working on the bench. Location/application naming is already fixed by D28 (`SensorCollectionBox_Cabin_Upstairs`) — no TBD here, unlike the home units.

## 2 — Box 3 (`RelayControlBox`, cabin bathroom, sensor + 2× Zigbee relay + manual toggle)

Box 3 has two independent subsystems sharing one enclosure: the same sensor package as Box 2 (steps 1–7 above apply unchanged — build that half identically), plus a relay half. Do the sensor half first using §1 steps 1–7, then continue here.

1. **Mount the two Sonoff MINI ONE PM modules** inside the enclosure, physically separated from the sensor half's low-voltage wiring where possible — these are mains-switching relays with their own line/load/neutral terminals, and keeping relay wiring and QT Py/Qwiic wiring in separate enclosure zones reduces both EMI risk to the I²C bus and the chance of a mis-wire during assembly.
2. **Mount the manual toggle switch(es)** somewhere accessible from outside the enclosure (or through a sealed actuator boot if the enclosure needs to stay IP65-rated at that penetration) — the whole point of this switch is a fail-safe that works with Z2M/HA down, so it can't be buried inside a sealed box with no external access.
3. **Wire per the inventory doc's §2.2 diagram, built out in full:**
   ```
   Mains ──┬──▶ [Manual toggle #1] ──▶ Exhaust fan
           └──▶ Sonoff MINI ONE PM #1 (Zigbee) ──▶ Exhaust fan (auto path)
   Mains ──┬──▶ [Manual toggle #2] ──▶ Heat fan
           └──▶ Sonoff MINI ONE PM #2 (Zigbee) ──▶ Heat fan (auto path)
   ```
   Each fan load has **two parallel paths to it** — manual toggle and Zigbee relay — not a toggle that gates the relay. That's deliberate: if the toggle gated the relay (toggle off = relay path also dead), it wouldn't be independent of Z2M/HA, it would just be a second single point of failure in series. Wire each toggle and its corresponding relay as two separate parallel branches from mains to the same fan load, each able to drive that fan on its own.
   - **Confirm with Nate before closing the enclosure**: most mechanical relays and rocker/toggle switches wired this way need the *inactive* path to present a high-impedance open, not a path that could backfeed the other control's output (e.g., toggle "on" feeding voltage back into the relay's load terminal while the relay is also live). Sonoff MINI ONE PM units are designed to sit in exactly this kind of parallel-with-manual-switch arrangement (it's a documented Sonoff use case), so this is a know-your-specific-switch check, not a sign the topology is wrong — but verify against the Sonoff MINI ONE PM's own wiring diagram for a manual override before energizing, since getting this wrong risks backfeeding the relay.
4. **Pair both Sonoff MINI ONE PM units to the cabin Zigbee coordinator** (SONOFF ZBDongle-E, already live) via Z2M's permit-join — same pairing flow as any other Zigbee device on this platform, no new adapter work (confirmed in the inventory doc §3). Do this on the bench, before final mounting, so a failed pairing doesn't mean re-opening a mounted enclosure.
5. **Build and flash the sensor half** per §4 below, same as Box 2.
6. **Close and mount Box 3** in the cabin bathroom. Naming is fixed by D28 (`RelayControlBox_Cabin_BathroomExhaustHeat`) — no TBD.
7. **Ontology note for later, not a build step:** Box 3's sensor Device and its two relay Devices are modeled as separate Devices `part_of` one enclosure grouping (D27), not one cross-protocol Device — that's a provisioning-time (W-80) and `related_to[]`-schema-time (W-79) concern, not something this build changes. Don't try to make one Z2M/HA entity represent the whole box.

## 3 — Box 4 (`UtilityRelayNode`, cabin mech room / breaker box, relay-only)

Simplest build of the three — no sensor package, no Mean Well module, no firmware decision.

1. **Mount the Sonoff ZBMINIR2** inside the vented plug-and-play enclosure per its own included instructions — this is a stock Sonoff relay module in a stock enclosure, nothing custom to fabricate.
2. **Wire it to the mech-room/breaker-box load** it's controlling. Confirm with Nate which specific circuit/load this is before wiring — the inventory doc names the unit's *location* (mech room / breaker box) but the specific load it switches isn't detailed in the BOM; don't guess at the application.
3. **Pair to the cabin Zigbee coordinator** via Z2M permit-join, same as any other Zigbee relay already on this platform.
4. **Mount Box 4** in the mech room. Naming is fixed by D28 (`UtilityRelayNode_Cabin_MechRoomBreaker`) — no TBD.

No bench firmware step — this unit has no QT Py, no I²C, nothing in §4 applies to it.

## 4 — QT Py firmware (Box 2 and Box 3's sensor halves only) — recommended path, **not yet ratified**

This section is the bridge from "wired correctly" to "reporting to Home Assistant." It exists so the boxes aren't sealed up non-functional, but treat every step past "flash something and confirm sensors read" as provisional:

1. **Flash ESPHome to each QT Py** over USB-C (bench step, before final mains wiring is relied on — USB-C power is enough to flash and bench-test the sensor chain without the Mean Well module live). Use ESPHome's own QT Py ESP32-S3 board profile and add the SHT45 and SEN66/SEN6x components from ESPHome's sensor component library (both have existing ESPHome components — this isn't custom driver work).
2. **Confirm each I²C sensor reads correctly on the bench** (ESPHome's logs or a quick Home Assistant dev-tools check) before final assembly — a wiring mistake caught here costs nothing; caught after the enclosure is mounted upstairs, it costs a trip back up there.
3. **Why ESPHome-native, and why this isn't settled:** the inventory doc and W-84 recommend ESPHome-native discovery (the box shows up as an ordinary `HOME_ASSISTANT_ENTITY` through the existing `ha_rest` adapter — no new topic schema, no new cabin-backend adapter class) over inventing a parallel MQTT contract under `cabin/device/{deviceId}/...`. That recommendation is sound engineering but **Cowork has not scored or ratified W-84** — it's "proposed," not accepted. If Cowork later picks the MQTT-contract alternative instead, the fix is a firmware re-flash on these two QT Pys, not a rewiring — the hardware and wiring in §1–§3 don't change either way.
4. **Do not claim these boxes are "live" or "reporting" in any doc, UI, or Ask answer** until they're actually paired/discovered in Home Assistant and confirmed reading real values — this mirrors the same caution already applied to the Midea Cube and Cloudline S6 in the ai-assistant docs (`ask-and-helpdesk.md`'s D28 section): ordered/built is not the same as live.

## 5 — After physical install (not part of this build, flagged so it isn't skipped)

- **W-80**: once each box is physically mounted, its `entity_id`/`display_label` pair gets provisioned exactly per D28's worked-examples table — this doc's naming above already matches that table for Boxes 2–4 (none of their three locations/applications are TBD), so W-80 for these three is pairing + ontology entry, not a location decision. The home-location units (relays/plugs/sensors) still have real TBDs; this build doc doesn't resolve those.
- **D1 reminder**: don't mint any `entity_id` before the physical unit is actually installed in its final location — it can never be renamed once minted.
- **W-81**: if anything in this build doc turns out wrong once hands are actually on the hardware (a part that doesn't fit as expected, a wiring detail that needs correcting), update the inventory doc and/or this doc rather than letting either go stale.
