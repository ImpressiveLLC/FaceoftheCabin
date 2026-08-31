# Cabin Data Ontology — Agreed Decisions

**Session date:** 2026-08-26 (updated 2026-08-29)

Source of truth for this file is the "Face of the Cabin" ontology-decisions
document; this is the committed, repo-local copy so Sprint work can
reference and link against stable section anchors (`#d1-identity` etc.)
instead of an external doc this repo has no direct read access to.

**Verification note (2026-08-30):** a Cowork peer session reported writing
a "D9" decision plus five locked-in policy answers (KB content, cost
tolerance, auto-run vs. click-triggered enrichment, search-engine privacy,
per-clone vs. shared enrichment) to a project file
(`cabin-ontology-decisions.md`) this repo has no read access to. The one
artifact link provided alongside that report was read in full and contains
none of that — it stops at D6, matching the Divergence Resolutions below.
Treat "D9" and the five answers as **unconfirmed** until the actual text
reaches this repo (pasted directly, or a readable link). Sprint 1 work
below proceeds on the parts that don't depend on those answers, using this
artifact's own already-stated KB Generator v0.1/v1 source list (HA REST
API + Zigbee2MQTT, then the smrekar-platform device table) as the working
default for KB Generator scope absent a confirmed alternative.

## WSJF Formula

```
CoD  = (Safety Value × 2) + Family Usability + Platform Extensibility + Time Sensitivity
WSJF = CoD / Complexity
```

Scale: 1–10 per component · Complexity: Fibonacci.
Secondary gate: each decision advances ≥2 FAIR properties.

This is the formula for scoring *ontology* decisions specifically — distinct
from the general engineering-backlog WSJF used elsewhere (value + time
criticality + risk/enablement over job size), which doesn't carry the 2×
safety weighting or the FAIR gate. Don't conflate the two when triaging
future work; use this one for anything that changes the ontology itself.

## Axioms (not re-litigated)

- Device is the atomic unit; `entity_id` is the canonical identity
- Location (cabin/home) and Area are first-class dimensions
- Time is primary on every fact
- FAIR applies at home scale
- snake_case `entity_id`s are inviolable for automation stability

## Divergence Resolutions

Each entry below includes a **Status** line noting whether it's already
reflected in the running system, partially built, or still a pure schema
decision with no code behind it yet — added 2026-08-29 so this file stays
a decision record *and* an honest map of what's actually shipped, not just
what was agreed.

### D1. Identity

snake_case `entity_id` IS the local URI stem → `cabin:{entity_id}` in JSON-LD.

**Status:** real as of Sprint 2 (2026-08-30). `entity_id` has been
inviolable in the running system all along; the `cabin:{id}` JSON-LD stem
is now a real, dereferenceable identity too — `GET /api/devices/{id}/jsonld`
serves it, `GET /api/context/cabin-context.jsonld` serves the SSN/SOSA
`@context` it references (`src/main/resources/context/cabin-context.jsonld`
is the one real copy; no duplicate lives in `docs/`, to avoid drift).
`JsonLdService` draws from the same DeviceRegistry/DeviceRepository/
DeviceReportingRelationshipRepository data KB Generator v1 already uses —
no new data source needed.

### D2. Observation

Two-layer model — `device_state` (fast/current) + `observations` (historical).

**Status:** already implemented, just not under these names. `DeviceRegistry`'s
in-memory `DeviceStatus` (state, attributes, lastSeen) is the fast/current
layer; the `cabin_event` Postgres table (`CabinEventService`) is the
historical observations layer, already queried for day-bucketed aggregates
(`dailyAggregates()`) and, as of 2026-08-27, for real per-device reported-field
history (`reportedFieldsByDevice()`). This decision describes the existing
architecture more than it prescribes new work.

### D3. Formalism

JSON Schema + JSON-LD context; LLM serialization is a separate layer.

**Status:** not yet true. `docs/ontology.yaml` — the existing, actively
maintained ontology documentation — is hand-authored YAML, not JSON Schema,
and there is no JSON-LD context anywhere in the repo. This is a real,
currently-unreconciled gap between the agreed formalism and what's on disk.
Sprint 1 does not migrate `docs/ontology.yaml` wholesale; it adds a first,
narrowly-scoped JSON Schema stub for D7 only (see below and
`docs/ontology/schema/`), specifically so this gap starts closing instead of
growing with every new entity added only to the YAML.

