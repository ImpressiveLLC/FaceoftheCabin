# C2 context-map wiring, 2026-10-27

Owner Code; review Cowork and Codex; no merge before both. Source: `docs/ai-assistant/rag/context-fixtures-r2.json` and its compiled copy `cabin-orchestration-platform/backend/src/main/resources/rag/context-fixtures-r2.json` (identical; a test compares them). No model call was made. Nothing was deployed, ingested or trained.

## Why

A guide section reaches Ask only through the context map. Sections written for C2 (K09 configuration, K10 backups, K11/K12 service health, K13 workflows, K15 Ask runtime) were not listed, so a C1 rerun would not have measured them. This change makes them reachable and keeps each category inside the per-question document budget (`ask.context.max-document-chars`, 6000 characters).

## What changed

| Change | Detail |
|---|---|
| `configuration_binding` (Q13) | Now supplies the per-name table, the HA-token validity section and the database-password rotation section (5,913 characters). Keywords added: `rotation`, `rotate`, `home_ha_token`, `cabin_ask_intent_enabled`, `cabin_ask_intent_model`. |
| `config_build_time_binding` (new, Q12) | The build-time trace table moved here (3,810 characters, boost 2, keywords `api url`, `vite`, `rebuild`, `build argument`, `host-config`, `running container`). Q12 keeps its knowledge nodes. This keeps the existing test that pins Q13 to `configuration_binding`. |
| `health_verification` (Q16) | The per-service table (4,151) is now first; the existing triage table (3,330) follows and is cut to about 1,849 characters by the budget. |
| `self_healing_recovery` (new) | The detect-versus-recover section (3,790). |
| `backup_restore_recipes` (new) | The K10 section (4,871). `backup` and `restore` stay with `persistence_recovery`; the new category uses multi-word phrases (`back up`, `pg_dump`, `database backup`, ...). |
| `config_passthrough` (new) | The pass-through section (2,089). |
| `ask_runtime_limits` (new) | The K15 section (4,328). |
| Guide headings | The detect-versus-recover subsection and the K10 recipe table were promoted from level three to level two, and the database-rotation and pass-through parts of the K09 slice-2 section were split into their own level-two sections, so each fits the budget alone. The Kuma/persistence paragraph that followed the K10 table was moved back under the persistence section. |
| `ContextMapWiringTest` | New. Fails when a mapped section no longer exists, when a category's first section exceeds the budget, when a category exceeds the budget without being a named exception (`health_verification`, `installation`, `live_sensors`), and checks the routing of eight probe questions and the audience gate. |

## Prompt characters per category

Reviewed-document characters delivered to an administrator and to a household role (a household role receives only `household` sections), after the budget rule: sections are added in map order, a section that does not fit is cut at a paragraph boundary, and one is skipped when fewer than 300 characters remain. Knowledge nodes and the fixed prompt text come on top and are not counted here. Computed from the guide files at this commit.

