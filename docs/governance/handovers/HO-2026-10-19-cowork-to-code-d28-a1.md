# Handover HO-2026-10-19: Cowork to Code, D28 Amendment A1

Replaces `HO-2026-10-16-cowork-to-code-d28-hardware.md` (deleted in this PR; its content now lives in D28). Reviewed against `origin/main` @ `43bc9de`.

## What changed and why

Nate, 2026-10-19: keep #167's scope, resolve every conflict now, newest dated source wins, the CLOUDLINE S6 has power monitoring **and** speed control, and the retired inventory doc's extra facts go into a WSJF package for later review. Rulings are in [D28 A1](../decisions/ontology-decisions.md) and [DL-2026-10-19-01](../discrepancy-log.md):

- **Allocation:** Box 4 is a `RelayControlBox` (third sensor package plus two MINI-ZB1GSP, USB plug-in power). All four MINI-ZB1GSP and both plugs are Cabin. The 6th SNZB-02P is the Cabin lower-level duct reference. Midea and S6 are Cabin.
- **Naming:** one rule, hub always present, segments never merged. D28's allocation table is the only place unit names live.
- **"Related to":** Nate's term, stored as D27 `relates_to` edges to seven domain subjects; no separate property.
- **S6:** relay metering and on/off now; speed via W-78.
- **Retired:** `docs/HARDWARE_INVENTORY_2026-10-16_...`; extra facts in [PKG-2026-10-19-01](../packages/PKG-2026-10-19-01-d28-inventory-delta.md) (W-95).
- **Scored:** W-77 11.0, W-78 7.3, W-79 7.0, W-80 9.3, W-84 14.0, W-95 6.5. W-81 closed.

## Code tasks, in order

| # | Task | Notes |
|---|---|---|
| 1 | **`docs/BUILD_INSTRUCTIONS_2026-10-18_box2-box3-box4.md` (merged from #193 before A1): correct it.** Box 4 becomes `RelayControlBox_Cabin_MechRoom_ClimateControl` with the sensor package and relays A (circulation, feeds the S6) and B (reserved), powered from a USB plug-in supply; Box 3's label becomes `RelayControlBox_Cabin_Bathroom_ExhaustHeat` and its relays `ZigbeeMiniRelay_Cabin_Bathroom_Exhaust`/`_Heat`; remove `UtilityRelayNode` and the owned ZBMINIR2 from the build; replace links to the retired inventory doc with Doc 27 and D28; extend the firmware section to box 4 | Nate has the parts. Tell him Box 4 takes a sensor package before he closes it up |
| 2 | **W-84** design note, then firmware for boxes 2 to 4 | Highest score in this set; parts on hand |
| 3 | **W-80 alignment slice** now: `docs/ontology.yaml` candidate type `sensor_relay_box` becomes `relay_control_box`; `doc27-device-inventory.json` technical aliases and allocations to D28 A1; check `docs/ai-assistant/taxonomy-eval/elements.json`, `CLAUDE.md` and `docs/MAINTENANCE.md` for the old key; run the validators and tests that read them. Pairing records follow at install | No `entity_id` minted before install (D1) |
| 4 | **W-78** step 1 needs Nate's photo and meter readings of the S6 connector; then the design note names parts | Isolation is mandatory |
| 5 | **W-77** after the Midea is placed; **W-79** when W-68's relation store or the YAML interim is ready | |

Cowork owns W-95 (package review).

## Closure

This file closes when the build-instructions correction merges and W-80's alignment slice merges. The PR that merges last deletes it.
