# SEN66 charting: the minimal reviewed change (design note)

Status: proposed, docs only. Code, 23 Nov 2026, per Cowork's ACTION CODE on #244 (due 12:00 CST Mon 23 Nov). This note authorizes no code, schema or ontology change, device assignment, host read or deploy. It does not change Decision N3: Box 3's platform metrics stay unrecorded until catalog S7 and the W-84 gate. This note defines the vocabulary those slices will need, so S7 does not invent it under time pressure.

Inputs read (repository only, main `0edd6e9f`):
- the [SEN66 claims note](../../hardware/doc27-sen66-reading-claims.md), which is the honesty rule for everything below;
- `docs/hardware/esphome/box3.yaml`;
- `DeviceType.java`, `D7MeasurementTypes.java` and `HomeAssistantDiscoveryService.java` (`inferType`, `semanticFieldFor`);
- the device-reporting-relationship schema;
- the S2 catalog and seed;
- the candidate ontology rows `pm1_mass_concentration` through `nitrogen_oxide_index`;
- `App.jsx` field options.

No live entity, Home Assistant registry or ESPHome build output was read.

## 1. What happens today if a SEN66 entity were discovered

Box 3 exposes nine SEN66 channels through ESPHome `sen6x` (`box3.yaml` lines 156 to 187): PM1.0, PM2.5, PM4.0, PM10, CO2, VOC index, NOx index, temperature and humidity. Each reaches Home Assistant as a separate `sensor` entity. Today discovery would treat them like this:

| Channel | `inferType` result (HomeAssistantDiscoveryService) | Stored field (`semanticFieldFor`) | Problem |
|---|---|---|---|
| CO2 | `CO2_SENSOR` (`carbon_dioxide`, line 463) | `co2` | none: correct quantity and unit |
| temperature, humidity | `TEMPERATURE_SENSOR`, `HUMIDITY_SENSOR` | `temperature`, `humidity` | none, but they are SEN66 module readings, not room truth; Box 3 also has the SHT45 |
| VOC index, NOx index | `AIR_QUALITY_SENSOR` **if** the entity carries `device_class: aqi` (line 464) | `airQualityIndex` | **overclaim**: a sensor-relative index from 1 to 500 is stored and shown as "Air Quality Index", which the claims note forbids |
| PM1, PM2.5, PM4, PM10 | no rule matches the PM classes; unit `µg/m³` is not one of the unit fallbacks | none: the entity becomes `HOME_ASSISTANT_ENTITY`, with no tile and no chart | the concentrations that support absolute comparison are dropped |

Which `device_class` each ESPHome `sen6x` channel publishes is **not verified here**. It comes from the ESPHome component at the pinned firmware version, and none is set in `box3.yaml`. It must be confirmed before slice C2 (section 5) by reading that component's source at the pinned version, or by a read-only registry read with Nate's go. The VOC and NOx row above is conditional for that reason. Whatever the classes turn out to be, the design below does not depend on them for the index channels.

## 2. Fields, not a new type

**Recommendation: add six semantic fields and no new `DeviceType`.**

- A device type answers "what kind of device is this" (category, check-in, safety handling, match rules). A field answers "what quantity is this reading".
- The SEN66 is one physical node with many channels. The ontology already models it that way: each channel is an `environmental_measurement_service` belonging to a sensor parent.
- A `SEN66_SENSOR` type would tie the vocabulary to one vendor part. It would also duplicate `AIR_QUALITY_SENSOR` and `CO2_SENSOR`, and add another catalog row with nothing new to say about check-in or safety.

| Field | `measurement_type` (D13) | Unit and basis | Ontology row (exists, candidate) | Label rule |
|---|---|---|---|---|
| `pm1` | `pm1` | µg/m³, mass concentration, raw | `pm1_mass_concentration` | "PM1" |
| `pm25` | `pm25` | µg/m³, mass concentration, raw | `pm2_5_mass_concentration` | "PM2.5" |
| `pm4` | `pm4` | µg/m³, mass concentration, raw | `pm4_mass_concentration` | "PM4" |
| `pm10` | `pm10` | µg/m³, mass concentration, raw | `pm10_mass_concentration` | "PM10" |
| `vocIndex` | `voc_index` | dimensionless, 1 to 500, relative to the sensor's own history | `volatile_organic_compound_index` | must contain "index"; never "AQI", "air quality" or a concentration unit |
| `noxIndex` | `nox_index` | dimensionless, 1 to 500, relative to the sensor's own history | `nitrogen_oxide_index` | must contain "index"; never "AQI", "air quality" or a concentration unit |

CO2 reuses `co2` (ppm). Temperature and humidity reuse their existing fields. `airQualityIndex` stays as it is for Kidde's vendor AQI and is never written from a SEN66 channel. The field names follow the existing camelCase rule, which `D7MeasurementTypes.toMeasurementType` already converts: `vocIndex` becomes `voc_index`, and `pm25` stays `pm25`.

**Type placement:**
- The four PM fields and the two index fields go on `AIR_QUALITY_SENSOR`. Its `telemetryFields()` widens from `{airQualityIndex}` to `{airQualityIndex, pm1, pm25, pm4, pm10, vocIndex, noxIndex}`.
- `CO2_SENSOR` is unchanged.

Widening is safe for existing devices: `reportsFields` only lists what a device may report, and the UI's field chips come from `reportsFields` (S1). A Kidde AQI entity would show the new chips as options with no data. That is acceptable, but the S1 Vitest case and the workflow picker must be checked for it in C3.

## 3. Mapping keyed on what the entity is, not on the type

