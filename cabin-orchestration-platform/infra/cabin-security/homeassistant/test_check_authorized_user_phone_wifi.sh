#!/bin/sh
# Tests for check_authorized_user_phone_wifi.sh. Run from this directory:
#
#   sh test_check_authorized_user_phone_wifi.sh
#
# No network is touched: `nmap` and `ip` are replaced by stubs on PATH.
# Exits non-zero if any case fails.

HERE=$(cd "$(dirname "$0")" && pwd)
SCRIPT="$HERE/check_authorized_user_phone_wifi.sh"
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT
mkdir "$T/bin"

# `ip neigh show` prints whatever the test put in $FAKE_NEIGH; nmap just logs
# its arguments so a test can see which LAN was swept.
cat > "$T/bin/ip" <<'EOF'
#!/bin/sh
printf '%s\n' "$FAKE_NEIGH"
EOF
cat > "$T/bin/nmap" <<'EOF'
#!/bin/sh
echo "$@" >> "$FAKE_NMAP_LOG"
EOF
chmod +x "$T/bin/ip" "$T/bin/nmap"
PATH="$T/bin:$PATH"
export PATH
FAKE_NMAP_LOG="$T/nmap.log"
export FAKE_NMAP_LOG

PASS=0
FAIL=0
ok()   { PASS=$((PASS + 1)); echo "  PASS  $1"; }
bad()  { FAIL=$((FAIL + 1)); echo "  FAIL  $1"; }
check() { # description, expected, actual
    if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 (expected '$2', got '$3')"; fi
}

CONF="$T/authorized_user_phones.conf"
export AUTHORIZED_USER_PHONES_CONF="$CONF"
cat > "$CONF" <<'EOF'
# a comment
nate   BC:10:7B:8B:DA:A0  192.168.2.0/24

#ghost  00:11:22:33:44:55  10.0.0.0/24
emma   aa:bb:cc:dd:ee:ff  192.168.9.0/24
EOF

run() { # person -> sets OUT (stdout) and RC
    OUT=$(sh "$SCRIPT" "$1" 2>"$T/err")
    RC=$?
}

echo "== presence"
FAKE_NEIGH="192.168.2.50 dev wlo1 lladdr bc:10:7b:8b:da:a0 REACHABLE"; export FAKE_NEIGH
run nate;  check "MAC in the ARP table -> ON" "ON" "$OUT"
check "...and exits 0" "0" "$RC"
FAKE_NEIGH="192.168.2.51 dev wlo1 lladdr 11:22:33:44:55:66 REACHABLE"; export FAKE_NEIGH
run nate;  check "MAC not in the ARP table -> OFF" "OFF" "$OUT"
FAKE_NEIGH=""; export FAKE_NEIGH
run nate;  check "empty ARP table -> OFF" "OFF" "$OUT"
FAKE_NEIGH="x dev wlo1 lladdr bc:10:7b:8b:da:a0 STALE"; export FAKE_NEIGH
run nate;  check "MAC matching ignores the case used in the config" "ON" "$OUT"

echo "== one user's phone never answers for another"
FAKE_NEIGH="x dev wlo1 lladdr aa:bb:cc:dd:ee:ff REACHABLE"; export FAKE_NEIGH
run nate;  check "emma's phone present, asking about nate -> OFF" "OFF" "$OUT"
run emma;  check "emma's phone present, asking about emma -> ON" "ON" "$OUT"

echo "== sweeps the configured LAN"
: > "$FAKE_NMAP_LOG"
run emma
check "sweep uses emma's CIDR" "-sn -T4 192.168.9.0/24" "$(cat "$FAKE_NMAP_LOG")"

echo "== fails safe: a broken setup prints nothing on stdout and exits 2"
FAKE_NEIGH="x dev wlo1 lladdr bc:10:7b:8b:da:a0 REACHABLE"; export FAKE_NEIGH
run ghost; check "commented-out user is not found: stdout empty" "" "$OUT"; check "...exit 2" "2" "$RC"
run nobody; check "unknown user: stdout empty" "" "$OUT"; check "...exit 2" "2" "$RC"
run "";    check "no person_id: stdout empty" "" "$OUT"; check "...exit 2" "2" "$RC"
AUTHORIZED_USER_PHONES_CONF="$T/missing.conf"; export AUTHORIZED_USER_PHONES_CONF
run nate;  check "missing config file: stdout empty" "" "$OUT"; check "...exit 2" "2" "$RC"
AUTHORIZED_USER_PHONES_CONF="$CONF"; export AUTHORIZED_USER_PHONES_CONF

printf 'bob  not-a-mac  192.168.2.0/24\n' > "$T/bad_mac.conf"
AUTHORIZED_USER_PHONES_CONF="$T/bad_mac.conf"; export AUTHORIZED_USER_PHONES_CONF
run bob;   check "malformed MAC: stdout empty" "" "$OUT"; check "...exit 2" "2" "$RC"
printf 'bob  aa:bb:cc:dd:ee:ff  nonsense\n' > "$T/bad_cidr.conf"
AUTHORIZED_USER_PHONES_CONF="$T/bad_cidr.conf"; export AUTHORIZED_USER_PHONES_CONF
run bob;   check "malformed CIDR: stdout empty" "" "$OUT"; check "...exit 2" "2" "$RC"
printf 'bob  aa:bb:cc:dd:ee:ff\n' > "$T/short.conf"
AUTHORIZED_USER_PHONES_CONF="$T/short.conf"; export AUTHORIZED_USER_PHONES_CONF
run bob;   check "missing CIDR column: stdout empty" "" "$OUT"; check "...exit 2" "2" "$RC"

echo "== repo hygiene (Cowork C-122-1): the real phone config is never committed"
if git -C "$HERE" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    tracked=$(git -C "$HERE" ls-files | grep -E '(^|/)authorized_user_phones\.conf$')
    check "no authorized_user_phones.conf is tracked (only the .example template may be)" "" "$tracked"
    if git -C "$HERE" check-ignore -q "$HERE/authorized_user_phones.conf"; then ignored=yes; else ignored=no; fi
    check "the real file is git-ignored, so it cannot be added by accident" "yes" "$ignored"
else
    echo "  SKIP  not inside a git work tree (hygiene checks need one)"
fi

echo
echo "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
