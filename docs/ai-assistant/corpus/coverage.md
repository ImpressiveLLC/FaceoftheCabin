# Corpus coverage and gap register

Baseline: `cd145620bcbe7deaa30e236779ec7e1134db7880`. Maintainer: shared authorized contributors. Domain review: unassigned until PR review. Evidence level: source review only; live/runtime and clean-room checks pending.

The [generated inventory](source-inventory.md) scans 237 allowlisted tracked files and records 126 route declarations, 44 Compose service declarations and 146 configuration names with versioned references. This is reproducible structural coverage, **not 100% documented capabilities or configuration semantics**. No semantic completeness percentage is claimed. Every row below remains partial until its source/capability inventory, normal/failure procedures and associated evals are reviewed and applicable behavior verified.

| ID | Capability/domain | Sources and current documentation | Audience | Eval seeds | Remaining coverage |
|---|---|---|---|---|---|
| K01 | Family profiles/notes/chores/rewards/schedule | [USER_GUIDE](../../USER_GUIDE.md); family controllers and Family Hub source in inventory | Household/operator | Q01,Q02 | Per-flow error handling, auth/version drift, cross-browser verification |
| K02 | Camera activity/media/privacy | User guide camera sections; CameraMediaController/EventController | Household/operator | Q03 | Integration-specific setup and unavailable-media paths |
| K03 | Device identity/configuration/lifecycle | DeviceController/DeviceLifecycleState; [operations](../user-guide/operations.md) | Operator | Q04,Q05 | Full per-type field and command semantics; actual UI walkthrough |
| K04 | Discovery and platform imports | DeviceDiscoveryController/PlatformImportController; operations | Operator | Q06,Q07 | Provider-by-provider prerequisites and negative-path testing |
| K05 | Rules/alerts/executions | RulesController/AlertController and workflow sources; operations | Operator | Q08 | Full trigger/action catalog, target/role checks and ownership map |
| K06 | Telemetry/history/signal quality | EventController/SignalQualityController and inventory | Operator | Q09 | Field mappings, freshness/retention oracles and empty/error cases |
| K07 | Presence/security | PresenceController/SecurityController; replication presence guidance | Role-dependent | Q09,Q10 | Per-signal behavior, consent, no-data and manual-state distinctions |
| K08 | Auth/managed users/guest scopes | Auth and security sources; user guide; configuration | Role-dependent | Q01,Q11 | Route-level policy matrix, all supported enrollment/recovery paths |
| K09 | Configuration/build/runtime bindings | [Configuration supplement](../user-guide/configuration.md), generated references | Installer/operator | Q12,Q13 | Purpose/requiredness/defaults/validation for every name; dynamic settings |
| K10 | Databases/events/storage/archive | Compose and backend repositories; maintenance | Operator | Q14 | Consistent backup/restore recipes and isolated recovery proof |
| K11 | HA/MQTT/Z2M/Node-RED/cameras | Compose service index, maintenance/replication; [Home collector evidence](home-collector-evidence.md) (2026-09-10/11 Termux/SLZB-MR5U bring-up, MQTT routing resolved end-to-end) | Installer/operator | Q15,Q16,CC-01 | Per-service prerequisites, host mounts, actual commands and restart boundaries |
| K12 | Monitoring/Grafana/Prometheus/Kuma | Replication health sections and source declarations | Operator | Q16 | Each monitor's claims, failures, persistence and supported restore |
| K13 | Build/release/rollback | Workflows; [maintenance supplement](../user-guide/maintenance.md) | Maintainer | Q17 | Validate selected-instance deployment/rollback and CI state |
| K14 | Independent installation | [Installation supplement](../user-guide/independent-installation.md), replication/Ansible | Installer | Q18,Q19,Q20 | Standalone supported overlay, all host assets, reproducible clean-room run |
| K15 | Ask/KB/Ollama | [Ask supplement](../user-guide/ask-and-helpdesk.md), Helpdesk/KB source | Role-dependent | Q21,Q22,Q23 | Whole-corpus integration, precise citations, model/runtime baseline |
| K16 | Ontology/provenance | Existing decisions/context, KnowledgeNode/type sources | Operator/contributor | Q04,Q22 | Reviewed document reference/section mapping; no new decision dependency |
| K17 | Credentials/vault | Configuration, Ansible/maintenance and CredentialPointerRedactor | Admin/restricted | Q11,Q13 | Role-filtered document projection and safe recovery proof |
| K18 | Shared contribution/governance | [Contribution procedure](../contributing.md), GitHub/PR #34 | Authorized collaborators | Q24 | Future in-app workflow and knowledge refresh implementation |
| K19 | Opportunities/Tech ID | Opportunities/Tech ID controllers; product notes and roadmap | Operator/contributor | Q25 | Supported providers, status semantics and actual outcomes |
| K20 | Remaining routes/services and optional surfaces | Full generated inventory; app panels | Scope-dependent | Derive before closure | Assign every declaration to a capability, review conditional/obsolete paths; regex index is not the final manifest |

