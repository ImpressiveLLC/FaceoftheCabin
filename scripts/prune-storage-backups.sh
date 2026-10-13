#!/usr/bin/env bash
# One-time cleanup of /storage/backups (W-48): keep the newest KEEP dated snapshots
# of home, services, configs and data, remove the older ones. Dry run unless --apply.
#
#   sudo bash prune-storage-backups.sh            # lists what it would remove
#   sudo KEEP=3 bash prune-storage-backups.sh --apply
#
# Touches only directories named exactly YYYY-MM-DD directly under
# /storage/backups/{home,services,configs,data}. It does NOT touch
# /storage/backups/system (one full system copy from 2026-06-29, about 29 GB; remove
# it yourself if you do not want it), /storage/backups/{logs,etc,docker,databases,
# homeassistant}, or anything outside /storage/backups.
set -euo pipefail

ROOT=${ROOT:-/storage/backups}   # override only for testing against a scratch folder
KEEP=${KEEP:-3}
APPLY=0
[ "${1:-}" = "--apply" ] && APPLY=1

[ "$(id -u)" -eq 0 ] || [ "${PRUNE_TEST:-}" = 1 ] || { echo "ABORT: run with sudo (the snapshot folders are owned by root)"; exit 1; }
[[ "$KEEP" =~ ^[0-9]+$ ]] && [ "$KEEP" -ge 1 ] || { echo "ABORT: KEEP must be a whole number, 1 or more"; exit 1; }

echo "before:"; df -h "$ROOT" | tail -1
for type in home services configs data; do
  dir="$ROOT/$type"
  [ -d "$dir" ] || { echo "$type: no such folder, skipped"; continue; }
  mapfile -t all < <(find "$dir" -mindepth 1 -maxdepth 1 -type d -regextype posix-extended \
                       -regex '.*/[0-9]{4}-[0-9]{2}-[0-9]{2}' -printf '%f\n' | sort)
  n=${#all[@]}
  drop=$(( n > KEEP ? n - KEEP : 0 ))
  if [ "$drop" -eq 0 ]; then echo "$type: $n snapshots, nothing to remove"; continue; fi
  echo "$type: $n snapshots, keeping the newest $KEEP (${all[$((n-KEEP))]} to ${all[$((n-1))]}), removing $drop (${all[0]} to ${all[$((drop-1))]})"
  [ "$APPLY" -eq 1 ] || continue
  for ((i = 0; i < drop; i++)); do
    # ${VAR:?} makes the shell stop instead of running rm if either is ever empty
    rm -rf --one-file-system -- "${dir:?}/${all[$i]:?}"
    echo "  removed $dir/${all[$i]}"
  done
done
echo "after:"; df -h "$ROOT" | tail -1
[ "$APPLY" -eq 1 ] || echo "(dry run: nothing was removed; add --apply to remove)"
