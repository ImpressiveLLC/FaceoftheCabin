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
| Off-host dead-man Worker `cabin-deadman` | `cabin-orchestration-platform/infra/cabin-deadman-worker/` | Cloudflare, deployed by Nate |
| Tests | `scripts/host/test_cabin_recovery_heartbeat.sh` (96 checks), the Worker's `index.test.js` (12), `RecoveryHeartbeatServiceTest`, `RecoveryControllerTest`, `ActiveAlertServiceTest`, `ui/src/RecoveryStatus.test.jsx` | CI: `.github/workflows/recovery-heartbeat-tests.yml` and `kiosk-cabin-ui.yml` |

### Three jobs of the host service

1. **Boot recovery check.** When the host has been up less than 15 minutes at service start, the service waits a grace period (180 s), runs the whole checklist, and if anything fails waits 120 s and runs it once more (components are often still starting). It then sends exactly one summary: INFO "Cabin restarted at T, recovered in N min, telemetry gap G. All N checks clear." or WARN/CRITICAL naming each failing component. It reports whether the previous shutdown was clean. The checklist: every heartbeat component, the extra containers (`cabin-ui`, `family-hub`, `cabin-grafana`, `uptime-kuma`, `mediamtx`), container restart counts, Postgres accepting, `cabin_event` ingest freshness (which also proves the Kafka to Postgres path), the telemetry gap across the restart, Zigbee2MQTT per-device availability, Frigate cameras at 0 fps, disk usage (`/storage` and `/`), the four backup timers, the trim agent and the blinkbridge watchdog, and NTP.
2. **Component heartbeats.** Every 60 s each of 17 components is probed: seven connectivity and SSH components (below) plus backend, postgres, kafka, mosquitto, zigbee2mqtt, frigate, blinkbridge, cloudflared, homeassistant and nodered (container running and not unhealthy, plus a real answer where one is cheap: actuator health, `pg_isready`, a broker message, the Zigbee bridge state, an HTTP reply). Five misses in a row (5 minutes) make a component down: one CRITICAL push, one Critical banner entry. It is not paged again each cycle; a reminder comes after 1 hour and then every 6 hours. Two good beats in a row clear it and send one "recovered" notice. One good beat in the middle of a bad patch resets the count, so a blip never pages.
3. **Dead-man signals.** Each cycle pings an external URL in two kinds (host alive, platform healthy), so something off the host can notice silence. A deliberate stop (`systemctl stop`) sends a CRITICAL "heartbeat STOPPED on purpose" push and tells the dead-man URL immediately (`/fail`). A host shutdown or reboot is recognised and is not announced as a stop; it leaves a "clean shutdown" marker, and its absence at the next boot is how an unclean shutdown (power loss) is recognised.

## Fit with W-56 and `docs/CABIN_REACHABILITY.md`

A framework for this already existed: W-56 (cabin dead-man heartbeat) and [`CABIN_REACHABILITY.md`](../../CABIN_REACHABILITY.md), with mechanism A (push heartbeat, two kinds: *host alive* and *platform healthy*, 10-minute grace, hourly repeat, back-online notice), mechanism B (pull probes from other vantage points) and slices S1 to S6. This work is built into it, not beside it, and reuses its ids and its parameters.

| W-56 / reachability item | How this PR covers it |
|---|---|
| **A, S1: host heartbeat script and timer** | Implemented inside `cabin-recovery-heartbeat` rather than as a separate timer, so there is one host service, not two. It pings both kinds every cycle: *host alive* (`HB_DEADMAN_URL`, no dependencies) and *platform healthy* (`HB_DEADMAN_PLATFORM_URL`, only while the backend and the Zigbee bridge both answered that cycle). The 10-minute grace, hourly repeat and "back" message live in the off-host monitor: the new Cloudflare Worker `cabin-deadman` (source and tests in this PR) or healthchecks.io |
| **A: external monitor and alert route** | Worker `cabin-deadman`, contract below; Nate deploys it and sets its secrets |
| **B, S3: pull probes from other vantage points** | Not built. The host's own `tailscale`, `public-url` and `tailscale-ssh` checks are from the host's vantage and do not replace two independent outside vantage points. The Worker's `/status` route is where a pull probe or the parent session can read the last-seen ages |
| **C, S4: Cloudflare tunnel-health notification** | Not built (a dashboard setting for Nate). The host's `public-url` component sees the same fault from inside (HTTP 530 when the tunnel is down), so the two are independent signals of one cause |
| S2 (Home collector), S5 (UPS and NUT), S6 (observer) | Untouched. The power-versus-internet distinction still needs S5; this PR only reports an unclean shutdown after the fact |
| New here (not in W-56) | The boot recovery check, per-component heartbeats and the Critical banner and Monitoring view, the connectivity and SSH components, and the report file for a read-only monitoring user. Tracked as W-144, which extends W-56 S1 on the host side |

