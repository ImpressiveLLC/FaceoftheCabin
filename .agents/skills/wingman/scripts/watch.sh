#!/usr/bin/env bash
# Wingman watch: one stdout line per GitHub event, meant for the Monitor tool. REST only (gh api).
#   watch.sh <issue> <pr>...        e.g. watch.sh 244 299 300 301
# Reports: a new comment on the issue or on a PR (not the board comment, not one that starts with
# COWORK); a PR head that moves; a PR that closes or merges after the watch started; main moving.
# Env: WATCH_BOARD_ID (default 6449313109), WATCH_INTERVAL seconds (default 60), WATCH_SINCE (UTC
# timestamp such as 2026-10-27T22:00:00Z, to catch up after a restart; default now), WINGMAN_REPO.
# Under the Monitor tool use timeout_ms 1800000 (its maximum) and re-arm on the expiry notice.
set -u
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
. "$here/lib.sh"
issue=${1:?usage: watch.sh <issue> <pr>...}
shift
prs=("$@")
board=${WATCH_BOARD_ID:-6449313109}
every=${WATCH_INTERVAL:-60}
R="repos/$W_REPO"
declare -A head reported
last=${WATCH_SINCE:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}
for n in "${prs[@]}"; do
  line=$(gh api "$R/pulls/$n" --jq '"\(.head.sha) \(.state)"' 2>/dev/null || echo "unknown unknown")
  head[$n]=${line%% *}
  [ "${line#* }" = "closed" ] && reported[$n]=1
done
main=$(gh api "$R/commits/main" --jq .sha 2>/dev/null || echo unknown)
echo "armed $last issue=$issue prs=${prs[*]:-none} main=${main:0:7}"
while true; do
  now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  for n in "$issue" "${prs[@]}"; do
    gh api "$R/issues/$n/comments?since=$last&per_page=100" \
      --jq ".[] | select(.id != $board) | select((.body | startswith(\"COWORK\")) | not) | \"#$n comment \\(.id) \\(.created_at) \\(.body[0:220] | gsub(\"\\n\";\" \"))\"" \
      2>/dev/null || true
  done
  for n in "${prs[@]}"; do
    line=$(gh api "$R/pulls/$n" --jq '"\(.head.sha) \(.state) \(.merged)"' 2>/dev/null) || continue
    read -r h s m <<< "$line"
    if [ -n "$h" ] && [ "$h" != "${head[$n]}" ]; then
      echo "#$n head moved ${head[$n]:0:7} -> ${h:0:7}"
      head[$n]=$h
    fi
    if [ "$s" = "closed" ] && [ -z "${reported[$n]:-}" ]; then
      echo "#$n closed merged=$m"
      reported[$n]=1
    fi
  done
  mm=$(gh api "$R/commits/main" --jq .sha 2>/dev/null) || mm=unknown
  if [ "$mm" != "unknown" ] && [ "$mm" != "$main" ]; then
    echo "main moved ${main:0:7} -> ${mm:0:7}"
    main=$mm
  fi
  last=$now
  sleep "$every"
done