`semanticFieldFor(DeviceType)` returns one field per type. That cannot work once `AIR_QUALITY_SENSOR` carries seven fields.

**Proposal:** `semanticFieldFor(type, deviceClass, unit)`. It takes the field from the entity's own `device_class`, checked against the unit, and falls back to today's per-type answer.

- The PM classes map to `pm1`, `pm25`, `pm4` and `pm10` only when the unit is `µg/m³`. A PM-class entity in another unit is not stored and is counted.
- `carbon_dioxide` maps to `co2` only with `ppm`. It never maps to `co`, which the ontology row already forbids.
- **The index fields are never inferred from `device_class`.** `aqi` cannot tell a vendor AQI from a SEN66 VOC or NOx index, and HA has no index class. `vocIndex` and `noxIndex` are written only through an explicit channel map. That map is the catalog-native enrollment in S7, matching the "explicit source and channel mapping in discovery" gate the ontology already sets for W-84. Until then, an `aqi` entity whose source is an ESPHome `sen6x` node keeps today's behavior. Better, it should not be stored at all: C2 decides which, with Codex.
- Matching by entity name or friendly name is not used. That would repeat the vendor workaround pattern (`ha-15-name-co-level`) that the catalog is meant to retire.

The `inferType` rules gain a PM rule, `ha-NN-pm-mass` (`device_class` in the PM set, so `AIR_QUALITY_SENSOR`). It becomes a new catalog match rule with a seed entry, so the S2 check "every `inferType` branch maps to a match rule" stays true.

## 4. Keeping the parity tests meaningful

One change runs through every list that the existing tests hold equal:

1. The `measurement_type` enum in `device-reporting-relationship.schema.json` gains `pm1, pm25, pm4, pm10, voc_index, nox_index`, and `D7MeasurementTypes` gets the same six. `D7MeasurementTypesTest` fails if only one side changes.
2. `DeviceType.AIR_QUALITY_SENSOR.telemetryFields()` widens. `DeviceTypeOntologyParityTest` (line 75) fails unless every field is in the D7 vocabulary, which is why item 1 comes first.
3. The catalog is regenerated. Its `AIR_QUALITY_SENSOR` entry's `reported_fields` comes from `DeviceType.java`. `DeviceCatalogParityTest` check 5 (line 162) holds the two equal. The catalog hash changes. `AIR_QUALITY_SENSOR` is not a protected safety type, so the S2 guard needs no `allowed_changes` entry.
4. The six candidate ontology rows each gain a link to their field, using the same mechanism `device_reports_fields` uses. They stay `candidate` until W-104 curates a source for any profile. Linking a row makes it reachable; it does not promote it.

A PR that changes any one of these without the others fails CI. That is the point of keeping S1 and S2 meaningful.

## 5. How it folds into W-168, W-160 and W-169

- **W-168 (measurement standards and derived concepts):** the six fields are measurement rows, the "observation" side of the row and column model.
  - No threshold profile is attached here. PM bands, any CO2 comfort range and any index attention rule are versioned threshold profiles under W-168, each with a W-104 primary source.
  - An AQI is a derived-concept row (named pollutant, averaging window, breakpoint revision). It is never computed from these fields in this change.
  - The VOC and NOx learning and gating behavior in the claims note becomes row metadata ("relative to sensor history; not comparable across sensors"), not a profile.
- **W-160 (catalog S2):** the field list lives in `DeviceType.java` and flows into the catalog through the generator. The new PM match rule lives in the seed. No parallel list is created.
- **W-169 (capability coverage):** once C1 lands, the coverage report's `illuminance`-style `offered_not_mappable` rows for PM and index channels become `offered_not_registered` or `catalog_declared`. Re-running the C1 fixtures shows the change as a diff.

## 6. Slices (each its own PR, test-gated; nothing deploys without Nate's go)

| Slice | What | Acceptance | Waits for |
|---|---|---|---|
| C1 vocabulary | Section 4 items 1 to 4; no discovery or UI change | D7, ontology and catalog parity tests pass; catalog regenerated; no assigned type changes for any device | this note reviewed (Cowork proxy, then Nate for the fields-not-type call) |
| C2 discovery mapping | Section 3: `semanticFieldFor(type, deviceClass, unit)`, the PM match rule, index channels left unmapped | regressions: Kidde AQI still stored as `airQualityIndex`; a `sen6x` VOC or NOx index is never stored as `airQualityIndex`; CO2 never becomes `co`; a PM entity in a non-µg/m³ unit is not stored; existing golden types unchanged | C1; the device-class check in section 1; backend deploy needs Nate's go |
| C3 UI | Units map (`App.jsx` line 6085) gains µg/m³ for PM and no unit for indexes; labels from section 2; the air-quality tile (line 5927) never labels an index as AQI | Vitest: index labels contain "index", no "AQI"; existing chips unchanged for current devices | C1 |
| Box 3 enrollment | the explicit channel map writing `vocIndex` and `noxIndex` | catalog S7 and W-84 acceptance | S3, N3, W-84 gate |

## 7. Decisions for Nate

1. **Fields on `AIR_QUALITY_SENSOR`, no SEN66 type** (section 2). Code recommends this; Nate owns any type decision.
2. **Index channels wait for explicit enrollment.** VOC and NOx indexes are charted only after the S7 channel map, not inferred from `device_class` earlier (section 3). The alternative is a firmware-side marker, which still needs the same gate.

## Not claimed

No SEN66 or Box 3 entity has been read on a live system. The ESPHome `device_class` values are unverified. No threshold, attention rule, tile or alert is approved. Nothing here says the foam work did or did not change the air.
