---
name: cabin-storage-tiers
description: >
  Storage rules for the cabin M920q: which disk is hot (NVMe) versus cold
  (the rotational /storage HDD), what must never be deleted without a warning
  (BI telemetry: temperature, humidity, presence, CO2 and the other sensor
  readings in cabin_event), what is disposable (recorded video, nightly
  backups), and how to size and clean up safely. Load this whenever a task
  touches disk space, retention, backups, archives, Frigate recordings, the
  telemetry archival job, /storage or the M920q's drives, or whenever someone
  asks to delete, prune, purge, dump or archive data.
---

# Cabin storage tiers

Read `docs/STORAGE_ARCHITECTURE.md` first. It has the live numbers (2026-10-10) and the open work. This file is the short version a session must not get wrong.

## Facts that are easy to forget

1. **The M920q has two disks and `/storage` is the cold tier.** `/` is a 938 GB NVMe SSD (hot: Postgres, Docker, the OS). `/storage` is a 916 GB rotational HDD (warm and cold: video, telemetry archives, backups). Nate, 2026-10-10: use the M920q's HDD as cold storage. Do not put Postgres or anything latency-sensitive on it, and do not treat `/storage` as unlimited: it reached 94% full unnoticed.
2. **BI telemetry is the data worth keeping.** `TELEMETRY` rows in `cabin_event` (temp, humidity, CO2, leak, battery, ...) and the presence and CO-alarm events. They are small (about 7 MB per day), so there is no reason to lose them.
3. **Dump-warning rule (Nate, 2026-10-10).** Never delete, archive-and-delete or "dump" BI telemetry without warning the owner first and waiting. The monthly `TelemetryArchivalService` job (the 1st, 02:00) deletes from the live table after archiving and currently has no warning (W-49). Do not add or schedule any other purge of these rows.
4. **Video and nightly backups are disposable.** Nate reviews the cameras himself and has downloaded what he wants; the cabin claim needs presence history, not footage. Frigate retention stays 5 days continuous, 10 days alerts and detections. Backups are rotating snapshots, not archives.
5. **The Reolink's 4K recording is 27 to 40 GB per day**, not the 6.3 GB per day Frigate's bandwidth stat suggests and not the old 80 to 160 GB estimate. Size from `du` of `/storage/cameras/frigate/recordings/<day>`.

## Before proposing any cleanup

- `df -h /storage /`, then `du` the real directories one level at a time (a full `du` of `/storage/backups` takes several minutes; run it in the background).
- Say which tier each thing is in and whether it falls under the dump-warning rule.
- Check what *creates* the data (systemd timers, cron, containers) before deleting what it made; otherwise it grows back. On 2026-10-10 the 640 GB in `/storage/backups` came from four `backup-*.timer` units running `backup-modular`, which kept every nightly copy.

## Deleting

Permanent deletion of files is done by the owner, not by an agent session. Prepare a script that **lists first** and removes only with `--apply`, restricted to exact name patterns, and state what it will not touch. `scripts/prune-storage-backups.sh` is the model. Never delete BI telemetry, Postgres data, `/storage/archives`, or `/storage/services`.
