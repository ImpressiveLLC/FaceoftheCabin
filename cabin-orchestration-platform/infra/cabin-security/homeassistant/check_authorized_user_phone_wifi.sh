#!/bin/sh
# check_authorized_user_phone_wifi -- is an authorized user's phone associated
# with this location's LAN right now?
#
#   usage: check_authorized_user_phone_wifi.sh <person_id>
#
# Prints ON or OFF to stdout for command_line's binary_sensor payload_on /
# payload_off to match (see cabin_security_presence.yaml). One sensor instance
# is created per authorized PRIMARY-role user at a location (ontology:
# `check_authorized_user_phone_wifi`, `location_role`); a MAINTENANCE-only
# user is deliberately not given an instance, so their phone never counts
# toward the location's presence.
#
# Nothing person- or site-specific lives in this script. The phone's WiFi MAC
# and the LAN to sweep come from authorized_user_phones.conf, which sits next
# to this script, is NOT committed (a MAC identifies a device), and has a
# committed template, authorized_user_phones.conf.example. One line per user:
#
#   person_id  wifi_mac  lan_cidr
#   nate       aa:bb:cc:dd:ee:ff  192.168.2.0/24
#
# Override the config path with AUTHORIZED_USER_PHONES_CONF (used by tests).
#
# It works off the OS network stack (ARP), not app-level reachability, so it is
# not affected by the phone's notification/lock state the way an ICMP ping to a
# sleeping app would be. A quick ping sweep fills the ARP cache first, since
# entries expire after a few minutes of inactivity.
#
# FAILS SAFE: a configuration problem (no person_id, no config file, person not
# listed, malformed MAC or CIDR) prints a message on stderr, exits 2 and prints
# NOTHING on stdout. command_line then reports the sensor as unavailable, which
# neither automation trigger acts on. It must never print OFF for a broken
# config: OFF for 3 minutes publishes not_home, and a false "away" is the
# dangerous direction for the siren gate.
#
# Known limit: a phone that uses a private (randomized) WiFi MAC on this
# network will not match the MAC in the config. See PRESENCE.md and the W-37
# backlog item for the options.

PERSON="$1"
CONF="${AUTHORIZED_USER_PHONES_CONF:-$(dirname "$0")/authorized_user_phones.conf}"

fail() {
    echo "check_authorized_user_phone_wifi: $*" >&2
    exit 2
}

[ -n "$PERSON" ] || fail "usage: $0 <person_id>"
[ -r "$CONF" ] || fail "cannot read $CONF (copy authorized_user_phones.conf.example and fill it in)"

# First non-comment line whose first field is exactly PERSON.
LINE=$(awk -v p="$PERSON" '$0 !~ /^[[:space:]]*#/ && $1 == p { print $1, $2, $3; exit }' "$CONF")
[ -n "$LINE" ] || fail "person '$PERSON' is not listed in $CONF"

MAC=$(echo "$LINE" | awk '{ print tolower($2) }')
CIDR=$(echo "$LINE" | awk '{ print $3 }')

echo "$MAC" | grep -Eq '^([0-9a-f]{2}:){5}[0-9a-f]{2}$' || fail "malformed MAC for '$PERSON' in $CONF"
echo "$CIDR" | grep -Eq '^[0-9]{1,3}(\.[0-9]{1,3}){3}/[0-9]{1,2}$' || fail "malformed LAN CIDR for '$PERSON' in $CONF"

nmap -sn -T4 "$CIDR" >/dev/null 2>&1

if ip neigh show | grep -qi "$MAC"; then
    echo "ON"
else
    echo "OFF"
fi