`CABIN_REACHABILITY.md`'s slice table and the W-56 and W-144 backlog rows are updated in this PR to say so.

## Connectivity and SSH components

Added at Nate's request ("services should include SSH setup and all health checks potentially including connectivity"). Each is its own heartbeat component, with the same 5-miss rule, banner entry and recovery notice as the rest. They come first in the list because the later ones depend on them.

| Component | Passes when | Notes |
|---|---|---|
| `gateway` | The default route exists and the router answers one ping | Distinguishes "router down" from "Starlink down" |
| `internet` | At least one of two ping targets (`1.1.1.1`, `9.9.9.9`) or two HTTPS targets (Cloudflare trace, Google 204) answers | Down only when all four fail, so one provider's outage never pages. The detail says which kind still works |
| `dns` | One of two names resolves (`cloudflare.com`, `github.com`) | |
| `tailscale` | `tailscaled` reports `Running` and at least one peer is online; each name in `HB_TAILSCALE_PEERS` (optional) answers a tailscale ping | Default has no named peers because the PC and phones sleep; name always-on peers if wanted |
| `public-url` | Every URL in `HB_PUBLIC_URLS` answers below HTTP 500 from the host | Cloudflare answers 530 when the tunnel has no connection, so this also proves the tunnel is registered. Replaces the boot-only public URL check |
| `sshd` | An OpenSSH service (`ssh` or `sshd`) is active and something listens on port 22 | |
| `tailscale-ssh` | Tailscale SSH is switched on (`RunSSH`) | Proves it is enabled, not that a remote machine can get in; that needs an outside vantage point (S3) |

**Parent silencing.** When `gateway` is down its child `internet` is not paged separately, and when `internet` is down neither are `dns` or `public-url`. They stay tracked and shown as down. If a child is still down once its parent has recovered, it is announced then. One outage, one page.

**While the internet is down the push cannot leave.** The host queues the notice and sends it, marked delayed, when the connection returns. The banner works throughout (it is LAN-local) and the Worker catches the silence off-host after 10 minutes.

### SSH for non-interactive recovery checks (decision for Nate)

Tailscale SSH currently asks for a browser re-auth link on each session, which blocked checking the cabin after the 2026-11-02 power loss. Nothing on the host was changed. Options:

| Option | What changes | Least privilege | Cost |
|---|---|---|---|
| **A (recommended): dedicated read-only OpenSSH user with a forced command** | A user `cabin-monitor` and an ed25519 key. The `authorized_keys` line is `restrict,command="cat .../.recovery/state.json .../.recovery/report.txt"`: no shell, no terminal, no forwarding. It can read only the two world-readable files the service writes (live component state, and the full report of the last boot check) | Highest: it cannot run Docker, read secrets, or change anything | Cannot run a fresh check or fix anything; it returns the service's last report. An extra OpenSSH port (for example 2222) is needed because Tailscale SSH takes port 22 on the tailnet address |
| B: Tailscale ACL, `check` to `accept` for one tagged machine and one non-root user | A tailnet policy edit by Nate in the admin console | Medium: a real shell as that user, no browser step | No host change, but the user has a shell, and the policy edit is broader |
| C: lengthen the ACL `checkPeriod` | One value in the tailnet policy | Lowest change, no new access | Still needs a browser link now and then, so it does not make checks unattended |

**Decisions for Nate:** A, B or C; which machine holds the private key (it belongs in the vault, not in git); and whether to add the extra port or turn Tailscale SSH off (not recommended: it is the admin path). The draft host commands for A are in the runbook, marked as not run.

## What is detected, and what is not

| Failure | Detected by | Without the external dead-man |
|---|---|---|
| A container or service stops, hangs or turns unhealthy | Host heartbeat: ntfy push after 5 minutes, banner, Monitoring | Yes |
| Internet, Starlink or router down | `gateway`, `internet` and `dns` heartbeats and the banner (LAN-local). The push cannot leave while the internet is down: it is queued and sent, marked delayed, when it returns. The Worker alerts off-host after 10 to 15 minutes of silence | Banner only |
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
| 15 | External dead-man: two ping kinds (W-56), a bearer token, and a Cloudflare Worker `cabin-deadman` (source in this PR) as the off-host monitor; healthchecks.io stays a valid alternative | The Worker keeps one fewer vendor and is reviewable; no account or Worker is created by this PR |
| 16 | Connectivity and SSH are components, in dependency order, with parent silencing | One outage should be one page; the internet being down must not also page DNS and the public URL |
| 17 | Internet is down only when all of two pings and two HTTPS targets fail | One provider's outage must not page; ICMP being filtered must not page |
| 18 | The boot check writes `report.txt` next to `state.json` | It gives a read-only monitoring user something useful to read without Docker access |
| 19 | Messaging fan-out is not built, but `HB_NOTIFY_HOOK` is | A hook is a few lines and tested, and it lets Nate add email or SMS without a code change (see below) |

