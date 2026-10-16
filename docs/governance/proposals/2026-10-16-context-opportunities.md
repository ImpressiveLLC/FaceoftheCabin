# Context relationships and weekly Opportunities review — baseline proposal

Date: 2026-10-16. Status: proposed; documentation only, no scheduler or runtime change.
Base inspected: origin/main f81147f. Requested by Nate in the ontology/intrusion conversation.

## Outcome and scope

Use the existing Opportunities experience to identify missing context, explain device reuse, review patterns weekly, and turn accepted findings into governed implementation work. Start with the leak-sensor buzzer reused by intrusion protection. A candidate relationship is evidence for review, not actuator authority. Git owns reviewed definitions; runtime stores own observations and review history.

This document owns the initial design and seed gap register. Existing [use cases](../use-cases.md), [decisions](../decisions/ontology-decisions.md), and [backlog](../backlog.md) remain authoritative. Local slice/gap names below are document-local labels, not allocated W-, D-, DL-, or UC-IDs. Cowork maps accepted slices into existing work or allocates IDs after checking main and every open PR. No duplicate backlog is created here.

## Existing UC reconciliation

| Existing UC | Reuse in this proposal | Boundary or gap |
|---|---|---|
| UC-4 | Ask explains connections with citations | Newly indexed sources need explicit reviewed retrieval mappings and evaluation |
| UC-6 | One governed home per fact | Local host facts become cited evidence; do not fork canonical definitions |
| UC-10 | Presence and intrusion policy | LAN absence, unknown, stale and authorized occupants are distinct |
| UC-11 | Device-to-workflow links, controls and timed overrides | Incident stop, protection snooze and resume need distinct acknowledged outcomes |
| UC-13 | Reviewable event record | Link observations, incident, notification and action receipts; do not infer identity from absence |
| UC-14 | Intrusion visible in-app with notification evidence | Door/motion seed broadens the camera example without inventing another UC |
| UC-15 | Remote notification and audible-output policy | Conflict: existing manual-only sirens versus Nate's latest description of configured automatic sirens; preserve both pending explicit reconciliation |
| UC-17 / D27 | Context relations and governed actions | Reuse subject_of, topic_of, relates_to, part_of and human_definition; propose any additional predicate through Q-OC-6 |
| UC-18 (merged via #165, 2026-10-16) | Environmental-device reuse | Reference only; this proposal makes no edits to its hardware or POC2 specification |

No new UC is needed for this baseline. Weekly discovery/review is a proposed acceptance extension to UC-6/UC-17. Cowork should decide whether its actor/outcome eventually warrants a separate UC before allocating one.

## Verified implementation foundation

Read from the base commit, not exercised live in this session:

- `OptimizationAnalyticsService` has an hourly default cron and creates/refreshes POWER_DRAW_ANOMALY opportunities. Keep that operational scan cadence unchanged.
- `OptimizationOpportunity` is device-centric: id, type, deviceId, detectedAt, evidence, status, resolvedAt. `OpportunityStatus` has OPEN, ACKNOWLEDGED, RESOLVED. These are not a complete build/review lifecycle.
- `OpportunitiesController` restricts reads and updates to administrators.
- `TechIdController` already accepts entity/related-entity findings and logs review actions. Its collection GET is open on this base: never place household presence/configuration evidence there without a separate access-policy change.
- `ReviewedDocumentSource` permits ai-assistant/user-guide/ and contributing.md. An arbitrary adjacent document is not automatically Ask context.
- Ontology relationships already exist, but the header vocabulary acknowledges drift. D27 is the governing starting vocabulary, not a license to invent runtime predicates.
- W-45 and D27 W-66..W-75 are related existing work. The shared checkout also has local W-64/W-65 alert/noisy-opportunity work; local/unmerged work is not a main feature. Re-read those branches before implementation.

## Baseline architecture

1. **Source catalog:** catalog reviewed Git docs, relevant sibling repos such as CabinAutomations, allowlisted ilikethelights notes, and read-only M920q configuration snapshots. Record source URI/path, revision/hash, section, owner, visibility, observedAt/verifiedAt, source kind and redaction status. No unrestricted disk crawler. Do not ingest secrets, phone MACs, tokens, raw personal location history or hidden configuration values. Host evidence is sanitized before Git publication.
2. **Evidence-backed relationship proposals:** deterministic extraction from explicit configuration references first; user assertions and pattern detection produce separately labelled candidates. Each candidate records subject/object stable IDs, existing relation predicate, human_definition, location/use-case scope, conditions, evidenceRefs, provenance, review state, implementation state and live-verification state. Unknown target IDs stay unresolved, never fabricated. Confidence does not confer capability or permission.
3. **Context gap records:** classify missing evidence, missing relation, vocabulary gap, policy conflict, capability gap, stale documentation, or missing implementation. Deduplicate by normalized subject/object/relation/scope/gap kind; refresh evidence without overwriting decisions. A rejected/deferred finding stays suppressed until its review date or materially new evidence; recurrence links the prior record.
4. **One Opportunities review experience:** project operational and technical findings into one view with sourceRecordType/sourceRecordId, rather than copying records between stores. Add an administrator-only context-gap store/API (or a reviewed extension of the existing opportunity store) for multi-entity records. Keep public technical findings separate from protected evidence. Domain/service tests must enforce visibility, not only UI filtering.
5. **Human review to Git:** accepted meaning changes produce reviewed ontology/docs PRs. Accepted build work links the canonical W-item and implementation PR; no automatic code merge or device reconfiguration. Store audit history in the application and publish a sanitized weekly decision summary in Git, with links instead of duplicated facts.
6. **Retrieval:** extend approved source mappings and graph context so Ask can explain a connection, cite evidence, or identify what remains unknown. Add tests for unsupported links, stale sources, conflicting policy and cross-location leakage. Model output is never treated as executable entity/service identifiers.

## Intrusion seed: separate configured behavior, intent and proof

Nate describes the current HA/Node-RED workflow as: protection activated; door opening and/or entry motion; his phone absent from the cabin Starlink LAN for a configured period; sirens sound and an alert is sent to Nate. Desired app behavior: see the active incident, stop it, then either resume protection or sleep it temporarily. App control remains unverified. This session did not inspect the live flow, execute a siren or determine the exact delay.

Model separate records for physical device, leak observation, audible-output capability, workflow, incident, protection state, notification delivery and command receipt. The buzzer's intrusion use must not be represented as a leak observation. Preserve the independently operating leak safety path.

| State or operation | Required baseline semantics |
|---|---|
| Protection | DISARMED, ARMED, SNOOZED with persisted expiry and location scope |
| Incident | CANDIDATE, ACTIVE, RESOLVING, RESOLVED; unresolved/failed commands remain visible |
| Detection | Record exact door/motion evidence and AND/OR semantics from owner configuration |
| Presence gate | Store policy reference, delay and freshness; unknown is not absence and absence is not identification |
| Stop and resume | Confirm exact incident/action; stop only this incident's output demand; resume/retain armed protection after receipt |
| Stop and snooze | Confirm incident, scope and duration; persist timer in owning workflow system; show resume time and actual state |
| Acknowledge | Marks review only; never claims output stopped or protection changed |
| Shared siren | Track active demands/reasons. Intrusion resolution cannot clear a concurrent leak demand. If the hardware cannot distinguish demands, refuse to promise selective silence and surface the constraint |
| Restart/timeout | Restore expiry from durable state; re-read actual owner state; retry by idempotency key, never replay a physical action blindly |

Existing HA/Node-RED workflows retain ownership, timers and device commands. Platform actions use narrow authenticated owner adapters, policy validation and auditable receipts. New platform-owned workflows follow D27's WorkflowRuleService rules. Neither a relationship PR nor this proposal migrates ownership or authorizes live siren testing.

## Seed gap register

All records are proposed and unverified unless explicitly stated. Owner lanes are proposed, not accepted assignments.

| Local key | Kind / evidence | Next step | Done evidence | Proposed owner |
|---|---|---|---|---|
| siren-policy | Policy conflict: UC-15 vs latest Nate description | Cowork document whether different modes explain both, or request a scoped supersession ruling | Nate-approved policy, references reconciled; no silent replacement | Cowork + Nate |
| entry-triggers | Missing evidence: door AND/OR motion and delay | Read-only deployed HA/Node-RED export compared with Git | Sanitized source/hash, exact predicates/timing and drift record | Code |
| presence-context | Missing evidence: LAN detection/freshness and occupants | Inspect source without publishing identifiers; trace D24 | Known/absent/unknown/stale cases and policy tests | Code + Cowork |
| buzzer-reuse | Missing relationship/capability binding | Match real instance and buzzer exposure to both workflows | Stable IDs, supported relation, provenance; no guessed command | Ontology lane |
| incident-ui | Missing implementation/proof | Trace owner event through API and active-alert UI | One incident with causal observations and acknowledged resolution receipt | Backend + UI |
| snooze-recovery | Missing implementation/proof | Inspect owner timers and restart behavior | Expiry, rearm, idempotency and failure tests | Integration lane |
| shared-output | Policy/capability gap | Define concurrent leak/intrusion arbitration | Simulated leak stays protected when intrusion stops; physical test separately authorized | Integration + safety review |
| source-coverage | Retrieval gap | Catalog and map reviewed operational sources | Ask cites both uses and states unresolved policy honestly | Ask lane |

## Weekly review cycle

Requested cadence: weekly. Proposed default for implementation: Monday 09:00 America/Chicago, configurable; day/time still requires product acceptance. This is an application feature proposal, not a Codex reminder or an enabled scheduled job.

Operational detection and urgent safety alerts continue at their own cadence. A weekly job produces one review batch from new/materially changed candidates and due deferred items, using a persisted watermark and unique week/location key. A missed run catches up once; retries cannot duplicate a batch. Capture detector/version, coverage window and omitted/unavailable sources, including an honest empty result. No routine review job silences or delays alarms.

Review cards show: need, evidence, uncertainty, affected devices/workflows, missing relationship, suggested next step, safety/privacy impact and provisional effort/value. Actions: accept for design, request evidence, defer with review date, reject with reason, or link existing work. Acceptance into planning does not approve execution. Keep reviewDisposition separate from existing opportunity status; RESOLVED alone is not proof of deployment. Notify administrators of a meaningful batch via the existing opt-in delivery mechanism only; delivery failure remains visible in-app. Don't expose presence details in notification text.

Weekly output: batch reference, decisions/reasons, canonical work links, owners, dependencies, WSJF changes, next review date and unresolved questions. Release criteria require an actual persisted batch and reviewed disposition, not merely a successful cron invocation.

## Provisional WSJF and sequence

Apply the [platform formula](../backlog.md): CoD = 2*Safety + Usability + Extensibility + Time; each 1–10, Size Fibonacci. Scores below are agent proposals for Cowork/Nate review, not ratified global priority. Safety policy gates and dependencies override numeric rank. Ask-specific work belongs in its separate backlog after scoping; do not double-score it.

| Slice | Safety | Usability | Extensibility | Time | CoD | Size | WSJF | Dependency |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| A: reconcile policy + source/UC evidence | 9 | 7 | 8 | 8 | 41 | 3 | 13.67 | First; read-only |
| B: relationship/gap contract + source catalog | 7 | 7 | 10 | 6 | 37 | 5 | 7.40 | A; align W-45/D27 |
| C: weekly Opportunities review + audit | 6 | 8 | 9 | 6 | 35 | 5 | 7.00 | B; auth/migration design |
| D: intrusion incident adapter + resolve/snooze UI | 10 | 9 | 7 | 8 | 44 | 8 | 5.50 | A/B; policy and shared-output gates |
| E: reusable pattern detectors | 5 | 7 | 9 | 4 | 30 | 5 | 6.00 | C; start with deterministic evidence |

Initial delivery order A, B, C, then D/E as independent lanes where feasible. E's higher score does not remove D's safety gates or preempt existing approved work. Scores must be compared with the full backlog, not only this table. Candidate evidence may be logged immediately; unknown detector accuracy blocks automatic promotion.

## Collaboration and work sharing

Use [cabin-opportunity-work-sharing](../../../.claude/skills/cabin-opportunity-work-sharing/SKILL.md), plus the existing peer collaboration and merge-sequencing skills. A coordinator records assignments and acknowledgments in the PR/iteration; availability is not assumed.

| Lane | Deliverable / ownership | Handoff |
|---|---|---|
| Cowork + Nate | UC/policy reconciliation, predicate review, canonical IDs, WSJF ratification | Approved contracts and open questions to implementers |
| Code/integration | Read-only owner/config evidence, incident/receipt contract, shared-siren feasibility | Sanitized fixtures to backend/UI; no live sound test by default |
| Codex/backend | Gap/source schema, privacy boundary, dedup/review API, weekly batching | Versioned API fixtures to UI |
| Codex/UI | Existing Opportunities review and incident controls | Wire against contract, show uncertain/failed state |
| Ask/evaluation | Reviewed context mappings and relationship explanations | Citation/failure evaluation to reviewers |
| Independent reviewer | Adversarial state/privacy tests, schema validation, source traceability | Evidence and remaining gaps before merge |

One PR per cohesive slice; contracts before dependents. File ownership must be explicit: source/schema owner, backend owner, UI owner, and one governance-table editor. Peers do not concurrently rewrite ontology.yaml/backlog.md/use-cases.md. Shared changes merge fetched main; no rebase/force push. Check every open PR and merge-tree, preserve queue order, publish overlap notes. Treat unanswered peer coordination as unknown.

Coordination snapshot: #165 edits ontology, UC/backlog and POC2/docs; #106 edits backlog. This proposal adds two new files only and allocates no global IDs. POC2/Doc27 session reviewed the scope on 2026-10-16: no file overlap; preserve platform ownership for new user-authored workflows, existing HA safety ownership, and the distinction between purpose and executable capability. It agreed the siren-policy conflict remains unresolved. This is a scope review, not acceptance of an implementation assignment. Existing local alert/noise work must be surveyed before implementation. The proposal may be reviewed independently; dependent implementation must refresh these facts.

## Validation and next steps

Documentation acceptance: existing UCs mapped, source claims tied to inspected code, local links valid, scores reproducible, no secrets or claims of live verification. No runtime test is implied by documentation checks.

Implementation acceptance: deterministic dedup and rejected-candidate suppression; weekly timezone/restart/catch-up tests; anonymous/guest/non-admin denial; private evidence absent from public Tech ID; unknown/stale presence; wrong location; concurrent leak/intrusion; duplicate stop; partial failure; restart during snooze; exact confirmed plan and owner receipt; Ask citation/conflict tests. Run relevant full suites and record actual counts. Live siren checks require their own explicit authorization and controlled plan.

Next review: Cowork/Nate reconcile siren-policy, ratify/revise scores, accept weekly review day/time, and assign slice A. Backend/integration agree source and event contracts before UI work. Record resulting canonical IDs, PRs and dispositions here by links. This proposal creates no deployed feature and does not claim step 5 shadow mode or POC2 implementation is complete.
