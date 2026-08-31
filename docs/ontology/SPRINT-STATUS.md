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
- **KB source material:** resolved by KB Generator v1 (#32, shipped
  2026-08-30) drawing entirely from data already assembled in-process —
  DeviceRegistry (every protocol adapter, uniformly), plus this sprint's
  own DeviceRepository/DeviceReportingRelationshipRepository (#30/#31).
  No separate HA REST API client was needed; DeviceRegistry already
  aggregates HA-sourced devices the same as Zigbee ones.

**KB Generator v1 confirmed live (2026-08-30):** D5's
`source = auto_generated` tagging verified on real generated content
(`GET /api/kb/nodes/z2m-temp_kitchen` on the M920q). The safety-critical
exclusion is enforced by scope, not a runtime check — v1 only ever
writes `DESCRIPTION`/`RELATIONSHIP` chunks, never
`TROUBLESHOOTING`/`SETUP`/`CREDENTIAL_POINTER`, so a freeze-risk/
mold-risk/water-shutoff procedure can't end up auto_generated through
this path regardless of which device it's about.

## Sprint 1 — Postgres device repo class + D7 entity schema properties

**Status: ALL FOUR ISSUES SHIPPED (2026-08-30).** Scope
expanded the same day per direct user instruction, folding in KB Generator
v1 and the generic D4 provenance mixin alongside the original two issues —
see DECISIONS.md's 2026-08-30 verification note for what that instruction
was and wasn't based on.

| # | Issue | Covers | Status |
|---|---|---|---|
| 1 | [#30 — Postgres-backed DeviceRepository for the D1/D6/D7 entity schema](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/30) | Base repository class; real columns (`manufacturer`, `model`, `area`, `paired_at`) instead of the `device` table's catch-all `config` JSONB | **Shipped.** Deployed to M920q; live data confirms real devices already populated (e.g. `z2m-temp_kitchen` → SONOFF/SNZB-02WD) |
| 2 | [#31 — Persist D7 reporting relationships with provenance](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/31) | New `device_reporting_relationship` table; both `vendor_spec` (Zigbee2MqttAdapter) and `empirical_observation` (`CabinEventService.reportedFieldsByDevice()`) now persist through one priority-respecting repository | **Shipped.** Deployed; the whole Zigbee fleet has real rows (verified live) |
| 3 | [#32 — KB Generator v1](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/32) | First KnowledgeNode source, feeding the Tiny Helpdesk deployed 2026-08-30; D5 `auto_generated` tagging is a hard requirement, not a nice-to-have | **Shipped.** `knowledge_node` table + `KbGeneratorService`, no new external API client needed; deployed and verified live — 79 chunks generated across the whole fleet |
| 4 | [#33 — Generic Provenance mixin](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/33) | D4's `created_by/modified_by/version` audit columns on `device` — additive to, not a replacement for, #31's `confirmation_source` | **Shipped** (same migration as #30 — both landed together since they share the same additive `ALTER TABLE`) |

**Not yet reconciled, tracked but not fixed this session:** `DmDeviceDetail`'s
"Reports" row and the mold-risk trigger's device-scoping picker still read
`DeviceType.telemetryFields()` (the older static guess), not the new
`device_reporting_relationship` table this issue just built. Closing that
is real follow-up work, not assumed done by #31 landing.

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
- D1's `cabin:{entity_id}` JSON-LD context — done in Sprint 2 instead, see below
- D3's full JSON Schema migration of `docs/ontology.yaml` (Sprint 1 adds one
  narrowly-scoped schema, not a wholesale format migration)
- D4's provenance mixin generalized beyond D7 specifically — done in
  Sprint 2 (the `/api/kb/curate` write path also gets D5's provenance)
- Migrating the existing `location` string attribute everywhere it's used
  today, once #30 gives it a real schema to migrate *to*

## Sprint 2 — JSON-LD identity, Tiny Helpdesk chat endpoint, curated content

**GATE STATUS (per the roadmap's own text): NOT CLEARED.** The roadmap's
stated gate is "Tiny Helpdesk v1 production-ready for family use." Every
backend piece is shipped and verified live (table below), but two items
the roadmap explicitly lists under Sprint 2 are genuinely not done —
this isn't scope creep to add now, it's work this sprint already
committed to that got deferred without the plan being corrected at the
time. Flagged 2026-08-30 on direct user question ("did we diverge") —
see the two open rows.

| Item | Covers | Status |
|---|---|---|
| D1 JSON-LD identity | `GET /api/devices/{id}/jsonld` + `GET /api/context/cabin-context.jsonld` (SSN/SOSA vocabulary); draws from the same #30/#31 data KB Generator uses | **Shipped**, verified live |
| KB Generator daily refresh | `KbGeneratorService` 3am cron, matching `TelemetryArchivalService`'s off-peak convention | **Shipped** |
| Tiny Helpdesk chat endpoint | `POST /api/helpdesk/ask` — word-overlap retrieval over `KnowledgeNodeRepository` (not embeddings; ~80 nodes doesn't justify that yet) + Ollama generation + source citations (`source` field visible per the roadmap's own requirement) | **Shipped**, verified live end-to-end |
| Manually-curated content mechanism (D5) | `POST /api/kb/curate` — the one path that writes `MANUALLY_CURATED`, source always forced; `KbGeneratorService` now never overwrites a curated chunk for the same entityRef+chunkType | **Shipped** |
| **Safety-critical curated content** — roadmap names three by name: leak response, valve reset, freeze procedure | Freeze-risk response (freeze treated as leak → `main_water_valve` shuts off main inlet upstream of pressure tank + mech-room lines) — user-supplied 2026-08-30, curated onto `z2m-main_water_valve`, verified via the live chat endpoint | **1 of 3 OPEN.** Freeze/leak-trigger done (freeze is explicitly treated as a leak event, so this one procedure may cover both of those names — needs user confirmation, not assumed). **Valve reset (how to manually restore water after a shutoff) is not curated at all** — need the user's real procedure, not fabricated |
| **Open WebUI → native panel** — roadmap's own stated Sprint 2 item | Family-facing chat UI; Open WebUI is real but generic/admin-facing, not the "family use" surface the gate names | **Not started** — real frontend work, deliberately not folded into the backend-focused pass this session did |

**Until both open rows close, Sprint 3 has not actually started per this
plan** — see `DECISIONS.md`'s roadmap cross-reference for what Sprint 3
(Vaultwarden + Actor model) actually is, and confirm sequencing with the
user before beginning it, rather than starting a third open sprint on
top of two unclosed gates.

## Cross-references

- Ontology decisions: `docs/ontology/DECISIONS.md`
- Existing narrative ontology (unaffected by this sprint): `docs/ontology.yaml`
- Related architecture proposal (not this sprint, same provenance concept):
  memory `project_device_definition_architecture` — multiple competing,
  user-selectable device definitions with sourced provenance
