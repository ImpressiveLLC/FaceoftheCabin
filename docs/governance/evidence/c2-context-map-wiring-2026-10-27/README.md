# C2 context-map wiring, 2026-10-27

Owner Code; review Cowork and Codex; no merge before both, and Cowork merges only with a Codex CLEAN on the final head and Nate's go. Source: `docs/ai-assistant/rag/context-fixtures-r2.json` and its compiled copy `cabin-orchestration-platform/backend/src/main/resources/rag/context-fixtures-r2.json` (identical; a test compares them). No model call was made. Nothing was deployed, ingested or trained.

## Why

A guide section reaches Ask only through the context map. The sections written for C2 (K09 configuration, K10 backups, K11/K12 service health, K13 workflows, K15 Ask runtime) were not listed, so a C1 rerun would not have measured them. Nate's direction on 2026-10-27 added a second requirement: everything must fit the per-question document budget (`ask.context.max-document-chars`, 6000 characters) without cutting guide text, and must stay extractable in full. The first draft left three categories over the budget; this version has none.

## How everything fits without losing text

No sentence of any guide was deleted or reworded. Four techniques, in order of use:

1. **Split by intent (gaps in other streams).** Content that overflowed one category moves to a sibling category that carries the other half of the question space, with its own keywords. The health triage table moved from `health_verification` to `health_triage`; the presence roles and open items moved from `live_sensors` to `presence_roles_and_gaps`; the build-time table moved from `configuration_binding` to `config_build_time_binding`.
2. **Promote implied structure to headings.** The four bold paragraph labels of the presence section in `operations.md` became level-three headings, so each can be mapped on its own. The detect-versus-recover subsection, the backup recipes and the three parts of the K09 slice-2 section became level-two sections for the same reason. Headings only; the text is unchanged.
3. **Reference-style links.** In three sections of `independent-installation.md`, inline links with long repository paths became reference links (`[text][inst-3]`) whose targets sit in a final `Link targets` section of the same file. The wording is unchanged and every target is still in the file; the sections shrank by 431 and 233 characters, which brought `installation` under the budget.
4. **A codified digest.** `Service health at a glance` (1,723 characters) restates, as four labelled lines, what the triage table and the per-service table say about the meanings of healthy, the healthchecks, recovery and first triage. It adds no claim; each line points to the section that holds the full detail. It is delivered first with the per-service table, so Q16 gets the triage rows that matter and the full table, and the full triage table is delivered whole to the questions that ask about it.

## What changed in the map

| Category | Change |
|---|---|
| `configuration_binding` (Q13) | Supplies the per-name table, HA-token validity and rotation sections. Keywords added: `rotation`, `rotate`, `home_ha_token`, `cabin_ask_intent_enabled`, `cabin_ask_intent_model`. |
| `config_build_time_binding` (new, Q12) | The build-time trace table (boost 2). Keeps the existing test that pins Q13 to `configuration_binding`. |
| `health_verification` (Q16) | Digest plus the per-service table, both whole. |
| `health_triage` (new) | The triage table, whole. |
| `live_sensors` | Capability map, presence badge and arming paragraphs, whole. Q10 keeps its route. |
| `presence_roles_and_gaps` (new) | Location roles and what is not settled, whole. |
| `self_healing_recovery`, `backup_restore_recipes`, `config_passthrough`, `ask_runtime_limits` (new) | One section each. |
| `ContextMapWiringTest` (new) | Fails when a mapped section no longer exists, when a first section exceeds the budget, or when any category exceeds the budget; the list of allowed exceptions is empty. Checks twelve probe questions and the audience gate. |

Eval questions by half of the former `health_verification` section: Q16 depends on the per-service table and the digest (which carries the triage rows about a running container); no frozen question routes to `health_triage`, so none depends on the triage table alone.

## Prompt characters per category

Reviewed-document characters delivered to an administrator and to a household role (a household role receives only `household` sections), after the budget rule: sections are added in map order, a section that does not fit is cut at a paragraph boundary, and one is skipped when fewer than 300 characters remain. Knowledge nodes and the fixed prompt text come on top and are not counted here. Computed from the guide files at this commit.

