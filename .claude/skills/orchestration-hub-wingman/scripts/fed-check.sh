#!/usr/bin/env bash
# Wingman fed-check: read-only documentation-federation drift check.
#   fed-check.sh             drift: AGENTS.md must equal what fed-regen.sh writes, .agents/skills must be
#                            a byte copy of .claude/skills,
#                            the context-map pair, skills paths rewritten with an agent name
#   fed-check.sh --manifest  sha256 (8 chars) of every federated file; run on two machines
#                            and `diff` the outputs. Hashes only, never contents. Text files are hashed
#                            without carriage returns, so a Windows (CRLF) and a Linux checkout of one
#                            commit print the same values.
#   fed-check.sh --registry  context registry: age, repo paths it names, rows still unknown
# Exit 0 when nothing FAILed, 1 otherwise, 2 when not in a clone.
# Text is compared without carriage returns (core.autocrlf on Windows is not drift); see lib.sh.
set -u
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
. "$here/lib.sh"
root=$(git rev-parse --show-toplevel 2>/dev/null) || { say FAIL "not inside a git clone"; exit 2; }
cd "$root" || exit 2

MAP_DOCS="docs/ai-assistant/rag/context-fixtures-r2.json"
MAP_CP="cabin-orchestration-platform/backend/src/main/resources/rag/context-fixtures-r2.json"
REGISTRY="docs/governance/rte/context-registry.md"

# norm: classifies a skill mirror that differs only by the old agent-name substitution (a byte copy fixes it).
norm() {
  tr -d '\r' < "$1" | sed -E 's/CLAUDE\.md|AGENTS\.md/INSTR.md/g; s#\.(claude|agents|Codex|codex)/skills#.X/skills#g;
          s/Claude Code|Claude|Codex|CLAUDE/AGENT/g; s#(claude-code|codex)/tmp-#AGENT/tmp-#g'
}

