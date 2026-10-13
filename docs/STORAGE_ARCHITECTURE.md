# Storage architecture

Where the M920q's data lives, which disk is which tier, what must never be dropped, and what is disposable. Numbers are from the live host on **2026-10-10** (read-only checks) unless stated. Written after `/storage` was found 94% full while sizing recording retention (W-8).

## The two disks

| Disk | Mount | Size | Role | Holds |
|---|---|---|---|---|
| NVMe SSD | `/` | 938 GB, 17% used (740 GB free) | **Hot tier** | Docker (images 58 GB, volumes 18 GB including Postgres `infra_cabin_pgdata`, build cache 43 GB), the OS, `/home` (18 GB, 13 GB of it `poc1-work`) |
| Rotational HDD | `/storage` | 916 GB, **94% used** (57 GB free) | **Warm and cold tier**, and bulk storage | Frigate recordings, telemetry archives, service configs, backups |

**The M920q's HDD (`/storage`) is the cold-storage tier** (Nate, 2026-10-10; `TelemetryArchivalService` has said the same since 2026-08-25). Anything long-lived and bulky goes there, and nothing that needs fast random reads (Postgres) does. A different deployment may use other hardware or a cloud bucket for cold storage; `EXECUTION_PLAN_2026-08-07_template-theme-camera.md` §4c names a `cold_storage_backend` ontology entity (`local_disk | s3_compatible | other`) for that. It is planned and **not yet in `docs/ontology.yaml`**. In this deployment its value is the local disk `/storage`.

## What is on `/storage` today (813 GB used)

| Path | Size | What |
|---|---|---|
| `/storage/backups/` | **640 GB** | Nightly rsync snapshots by four systemd timers (`backup-home`, `backup-configs`, `backup-services`, `backup-data`), run by `/usr/local/bin/backup-modular`. **No pruning** until W-48: 101 full copies of `/home` (577 GB, mostly the 13 GB `poc1-work`), about 100 of `/storage/services` (35 GB), plus one 29 GB system copy from 2026-06-29 |
| `/storage/cameras/frigate/recordings/` | 172 GB | Frigate continuous (5 days) and alert/detection (10 days, mode motion) recordings. The Reolink's 4K main stream measures **27 to 40 GB per day** (Frigate's own bandwidth figure, 6.3 GB per day, understates it; the old 80 to 160 GB estimate overstated it) |
| `/storage/archives/` | 0.43 GB | Telemetry cold copies, see below |
| `/storage/services/` | 0.64 GB | Home Assistant, Zigbee2MQTT, Frigate config, Node-RED, mosquitto, uptime-kuma |

## Telemetry: the data that must be kept

The readings behind the BI metrics (temperature, humidity, presence, CO2, leak, battery and the rest) are `TELEMETRY` rows in Postgres `cabin_event`, plus the discrete events (`PRESENCE_CHANGED`, `KIDDE_CO_ALARM_CHANGED`, ...).

| Tier | Where | Detail |
|---|---|---|
| Hot | Postgres on the NVMe | 3-month window (`cabin.telemetryArchival.hotRetentionMonths`). 515 MB for 755,000 rows over 70 days, about 13,800 rows (about 7 MB) per day: roughly 2.5 GB per year. Telemetry is tiny next to video |
| Cold | `/storage/archives/cabin_event/` | Monthly: whole months older than the hot window are written as gzipped JSONL, then **deleted from the live table** (cron `0 0 2 1 * *`, the 1st at 02:00). Hourly additive copy to `incremental/incremental-YYYY-MM-DD.jsonl` since 2026-09-14 |
| Independent copy | `infra/telemetry-backup-agent/` (D19 Option B) | Documented in `MAINTENANCE.md`, but **no such container and no `/storage/telemetry-backup/` existed on 2026-10-10**. Both copies above depend on `cabin-backend` being alive |

### The dump-warning rule (Nate, 2026-10-10)

**BI telemetry is never deleted, archived-and-deleted or "dumped" without a warning first.** When the amount gets large, the system tells the owner what it is about to remove (how much, which range, where the cold copy is) and waits, rather than deleting on a schedule. The monthly archive-and-delete job above is exactly such a dump, and **today it has no warning**. At current volume it is small, so "massive" has to be defined by the owner (a size or row-count threshold on the live table, or on the archive directory). Tracked as **W-49**.

## What is disposable

- **Recorded video.** Nate, 2026-10-10: he reviews the cameras himself, anything he wanted is already downloaded, and he is fine losing stored video. What the cabin claim needs is the **presence history**, not footage. Retention stays at the configured 5 days continuous and 10 days alerts/detections (W-8); no purge of existing video is needed once backups are pruned.
- **Old nightly backups, and anything regenerable inside them** (`poc1-work`, build tools, caches, the GitHub runner).

## Rules for any session touching storage

1. Check `df -h /storage` and `/` first. A volume above 80% is a finding, not background.
2. Size before you propose: `du` the real directories and say which tier each belongs to. Do not assume.
3. Telemetry follows the dump-warning rule above. Video and backups do not, but are still removed oldest-first and never while a dry run has not been read.
4. Permanent deletion of files is run by the owner. Prepare a script that lists first and removes only with `--apply` (see `scripts/prune-storage-backups.sh`), and say what it will not touch.
5. Anything bulky and long-lived goes to `/storage`; anything that needs fast random I/O stays on the NVMe.

## Pruning: `cabin-storage-prune` (W-61)

