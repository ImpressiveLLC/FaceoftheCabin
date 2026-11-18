# Operations and recovery reference

Source review only, 2026-11-18. Baseline commit: `b9eea9cd649dd716672fc574c39aaf5e52b887d9`. These instructions describe repository behavior, not current host state. No service was operated, no restore rehearsed, and no model run. Each section states its audience; the Ask mapping enforces that boundary. Commands describe operator procedures, not authorization for Ask to execute them.

## Recovery checks and notices

Audience: administrator. Evidence baseline b9eea9cd; [heartbeat script](../../../scripts/host/cabin-recovery-heartbeat), defaults at lines 31-70, checklist at 419-523, summary at 525-598, cycle at 602-639, manual command at 695-714, stop classification at 717-747.

An operator can run `sudo cabin-recovery-heartbeat check` to print the current checklist without sending a notification. It probes services and may prepare its local state directory; it is not a guarantee of zero filesystem writes. Exit status is zero only for an all-clear summary. Adding `--notify` sends the summary and updates report/state, so it requires an explicit notification test decision.

| Result | Meaning from source |
|---|---|
| CRITICAL component | Core containers or gateway/internet/DNS/Tailscale/public reachability/SSH probes failed. The service checks actual responses where implemented, not only container existence. |
| Event ingestion | Newest event older than 15 minutes warns; 60 minutes or query failure is critical by default. |
| WARN | Extra containers, restart counts, offline Zigbee devices, zero-fps cameras, backup timers, host units, clock synchronization or disk use can need attention. Disk results here do not replace the storage monitor's critical alert. |
| Previous shutdown / telemetry gap | Diagnostic context; an absent clean-shutdown marker alone is not proof of a specific fault. |

Default ongoing cadence is 60 seconds, five misses to declare a component down, two good beats to recover, reminders after one hour and then six hours. Parent failures suppress dependent duplicate pages, not tracking.

Boot titles distinguish all clear, needs a look, and PROBLEMS. A deliberate service stop sends "heartbeat STOPPED on purpose"; a successful restart of this service does not. A crash still warns, with a default ten-minute crash-notice cooldown. Stopping a monitored component can still trigger its own alert. There is no general maintenance mute. A stale recovery banner does not prove the whole host is dead; an off-host monitor is needed for that.

## Recovery installation and tuning

Audience: administrator. Evidence baseline b9eea9cd; [maintenance recovery runbook](../../MAINTENANCE.md), lines 2918-2942 and 3024-3026; [systemd unit](../../../scripts/host/cabin-recovery-heartbeat.service); [script defaults](../../../scripts/host/cabin-recovery-heartbeat), lines 31-70.

Installation is an owner-approved host change. The runbook installs the script in `/usr/local/bin`, installs the systemd unit, and protects `/etc/cabin-recovery-heartbeat.env` with mode 600. Verify that `HB_NTFY_TOPIC` is configured without printing its value; a blank topic cannot deliver pushes. Run the checklist before enabling the service and reconcile configured component/timer names with the actual host.

After approval to enable and reboot, the acceptance check is one boot-summary push, normally after the default three-minute grace plus a possible two-minute retry, and no deliberate-stop push for the reboot. This is a procedure, not a test performed for this guide. Backend banner integration and host-service installation are separate deliverables.

Tune `HB_INTERVAL`, `HB_MISS_LIMIT`, `HB_RECOVER_OKS`, `HB_REPAGE_FIRST`, `HB_REPAGE_EVERY`, `HB_BOOT_GRACE` and `HB_BOOT_RETRY` in the root-only environment file, then restart the service under the host change process. Endpoint/peer/component settings must fit the instance; never publish their private values. Changing notification configuration alone does not prove receipt. Backend stale-state timing is separately configured by `cabin.recovery.staleAfterSeconds`.

## External dead-man monitoring

Audience: administrator. Evidence baseline b9eea9cd; [heartbeat script](../../../scripts/host/cabin-recovery-heartbeat), lines 159-201 and 630-638; [Worker implementation](../../../cabin-orchestration-platform/infra/cabin-deadman-worker/src/index.js), lines 1-41 and 87-139; [maintenance runbook](../../MAINTENANCE.md), lines 2944-2963.

The host-alive ping runs every cycle. The platform-healthy ping is sent only when the backend and Zigbee bridge both answer. Neither proves every application feature works. If the host loses power, only the external receiver can notice its silence.

The repository offers a Cloudflare Worker receiver or a healthchecks.io-style receiver. Worker setup requires its KV binding, scheduled check and secret configuration; deployment is not established by source existing. Worker defaults are ten-minute silence and hourly reminders, with a minimum silence threshold of eight minutes because ping storage is rate-limited to four-minute intervals. Status ages therefore are approximate. A kind that has never pinged is not alerted: verify first receipt during setup.

