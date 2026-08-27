---
name: cabin-3rd-party-device-onboarding
description: >
  Bridges a 3rd-party/vendor-cloud device -- one with no local protocol,
  paired only through its own app, Google Home, Alexa, or a vendor cloud
  service -- into the cabin-orchestration-platform stack, and makes its
  readings sortable/chartable in the UI exactly like a native Zigbee
  sensor. Use this whenever a device, sensor, or appliance is vendor-app-
  only or cloud-linked with no local API: smart smoke/CO/air-quality
  alarms, most HVAC systems and mini-splits, white-goods/appliances
  (Kidde, Liebherr, Bosch, Daikin, Mitsubishi, Ring, and similar), or any
  time the user says things like "the Kidde alarm isn't showing up",
  "add my new fridge to the dashboard", "this only works through the [X]
  app", "why isn't [device]'s temperature/humidity/reading charting",
  or "how do I wire in a non-native device" -- even if they never say
  "Home Assistant" or "MQTT" by name. Also load this when a device IS
  already registered but its readings show up as a generic/unlabeled
  type, don't appear in a sensor picker, or a chart shows "no data" for
  a device you can see is online.
---

# Onboarding a 3rd-party / non-native device

## Why this is its own pattern, not a one-off

A native device (Zigbee, direct MQTT) speaks the stack's own local
protocol: Zigbee2MQTT publishes a JSON payload with semantically-named
fields (`temperature`, `humidity`) straight onto the bus, and every
consumer -- workflow triggers, the charting endpoint, the frontend
picker -- already knows how to read that shape.

A 3rd-party device doesn't. Its data lives inside a vendor's own
app/cloud ecosystem, built for that vendor's own consumption, not this
stack's. Bridging it always means: get its data into Home Assistant (the
stack's actual convergence point for this class of device) by whatever
mechanism is available, then close three gaps that a native device never
has -- **type inference**, **field normalization**, and **UI parity**.
Those three gaps are the reusable part; everything else about the vendor
(which cloud API, which HACS integration) is specific to that one device.

