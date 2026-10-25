# HO-2026-10-18: Codex per-intent schema implementation

Implements [Code's handover](HO-2026-10-18-code-to-codex-per-intent-schema.md)
against main `f40b243`, for W-75 / UC-17 / D27. No new global governance IDs.

## Structural fix

Root cause: the old decoder schema advertised `concept` for every intent even
though both semantic validators prohibited it outside `express_need`.

The schema now uses two complete, disjoint `oneOf` branches. One enumerates the
seven non-need intents and excludes `concept`; the other selects `express_need`
and retains the exact concept enum. Both close their envelopes and slot objects.
No `$ref`, new validation dependency, deterministic stripping, or new device
behavior is introduced. Optional automation tightening is deferred to keep this
round focused. Existing semantic validators remain unchanged and fail closed.

`JsonSchemaLite` already implements `oneOf` with exactly-one matching; no new
keyword support is necessary. New Java tests prove rejection at the schema layer
for every non-need intent and every concept, plus zero/multiple matches and sibling
constraints. Existing gold and confirmation tests still pass. Python tests prove
the same behavior; the original schema demonstrably accepts the leak fixture.

The grading screen reads intent branches while still supporting archived flat
schemas. Vocabulary parity checks read the need branch. The evaluator records
schema/prompt hashes, and ledger recording rejects missing or mismatched hashes.
Comparisons identify schema-only changes and flag unknown provenance or simultaneous
prompt/schema edits. The r3 ledger's missing schema hash was recovered from its
archived M920q package and matched to Git; its scores were not altered.

## Baseline corrections and measurement boundaries

- The offline r3 prompt and all 47 approved gold rows are byte-identical to main.
  The backend resource was still r2 on main; its one-line drift is corrected to
  match the already-evaluated r3 source. This is a backend packaging correction,
  not a new evaluation prompt.
- Ollama reports version **0.33.2**. A bounded probe using the complete proposed
  schema succeeded. When asked to put `concept` on `set_device_state`, it omitted
  the forbidden field but moved the value into other allowed attributes. This
  demonstrates the need to measure semantics as well as decoder validity.
- The pre-merge evaluation uses the existing `cabin-intent-slot-poc2-r3:latest`
  model (listed digest prefix `9d69b2ad6823`) with the candidate schema, unchanged
  r3 prompt and gold, temperature 0, and the same chat request content as
  `evaluate.query`. A standard-library Python client streamed requests in memory
  over SSH to the existing Ollama container network address. Results were saved
  on the Windows workspace, then scored with the repository evaluator.
- No new model tag, host package directory, service restart, runtime flag or
  backend deployment was created. This is a pre-merge candidate evaluation, not
  the canonical `scripts/host/intent-slot-baseline` run. No memory sampling or
  runtime safety integration claims are made for it.
- `reviews-8770.json` remains tied to its original contract. Keep it with the
  archived package; do not change its fingerprint. Direct evaluation of unchanged
  approved gold requires no new human grading.

## Verification and release hold

### Measured pre-merge candidate

All 47 fixtures completed with no transport errors. Evidence:
[raw scored report](../../ai-assistant/training/intent-slot/rounds/2026-10-18-r4-premerge-report.json)
and [round ledger](../../ai-assistant/training/intent-slot/rounds/2026-10-18-r4-premerge.json).

| Metric | r3 | r4 pre-merge |
|---|---:|---:|
| Valid output (schema plus semantic checks) | 74.5% | **91.5% (43/47)** |
| Intent accuracy | 61.7% | **78.7% (37/47)** |
| Slot micro F1 | 0.492 | **0.377** |
| Critical fixture failures | 17 | **17** |
| Concept leaks outside express_need | 10 | **0** |

**Acceptance failed:** validity is below 95%, and slot F1 regressed. Do not treat
the higher intent accuracy as a release pass. The schema constraint worked on all
47 outputs, but it did not fix semantic output quality. Remaining invalid rows:
`gold-08` and `gold-40` lack explicit temperature units; `gold-32` and `gold-47`
fail recurrence-rule parsing. The ledger comparison exits nonzero for the F1 drop.
No invalid values were stripped or repaired before scoring.

The next owner should inspect the extra/wrong slots and recurrence strings in the
report before choosing a separate change. Do not add prompt or gold changes to
this round. Deterministic concept stripping is not indicated by this evidence:
there are no remaining concept leaks to strip. The canonical host-run wrapper and
a new r4 tag remain pending; this candidate report uses the actual existing r3 tag
name rather than claiming an uncreated model.

- Offline Python suite: **80 passed**. Browser schema helpers: **2 passed**.
- Full backend suite attempted: **710 tests, 0 assertion failures, 36 errors**.
  All errors are unavailable Docker/Testcontainers infrastructure; **674 tests
  passed**, including all **91 assistant tests**. The Docker-dependent tests must
  run successfully before merge; an attempted full run is not a green suite.
- User-requested `karpathy-agentic-code` skill validated and installed in local
  Codex skills, with its shared source under `.claude/skills/` in this change.

**Do not merge or deploy yet.** A backend-path merge triggers the deployment
workflow automatically, so the original handover's “merge then measure” order
cannot preserve its own pre-deployment gate. Code owns the eventual merge; first
review the candidate metrics, pass the Docker-dependent suite, and agree the
canonical r4 run/deployment sequence. Retain at least 95% valid output and no
regression against r3 intent accuracy or critical failures as minimum measurement
conditions; these are not sufficient for overall release eligibility.

Shadow mode, live heater execution, temperature-unit prompt changes, `next_step`,
and D27 A1 remain outside this change. No execution authorization is implied.
