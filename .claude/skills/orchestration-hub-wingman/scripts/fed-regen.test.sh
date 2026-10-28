#!/usr/bin/env bash
# Offline test for fed-regen.sh and fed-check.sh in throwaway repos. No network. Run: bash fed-regen.test.sh
# Part 1: regeneration and drift detection. Part 2: Windows-style checkouts (CRLF text). Part 3:
# core.fileMode=false, where the executable bit lives only in the git index.
# Exit 1 on any failure. A check that cannot run here (a filesystem with no executable bit) prints SKIP and is
# not counted as passed.
set -u
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
t=$(mktemp -d); trap 'rm -rf "$t"' EXIT
pass=0; fail=0; skip=0
ok() { if [ "$1" -eq 0 ]; then pass=$((pass + 1)); else fail=$((fail + 1)); echo "FAIL: $2"; fi; }
skipped() { skip=$((skip + 1)); echo "SKIP: $1"; }
R="bash .claude/skills/orchestration-hub-wingman/scripts/fed-regen.sh"
C="bash .claude/skills/orchestration-hub-wingman/scripts/fed-check.sh"

# Does this filesystem keep an executable bit? (Git Bash on NTFS does not.)
: > "$t/probe"; chmod 755 "$t/probe"
if [ "$(stat -c %a "$t/probe")" = "755" ]; then can_x=1; else can_x=0; fi

# mk <dir>: a throwaway clone with a CLAUDE.md, two demo skill files and the wingman scripts. Leaves cwd there.
mk() {
  mkdir -p "$1" && cd "$1" && git init -q . || exit 2
  mkdir -p .claude/skills/orchestration-hub-wingman/scripts .claude/skills/demo
  cp "$here/lib.sh" "$here/fed-regen.sh" "$here/fed-check.sh" .claude/skills/orchestration-hub-wingman/scripts/
  printf '# CLAUDE.md - Test\n\nThis file gives Claude Code full context for every session.\nSeveral Claude sessions work here.\n' > CLAUDE.md
  printf 'echo demo\n' > .claude/skills/demo/run.sh; chmod 755 .claude/skills/demo/run.sh
  printf 'notes\n' > .claude/skills/demo/notes.md
}
tocrlf() { sed -i 's/$/\r/' "$@"; }
# has_cr <file>: true if the file holds a carriage return. Not grep: Git Bash's grep reads in text mode and strips CR,
# so grep -q $'\r' never matches there (and a negated one passes without checking anything).
has_cr() { [ "$(tr -cd '\r' < "$1" | wc -c)" -gt 0 ]; }

# ---- Part 1: regeneration and drift ----------------------------------------------------------
mk "$t/r1"
$R --check >/dev/null 2>&1; [ $? -eq 1 ]; ok $? "check must fail before the first regeneration"
$R >/dev/null 2>&1; ok $? "regenerate exits 0"
head -1 AGENTS.md | grep -q '^# AGENTS.md - Test$'; ok $? "title rewritten"
sed -n 2p AGENTS.md | grep -q '^<!-- GENERATED from CLAUDE.md'; ok $? "header under the title"
grep -q '^This file gives Codex full context' AGENTS.md; ok $? "sentence rewritten"
grep -q '^Several Claude sessions work here.$' AGENTS.md; ok $? "other lines untouched"
cmp -s .claude/skills/demo/notes.md .agents/skills/demo/notes.md; ok $? "skill file byte copy"
if [ "$can_x" -eq 1 ]; then
  [ "$(stat -c %a .agents/skills/demo/run.sh)" = "755" ]; ok $? "executable bit kept"
else
  skipped "executable bit kept (this filesystem cannot hold an executable bit)"
fi
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
rm -f .agents/skills/demo/only-here.md