| Category | Admin chars | Household chars | Sections (full size) | Truncation for an administrator |
|---|---|---|---|---|
| `user_auth_role` | 1902 | 1902 | users-and-family-data 1902 | none |
| `note_sync` | 1902 | 1902 | users-and-family-data 1902 | none |
| `camera_capability` | 3100 | 3100 | capability-map 3100 | none |
| `device_identity` | 2792 | 0 | device-onboarding 2792 | none |
| `device_import_lifecycle` | 2792 | 0 | device-onboarding 2792 | none |
| `rules_workflows` | 1070 | 0 | rules-and-safety 1070 | none |
| `live_sensors` | 6000 | 6000 | capability-map 3100, presence-and-location-roles 4645 | presence-and-location-ro cut to ~2900 |
| `credential_redaction` | 1487 | 0 | credentials-and-access 1487 | none |
| `configuration_binding` | 5913 | 0 | per-name-reference-ask-home-assist 3126, ha-token-validity-k09-slice-2 1290, database-password-rotation-k09-sli 1497 | none |
| `config_build_time_binding` | 3810 | 0 | trace-a-setting-to-its-consumer 3810 | none |
| `persistence_recovery` | 3246 | 0 | persistence-and-replacement-host-r 3246 | none |
| `installation` | 6000 | 0 | minimum-inputs 1488, verified-clean-host-limitations 2080, presence-for-each-location 2782 | presence-for-each-locati cut to ~2432 |
| `health_verification` | 6000 | 0 | running-versus-healthy-per-service 4151, first-determine-what-is-failing 3330 | first-determine-what-is- cut to ~1849 |
| `deployment_source` | 5794 | 0 | deployment-source-correction 5794 | none |
| `ask_helpdesk_behavior` | 4570 | 4570 | current-behavior 2033, what-a-source-currently-means 1568, questions-and-useful-limits 969 | none |
| `governance_contribution` | 2493 | 2493 | small-auditable-changes 1445, contribution-record 1048 | none |
| `opportunity_status` | 3100 | 3100 | capability-map 3100 | none |
| `home_collector_termux` | 0 | 0 | none | none |
| `environmental_nodes` | 4881 | 3784 | ordered-equipment-and-current-scop 1115, what-the-devices-can-and-cannot-es 1257, year-round-circulation-and-tempera 833, setup-and-maintenance 579, ask-behavior-and-execution-boundar 1097 | none |
| `self_healing_recovery` | 3790 | 0 | what-detects-and-what-recovers 3790 | none |
| `backup_restore_recipes` | 4871 | 0 | backup-and-restore-recipes-per-sto 4871 | none |
| `config_passthrough` | 2089 | 0 | settings-that-never-reach-cabin-ba 2089 | none |
| `ask_runtime_limits` | 4328 | 0 | model-runtime-prompt-size-and-whic 4328 | none |

Three categories exceed the budget today and truncate a later section: `live_sensors`, `installation` and `health_verification`. `deployment_source` is 5,794 and `configuration_binding` is 5,913, so a few lines added to either would start cutting them.

## Ollama context window: NOT_MEASURED

The backend sends no `num_ctx`; the model's declared maximum is 131072 tokens; the window Ollama applies to an Ask request was not measured, and no prompt-length log line was present when the backend log was read. The 6000-character budget (about 1,500 tokens) is a margin, not a measured fit. A measurement needs a model call and is outside this change.

## Sections still not mapped

These 14 sections are not reachable by Ask and were left alone because they are not in this change's scope. The K13 workflow table is a level-three part of the mapped deployment section, so it is reachable.

- `ask-and-helpdesk.md` `#d28-naming-and-the-related-to-tag--not-yet-in-the-knowledge-base` (804 characters)
- `ask-and-helpdesk.md` `#add-or-correct-knowledge` (1035 characters)
- `configuration.md` `#explicit-source-disagreement` (1236 characters)
- `configuration.md` `#new-device-classes-not-yet-configurable-2026-10-16` (624 characters)
- `configuration.md` `#remaining-configuration-work` (404 characters)
- `independent-installation.md` `#establish-the-actual-installation-plan` (1985 characters)
- `independent-installation.md` `#acceptance-record` (1487 characters)
- `maintenance.md` `#a-camera-is-not-delivering-frames` (1862 characters)
- `maintenance.md` `#new-hardware-pending-install-2026-10-16` (839 characters)
- `maintenance.md` `#a-zigbee-plug-or-sensor-is-listed-twice-charts-twice-or-shows-power-as-unknown-w-88-2026-10-21` (3033 characters)
- `maintenance.md` `#close-the-incident` (459 characters)
- `operations.md` `#products-and-responsibility` (709 characters)
- `contributing.md` `#doc-27-environmental-content` (848 characters)
- `contributing.md` `#future-in-app-workflow` (414 characters)

## Risks and order

- This edits the r2 map in place (a new entry in `r2Changes`) rather than creating r3. The deployed r2 behaviour of the earlier runs stays in Git history; a rerun must record the deployed commit. Cowork can ask for an r3 copy instead.
- The compiled map changes, so a merge touching `backend/**` starts the backend deploy workflow (tests gate it, health check and rollback follow).
- The wiring depends on the headings and section text of #281, #282, #283, #284 and #287. Merge those first. On this branch they are merged in so the tests run against the real sections; after they land, this PR's diff reduces to the map, the test, the heading changes, the backlog row and the evidence.
- A rotated-or-renamed heading breaks the mapping silently at runtime (the loader logs a warning and Ask runs without it); `ContextMapWiringTest` catches that in CI.
