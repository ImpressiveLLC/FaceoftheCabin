# Sprint Status

Tracks execution against `docs/ontology/DECISIONS.md`. Updated as work lands —
this file should never drift more than a sprint behind reality (see
`feedback_plan_hygiene` discipline: no dangling half-finished decisions).

## Sprint 0 — Deploy only

**Scope:** Tiny Helpdesk (Ollama + Open WebUI + KB Generator).

**Status: NOT STARTED.** Verified live on the M920q 2026-08-29 — no
container matching Ollama, Open WebUI, or a KB Generator exists yet
(`docker ps -a` came back empty for all three). Matches what was already
known going in: the user flagged this session that Tiny Helpdesk "still
need[s] to finish setup."

No GitHub issues opened yet for Sprint 0 — deliberately not invented ahead
of an actual deployment plan. Open them once the deploy approach (which
Ollama model, which KB source material, Open WebUI auth model) is decided,
not before.

**Watch for once deployed:** D5 (`KnowledgeNode.source = auto_generated |
manually_curated`) applies directly to whatever the KB Generator produces.
Confirm it actually tags output that way, and that anything touching a
safety-critical automation (freeze risk, mold risk, water shutoff) is
excluded from auto-generated content per that decision — not verifiable
until something is actually running.

## Sprint 1 — Postgres device repo class + D7 entity schema properties

**Status: ISSUES OPEN, NOT STARTED.**

| # | Issue | Covers |
|---|---|---|
| 1 | [#30 — Postgres-backed DeviceRepository for the D1/D6/D7 entity schema](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/30) | Base repository class; real columns instead of the `device` table's catch-all `config` JSONB; D6 Location-as-entity stub |
| 2 | [#31 — Persist D7 reporting relationships with provenance](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/31) | The D7-specific migration: persists what `CabinEventService.reportedFieldsByDevice()` already computes live today, with `confirmation_source` provenance; also closes the known `DmDeviceDetail`/mold-risk-trigger inconsistency |

**JSON Schema stubs (this sprint's task 3), already committed:**
- `docs/ontology/schema/device-reporting-relationship.schema.json` — D7
- `docs/ontology/schema/location.schema.json` — D6, minimal, referenced by the above

**What already exists before either issue starts** (so neither one
rebuilds it from scratch): `DeviceLifecycleStore`/`JdbcDeviceLifecycleStore`
(the current `device` table + lifecycle state), `CabinEventService.reportedFieldsByDevice()`
(the live, unpersisted D7 computation this sprint promotes to a real
entity), `DeviceType.telemetryFields()` (the older static guess `type_inferred`
provenance preserves as a fallback, not deletes).

**Not in Sprint 1, tracked as later candidates:**
- D1's `cabin:{entity_id}` JSON-LD context (no JSON-LD anywhere yet)
- D3's full JSON Schema migration of `docs/ontology.yaml` (Sprint 1 adds one
  narrowly-scoped schema, not a wholesale format migration)
- D4's provenance mixin generalized beyond D7 specifically
- Migrating the existing `location` string attribute everywhere it's used
  today, once #30 gives it a real schema to migrate *to*

## Cross-references

- Ontology decisions: `docs/ontology/DECISIONS.md`
- Existing narrative ontology (unaffected by this sprint): `docs/ontology.yaml`
- Related architecture proposal (not this sprint, same provenance concept):
  memory `project_device_definition_architecture` — multiple competing,
  user-selectable device definitions with sourced provenance
