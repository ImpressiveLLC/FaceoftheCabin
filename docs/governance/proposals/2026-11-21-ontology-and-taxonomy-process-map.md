# Ontology and taxonomy process map: as-built, next gate, roadmap and learning-curve plan

Cowork, Sat 21 Nov 2026 (23:32 CST), at Nate's direction in chat (23:29 CST): recall the ontology process, jump back in, wrap it up, add future-ready documentation (roadmap of out-of-scope enhancements, a plan for the learning curve as devices and measurements change, as-built specs) and reach the next ontology and taxonomy gate. Status: **proposal and map; authorizes nothing.** No code, schema, ontology edit, device assignment, host action or deploy is requested or implied. Omission is not permission.

One canonical home per fact: this file points at the records that own each fact and states only what is needed to connect them. Counts marked "fit log" come from the 14 Nov monitoring-configuration proposal at `8827590` and were not re-counted for this note.

## 1. The process, in one table

The ontology work is five chains, started at different times, that meet in the same few files. Nate's rule for all of them: the ontology drives everything; only per-unit identity and the readings themselves sit outside it.

| Chain | Owns | Rows | State on main (57938b30) | Next gate |
|---|---|---|---|---|
| A. Device catalog (D29) | Which type a device is, what it reports, its check-in window, enrollment | W-160 to W-166 (allocated by this PR) | S1 parity test merged (#450); S2 design note merged (#475); nothing built after it | S2 build merged, then S3 shadow read |
| B. Topic taxonomy (D16) | Which human topic each data element lives under | W-89 to W-92 | G1 passed (#179). W-90 (G2, blind double labeling) proposed, not started | G2: kappa at least 0.80, audit at 90% |
| C. Measurement standards and attention | Thresholds, units, standards, AQI, explanation and attention states | W-96 to W-107, DOC-7, [framework](2026-10-22-measurement-attention-framework.md) | DOC-7 "in review"; W-96 to W-104 proposed; live AQI enrollment held | DOC-7 ratified, then W-104 (profile and source curation) and W-102 (units) |
| D. Monitoring configuration | Which ontology elements may appear on Monitoring, and the per-device toggle | W-167 (allocated by this PR; the 14 Nov proposal left it `W-TBD`) | Proposal merged (#458) with Nate's answers; no implementation | Cowork scoring and ratification (this PR), then a design slice |
| E. Human concepts (D27) | Needs and concepts (`cold`, `light`), metadata store, controls from the ontology | W-66 to W-70, W-79 | Vocabulary stored; inventory API slice 1 only | W-68 design note, which the catalog (chain A) seeds |

Chain C is where "OSHA or similar" belongs. The framework's `ThresholdProfile` has jurisdiction, indoor or outdoor context, population, purpose (health guidance, comfort, device operation, empirical anomaly), comparison unit, method and window, and an evidence record; its evidence order puts "relevant current primary authority or standard" first. W-104 owns curating the profiles from primary sources and approving the initial indoor relative-humidity scope. **A repository search finds no OSHA or ASHRAE source recorded anywhere (searched docs, YAML, Java, JSON on main).** Nothing about those standards is in the platform yet, so none is asserted here; W-104 is where each is verified and licensed before it becomes a profile.

## 2. The row and column model (Nate, 23:29 CST), stated as the proposed reading of the records

Nate's expectation: AQI and the measured standard features tied to monitoring-device capabilities get both row and column entries, apply directly to devices as discrete observations, and also apply as combinations of attributes that produce a derived concept.

Read against the records, that is already the shape of D7, D13 and the framework; what is missing is that it is written down in one place and enforced by tests. Proposed reading:

- **Rows are things.** A measurement element (atomic, device-independent: temperature, relative humidity, CO, PM2.5, CO2, VOC index, NOx index), a device class and its capabilities (D7 `capabilities[]`), a service entity (D13: one reading a device reports), a derived concept (below).
- **Columns are facts about a row.** Unit and quantity kind and basis; reporting topic (`reports_to`, D15, D16); the catalog fields (reported fields, default check-in, protocol: D29); threshold profiles and bands (jurisdiction, context, population, purpose, evidence, version); lifecycle and "allowed on Monitoring" (chain D); who may change it (O6 in the fit log).
- **Observed versus derived.** An observation attaches to a service entity of a device. A derived concept is its own row: a mathematical join of atomic measurement rows, defined once in the ontology and satisfiable by any sensor, web service or manual entry (Nate's chat statement of 14 Nov, relayed here; no cited record carries this wording yet). Examples already named on the records: temperature plus humidity, dew point, absolute humidity, and an AQI computed by a named pollutant, window and breakpoint revision. The framework requires that a derivation keep its lineage and never merge sources.
- **Standards attach to rows through profiles, not through the row.** A row can carry many profiles (indoor RH for comfort, a code limit, a health guideline). The same observation may be assessed under several, each explicit. Attention never replaces the native category, and never hides a critical alert.
- **Direct and derived must not blur.** The SEN66 VOC and NOx values are dimensionless indexes, not ppm or an AQI (Doc 27 section on the sensor); W-102 already owns repairing the ppm, CO and VOC/AQI conflation.

Open points for Nate, not decided here: (1) whether the profile columns live in `docs/ontology.yaml` rows or in the separate profile records the framework describes (the framework says separate versioned records; the ontology header says one file); (2) the join condition across devices for a derived row (a new open point, not yet a row in the fit log, which ends at O14); (3) whether CO is a concentration row or an alarm-state row (also a new open point, not yet a fit-log row).

## 3. As-built (what exists, read from main)

- `docs/ontology.yaml`: about 245 entries; header says 0.6.0 and last updated 2026-10-04 though entries were added 12 and 13 Nov (fit log O12). 86 entries at schema 0.1.0, 122 without `lifecycle_status`. Relationship vocabulary drift: 11 edge types in the header, 20 in use (O11). Read by `OntologyLookupService` (display names, candidate triggers). Not read when a device is added.
- `DeviceType` (24 values) carries category and `telemetryFields()`; serialized onto every device read. A third copy of the reported-fields list in `App.jsx` was removed by S1 (#450). Parity between ontology and enum is tested (S1).
- Measurement vocabulary: D7 closed list (14 types) in decision text and `D7MeasurementTypes.java`, tied to a JSON schema by `D7MeasurementTypesTest`. The framework's quantity, unit and profile model is a proposal, not ontology elements (O10).
- Services (D13): `DeviceReportingRelationship` keyed by device id and semantic field. Reporting topics D15 and D16.
- Add and discovery: `HomeAssistantDiscoveryService.inferType` (about 60 lines of rules, a heuristic fallback to `HOME_ASSISTANT_ENTITY`); the discovery service (`web_lookup.py`, `vendor_spec.py`, `catalog.py`) returns cited, confidence-capped matches and labels where suggested reported fields came from (vendor_spec, empirical_observation, type_inferred, manual_override). It proposes and never activates.
- Monitoring: tile eligibility is a `kpiTileFor` switch (15 of 24 types), order in browser storage (fit log section 1). Air-quality tiles exist for `CO2_SENSOR` and `AIR_QUALITY_SENSOR`.
- Ask: `GET /api/assistant/inventory` slice 1 (parent devices only).
- Box 3: not in the platform; per D29 N3 its metrics stay unrecorded until S7 and the W-84 gate. Commissioning state is unverified in the repo.

## 4. Gaps that block Box 3 and air-quality use in the UI and orchestrations

| Gap | Consequence today | Closed by |
|---|---|---|
| No catalog entry or device row for the SEN66 boxes | Their VOC, NOx, PM and CO2 values are not recorded or charted by the platform | W-160 (S2), W-161 (S3), W-165 (S7) with the W-84 gate |
| `inferType` is a guess and enrollment is not gated | A new sensor can be mistyped silently | W-162 (S4) |
| No threshold or standard rows | A reading has no stated meaning; no "spike" can be explained | DOC-7, then W-104 and W-102, then W-97 and W-98 |
| Monitoring eligibility hard-coded | A new measurement needs a code change before it can show | W-167 |
| Two type lists plus a third in docs | Drift | S1 test (done), S2 generator |

## 5. The next gate, and what reaches it

Two gates can be reached without any host work or purchase. Recommended order:

1. **Chain A, gate "S2 merged"**: catalog v0 generated from the ontology, parity test both ways, CI pre-save gate. Owner Code (W-160); Codex reviews the safety-device allowlist first. This is the first gate on the shortest path to Box 3 and needs no Nate decision.
2. **Chain C, gate "DOC-7 ratified"**: Nate's or Cowork's sign-off of the attention semantics, profile applicability and source hierarchy. It is the gate that turns "a standard such as RH or AQI applies through the ontology" from a proposal into policy. It needs Nate's word on the three open points in section 2.
3. **Chain B, gate G2** (W-90): the topic taxonomy's labeling gate; independent of A and C and can run in parallel by Code and Cowork labelers with Nate auditing a 10% sample.

## 6. Roadmap of out-of-scope enhancements (parked, nothing deleted)

Each item names where it would attach. None is authorized by this note.

- Runtime ontology editor with the same server-side validation as the CI gate (S2 design note section 3).
- Generate Java types from the catalog once it has soaked (D29 N1 later option).
- Telemetry quarantine metric for undeclared readings, after S6 has measured how many exist.
- Relocatable devices runtime (W-157), after S3.
- Catalog entries for Home as a collector location (D23).
- More measurement rows: radon, formaldehyde, CO concentration, oxygen. The SEN66 is not a CO, oxygen, radon, mold or smoke sensor (Doc 27), so each needs its own device and its own profile.
- Other jurisdictions' profiles (non-US AQI schemes) and NowCast versus daily averages (framework section on AQI).
- A user-initiated, preview-before-send web lookup seeded from a measurement and its reporting metadata (framework "future option"), reusing the discovery lookup.
- In-app recommendations: need their own evidence, evaluation, privacy and action-authority design (framework).
- Matter and Thread device classes under the same catalog path.
- The D27 metadata store (W-68) taking the catalog as its Phase 1 seed (D29 proposal (c)).

## 7. Learning-curve plan: devices and measurements will change

Principle: expect each of these to change, make the change cheap and visible, and make the unsafe change impossible.

| What changes | What it breaks first | Control |
|---|---|---|
| A new device type or firmware | Wrong type from the `inferType` guess; a reported field the type does not declare | Unknown device is held as a flagged candidate (D29 N2); catalog intake checklist below; S6 measures undeclared fields without dropping data |
| A sensor's algorithm changes (for example how an index self-normalizes) | A baseline silently shifts; a long exposure reads as normal | Record the algorithm version and datasheet citation on the row; treat index-type rows as relative until the datasheet says otherwise; baseline note below |
| A standard is revised (for example a breakpoint table) | An old profile keeps classifying | Profiles are versioned and effective-dated; replay keeps the original assessment and labels a recalculation (framework); review each source when its publisher revises it and at least once a year |
| A unit or quantity basis changes (ppm versus mass concentration, index scheme) | Wrong comparison | W-102 quantity and scheme mastering; no comparison across bases without a stated conversion |
| An ontology edit | The catalog goes stale | Per-entry content-hash stamp and a CI gate (S2 design note sections 3 and 4); the version string is a label only |
| A person's understanding of a reading | A tile is trusted for more than it measures | Act reference dictionary and "Understand this measurement" (W-98); claims limited to what the sensor measures |

**Baseline has two different sources.** A spec-derived reference (what the channel means, units, range, what an index value represents) comes from the vendor datasheet through the discovery lookup path, cited. A site-derived baseline (this location's normal) is observed, labelled `empirical_observation`, and cannot come from the web. A baseline taken after a disturbing event is contaminated. Neither may be written from memory.

**New-device intake checklist** (for each new hardware class): choose or add the Device Type Name (D28); add the catalog entry through the generator, not by hand; list reported fields and their quantity, unit and basis; cite the datasheet for each; add or link threshold profiles only through W-104's source review; add a test fixture for each field; state what the device does not measure; record friction in section 8. **New-measurement intake:** same, plus its derivation if derived, and the lineage back to atomic rows.

## 8. Friction log (Nate, 14 Nov: record difficulties as found so sticky elements can be drilled into)

The canonical log is section 11 of the [monitoring-configuration proposal](2026-11-14-monitoring-dashboard-configuration.md) (O1 to O14). New findings are appended there, not here. Found while writing this note: (F1) no source for OSHA, ASHRAE or similar standards exists in the repository, so the expectation that they are applied through the ontology has no row to attach to until W-104 curates one; (F2) the monitoring-configuration proposal sat with `W-TBD` placeholders from 14 Nov, so it had no row to schedule; (F3) the term "S2" is used for both the intent training candidates and the device catalog, which has already caused misreading; this note writes "catalog S2" where it matters and leaves a rename to Nate.

## 9. First worked example: air-quality watch after spray-foam insulation

Spray-foam work at the cabin makes the first real use of chains A, C and D at once. The SEN66 can report PM, CO2, VOC index and NOx index; it cannot report formaldehyde, isocyanates or CO, and VOC and NOx are indexes, not concentrations. Today the readings exist only in cabin Home Assistant. Two paths: the interim path (assign the Box 3 entities in Device Manager now, then reconcile with the catalog later) reverses D29 N3 and needs Nate's word; the catalog path (W-160, W-161, W-165) is gated by S2, S3, S7 and the W-84 gate. The tile and explanation copy must claim only what the sensor measures. Product re-entry and ventilation instructions are the product's own and are not replaced by a tile.

## 10. Not claimed

- No live system was read. The count of ontology entries and the `inferType` branch count come from earlier notes, not a fresh count.
- The profile columns' home (ontology YAML versus separate records) is an open point, not a ruling.
- Nothing here asserts any value from OSHA, ASHRAE, EPA or a building code; only the EPA AQI category structure already cited in the framework is referenced, by link.
- D29 is recorded but not ratified by Nate's direct confirmation.
