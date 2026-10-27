#!/usr/bin/env bash
# Wingman fed-regen: write the mirrors from their sources. A mirror is generated, never hand-edited.
#   fed-regen.sh           regenerate AGENTS.md from CLAUDE.md; byte-copy .claude/skills to .agents/skills
#   fed-regen.sh --check   change nothing; print what differs; exit 1 if a mirror is not what regeneration writes
# Run from anywhere inside the clone. Commit the source change and the regenerated mirror in one PR.
# Files that exist only in .agents/skills are reported, never deleted (removing one is a decision).
set -u
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
. "$here/lib.sh"
root=$(git rev-parse --show-toplevel 2>/dev/null) || { say FAIL "not inside a git clone"; exit 2; }
cd "$root" || exit 2
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

if cmp -s "$tmp" AGENTS.md; then
  say OK "AGENTS.md is CLAUDE.md regenerated"
elif [ "$check_only" -eq 1 ]; then
  say FAIL "AGENTS.md is not CLAUDE.md regenerated ($(diff "$tmp" AGENTS.md 2>/dev/null | grep -c '^[<>]') line(s) differ)"
else
  cp "$tmp" AGENTS.md && say OK "AGENTS.md regenerated from CLAUDE.md"
fi

# Every file under .claude/skills must exist under .agents/skills, byte-identical and with the same mode.
while IFS= read -r f; do
  rel=${f#.claude/skills/}
  m=".agents/skills/$rel"
  if [ -f "$m" ] && cmp -s "$f" "$m" && [ "$(stat -c %a "$f")" = "$(stat -c %a "$m")" ]; then continue; fi
  if [ "$check_only" -eq 1 ]; then
    say FAIL "mirror differs: $rel"
  else
    mkdir -p "$(dirname "$m")" && cp -p "$f" "$m" && say OK "copied $rel"
  fi
done < <(find .claude/skills -type f | sort)
if [ -d .agents/skills ]; then
  while IFS= read -r m; do
    rel=${m#.agents/skills/}
    [ -f ".claude/skills/$rel" ] || say WARN "mirror-only file (not deleted): $rel"
  done < <(find .agents/skills -type f | sort)
fi
[ "$w_fails" -eq 0 ]
