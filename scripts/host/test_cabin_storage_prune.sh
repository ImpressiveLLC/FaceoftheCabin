#!/usr/bin/env bash
# Tests for cabin-storage-prune against a scratch folder. Nothing outside it is touched.
#   bash scripts/host/test_cabin_storage_prune.sh
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
SCRIPT="$HERE/cabin-storage-prune"
W=$(mktemp -d)
trap 'rm -rf -- "${W:?}"' EXIT
PASS=0; FAIL=0

ok()   { PASS=$((PASS+1)); echo "  ok   $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  FAIL $1"; }
check(){ if eval "$2"; then ok "$1"; else bad "$1  [$2]"; fi; }

run() { CABIN_STORAGE_CONF="$W/conf" CABIN_STORAGE_LOG="$W/log" CABIN_STORAGE_TEST=1 bash "$SCRIPT" "$@"; }

B="$W/backups"            # the allowed root
mkdir -p "$B/bk" "$B/other" "$B/sys" "$B/arch/cabin_event" "$W/outside/2020-01-01" "$W/ext/logs"
for d in 2026-01-01 2026-01-02 2026-01-03 2026-01-04 2026-01-05 2026-01-06; do mkdir -p "$B/bk/$d"; echo x >"$B/bk/$d/f"; done
mkdir -p "$B/bk/latest"; echo keep >"$B/bk/README"
echo data >"$B/sys/blob"
ln -s "$W/outside" "$B/link"
mkdir -p "$W/outside/2020-01-02"

cat >"$W/conf" <<EOF
allowed_root $B
allowed_root $W/ext
off backups-home
off backups-services
off backups-configs
off backups-data
off system-copy
off docker-cache
off journal
target bk       dated-dirs $B/bk    keep=3
target syscopy whole-dir  $B/sys
target escape   dated-dirs $B/link  keep=1
target hidden   dated-dirs $B/arch/cabin_event keep=1
target stray    dated-dirs $W/outside keep=1
target extlogs  old-files  $W/ext/logs days=7 glob=*.log
target bad0     dated-dirs $B/bk    keep=0
EOF
touch "$W/ext/logs/new.log"; touch -d "30 days ago" "$W/ext/logs/old1.log" "$W/ext/logs/old2.log"; touch -d "30 days ago" "$W/ext/logs/keep.txt"

echo "report / plan are read-only"
out=$(run report --no-sizes); rc=$?
check "report exits 0" "[ $rc -eq 0 ]"
check "bk shows 3 to remove" "echo \"\$out\" | grep -E '^bk +dated-dirs +ok +3 ' >/dev/null"
out=$(run plan --only bk)
check "plan names the oldest three" "echo \"\$out\" | grep -c 'would remove .*2026-01-0[123]\$' | grep -qx 3"
check "plan removed nothing" "[ -d $B/bk/2026-01-01 ] && [ -d $B/bk/2026-01-06 ]"
js=$(run report --json --no-sizes)
check "json is a list with the bk object" "echo \"\$js\" | grep -q '\"id\":\"bk\",\"kind\":\"dated-dirs\"'"
check "json starts and ends as a list" "[ \"\${js:0:1}\" = '[' ] && [ \"\${js: -1}\" = ']' ]"

echo "refusals"
check "path outside every allowed root is refused" "echo \"\$(run report --no-sizes)\" | grep -E '^stray +dated-dirs +refused' >/dev/null"
if [ -L "$B/link" ]; then
  check "symlink escaping the root is refused" "echo \"\$(run report --no-sizes)\" | grep -E '^escape +dated-dirs +refused' >/dev/null"
else
  echo "  skip symlink escape check (this shell cannot create real symlinks; it runs on Linux)"
fi
check "telemetry-named path is refused" "echo \"\$(run report --no-sizes)\" | grep -E '^hidden +dated-dirs +refused.*protected' >/dev/null"
check "keep=0 is refused" "echo \"\$(run report --no-sizes)\" | grep -E '^bad0 +dated-dirs +refused' >/dev/null"
run apply --only stray --yes >/dev/null 2>&1; check "apply on a refused target removes nothing outside" "[ -d $W/outside/2020-01-01 ] && [ -d $W/outside/2020-01-02 ]"
if [ -L "$B/link" ]; then run apply --only escape --yes >/dev/null 2>&1; check "apply through a symlink removes nothing" "[ -d $W/outside/2020-01-01 ]"; fi
run apply --only hidden --yes >/dev/null 2>&1; check "apply on a protected path removes nothing" "[ -d $B/arch/cabin_event ]"