`scripts/host/cabin-storage-prune` is the one reviewed place that decides what disposable storage may be removed. It reports first, plans second, and removes only what is named. It replaces improvised `rm` and extends the one-time `scripts/prune-storage-backups.sh` (still valid for the four dated backup folders).

**Install once on the M920q** (Nate; an agent session cannot write there):

```bash
sudo install -m 0755 scripts/host/cabin-storage-prune /usr/local/bin/cabin-storage-prune
# optional: only if you want to change a default (see the file for examples)
sudo install -m 0644 scripts/host/cabin-storage-prune.conf.example /etc/cabin-storage-prune.conf
```

**Use it, in this order:**

```bash
cabin-storage-prune report                       # what is prunable and the estimated space (read-only; a few minutes on the HDD, --no-sizes is instant)
cabin-storage-prune plan --only all-safe         # exactly which folders would go (read-only)
sudo cabin-storage-prune apply --only all-safe   # shows the plan, asks you to type APPLY, then removes
sudo cabin-storage-prune apply --only system-copy --yes-delete system-copy   # a whole directory needs both flags
```

`report --json` gives the same data for a program (the in-app control, W-62, reads it).

**What it will and will not do**

- Targets are `dated-dirs` (keep the newest N of `YYYY-MM-DD` folders), `old-files` (older than N days, optional glob), `whole-dir`, `docker-cache` and `journal`. `all-safe` is every target except `whole-dir`.
- It never touches BI telemetry or databases: any path containing `archives`, `cabin_event`, `telemetry`, `postgres` or docker volumes is refused, as is a symlink that resolves into one. This holds whatever the config says.
- A target must be strictly inside an `allowed_root` (default `/storage/backups`), and deletion never crosses a filesystem.
- Nothing is removed without `apply`, an explicit `--only`, and a typed confirmation (or `--yes`). Every removal is logged to `/var/log/cabin-storage-prune.log`.

**Adjusting it.** Settings live in `/etc/cabin-storage-prune.conf` (root-edited today; the in-app control for an authorized user is W-62). Change how many snapshots to keep, add a target, or turn one `off`. To manage other storage, mount it and add its directory with `allowed_root`: an SD card or extra disk, a LAN share (NFS or SMB), or a remote volume are all managed through the path they are mounted at (W-63 covers registering and monitoring them, not just pruning them).

**Tested** by `scripts/host/test_cabin_storage_prune.sh` (32 checks against a scratch folder, including refusal of outside paths, protected names and symlink escapes; the symlink checks need Linux).

## Disk monitor (W-11, PR #145)

`StorageMonitorService` in `cabin-backend` measures `storage-hdd` (the HDD, seen through the archive mount `/app/archives/cabin_event`) and `root-nvme` (`/`) every 5 minutes. At **80%** it publishes a WARN `STORAGE_THRESHOLD` event, at **90%** a CRITICAL one, and `STORAGE_CLEARED` once it falls 3 points below the line; the notice repeats every 24 hours while above, and survives a restart without re-paging. The event shows in Rules & Alerts' 24-hour list and, when `CABIN_ALERT_NTFY_TOPIC` is set (W-51 S1), is pushed to the phone (WARN at ntfy priority high, CRITICAL urgent). `GET /api/system/storage` reports the current figures. Tune with `STORAGE_WARN_PERCENT`, `STORAGE_CRITICAL_PERCENT`, `STORAGE_RENOTIFY_HOURS`, `STORAGE_MONITOR_MOUNTS`. **It reports only and never deletes** (see the rules above); the automatic purge W-11 first described is W-11b.

## Open work

| ID | What |
|---|---|
| W-61 | `scripts/host/cabin-storage-prune`: report, plan and discrete owner-run removal with a config file (built, install and run by Nate) |
| W-62 | In-app Act on storage: a multi-select prune control and authorized settings in See / Think / Act (proposed, see below) |
| W-63 | Storage beyond `/storage`: SD cards, extra disks, and LAN or WAN network storage, registered, monitored and prunable (proposed) |
| W-48 | Backup retention and exclusions: `scripts/host/backup-modular` (prunes to the newest N, leaves regenerable data out) and the one-time `scripts/prune-storage-backups.sh` |
| W-49 | Dump warning before telemetry is archived-and-deleted, with an owner-set threshold |
| W-11 | `/storage` thresholds: warning at 80%, critical at 90%, in-app and ntfy. Built (PR #145). The disk had reached 94% unnoticed |
| W-11b | The automatic oldest-first purge W-11 first described. Deferred: permanent deletion is owner-run and telemetry needs a warning first (W-49) |
| W-8 / W-10 / W-12 | Recording retention decision (kept at 5/10 days), Warm and Cold rules |
| D19 Option B | Deploy `telemetry-backup-agent` so a copy exists that does not depend on `cabin-backend` |

### Proposed: how the in-app Act (W-62) reaches the host

Not built and not decided. The web container must stay unprivileged, so it cannot delete host files. The proposal keeps the deletion logic in the one script above and uses the MQTT bus the platform already runs on: a small root-owned host service (installed once by the owner) publishes `cabin-storage-prune report --json` to a retained `cabin/system/storage/prune-plan` topic; `cabin-backend` shows it in See / Think / Act as a multi-select list with sizes; an authorized user's selection becomes an audited `prune-request` that the host service re-validates against its own config and runs through `apply --only ...`, publishing the result. The same path carries config changes (validated, versioned, written to `/etc/cabin-storage-prune.conf`). Decisions needed from Nate and Cowork are listed on W-62.
