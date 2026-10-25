# Handover HO-2026-10-25: Cowork to Code, correct `BUILD_INSTRUCTIONS_2026-10-18_box2-box3-box4.md`

Reviewed against `docs/hardware/doc27-schematics.md` on `origin/main`, 2026-10-25. The schematics are authoritative; the build doc must follow them.

## Corrections needed

| Line (approx.) | Build doc says | Doc 27 schematics say | Fix |
|---|---|---|---|
| 15, 27 | IRM-05-5 has "screw terminals" | Terminal/pin type not established; mains terminal schedule not approved | Say "verify delivered part's terminal type"; remove the claim |
| 31 | Wire IRM 5V output to QT Py 5V/VIN | One reviewed external-power entry only: 5V pad with documented series diode, or BAT/GND pads; never USB plus an improvised supply; diode/connector not in BOM | Replace with the S1 power-entry paragraph; bench on USB first |
| 45 | Toggle is a fail-safe that works with Z2M/HA down | A local switch input is not a bypass or fail-safe disconnect | Reword as a manufacturer-approved relay switch input |
| 53-54 | Toggle and relay as two parallel branches to the load | S3: toggle is a switch input to the relay (dotted edge); never wire mains/Sonoff switch terminals to ESP GPIO | Replace topology with S3; installer approves final drawing |
| Box 2/3 power | SEN66 implied on a 5 V rail | SEN66 supply 3.15-3.6 V, fed from QT 3.3 V via the SEN6x adapter | State 3.3 V; no raw 5 V into the sensor |
| Box 4 | (peer-corrected 2026-10-21) | Full sensor + 2 relays, USB powered, relay B reserved | Recheck for consistency |

## Done in parallel (no Code action)

- Illustrated guides published as private artifacts (Box build guide, S6 design and speed test).
- PR #257: Guest Access card moves revoked/expired links behind a History disclosure.

## Open for Code

- Confirm the corrected text lands in the build doc and keep the figures (IDs P1-P14) out of the repo unless wanted.
- W-78: speed-test numbers from Nate's multimeter will arrive in chat; Cowork will hand them over.