This was worked out concretely bridging a **Kidde HomeSafe CO/air-quality
alarm** into this stack (`ImpressiveLLC/FaceoftheCabin`,
cabin-orchestration-platform backend + `cabin-ui` frontend). Commit
`17655ca` ("Chart Kidde's CO2/air-quality/CO readings, closing the gap
MAINTENANCE.md flagged") is the concrete worked example every step below
references. Read it before re-deriving any of this from scratch --
`git show 17655ca` in the repo.

## Step 0: confirm the bridge mechanism already exists (or build it)

Check whether the vendor already has a **maintained Home Assistant
integration** -- built-in (`home_connect` for Bosch/Siemens, etc.) or via
HACS (community-maintained; Kidde's is `snell-evan-itt/Kidde-HomeSafe`).
Most name-brand smart devices do, even ones that market themselves as
"works with Google Home/Alexa only" -- the HA integration is usually a
separate, independent bridge someone else already built. If one exists,
the REST-polling path below is almost always less work and more reliable
than anything hand-rolled, since HA's own maintainers absorb the vendor
API churn.

**If a real HA integration exists** (this repo's actual Kidde case):
follow `docs/REPLICATION.md`'s "Third-party cloud device with no native
HA integration -- the HACS path" section for the install mechanics --
HACS bootstrap, the account's own OAuth/login through HA's config flow
(never something an agent completes on the user's behalf), picking an
integration by recent commit activity not star count, and the
instance-wide `HA_TOKEN` gotcha (a blank token silently hides *all* HA
discovery, not just one device -- this has bitten this exact project
twice). Don't re-derive that checklist here; go read it.

**If no HA integration exists at all for the vendor**, the fallback is a
hand-rolled MQTT-discovery bridge (something else polls the vendor's own
API/webhook and republishes to a local MQTT topic with HA MQTT discovery
configs). That path has its own real, easy-to-relearn-the-hard-way
gotchas (retain flags, Docker networking, wildcard syntax, verifying
which broker is actually running) -- see
[references/mqtt-discovery-fallback.md](references/mqtt-discovery-fallback.md)
before building one. Don't reach for this path first; it's real
maintenance burden this stack's own incident history has already paid
for once (see that reference's opening note).

Either way, the output of this step is: **the vendor's entities are
visible and polling successfully inside Home Assistant.** Confirm that
in HA's own Entities tab (not just the Devices list, which can cache
stale after an area assignment) before moving on.

## Step 1: type inference (`HomeAssistantDiscoveryService.inferType()`)

Once HA has the entities, this stack's `HomeAssistantDiscoveryService`
polls them via `HomeAssistantAdapter` and turns each into a
`DeviceDescriptor`. `inferType()` maps HA's `device_class` attribute to
one of this app's `DeviceType` enum values. A vendor integration will
almost always report `device_class` values this mapping doesn't cover
yet, so the entity falls through to the generic `HOME_ASSISTANT_ENTITY`
catch-all -- which the UI can't specially chart, sort, or icon.

**Fix**: add the missing `device_class` -> `DeviceType` mapping(s). Give
the new type a sensible `DeviceCategory` -- an ambient/continuous reading
(temperature, humidity, CO2, air-quality-index) joins `CLIMATE`
alongside the existing temperature/humidity sensors, not a new category,
unless the reading is genuinely unlike anything that category already
holds.

**Real gotcha, confirmed live**: a vendor's own `device_class` values are
not always the ones you'd guess, and are not even consistent across one
device's own entity set. Kidde's CO2 entity really does use
`carbon_dioxide` as expected -- but its TVOC entity uses
`volatile_organic_compounds_parts` (not the shorter, more "obvious"
`volatile_organic_compounds`), and its dedicated CO-ppm entity has **no
`device_class` at all**, confirmed against the live account, not assumed.
`.contains()` matching (not `.equals()`) on the device_class string
absorbs minor vendor variants like the `_parts` suffix. For an entity
missing `device_class` entirely, fall back to `unit_of_measurement`
(Kidde's CO-ppm entity reports `ppm`) -- keep this fallback generic
(unit-based), not keyed to the specific vendor's name, so it also covers
a future integration with the same gap. If even the unit is missing,
entity_id substring matching is the last resort -- scope it narrowly and
comment exactly why, since it's the least principled signal available.

## Step 2: semantic field normalization

This is the step that's easy to skip and makes everything downstream
silently not work. A native device's payload already carries
semantically-named fields (Zigbee's `temperature`/`humidity`) that
`WorkflowRuleService`, `CabinEventService.dailyAggregates()`, and the
frontend's history picker all read *directly by that field name*. An
HA-discovered entity's only guaranteed reading is its raw `state` string
-- captured generically as `value` -- which none of those consumers know
to look for.

**Fix**: normalize the raw state into the *same semantic key* a native
device would use for that kind of reading, keyed off the DeviceType you
just inferred in Step 1 (`temperature`->`"temperature"`, a new CO2
type->`"co2"`, etc.), stored *alongside* the generic `value` capture, not
instead of it. This one normalization point means every downstream
consumer treats a 3rd-party reading exactly like a native one, with zero
special-casing per integration anywhere else in the codebase. Guard the
parse: a transient non-numeric state (`"unavailable"`, `"unknown"`, a
handful of seconds during a cloud-poll gap) must not get stored under the
numeric field -- skip it silently and let the next poll cycle retry,
rather than let a string pollute what every consumer assumes is a
number.

## Step 3: UI parity (the frontend chart/dropdown)

A chart or dropdown component that only ever had one native use case to
build against is often hard-filtered to that one `DeviceType` and one or
two hardcoded field options -- e.g. `devices.filter(d => d.type ===
"TEMPERATURE_SENSOR")` plus a two-option `<select>` for
Temperature/Humidity. This is exactly why Kidde's CO2/air-quality
readings had real data flowing into Postgres for two days before anyone
could actually see them charted: the endpoint already worked, the
*picker* didn't know these devices existed.

**Fix**: make both the device filter and the field options data-driven
off `DeviceType` (a small options table: `{value, label, types: [...]}`),
so onboarding the *next* new sensor type is "add one table row," not
"touch filter logic." Two structural things worth designing for
explicitly, not assuming:

- A native combo sensor may report multiple fields from *one* entity
  (Zigbee's temp+humidity sensor). A 3rd-party integration is more often
  *one reading per HA entity* -- each physical measurement is its own
  separate device/entity. The field picker should narrow to just the
  field(s) the *currently selected* device can actually report, and
  reset to a valid default when the device selection changes to a
  different type -- otherwise switching from a Zigbee sensor to a Kidde
  one leaves the picker showing "Temperature" for a device that will
  never have one.
- **Preserve existing defaults exactly when widening a shared options
  list.** Reordering or inserting into an options array can silently
  change what "the first/default option" resolves to for existing
  devices, breaking an existing default-selection test or, worse, a
  user's muscle memory. If a field/option order matters for a default,
  say so in a comment next to the list.

New `DeviceType`s should also fall into the existing Group-by-Type
dropdown and icon map automatically (most such maps already default
gracefully for an unmapped key) -- check this rather than assume it, but
it usually needs no separate work if those maps already have sane
fallbacks.

## Step 4: the "already-configured device doesn't get the fix" trap

This stack treats a device's `type`/`capabilities` as **sticky once it's
been explicitly configured** (lifecycle `ASSIGNED`, or otherwise
"configuration-asserted") -- by design, not a bug: re-inferring type from
a possibly-degraded discovery snapshot on every ~60s poll would be unsafe
for a device a person has already reviewed and approved. This means
shipping the Step 1/2 fix does **not** retroactively fix a device the
user already added before you shipped it -- confirmed live: two
already-assigned Kidde entities kept showing the old generic type after
the fix deployed, while a sibling entity still sitting as an unreviewed
CANDIDATE picked up the correct type automatically on its very next poll.

**After shipping a type-inference fix, always check whether any
already-configured device of the affected vendor needs a one-time
correction.** The sanctioned way to do that is the same "discovery
apply" endpoint the manual review flow itself uses --

```
POST /api/devices/{deviceId}/discovery/apply
{ "mode": "replace", "fields": { "type": "<NewDeviceType>" } }
```

(`DeviceRegistry.replaceConfiguration()`) -- **not** removing and
re-adding the device, and not a raw database edit. This mutates live
device configuration, so treat calling it the same way you'd treat any
other production state change: confirm with the user before doing it
yourself, or point them at the equivalent action in Device Manager's own
UI and let them trigger it.

## Step 5: ontology + tests, in the same commit

- Update (or add) the device's entry in `docs/ontology.yaml` with the
  before/after of what was fixed and why -- this project keeps ontology
  and code deliberately in lockstep
  (`docs/DEFINITION_OF_DONE.md` rules 3/7/9); a code fix without a
  matching ontology update is treated as incomplete, not optional
  polish.
- Every new backend branch needs a real test under `backend/src/test`;
  every new frontend branch needs one in the existing Vitest suite --
  both already wired into the CI gate that runs before deploy. Concrete
  shapes worth copying directly from commit `17655ca`:
  - a `device_class` -> `DeviceType` mapping test per new mapping
  - the missing-`device_class`/unit-fallback test (the vendor-gap case)
  - the semantic-field-normalization test, *including* the
    non-numeric-guard case (a transient `"unavailable"` state must not
    land in the numeric field)
  - a frontend test asserting the widened device filter/field options,
    *plus* a regression test that the pre-existing default selection
    didn't silently change

## Checking for a genuinely local alternative first

Before committing to any cloud-dependent bridge, it's worth a quick check
whether a real local alternative already exists for that vendor/model --
some HVAC brands (Mitsubishi, via an ESPHome CN105 adapter) have a
community-built fully-local path that sidesteps vendor cloud reliability
entirely. A cloud API can also simply break on a vendor app migration
(Daikin's Aurora line has had exactly this happen) -- when evaluating
which HACS integration or bridge to use, prefer one with recent, active
maintenance over one with more stars but a stale commit history, for the
same reason `docs/REPLICATION.md` already gives.

Also don't assume every vendor integration is a live push channel: some
report state only when polled/woken, which can look identical to "device
offline" if you're not expecting the latency. Confirm the delivery
mode (continuous push vs. pull-on-request) explicitly for a new vendor
rather than assuming it matches the last one you onboarded -- this
stack has at least one device (a smart fridge) still under live
investigation for exactly this question.

## Quick checklist

- [ ] Confirmed a maintained HA integration exists (or built the MQTT
      fallback -- see the reference doc)
- [ ] Entities visible in HA's own Entities tab, `HA_TOKEN` confirmed
      non-blank
- [ ] `inferType()` maps the vendor's real `device_class` values
      (checked live, not assumed) + a generic fallback for any entity
      missing `device_class`
- [ ] Raw reading normalized into the same semantic field key a native
      device would use, with a non-numeric guard
- [ ] Frontend device filter + field options are data-driven off
      `DeviceType`, not hardcoded to the first native case
- [ ] Checked whether any already-configured device of this vendor needs
      a one-time `discovery/apply` `mode=replace` type correction
- [ ] `docs/ontology.yaml` updated in the same commit
- [ ] Real tests added on both sides, wired into the existing CI gate
