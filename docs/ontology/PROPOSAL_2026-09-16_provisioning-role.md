# Proposal: `provisioning_role` ontology field (schema v0.6.0 candidate)

**Status: proposed, not ratified.** Authored by Claude Code, for Cowork's
review — ontology changes are Cowork's owned domain per
`docs/ai-assistant/rag/EVAL-ENVIRONMENTS.md` §3 and
`questions_manifest_r1.json`'s `agent_registry`. This is a write-up, not a
live schema edit — nothing in `docs/ontology.yaml` has been touched.

## Where this came from

Grading a POC1 fine-tuning eval round (2026-09-16) surfaced a real
recurring failure: on Q18 ("Install an independent Family Hub/cabin
system with no cameras or zone presence — what inputs do you need?"),
every model repeat confidently presented a short, fixed device list as
*the* definitive answer. The eval manifest's own `fail_if` explicitly
names this pattern ("Names a small subset of devices as the definitive
input list without acknowledging incompleteness") — but that requirement
currently exists only as prose in a grading rubric
(`questions_manifest_r1.json`), asking the model to remember not to
truncate. We've now seen directly that instruction-following alone isn't
reliable for this.

## The actual proposal

The underlying fact this is really about isn't an instruction ("don't
truncate lists") — it's a real structural property of the devices/
services themselves: some are load-bearing for *any* install of a given
kind (Zigbee coordinator, MQTT broker, Postgres, Kafka — see
`docs/REPLICATION.md`'s new "Scoping a minimal install" section, PR #74),
and some are scope-dependent (cameras only matter if you want recording;
presence sensors only matter if you want presence-based automation).
Encoding this as ontology data, rather than leaving it entirely to prose
the model has to remember, lets any grounded answer (human or AI)
correctly enumerate both classes instead of relying on the model not to
forget.

### Field shape (matching the existing `lifecycle_status`/`data_class` rollout pattern)

```yaml
provisioning_role: core | optional        # schema v0.6.0, added <date>
# optional entries also carry:
optional_for_capability: camera_recording | zone_presence | ha_bridging | <etc.>
```

- `core` — required for essentially any install of this system, regardless
  of which optional capabilities are enabled. Example candidates: the
  Zigbee coordinator entity, MQTT broker, Postgres, Kafka, the core event
  pipeline entities (`EventPublisher`/`EventConsumer`/`CabinEventService`).
- `optional` — only needed to support one named capability. Example
  candidates: Frigate/camera entities (`optional_for_capability:
  camera_recording`), presence `zone:` entities (`optional_for_capability:
  zone_presence`), Home Assistant bridging entities (`optional_for_capability:
  ha_bridging`) if HA itself is ever optional rather than core — needs
  Cowork's call, flagged as an open question below.

### Rollout, matching existing precedent exactly

Per `data_class: product`'s own 2026-09-11 rollout note: **not backfilled
across all existing entries in one pass.** New entries get
`provisioning_role` from day one; existing entries pick it up as they're
otherwise touched, or in a scheduled priority-order migration pass — same
pattern already used for `lifecycle_status` (v0.4.0) and `data_class`
(v0.5.0). See `ROADMAP.md`'s "Ontology Migration Review" section, which
already owns this kind of staged-rollout tracking.

## What this does NOT do

- Does not change how any answer is generated today — this is ontology
  metadata, not a code change. A future step (out of scope here) would
  have the Ask/RAG pipeline actually read and use this field when
  answering install-scoping questions; that's a separate, larger piece of
  work, not bundled into this proposal.
- Does not replace the eval manifest's `fail_if` criteria for Q18 — that
  stays as the test. This would be the mechanism that makes passing it
  reliable instead of hoping the model remembers.
- Does not retroactively grade or re-open the POC1 eval round that
  surfaced this — that's tracked separately in the round's own grading
  file and the `[POC1]` backlog entry.

## Open questions for Cowork

1. **Field name.** `provisioning_role` was chosen to avoid colliding with
   `lifecycle_status` (used/unused/deprecated/candidate — a *temporal*
   axis) and `data_class` (raw/derived/conceptual — a *data nature* axis).
   This is a third, orthogonal axis: *is this required to stand up an
   instance at all*. Cowork may prefer different naming (e.g.
   `installation_role`, `core_element` as the user's own initial framing
   suggested) — no strong opinion here, just avoid ambiguity with the two
   existing axes.
2. **Is Home Assistant itself `core` or `optional`?** This instance uses
   HA as an integration bridge, but a hypothetical pure-Zigbee-only
   install might not need it at all. Needs a real decision, not assumed
   here.
3. **Should `optional_for_capability` be a free-text field (matching
   `TechIdFinding.provider`'s and `first_used.ref`'s existing "free text,
   never blocked on a missing enum value" precedent) or a closed enum?**
   Leaning free-text for consistency with that precedent, but flagging
   for Cowork's call rather than assuming.

## Suggested next step

If Cowork ratifies the shape above (with whatever naming/scope
corrections), the actual `docs/ontology.yaml` edit would be a normal PR
against a handful of already-identified entities (the ones enumerated in
`docs/REPLICATION.md`'s new "Scoping a minimal install" section is a
reasonable starting set) — small and mechanical once the shape itself is
agreed, not gated on anything further from this write-up.