## Dead-man's switch and the Worker contract

**Host side (built).** Each cycle the service pings `HB_DEADMAN_URL` (*host alive*) and, only while the backend and the Zigbee bridge both answered, `HB_DEADMAN_PLATFORM_URL` (*platform healthy*). `HB_DEADMAN_TOKEN` is sent as `Authorization: Bearer <token>` and is never written to the state file or a log. A deliberate stop calls `HB_DEADMAN_STOP_URL` (default: the host URL plus `/fail`, which is what healthchecks.io expects). The URLs and token live in the root-only `/etc/cabin-recovery-heartbeat.env`, never in git.

**Off-host side (built as source; Nate deploys).** Cloudflare Worker `cabin-deadman`, source in `cabin-orchestration-platform/infra/cabin-deadman-worker/` with 12 tests (`node --test`), run in CI. Contract:

| Item | Value |
|---|---|
| Worker name | `cabin-deadman` |
| Ping URLs it receives | `GET` or `POST` `https://cabin-deadman.<account>.workers.dev/ping/host` and `/ping/platform`; `/stop/host` for a deliberate stop; `GET /status` returns last-seen ages and open alerts |
| Auth | `Authorization: Bearer <HEARTBEAT_TOKEN>` on every route; a wrong or missing token is 401, an unset secret is 503. Compared in constant time |
| Secrets (names only; Nate sets the values in the dashboard) | `HEARTBEAT_TOKEN`, `NTFY_TOPIC` |
| KV binding | `HEARTBEAT_KV`. Keys: `last:host`, `last:platform` (last-seen time) and `alert:host`, `alert:platform` (open-alert state, for dedupe). The namespace id in `wrangler.toml` is a placeholder to replace |
| Schedule | Cron `*/5 * * * *` |
| Silence threshold | 10 minutes (`SILENCE_MINUTES`), so detection takes 10 to 15 minutes |
| Alert | One ntfy push at priority `urgent` ("Cabin host heartbeat silent": the silence length and last-seen time); repeated every 60 minutes (`REPEAT_MINUTES`) while silent |
| Recovery message | One push at default priority when pings resume: "heartbeat is back after N min" |
| Rules | A kind that never pinged is never alerted. While *host alive* is silent, *platform healthy* is not alerted separately. A failed ntfy send is retried on the next cron rather than recorded. A deliberate stop pushes once, then its silence is treated as already announced |
| Not secrets | `NTFY_URL` (`https://ntfy.sh`), `SILENCE_MINUTES`, `REPEAT_MINUTES` are plain variables in `wrangler.toml` |

**healthchecks.io remains a valid alternative** (about five minutes: a check with period 5 and grace 5 minutes, an integration for where admins are reached, the ping URL into `HB_DEADMAN_URL`, no token). Choose one; the host service pings whichever URL it is given.

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
- **SSH setup is documented, not applied.** The host was not touched; the choice between options A, B and C is Nate's.
- **Pull probes (W-56 S3), Cloudflare tunnel-health notification (S4), UPS (S5), observer (S6)**: not built.
- **Home location.** The banner entries have location `cabin` and do not show when Home alone is selected. Fixing it means teaching the banner filter an "all locations" value, which touches `mergeStatusCheckItems` and `AlertControls`; left for a separate change.
- **Backend flicker at boot.** For a few seconds after a reboot the backend may read the pre-reboot state file before the host service rewrites it; a stale-file banner can flash and clear.
- **Ontology.** No `docs/ontology.yaml` entries yet. Candidate entities: `recovery_heartbeat_service`, `component_heartbeat`, `boot_recovery_summary`, `dead_man_switch`. Per the ontology discrepancy protocol these are flagged as proposed, not ratified.
- **Dead-man account and the Worker deployment**: not created or deployed (Nate). The Worker source and tests are in this PR; its KV namespace id in `wrangler.toml` is a placeholder.
- **Messaging fan-out beyond ntfy and the hook**: not built.
- **Self-test of delivery**: `sudo cabin-recovery-heartbeat check --notify` sends a real summary push and is the way to confirm a phone receives it.
