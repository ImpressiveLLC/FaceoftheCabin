# SEN66 readings: what they can honestly claim

Status: proposed, docs only. Written by Cowork on 21 Nov 2026 while Codex was out; Codex confirms or overrules on return. It authorizes no code, schema change, device assignment, host read or deploy. It sits beside the [Doc 27 build plan](doc27-environmental-nodes.md) and the [environmental nodes guide](../ai-assistant/user-guide/environmental-nodes.md), and feeds the standards and derived-concept work in W-168 and the catalog work in W-160.

Source: the Sensirion SEN66 datasheet, version 0.92 (preliminary, December 2025), read by Cowork on 21 Nov 2026. The datasheet is preliminary, so every figure below should be rechecked against the released version before it is pinned in the ontology. Anything not stated by that datasheet is marked as such.

## What each reading is

| Reading | What it is | What it is not |
|---|---|---|
| PM1, PM2.5, PM4, PM10 | Mass concentrations in micrograms per cubic metre; datasheet range 0 to 1000 | An air quality index. An index is a separate derived concept that needs a named pollutant, window and breakpoint revision |
| CO2 | A concentration in ppm. Accuracy depends on automatic self-calibration (ASC), which the datasheet ties to regular exposure to fresh air (weekly) | A fresh-air-free reading that stays calibrated indefinitely |
| VOC index | A dimensionless index from 1 to 500, relative to the sensor's own recent history; the default offset is 100, described as typical average conditions | A concentration, ppm, ppb or an AQI |
| NOx index | A dimensionless index from 1 to 500; default offset 1 | A concentration, ppm, ppb or an AQI |
| Temperature and humidity | Ambient readings from the module | A moisture-damage or mold assessment |

## What the SEN66 does not measure

It has no carbon monoxide, formaldehyde or isocyanate channel. The datasheet names formaldehyde as a feature of other models in the family, not this one. So a SEN66 on its own cannot say anything about CO, formaldehyde or isocyanates, and the platform must not display or imply a reading for them. A CO question needs a CO sensor, and CO alarms stay in the existing safety path (alarm state, not a concentration chart, until the ontology decides otherwise).

## Baselines: two different things

The question "what is normal" has two answers, and they come from different places.

1. **Spec-derived reference.** The vendor's stated behaviour and ranges (the figures above). The device-add discovery lookup can supply a cited datasheet link and snippet at best, with confidence capped at medium and no structured units or ranges today (Code's read of the lookup, #244 comment 7074821395). It proposes; a person reads the datasheet and curates the row. It never activates anything and is not a site baseline.
2. **Site-derived baseline.** What this room normally reads, observed over time at the cabin. It cannot come from the web. A baseline taken after a disturbance, such as spray foam insulation, is contaminated by that disturbance and is not a before-state.

## Why a VOC or NOx spike needs care

The datasheet describes the VOC and NOx indexes as self-normalizing. The estimator learns over a window of about 12 hours for VOC, forgets events after roughly twice that, and may freeze its learning for up to 180 minutes (VOC) or 720 minutes (NOx) during gating. The index specification is met only after about one hour (VOC) or six hours (NOx) of running. The gas specification is stated for zero air with an ethanol test gas.

Practical consequences, stated as inferences from those figures and not as vendor claims:

- A sudden release can raise the index, but a sustained, steady off-gassing source may be learned as normal over hours and read back near the default, so a quiet index does not prove clean air.
- A freshly powered or reset sensor is not yet meaningful for hours.
- Because the index is relative, it should not be compared across two rooms or two sensors as if it were an absolute level.
- For absolute comparison, use the PM concentrations and CO2, and keep the raw index and its timestamps so a change can be reviewed later.

## What to record for each reading

Each stored reading keeps its unit and basis, its source device identity (stable ID, not a display name), its observation time and age, and whether the sensor had completed its warm-up and learning period. A displayed value that is an index says so in its label.

## Open points for Nate

- Which standards, if any, attach to PM, CO2 and humidity bands at the cabin. No OSHA, ASHRAE or similar source exists in the repository today, so none is asserted here; W-104 is the item that curates primary sources.
- Whether the cabin keeps a deliberate pre-existing baseline period for new sensors before any attention rule uses an index.

## Not claimed

No sensor in this note has been read on a live system. Nothing here says the foam work did or did not change the air. No threshold, alert rule or tile has been approved.
