#!/usr/bin/env bash
# Wingman watch: one stdout line per GitHub event, meant for the Monitor tool. REST only (gh api).
#   watch.sh <issue> <pr>...        e.g. watch.sh 244 300 303
# Reports: a new comment on the issue or on a PR (not the board comment, not one that starts with
# COWORK) or an edit to one; a PR head that moves; a PR that closes or merges after the watch started;
# main moving.
# A failed request is never skipped silently. The watcher prints one "WATCH FAILING <what>" line (and
# another on every 10th retry), changes nothing it has not read, retries on the next cycle, and prints
# "WATCH RECOVERED <what>" when the request works again.
# Each comment stream (the issue, each PR) has its own cursor. A cursor moves only after every page of
# a fetch succeeded: a failed or malformed page leaves it where it was and prints nothing from that
# fetch. Each fetch overlaps the last by 10 seconds, and a comment is printed once however many
# fetches return it (keyed by id and update time).
# Env: WATCH_BOARD_ID (default 6449313109), WATCH_INTERVAL seconds (default 60), WATCH_SINCE (UTC
# timestamp such as 2026-10-27T22:00:00Z, to catch up after a restart; default now), WATCH_MAX_CYCLES
# (default 0, run until stopped; tests set it), WINGMAN_GH_TIMEOUT seconds per request (default 45),
# WINGMAN_REPO.
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
max_cycles=${WATCH_MAX_CYCLES:-0}
R="repos/$W_REPO"
for a in "$issue" "${prs[@]}" "$board" "$every" "$max_cycles"; do
  case "$a" in ''|*[!0-9]*) echo "usage: watch.sh <issue> <pr>... (numbers only); got '$a'" >&2; exit 2 ;; esac
done
start=${WATCH_SINCE:-$(date -u +%Y-%m-%dT%H:%M:%SZ)}
# GitHub's since parameter takes exactly this form; date alone would also accept words such as "yesterday".
[[ "$start" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$ ]] && [ -n "$(w_utc_minus "$start" 0)" ] ||
  { echo "WATCH_SINCE must look like 2026-10-27T22:00:00Z, got: $start" >&2; exit 2; }
streams=("$issue" "${prs[@]}")
declare -A head reported cursor seen bad
for n in "${streams[@]}"; do cursor[$n]=$start; done

# The jq program run by gh on each page of comments. First line: COUNT <comments on the page, before
# filtering>, which decides whether another page exists. Then one line per peer comment:
# "<id>@<updated_at><TAB><text>"; the key before the tab is the dedupe key.
JQ_TMPL='"COUNT \(length)", (.[] | select(.id != @BOARD@) | select((.body | startswith("COWORK")) | not) | "\(.id)@\(.updated_at)\t#@N@ comment \(.id) \(.created_at)\(if .updated_at != .created_at then " edited " + .updated_at else "" end) \(.body[0:220] | gsub("[\r\n]+";" "))")'

# fail_note <key> <what>: count a failure; print on the first and on every 10th.
fail_note() {
  bad[$1]=$(( ${bad[$1]:-0} + 1 ))
  if [ "${bad[$1]}" -eq 1 ] || [ $(( ${bad[$1]} % 10 )) -eq 0 ]; then
    echo "WATCH FAILING $2 (try ${bad[$1]}; nothing is lost, the next cycle retries)"
  fi
}
# ok_note <key> <what>: say so when a failing request works again.
ok_note() {
  if [ "${bad[$1]:-0}" -gt 0 ]; then echo "WATCH RECOVERED $2 after ${bad[$1]} failed tries"; fi
  bad[$1]=0
}

# fetch_comments <n>: read every page of the comments on #n updated since its cursor into FETCHED
# (one "key<TAB>text" per peer comment). Returns 1, with FETCHED empty, when any page fails or comes
# back malformed (a missing or non-numeric COUNT line is a failure, never "no comments").
fetch_comments() {
  local n=$1 page=1 raw cnt prog line
  FETCHED=()
  prog=${JQ_TMPL//@BOARD@/$board}; prog=${prog//@N@/$n}
  while :; do
    raw=$(w_gh api "$R/issues/$n/comments?since=${cursor[$n]}&per_page=100&page=$page" --jq "$prog" 2>/dev/null) || { FETCHED=(); return 1; }
    cnt=${raw%%$'\n'*}
    case "$cnt" in 'COUNT '[0-9]*) cnt=${cnt#COUNT } ;; *) FETCHED=(); return 1 ;; esac
    case "$cnt" in *[!0-9]*) FETCHED=(); return 1 ;; esac
    if [[ "$raw" == *$'\n'* ]]; then
      while IFS= read -r line; do [ -n "$line" ] && FETCHED+=("$line"); done <<< "${raw#*$'\n'}"
    fi
    [ "$cnt" -ge 100 ] || return 0
    page=$((page + 1))
    [ "$page" -le 30 ] || { FETCHED=(); return 1; }
  done
}

for n in "${prs[@]}"; do
  line=$(w_gh api "$R/pulls/$n" --jq '"\(.head.sha) \(.state)"' 2>/dev/null) || line="unknown unknown"
  head[$n]=${line%% *}
  [ "${line#* }" = "closed" ] && reported[$n]=1
done
main=$(w_gh api "$R/commits/main" --jq .sha 2>/dev/null) || main=unknown
echo "armed $start issue=$issue prs=${prs[*]:-none} main=${main:0:7}"

cycle=0
while true; do
  cycle=$((cycle + 1))
  for n in "${streams[@]}"; do
    t0=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    if fetch_comments "$n"; then
      for ((i = 0; i < ${#FETCHED[@]}; i++)); do
        key=${FETCHED[$i]%%$'\t'*}
        if [ -z "${seen[$key]:-}" ]; then
          seen[$key]=1
          echo "${FETCHED[$i]#*$'\t'}"
        fi
      done
      cursor[$n]=$(w_utc_minus "$t0" 10)
      ok_note "c$n" "the comments of #$n"
    else
      fail_note "c$n" "the comments of #$n"
    fi
  done
  for n in "${prs[@]}"; do
    if line=$(w_gh api "$R/pulls/$n" --jq '"\(.head.sha) \(.state) \(.merged)"' 2>/dev/null) && [ -n "$line" ]; then
      ok_note "p$n" "the state of #$n"
      read -r h s m <<< "$line"
      if [ "${head[$n]}" = "unknown" ]; then
        head[$n]=$h
      elif [ -n "$h" ] && [ "$h" != "${head[$n]}" ]; then
        echo "#$n head moved ${head[$n]:0:7} -> ${h:0:7}"
        head[$n]=$h
      fi
      if [ "$s" = "closed" ] && [ -z "${reported[$n]:-}" ]; then
        echo "#$n closed merged=$m"
        reported[$n]=1
      fi
    else
      fail_note "p$n" "the state of #$n"
    fi
  done
  if mm=$(w_gh api "$R/commits/main" --jq .sha 2>/dev/null) && [ -n "$mm" ]; then
    ok_note main "the main head"
    if [ "$main" = "unknown" ]; then
      main=$mm
    elif [ "$mm" != "$main" ]; then
      echo "main moved ${main:0:7} -> ${mm:0:7}"
      main=$mm
    fi
  else
    fail_note main "the main head"
  fi
  [ "$max_cycles" -eq 0 ] || [ "$cycle" -lt "$max_cycles" ] || break
  sleep "$every"
done
