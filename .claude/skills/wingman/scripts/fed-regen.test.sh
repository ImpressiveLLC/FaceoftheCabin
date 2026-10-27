#!/usr/bin/env bash
# Offline test for fed-regen.sh in a throwaway repo. No network. Run: bash fed-regen.test.sh
set -u
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
t=$(mktemp -d); trap 'rm -rf "$t"' EXIT
pass=0; fail=0
ok() { if [ "$1" -eq 0 ]; then pass=$((pass + 1)); else fail=$((fail + 1)); echo "FAIL: $2"; fi; }
cd "$t" && git init -q . || exit 2
mkdir -p .claude/skills/wingman/scripts .claude/skills/demo
cp "$here/lib.sh" "$here/fed-regen.sh" .claude/skills/wingman/scripts/
printf '# CLAUDE.md - Test\n\nThis file gives Claude Code full context for every session.\nSeveral Claude sessions work here.\n' > CLAUDE.md
printf 'echo demo\n' > .claude/skills/demo/run.sh; chmod 755 .claude/skills/demo/run.sh
printf 'notes\n' > .claude/skills/demo/notes.md
R="bash .claude/skills/wingman/scripts/fed-regen.sh"

$R --check >/dev/null 2>&1; [ $? -eq 1 ]; ok $? "check must fail before the first regeneration"
$R >/dev/null 2>&1; ok $? "regenerate exits 0"
head -1 AGENTS.md | grep -q '^# AGENTS.md - Test$'; ok $? "title rewritten"
sed -n 2p AGENTS.md | grep -q '^<!-- GENERATED from CLAUDE.md'; ok $? "header under the title"
grep -q '^This file gives Codex full context' AGENTS.md; ok $? "sentence rewritten"
grep -q '^Several Claude sessions work here.$' AGENTS.md; ok $? "other lines untouched"
cmp -s .claude/skills/demo/notes.md .agents/skills/demo/notes.md; ok $? "skill file byte copy"
[ "$(stat -c %a .agents/skills/demo/run.sh)" = "755" ]; ok $? "executable bit kept"
[ -z "$($R | grep -E '^(OK +copied|FAIL)')" ]; ok $? "second run copies nothing (idempotent)"
$R --check >/dev/null 2>&1; ok $? "check passes after regeneration"

echo hand-edit >> AGENTS.md
$R --check >/dev/null 2>&1; [ $? -eq 1 ]; ok $? "check fails on a hand-edited AGENTS.md"
$R >/dev/null 2>&1; $R --check >/dev/null 2>&1; ok $? "regeneration repairs the hand edit"

echo more >> CLAUDE.md
$R --check >/dev/null 2>&1; [ $? -eq 1 ]; ok $? "check fails when CLAUDE.md moved"
$R >/dev/null 2>&1; $R --check >/dev/null 2>&1; ok $? "regeneration follows CLAUDE.md"

echo changed >> .claude/skills/demo/notes.md
$R --check >/dev/null 2>&1; [ $? -eq 1 ]; ok $? "check fails when a skill moved"
$R >/dev/null 2>&1; cmp -s .claude/skills/demo/notes.md .agents/skills/demo/notes.md; ok $? "copy follows the skill"

echo stray > .agents/skills/demo/only-here.md
out=$($R --check 2>&1); rc=$?
[ "$rc" -eq 0 ] && printf '%s' "$out" | grep -q 'mirror-only file'; ok $? "mirror-only file is a WARN, not a failure, and is kept"
[ -f .agents/skills/demo/only-here.md ]; ok $? "mirror-only file not deleted"

echo "fed-regen tests: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
