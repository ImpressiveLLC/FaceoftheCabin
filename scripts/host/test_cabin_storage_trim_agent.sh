#!/usr/bin/env bash
# Tests for cabin-storage-trim-agent with a stub tool and a scratch spool.
#   bash scripts/host/test_cabin_storage_trim_agent.sh
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
AGENT="$HERE/cabin-storage-trim-agent"
W=$(mktemp -d)
trap 'rm -rf -- "${W:?}"' EXIT
PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "  ok   $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  FAIL $1"; }
check(){ if eval "$2"; then ok "$1"; else bad "$1  [$2]"; fi; }

SPOOL="$W/spool"
cat >"$W/tool" <<EOF
#!/usr/bin/env bash
echo "\$@" >>"$W/calls"
if [[ "\$*" == *--auto-below* ]]; then cat "$W/auto_out"; [ -f "$W/autofail" ] && exit 3; exit 0; fi
case "\$1" in
  report) echo '[{"id":"bk","kind":"dated-dirs","status":"ok","keep":3,"snapshots":[{"name":"2026-01-01","bytes":100}]}]' ;;
  apply)  [ -f "$W/fail" ] && { echo "boom"; exit 3; }
          echo "  removed /x/bk/2026-01-01"; echo "  removed /x/bk/2026-01-02"; echo "done: 2 removal step(s)." ;;
esac
EOF
chmod +x "$W/tool"
agent() { TRIM_AUTO=0 TRIM_SPOOL="$SPOOL" TRIM_TOOL="$W/tool" TRIM_ONCE=1 TRIM_DF_PATH="$W" bash "$AGENT" 2>/dev/null; }
mkdir -p "$W/stub"
cat >"$W/stub/df" <<'DFEOF'
#!/usr/bin/env bash
if [[ "$*" == *pcent* ]]; then printf 'Use%%\n %s%%\n' "$(cat "$USED_FILE")"; else printf 'Avail\n 1000\n'; fi
DFEOF
chmod +x "$W/stub/df"
agent_auto() { PATH="$W/stub:$PATH" USED_FILE="$W/used" TRIM_SPOOL="$SPOOL" TRIM_TOOL="$W/tool" TRIM_ONCE=1 TRIM_DF_PATH="$W" bash "$AGENT" 2>/dev/null; }
calls() { cat "$W/calls" 2>/dev/null; }

echo "plan"
agent
check "writes plan.json with a timestamp and the tool's targets" "grep -q '\"plannedAt\"' $SPOOL/plan.json && grep -q '\"id\":\"bk\"' $SPOOL/plan.json && grep -q '\"sizesMeasured\":true' $SPOOL/plan.json"
check "the spool is writable by the container's user" "[ \$(stat -c %a $SPOOL) = 777 ]"
check "plan.json is world-readable" "[ \$(stat -c %a $SPOOL/plan.json) = 644 ]"

echo "a valid request"
: >"$W/calls"
printf 'requestId=abc-123\nrequestedAt=2026-10-13T12:00:00Z\nkeep.bk=2\n' >"$SPOOL/request.env"
agent
check "runs apply for exactly the named target with --keep and --yes" "calls | grep -qx 'apply --only bk --keep bk=2 --yes'"
check "the request file is consumed" "[ ! -e $SPOOL/request.env ]"
check "result.json says ok, names the request and counts removals" "grep -q '\"requestId\":\"abc-123\"' $SPOOL/result.json && grep -q '\"ok\":true' $SPOOL/result.json && grep -q '\"removed\":2' $SPOOL/result.json"
check "the plan is refreshed afterwards" "calls | grep -q '^report --json --snapshots'"

echo "requests the agent must refuse"
for case in 'keep.bk=0' 'keep.bk=abc' 'keep.bk=99999' 'keep.Bad_ID=2' 'keep.bk=2;rm -rf /' 'surprise=1' 'requestId=x'; do
  : >"$W/calls"; printf 'requestId=r1\n%s\n' "$case" >"$SPOOL/request.env"
  [ "$case" = "requestId=x" ] && printf 'requestId=r1\n' >"$SPOOL/request.env"
  agent
  check "refused without running apply: $case" "! calls | grep -q '^apply' && grep -q '\"ok\":false' $SPOOL/result.json && [ ! -e $SPOOL/request.env ]"
