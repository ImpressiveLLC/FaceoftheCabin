# Recovery check and component heartbeats — design note

Date: 2026-11-03. Status: built in this PR, not yet installed on the M920q. Work item: W-144 (see `docs/governance/backlog.md`). Runbook: [`docs/MAINTENANCE.md`, "Recovery check"](../../MAINTENANCE.md#recovery-check-boot-summary-component-heartbeats-and-the-dead-mans-switch).

## Why

On 2026-11-02 the cabin lost power. The host rebooted at 23:54 UTC and recovered fully, but nobody was told: telemetry had a silent gap of more than 12 minutes and Nate found out only by asking. The recovery check that followed (uptime, containers healthy, restart counts, Postgres events flowing, Kafka, Zigbee2MQTT, Frigate, blinkbridge, cloudflared, backups, trim agent, disk) was improvised in chat. Nate's request: a critical recovery service that checks and notifies on restart, runs component-by-component heartbeats for long enough that a missing one means a problem, and tells admins if the heartbeat itself has to stop or dies, with at least a Critical banner and ideally a message.

## What is built

| Piece | Where | Runs |
|---|---|---|
| Host service `cabin-recovery-heartbeat` (bash) and its systemd unit | `scripts/host/` | On the host, as root, **outside Docker**, so it keeps working when the stack is down |
| State spool `state.json` | `/storage/archives/cabin_event/.recovery/` (the bind mount the backend already has) | Written by the host service, read-only for the backend |
| `RecoveryHeartbeatService`, `RecoveryController` (`GET /api/system/recovery`, administrator only) | `cabin-backend` | Reads the spool, validates it, serves the Monitoring section, and feeds `ActiveAlertService` |
| Critical banner entries | `ActiveAlertService` → `GET /api/alerts/active` → the existing banner, Status Checks and nav rail | Nothing new in the UI's banner code: the conditions arrive as ordinary CRITICAL alerts |
| Monitoring section "Recovery and heartbeats" | `RecoveryStatusWidget` in `App.jsx`, under Storage | Administrators, Cabin location, signed in, not demo |
| Tests | `scripts/host/test_cabin_recovery_heartbeat.sh` (71 checks), `RecoveryHeartbeatServiceTest`, `RecoveryControllerTest`, `ActiveAlertServiceTest`, `ui/src/RecoveryStatus.test.jsx` | CI: `.github/workflows/recovery-heartbeat-tests.yml` and `kiosk-cabin-ui.yml` |

### Three jobs of the host service

1. **Boot recovery check.** When the host has been up less than 15 minutes at service start, the service waits a grace period (180 s), runs the whole checklist, and if anything fails waits 120 s and runs it once more (components are often still starting). It then sends exactly one summary: INFO "Cabin restarted at T, recovered in N min, telemetry gap G. All N checks clear." or WARN/CRITICAL naming each failing component. It reports whether the previous shutdown was clean. The checklist: every heartbeat component, the extra containers (`cabin-ui`, `family-hub`, `cabin-grafana`, `uptime-kuma`, `mediamtx`), container restart counts, Postgres accepting, `cabin_event` ingest freshness (which also proves the Kafka to Postgres path), the telemetry gap across the restart, Zigbee2MQTT per-device availability, Frigate cameras at 0 fps, the public URL, disk usage (`/storage` and `/`), the four backup timers, the trim agent and the blinkbridge watchdog, and NTP.
2. **Component heartbeats.** Every 60 s each of backend, postgres, kafka, mosquitto, zigbee2mqtt, frigate, blinkbridge, cloudflared, homeassistant and nodered is probed (container running and not unhealthy, plus a real answer where one is cheap: actuator health, `pg_isready`, a broker message, the Zigbee bridge state, an HTTP reply). Five misses in a row (5 minutes) make a component down: one CRITICAL push, one Critical banner entry. It is not paged again each cycle; a reminder comes after 1 hour and then every 6 hours. Two good beats in a row clear it and send one "recovered" notice. One good beat in the middle of a bad patch resets the count, so a blip never pages.
3. **Dead-man signals.** Each cycle pings an external URL (`HB_DEADMAN_URL`), so something off the host can notice silence. A deliberate stop (`systemctl stop`) sends a CRITICAL "heartbeat STOPPED on purpose" push and tells the dead-man URL immediately (`/fail`). A host shutdown or reboot is recognised and is not announced as a stop; it leaves a "clean shutdown" marker, and its absence at the next boot is how an unclean shutdown (power loss) is recognised.

## What is detected, and what is not

| Failure | Detected by | Without the external dead-man |
|---|---|---|
| A container or service stops, hangs or turns unhealthy | Host heartbeat: ntfy push after 5 minutes, banner, Monitoring | Yes |
| Host rebooted (power loss) | Boot summary: ntfy push, unclean shutdown stated, telemetry gap measured | Yes, once the host is back and ntfy can be reached; a failed send is queued and retried every cycle for a day |
| Heartbeat service stopped on purpose | ntfy push at the moment of the stop, banner "stopped on purpose", dead-man `/fail` | Yes |
| Heartbeat service crashed | systemd restarts it after 15 s and sends a WARN "crashed" notice; if it cannot run, the backend sees a stale state file after 5 minutes: banner "Recovery service not reporting" | Banner only, and only if someone looks |
| Heartbeat service hung or killed so hard nothing ran | Backend sees a stale state file after 5 minutes: Critical banner "Recovery service not reporting" | Banner only, no push |
| **Whole host dead, or the backend down with it** | Only the external dead-man URL | **Not detected.** Nothing on the host can report its own death. Without the external piece you learn it when you look |
| Internet or ntfy down while the host is fine | Host push fails and queues; banner and Monitoring still work | Banner only |

The banner needs the backend alive; the push needs the host service alive and ntfy reachable; the dead-man needs only the host to have pinged once. That ordering is why all three exist.

## Decisions made (defaults, each open to change by Nate)

| # | Decision | Why |
|---|---|---|
| 1 | The host service sends the push itself and the backend does not publish events for these conditions | The backend, Kafka and Postgres are exactly what may be down. A backend-published CRITICAL event would also double-push through `NtfyAlertPublisher`. The host uses the same ntfy topic and the same priorities (CRITICAL `urgent`, WARN `high`, INFO `default`), so a phone sees one channel. `NtfyAlertPublisher` is reused as the topic and format convention, not called |
| 2 | Heartbeat interval 60 s; down after 5 consecutive misses (5 min); recovered after 2 good beats | Long enough to ride out a container restart or a slow probe; short enough that 12 minutes of silence like 2026-11-02 is reported within 5. All env-configurable (`HB_INTERVAL`, `HB_MISS_LIMIT`, `HB_RECOVER_OKS`) |
| 3 | Reminders at 1 hour, then every 6 hours | Escalate on persistence only, no paging each cycle |
| 4 | Boot check after a 180 s grace plus one 120 s retry; a boot is "host up under 15 minutes at service start" | Containers need minutes to settle; a retry removes most false alarms. A service restart on a long-running host sends no boot summary |
| 5 | A component the boot summary already named as failing is marked announced | The heartbeat loop does not page it again 5 minutes later; its recovery is still reported |
| 6 | An unclean shutdown that fully recovered is INFO, with the sentence "after an unclean shutdown" | The 2026-11-02 case should read as "recovered", not as a warning; the facts (unclean, gap) are in the message |
| 7 | Disk usage is at most WARN in the boot summary | `StorageMonitorService` already alerts on disk at 60% and 75%; the boot summary should not turn every restart CRITICAL while the disk is high |
| 8 | A missing or stale state file: stale is CRITICAL "Recovery service not reporting"; missing is only a WARN "not installed" | A stale file proves something was running and stopped. A missing file on a clone or a dev machine must not nag. The feature is off by default (`cabin.recovery.enabled=false`) and `docker-compose.m920q.yml` turns it on |
| 9 | Stale after 300 s (`cabin.recovery.staleAfterSeconds`) | Five missed writes at the 60 s interval, the same patience as the component rule |
| 10 | Banner alerts carry location `cabin` | The M920q is the head for both locations (D23), but the banner filters alerts by the selected location, so with Home selected these do not show. Known limit, see "Not built" |
| 11 | State lives in a new `.recovery` folder inside `/storage/archives/cabin_event`, mode 0755, files 0644, written only by the host | The backend only reads. It is a different folder from the world-writable `.trim` spool on purpose: the backend must never be able to write what a root service trusts |
| 12 | The backend treats the file as untrusted: regular file only (no symlink), at most 200 KB, whitelisted fields, bounded lengths, component names matched against a pattern | Same discipline as `StorageTrimService` |
| 13 | `GET /api/system/recovery` is administrator-only and a demo DENY | It lists internal component names and install commands |
| 14 | `ExecStopPost`, not `ExecStop`, classifies how the service ended | It runs however the service ended; `SERVICE_RESULT` separates a deliberate stop (success), a crash, and a shutdown (a `shutdown.target` job is queued) |
| 15 | External dead-man: a healthchecks.io-style URL in `HB_DEADMAN_URL`, pinged every cycle, with `/fail` on a deliberate stop | No account is created by this PR. Setup is below and in the runbook |
| 16 | Messaging fan-out is not built, but `HB_NOTIFY_HOOK` is | A hook is a few lines and tested, and it lets Nate add email or SMS without a code change (see below) |

## Dead-man's switch: setup, and the Cloudflare alternative

**healthchecks.io (about five minutes).** Create a free account, add a check named "cabin heartbeat" with period 5 minutes and grace 5 minutes, and add an integration for where it should reach admins (email, Signal, Telegram, SMS, or the ntfy topic; the integration list is the fan-out). Copy the check's ping URL, then on the host add `HB_DEADMAN_URL=<that URL>` to `/etc/cabin-recovery-heartbeat.env` and `sudo systemctl restart cabin-recovery-heartbeat`. The service pings every minute; if pings stop for about ten minutes healthchecks.io alerts, whether the service died or the host lost power. A deliberate stop calls `<url>/fail`, which alerts at once. The URL is a secret (anyone with it can mark the check up), so it goes in the root-only env file and never in git.

**Cloudflare Worker alternative (not built here).** The project already uses Cloudflare. A Worker with a Cron Trigger and one KV key does the same job: the host pings a Worker URL with a long random path (the Worker stores the time in KV), and a Cron Trigger every minute compares that time with now and, if it is older than 10 minutes, posts to the ntfy topic once (it remembers that it has alerted so it stays quiet until pings resume). `HB_DEADMAN_URL` points at the Worker and `HB_DEADMAN_STOP_URL` at a second route for the deliberate-stop signal. It stays inside the free tier and keeps one fewer vendor, but it is code to write and keep, where healthchecks.io is configuration, so healthchecks.io is the recommended first step. The Worker is a follow-up if Nate wants it.

## Messaging fan-out (documented, not built)

Today the push goes to the ntfy topic, which reaches every phone subscribed to it; admins who need it subscribe to the topic in the ntfy app. Options, cheapest first:

1. **More subscribers on the same topic** (works now, no code).
2. **`HB_NOTIFY_HOOK`** (built): an executable on the host called as `HOOK LEVEL TITLE MESSAGE` for every notification. It can send email (`msmtp`, or ntfy's `Email:` header), SMS through a carrier email gateway or Twilio, or a Signal or Telegram bot message. It is called from the root service, so the script must be root-owned and not world-writable.
3. **The dead-man provider's integrations** (healthchecks.io has email, SMS, Signal, Telegram, Slack and more), which also covers the whole-host-dead case.
4. **A backend role-based fan-out** to every administrator in the managed-users table. Not recommended as the first path: it depends on the backend being up, which is the failure it should survive.

## Install (Nate)

An agent session cannot write to the host. Exact commands are in the runbook; they are idempotent (safe to run again) and the dry run in step 3 changes nothing. The backend part needs the usual backend deploy, which needs Nate's go; until then the host service and its pushes work and the banner and Monitoring section do not.

## Not built, and risks

- **Not run on the real M920q.** The probes assume the container names in the compose files (`cabin-backend`, `cabin-postgres`, `cabin-kafka`, `mosquitto`, `zigbee2mqtt`, `frigate`, `blinkbridge`, `cloudflared`, `homeassistant`, `nodered`), the backup timer names in `docs/STORAGE_ARCHITECTURE.md`, and Frigate's `/api/stats` shape. Tests use stand-ins for `docker`, `curl` and `systemctl`. Run `sudo cabin-recovery-heartbeat check` once before enabling and fix any name that does not match (all names are env-overridable: `HB_COMPONENTS`, `HB_EXTRA_CONTAINERS`, `HB_BACKUP_TIMERS`, `HB_HOST_UNITS`, `HB_PUBLIC_URLS`).
- **The ntfy topic is blank on the M920q today (W-51).** If it stays blank no push is sent anywhere; the state file says `not-configured` and the Monitoring section shows a red warning. The banner still works.
- **Kafka is probed by its container only**; the real Kafka-to-Postgres path is proved by the ingest-freshness check at boot, not by the per-minute heartbeat.
- **No maintenance mute.** Stopping the service on purpose pages; so does stopping a monitored container for longer than 5 minutes. A planned-maintenance window is a follow-up.
- **Home location.** The banner entries have location `cabin` and do not show when Home alone is selected. Fixing it means teaching the banner filter an "all locations" value, which touches `mergeStatusCheckItems` and `AlertControls`; left for a separate change.
- **Backend flicker at boot.** For a few seconds after a reboot the backend may read the pre-reboot state file before the host service rewrites it; a stale-file banner can flash and clear.
- **Ontology.** No `docs/ontology.yaml` entries yet. Candidate entities: `recovery_heartbeat_service`, `component_heartbeat`, `boot_recovery_summary`, `dead_man_switch`. Per the ontology discrepancy protocol these are flagged as proposed, not ratified.
- **Dead-man account and Cloudflare Worker**: not created.
- **Messaging fan-out beyond ntfy and the hook**: not built.
- **Self-test of delivery**: `sudo cabin-recovery-heartbeat check --notify` sends a real summary push and is the way to confirm a phone receives it.
