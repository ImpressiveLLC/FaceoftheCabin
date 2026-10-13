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
case "\$1" in
  report) echo '[{"id":"bk","kind":"dated-dirs","status":"ok","keep":3,"snapshots":[{"name":"2026-01-01","bytes":100}]}]' ;;
  apply)  [ -f "$W/fail" ] && { echo "boom"; exit 3; }
          echo "  removed /x/bk/2026-01-01"; echo "  removed /x/bk/2026-01-02"; echo "done: 2 removal step(s)." ;;
esac
EOF
chmod +x "$W/tool"
agent() { TRIM_SPOOL="$SPOOL" TRIM_TOOL="$W/tool" TRIM_ONCE=1 TRIM_DF_PATH="$W" bash "$AGENT" 2>/dev/null; }
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

echo "tool failure"
: >"$W/calls"; touch "$W/fail"
printf 'requestId=f1\nkeep.bk=1\n' >"$SPOOL/request.env"; agent
check "a failing tool is reported as not ok with its output" "grep -q '\"ok\":false' $SPOOL/result.json && grep -q 'tool exited 3' $SPOOL/result.json && grep -q 'boom' $SPOOL/result.json"

echo
echo "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