## Gap register

The [source-answer set](eval-answer-evidence.md) now answers all 24 informational Q seeds and defines the Q20 execution oracle using existing code, manuals and [local Claude evidence](local-history-evidence.md). This closes “answers have not been located” for those seeds; it does not close their unexecuted model/live checks or claim full domain coverage. Recovered findings are available for immediate baseline work. Remaining gaps below are refinement work, not a global corpus-completeness gate.

All entries are OPEN unless explicitly closed with evidence. The shared reviewer assigns responsibility; these are not private agent work queues.

| Gap | Finding | Next evidence/deliverable | Blocks |
|---|---|---|---|
| G01 | 146 configuration references are names, not reviewed installer semantics. | Per-name purpose, requirement, safe default, binding, audience, validation and lifecycle; dynamic settings audit. | Complete configuration/automated-input claims |
| G02 | M920q overlay expects an existing network/support stack and host-specific assets. | Reviewed standalone arrangement and host asset checklist for selected services. | Clean-host install claim, not general documentation |
| G03 | Bootstrap script is development tooling, not a complete installer. | Separate host prerequisites/account steps; verified installation automation if approved. | One-command recreation claim |
| G04 | Full-platform consistent backup/restore has not been rehearsed here; existing Kuma full-data backup guidance and health/notification runbooks are located and linked in Q14/Q16. | Extend the existing component guidance to selected services, secure custody and isolated replacement-host restore evidence. | Disaster-recovery completion |
| G05 | Intended WEBUI_AUTH setting differs from checked-in overlay. | Record instance-specific intended/current setting through authorized verification; no speculative runtime change. | That access-configuration assertion only |
| G06 | Safety-procedure curation is historically incomplete. | Operator-supplied reviewed procedures and applicability; validate current curated KB safely. | Unsupported safety instructions only |
| G07 | Raw KB listing carve-out and pointer redaction do not prove full document audience filtering. | Reviewed role/source projection and denial tests before broad corpus exposure. | Broad household ingestion/exposure |
| G08 | Current model/digest, deployed SHA and answer baseline unverified. | Authorized read-only live verification and paired question results. | Live assistant improvement claim |
| G09 | Generated source index is structural; some consumers/mappings are dynamic. | Review every route/service/config declaration, UI-only capability and dynamic binding; maintain per-capability oracles. | Full semantic coverage claim |
| G10 | Shared in-app contribution/refresh controls are a target, not current behavior. | Separately reviewed workflow implementation and E2E tests. | In-app governance completion, not repository collaboration |

The unavailable handover/D13–D16 are not global gaps blocking these tasks. Record a specific unsupported assertion only if it actually needs that evidence.

Two localized follow-ups recovered during the answer review: (1) Q02 exposes a local-only note recovery/replay gap in the current code; the safe preservation/resend guidance is documented now, and implementation can be proposed separately. (2) Claude history H02 relays **CANDIDATE persistence + D10 provenance tag** as one proposed WSJF item; current code confirms the early-candidate persistence issue. This applies to durable imports before their household use, not to general documentation or an unrelated assistant baseline. It is recorded as a proposal, without changing Cowork Ratification fields.

## Completion accounting

Freeze the reviewed capability list, count documented applicable normal/failure paths and report the denominator. A row with only a link is not “complete.” Source mapping, prose review, local eval, live verification and clean-room verification are separate statuses. Reuse existing canonical sections; close gaps through evidence-bearing PRs and retain superseded records for audit.