echo "apply is explicit"
run apply >/dev/null 2>&1; rc=$?
check "apply with no --only exits 2" "[ $rc -eq 2 ]"
run apply --only nosuch --yes >/dev/null 2>&1; rc=$?
check "apply with an unknown id exits 2" "[ $rc -eq 2 ]"
check "nothing removed so far" "[ -d $B/bk/2026-01-01 ]"
run apply --only bk </dev/null >/dev/null 2>&1; rc=$?
check "apply without --yes and without a terminal exits 2" "[ $rc -eq 2 ] && [ -d $B/bk/2026-01-01 ]"

echo "dated-dirs apply"
run apply --only bk --yes >/dev/null 2>&1; rc=$?
check "apply exits 0" "[ $rc -eq 0 ]"
check "oldest three removed" "[ ! -d $B/bk/2026-01-01 ] && [ ! -d $B/bk/2026-01-02 ] && [ ! -d $B/bk/2026-01-03 ]"
check "newest three kept" "[ -d $B/bk/2026-01-04 ] && [ -d $B/bk/2026-01-05 ] && [ -d $B/bk/2026-01-06 ]"
check "non-dated folder and file untouched" "[ -d $B/bk/latest ] && [ -f $B/bk/README ]"
check "apply logged each removal" "[ \$(grep -c 'removed .*bk/2026-01-0' $W/log) -eq 3 ]"
out=$(run plan --only bk); check "second plan has nothing left" "echo \"\$out\" | grep -E '^bk +dated-dirs +ok +0 ' >/dev/null"

echo "old-files"
run apply --only extlogs --yes >/dev/null 2>&1
check "old matching files removed" "[ ! -f $W/ext/logs/old1.log ] && [ ! -f $W/ext/logs/old2.log ]"
check "new file and non-matching old file kept" "[ -f $W/ext/logs/new.log ] && [ -f $W/ext/logs/keep.txt ]"

echo "whole-dir"
run apply --only all-safe --yes >/dev/null 2>&1
check "all-safe never removes a whole-dir target" "[ -f $B/sys/blob ]"
run apply --only syscopy --yes >/dev/null 2>&1
check "whole-dir needs --yes-delete" "[ -f $B/sys/blob ]"
run apply --only syscopy --yes --yes-delete syscopy >/dev/null 2>&1
check "whole-dir removed when named twice" "[ ! -e $B/sys ]"

echo "keep override and snapshot sizes (what the in-app Trim control uses)"
mkdir -p "$B/tr"; for d in 2026-02-01 2026-02-02 2026-02-03 2026-02-04 2026-02-05; do mkdir -p "$B/tr/$d"; head -c 1000 /dev/zero >"$B/tr/$d/f"; done
printf 'allowed_root %s\ntarget tr dated-dirs %s keep=4\n' "$B" "$B/tr" >"$W/conf5"
r5() { CABIN_STORAGE_CONF="$W/conf5" CABIN_STORAGE_LOG="$W/log" CABIN_STORAGE_TEST=1 bash "$SCRIPT" "$@"; }
js=$(r5 report --json --snapshots --only tr)
check "json lists every dated folder with its size and the keep in force" "echo \"\$js\" | grep -q '\"keep\":4' && echo \"\$js\" | grep -q '\"name\":\"2026-02-01\",\"bytes\":' && [ \$(echo \"\$js\" | grep -o '\"name\":' | wc -l) -eq 5 ]"
js=$(r5 report --json --snapshots --only tr --keep tr=2)
check "--keep overrides the configured keep in the plan" "echo \"\$js\" | grep -q '\"keep\":2' && echo \"\$js\" | grep -q '\"items\":3'"
r5 apply --only tr --keep tr=2 --yes >/dev/null 2>&1
check "apply --keep 2 leaves exactly the newest two" "[ ! -d $B/tr/2026-02-03 ] && [ -d $B/tr/2026-02-04 ] && [ -d $B/tr/2026-02-05 ]"
r5 report --keep tr=0 >/dev/null 2>&1; rc=$?
check "--keep 0 is refused (the newest snapshot is never removed)" "[ $rc -eq 2 ]"
r5 report --keep tr=abc >/dev/null 2>&1; rc=$?
check "--keep with a non-number is refused" "[ $rc -eq 2 ]"
r5 report --keep nosuch=2 >/dev/null 2>&1; rc=$?
check "--keep for an unknown target is refused" "[ $rc -eq 2 ]"
check "the newest folder survived every run" "[ -d $B/tr/2026-02-05 ]"

