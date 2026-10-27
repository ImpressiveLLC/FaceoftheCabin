#!/usr/bin/env bash
# Tests for w101-census with a stub docker. Nothing real is touched.
#   bash scripts/host/test_w101_census.sh
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
SCRIPT="$HERE/w101-census"
W=$(mktemp -d)
trap 'rm -rf -- "${W:?}"' EXIT
PASS=0; FAIL=0

ok()   { PASS=$((PASS+1)); echo "  ok   $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  FAIL $1"; }
check(){ if eval "$2"; then ok "$1"; else bad "$1  [$2]"; fi; }

# Stub 1: docker fails, as when the container is gone or a query is cancelled by the timeout.
cat >"$W/docker-fail" <<'EOF'
#!/usr/bin/env bash
cat >/dev/null
echo "ERROR: canceling statement due to statement timeout" >&2
exit 3
EOF
# Stub 2: docker succeeds and returns no rows (only the transaction echoes).
cat >"$W/docker-empty" <<'EOF'
#!/usr/bin/env bash
cat >/dev/null
printf 'BEGIN\nSET\nCOMMIT\n'
exit 0
EOF
chmod +x "$W/docker-fail" "$W/docker-empty"

echo "w101-census"
DOCKER="$W/docker-fail" bash "$SCRIPT" >"$W/fail.out" 2>"$W/fail.err"; FAIL_RC=$?
check "a failed query exits nonzero" '[ "$FAIL_RC" -ne 0 ]'
check "a failed query does not print later sections" '! grep -q "^## devices" "$W/fail.out"'

DOCKER="$W/docker-empty" bash "$SCRIPT" >"$W/empty.out" 2>"$W/empty.err"; EMPTY_RC=$?
check "zero rows exits 0" '[ "$EMPTY_RC" -eq 0 ]'
check "zero rows still prints every section header" '[ "$(grep -c "^## " "$W/empty.out")" -eq 5 ]'
check "transaction echoes are not in the output" '! grep -qE "^(BEGIN|COMMIT|SET)$" "$W/empty.out"'

echo "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
