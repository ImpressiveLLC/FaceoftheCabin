# Sprint Status

Tracks execution against `docs/ontology/DECISIONS.md`. Updated as work lands —
this file should never drift more than a sprint behind reality (see
`feedback_plan_hygiene` discipline: no dangling half-finished decisions).

## Sprint 0 — Deploy only

**Scope:** Tiny Helpdesk (Ollama + Open WebUI + KB Generator).

**Status: PARTIALLY DEPLOYED (2026-08-30).** Ollama + Open WebUI added to
`docker-compose.m920q.yml` and brought up on the M920q (`ollama`,
`open-webui` containers, network-internal only for Ollama — see that
file's own comments for the port/auth reasoning). KB Generator is
**not** part of this deploy: no implementation exists anywhere in this
repo or elsewhere (confirmed via SSH 2026-08-29, and re-confirmed no
change since). It's real engineering work — parsing `zigbee-herdsman-
converters` data, generating KnowledgeNode entries — not a container to
stand up, so it doesn't fit "deploy only." Tracked separately, not
invented as fake Sprint-0 scope; see the KB Generator issue below.

Deploy-approach decisions made this session, closing out the reason
issues weren't opened earlier:
- **Model:** `llama3.2:3b` (3B, default q4_K_M quant). Benchmarked live
  on this hardware (i7-8700T, no GPU) before committing: ~14 tok/s
  generation / ~49 tok/s prompt-eval once warm, first-load ~3s. Fine for
  occasional lookups, not real-time chat — that's this hardware's
  ceiling, not a config choice worth re-litigating without different
  hardware.
- **Open WebUI auth:** `WEBUI_AUTH=true` (its own default — first
  account created becomes admin). Reachable by anyone on the tailnet,
  same boundary as every other admin surface in that compose file.
- **KB source material:** still open — nobody has said yet what content
  the KB Generator should actually turn into KnowledgeNodes beyond the
  `zigbee-herdsman-converters` capability data the D7 vendor_spec work
  already parses. Needs the user's input before that issue can be scoped
  precisely.

**Watch for once the KB Generator exists:** D5 (`KnowledgeNode.source =
auto_generated | manually_curated`) applies directly to whatever it
produces. Confirm it actually tags output that way, and that anything
touching a safety-critical automation (freeze risk, mold risk, water
shutoff) is excluded from auto-generated content per that decision — not
verifiable until something is actually running.

## Sprint 1 — Postgres device repo class + D7 entity schema properties

**Status: IN PROGRESS (started 2026-08-30).** Scope expanded the same day
per direct user instruction, folding in KB Generator v1 and the generic
D4 provenance mixin alongside the original two issues — see DECISIONS.md's
2026-08-30 verification note for what that instruction was and wasn't
based on.

| # | Issue | Covers |
|---|---|---|
| 1 | [#30 — Postgres-backed DeviceRepository for the D1/D6/D7 entity schema](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/30) | Base repository class; real columns instead of the `device` table's catch-all `config` JSONB; D6 Location-as-entity stub |
| 2 | [#31 — Persist D7 reporting relationships with provenance](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/31) | The D7-specific migration: persists what `CabinEventService.reportedFieldsByDevice()` and `vendor_spec.py` already compute live today, with `confirmation_source` provenance; also closes the known `DmDeviceDetail`/mold-risk-trigger inconsistency |
| 3 | [#32 — KB Generator v1](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/32) | First KnowledgeNode source, feeding the Tiny Helpdesk deployed 2026-08-30; D5 `auto_generated` tagging is a hard requirement, not a nice-to-have |
| 4 | [#33 — Generic Provenance mixin](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/33) | D4's `created_by/modified_by/version` audit columns on Device/Rule — additive to, not a replacement for, #31's `confirmation_source` |

"Observation tables" (the two-layer `device_state`/`observations` model)
is **not** a new issue — it's already built, just under different names
(see D2's own Status note below): `DeviceRegistry`'s in-memory state is
`device_state`, `cabin_event` is `observations`. #30 formalizes the
`device` side of this; the observation side needs no new schema work.

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