printf 'A\0B\r\n' > .claude/skills/demo/bin.dat
$R >/dev/null 2>&1
printf 'A\0B\n' > .agents/skills/demo/bin.dat
$R --check >/dev/null 2>&1; [ $? -eq 1 ]; ok $? "a binary file that differs only by a carriage return is drift"
$R >/dev/null 2>&1; cmp -s .claude/skills/demo/bin.dat .agents/skills/demo/bin.dat; ok $? "binary file is copied byte for byte"

# ---- Part 2: a Windows-style checkout (core.autocrlf=true: CRLF in markdown and similar) -----
tocrlf AGENTS.md
$R --check >/dev/null 2>&1; ok $? "a CRLF AGENTS.md is not drift"
$R >/dev/null 2>&1; has_cr AGENTS.md; ok $? "regeneration leaves a CRLF AGENTS.md alone"
tocrlf CLAUDE.md .claude/skills/demo/notes.md .agents/skills/demo/notes.md
$R --check >/dev/null 2>&1; ok $? "CRLF sources and CRLF mirrors are not drift"
sed -i 's/\r$//' .agents/skills/demo/notes.md
$R --check >/dev/null 2>&1; ok $? "a CRLF source with an LF mirror is not drift"
printf 'tail line\r\n' >> CLAUDE.md
$R --check >/dev/null 2>&1; [ $? -eq 1 ]; ok $? "a real change to a CRLF CLAUDE.md is still drift"
$R >/dev/null 2>&1; $R --check >/dev/null 2>&1; ok $? "regeneration repairs it"
! has_cr AGENTS.md; ok $? "a regenerated AGENTS.md is LF only"

# the manifest of one commit must not depend on line endings: same content, LF versus CRLF
mk "$t/m1"; $R >/dev/null 2>&1; $C --manifest 2>/dev/null | grep -v '^#' > "$t/m1.out"
tocrlf CLAUDE.md AGENTS.md .claude/skills/demo/notes.md .agents/skills/demo/notes.md
$C --manifest 2>/dev/null | grep -v '^#' > "$t/m2.out"
[ -s "$t/m1.out" ] && cmp -s "$t/m1.out" "$t/m2.out"; ok $? "manifest hashes ignore line endings (LF and CRLF checkouts match)"

# ---- Part 3: core.fileMode=false (Git for Windows): the mode that counts is the git index's ------
mk "$t/r2"
git config core.fileMode false
git add -A
git update-index --chmod=+x .claude/skills/demo/run.sh
out=$($R 2>&1); rc=$?
[ "$rc" -eq 0 ]; ok $? "regenerate exits 0 under core.fileMode=false"
printf '%s' "$out" | grep -q 'git update-index --add --chmod=+x -- .agents/skills/demo/run.sh'; ok $? "regeneration prints the command that sets the mirror's mode"
out=$($R --check 2>&1); rc=$?
[ "$rc" -eq 0 ] && printf '%s' "$out" | grep -q '^NOTE'; ok $? "an untracked mirror is a NOTE under --check, not a failure"
git add -A
out=$($R --check 2>&1); rc=$?
[ "$rc" -eq 1 ] && printf '%s' "$out" | grep -q 'update-index --add --chmod=+x -- .agents/skills/demo/run.sh'; ok $? "a tracked 644 mirror of a 755 source fails and names the fix"
git update-index --chmod=+x -- .agents/skills/demo/run.sh
$R --check >/dev/null 2>&1; ok $? "the printed command fixes it"
chmod 644 .agents/skills/demo/run.sh
$R --check >/dev/null 2>&1; ok $? "the index decides, not the working-tree bit"
git update-index --chmod=-x -- .claude/skills/demo/run.sh
out=$($R --check 2>&1); rc=$?
[ "$rc" -eq 1 ] && printf '%s' "$out" | grep -q -- '--chmod=-x'; ok $? "a 644 source with a 755 mirror fails and names the fix"
git update-index --chmod=-x -- .agents/skills/demo/run.sh
$R --check >/dev/null 2>&1; ok $? "both 644 in the index: passes"

echo "fed-regen tests: $pass passed, $fail failed, $skip skipped"
[ "$fail" -eq 0 ]
