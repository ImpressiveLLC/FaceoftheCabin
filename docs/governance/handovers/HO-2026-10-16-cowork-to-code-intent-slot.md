# HO-2026-10-16: Cowork to Claude Code, intent/slot implementation (execute now)

Baseline: `origin/main` 8ed437b (2026-10-15, PR #159 merged with 98bf290). Nate, 2026-10-16: pick up the intent/slot implementation from the repo state and execute as soon as possible.

**Read first:**
- `.claude/skills/cabin-peer-agent-collaboration/SKILL.md` (freshness, id checks, merge rules)
- `docs/ai-assistant/training/intent-slot/README.md`
- `docs/ai-assistant/training/intent-slot/IMPLEMENTATION-ISSUE.md`
- D27 in `docs/governance/decisions/ontology-decisions.md` (this PR)

For the intent/slot track, this handover takes precedence over HO-2026-10-14. The camera and design-note items there still stand.

## Evidence at baseline (verified 2026-10-16, do not assume more)

- **The package is offline tooling only.** It has schemas, source adapters, synthetic seeds, 22 gold fixtures, `evaluate.py`, the Modelfile and 12 unit tests; the tests pass. Gold labels are `pending-human-review`.
- **Nothing has been trained, exported or deployed.** No model was trained on this package, no `cabin-intent-slot-*` Ollama tag exists, and no runtime route uses it.
- **Ask is read-only Q&A.** `TinyHelpdeskController` serves `POST /api/helpdesk/ask`, which calls `TinyHelpdeskService.ask(question, role)`. The prompt goes to Ollama through `OllamaHttpClient`, defaulting to `llama3.2:3b`. There are no tools, no live state and no action proposals.
- **Execution paths that exist:**
  - `HaServiceController` (`POST /api/ha/services`): `switch` and `input_boolean` only, valve and lock denied, behind the W-31 write gate.
  - `WorkflowRuleService` with `WorkflowActionTargetValidator` and `CommandCatalogService`.
- **Architecture constraint** (D27 Q-OC-5 and the README as merged): FaceoftheCabin's workflow engine owns automation semantics. Home Assistant and MQTT are integration and execution paths, and the cabin leak, freeze and siren automations stay in HA.

## Step 0: land the governance record (docs only, merge first)

This PR carries D27, UC-17, W-66 to W-75, POC2 and C5, which Nate approved on 2026-10-14. These ids are not on main yet, and Nate's rule is that ids are allocated in git before work starts.

1. Before pushing, re-check every id against main and all open branches (skill section 2). Renumber in this PR if any id is taken.
2. Merge once the PR is clean. W-74 is recorded as done in this commit.

## Work, in order

Each step is its own PR from fresh main and carries the DoD governance record. The 5 steps are Nate's 2026-10-16 list, mapped to the ids in this PR.

| # | Nate's step | Id | Machine | Output |
|---|---|---|---|---|
| 1 | Live non-secret entity/inventory export | W-67 slice 1 | ilikethelights | read-only API plus tests |
| 2 | Java `EntityResolver` | W-75 (resolver) | ilikethelights | service plus tests |
| 3 | Parser integration into Ask / Tiny Helpdesk | W-75 (route) | ilikethelights | feature-flagged proposal route |
| 4 | Isolated Ollama evaluation | POC2 / W-75 evidence | M920q | evaluation report on the PR |
| 5 | Shadow / proposal mode | W-75 | M920q deploy | shadow log, executor disabled |

### 1. Inventory export (W-67 slice 1)

`GET /api/assistant/inventory`: a permission-filtered, non-secret projection of what the resolver may target. Each entry carries:
- `entity_id` (canonical, D1)
- display name and `aliases[]` (aliases come from names already stored; no new store yet)
- `site`/location (D6, D24) and area/room
- device class and capabilities (from `DeviceType`/`DeviceCapability`)
- availability, last observation time, and an inventory `version` hash

**Never include:** credentials, tokens, IP addresses, MAC addresses, MQTT topics or HA URLs. Write a test that asserts each of these is absent.

**Policy entries:** add a `DemoAccessPolicy` entry (DENY), and filter by role the same way `ReviewedDocumentSource` does.

### 2. `EntityResolver` (W-75)

**Input:** the semantic target (`device_name`, `location`, `site`, plus `attribute`/`unit` where present) from the intent/slot contract, the inventory snapshot, and the caller.

**Output:** exactly one match, or a typed refusal: `AMBIGUOUS` (with candidates), `NOT_FOUND`, `WRONG_SITE`, `UNSUPPORTED_CAPABILITY`, `STALE`, `OFFLINE` or `UNAUTHORIZED`. It never guesses.

**Checks:**
- the capability must be supported and the value and unit valid
- the inventory snapshot must be fresh
- site isolation
- guards: valve open and lock unlock are refused for Ask (D27 Q-OC-2)

**Logging:** log the reason for every decision.

**Tests:** the IMPLEMENTATION-ISSUE list (unknown entity, wrong site, changed capability, unavailable, stale, ambiguous alias, unsafe range, negation and unit conversion). Use a fake inventory; no live calls.

### 3. Parser integration (W-75 route)

**Route:** `POST /api/helpdesk/propose`, behind `cabin.ask.intent.enabled` (default false), admin session only. `/ask` and its citations stay unchanged.

**Parsing:** call Ollama `/api/chat` with the `intent-slot.schema.json` format and `system.txt`. Use a configurable model tag (prompt-only baseline first, e.g. `cabin-intent-slot-prompt-r1`).

**Validation:** validate against the schema plus the `contract.py` rules, ported to Java or shared through fixtures. Malformed output or an `unsupported` result fails closed.

**Answering questions:** `answer_question` falls through to the existing `/ask` path.

**Mutations:** for mutation intents, run the resolver and return a proposal object (resolved target, action, `requires_confirmation: true`, plan hash, expiry, reasons). **No executor call exists in this PR.** Prove it with a test that uses a spy executor and asserts zero invocations.

**Policy entries:** add the `WriteGateAuditTest` and `DemoAccessPolicy` entries for the new route. Run the whole backend suite (`./mvnw -o test`) and read the surefire XML.

### 4. Isolated evaluation on the M920q (POC2 / W-75 evidence)

Follow the README "M920q execution handoff" exactly:
1. Record RAM, disk and service load first.
2. Use an isolated directory and a candidate tag only, never a production tag.
3. Don't run this while helpdesk training or another eval is active (routing skill load rule).
4. Run the prompt-only baseline: `ollama create cabin-intent-slot-prompt-r1 -f Modelfile.intent-r1`, then `python evaluate.py --model cabin-intent-slot-prompt-r1 --output <isolated>/baseline.json`.

**Post on the PR:**
- structured-output rate, intent accuracy and slot micro F1 on the 22 fixtures
- p50/p95 latency and peak RSS
- the model digest
- the raw outputs path

Measured integration gates stay `NOT_MEASURED`. 22 fixtures measure tooling, not quality: say so. **No SFT training run in this step.** Training waits until the gold set is human-reviewed and expanded, per the README.

### 5. Shadow mode (W-75)

Deploy with the flag on for admin only. Every `/propose` call logs the request id, model tag, raw model output, validation result, resolver decision and reasons, and the proposal returned; execution stays disabled.

Report after a few days of use:
- counts by intent and refusal type
- every wrong or ambiguous target
- latency

The live heater step (W-75's confirm-then-run on the mech room heater only) needs Nate's explicit go after this report. Every other execution path needs C5's gates.

## Gates Cowork holds

- No execution from Ask in steps 1 to 5.
- The model never supplies an entity id (R-OC-5).
- Guards are enforced in the resolver, server-side.
- Secrets never enter the inventory, prompts or logs.
- Ratification and release authority stay with existing governance.
- This handover file is deleted when W-75's shadow report is accepted (Nate's rule: handovers are temporary).
