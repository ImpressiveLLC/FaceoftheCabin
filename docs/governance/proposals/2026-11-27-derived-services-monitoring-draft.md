# Draft contracts for derived services and optional Monitoring

Status: **draft for peer review, not implementation approval**. Baseline: main `d52c06b2`, 2026-11-27. Scope: W-168 B1/B3 and W-167 C1/C2 from [Cowork's handover](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-7212314949), resumed by [7219040361](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-7219040361). No ontology YAML, schema, registry, device, threshold or runtime changes.

## Decision boundaries

The [B2 decision packet](2026-11-27-ontology-readiness-decisions.md) remains unratified. Separate profile records, same-parent default with explicit cross-source joins, and separate CO concentration/alarm semantics below are conditional recommendations. They may be reviewed now, but no implementation or merge adopting them proceeds before Nate's answers. Numeric freshness limits are also not selected here.

Already recorded in W-167 and the handover: global per-device inclusion, any signed-in user may toggle, removing a tile leaves its device enabled, safety alerts stay active, order is server-side, defaults cover existing types, and aurora follows the configurable mechanism. These later rulings resolve the corresponding open questions in the dated 14 November proposal. Service-by-service toggles and splitting the combined temperature/humidity tile are **not** silently adopted.

## B1: candidate measurement rows, reusing existing meanings

These are draft row contracts, not newly registered measurement types. Source validity, formula validity and attention thresholds are different fields. Every row carries quantity, unit/basis, observation time, source identity, method/version and validity reason. Sensor, web-service and manual sources may supply compatible inputs; source kind alone neither qualifies nor excludes an observation.

| Quantity / existing ontology ID | Canonical representation | Validity and compatibility |
|---|---|---|
| Temperature / `temperature_celsius` | °C, scalar | Finite value within the source's documented measurement range. Preserve existing `temperature` reporting type. Formula-specific limits apply separately. |
| Relative humidity / `humidity_percent` | %RH, scalar, declared reference phase | Finite 0 to 100 for this candidate contract; preserve the source value and flag out-of-range rather than clamp it. Existing `humidity` type unchanged. |
| CO concentration / new candidate ID not allocated | ppm only when the source supplies that quantity/basis | Nonnegative finite concentration within that source's specified range; unknown without source evidence. Existing `co` mappings require a reviewed crosswalk before any change. No concentration inferred from `clear`. B2 conditional. |
| CO alarm / existing alarm service contract | Categorical source alarm state | Preserve source states and unknown/stale separately. Not a zero concentration. B2 conditional; no existing ID renamed. |
| PM2.5 / `pm2_5_mass_concentration` | µg/m³, with method and averaging interval | Finite, nonnegative, within documented source range. An instantaneous indoor sample is not daily ambient AQI. |
| CO2 / `carbon_dioxide_concentration` | ppm, declared concentration basis | Finite, nonnegative, within documented source range. Preserve `co2`; never use it for CO. |
| VOC / `volatile_organic_compound_index` | Sensirion VOC Index points | Method-specific dimensionless index. The October 2026 SEN6x datasheet specifies 1 to 500; preserve startup/error values with quality flags, not healthy readings. No ppm or AQI conversion. |
| NOx / `nitrogen_oxide_index` | Sensirion NOx Index points | Same 1 to 500 index range and source-quality requirement; not NO2 concentration. No ppm or AQI conversion. |

The [Sensirion SEN6x datasheet, version 1.0, October 2026](https://sensirion.com/media/documents/FAFC548D/6AC50BC5/PS_DS_SEN6x.pdf), sections 1.4 and 4.8.7, is the source for the index semantics/range. Its product table distinguishes SEN66 from other variants; SEN66 does not acquire formaldehyde capability from family-level marketing. Device operating limits and source error/startup encodings must be mapped separately before enrollment.

### Candidate derivations

Draft formula names below identify versions for discussion, not allocated ontology IDs. Let `T` be °C and `RH` be percent relative humidity referenced to liquid water. No formula emits a value unless its input-quality, age and binding requirements pass.

| Existing ID / candidate concept | Inputs and formula | Output and domain |
|---|---|---|
| `dew_point`, Magnus-water candidate v1 | `g = ln(RH/100) + 17.62*T/(243.12+T)`; `Td = 243.12*g/(17.62-g)` | °C. Require `0 < RH <= 100`, `-45 <= T <= 60`, and computed `Td` within the same approximation domain. No frost-point claim. |
| `absolute_humidity`, ideal-water candidate v1 | `e = (RH/100)*6.112*exp(17.62*T/(243.12+T))` in hPa; `AH = 216.679*e/(273.15+T)` | g/m³. Require `0 <= RH <= 100`, `-45 <= T <= 60`; approximate ideal-gas/pure-water model, no pressure enhancement correction. Source range may narrow the domain further. |
| Temperature-and-humidity | Ordered pair of the two qualified atomic observations, preserving both units and timestamps | Composite, not a scalar formula, heat index, comfort score or new ambient-temperature channel. Keep today's combined tile. |
| Named US PM2.5 AQI candidate | Reviewed time-aggregated PM2.5 plus named method/revision; `I = (Ihi-Ilo)/(BPhi-BPlo)*(C-BPlo)+Ilo` after method-specific concentration preparation | Unitless named index. Requires an approved window, completeness rule, breakpoint revision and rounding/out-of-scale rules. No instantaneous Box sample, VOC Index or NOx Index accepted as daily AQI. Held for method/profile review. |

Humidity formula basis: [WMO-No. 8, 2008 edition, Annex 4.B, page I.4-29](https://www.weather.gov/media/epz/mesonet/CWOP-WMO8.pdf), and Vaisala's [absolute humidity](https://docs.vaisala.com/r/M211280EN-E/en-US/GUID-6454B70E-2FB0-437D-8D3E-0DA8A5AD35D2) and [vapor pressure](https://docs.vaisala.com/r/M211280EN-E/en-US/GUID-718156BA-B884-494E-9739-D102F69AE5FB) definitions. The selected combination is a proposed approximation, not a claim to reproduce a calibrated instrument's full algorithm.

AQI source review: EPA links to the [May 2026 AirNow technical assistance document](https://document.airnow.gov/technical-assistance-document-for-the-reporting-of-daily-air-quailty.pdf), appendix Equation 1 and Table 6. It distinguishes pollutant windows, concentration truncation and index rounding, and supplies out-of-scale handling. Do not adopt a breakpoint table or NowCast method from an older code fixture without reconciling this revision under W-104. No breakpoint or attention threshold is adopted by this draft.

### Preserve existing Box calculations

`docs/ontology.yaml` already contains candidate `dew_point` and `absolute_humidity` rows. Do not duplicate them. Box 2 firmware already computes SHT45-derived values: dew point uses 17.62/243.12, while absolute humidity uses 17.67/243.5 and 2.1674. The proposed absolute-humidity approximation differs. Preserve firmware outputs and identify their formula provenance; never relabel one formula's output as another or rewrite history. A compatibility fixture must compare both before a server-derived channel is enrolled. Firmware refresh from cached inputs is not proof that its two inputs are fresh.

Arithmetic examples for reviewing the candidate formulas (not implementation tests or live readings):

| T °C | RH % | Candidate dew point °C | Candidate AH g/m³ | Existing Box 2 AH expression g/m³ |
|---|---|---|---|---|
| 20 | 50 | 9.255175 | 8.620579 | 8.639091 |
| 20 | 100 | 20.000000 | 17.241159 | 17.278182 |
| 0 | 50 | -9.202020 | 2.424203 | 2.424885 |

Before implementation, independently validate fixtures and choose tolerances from the approximation and source precision. Include RH=0 (dew point unavailable, AH zero only with valid inputs), nonfinite values, wrong units, out-of-domain values, and boundary cases. Never route dew point into heating/freezing logic as ambient temperature.

## B3: one server-side derived-service path

Reuse the existing registry/reporting identity, observation ingestion and display configuration. A derivation consumes immutable source observations; it does not write a synthetic raw telemetry event or make a second device registry. D13 `ServiceEntity.derived_from` names the parent device: retain that meaning and keep input-observation lineage in a separate typed derivation record.

1. Load one validated versioned definition bundle. A definition names formula/version, output quantity/unit, ordered input roles, approved bindings and required age/skew policy. Missing approval, policy or references yields unavailable. Profile placement remains B2 conditional.
2. Resolve each role to an explicit source service. The proposed same-parent default is still B2 conditional. Cross-source bindings require explicit approval and compatible quantity, basis and location context. No nearest-device or newest-anywhere fallback; never mix SHT45 temperature with SEN66 humidity merely because they share a Box enclosure.
3. At evaluation time, select observations no later than that time. Validate source status, finite values, source range, units, maximum age per input and pairwise timestamp skew. Manual entries require their actual observation time and attribution, not merely their edit time. A later arrival of an old observation cannot make the current value fresh.
4. Compute the named formula and return either a value or a typed unavailable reason. Include definition/version, source service and observation IDs, original values/units, observation and ingestion times, evaluation time, input ages, selected bindings and location context. Expiry is bounded by the earliest input expiry; reevaluation without new source observations never extends it.
5. Serve the derived result to display consumers. A historical derived assessment, if retained, stays distinct from raw observations and keeps its original definition/version. Display removal changes neither observations nor assessment retention. No actuator or alert-authority change follows from a derived value.

Proposed unavailable reasons: missing input, stale input, invalid source, incompatible unit/basis, ambiguous binding, future observation, excessive skew, outside formula domain, unapproved definition. Reasons describe evidence, not a room-safety conclusion. A previous value may appear only as explicitly stale history, never as a current successful result.

Required backend proof before READY: golden arithmetic fixtures; same-parent and explicit cross-source bindings; separate channels on one parent; missing/old/future/out-of-order observations; age and skew boundaries; formula-version changes; unit mismatch; output-domain errors; preservation of original device IDs and raw readings. Candidate outputs must be absent from actuator/resolver inputs until separately approved. No runtime endpoint or storage migration is implemented here.

## C1: Monitoring contracts and identity

| Concept | Proposed ownership and behavior |
|---|---|
| Monitoring panel | Location-scoped presentation surface over registered services and derived results; not an observation producer. |
| Tile specification | Reviewed ontology metadata: permitted content/renderer, quantity/unit and eligibility. Unknown or missing specs are not eligible. Retain existing renderers as compatibility adapters. |
| Mutability | Identity, quantity, unit, source, formula and eligibility are locked metadata. Global inclusion and display preferences are separate editable instance state; signed-in users cannot override locked fields. |
| Enabled and lifecycle | Device enablement, ontology lifecycle/maturity, observation freshness and tile inclusion are independent. Removing a tile writes inclusion only. Disabling a device does not silently reset its stored inclusion preference. |
| Inclusion | One global per-device setting in v1, per the latest ruling. Its existing combined tile remains combined. Future per-service overrides require a separate decision and do not follow from this draft. |
| Order | Server-owned order by stable tile identity, scoped to location. Concurrent updates must detect stale revisions rather than silently overwrite each other. Browser order is not authoritative; migration needs a reviewed deterministic default. |
| Site identity | Reference the existing location identity, not a display label embedded in a primary key. Preserve registered device/service IDs and W-157 relocation compatibility; do not create a second identity by concatenating a new location name. |

Effective visibility requires a permitted tile spec, active/enabled device and inclusion. Freshness determines how an included tile renders: stale data remains visibly stale/unavailable rather than disappearing or implying ONLINE. Retain D14 occupancy masking and D22 demo read-only behavior. Existing presentation overrides stay in `DeviceDisplayConfig`; do not copy profile-specific overrides into the global inclusion setting.

Implementation should extend the existing registry metadata and configuration write paths, rather than a parallel dashboard registry. Test any signed-in user's permitted inclusion/order update; signed-out/demo denial; rejection of locked-field edits and ineligible inclusion; stale revision conflicts; second-browser consistency; and removal of smoke/CO/leak tiles with alerts, history and Devices state unchanged. Backfill defaults only from the reviewed set of currently rendered types; no claim that the old proposal's type count is current without a new code census.

## C2: per-location ExternalFeed, aurora first

An ExternalFeed is a configured source for a location, not physical hardware. Its record needs a stable source identity, provider/product, configured location reference, declared fields/units, observation or forecast-valid time, fetch time, freshness policy, source attribution and operational status. Outdoor weather and aurora share that contract while retaining different products and methods. A successful fetch does not make an old forecast current.

Aurora follows C1, with a named provider payload mapped through reviewed source metadata and the closed measurement vocabulary. No generic Home Assistant entity becomes eligible by matching a broad name. First reconcile actual configured entities and provider semantics read-only: a forecast/map, local visibility estimate and binary alert are not interchangeable. No local alert threshold is invented here. A map must carry product/validity attribution and use a reviewed provider origin; no arbitrary URL becomes dashboard content.

Names derive from the configured location label, such as Aurora Visibility, Cabin; a separate Home configuration uses Home. Default inclusion is off for this requested new feed. Setup belongs in `docs/REPLICATION.md`, with location selection, entity-to-service mapping and enrollment gates. Upkeep belongs in `docs/MAINTENANCE.md`, with stale-feed diagnosis, expected provider rhythm, source/version checks and reconfiguration steps. Those runbook edits follow a verified adapter design, not guessed live settings.

Acceptance: two locations have separate bindings and names without ID collisions; stale forecast plus recent fetch stays stale; missing or unknown fields are unavailable; Add/Remove persists globally; removal preserves enabled state and alert evaluation; signed-out/demo restrictions hold. Box 3 derived tiles consume B3 through C1, never a browser calculator. Neither aurora nor Box 3 enrollment is performed by this design.

## Review and implementation sequence

| Next slice | Can proceed now | Gate before adoption/build |
|---|---|---|
| B1 rows and fixture review | Review existing-ID reuse, source references, formula domains and firmware differences | B2, W-102 unit/basis review, W-104 method curation; schema/Java parity for any new reporting type |
| B3 contract | Review lineage, unavailable reasons and reuse boundaries | B1 and B2; independently reviewed implementation and applicable deployment go |
| C1 configuration | Review the already decided inclusion model, identity and migration test plan | O6/O7 meanings confirmed in design; exact-head review, parity and auth tests |
| C2 ExternalFeed | Review source/forecast/location contract and runbook outline | C1, actual provider/entity mapping, measurement-vocabulary amendment and applicable enrollment gates |
| Box consumer | Plan SHT45 provenance and visible derived/stale cases | B3+C1, W-84/D29 N3 and S7 where required; no bypass of held entities |

Cowork is the independent reviewer. Nate's three B2 answers remain open. This packet is deliberately held as a draft until peer review identifies which parts may land as documentation without adopting an unanswered policy. W-172's source repair is separately complete: #618 merged `d52c06b2`, post-merge launcher CI 45989216373 passed; no live training verification is claimed.