A deliberate stop is distinct from a crash. The Worker stops repeating the initial deliberate-stop notice, but `STOP_MAX_MINUTES` bounds that suppression (default 24 hours); after that it treats continued silence as an outage. Healthchecks.io uses the configured failure endpoint on a deliberate stop. Do not assume both receivers have identical semantics.

Ping URLs, tokens and notification topics are secrets. The host only sends its bearer token to configured trusted HTTPS hosts and refuses control characters; a third-party monitoring URL must not receive the Worker token. Test delivery only with explicit authorization.

## Monitoring key access

Audience: administrator. Evidence baseline b9eea9cd; [maintenance restricted-SSH runbook](../../MAINTENANCE.md), lines 2965-3022.

The recovery-monitor key is restricted to reading the recovery state and report through a forced command. It cannot run a new check, execute arbitrary commands or repair the host. This is separate from the owner's interactive access. The runbook records option A applied previously; source review here does not reverify installation or key validity.

For rotation, the owner generates a new key pair, replaces the authorized public-key entry while retaining its forced-command restrictions, verifies the new key reads only the expected report, and verifies the old key is rejected before disposing of the old private key. Revocation removes the authorized key; it must not widen access to avoid a login problem. Keep private keys in operator-controlled secure storage, never git or corpus text. A password manager hosted on the failed machine cannot be the only outage recovery copy.

Connection refused suggests the separate listener is unavailable; permission denied suggests key/ownership/mode or revocation; a missing report can mean the service has not written it yet. Check these separately. Report only sanitized status, not private keys or complete host output.

## Incident triage for the household

