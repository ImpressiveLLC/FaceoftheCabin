# Discussion artifact: install-scoping dependencies in the ontology

**Status: discussion artifact, not a ratification-ready proposal, and
NOT yet an actionable design.** Revised 2026-09-16 after review (see
"Review findings" below) rejected the original flat-field design on
solid technical grounds. Authored by Claude Code; ontology changes are
Cowork's owned domain per `docs/ai-assistant/rag/EVAL-ENVIRONMENTS.md`
§3 and `questions_manifest_r1.json`'s `agent_registry`. This is a
write-up only — nothing in `docs/ontology.yaml` has been touched, no
schema change, no Ask behavior change, no checks added anywhere.

## Feasibility check, 2026-09-16 — not yet actionable, holding here

Before attempting to turn the sketch below into a real design, tested it
against an ontology-engineer and a data-engineer lens. Neither is
convinced there's enough foundation yet — both objections are concrete,
not just caution:

- **Ontology engineer**: "capability" vs. "installation profile" as the
  modeling unit is still an open question (see below) — you can't
  enumerate a real capability/dependency inventory when you haven't
  decided what a capability *is*. The one example driving this whole
  effort (Q18) isn't enough to generalize a schema from safely.
- **Data engineer**: verified directly that `TinyHelpdeskService`'s
  retrieval is stopword-filtered keyword scoring against flat
  `KnowledgeNode` rows (`MAX_CONTEXT_NODES = 5`), not anything that can
  traverse a capability→dependency graph. Even a well-designed graph
  shape has no path to what Ask actually retrieves without new,
  unscoped retrieval logic — and "measurable" needs a coverage metric
  that doesn't exist yet, computed from a modeling unit that isn't
  decided yet, feeding a mechanism that can't consume it yet. Three
  unresolved prerequisites stacked on each other, not one design task.

**Conclusion: holding this as a discussion artifact, not proceeding to a
design pass today.** Revisit once the modeling-unit question has an
answer and the retrieval-integration path has at least a candidate
shape — neither should be decided solo; both belong to whoever actually
scopes this (Cowork for the ontology axis, likely a joint call with
Nate on retrieval given it touches C1b territory).

## Where this came from

