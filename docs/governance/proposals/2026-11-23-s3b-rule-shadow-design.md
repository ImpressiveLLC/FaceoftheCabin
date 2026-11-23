# Catalog S3b: executable match-rule shadow (design)

Status: proposed, design and acceptance only. This is Code's refinement, 23 Nov 2026, of the design posted on #244 (7099513276). It incorporates Codex's seven items (7099691462), which Cowork adopted as acceptance (7099950601). It authorizes no runtime wiring, host read or deploy.

**Sequencing (Cowork ruling 7099950601):** Colab slice A goes first. S3b continues as design and tests-only work, with no runtime wiring until this note is reviewed. S3b sits between S3 (#584, a shadow read that matches only by the assigned type) and S4 (the unknown-device gate).

## 1. Why

The catalog's 39 match rules (16 Home Assistant, 17 Zigbee2MQTT, 6 MQTT bridge) are reviewed fixtures with prose `describes`. DeviceCatalogParityTest check 6 proves that each fixture gives its rule's type in the real `inferType`. Nothing lets the catalog classify a device on its own. S4 needs an independent classification, or its "unmatched device" gate would only restate `inferType`.

## 2. The grammar (closed, data only)

Each seed rule gains `when`. Rules are evaluated in seed order, and the first match wins; this mirrors the if-chains. The last rule of each site is the explicit catch-all, `when: {always: true}`, and it must be last.

| Operator | Meaning |
|---|---|
| `all: [..]`, `any: [..]`, `not: {..}` | boolean composition; an empty `all` or `any` is invalid |
| `equals: {field, value}` | the field's string form, lower-cased with `Locale.ROOT`, equals `value`. `value` is written lower-case, and the schema rejects upper case |
| `contains: {field, value}` | the same normalization, then Java `String.contains` (substring, not word match), mirroring `inferType`'s `.toLowerCase().contains(..)` |
| `present: {field}` | the key exists in the input map, whatever its value (MQTT: key presence only) |
| `capability: NAME` | the Z2M capability set contains this `DeviceCapability` |
| `exposes_property: name` | Z2M `exposes`, searched recursively through `features`, has a feature whose `property` (or, when absent, its `name`) equals `name` and whose `category` is not `config`. This is `hasExposeProperty`'s contract |
| `exposes_relay: true` | `hasRelay`'s contract, searched recursively:<br>• skip any feature of type `light`, `lock`, `cover`, `fan` or `climate`, including its subtree;<br>• match a `binary` feature whose `category` is neither `config` nor `diagnostic`;<br>• whose `access` has bit 2 set (a missing `access` counts as 1);<br>• and whose `name` or `property` is `state` or matches `state_[a-z0-9]+` |
| `always: true` | catch-all only |

**Fields, per site.** These are only what each `inferType` reads today:
- **HA:** `domain`, `attributes.device_class`, `attributes.unit_of_measurement`, `attributes.friendly_name`.
- **Z2M:** `definition.model`, `definition.description`, `definition.vendor`, `definition.exposes`, `capabilities`, `friendly_name`.
- **MQTT:** payload keys only.

**Missing and wrong types.**
- A missing or null field reads as the empty string. That is `inferType`'s own `getOrDefault("")` and `asText("")` behavior, so `equals` and `contains` never throw.
- A non-string value uses its `String.valueOf` form, as `inferType` does.
- `present` is true only for an existing key.

**Validation at load.** The following make the rule set **unavailable**:
- an unknown operator or field;
- a missing `when`;
- duplicate rule ids;
- a catch-all that is not last, or a site with no catch-all;
- a `value` that is not lower case.

The shadow then reports `RULE_EVALUATION_UNAVAILABLE` for every device and never throws (section 4).

**No executable code.** The seed holds data only: no Java reflection, class names, expressions or scripts.

**Where the semantics live.** `exposes_property` and `exposes_relay` are evaluator operators. Their contracts are written out above and implemented separately in the evaluator. They never call `Zigbee2MqttAdapter`'s helpers, so a change to a helper that the operator does not mirror fails the parity tests instead of passing silently.

**Locale.** Zigbee2MQTT's `inferType` lower-cases with the default locale (`toLowerCase()`). Home Assistant's uses `Locale.ROOT`. The grammar uses `Locale.ROOT` everywhere. A test with a Turkish-locale JVM (`İ`/`ı`) records whether the two differ; any difference is reported as a code finding, not hidden by the grammar.

## 3. Helper rules and the friendly-name workaround

- **Helpers.** `zb-05` and `zb-06` are written out in the grammar (option a).
  - `zb-05`, the valve actuator: `contains valve` on description, model or friendly name, then `exposes_relay`.
  - `zb-06`, the smart switch: either the switch case or the power-monitor case.
    - Switch case: not a valve, no `exposes_property water_leak`, no `exposes_property smoke`, and `exposes_relay`.
    - Power-monitor case: `capability POWER_MONITOR`, and a description containing switch, plug, outlet or relay. If a rule cannot be expressed exactly, it is **not** switched to delegation automatically, because a parity failure is exactly what the shadow exists to expose. A delegated rule needs an explicit per-rule approval. It is marked `independence: NOT_INDEPENDENT`, and it counts as unresolved: it cannot satisfy the independent-coverage gate before S4.
- **The friendly-name workaround.** `ha-15-name-co-level` (Kidde's CO-level entity, which has neither a class nor a unit) stays, so behavior parity holds. It is tagged `vendor_workaround`, with:
  - provenance: Bug #3, 2026-09-03;
  - a retirement gate: a separately reviewed, evidence-based migration that keeps the existing CO reading.

  S4 does not retire it silently. Raw names are used only in memory and are never logged, stored or reported. W-170 keeps its privacy-reviewed keyword features.

## 4. Two comparisons, kept apart

| Comparison | Inputs | Reason code when they differ | Meaning |
|---|---|---|---|
| **Inference parity** | catalog rule result versus current `inferType`, on the **identical** input captured at the same call | `RULE_INFERENCE_DIFFERS` | the grammar and the code disagree, a catalog or code defect to fix |
| **Assignment drift** | catalog rule result versus the device's **persisted** assigned type | `ASSIGNED_TYPE_DIFFERS` | an observation only; configured types are deliberately sticky (an admin's assignment, W-93 re-detect). It is never labeled a classifier defect and never changes the type |

S3's reasons stay as they are (`CATALOG_UNAVAILABLE`, `NO_CATALOG_ENTRY`, `ONTOLOGY_ONLY_ENTRY`). Each device read gains:
- `catalogRuleId`, `catalogRuleType` and `independence` (`INDEPENDENT` or `NOT_INDEPENDENT`);
- the input evidence: `inputCapturedAt`, `inputRevision` (the seed hash used) and `inputHash` (SHA-256 of the normalized input, so no raw value is exposed);
- one reason per comparison that differs.

A device with no captured input is `NOT_EVALUATED`, never "matched".

**Failure isolation.** The evaluation runs after `inferType` has returned, inside a try/catch. Any exception or unavailable rule set records `RULE_EVALUATION_UNAVAILABLE` and lets discovery, telemetry and enrollment continue unchanged. A test registers a device with a deliberately broken rule set and proves registration succeeds.

## 5. Coverage of the current inventory

Evaluation happens where `inferType` runs: HA discovery polls, Z2M `bridge/devices`, and MQTT bridge messages.
- Existing devices are covered on their next discovery pass, because every site re-runs `inferType` on each poll or retained message without reassigning a configured device. The comparison is observational.
- A device whose source never re-announces stays `NOT_EVALUATED`. That covers a manually configured device, or an MQTT device that has gone quiet.

The S3 report gains explicit counts:
- `evaluated` and `notEvaluated`;
- `independent` and `delegated`;
- `ruleInferenceDiffers` and `assignedTypeDiffers`.

Runtime observation does **not** close parity check 7. W-170's golden-table snapshot (a gated, read-only capture with Nate's go) remains its own gate.

