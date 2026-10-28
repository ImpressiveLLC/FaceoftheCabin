# Wingman shared helpers. Source this file; do not run it.
# REST only (`gh api`): GraphQL can be blocked in cloud sessions. Read-only helpers.

W_REPO="${WINGMAN_REPO:-ImpressiveLLC/FaceoftheCabin}"
w_fails=0
w_holds=0

# say <LEVEL> <text>: LEVEL is OK, NOTE, WARN, HOLD or FAIL. HOLD and FAIL are counted.
say() {
  printf '%-5s %s\n' "$1" "$2"
  case "$1" in
    FAIL) w_fails=$((w_fails + 1)) ;;
    HOLD) w_holds=$((w_holds + 1)) ;;
  esac
  return 0
}

# w_need <tool>: stop with exit 2 when a required tool is missing.
w_need() {
  command -v "$1" >/dev/null 2>&1 || { say FAIL "missing tool: $1"; exit 2; }
}

# w_cdt <iso8601-utc>: print the time as HH:MM CDT (America/Chicago). Empty on failure.
w_cdt() {
  TZ=America/Chicago date -d "$1" '+%H:%M' 2>/dev/null
}

# w_age_min <iso8601>: minutes since that time. Empty on failure.
w_age_min() {
  local t
  t=$(date -u -d "$1" +%s 2>/dev/null) || return 0
  echo $(( ( $(date -u +%s) - t ) / 60 ))
}

# w_pages <rest-path> [<jq filter for one item>]: read every page of a REST list and print the filter's
# output for each item, in the API's order (oldest first for comments). All or nothing: when a page
# fails, comes back malformed (not a list) or the list runs past W_PAGES_MAX pages (default 30, which
# is also GitHub's cap of 3000 files on a PR), it prints nothing and returns 1, so a caller can treat
# the read as incomplete and a gate can hold. Use this, never `gh api --paginate`: that follows the
# response's Link header, which names a repositories/{id}/... URL, and the cloud agent proxy refuses
# those (403), so there it silently returns page 1 only (seen 2026-10-28: the lane line of ctx-audit.sh
# read the first 100 of #244's 211 comments). An explicit ?page=N on the repos/{owner}/{repo}/...
# path works everywhere, as watch.sh already did. The filter runs once per item; the count of items on
# the page comes back as the first line of each answer and decides whether another page exists.
w_pages() {
  local path="$1" item="${2:-.}" page=1 sep='?' raw cnt acc=""
  case "$path" in *\?*) sep='&' ;; esac
  while :; do
    raw=$(w_gh api "${path}${sep}per_page=100&page=${page}" --jq \
      "if type != \"array\" then error(\"not a list\") else (\"COUNT \\(length)\", (.[] | ($item))) end" 2>/dev/null) || return 1
    cnt=${raw%%$'\n'*}
    case "$cnt" in 'COUNT '[0-9]*) cnt=${cnt#COUNT } ;; *) return 1 ;; esac
    case "$cnt" in *[!0-9]*) return 1 ;; esac
    if [[ "$raw" == *$'\n'* ]]; then acc+="${raw#*$'\n'}"$'\n'; fi
    if [ "$cnt" -lt 100 ]; then printf '%s' "$acc"; return 0; fi
    page=$((page + 1))
    [ "$page" -le "${W_PAGES_MAX:-30}" ] || return 1
  done
}

# w_verdict <pr> <full-head-sha>: print "<CLEAN|HOLD|none|error> <hex|-> <match|older|->".
# A verdict is the newest PR comment that starts with CLEAN or HOLD (optionally after a
# "CODEX ..:" header). It binds only if a hex token in it is a prefix of the head sha. "error" means a
# page of the comments could not be read, so the verdict is unknown (not absent): callers hold.
w_verdict() {
  local pr="$1" head="$2" raw line v tok found=""
  raw=$(w_pages "repos/$W_REPO/issues/$pr/comments" \
    'select(.body|test("^(CODEX[^\\n]{0,40}: *)?(CLEAN|HOLD)")) | (.body[0:400]|gsub("\n";" "))') \
    || { echo "error - -"; return 0; }
  line=$(printf '%s\n' "$raw" | grep . | tail -1)
  if [ -z "$line" ]; then echo "none - -"; return 0; fi
  v=$(printf '%s' "$line" | grep -oE 'CLEAN|HOLD' | head -1)
  for tok in $(printf '%s' "$line" | grep -oE '[0-9a-f]{7,40}'); do
    case "$head" in
      "$tok"*) echo "$v ${tok:0:7} match"; return 0 ;;
    esac
    [ -z "$found" ] && found="${tok:0:7}"
  done
  if [ -n "$found" ]; then echo "$v $found older"; else echo "$v - -"; fi
}

