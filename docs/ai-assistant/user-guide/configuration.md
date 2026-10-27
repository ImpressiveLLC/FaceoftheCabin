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
| Telemetry archival | `TELEMETRY_ARCHIVAL_ENABLED`, `TELEMETRY_ARCHIVAL_CRON`, `TELEMETRY_ARCHIVAL_DIR`, `TELEMETRY_ARCHIVAL_HOT_MONTHS` (3) |
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

## Explicit source disagreement

Two existing incident runbooks answer common configuration failures. A successfully rebuilt Family Hub may still receive old `host-config.js` from an edge cache; inspect the browser-served nonsecret configuration and use the authorized cache correction described in [MAINTENANCE](../../MAINTENANCE.md#cloudflare-edge-caching-a-build-time-config-file-found-2026-08-01). Frigate's configuration substitutions require its `FRIGATE_` namespace, distinct from Compose interpolation; see the [existing two-stage mapping](../../REPLICATION.md#frigate-environment-substitution-is-prefix-restricted). Neither problem is resolved merely by proving a setting exists.

The supplied handover says `WEBUI_AUTH=false` is intentional with Tailscale as the access layer. The inspected [M920q Compose](../../../cabin-orchestration-platform/infra/docker-compose.m920q.yml) declares `WEBUI_AUTH: "true"`. Both observations must remain visible; neither proves the active runtime value. Do not change the running setting or assume the broader app is unauthenticated. Resolve the intended deployment setting for the specific instance when preparing its configuration. This disagreement does not block unrelated documentation.

## New device classes, not yet configurable (2026-10-16)

[D28](../../governance/decisions/ontology-decisions.md) names three device classes with no configuration surface yet: the WiFi/ESPHome sensor-box adapter (W-84), the Midea Cube HA integration (W-77, local-LAN device key/token, no env var exists for it today) and the CLOUDLINE S6 power and speed paths (W-78: relay metering, then a speed node). No `application.yml` property, Compose env var or UI setting exists for any of them as of this entry. Do not answer a configuration question about these units from inference — there is nothing configured to describe yet.

## Remaining configuration work

The generated inventory deliberately omits values/defaults and does not infer purpose or requiredness for all 146 names. The complete per-setting semantic registry is still open. Configuration sources include YAML bindings, Compose overlays, templates, Dockerfiles and application consumers; a name-only inventory must never be advertised as a complete installer schema.