### D4. Governance

Provenance mixin on Definition layer only; observation stream =
`source_device_id` + timestamp only.

**Status:** not yet implemented as a real schema mixin. The closest existing
analogue is `DeviceStatus.attributes.discoveredFrom`/`source`, which is
informal and inconsistent, not a formal provenance property. D7's JSON
Schema stub below is the first concrete application of this mixin —
`confirmation_source` on a reporting relationship is exactly the
provenance concept D4 describes, applied to one Definition-layer fact.

### D5. KB Freshness

`KnowledgeNode.source = auto_generated | manually_curated`; safety-critical
content is curated only.

**Status:** not yet implemented — no `KnowledgeNode` concept exists in code
yet. Directly relevant to Sprint 0's KB Generator (part of the Tiny
Helpdesk deploy): whatever it produces must be tagged `auto_generated`,
and anything touching safety-critical automations (freeze risk, mold risk,
water shutoff) must not be served from auto-generated content per this
decision. Worth confirming the KB Generator actually tags its output this
way once Sprint 0 deploys — not yet verifiable since nothing is deployed.

### D6. Multi-location

Location entity in schema from day one; defaults to cabin.

**Status:** partially real. `location` already exists on every device
(`"cabin"`/`"home"`) and is used throughout the backend/frontend — but it's
a bare string attribute, not a first-class `Location` entity with its own
schema/id the way this decision specifies. Area (sub-location, e.g.
`mech_room`) is even less formal — it exists only informally in device
names, never as a queryable field. Sprint 1's JSON Schema work stubs a
minimal `Location` schema so D7's reporting-relationship schema has
something real to reference, but does not migrate the existing string
attribute to it — that migration is out of scope for Sprint 1.

### D7. Sensor Naming and Reporting Context

Devices named `temp_mech_room` also report humidity. Two options: (A)
rename device at primitive level to reflect all outputs, (B) keep primitive
name, derive explicit semantic data elements per measurement type with
typed reporting relationships. **Resolution: Option B.**

Device name is the device's short identity — primary measurement +
location — not an exhaustive capability list.

**Status:** partially implemented, and directly informed by a real
production bug found the same week this was decided. `DeviceType.telemetryFields()`
(a static per-type guess: "every TEMPERATURE_SENSOR reports humidity") was
built 2026-08-27 as a first attempt at exactly this "typed reporting
relationship" idea — and turned out wrong for `z2m-temp_outside_lowest`
(model SNZB-02LD, same type as combo sensors that *do* report humidity, but
confirmed via its own 90-day history to have never once logged a humidity
reading). Replaced same day with `CabinEventService.reportedFieldsByDevice()`
— a real query grouping actual logged payload keys per device — which is
now what `SensorHistoryPanel`'s field/device picker gates on. This is Option
B's principle already working in production, just not yet persisted as a
first-class, queryable entity with its own provenance (D4) — it's
recomputed live from `cabin_event` on every request, not stored. Sprint 1's
job is exactly that: give this relationship real columns and a real
repository, with `confirmation_source` distinguishing `empirical_observation`
(what exists today) from `vendor_spec`/`manual_override`.

**Update 2026-08-30:** `vendor_spec` is no longer a stub — `Zigbee2MqttAdapter.
extractVendorReportedFields()` parses Z2M's real `exposes[]` and
`cabin-discovery`'s `vendor_spec.py` provider returns it as a confirmed,
zero-network-call match (`suggestedReportedFieldsSource: "vendor_spec"`),
verified live against a real device. Still not persisted anywhere — this
remains a request-time computation on both the empirical and vendor_spec
paths. Sprint 1's device_reporting_relationship table (issue #31) is what
turns both into a stored, queryable fact instead of two different live
computations.

**Related, not yet reconciled:** a separate proposal (multiple competing,
user-selectable device definitions with provenance — e.g. a community-sourced
alternate definition a user can assert over the manufacturer spec) uses the
same `confirmation_source`-style provenance concept D4/D7 need. Not part of
Sprint 1; flagged so the schema added now doesn't accidentally close off
room for that later.