| Category | Admin chars | Household chars | Sections (full size) | Truncation for an administrator |
|---|---|---|---|---|
| `user_auth_role` | 1902 | 1902 | users-and-family-data 1902 | none |
| `note_sync` | 1902 | 1902 | users-and-family-data 1902 | none |
| `camera_capability` | 3100 | 3100 | capability-map 3100 | none |
| `device_identity` | 4162 | 0 | device-onboarding 4162 | none |
| `device_import_lifecycle` | 4162 | 0 | device-onboarding 4162 | none |
| `rules_workflows` | 4879 | 0 | rules-and-safety 1070, rules-and-alerts-trigger-and-action-catalog-k05 3809 | none |
| `live_sensors` | 5233 | 5233 | live-values-and-ask 441, capability-map 3100, what-the-presence-badge-means 961, arming 731 | none |
| `credential_redaction` | 1928 | 0 | credentials-and-access 1928 | none |
| `configuration_binding` | 5913 | 0 | per-name-reference-ask-home-assist 3126, ha-token-validity-k09-slice-2 1290, database-password-rotation-k09-sli 1497 | none |
| `config_build_time_binding` | 3810 | 0 | trace-a-setting-to-its-consumer 3810 | none |
| `persistence_recovery` | 3246 | 0 | persistence-and-replacement-host-r 3246 | none |
| `installation` | 4562 | 0 | minimum-inputs 2131, verified-clean-host-limitations 2431 | none |
| `installation_presence` | 2351 | 0 | presence-for-each-location 2351 | none |
| `health_verification` | 5874 | 0 | service-health-at-a-glance 1723, running-versus-healthy-per-service 4151 | none |
| `deployment_source` | 5922 | 0 | deployment-source-correction 5922 | none |
| `ask_helpdesk_behavior` | 5478 | 5478 | current-behavior 2033, what-a-source-currently-means 2042, questions-and-useful-limits 1403 | none |
| `governance_contribution` | 3030 | 3030 | small-auditable-changes 1982, contribution-record 1048 | none |
| `opportunity_status` | 5973 | 3100 | opportunities-and-the-tech-id-service-k19 2873, capability-map 3100 | none |
| `home_collector_termux` | 0 | 0 | none | none |
| `environmental_nodes` | 4881 | 3784 | ordered-equipment-and-current-scop 1115, what-the-devices-can-and-cannot-es 1257, year-round-circulation-and-tempera 833, setup-and-maintenance 579, ask-behavior-and-execution-boundar 1097 | none |
| `self_healing_recovery` | 3790 | 0 | what-detects-and-what-recovers 3790 | none |
| `backup_restore_recipes` | 4871 | 0 | backup-and-restore-recipes-per-sto 4871 | none |
| `config_passthrough` | 2089 | 0 | settings-that-never-reach-cabin-ba 2089 | none |
| `ask_runtime_limits` | 4328 | 0 | model-runtime-prompt-size-and-whic 4328 | none |
| `health_triage` | 3330 | 0 | first-determine-what-is-failing 3330 | none |
| `presence_roles_and_gaps` | 2922 | 2922 | who-counts-location-roles-ratified 1273, what-is-not-settled 1649 | none |

C2 rewrite r1 (2026-10-27, see [the edit evidence](../c2-rewrite-edits-r1-2026-10-27/README.md)) changed the seven rows `device_identity`, `device_import_lifecycle`, `live_sensors`, `credential_redaction`, `installation`, `ask_helpdesk_behavior` and `governance_contribution` and added `installation_presence`.

No category is truncated. `deployment_source` (now 5,922 after the #283 rollback-wording fix), `configuration_binding` (5,913) and `health_verification` (5,874) are within about 130 characters of the cap, so a short addition to any of them would start cutting; the wiring test fails when that happens.

## Ollama context window: NOT_MEASURED

The backend sends no `num_ctx`; the model's declared maximum is 131072 tokens; the window Ollama applies to an Ask request was not measured, and no prompt-length log line was present when the backend log was read. The 6000-character budget (about 1,500 tokens) is a margin, not a measured fit. The first rerun must report truncation or errors separately. A measurement needs a model call and is outside this change.

K19 (2026-10-27) added the Opportunities and Tech ID Service section to `opportunity_status` ahead of the capability map (administrator only; a household role still receives the capability map alone).

## Sections still not reachable

14 of the 52 second- and third-level sections of the user guides and the contribution guide are not reachable by Ask. They were left alone because they are outside this change's scope.

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

- This edits the r2 map in place (a new entry in `r2Changes`), as Cowork ruled; no r3. A rerun records the map hash and the deployed commit.
- The compiled map changes, so a merge touching `backend/**` starts the backend deploy workflow (tests gate it; a health check and rollback follow) and changes live Ask context.
- The wiring depends on the headings and text of #281, #282, #283, #284 and #287. Merge those first; this branch has them merged in so the tests run against the real sections.
- Docs-only does not mean no live effect: the guides are mounted read-only into the backend, so once this map is live, a merged edit to a mapped section changes Ask context when the host checkout updates, with no backend deploy. The section sizes above are therefore a standing constraint on guide edits, not a one-time check.
- A renamed or removed heading breaks its mapping silently at runtime (the loader logs a warning and Ask runs without it); `ContextMapWiringTest` catches that in CI.
