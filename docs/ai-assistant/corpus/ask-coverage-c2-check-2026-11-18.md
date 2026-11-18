# Ask config and maintenance coverage check (W-117 C2 slice 7, 2026-11-18)

Status: offline routing evidence only. Nothing was sent to Ask, Ollama or any endpoint; no model run, ingestion, training or host change. Requested by Cowork on #244 ([6977139472](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6977139472)).

## What was compared

- **Before C2:** the routing map at `main` b9eea9cd (before slices 1 to 3).
- **After C2:** the routing map at `main` f7459bd6, with slices 1 (#482), 2 (#487) and 3 (#490) merged. Slice 4 (#491) and later were not merged when this was measured and are not included.
- **Questions:** the 150 coverage-map phrasings ([phrasings.json](ask-coverage-tools/phrasings.json)) and 20 new plain-language config and maintenance questions written from the merged slices' sections ([ask-coverage-c2-check-2026-11-18.json](ask-coverage-c2-check-2026-11-18.json)). They are not from usage logs, the eval gold or any sealed or held-out set.
- **Tools:**
  - [`route.py`](ask-coverage-tools/route.py), as requested: an offline replica of the routing that predates the W-151 matcher (wider word endings; tied categories all kept).
  - The backend's own `AskContextBuilder`, the authority, run in a throwaway local probe that was not committed. It reports the first category (`categoryIdFor`), every selected category including ties (`categoryIdsFor`), and the sections delivered to an administrator under the 6000-character budget (`buildContext(question, ADMINISTRATOR)`).
  - The pins are [`routing-pins.json`](../../../cabin-orchestration-platform/backend/src/test/resources/rag/routing-pins.json).

## Results

There are three different measures. Do not read them as one.

- **First category:** the top-scoring category equals the expected one. `route.py` and the backend agree on all 20.
- **Selected set:** the expected category is among the categories kept, ties included (backend only).
- **Delivered:** an administrator receives the expected category's guide section whole, untruncated, within 6000 characters (backend only). This is the one that says whether the right text reaches the model.

| Set | Measure | Before C2 | After C2 |
|---|---|---|---|
| 150 coverage-map phrasings | No category (`route.py`) | 16 | 16 |
| 120 pinned phrasings | Misrouted against their pin (`route.py`) | 0 | 0 |
| 20 new questions | First category is the expected one | 1 | **13** (6 other first, 1 none) |
| | Expected category in the selected set | 1 | **14** (5 wrong selection, 1 none) |
| | Expected section delivered whole to an administrator | 1 | **13** (7 not delivered) |

The C2 slices did not move any existing phrasing. For the 20 new questions, delivery of the expected section goes from 1 of 20 to 13 of 20. Here those 13 are the same questions that match on first category. The tie on M04 adds a category but no delivered text, so it does not count as delivered.

## Per question (backend)

"First" is `categoryIdFor`; a tied expected category is shown as "+ … tied". "Delivered" means the expected section reached an administrator whole.

| Id | Question | Expected | Before: first | Before: delivered | After: first | After: delivered |
|---|---|---|---|---|---|---|
| C01 | What are BLINK_USERNAME and BLINK_PASSWORD used for? | `config_env_blink_camera` | none | no | `config_env_blink_camera` | yes |
| C02 | A Blink camera's live view does not start; how is the Blink camera map set? | `config_env_blink_camera` | `camera_frames` | no | `config_env_blink_camera` | yes |
| C03 | SmartThings import fails; is VAULTWARDEN_URL set correctly? | `config_env_vaultwarden` | `device_add` | no | `config_env_vaultwarden` | yes |
| C04 | I changed WEATHER_ENABLED in .env and nothing happened. Why? | `config_env_weather` | `configuration_binding` | no | `config_env_weather` | yes |
| C05 | How do I change the MQTT URL the backend uses? | `config_env_addresses` | none | no | `config_env_addresses` | yes |
| C06 | What does IMAGE_TAG control? | `config_env_instance` | none | no | `config_env_instance` | yes |
| C07 | Where do invite links point, and which setting controls it? | `config_env_instance` | `managed_users` | no | `managed_users` | no |
| M01 | How do I restore Postgres after a disk failure? | `recovery_restore` | `storage_disk` | no | `recovery_restore` | yes |
| M02 | I can't log in to the Node-RED editor. What now? | `recovery_nodered` | none | no | none | no |
| M03 | Grafana shows a blank page outside the embedded panel; how do I sign in? | `recovery_grafana` | `user_auth_role` | no | `user_auth_role` | no |
| M04 | What does the external dead-man monitor do if the hub goes silent? | `recovery_deadman` | `self_healing_recovery` | no | `self_healing_recovery` (+ `recovery_deadman` tied) | no |
| M05 | How do I stop and restart the recovery heartbeat? | `recovery_checks` | `self_healing_recovery` | no | `self_healing_recovery` | no |
| M06 | What does storage pruning delete and what does it keep? | `recovery_prune` | `storage_disk` | no | `recovery_prune` | yes |
| M07 | What can the monitoring key do over SSH? | `recovery_key` | none | no | `recovery_key` | yes |
| I01 | How do I make someone an administrator? | `identity_admin` | none | no | `identity_admin` | yes |
| I02 | How long does a sign-in last before I have to sign in again? | `identity_sessions` | `user_auth_role` | no | `user_auth_role` | no |
| I03 | Why does the managed sign-in link work without signing in? | `identity_sign_in_link` | `managed_users` | no | `identity_sign_in_link` | yes |
| I04 | What scopes can a guest link have? | `identity_guest_link_checks` | `access_sharing` | no | `access_sharing` | no |
| I05 | Where is each credential kept and how do I rotate a secret? | `credential_inventory` | `credential_redaction` | no | `credential_inventory` | yes |
| I06 | Where is the Home Assistant password? | `credential_redaction` | `credential_redaction` | yes | `credential_redaction` | yes |

## The seven gaps after C2

Six selection gaps and one delivery gap. Nothing is fixed here: this is a docs-only evidence slice and changes no aliases, matcher or budget.

**Selection gaps.** The expected category is not selected, or another category is selected instead.
- **C07** "Where do invite links point…": goes to `managed_users`, which covers sending invites but not `CABIN_FRONTEND_ORIGIN`. A bounded phrase such as "invite link point" on `config_env_instance` would need a check against the managed-user questions first.
- **M02** "I can't log in to the Node-RED editor": no category. `recovery_nodered` matches "node-red login" but not "log in to the Node-RED". Candidate: "log in to node-red", checked against credential-location questions (the #487 HOLD precedent).
- **M03** "Grafana shows a blank page… how do I sign in?": goes to `user_auth_role` on "sign in". `recovery_grafana` has "grafana blank" but not "grafana shows a blank".
- **M05** "stop and restart the recovery heartbeat": goes to `self_healing_recovery`. `recovery_checks` has "heartbeat stop" and "heartbeat restart" but not "stop and restart the recovery heartbeat".
- **I02** "How long does a sign-in last…": goes to `user_auth_role`. `identity_sessions` has "session last", not "sign-in last".
- **I04** "What scopes can a guest link have?": goes to `access_sharing`. `identity_guest_link_checks` has "guest link scope" (singular, adjacent), which this phrasing does not contain.

**Delivery gap.**
- **M04** "external dead-man monitor": `self_healing_recovery` scores highest, and the tie also keeps `recovery_deadman`. The first category's section (`maintenance.md#what-detects-and-what-recovers`, 4648 characters) leaves too little of the 6000-character budget, so `operations-recovery-reference.md#external-dead-man-monitoring` is not delivered. The routing tie holds, but the section the person asked about does not reach the model.

Each candidate should be tried against the 120 pins, the credential-location negatives and the slice tests before it is added, in its own reviewed change.

## Reproduce

From the repository root:

```
python3 -I -c "import json;[print(f'{t}-{i}|{q}') for t,qs in json.load(open('docs/ai-assistant/corpus/ask-coverage-tools/phrasings.json')) for i,q in enumerate(qs,1)]" > /tmp/phr.txt
python3 -I -c "import json;[print(q['id']+'|'+q['question']) for q in json.load(open('docs/ai-assistant/corpus/ask-coverage-c2-check-2026-11-18.json'))['questions']]" > /tmp/c2.txt
git show b9eea9cd:docs/ai-assistant/rag/context-fixtures-r2.json > /tmp/before.json
git show f7459bd6:docs/ai-assistant/rag/context-fixtures-r2.json > /tmp/after.json
for f in before after; do python3 -I docs/ai-assistant/corpus/ask-coverage-tools/route.py /tmp/$f.json /tmp/phr.txt | grep -c "NO CATEGORY"; python3 -I docs/ai-assistant/corpus/ask-coverage-tools/route.py /tmp/$f.json /tmp/c2.txt; done
```

These commands reproduce the `route.py` numbers (replica, first category only). For the selected set and delivery numbers, use the backend:

1. Build `AskContextBuilder` the way `AskIdentityCredentialsRoutingTest` does: a no-op `KnowledgeNodeRepository`, `ReviewedDocumentSource` over `docs/`, budget 6000. Point the fixture path at `file:/tmp/before.json` or `file:/tmp/after.json`.
2. For each question, record `categoryIdFor` (first) and `categoryIdsFor` (selected set).
3. Call `buildContext(question, HouseholdRole.ADMINISTRATOR)`. Count a question as delivered when one returned node's `entityRef` ends with the expected category's `path#section` and its content does not contain "section truncated".

The probe used for this file was a throwaway test that was not committed.