# w_is_deploy_path <path>: true when a merge touching it can start a deploy workflow.
w_is_deploy_path() {
  case "$1" in
    cabin-orchestration-platform/*|family-hub/*|discovery-service/*|.github/workflows/*) return 0 ;;
    *) return 1 ;;
  esac
}

# w_gen_agents: print AGENTS.md as it must be, from ./CLAUDE.md: three changes only. The title
# line, the "gives Claude Code full context" sentence (Codex reads AGENTS.md), and a generated-file
# header under the title. Used by fed-regen.sh (writes it) and fed-check.sh (compares to it).
# Output is LF only whatever the line endings of CLAUDE.md (see "Line endings and modes" below).
W_AGENTS_HDR='<!-- GENERATED from CLAUDE.md by .claude/skills/orchestration-hub-wingman/scripts/fed-regen.sh. Do not edit by hand: change CLAUDE.md, run the script, commit both. Drift check: fed-check.sh. -->'
w_gen_agents() {
  tr -d '\r' < CLAUDE.md | awk -v hdr="$W_AGENTS_HDR" '
    NR == 1 { sub(/^# CLAUDE\.md/, "# AGENTS.md"); print; print hdr; next }
    { sub(/^This file gives Claude Code full context/, "This file gives Codex full context"); print }
  '
}

# ---- Line endings and modes -------------------------------------------------------------------
# A Windows checkout can differ from a Linux checkout of the same commit in two ways: core.autocrlf
# rewrites line endings in markdown, JSON and similar files, and Git for Windows sets
# core.fileMode=false, so the executable bit exists only in the git index. The federation checks
# give the same answer on both machines by comparing text without carriage returns and, when
# core.fileMode is false, modes from the index.

# w_same <a> <b>: true when two files hold the same content. Text files are compared with carriage
# returns removed; anything binary is compared byte for byte.
w_same() {
  cmp -s "$1" "$2" && return 0
  LC_ALL=C grep -Iq . "$1" 2>/dev/null && LC_ALL=C grep -Iq . "$2" 2>/dev/null || return 1
  cmp -s <(tr -d '\r' < "$1") <(tr -d '\r' < "$2")
}

# w_h8 <path>: first 8 hex characters of the sha256 of the file. Text files are hashed with carriage
# returns removed, so two checkouts of one commit print the same value whatever their line endings.
w_h8() {
  if LC_ALL=C grep -Iq . "$1" 2>/dev/null; then tr -d '\r' < "$1" | sha256sum | cut -c1-8
  else sha256sum "$1" 2>/dev/null | cut -c1-8; fi
}

# w_fm_probe: call once, after cd to the clone root. Sets W_FM to this checkout's core.fileMode
# (default true). When it is false, loads the index modes of the federated trees into W_IDX
# (path -> 755 or 644), because the file's own mode is then not what git records.
declare -A W_IDX=()
W_FM=true
w_fm_probe() {
  local v mode path
  v=$(git config --bool core.fileMode 2>/dev/null) || v=true
  W_FM=${v:-true}
  [ "$W_FM" = "false" ] || return 0
  while read -r mode _ _ path; do
    case "$mode" in 100755) W_IDX[$path]=755 ;; 100644) W_IDX[$path]=644 ;; esac
  done < <(git -c core.quotePath=false ls-files -s -- .claude/skills .agents/skills 2>/dev/null)
}

# w_mode <path>: the octal mode that counts on this checkout: the file's own, or, when core.fileMode
# is false, the git index's. Empty when unknown (a file not yet in the index).
w_mode() {
  if [ "$W_FM" = "false" ]; then echo "${W_IDX[$1]:-}"; else stat -c %a "$1" 2>/dev/null; fi
}

# w_modes_agree <src> <mirror>: true when the two modes are equal or either is unknown.
w_modes_agree() {
  local a b
  a=$(w_mode "$1"); b=$(w_mode "$2")
  [ -z "$a" ] || [ -z "$b" ] || [ "$a" = "$b" ]
}

# w_mode_fix <src> <mirror>: with core.fileMode=false, print the git command that gives the mirror
# its source's index mode. Nothing when the modes agree, the source is not in the index, or the
# mirror is untracked and its source is 644 (git adds it as 644).
w_mode_fix() {
  [ "$W_FM" = "false" ] || return 0
  local s=${W_IDX[$1]:-} m=${W_IDX[$2]:-} flag
  [ -n "$s" ] && [ "$s" != "$m" ] || return 0
  [ -z "$m" ] && [ "$s" = 644 ] && return 0
  [ "$s" = 755 ] && flag='+x' || flag='-x'
  echo "git update-index --add --chmod=$flag -- $2"
}

# w_utc_minus <iso8601-utc> <seconds>: the same instant earlier by that many seconds, as UTC.
w_utc_minus() {
  date -u -d "$1 - $2 seconds" '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null
}

# w_gh <gh args>: gh with a time limit, so one hung request cannot freeze a long-running loop.
w_gh() {
  if command -v timeout >/dev/null 2>&1; then timeout "${WINGMAN_GH_TIMEOUT:-45}" gh "$@"; else gh "$@"; fi
}
