# C2 Aurora: source contract and enrollment design

Status: design for review, 2026-11-27 17:06 CST. W-167 C2, following
[the assigned design lane](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-7212314949)
and [Cabin ON-at-enrollment ratification](../decisions/2026-11-27-b2-and-cabin-enrollment-ratification.md).
No runtime change, new measurement enum, provider configuration or live enrollment is made here.

## Verified sources and their different meanings

[Home Assistant Aurora](https://www.home-assistant.io/integrations/aurora/), read 2026-11-27,
documents a percentage estimate and a binary high/low indicator for each configured location.
It polls every five minutes and permits multiple location configurations. Its binary threshold
is configurable (documented default greater than 75); that default is not a newly adopted
Cabin alert rule. Neither an off indicator nor a missing entity is a measured zero probability.

[NOAA's OVATION product](https://www.swpc.noaa.gov/products/aurora-30-minute-forecast)
is a short-term auroral location/intensity forecast, usually 30 to 90 minutes ahead. The
provider describes a Kp-driven fallback without forecast lead time. Visibility also depends
on viewing conditions; a grid value is not a promise that a person at the Cabin will see it.
Keep the provider's product meaning and actual times rather than asserting a fixed 30-minute window.

A direct read of [the provider JSON](https://services.swpc.noaa.gov/json/ovation_aurora_latest.json)
at approximately 17:06 CST returned `Observation Time` = `2026-11-27T23:03:00Z`,
`Forecast Time` = `2026-11-28T00:12:00Z`, `Data Format` = `[Longitude, Latitude, Aurora]`,
`type` = `MultiPoint`, and 65,160 coordinate triples. Endpoints sampled were `[0,-90,5]`
and `[359,90,0]`. This verifies the public response shape, not a local probability calculation,
the whole grid's validity, a Cabin binding, or future provider availability. No private
coordinates were queried or stored. Do not interpret this response as a conventional
GeoJSON FeatureCollection or swap longitude and latitude.

## Binding decision before implementation

The inspected main tree has no NOAA aurora adapter or recorded live entity mapping; the
Daikin Aurora HVAC entry is unrelated. Live Cabin inventory evidence is requested in
[#244](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-7229766551).
Absence from source code is not evidence that HA has no configured integration.

Prefer the existing HA bridge if a verified Aurora configuration supplies the required
location and provenance. Record the exact stable device/entity IDs and integration identity;
do not infer eligibility from a friendly name containing "aurora" or from `%` alone.
If HA lacks provider-validity timestamps, its state-update time must not be relabeled as
forecast time. Either expose the missing provenance through a reviewed adapter or keep
freshness unknown and withhold a current-forecast claim. Do not add a second NOAA poller
merely to avoid investigating the existing bridge.

If no suitable bridge exists, propose a server-side NOAA adapter behind the same ExternalFeed
contract, with bounded requests/cache, approved provider origins and reviewed location-to-grid
semantics. Numeric sampling, spatial interpolation, stale limits and alert thresholds are not
selected by this design. A map link alone is not completion of the requested typed feed.

## Proposed field mapping

These are contract roles, not allocated D7 names or new storage keys.

| Role | Source | Constraint |
|---|---|---|
| Feed identity | Existing service/configuration identity plus site binding | Stable across display-name changes; no Home/Cabin collision |
| Display name | Configured location label | Aurora Visibility, Cabin; no enrollment at Home by implication |
| Numeric estimate | Verified HA Aurora percentage sensor | Retain provider method and percent unit; finite 0–100; never humidity or AQI |
| Binary indicator | Verified HA Aurora binary sensor | Distinct boolean meaning and source threshold; unavailable is not false |
| Provider observation time | Explicit provider field | Never discovery `lastSeen` |
| Forecast-valid time | Explicit provider field | Preserve UTC time and display named location zone |
| Fetch time | Adapter clock | Diagnostic only; does not refresh old provider data |
| Operational status | Fetch result plus approved freshness policy | Missing/invalid/stale distinguishable from a valid zero |

Closed-vocabulary, catalog, schema and Java parity changes must be reviewed together before
a numeric field is enrolled. Reuse C1 eligibility, global inclusion preferences and server
ordering; no special browser-only aurora tile bypass. Initialize inclusion ON for the first
Cabin enrollment only. Preserve explicit later removal and do not disable the feed when its
tile is removed. This design grants no notification or actuator behavior.

## Implementation and acceptance handoff

1. Confirm source configuration, exact entities, site binding and available timestamps; select
   the adapter path with Cowork. Missing evidence is an open input, not invented configuration.
2. Code delivers the bounded backend mapping and parity tests through normal review gates;
   Codex reviews the exact head. Preserve existing device IDs and observations.
3. Integrate the eligible feed with C1 and add setup/maintenance instructions to REPLICATION
   and MAINTENANCE in the implementation PR. Setup identifies location and source; maintenance
   distinguishes source stale, fetch failure and removed tile. No host install occurs here.
4. Test two site bindings, explicit removal persistence, enrollment default, signed-out/demo
   restrictions, missing values, invalid units, out-of-range numbers, stale forecast with new
   fetch time, UTC/date rollover, and wrong/missing integration metadata. Direct-grid adapters
   additionally need longitude normalization, coordinate-order, missing-cell and sampling tests.
5. Compare live source, feed and tile at Cabin and exercise Add/Remove without changing enabled
   state. Report numerical correctness, freshness and location independently; unrun checks
   remain NOT_RUN. No live feed is claimed delivered by merging this design.

## Live evidence and implementation handoff, 2026-11-27

[Cowork's 17:10 CST read and disposition](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-7229926758)
answers the inventory question above. This is peer-observed evidence, not a direct Codex
live check. The read used `/api/devices?location=cabin` at 17:09 CST.

| Existing stable device ID | Source entity | Observed state |
|---|---|---|
| `ha-cabin-sensor-aurora-visibility-visibility` | `sensor.aurora_visibility_visibility` | `HOME_ASSISTANT_ENTITY`; unit `%`; state_class `measurement`; no device_class; value `"0"`; ONLINE; ASSIGNED; enabled true; Cabin |
| `ha-cabin-binary-sensor-aurora-visibility-visibility-alert` | `binary_sensor.aurora_visibility_visibility_alert` | No unit/device_class; ONLINE; CANDIDATE; enabled false |

Both report HA discovery through `ha_rest` and NOAA attribution. Only discovery poll
`lastSeen` (`2026-11-27T23:09:45Z`) was exposed; neither provider observation nor
forecast-valid time was available. The numeric zero is a reported value with unknown
forecast freshness, not proof of a fresh zero-probability forecast. The binary state
value was not supplied in this evidence and must not be guessed from ONLINE.

Cowork proposes the existing HA bridge for both source roles, subject to Nate override:
no second NOAA poller; freshness remains unknown and no current-forecast claim is made
until a separate reviewed provenance step supplies provider times. This is the working
adapter direction, not a newly ratified freshness interval or alert threshold.

**Code implementation handoff:** follow [the PMO assignment](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-7230025694)
with one bounded PR using the existing percentage entity and stable identity. Bind source
integration, entity and Cabin explicitly; `%` alone does not establish aurora semantics.
Retain the two source roles separately. Preserve the binary entity's CANDIDATE/disabled
state; a direction to map it is not permission to enable or emit its alerts. Preserve
other locations and all existing preferences. Cabin's first Monitoring enrollment initializes
ON only after typed eligibility is supported; later explicit user choices win.

Present the reported percentage with source attribution and an explicit unknown-freshness
status, not "current" or a synthesized forecast-valid time. Do not claim usable fresh
forecast history from discovery poll times. Missing, invalid or unavailable remains distinct
from numeric zero. If the chosen contract requires provider times for eligibility, keep
the feed unavailable until that gate is met rather than fabricating times to make it appear.
The PR must state which presentation it implements and expose the missing provenance.

Include controlled-vocabulary/catalog/schema/Java parity and C1 projection tests together;
keep the implementation's metadata reusable for a separately configured location. Add tests
for this exact synthetic source shape, an unrelated `%` sensor, Daikin Aurora name, missing
metadata, zero versus unavailable, two locations, unknown freshness, preserved disabled binary
entity, initial ON versus explicit removal, and signed-out/demo restrictions. Link the existing
history/CSV unit behavior if those surfaces are included. No implicit numeric threshold.

Post full-SHA READY for Codex and Cowork review. Runtime deployment follows its existing
authorization and health gates; implementation is not a live enrollment claim. Setup and
maintenance docs must identify this bridge and the unknown-provenance limitation. Verify the
actual tile and persistence after deployment before declaring C2 released.

Remaining open: provider timestamp evidence; approved freshness policy; closed-vocabulary
naming and parity; implementation and live acceptance. No new ID or threshold allocated.
