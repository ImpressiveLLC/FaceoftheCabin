# W-101 runtime census, 2026-10-26

Owner Code, verifier Codex, semantic review Cowork. Evidence only: no secrets, no raw addresses, no telemetry values or payloads. Unresolved rows are allowed and each names what is missing.

## What was done

`census.csv` has one row for each of the 75 records in [measurement attention framework section 10](../../proposals/2026-10-22-measurement-attention-framework.md) (39 allocation, 13 concrete ontology Zigbee, 7 other ontology appliance/camera/network, 14 disabled code seeds, 2 historical recognitions). Each row was reconciled against a read-only export of the cabin database taken on 2026-10-26 with [`scripts/host/w101-census`](../../../../scripts/host/w101-census): identity columns, allowlisted config keys, reporting-relationship field names, observation counts with first and last times, and observed telemetry field names. Every query ran in a `READ ONLY` transaction capped at 60 seconds.

The raw export is private and is not committed (it holds hardware ids and names). Zigbee `0x...` ids are masked to their last six digits in these files. Codex can re-derive any row by running the script on the host and looking up the row's canonical id or its group in `runtime-roots.csv`.

`runtime-roots.csv` has one row for each of the 36 device groups found at runtime (a parent and the service rows that point to it), with row counts, enabled counts, lifecycle and first and last telemetry dates.

## Runtime totals

| Measure | Count |
|---|---|
| Device rows persisted | 184 (175 cabin, 9 home) |
| Lifecycle ASSIGNED / CANDIDATE | 96 / 88 |
| Device groups (a parent plus its services) | 36 |
| Groups whose parent id has no persisted device row (dangling) | 7, covering 48 service rows |
| Observation ids with no device row | 26 (some are system or test ids, such as `deploy-verify`, `events`, `security:cabin`, `presence:*`, `storage-storage-hdd`) |
| Device rows with no observation of any kind | 9 |
| Groups not matched to any of the 75 records | 11: six Home `netscan-*` parents, `home_aldrich_front`, a phone battery group, the Home Assistant backup group, the `sun` group and the Zigbee2MQTT bridge group |

## Coverage: the 75 records against today's inventory

| Disposition | Rows |
|---|---|
| Present at runtime and matched to a runtime device (`yes`, `yes_persisted`) | 19: the 13 Zigbee records, Kidde, Blink Outdoor 4, the attic sensor, the dehumidifier outlet, the Home laundry sensor, and the `home-cam-driveway` seed |
| Partly present: service rows exist, parent row missing (`partial`) | 3: Liebherr, the router, Starlink |
| Events exist but no device row (`events_only`) | 1: the Reolink camera |
| Ordered or documented but not commissioned: no device row and no observation (`not_commissioned`) | 39: 38 allocation records (planning_not_live) and the Bosch dishwasher. This is "not yet installed or integrated", not "missing" |
| Disabled code seed, not persisted (`seed_only`) | 13 |
| Total | 75 |

Status: 66 resolved, 9 unresolved. The nine unresolved rows are `allocation-home-th-laundry_room`, `ontology-kidde`, `ontology-liebherr`, `ontology-hw_camera_reolink_rlc820a_01`, `ontology-hw_camera_blink_outdoor4_01`, `ontology-hw_router_c4000lg`, `ontology-hw_starlink_terminal`, `seed-home-cam-driveway` and `recognized-dehumidifier-outlet`; each names what is missing.

## Findings the other lanes need