Audience: household. Evidence baseline b9eea9cd; [incident triage](../../MAINTENANCE.md), line 3030 onward; [existing incident guide](maintenance.md#close-the-incident).

Record which feature failed, when, whether other features still work, and the visible error. A blank page, missing reading or old presence signal is not proof that a device or person is absent. Do not assume a successful page load proves synchronization.

For a login or sync problem, an administrator can inspect the failed request and its response, with private headers and values removed. For a reboot/recovery banner, ask the operator to check recovery status. The household should not restart services, change authentication or delete data to clear the display. Avoid repeated retries of physical actions when their outcome is unknown.

Close an incident only with a stated cause or remaining uncertainty, the authorized correction, and evidence that the affected feature works. A green container or a proposed fix alone is not that evidence.

## Restore status by store

Audience: administrator. Evidence baseline b9eea9cd; [existing backup and restore inventory](maintenance.md#backup-and-restore-recipes-per-store-k10-verified-from-source-only), [D19 recovery procedure](../../MAINTENANCE.md), line 2821 onward; [backup script](../../../scripts/host/backup-modular); selected Compose volume declarations.

This is a source inventory, not a restore recipe. Every restore rehearsal below remains NOT_RUN. Before any recovery, identify the backup's source, consistency, timestamp, version, intended target and rollback; preserve the original.

| Store | Documented recovery status and gap |
|---|---|
| PostgreSQL application database | Existing guide records no documented dump job or tested per-store restore. Live-file rsync is not proof of database consistency. Do not restore an arbitrary volume copy over production. |
| Kafka | Persistence is declared, but a service-specific tested restore/replay procedure is not supplied by the guide. Replay needs a decision that prevents duplicate external or physical actions. |
| Grafana | Configuration/provisioning and required history must be distinguished. No verified full per-store restore recipe is recorded. |
| Home Assistant | Configuration/state backup must match the instance and its integrations. No tested per-store restore recipe is recorded. |
| Vaultwarden | Requires operator-controlled encrypted custody and recovery material. No tested restore recipe is recorded; never expose vault contents in Ask. |
| Frigate | Git can recreate configuration, not lost recordings or instance state. Full restore acceptance is unproven. |
| Node-RED | A settings-file rollback exists for the authentication change; it is not a complete flow/credential store restore. |
| Telemetry | D19 describes gap-window JSONL recovery from incremental archives or raw MQTT backup. Whether each source exists and is complete must be checked. The cold archive can be the only copy after monthly deletion; never prune it as disposable backup. |
| Uptime Kuma | The guide identifies the full data directory as the supported backup; a monitor specification is not that backup. Restore rehearsal remains absent. |

The recovery acceptance criterion is preserved required state and working behavior, not merely running containers. Unknown backup coverage stays unknown.

## Node-RED login recovery

Audience: administrator. Evidence baseline b9eea9cd; [authentication role](../../../ansible/roles/nodered_auth/tasks/main.yml), lines 32-152; [maintenance authentication runbook](../../MAINTENANCE.md), lines 1471-1552.

A 200 response from the editor shell does not prove authentication is disabled. Check the protected Admin API: the role expects an unauthenticated `/flows` request to return 401. For changes it applies, it also tests that the configured credentials obtain an access token, without logging secrets. Both denial and successful authorized login matter.

The role backs up the settings file before replacing the commented stock authentication blocks. It is first-time enablement, not a general password-rotation mechanism: already-uncommented blocks do not match, and the post-change checks are conditional on a change. A no-change run therefore does not prove a rotated credential was installed or tested. An empty configured password ends the play without enabling authentication.

The documented rollback restores the timestamped settings backup and restarts Node-RED under operator approval. If that backup predates authentication, it can restore unauthenticated access; check its security state and exposure before using it. This rollback covers the settings change only, not all Node-RED data. Credential edits, restart and login tests require the owner-controlled change process; no secret values belong in the guide.

## Grafana login outside the embedded panel

Audience: household. Evidence baseline b9eea9cd; [Grafana maintenance history](../../MAINTENANCE.md), lines 1556-1609.

A blank Grafana embed can mean its sign-in session needs renewal. The documented workaround is to open the configured Grafana dashboard in a normal top-level tab, sign out and sign in there if needed, then reload the Monitoring panel. Google sign-in cannot complete inside the embedded frame in the documented flow. A previously issued session cookie may remain stale until reauthentication.

This is historical source guidance, not a diagnosis of the current browser. If top-level login fails, report the visible error to the administrator rather than disabling security or exposing another service. Grafana's documented outside-Tailscale exception does not make Home Assistant, Node-RED, Zigbee2MQTT or Frigate administration public.

## Storage pruning boundaries

Audience: administrator. Evidence baseline b9eea9cd; [storage rules](../../STORAGE_ARCHITECTURE.md), lines 72-115; [prune implementation](../../../scripts/host/cabin-storage-prune).

Use the reviewed pruning tool to report and plan before approving removal. `cabin-storage-prune report` inventories disposable storage; `plan --only all-safe` names candidates without deleting. `all-safe` excludes whole-directory targets; it is not a guarantee that every retained copy is unnecessary.

Removal requires the apply mode, explicit target selection and confirmation. The owner reviews the actual plan and backup needs first. Paths must remain within allowed roots; protected telemetry/archive/database paths and Docker volumes must not become cleanup targets. Whole-directory removal has an additional explicit selection gate. Do not replace these checks with improvised recursive deletion or bypass a refused path.

Storage configuration and retention are instance-dependent. Confirm the mounted filesystem and current capacity before a plan. Preserve telemetry's archive/warning rules and distinguish disposable video/backups from the only surviving copy of a record. No pruning or restore was run for this guide.

## Workflow test gates

Audience: administrator. Evidence baseline b9eea9cd; [workflow directory](../../../.github/workflows) and [existing deploy-gate guide](maintenance.md#deployment-source-correction).

The baseline contains 16 workflow YAML files; the scripts directory is not a seventeenth workflow. Eleven are test/tooling gates. Seven were already named in the older guide table, so "eleven unnamed" is not an accurate gap count.

| Workflow file (without .yml) | Trigger scope and purpose |
|---|---|
| blink-relay-tests | Relevant PR/push paths; Blink relay checks. |
| blinkbridge-tooling | Relevant PR/push paths; offline Blinkbridge tooling checks. |
| eval-fixture-tooling | Relevant PR/push paths; evaluation fixture tooling checks. |
| family-notes-backend | Relevant PR paths; backend tests. |
| homeassistant-package-tests | Relevant PR/push paths; Home Assistant package structural tests. |
| intent-slot-tooling | Relevant PR/push paths; intent/slot offline tooling checks. |
| kiosk-cabin-ui | Relevant PR paths; UI tests and production build. |
| kiosk-family-hub | Relevant PR paths; Family Hub browser checks in a test container. |
| recovery-heartbeat-tests | Relevant PR/push paths; host-script, Worker and targeted backend checks. |
| taxonomy-eval-tooling | Relevant PR/push paths; taxonomy tooling/source extraction checks. |
| telemetry-export-tests | Relevant PR/push paths; telemetry export checks. |

These gates do not themselves deploy production. Four deployment workflows and the credential-rotation workflow are separate, with different path filters and effects. Read the exact YAML for the proposed change; passing one test job is not passing every applicable gate.

If a deployment test fails before rebuild/restart, distinguish that from a bad deployed release. Record the failing assertion and whether anything changed live. A retry that passes on the same commit does not establish the root cause; preserve evidence and investigate intermittent behavior. Retrying a production workflow remains an authorized action, not a default way to hide a failing check.
