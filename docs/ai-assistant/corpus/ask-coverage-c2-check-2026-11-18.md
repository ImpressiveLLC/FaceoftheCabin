# Ask config and maintenance coverage check (W-117 C2 slice 7, 2026-11-18)

Status: offline routing evidence only. Nothing was sent to Ask, Ollama or any endpoint; no model run, ingestion, training or host change. Requested by Cowork on #244 ([6977139472](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6977139472)).

## What was compared

- **Before C2:** the routing map at `main` b9eea9cd (before slices 1 to 3).
- **After C2:** the routing map at `main` f7459bd6, with slices 1 (#482), 2 (#487) and 3 (#490) merged. Slice 4 (#491) and later were not merged when this was measured and are not included.
- **Questions:** the 150 coverage-map phrasings ([phrasings.json](ask-coverage-tools/phrasings.json)) and 20 new plain-language config and maintenance questions written from the merged slices' sections ([ask-coverage-c2-check-2026-11-18.json](ask-coverage-c2-check-2026-11-18.json)). They are not from usage logs, the eval gold or any sealed or held-out set.
- **Tools:** [`route.py`](ask-coverage-tools/route.py) as requested, and the backend's own matcher (`AskContextBuilder.categoryIdFor` and `categoryIdsFor`) in a throwaway local probe that was not committed. `route.py` predates the W-151 matcher (wider word endings; tied categories all kept), so the backend result is the authority. Both gave the same first category for all 20 new questions; the backend also showed one tie (M04).

## Results

| Set | Measure | Before C2 | After C2 |
|---|---|---|---|
| 150 coverage-map phrasings | No category | 16 | 16 |
| 120 pinned phrasings (`routing-pins.json`) | Misrouted against their pin | 0 | 0 |
| 20 new config and maintenance questions | Reach the expected category | 1 | **13** |
| | No category | 6 | **1** |
| | Misrouted | 13 | **6** |

The C2 slices did not move any existing phrasing. On the new questions they take reaching the right section from 1 of 20 to 13 of 20.

## Per question (backend matcher)

| Id | Question | Expected | Before C2 | After C2 | After |
|---|---|---|---|---|---|
| C01 | What are BLINK_USERNAME and BLINK_PASSWORD used for? | `config_env_blink_camera` | no category | `config_env_blink_camera` | OK |
| C02 | A Blink camera's live view does not start; how is the Blink camera map set? | `config_env_blink_camera` | `camera_frames` | `config_env_blink_camera` | OK |
| C03 | SmartThings import fails; is VAULTWARDEN_URL set correctly? | `config_env_vaultwarden` | `device_add` | `config_env_vaultwarden` | OK |
| C04 | I changed WEATHER_ENABLED in .env and nothing happened. Why? | `config_env_weather` | `configuration_binding` | `config_env_weather` | OK |
| C05 | How do I change the MQTT URL the backend uses? | `config_env_addresses` | no category | `config_env_addresses` | OK |
| C06 | What does IMAGE_TAG control? | `config_env_instance` | no category | `config_env_instance` | OK |
| C07 | Where do invite links point, and which setting controls it? | `config_env_instance` | `managed_users` | `managed_users` | misrouted |
| M01 | How do I restore Postgres after a disk failure? | `recovery_restore` | `storage_disk` | `recovery_restore` | OK |
| M02 | I can't log in to the Node-RED editor. What now? | `recovery_nodered` | no category | no category | no category |
| M03 | Grafana shows a blank page outside the embedded panel; how do I sign in? | `recovery_grafana` | `user_auth_role` | `user_auth_role` | misrouted |
| M04 | What does the external dead-man monitor do if the hub goes silent? | `recovery_deadman` | `self_healing_recovery` | `self_healing_recovery` (tie; `recovery_deadman` also kept) | misrouted |
| M05 | How do I stop and restart the recovery heartbeat? | `recovery_checks` | `self_healing_recovery` | `self_healing_recovery` | misrouted |
| M06 | What does storage pruning delete and what does it keep? | `recovery_prune` | `storage_disk` | `recovery_prune` | OK |
| M07 | What can the monitoring key do over SSH? | `recovery_key` | no category | `recovery_key` | OK |
| I01 | How do I make someone an administrator? | `identity_admin` | no category | `identity_admin` | OK |
| I02 | How long does a sign-in last before I have to sign in again? | `identity_sessions` | `user_auth_role` | `user_auth_role` | misrouted |
| I03 | Why does the managed sign-in link work without signing in? | `identity_sign_in_link` | `managed_users` | `identity_sign_in_link` | OK |
| I04 | What scopes can a guest link have? | `identity_guest_link_checks` | `access_sharing` | `access_sharing` | misrouted |
| I05 | Where is each credential kept and how do I rotate a secret? | `credential_inventory` | `credential_redaction` | `credential_inventory` | OK |
| I06 | Where is the Home Assistant password? | `credential_redaction` | `credential_redaction` | `credential_redaction` | OK |

## The seven gaps after C2

Wording only. None of these is fixed here: this is a docs-only evidence slice and changes no routing.

- **C07** "Where do invite links point…": goes to `managed_users`, which covers sending invites but not `CABIN_FRONTEND_ORIGIN`. A bounded phrase such as "invite link point" on `config_env_instance` would need a check against the managed-user questions first.
- **M02** "I can't log in to the Node-RED editor": no category. `recovery_nodered` matches "node-red login" but not "log in to the Node-RED". Candidate: "log in to node-red", checked against credential-location questions (the #487 HOLD precedent).
- **M03** "Grafana shows a blank page… how do I sign in?": goes to `user_auth_role` on "sign in". `recovery_grafana` has "grafana blank" but not "grafana shows a blank".
- **M04** "external dead-man monitor": `self_healing_recovery` wins, but the tie keeps `recovery_deadman` as well, so its section is delivered when it fits the budget. A partial gap.
- **M05** "stop and restart the recovery heartbeat": goes to `self_healing_recovery`; `recovery_checks` has "heartbeat stop" and "heartbeat restart" but not "stop and restart the recovery heartbeat".
- **I02** "How long does a sign-in last…": goes to `user_auth_role`; `identity_sessions` has "session last", not "sign-in last".
- **I04** "What scopes can a guest link have?": goes to `access_sharing`; `identity_guest_link_checks` has "guest link scope", singular and adjacent, which this phrasing does not contain.

Each candidate phrase should be tried against the 120 pins, the credential-location negatives and the slice tests before it is added, in its own reviewed change.

## Reproduce

From the repository root:

```
python3 -I -c "import json;[print(f'{t}-{i}|{q}') for t,qs in json.load(open('docs/ai-assistant/corpus/ask-coverage-tools/phrasings.json')) for i,q in enumerate(qs,1)]" > /tmp/phr.txt
python3 -I -c "import json;[print(q['id']+'|'+q['question']) for q in json.load(open('docs/ai-assistant/corpus/ask-coverage-c2-check-2026-11-18.json'))['questions']]" > /tmp/c2.txt
git show b9eea9cd:docs/ai-assistant/rag/context-fixtures-r2.json > /tmp/before.json
git show f7459bd6:docs/ai-assistant/rag/context-fixtures-r2.json > /tmp/after.json
for f in before after; do python3 -I docs/ai-assistant/corpus/ask-coverage-tools/route.py /tmp/$f.json /tmp/phr.txt | grep -c "NO CATEGORY"; python3 -I docs/ai-assistant/corpus/ask-coverage-tools/route.py /tmp/$f.json /tmp/c2.txt; done
```
