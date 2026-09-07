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
| `VAULTWARDEN_*` | Backend bindings in [M920q Compose](../../../cabin-orchestration-platform/infra/docker-compose.m920q.yml); [Vaultwarden setup](../../MAINTENANCE.md). | Vault service admin access and backend CLI login/decryption are separate setup concerns. Only credential-pointer names may enter assistant knowledge. |

## Credentials and access

Use the current [Ansible secret provisioning/rotation guide](../../../ansible/README.md) and [Vaultwarden runbook](../../MAINTENANCE.md). The repository describes Ansible Vault as deployment-secret provisioning and Vaultwarden as the application credential vault. A pointer to an entry is not a replacement for provisioning the service securely; do not collapse these two mechanisms into an invented single implementation.

The current credential redactor permits an administrator to receive a vault entry name and directs other roles to an administrator. It runs before Helpdesk prompting/fallback/source return and on KB listing responses. It is not a general topology/privacy classifier for every other chunk type. Source: [CredentialPointerRedactor](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/devices/model/CredentialPointerRedactor.java), [TinyHelpdeskService](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/helpdesk/TinyHelpdeskService.java), [KnowledgeNodeController](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/KnowledgeNodeController.java).

Do not paste `.env`, decrypted vault output, full resolved Compose output, session credentials or provider responses into the assistant. Check presence or a safe status signal when debugging. Secret entry/rotation belongs in the approved secure mechanism; it is not a conversational field.

## Explicit source disagreement

The supplied handover says `WEBUI_AUTH=false` is intentional with Tailscale as the access layer. The inspected [M920q Compose](../../../cabin-orchestration-platform/infra/docker-compose.m920q.yml) declares `WEBUI_AUTH: "true"`. Both observations must remain visible; neither proves the active runtime value. Do not change the running setting or assume the broader app is unauthenticated. Resolve the intended deployment setting for the specific instance when preparing its configuration. This disagreement does not block unrelated documentation.

## Remaining configuration work

The generated inventory deliberately omits values/defaults and does not infer purpose or requiredness for all 146 names. The complete per-setting semantic registry is still open. Configuration sources include YAML bindings, Compose overlays, templates, Dockerfiles and application consumers; a name-only inventory must never be advertised as a complete installer schema.