## 6. Tests (expected answers come from the real classifier)

The expected type in every case comes from invoking the real `inferType` by reflection (the check 6 harness), **never** from the grammar interpreter on both sides.

- **Fixtures:** all 39 fixtures give the same first match in the grammar and in `inferType`.
- **Per-branch near-misses:** for each rule, a near-miss input that must fall through, for example `device_class: smok` or a model `snzb-0`.
- **Precedence:** overlapping inputs that satisfy two rules and prove the earlier one wins:
  - smoke versus `carbon_monoxide`;
  - a valve with a relay versus a smart switch;
  - a description containing "switch" plus power monitoring.
- **Combined Z2M cases:** capability plus exposes, such as ALARM with no description match, or occupancy only.
- **Missing and odd inputs:** missing, null and wrong-type fields; upper- and mixed-case values; MQTT payloads with several keys, to prove key order.
- **Safety boundaries:** the leak, valve, lock and CO rules, each with an input on either side of its predicate.
- **Mutation:** each safety predicate, and each precedence swap, mutated in the seed. The independent comparison against `inferType` must fail for every mutant. The run reports how many were killed.
- **Validation:** an unknown operator, unknown field, missing `when`, duplicate id, misplaced catch-all and upper-case value each make the rule set unavailable, and registration still succeeds.

## 7. Acceptance (before S4 may rely on it)

- Every `inferType` branch has an independent `when` clause; the count of delegated rules is zero, or each one is explicitly approved and listed as unresolved.
- Section 6 passes in CI, including the mutation count.
- Assigned types change for zero devices, and the full suite passes.
- The live reading (evaluated and not-evaluated counts, and both reasons) is taken after a deploy, with Nate's go, and reviewed by Codex before S4 starts.

## 8. Corrections made with this note

The parity check 7 note (`2026-11-22-s2-parity-check-7-golden-table.md`) said the MQTT capture should include `alarm`'s value. `MqttBridgeService.inferType` uses key presence only; `alarm`'s value feeds `determineState`. The row now captures keys only (Codex 7099691462 item 7).

## Out of scope

Runtime wiring before review, any deploy, S4 enforcement, retiring `ha-15`, and W-170's capture.
