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

## Open work

| ID | What |
|---|---|
| W-48 | Backup retention and exclusions: `scripts/host/backup-modular` (prunes to the newest N, leaves regenerable data out) and the one-time `scripts/prune-storage-backups.sh` |
| W-49 | Dump warning before telemetry is archived-and-deleted, with an owner-set threshold |
| W-11 | `/storage` thresholds (warn 80%, purge at 90% oldest-first, never Cold or Pinned, ntfy). Not built; the disk reached 94% unnoticed |
| W-8 / W-10 / W-12 | Recording retention decision (kept at 5/10 days), Warm and Cold rules |
| D19 Option B | Deploy `telemetry-backup-agent` so a copy exists that does not depend on `cabin-backend` |