Grading a POC1 fine-tuning eval round (2026-09-16) surfaced a real
recurring failure: on Q18 ("Install an independent Family Hub/cabin
system with no cameras or zone presence — what inputs do you need?"),
every model repeat confidently presented a short, fixed device list as
*the* definitive answer. The eval manifest's own `fail_if` names this
pattern explicitly, but the only defense against it today is prose in a
grading rubric — asking the model to remember not to truncate, which
we've now seen directly isn't reliable.

## Review findings (2026-09-16) — the original design doesn't work

The first draft of this proposal added a flat `provisioning_role: core |
optional` field to individual ontology entities. Review rejected it on
three grounds, all of which hold up:

**1. "Core" isn't a property of an entity in isolation — it's relative to
a selected profile/capability set.** The original draft listed a Zigbee
coordinator as `core`, but an install with zero Zigbee devices needs no
coordinator at all. A flat per-entity tag can't express "required *if*
you want capability X," only "always required" or "sometimes required" —
and Frigate alone needs more than one capability tag (`optional_for_capability`
as a single scalar can't hold camera recording *and* whatever else
Frigate participates in). The right shape is a dependency graph —
installation profile → selected capabilities → required/alternative
dependencies — not a property flag.

**2. The named "core" examples aren't ontology entries today, and
creating them isn't mechanical.** Checked directly against
`docs/ontology.yaml` (183 total entries): there is a `zigbee2mqtt_bridge`
entry (the software bridge), but no distinct entries for the Zigbee
coordinator hardware, the MQTT broker, Postgres, Kafka, or the three
event-pipeline services named in the original draft. Only 10 of 183
entries carry `layer: infra` at all, and none of them are these. The
original draft's "small, mechanical tagging pass" claim was wrong —
before any tagging can happen, these need to be reconciled against the
real, running service/device records with the same evidentiary standard
this file already holds itself to (`first_verified_live` — a real check,
not a class name read off source). That reconciliation is real,
unstarted work, not a formality.

**3. Partial coverage cannot honestly answer an exhaustive question.**
Q18's own criterion is "don't present a partial list as complete." A
field rolled out gradually (the same pattern `lifecycle_status`/
`data_class` correctly use, where partial coverage is harmless) would
leave Ask unable to tell whether an untagged entry is untagged because
it's irrelevant or because nobody's gotten to it yet — which reproduces
the exact failure this exists to prevent, just moved from "the model
forgot" to "the ontology is incomplete and nothing noticed." Any real
fix needs a **retrievability/completeness check as an actual runtime
behavior**: before answering an install-scoping question, Ask must be
able to tell whether the relevant profile's dependency set is fully
classified. If it isn't, the honest answer is "ask what the system needs
to do" or "this list may be incomplete" — never a confident enumeration
built on partial data.

## Revised target design (sketch, not ready to build)

- **Shared ontology vocabulary** (`docs/ontology.yaml`, template-level,
  reusable across cloned instances per `REPLICATION.md`): a graph of
  **installation capabilities** (e.g. `zigbee_devices`, `camera_recording`,
  `zone_presence`, `ha_bridging`) each pointing at the **dependency
  types** they require (e.g. `zigbee_devices` requires "a Zigbee
  coordinator" and "an MQTT broker" as *types*, not specific devices).
  This is a general, cross-instance fact — the same shape any clone of
  this platform would need, matching this file's existing charter as the
  semantic contract rather than live state.
- **Per-instance fact** (live `DeviceRegistry`/Postgres, never baked into
  the shared `docs/ontology.yaml`): whether *this* running instance
  actually has something fulfilling each required dependency type today.
  `docs/ontology.yaml:8296` already states this exact principle for the
  main valve action ("Instance-specific by design") — this design
  extends the same, already-established split rather than inventing a
  new one.
- Answering "what do I need for capability set X" then means: look up
  X's required dependency types (shared ontology), check which are
  already satisfied in this instance's live inventory (instance data),
  and present the gap — genuinely joined, genuinely complete for
  whatever's actually classified, and honest about anything that isn't.

This is a real design task, not a mechanical field addition — it needs
its own scoping pass (what counts as a "capability," how alternatives
are expressed when more than one dependency type can satisfy the same
requirement, how the completeness check surfaces to Ask) before anyone
estimates it as small.

## What this does NOT do, still true

- Does not change how any answer is generated today.
- Does not mean Q18 is fixed. It isn't — not by this document, and not
  by `docs/REPLICATION.md`'s new prose section either (PR #74): merging
  Markdown does not update what Ask retrieves. Q18 should stay open
  until there is a real design here, real ontology entries with real
  evidence behind them, an actual completeness check wired into Ask, and
  an end-to-end retrieval test confirming it — not before.
- Does not retroactively grade or reopen the POC1 eval round that
  surfaced this.

## Open questions for Cowork

1. Is "capability" the right unit, or should this be modeled closer to
   an explicit named installation profile (e.g. "minimal," "full
   security")? Capabilities compose more flexibly; profiles are easier
   to reason about and grade against. No strong opinion here.
2. How are alternative dependency types expressed (e.g. this instance's
   own Termux/Android Zigbee bridge as a real alternative to a dedicated
   coordinator, per `MAINTENANCE.md`'s "Home Location" section)? A
   simple "requires type X" edge can't express OR-alternatives.
3. Where does the completeness check actually live — a property of the
   ontology data itself (e.g. a per-capability "fully classified: yes/no"
   marker), or a runtime check Ask performs against live retrieval
   results? Affects whether this needs a schema field at all versus
   being pure application logic.

## Suggested next step

Not a next step in the "land this soon" sense — a real design pass,
likely worth its own scoped session rather than an incremental extension
of this write-up. Whoever picks it up should start from the "Revised
target design" sketch above, not the original flat-field draft, which is
kept below only for the record.

---

## Superseded: original draft (kept for record, do not build this)

<details>
<summary>Original 2026-09-16 draft — a flat provisioning_role field, rejected by review the same day</summary>

```yaml
provisioning_role: core | optional        # schema v0.6.0, added <date>
optional_for_capability: camera_recording | zone_presence | ha_bridging | <etc.>
```

Rejected because: "core" is profile-relative, not a fixed entity
property (finding 1 above); the named examples don't exist as ontology
entries and creating them isn't mechanical (finding 2); and a gradually-
rolled-out field can't safely support an exhaustive-list answer without
an explicit completeness check, which this draft didn't include
(finding 3).

</details>
