# S2: device catalog v0, design note

Code, Tue 17 Nov 2026. Status: **proposal, design only**. This is the note ordered on #244 (Cowork, 20:10 CST Sun 15 Nov: "generator script vs hand-edited, say why; the pre-save ontology gate; the per-entry ontology version stamp; how the parity test runs both ways"). No catalog file, generator, test or workflow is added by this PR. Repo-side only; no host work; nothing deploys.

Builds on: the [roadmap](2026-11-14-ontology-drives-device-adds-roadmap.md) (slice S2 and Nate's rulings N1 to N3), the merged S1 parity test (`DeviceTypeOntologyParityTest`, #450), and the [S8 note](2026-11-15-s8-last-known-good-and-ontology-stamp.md), whose ruling Q4 (content hash is the check; the version number is a human label) this note follows.

Evidence basis: `docs/ontology.yaml` (header `version: "0.6.0"`, 32 `applies_to_device_type` references), `DeviceType.java`, `DeviceTypeOntologyParityTest.java` and `HomeAssistantDiscoveryService.inferType` read on `main` at `dfae944e`. Not run against the live hub. I did not count the `inferType` branches; the roadmap's "about 60 lines of rules" is the figure I rely on.

## 1. What S2 has to deliver

From the roadmap acceptance row: a schema-validated catalog file under `docs/ontology/` with one entry per device type holding id, D28 type name, category, protocol, reported fields, default check-in minutes, lifecycle and match rules; seeded from the enum, the D28 registry and `inferType`; a parity test in both directions against the enum; every `inferType` branch mapped to a match rule; a golden table of today's per-device types for the live inventory. No runtime read yet.

N1 (ruled, as relayed): the catalog is driven by the ontology in one direction; a change to the ontology that would affect the catalog must be tested and validated before it can be saved; each catalog entry records the ontology it was built from and is refreshed when that moves; refresh happens at build and review time, as a generated change committed through a PR.

## 2. Generator or hand-edited: generator, with a small seed file

**Decision: a generator script writes the catalog; nobody edits the catalog file by hand.** The reasons, in order of weight:

1. **N1 already says so.** "Driven by the ontology" and "a generated change committed through a PR" describe a generated file. A hand-edited catalog would drift from the ontology the first time two people touch both, which is the failure N1's gate exists to prevent.
2. **One direction.** A generator makes the dependency visible in code: ontology in, catalog out. The parity test then has one job (catch what the generator cannot, below) instead of policing two hand-kept copies.
3. **Reviewable diffs.** The generated file is committed, so a reviewer reads the catalog change and the ontology change in the same PR, and CI proves they agree by regenerating and comparing.

**What the generator cannot do alone.** Only part of an entry exists in `docs/ontology.yaml` today. Entry id, category and the device types that triggers apply to are in the ontology (and in `DeviceType`). The match rules live in Java (`inferType`), and default check-in minutes and protocol defaults live in `DeviceHealthMonitor` and adapter code. These need a home before they can be generated into anything. I do not want to widen the ontology semantics inside S2 (the roadmap puts ontology semantic expansion out of scope), so:

- Add one **seed file**, `docs/ontology/catalog-seed.yaml`, holding only the fields the ontology does not yet hold: per type, `match` rules, `default_checkin_minutes`, `protocol`. It is hand-edited, validated by a schema, and every field in it carries `source: "inferType"` or `source: "DeviceHealthMonitor"` so a reader knows where the value came from.
- The generator merges ontology-owned fields and seed-owned fields into the catalog and labels each emitted field `from: ontology` or `from: seed`.
- A field moves from the seed to the ontology when the ontology gains it; a test fails if the seed and the ontology both define the same field with different values, so the two cannot disagree silently. The seed is therefore shrinking scaffolding, not a second source of truth.

The cost I accept: one hand-edited input file exists until the ontology owns those fields. The alternative (hand-edit the whole catalog now, generate later) saves a script but leaves the catalog unprotected through S3 and S4, which is exactly when it starts to matter.

Language: a Python script under `scripts/ontology/`, matching the repo's existing tooling (`round_ledger.py`, `generate_synthetic.py`), standard library plus PyYAML. Output is deterministic: sorted keys, fixed number formats, LF line endings.

## 3. The pre-save ontology gate

In this repo the ontology is a file in git, so "save" means commit and merge. The gate has two layers, and only one of them is binding:

1. **Binding: a required CI check on any PR that touches `docs/ontology.yaml`, the seed, the schema, `DeviceType.java` or the catalog.** It (a) regenerates the catalog in memory and fails if the result differs from the committed file, with the diff and the one command that fixes it; (b) validates the catalog against its schema; (c) runs the two-way parity test (section 5); (d) runs a **safety guard**: the entries for leak sensors, water valves, locks and CO alarms cannot be removed, renamed, or have their category or default check-in changed unless the PR lists them in a reviewed `safety-allowlist` with a reason, because S4 and the roadmap name these as never to be downgraded. The PR cannot merge while any of these fail; the failing message names the ontology change that caused it.
2. **Advisory: the same checks as a local script** (`scripts/ontology/check_catalog_impact.py`, optionally wired as a pre-push hook) so a change can be tested before it is pushed. Hooks can be skipped, so nothing relies on this layer.

Open question for Nate, not decided here: if the application ever lets someone **edit the ontology at runtime** (an admin screen), the same validation must run server-side before that save. I did not find an ontology write route on `main` (`OntologyLookupService` is read-only as far as I read it; I did not search the whole backend), so S2 has no runtime gate to build, but any later runtime editor must call the same validation function.

## 4. The per-entry ontology stamp

Each catalog entry records what it was built from, so staleness is detectable per entry and not only for the whole file:

- `ontology_content_hash`: SHA-256 of the **ontology fragments the entry actually reads** (the entity blocks and `applies_to_device_type` lists it draws from), in canonical form. A change elsewhere in the ontology does not stale this entry; a change to a fragment it reads does. This gives the field-level refresh N1 asks for.
- `seed_hash`: SHA-256 of the entry's own seed block.
- `generated_from`: the file-level SHA-256 of `docs/ontology.yaml`, plus the `version` string as a human label only.

Alignment with the S8 ruling: `docs/ontology.yaml` says `version: "0.6.0"` but that string is not maintained (the S8 note found it last bumped on 2026-11-03 across several later edits), so **the content hash is the check and the version string is a label**. S2 stamps the label because Q4 rules that CI will enforce a bump on every ontology change; it never decides staleness on the label. The catalog also carries one file-level `catalog_hash`, which S3 exposes and S8 stamps onto devices.

Refresh rule (build and review time, per N1's refresh-timing ruling): when the CI check finds an entry whose `ontology_content_hash` no longer matches the current ontology, the generator rewrites that entry in the PR, and the diff shows exactly which fields changed (add, amend, append or delete at field level). Nothing is refreshed at runtime in S2.

## 5. The parity test, both ways

The S1 test already runs ontology to enum. S2 extends it into one test class with these directions, each with an explicit allowlist that fails when an entry stops being true (the S1 pattern):

| Check | Direction | Fails when |
|---|---|---|
| 1 | enum to catalog | a `DeviceType` constant has no catalog entry |
| 2 | catalog to enum | a catalog entry names a type that is not a `DeviceType` constant (known differences listed with a reason, today only `RF_TRIPWIRE`) |
| 3 | ontology to catalog | a device type referenced in an `applies_to_device_type` list has no catalog entry |
| 4 | catalog to ontology | a catalog entry's ontology-owned fields differ from the ontology (the same comparison the generator makes, run again as a test so a hand-edit of the generated file is caught) |
| 5 | `telemetryFields()` to catalog | the enum's reported fields differ from the catalog's, per type |
| 6 | `inferType` to catalog | an `inferType` branch has no match rule, or a match rule maps to no branch (the roadmap's "every branch maps to a rule"); implemented as a table-driven test over fixture inputs, not by parsing Java |
| 7 | golden table | the type assigned to each device in the recorded live inventory (a committed export, no live call) changes under the catalog match rules |

Checks 1 to 5 run offline on files. Check 6 needs the fixture inputs for `inferType`, which I will derive by reading each branch; I expect gaps and will list them rather than invent rules (the roadmap says S3's shadow read, not S2, finds disagreements in live data). Check 7 uses a committed inventory snapshot; I have not looked at how fresh the existing export is.

## 6. Acceptance mapping, and what S2 does not do

| Roadmap S2 acceptance | Where it is covered |
|---|---|
| schema validates | the schema in `docs/ontology/schema/` and check (b) in the gate |
| parity test passes | section 5, checks 1 to 5 |
| every `inferType` branch maps to a match rule | check 6, with the seed file carrying the rules |
| golden table of today's per-device types for the live inventory | check 7 |
| no runtime read yet | no backend change in S2; S3 loads it in shadow mode |

Not in S2: any backend runtime read, catalog hash exposure, device-row change, enrollment behavior, check-in change, telemetry scoping, ontology semantic expansion, hardware commissioning or any deploy. Omission is not permission.

## 7. Risks and open items

1. **The seed file can rot.** Mitigation: the seed carries a source label per field, a duplicate-definition test, and a migration note each time the ontology gains a field.
2. **The generator hides a judgement.** Deriving "category" or "lifecycle" from ontology blocks may need a rule where the ontology is ambiguous; the generator must fail on ambiguity rather than guess, and the PR lists each case.
3. **Safety devices.** The safety guard in section 3 is the control; Codex reviews its allowlist before S4 starts (as the roadmap says for S3's report).
4. **Version label discipline.** The CI bump rule belongs to the S8 ruling; S2 depends on it only for the label.
5. **Unverified by me:** the full set of `inferType` branches, the freshness of any inventory export, and whether any runtime path writes the ontology. Each is called out where it matters above.

## 8. Decisions needed

None block this note being merged. For the S2 build: Nate to confirm the seed-file approach in section 2 (the one place I add a hand-edited input), and confirm the runtime-editor question in section 3 as "none exists; if one is added, it calls the same validation".