if [ "${1:-}" = "--manifest" ]; then
  echo "# head $(git rev-parse --short HEAD) branch $(git rev-parse --abbrev-ref HEAD) dirty $(git status --porcelain | wc -l | tr -d ' ')"
  { for f in CLAUDE.md AGENTS.md "$MAP_DOCS" "$MAP_CP" docs/governance/backlog.md docs/governance/rte/*.md; do
      [ -f "$f" ] && echo "$(w_h8 "$f")  $f"
    done
    find .claude/skills .agents/skills -type f 2>/dev/null | sort | while read -r f; do echo "$(w_h8 "$f")  $f"; done
  }
  exit 0
fi

if [ "${1:-}" = "--registry" ]; then
  if [ ! -f "$REGISTRY" ]; then say FAIL "$REGISTRY missing"; exit 1; fi
  d=$(grep -oE 'Last verified: [0-9]{4}-[0-9]{2}-[0-9]{2}' "$REGISTRY" | head -1 | grep -oE '[0-9-]{10}')
  if [ -n "$d" ]; then
    age=$(( ( $(date -u +%s) - $(date -u -d "$d" +%s) ) / 86400 ))
    if [ "$age" -gt 14 ]; then say WARN "registry last verified $d ($age days ago): re-verify rows"; else say OK "registry last verified $d ($age days ago)"; fi
  else
    say WARN "registry has no 'Last verified: YYYY-MM-DD' stamp"
  fi
  miss=0; seen=0
  for p in $(grep -oE '`[^` ]+`' "$REGISTRY" | tr -d '`' | sort -u); do
    case "$p" in
      docs/*|scripts/*|.claude/*|.agents/*|.github/*|cabin-orchestration-platform/*|ansible/*|AGENTS.md|CLAUDE.md) ;;
      *) continue ;;
    esac
    case "$p" in *'<'*|*'*'*|*'...'*) continue ;; esac
    seen=$((seen + 1))
    if [ ! -e "${p%/}" ]; then say FAIL "registry names a path that does not exist: $p"; miss=$((miss + 1)); fi
  done
  [ "$miss" -eq 0 ] && say OK "all $seen repo paths named in the registry exist"
  u=$(grep -cE '\| U[^|]* \|$' "$REGISTRY")
  if [ "$u" -gt 0 ]; then say WARN "$u registry row(s) still unknown (Check = U): resolve or re-date them"; else say OK "no unknown rows"; fi
  [ "$w_fails" -eq 0 ]; exit
fi

echo "== instruction mirror: AGENTS.md vs CLAUDE.md"
if [ ! -f AGENTS.md ]; then
  say WARN "AGENTS.md missing: run fed-regen.sh"
elif cmp -s <(w_gen_agents) <(tr -d '\r' < AGENTS.md); then
  say OK "AGENTS.md is CLAUDE.md regenerated"
else
  n=$(diff <(w_gen_agents) <(tr -d '\r' < AGENTS.md) | grep -c '^[<>]')
  say WARN "$n line(s) differ from CLAUDE.md regenerated (stale or hand-edited mirror): run fed-regen.sh and commit both. First hunks:"
  diff <(w_gen_agents) <(tr -d '\r' < AGENTS.md) | grep -E '^[0-9]' | head -6
fi
# The string hunted is the skills directory as the agent-name substitution once wrote it
# (a dot, the agent's capitalised name, a slash). It is assembled from two pieces so that no
# file in the orchestration-hub-wingman mirror contains it: this script is copied into .agents and would flag itself.
bad_dir=".Cod"; bad_dir="${bad_dir}ex/"
bad=$(grep -rInF -- "$bad_dir" AGENTS.md .agents 2>/dev/null | head -50)
if [ -n "$bad" ]; then
  say FAIL "skills path rewritten with the agent name in $(printf '%s\n' "$bad" | wc -l | tr -d ' ') place(s) (that directory does not exist):"
  printf '%s\n' "$bad" | cut -d: -f1,2 | head -4
else
  say OK "no skills paths rewritten with the agent name"
fi

echo "== skill mirrors: .agents/skills vs .claude/skills"
for d in .claude/skills/*/; do
  n=$(basename "$d")
  if [ ! -d ".agents/skills/$n" ]; then say WARN "no Codex mirror for skill: $n"; continue; fi
  state=same
  while IFS= read -r f; do
    rel=${f#.claude/skills/$n/}; m=".agents/skills/$n/$rel"
    if [ ! -f "$m" ]; then state="missing-file:$rel"; break; fi
    if ! w_same "$f" "$m"; then
      if cmp -s <(norm "$f") <(norm "$m"); then state="subst-only"; else state="drift:$rel"; break; fi
    fi
  done < <(find "$d" -type f | sort)
  case "$state" in
    same) say OK "$n byte-identical" ;;
    subst-only) say WARN "$n differs only by agent-name substitution (a byte copy fixes it; the skill text is agent-neutral)" ;;
    *) say FAIL "$n: $state" ;;
  esac
done
for d in .agents/skills/*/; do
  [ -d ".claude/skills/$(basename "$d")" ] || say WARN "mirror without a source skill: $(basename "$d")"
done
if [ -d .claude/skills/orchestration-hub-wingman ] && [ -d .agents/skills/orchestration-hub-wingman ] && ! diff -rq --strip-trailing-cr .claude/skills/orchestration-hub-wingman .agents/skills/orchestration-hub-wingman >/dev/null; then
  say FAIL "orchestration-hub-wingman mirror must be byte-identical: cp -r .claude/skills/orchestration-hub-wingman/. .agents/skills/orchestration-hub-wingman/"
fi

echo "== context map pair (docs copy vs backend classpath copy)"
if [ -f "$MAP_DOCS" ] && [ -f "$MAP_CP" ]; then
  if w_same "$MAP_DOCS" "$MAP_CP"; then say OK "identical ($(w_h8 "$MAP_DOCS"))"; else say FAIL "docs $(w_h8 "$MAP_DOCS") != classpath $(w_h8 "$MAP_CP")"; fi
else
  say FAIL "context map file missing"
fi

[ "$w_fails" -eq 0 ]
