# Configuration and credential handling

Audience: installer/operator. Evidence: pinned repository source; actual deployed settings unverified. This document never asks the assistant to read or print raw credential values.

## Trace a setting to its consumer

Use the [configuration name/source inventory](../corpus/source-inventory.md#configuration-references). It lists references, not a complete semantic configuration schema. An environment variable name appearing in source does not prove it is required, wired into Compose, used at runtime or safe to expose.

For each selected feature, trace: declaration → template/Compose/build argument → application binding → actual consumer → validation. Record whether the setting is required, conditional or optional; safe default if nonsecret; reload/rebuild requirement; audience; and verification. Unknown entries remain open in [coverage](../corpus/coverage.md), not fabricated defaults.

| Setting/group | Verified purpose and binding | Operational implication |
|---|---|---|
| `VITE_CABIN_API_BASE`, `VITE_CABIN_WS_BASE`, `VITE_CABIN_*_URL`, `VITE_CABIN_GOOGLE_CLIENT_ID` | [UI Dockerfile](../../../cabin-orchestration-platform/ui/Dockerfile) declares build arguments and exports them before `npm run build`. | These are compiled UI settings. Changing a running container's environment alone does not rebuild its JavaScript. Use your own approved endpoints; never place secret values in browser build arguments. |
| `GOOGLE_CLIENT_ID`, `ADMIN_EMAILS`, `CABIN_API_URL` | [Family Hub Dockerfile](../../../family-hub/Dockerfile) writes browser `host-config.js` from build arguments. | Rebuild/redeploy the static app for changed host configuration, and verify the browser receives it. These are browser-visible values, not a credential vault. |
| `HA_TOKEN` | [HaTokenCabinHealthIndicator](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/health/HaTokenCabinHealthIndicator.java) binds it directly and marks health DOWN if blank. | Presence is necessary for this guard; a nonblank value does not validate authentication. Do not use the health guard as a token-validity test. |
| `POSTGRES_PASSWORD` | [Base Compose](../../../cabin-orchestration-platform/infra/docker-compose.yml) configures the database; [Ansible rotation](../../../ansible/playbooks/rotate-secrets.yml) handles coordinated changes. | Existing database credentials are not rotated by changing `.env` alone. Follow the reviewed rotation runbook, never print resolved config to compare secrets. |
| `cabin.ollama.url`, `cabin.ollama.model` | [OllamaHttpClient](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/helpdesk/OllamaHttpClient.java) uses these application properties. | The code default is `llama3.2:3b`; this is not verification of the active model/digest. Keep the installed model unchanged unless the operator authorizes a measured alternative. |
| `cabin.ollama.connect-timeout-seconds`, `cabin.ollama.read-timeout-seconds` (`CABIN_OLLAMA_CONNECT_TIMEOUT_SECONDS`, `CABIN_OLLAMA_READ_TIMEOUT_SECONDS`) | [OllamaHttpClient](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/helpdesk/OllamaHttpClient.java) binds them; [M920q Compose](../../../cabin-orchestration-platform/infra/docker-compose.m920q.yml) passes them through. | Defaults 3 s connect and 50 s read, kept below the 60 s nginx and 100 s Cloudflare limits so a stalled model ends in the raw-facts fallback; zero or negative falls back to the default. The Home Assistant adapter uses 3 s connect and 30 s read bounds (`cabin.ha.connect-timeout-seconds`, `cabin.ha.read-timeout-seconds`; not passed through Compose). A timeout on a Home Assistant service call means the outcome is unknown, not that it did not happen. |
| `CABIN_ASK_INTENT_ENABLED`, `CABIN_ASK_INTENT_MODEL` (`cabin.ask.intent.*`) | [HelpdeskProposeController](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/HelpdeskProposeController.java) and [IntentProposalService](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/assistant/IntentProposalService.java) bind them; Compose and `.env.m920q.example` pass them through. | Defaults: enabled `false`, model `cabin-intent-slot-prompt-r1`. While disabled, `POST /api/helpdesk/propose` answers 404 as if absent. When enabled it is administrator only, returns a proposal and never executes anything, and `/api/helpdesk/ask` is unchanged. Enabling it is a deployment decision, not a documentation step. |
| `VAULTWARDEN_*` | Backend bindings in [M920q Compose](../../../cabin-orchestration-platform/infra/docker-compose.m920q.yml); [Vaultwarden setup](../../MAINTENANCE.md). | Vault service admin access and backend CLI login/decryption are separate setup concerns. Only credential-pointer names may enter assistant knowledge. |

## Per-name reference: Ask, Home Assistant and database settings (K09, first slice)

Audience: installer/operator. Source-reviewed at `main`; the active runtime values are unverified and never shown here. Compose = [M920q Compose](../../../cabin-orchestration-platform/infra/docker-compose.m920q.yml); App = [application.yml](../../../cabin-orchestration-platform/backend/src/main/resources/application.yml).

| Name | Required? | Code default | Binding path | Visibility |
|---|---|---|---|---|
| `HA_TOKEN` | Needed for any Home Assistant read or command; health reports DOWN while blank | blank | Compose passes `${HA_TOKEN:-}`; App `token: ${HA_TOKEN:}` | Secret. Check presence only |
| `HOME_HA_TOKEN` | No. Blank on purpose: no Home Assistant exists at Home (D23) | blank | Compose `${HOME_HA_TOKEN:-}`; App line 68 | Secret. Leave blank |
| `POSTGRES_PASSWORD` | Yes, but Compose and App both fall back to `cabin_dev_password` when unset, so a missing value does not fail at startup | `cabin_dev_password` | Compose `${POSTGRES_PASSWORD:-cabin_dev_password}` (two services); App `password: ${POSTGRES_PASSWORD:cabin_dev_password}` | Secret. An unset value running on the dev default is a deployment defect, not a working configuration |
| `CABIN_ASK_INTENT_ENABLED` | No | `false` | Compose `${CABIN_ASK_INTENT_ENABLED:-false}`; `cabin.ask.intent.enabled` | Not secret. Off means `POST /api/helpdesk/propose` answers 404 |
| `CABIN_ASK_INTENT_MODEL` | Only when enabled | `cabin-intent-slot-prompt-r1` | Compose passes it; `cabin.ask.intent.model` | Not secret. Must name an Ollama tag that exists on the host |
| `CABIN_ASK_INTENT_PROPOSAL_TTL_SECONDS` | No | `300` | `cabin.ask.intent.proposal-ttl-seconds`. **Compose does not pass it** | Not secret |
| `CABIN_ASK_INTENT_MAX_INPUT_CHARS` | No | `500` | `cabin.ask.intent.max-input-chars`. **Compose does not pass it** | Not secret |
| `CABIN_ASK_INTENT_TIMEOUT_SECONDS` | No | `60` | `cabin.ask.intent.timeout-seconds`. **Compose does not pass it** | Not secret |
| `CABIN_ASK_RESOLVER_MAX_INVENTORY_AGE_SECONDS` | No | `120` | `cabin.ask.resolver.max-inventory-age-seconds`. **Compose does not pass it** | Not secret |
| `CABIN_ASK_RESOLVER_MAX_OBSERVATION_AGE_HOURS` | No | `6` | `cabin.ask.resolver.max-observation-age-hours`. **Compose does not pass it** | Not secret |

Reading the table:

- A name Compose does not pass reaches the container only if someone adds it to the service's `environment:`; putting it in `infra/.env` alone changes nothing. Until then the code default applies.
- A change to any of these is applied by recreating `cabin-backend`, not by editing the running container. Changing `POSTGRES_PASSWORD` in `.env` does not change the password the database already holds (see the rotation row above), so a backend started with a new value against an unchanged database fails to connect.
- A nonblank `HA_TOKEN` shows only that a value is present. It does not show the token is valid, unexpired or authorized for the entities in use.
- Not covered here: the other names in the inventory. This is a first slice of G01, not a complete registry.

## HA token validity (K09, slice 2)

Audience: installer/operator. Source-reviewed at `main`; nothing was run on the host (`NOT_RUN`). Values are never shown.

**Is `HA_TOKEN` valid?** The backend's health check only tests for blank. [`HaTokenCabinHealthIndicator`](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/health/HaTokenCabinHealthIndicator.java) returns DOWN when the value is blank or missing and UP otherwise, so `/actuator/health` can read UP with an expired, revoked or wrong token. When such a token is used, [`HomeAssistantAdapter`](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/integrations/homeassistant/HomeAssistantAdapter.java) does not fail loudly: it logs a warning (for example `HA fetch failed for <device>` or `HA discovery for '<location>' returned <status>`) and the affected Home Assistant devices stop updating. `DeviceHealthMonitor` then moves a polled device to OFFLINE only after an active poll also fails. So the evidence of a bad token is in the backend log warnings and in stale `lastSeen` values on Home Assistant devices, not in the health endpoint. A blank token logs `HA token not configured for location` and skips discovery. Home's token (`HOME_HA_TOKEN`) is blank on purpose.

## Database password rotation (K09, slice 2)

**What the automated rotation covers.** [`rotate-secrets.yml`](../../../.github/workflows/rotate-secrets.yml) runs the [Ansible playbook](../../../ansible/playbooks/rotate-secrets.yml) at 06:00 UTC on the 1st of each month, and on manual dispatch. It rotates the `cabin-postgres` password only. In order:

1. Preflight, with nothing live changed: the vault has non-empty `vault_postgres_password`, `vault_ha_token`, `vault_blink_username` and `vault_blink_password`; rendering both env templates would not blank a value the live file holds; the vault file has no uncommitted changes; the account can push to `main`.
2. Generate a new password, apply it to the running database, re-encrypt the vault, re-template `.env`, recreate `cabin-backend` and `cabin-grafana`, wait for `cabin-backend` to report healthy, and commit the re-encrypted vault to `main`.
3. If any step fails, it restores the old password in the live database, the committed vault file and `.env`, recreates the dependents, and fails the run.

`GRAFANA_PASSWORD` and `HA_TOKEN` are not rotated by this playbook. Each is a manual vault edit plus a matching change in the other system (Grafana's admin account, a new long-lived Home Assistant token). A rotation or token change that blanks `vault_ha_token` turns backend health DOWN on the next recreate, which the backend deploy treats as a failure. The 2026-10-01 half-finished rotation and its recovery are in the Maintenance guide.

## Settings that never reach cabin-backend (K09, slice 2)

Audience: installer/operator. Source-reviewed at `main`; nothing was run on the host (`NOT_RUN`).

**Which application settings never reach `cabin-backend`.** The M920q overlay's `cabin-backend` `environment:` block lists the names it sets. Of the 52 environment names `application.yml` reads, 28 do not appear in that block, so a value placed in `infra/.env` for any of them is ignored and the code default applies:

| Group | Names with no pass-through |
|---|---|
| Storage monitor | `STORAGE_MONITOR_ENABLED`, `STORAGE_MONITOR_MOUNTS`, `STORAGE_WARN_PERCENT` (default 60), `STORAGE_CRITICAL_PERCENT` (75), `STORAGE_CLEAR_MARGIN_PERCENT` (3), `STORAGE_RENOTIFY_HOURS` |
| Telemetry archival | `TELEMETRY_ARCHIVAL_ENABLED`, `TELEMETRY_ARCHIVAL_CRON`, `TELEMETRY_ARCHIVAL_DIR`, `TELEMETRY_ARCHIVAL_HOT_MONTHS` (36) |
| Opportunities | `OPPORTUNITIES_ENABLED`, `OPPORTUNITIES_CRON`, `OPPORTUNITIES_LOOKBACK_DAYS`, `OPPORTUNITIES_CONTINUOUS_DRAW_HOURS`, `OPPORTUNITIES_OFF_THRESHOLD_WATTS` |
| History and presence | `CABIN_HISTORY_MAX_DAYS` (60), `CABIN_DEMO_MAX_HISTORY_DAYS`, `CABIN_PRESENCE_STALE_AFTER_HOURS` (6) |
| Camera reconciliation | `CAMERA_RECONCILIATION_BACKFILL_DAYS`, `CAMERA_RECONCILIATION_OVERLAP_SECONDS`, `CAMERA_RECONCILIATION_PAGE_LIMIT` |
| Other | `BLINKBRIDGE_URL`, `FRIGATE_URL`, `FAMILY_DASHBOARD_URL`, `ONTOLOGY_PATH`, `GOOGLE_CALENDAR_ID`, `GOOGLE_CLIENT_SECRET`, `CABIN_EMAIL_FROM` |

Two consequences. The documented storage thresholds (60% and 75%) and presence staleness (6 hours) are the code defaults, so they hold only while nobody expects `.env` to change them; to change one, the name must first be added to the service's `environment:` in a reviewed Compose change and the container recreated. The `FRIGATE_URL` in the table is not the Frigate location setting the overlay does pass (`CABIN_LOCATIONS_CABIN_FRIGATE_URL`). The list is from a name comparison of the two files; a name may also be set by another route, such as a Spring profile, so confirm before treating one as unconfigurable.

## Credentials and access

**Short answer.** For an administrator, Ask can return the name of the vault entry, not the secret itself; other roles are directed to an administrator. Credentials live in Vaultwarden, the application credential vault; Ansible Vault is the separate mechanism for deployment secrets. Do not paste a secret into Ask or treat it as a place to enter or rotate one. This describes the current redactor, not verified enforcement for every role.

Use the current [Ansible secret provisioning/rotation guide](../../../ansible/README.md) and [Vaultwarden runbook](../../MAINTENANCE.md). The repository describes Ansible Vault as deployment-secret provisioning and Vaultwarden as the application credential vault. A pointer to an entry is not a replacement for provisioning the service securely; do not collapse these two mechanisms into an invented single implementation.

The current credential redactor permits an administrator to receive a vault entry name and directs other roles to an administrator. It runs before Helpdesk prompting/fallback/source return and on KB listing responses. It is not a general topology/privacy classifier for every other chunk type. Source: [CredentialPointerRedactor](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/devices/model/CredentialPointerRedactor.java), [TinyHelpdeskService](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/helpdesk/TinyHelpdeskService.java), [KnowledgeNodeController](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/KnowledgeNodeController.java).

Do not paste `.env`, decrypted vault output, full resolved Compose output, session credentials or provider responses into the assistant. Check presence or a safe status signal when debugging. Secret entry/rotation belongs in the approved secure mechanism; it is not a conversational field.

## Environment variable reference, first extension (W-117 C2 slice 1)

Audience: installer/operator. Evidence: source review only at `main` (`b9eea9cd`); the deployed values are unverified and never shown here. This extends the per-name table above to the names that table does not cover, in five groups below. App = [application.yml](../../../cabin-orchestration-platform/backend/src/main/resources/application.yml); Compose = [M920q Compose](../../../cabin-orchestration-platform/infra/docker-compose.m920q.yml); Production stack = [production-stack Compose](../../../cabin-orchestration-platform/infra/production-stack/docker-compose.yml). A name found in source does not prove it is set or used on the live host. Nothing here asks for a secret value: check presence only.

## Reference: Blink and camera settings

| Name | Required? | Code default | Binding path | Visibility and effect |
|---|---|---|---|---|
| `BLINK_USERNAME` | Yes for the `blinkbridge` service; Compose substitutes a blank with a warning when unset | none | Production stack line 141, passed to `blinkbridge` | Account name for Blink's cloud. Blink sign-in fails while blank |
| `BLINK_PASSWORD` | Same | none | Production stack line 142 | Secret. Check presence only |
| `BLINK_CAMERA_MAP` | No | App line 120 and Compose line 250 each set a default pair list | `cabin.devices.cameras.blinkCameraMap`; comma-separated `cabinCameraName:blinkDeviceName` pairs | Not secret. A camera not in the map gets no live view start or stop (a harmless no-op). Source disagreement: the App comment says "Empty by default" but the value on that line is not empty |
| `BLINK_MOTION_WEBHOOK_API_KEY` | Only to accept Blink motion posts | blank | App line 181; Compose line 256 | Secret. While blank, `POST /api/webhooks/blink-motion` answers 503; a wrong key answers 401 |
| `CAMERA_PASSWORD` | Yes for Frigate's RTSP login | none | Production stack lines 87 and 88; the example file for that stack uses the same name as `infra/.env.m920q.example` but is a separate file | Secret. Check presence only |

Apply a change by recreating the named service. Blink credentials and the camera map are separate layers: a correct password does not create a map entry.

## Reference: Vaultwarden and credential settings

| Name | Required? | Code default | Binding path | Visibility and effect |
|---|---|---|---|---|
| `VAULTWARDEN_URL` | Only for SmartThings or Ring platform import | App blank; Compose supplies the in-network service address | App line 150; Compose line 306 | Not secret. A wrong URL makes platform import fail loudly, not silently |
| `VAULTWARDEN_CLIENT_ID` | Same | blank | App line 151; Compose line 307 | Organization API key id. Not shown to the assistant |
| `VAULTWARDEN_CLIENT_SECRET` | Same | blank | App line 152; Compose line 308 | Secret. Check presence only |
| `VAULTWARDEN_MASTER_PASSWORD` | Same | blank | App line 157; Compose line 309 | Secret, and distinct from the API key: the key only skips two-step login, the master password derives the decryption key |
| `VAULTWARDEN_ADMIN_TOKEN` | Only for the Vaultwarden admin page | blank | Compose line 534 as the Vaultwarden service's own `ADMIN_TOKEN`; `cabin-backend` does not read it | Secret |
| `RESEND_API_KEY` | Only to email a managed-user invite | blank | App line 87; Compose line 261 | Secret. While blank, inviting a managed user fails loudly; a sign-in link can still be copied |

The one-time Vaultwarden Organization and API key setup is in the maintenance guide. Treat any request to read these values as a credential question: see Credentials and access above.

## Reference: weather settings

| Name | Required? | Code default | Binding path | Visibility and effect |
|---|---|---|---|---|
| `WEATHER_ENABLED` | No | `true` | App line 208 | `false` switches the daily airport weather pull off; the chart then shows no outdoor baseline for new days |
| `WEATHER_STATIONS` | No | the code default lists the cabin location only | App line 209 | Not secret. Format is `location=primary,fallback,...`, one entry per location, primary station first. A location with no entry gets no baseline, so another location needs its own entry |
| `WEATHER_BACKFILL_DAYS` | No | `60` | App line 210 | How far back a first run fills |
| `WEATHER_ARCHIVE_DIR` | No | the container path of the weather archive mount | App line 211 | Where the raw and monthly CSV logs go (cold tier) |
| `WEATHER_CRON` | No | daily, early morning | App line 212 | Six-field Spring cron, evaluated in the JVM time zone |

Compose does not pass any of these five, so editing `infra/.env` alone changes nothing; the code defaults apply until Compose adds them to the service's `environment:`.

## Reference: Home Assistant, MQTT and Kafka addresses

| Name | Required? | Code default | Binding path | Visibility and effect |
|---|---|---|---|---|
| `HA_URL` | Yes for any Home Assistant call | local address | App line 62. Compose line 194 sets a fixed LAN address, not `${HA_URL}` | Not secret. Home Assistant runs on the host network, so containers reach it by LAN or tailnet address, not container name. Editing `.env` does not change it |
| `HOME_HA_URL` | No | a `home-hub` address that is not live | App line 67; Compose line 196 | Not secret. Leave unset: no Home Assistant exists at Home (D23) |
| `HOME_HA_DEPLOYED` | No | `false` | Compose line 198 | Not secret. `false` means Home Assistant at Home is reported out of service by design, not as an outage |
| `HOME_HUB_DEPLOYED` | No | `false` | Compose line 198, the old name still read for one release | Same flag, renamed in W-60 |
| `MQTT_URL` | Yes | local broker address | App line 32; Compose line 186 fixes it to the in-network broker | Not secret |
| `KAFKA_BOOTSTRAP` | Yes | local address | App line 51; Compose line 199 fixes it to the in-network broker | Not secret. Kafka or MQTT failures at startup log warnings; the app still starts |

Where Compose fixes a value, the `.env` file cannot override it. Change the Compose line and recreate the service.

## Reference: instance, ports and image settings

| Name | Required? | Code default | Binding path | Visibility and effect |
|---|---|---|---|---|
| `SERVER_PORT` | No | `8090` | App line 2; Compose line 184 sets it as a fixed value | Not secret. Compose fixes it, so `.env` and recreating the service cannot change it. Change the Compose value, and the UI and proxy settings that name the port, together |
| `IMAGE_TAG` | No | `latest` | Compose lines 178, 353, 564 | Not secret. The backend deploy sets it to the git short SHA so the previous image can be redeployed on failure |
| `CABIN_FRONTEND_ORIGIN` | Yes for managed-user sign-in links | a local dev address | App line 95; Compose line 264 | Not secret. Must be where `cabin-ui` is really served, or the emailed link points to the wrong place |
| `FAMILY_HUB_URL` | No | an instance public address | Compose lines 297 and 442 | Not secret. Line 442 is a build argument: change needs a `cabin-ui` rebuild |
| `GRAFANA_EXTERNAL_HOST` | No | an instance public host name | Compose lines 124, 292, 424 | Not secret. Line 424 is a build argument |
| `CABIN_INSTANCE_PLATFORM` | No | the App says "Not configured"; Compose supplies a description | App line 59; Compose line 272 | Not secret. Shown in Config, Platform |
| `CABIN_INSTANCE_REMOTE_ACCESS` | No | `Tailscale` in App; Compose adds a second entry | App line 60; Compose line 273 | Not secret. Comma-separated. Shown in Config, Remote Access |

Names Compose passes through from `.env` take effect on recreating the service; those marked as build arguments need the image rebuilt first. `SERVER_PORT` is the exception: Compose fixes it, so change the Compose line.

## Explicit source disagreement

Two existing incident runbooks answer common configuration failures. A successfully rebuilt Family Hub may still receive old `host-config.js` from an edge cache; inspect the browser-served nonsecret configuration and use the authorized cache correction described in [MAINTENANCE](../../MAINTENANCE.md#cloudflare-edge-caching-a-build-time-config-file-found-2026-08-01). Frigate's configuration substitutions require its `FRIGATE_` namespace, distinct from Compose interpolation; see the [existing two-stage mapping](../../REPLICATION.md#frigate-environment-substitution-is-prefix-restricted). Neither problem is resolved merely by proving a setting exists.

The supplied handover says `WEBUI_AUTH=false` is intentional with Tailscale as the access layer. The inspected [M920q Compose](../../../cabin-orchestration-platform/infra/docker-compose.m920q.yml) declares `WEBUI_AUTH: "true"`. Both observations must remain visible; neither proves the active runtime value. Do not change the running setting or assume the broader app is unauthenticated. Resolve the intended deployment setting for the specific instance when preparing its configuration. This disagreement does not block unrelated documentation.

## New device classes, not yet configurable (2026-10-16)

[D28](../../governance/decisions/ontology-decisions.md) names three device classes with no configuration surface yet: the WiFi/ESPHome sensor-box adapter (W-84), the Midea Cube HA integration (W-77, local-LAN device key/token, no env var exists for it today) and the CLOUDLINE S6 power and speed paths (W-78: relay metering, then a speed node). No `application.yml` property, Compose env var or UI setting exists for any of them as of this entry. Do not answer a configuration question about these units from inference — there is nothing configured to describe yet.

## Remaining configuration work

The generated inventory deliberately omits values/defaults and does not infer purpose or requiredness for all 146 names. The complete per-setting semantic registry is still open. Configuration sources include YAML bindings, Compose overlays, templates, Dockerfiles and application consumers; a name-only inventory must never be advertised as a complete installer schema.