echo "auto-trim to a fill level (oldest first, never below the floor)"
mkdir -p "$W/stub"
cat >"$W/stub/df" <<'DFEOF'
#!/usr/bin/env bash
n=$(cat "$USED_FILE"); printf 'Use%%\n %s%%\n' "$n"; echo $(( n - 10 )) >"$USED_FILE"
DFEOF
chmod +x "$W/stub/df"
printf 'allowed_root %s\ntarget au1 dated-dirs %s\ntarget au2 dated-dirs %s\n' "$B" "$B/au1" "$B/au2" >"$W/conf6"
mkau() { rm -rf "$B/au1" "$B/au2"; mkdir -p "$B/au1" "$B/au2"
  for d in 2026-03-01 2026-03-02 2026-03-03 2026-03-04 2026-03-05; do mkdir "$B/au1/$d"; done
  for d in 2026-03-02 2026-03-03 2026-03-06; do mkdir "$B/au2/$d"; done; }
r6() { PATH="$W/stub:$PATH" USED_FILE="$W/used" CABIN_STORAGE_CONF="$W/conf6" CABIN_STORAGE_LOG="$W/log" CABIN_STORAGE_TEST=1 bash "$SCRIPT" "$@"; }
mkau; echo 85 >"$W/used"
out=$(r6 apply --auto-below 65 --floor 2 --yes --only au1,au2)
check "removes the oldest folders one at a time until the disk is at the level" "[ ! -d $B/au1/2026-03-01 ] && [ ! -d $B/au1/2026-03-02 ] && [ -d $B/au1/2026-03-03 ] && [ -d $B/au2/2026-03-02 ] && echo \"\$out\" | grep -q 'auto-result: reached used=65 removed=2'"
mkau; echo 99 >"$W/used"
out=$(r6 apply --auto-below 10 --floor 3 --yes)
check "stops at the floor when the disk is still too full, and says so" "echo \"\$out\" | grep -q 'auto-result: floor' && [ \$(ls $B/au1 | wc -l) -eq 3 ] && [ \$(ls $B/au2 | wc -l) -eq 3 ] && [ -d $B/au1/2026-03-05 ]"
mkau; echo 99 >"$W/used"
r6 apply --auto-below 10 --floor 1 --yes --only au2 >/dev/null
check "--only limits it to the named targets" "[ \$(ls $B/au1 | wc -l) -eq 5 ] && [ \$(ls $B/au2 | wc -l) -eq 1 ] && [ -d $B/au2/2026-03-06 ]"
mkau; echo 40 >"$W/used"
out=$(r6 apply --auto-below 65 --yes)
check "does nothing when the disk is already below the level" "echo \"\$out\" | grep -q 'reached used=40 removed=0' && [ \$(ls $B/au1 | wc -l) -eq 5 ]"
r6 apply --auto-below 0 --yes >/dev/null 2>&1; rc=$?; check "--auto-below 0 is refused" "[ $rc -eq 2 ]"
r6 apply --auto-below abc --yes >/dev/null 2>&1; rc=$?; check "--auto-below with a non-number is refused" "[ $rc -eq 2 ]"
r6 apply --auto-below 50 --floor 0 --yes >/dev/null 2>&1; rc=$?; check "--floor 0 is refused" "[ $rc -eq 2 ]"
check "auto-trim never touched the protected telemetry folder" "[ -d $B/arch/cabin_event ]"
check "auto-trim logged each removal" "grep -c 'auto-trim removed' $W/log | grep -qv '^0\$'"

echo "config"
printf 'bogus thing\n' >"$W/conf2"
CABIN_STORAGE_CONF="$W/conf2" bash "$SCRIPT" report >/dev/null 2>&1; rc=$?
check "unknown config directive exits 2" "[ $rc -eq 2 ]"
printf 'target Bad_ID dated-dirs /x keep=1\n' >"$W/conf3"
CABIN_STORAGE_CONF="$W/conf3" bash "$SCRIPT" report >/dev/null 2>&1; rc=$?
check "bad target id exits 2" "[ $rc -eq 2 ]"
printf 'target x teleport /x\n' >"$W/conf4"
CABIN_STORAGE_CONF="$W/conf4" bash "$SCRIPT" report >/dev/null 2>&1; rc=$?
check "unknown kind exits 2" "[ $rc -eq 2 ]"
bash "$SCRIPT" report --no-sizes >/dev/null 2>&1; rc=$?
check "built-in defaults load (targets are simply missing here)" "[ $rc -eq 0 ]"

echo
echo "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
