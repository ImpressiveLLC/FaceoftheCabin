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

# w_verdict <pr> <full-head-sha>: print "<CLEAN|HOLD|none> <hex|-> <match|older|->".
# A verdict is the newest PR comment that starts with CLEAN or HOLD (optionally after a
# "CODEX ..:" header). It binds only if a hex token in it is a prefix of the head sha.
w_verdict() {
  local pr="$1" head="$2" line v tok found=""
  line=$(gh api "repos/$W_REPO/issues/$pr/comments?per_page=100" --paginate --jq \
    '[.[]|select(.body|test("^(CODEX[^\\n]{0,40}: *)?(CLEAN|HOLD)"))]
     |if length==0 then empty else (last|.body[0:400]|gsub("\n";" ")) end' 2>/dev/null | grep . | tail -1)
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
W_AGENTS_HDR='<!-- GENERATED from CLAUDE.md by .claude/skills/wingman/scripts/fed-regen.sh. Do not edit by hand: change CLAUDE.md, run the script, commit both. Drift check: fed-check.sh. -->'
w_gen_agents() {
  awk -v hdr="$W_AGENTS_HDR" '
    NR == 1 { sub(/^# CLAUDE\.md/, "# AGENTS.md"); print; print hdr; next }
    { sub(/^This file gives Claude Code full context/, "This file gives Codex full context"); print }
  ' CLAUDE.md
}
