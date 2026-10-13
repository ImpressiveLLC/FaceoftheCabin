# Maintenance and recovery supplement

Audience: operator. Source-reviewed at the corpus baseline; runtime state and recovery results unverified. Read the existing [Maintenance & Operations Guide](../../MAINTENANCE.md) for detailed incident history. This supplement corrects specific source drift and provides a consistent diagnostic sequence.

## First determine what is failing

| Observation | Safe next question/check | What it does not prove |
|---|---|---|
| Page cannot load | Is the intended instance URL reachable, and is its serving/tunnel service healthy? | Backend/database failure is not established merely by a page load failure. |
| Page loads but a feature is empty | Inspect the signed-in browser request/status and the relevant response freshness. | An empty display does not mean no events/devices exist; authorization or network errors may be hidden. |
| Container is running | Inspect its defined healthcheck and the actual feature path. | “Running” is not data flow, authentication, correct configuration or durability. |
| Health reports HA token missing | Check secret presence through the approved provisioning mechanism; do not print it. | A nonblank token is not proof of valid HA authentication. |
| Ask is unavailable or gives raw facts | Check `answeredByModel` and relevant sources, then model availability through an authorized read path. | Fallback is not evidence that the LLM is working. |
| Device is known but offline | Check timestamps, lifecycle, protocol/provider and expected reporting behavior. | Registry existence alone is not a functioning physical device. |
| Presence reads Away (or "Presence unknown") where someone is | Check how old the signal's source is (the badge's age, or the retained `{location}/presence/{person}/last_seen` time), then the phone: is its Tailscale connected (`tailscale status` on the hub lists the phone's `Online` and `LastSeen`), and is the Home Assistant Companion app reporting? | A stale signal is unknown, not away. Do not "fix" it by setting the manual badge: a manual value hides the stale signal. A phone silent for days is the usual cause; one such case (2026-10-04) was a phone off Tailscale since 2026-10-02. |
| The phone WiFi check never reads on | Run `check_authorized_user_phone_wifi.sh <person_id>` inside the Home Assistant container and read its result; a configuration error prints nothing and exits 2 (the sensor then shows unavailable, by design: an error must not read as away). Then compare the MAC in `authorized_user_phones.conf` with the MAC the router shows for the phone on that network. | OFF with the phone on the network usually means the phone presents a different (private) MAC there; that is unconfirmed for the cabin and is an open item (W-37). Do not ask users to change phone settings as the answer. |

Sources: [maintenance incident lessons](../../MAINTENANCE.md), [replication health layers](../../REPLICATION.md#10-monitoring--cross-container-health), [HaTokenCabinHealthIndicator](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/health/HaTokenCabinHealthIndicator.java), [TinyHelpdeskService](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/helpdesk/TinyHelpdeskService.java).

Use only established authorized read endpoints for diagnosis. Method names alone are insufficient: [platform import proposals](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/PlatformImportController.java) is a GET that contacts the platform and upserts records. Do not trigger regeneration, scanning, liveview sessions or device commands as an unannounced “read check.”

## A camera is not delivering frames

Audience: operator. Source-reviewed against the live host on 2026-10-03. The full procedure and the site facts are in the [Maintenance & Operations Guide](../../MAINTENANCE.md#a-camera-on-a-different-network-than-the-m920q).

| Observation | Safe next check | What it does not prove |
|---|---|---|
| Frigate reports `camera_fps` 0.0 for one camera and its log says "No route to host" | Confirm the camera's current address in its own app or the router's client list, then from the M920q run `ip route get <ip>`, `ping`, and `nc -zv <ip> 554`. | "No route to host" shows that nothing answers at that address. It does not show the camera is off: it may have a new address or sit on another subnet. |
| The camera answers but Frigate still shows no frames | Test the stream from inside the Frigate container with the full ffprobe path `/usr/lib/ffmpeg/7.0/bin/ffprobe`, letting the password expand there. Try a wrong password once, not in a loop. | A reachable camera is not a working stream: paths differ by firmware, and the password may differ from the one in the vault. |
| Frames and recording are fine but the camera never produces events | Read Frigate's effective config for the camera (`/api/config`): is `detect.enabled` true, and which classes does `objects.track` list? | Frames and continuous recording do not mean detection is on. Frigate 0.17 defaults detection to off. |
| The camera is on a different network from the M920q | Prefer a cable from the M920q's spare Ethernet port to the camera's network. A Wi-Fi join takes the M920q off its own network, Tailscale and the internet. | A network name does not identify a network: names repeat across the cabin, Home and several bands. |

Camera configuration changes go through a pull request, and the camera password never goes into the file or the repository.

## Deployment source correction

The old Maintenance Deployment section describes backend deployment as manual-only. The current [deploy-cabin-backend workflow](../../../.github/workflows/deploy-cabin-backend.yml) instead has a main-push path filter and manual dispatch. Its implementation tests, builds/tags a candidate, checks health and has rollback handling. The workflow is the source for current configured behavior; none of this proves its latest run succeeded.

For a release, record separately: repository/commit, tests, built image, target host/stack, deployed image/commit, health result and real feature verification. Obtain authorization for the actual deployment; do not merge, dispatch, rebuild or restart merely because a PR exists. The [family-hub](../../../.github/workflows/deploy-family-hub.yml), [discovery](../../../.github/workflows/deploy-cabin-discovery.yml) and [production stack](../../../.github/workflows/deploy-production-stack.yml) have separate scopes/triggers. Read the relevant workflow rather than applying one generic deployment recipe.

Do not print full resolved Compose configuration to prove equality: it can contain secret values. Compare relevant nonsecret metadata, presence or carefully selected status fields. The existing maintenance guide records why raw-value comparisons are prohibited.

## Persistence and replacement-host recovery

The [base Compose](../../../cabin-orchestration-platform/infra/docker-compose.yml), [M920q overlay](../../../cabin-orchestration-platform/infra/docker-compose.m920q.yml) and [production-stack Compose](../../../cabin-orchestration-platform/infra/production-stack/docker-compose.yml) define different persistence mechanisms. Inspect the selected stack's effective mounts without exposing credentials; service declaration names are not a backup inventory.

| State category | Source starting point | Recovery evidence required |
|---|---|---|
| Application database | Base Compose Postgres volume and backend repositories | Consistent backup, version compatibility and restored application records/behavior |
| Kafka/event processing | Base Compose broker persistence and backend event consumers | Deliberate replay/retention decision; no accidental repeat of external/physical actions |
| Grafana/Prometheus and dashboards | Compose storage and checked-in provisioning | Restored required configuration/history, compatible provisioning and functional dashboards |
| HA, Node-RED, MQTT, Zigbee2MQTT | Selected support stack bind mounts/volumes and device prerequisites | Service configuration, required credential recovery, physical coordinator mapping and verified data path |
| Camera/media services | Production stack media/config mounts and bridge build source | Required media/configuration preserved according to retention policy; streams actually work |
| Ollama/Open WebUI | M920q named volumes | Model/digest and intended chat/config state recover; do not treat model files as application facts |
| Vaultwarden and deployment credentials | M920q vault volume plus the secure provisioning runbooks | Operator-controlled secure backup/decryption recovery; never include contents in this corpus |
| Archives and instance-specific files | Backend archive bind mount and selected stack host mounts | Files, permissions, paths and retention match the new target plan |

This is a **recovery scope**, not a verified backup procedure. Do not copy a live database volume or stop production based on this table. Service-specific consistent backup/restore commands, encrypted off-host custody, recovery objectives and an isolated restore rehearsal remain open in [G04](../corpus/coverage.md#gap-register). A recreated stack with empty/lost required state fails recovery acceptance even if every container is green.

Existing component guidance is available now: [REPLICATION section 10](../../REPLICATION.md#kuma-config-as-code-decision--poc-passed-production-approval-pending) records the Kuma 2.5.0 disposable reconciliation POC and distinguishes a monitor specification from a complete `/app/data` backup. It leaves production reconciliation separately pending and provides a manual monitor target list. Use that current record instead of the older Claude plan's proposed JSON-import wording. For camera/MQTT diagnosis, the same section explains the configured availability topic, frame checks and notification routing; a green service on the wrong broker/topic does not prove the intended path. See [recovered history H06](../corpus/local-history-evidence.md#findings-reconciled-with-current-source).

## Close the incident

Record the symptom, affected instance/capability, source/runtime version, bounded checks, cause evidence, authorized correction, verification and remaining uncertainty. Submit reusable facts or corrected instructions through the [shared contribution process](../contributing.md). Keep secret values and household telemetry out of the shared incident summary. Never label an inferred cause “confirmed” or a suggested command “executed.”