1. **Suspected duplicate parents: four physical devices appear to have more than one parent group.** None is established: no registry record links the groups, so each is a suspicion from names, ids and overlapping telemetry. Established facts are the group sizes and telemetry dates in `runtime-roots.csv`.
   - Mechanical-room temperature: `z2m-temp_mech_room` (1 row) and a Home Assistant group `ha-cabin-sensor-mech-room-temp-and-humidity-temperature` (7 rows). Both record telemetry, so the sensor is logged twice. Probably the same hardware: the Home Assistant entity was renamed, so its name prefix no longer matches the Zigbee name.
   - Bathroom leak alarm: `z2m-leak_alarm_bathroom` (1 row) and five service rows whose parent id `ha-cabin-switch-bathroom-leak-detect-alarm` has no persisted row.
   - Kidde: group A (`...living-room-kidde...`, 20 rows) and group B (`...kidde-air-quality-alarm...`, 1 parent plus a child). Two Home Assistant naming generations of one alarm.
   - Home television: `netscan-oled42c5pua` and `netscan-lg_webos_tv_oled42c5pua`, both Home scan parents for the same model.
2. **Dangling parents.** Seven parent ids referenced by 48 service rows have no persisted row, though observations exist under most of them: Starlink connectivity (21 rows), Liebherr top zone (8), bathroom leak alarm (5), `sun` (5), Zigbee2MQTT bridge state (3), Home Assistant backup state (3), router WAN status (3).
3. **Old id with its own history.** Observations exist under `z2m-Smart_Switch_Zigbee_|_Dehumidifier`, an earlier id of the dehumidifier switch whose current parent is `z2m-0x..84ffff`. The alias is not linked, so the earlier history is not under the canonical parent.
4. **Camera event aliases.** Events sit under `blink-driveway`, `blink-home_aldrich_front`, `outdoor_4` and `cabin_outside_reolink`, none of which is a device row.
5. **A persisted seed.** `home-cam-driveway` is persisted ASSIGNED and enabled with zero events of any kind, while the code seed says disabled until home-hub. Hardware presence at Home is not established.
6. **Typing.** `z2m-main_water_valve` and the dehumidifier switch parent are typed `HOME_ASSISTANT_ENTITY`; the legacy device `front_door` is typed `CAMERA` but is the front-door contact monitor, and hangs under the front contact group.

## Counts the issue asked for

| Item | Value |
|---|---|
| Original 75 reconciled to a runtime device | 19 matched, 3 partial, 1 events-only, 52 not present at runtime (39 `not_commissioned`, 13 `seed_only`) |
| Suspected duplicate parents | 4 physical devices (3 inside the 75: mechanical-room temperature, bathroom leak alarm, Kidde; 1 outside it: the Home television), all suspected, none established |
| Rows needing alias or history repair | 9 of the 75 (the `yes` rows in `census.csv`) |
| Dangling parent ids | 7 (48 service rows) |

## Column notes

- `enrollment` is one of `enabled`, `disabled`, `candidate`, `none` (19 / 13 / 0 / 43). For a group it is the parent's state; `none` means no parent lifecycle row exists (not commissioned, events only, or a missing parent). `enrollment_detail` keeps the free text: lifecycle state, enabled-row counts in the group, and any second parent.
- `measurements` lists every observed payload field name that is a reported quantity, state or device setting (for example `temperature`, `power`, `state`, `led_brightness`). `payload_metadata` lists the observed names that are not measurements: link quality, update state, and registry attributes carried in the payload (`ieeeAddress`, `capabilities`, `source` and similar). Neither cell is truncated: for each of the 13 Zigbee rows, `measurements` plus `payload_metadata` equals the full observed name list. Counts for the three rows Codex named: heater 39 observed = 15 measurements + 24 metadata; breaker-box switch 36 = 15 + 21; mechanical-room temperature 29 = 7 + 22.
- `runtime_present`: `yes`, `yes_persisted`, `partial`, `events_only`, `seed_only`, `not_commissioned`.

## Limits

- `supported_vs_declared` does not compare observed fields with declared fields: the declared fields in the proposal are prose, so a field-by-field comparison is not made. Each row states the observed count.
- Telemetry date ranges come from observation first and last times, not from reading values.
- The Home laundry match rests on a room name and model; the order record that would confirm it is not in this export.
- The export reads the database. A device held only in memory by the running registry, such as an unpersisted seed, appears as `seed_only` from the code, not from the database.
