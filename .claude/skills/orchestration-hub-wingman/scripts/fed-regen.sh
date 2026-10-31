#!/usr/bin/env bash
# Wingman fed-regen: write the mirrors from their sources. A mirror is generated, never hand-edited.
#   fed-regen.sh           regenerate AGENTS.md from CLAUDE.md; byte-copy .claude/skills to .agents/skills
#   fed-regen.sh --check   change nothing; print what differs; exit 1 if a mirror is not what regeneration writes
# Run from anywhere inside the clone. Commit the source change and the regenerated mirror in one PR.
# Files that exist only in .agents/skills are reported, never deleted (removing one is a decision).
# Works on Linux and on Windows (Git Bash): text is compared without carriage returns, so a CRLF checkout
# is not drift and is never rewritten; with core.fileMode=false the executable bit is the git index's, and
# a NOTE prints the one git command that sets it when a mirror's index mode is not its source's.
set -u
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
. "$here/lib.sh"
root=$(git rev-parse --show-toplevel 2>/dev/null) || { say FAIL "not inside a git clone"; exit 2; }
cd "$root" || exit 2
w_fm_probe
[ -f CLAUDE.md ] || { say FAIL "CLAUDE.md missing"; exit 2; }
[ -d .claude/skills ] || { say FAIL ".claude/skills missing"; exit 2; }

check_only=0
[ "${1:-}" = "--check" ] && check_only=1
tmp=$(mktemp) && trap 'rm -f "$tmp"' EXIT
w_gen_agents > "$tmp"
if ! head -1 "$tmp" | grep -q '^# AGENTS\.md' || ! grep -q '^This file gives Codex full context' "$tmp"; then
  say FAIL "CLAUDE.md no longer has the title or the 'gives Claude Code full context' line this generator rewrites: update w_gen_agents in lib.sh"
  exit 2
fi

if [ -f AGENTS.md ] && cmp -s "$tmp" <(tr -d '\r' < AGENTS.md); then
  say OK "AGENTS.md is CLAUDE.md regenerated"
elif [ "$check_only" -eq 1 ]; then
  say FAIL "AGENTS.md is not CLAUDE.md regenerated ($(diff "$tmp" <(tr -d '\r' < AGENTS.md 2>/dev/null) 2>/dev/null | grep -c '^[<>]') line(s) differ)"
else
  cp "$tmp" AGENTS.md && say OK "AGENTS.md regenerated from CLAUDE.md"
fi

# Every file under .claude/skills must exist under .agents/skills with the same content (text: ignoring
# carriage returns; binary: byte for byte) and the same mode (see w_mode in lib.sh).
while IFS= read -r f; do
  rel=${f#.claude/skills/}
  m=".agents/skills/$rel"
  why=""; mode_only=0
  if [ ! -f "$m" ]; then why="missing"
  elif ! w_same "$f" "$m"; then why="content differs"
  elif ! w_modes_agree "$f" "$m"; then why="mode differs: source $(w_mode "$f"), mirror $(w_mode "$m")"; mode_only=1
  fi
  # With core.fileMode=false a mode difference lives in the git index: copying cannot change it, the
  # command from w_mode_fix can. Everything else is fixed by copying.
  if [ -n "$why" ] && ! { [ "$mode_only" -eq 1 ] && [ "$W_FM" = "false" ]; }; then
    if [ "$check_only" -eq 1 ]; then
      say FAIL "mirror differs ($why): $rel"
    else
      mkdir -p "$(dirname "$m")" && cp -p "$f" "$m" && say OK "copied $rel"
    fi
  fi
  fix=$(w_mode_fix "$f" "$m")
  if [ -n "$fix" ]; then
    if [ "$check_only" -eq 1 ] && [ -n "${W_IDX[$m]:-}" ]; then
      say FAIL "mirror mode in the git index differs from its source ($rel): $fix"
    else
      say NOTE "set the mirror's mode in the git index ($rel): $fix"
    fi
  fi
done < <(find .claude/skills -type f | sort)
if [ -d .agents/skills ]; then
  while IFS= read -r m; do
    rel=${m#.agents/skills/}
    [ -f ".claude/skills/$rel" ] || say WARN "mirror-only file (not deleted): $rel"
  done < <(find .agents/skills -type f | sort)
fi
[ "$w_fails" -eq 0 ]