done
: >"$W/calls"; head -c 5000 /dev/zero | tr '\0' 'a' >"$SPOOL/request.env"; agent
check "an oversized request is refused" "! calls | grep -q '^apply' && grep -q 'too large' $SPOOL/result.json"
: >"$W/calls"; ln -s "$W/tool" "$SPOOL/request.env"; agent
if [ -L "$W/probe" ] || ln -s "$W/tool" "$W/probe" 2>/dev/null && [ -L "$W/probe" ]; then
  check "a symlink as the request is refused" "! calls | grep -q '^apply' && [ ! -e $SPOOL/request.env ]"
else echo "  skip symlink request check (this shell cannot create real symlinks; it runs on Linux)"; rm -f "$SPOOL/request.env"; fi

echo "automatic trimming when the disk gets too full (W-11b)"
rm -f "$SPOOL/result.json"
: >"$W/calls"; echo 50 >"$W/used"; printf 'auto-result: reached used=50 removed=0\n' >"$W/auto_out"
agent_auto
check "below the threshold it does not trim" "! calls | grep -q -- '--auto-below'"
check "plan.json states the policy so the UI can show it" "grep -q '\"auto\":{\"enabled\":true,\"aboveFull\":70,\"downTo\":55,\"floorKeep\":3}' $SPOOL/plan.json"
: >"$W/calls"; echo 80 >"$W/used"; printf '  removed /x/a/2026-01-01\n  removed /x/a/2026-01-02\n  removed /x/b/2026-01-01\nauto-result: reached used=54 removed=3\n' >"$W/auto_out"
agent_auto
check "at or above it, it trims oldest-first down to the target with the floor" "calls | grep -qx 'apply --auto-below 55 --floor 3 --yes'"
check "result.json marks it automatic, counts removals and says the target was reached" "grep -q '\"auto\":true' $SPOOL/result.json && grep -q '\"removed\":3' $SPOOL/result.json && grep -q '\"reachedTarget\":true' $SPOOL/result.json && grep -q '\"requestId\":\"auto-' $SPOOL/result.json"
: >"$W/calls"; rm -f "$SPOOL/result.json"; echo 90 >"$W/used"; printf 'auto-result: floor used=88 removed=0\n' >"$W/auto_out"
agent_auto
check "when it hits the floor with the disk still full it says so, once" "grep -q '\"reachedTarget\":false' $SPOOL/result.json && grep -q '\"ok\":true' $SPOOL/result.json"
: >"$W/calls"; rm -f "$SPOOL/result.json"; echo 90 >"$W/used"; printf 'boom\n' >"$W/auto_out"; touch "$W/autofail"
agent_auto
check "a failing tool is reported as a failed automatic trim" "grep -q '\"ok\":false' $SPOOL/result.json && grep -q '\"auto\":true' $SPOOL/result.json && grep -q 'auto-trim failed' $SPOOL/result.json"
rm -f "$W/autofail" "$SPOOL/result.json"; : >"$W/calls"; echo 99 >"$W/used"
PATH="$W/stub:$PATH" USED_FILE="$W/used" TRIM_AUTO=0 TRIM_SPOOL="$SPOOL" TRIM_TOOL="$W/tool" TRIM_ONCE=1 TRIM_DF_PATH="$W" bash "$AGENT" 2>/dev/null
check "TRIM_AUTO=0 turns it off, even at 99%" "! calls | grep -q -- '--auto-below'"

echo "tool failure"
: >"$W/calls"; touch "$W/fail"
printf 'requestId=f1\nkeep.bk=1\n' >"$SPOOL/request.env"; agent
check "a failing tool is reported as not ok with its output" "grep -q '\"ok\":false' $SPOOL/result.json && grep -q 'tool exited 3' $SPOOL/result.json && grep -q 'boom' $SPOOL/result.json"

echo
echo "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
