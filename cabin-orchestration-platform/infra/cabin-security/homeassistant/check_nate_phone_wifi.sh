#!/bin/sh
# Checks whether Nate's Android phone (WiFi MAC bc:10:7b:8b:da:a0) is
# currently associated with the cabin LAN (192.168.2.0/24, the Starlink
# Gen3 router's subnet — same network the M920q itself is on). Populates
# the ARP cache with a quick ping sweep first, since entries expire
# after a few minutes of inactivity, then checks for the MAC directly —
# this works off the OS network stack (ARP), not app-level reachability,
# so it isn't affected by the phone's notification/lock state the way an
# ICMP ping to a sleeping app would be.
#
# Prints ON/OFF to stdout for command_line's binary_sensor payload_on/
# payload_off to match against (see cabin_security_presence.yaml).
MAC="bc:10:7b:8b:da:a0"

nmap -sn -T4 192.168.2.0/24 >/dev/null 2>&1

if ip neigh show | grep -qi "$MAC"; then
    echo "ON"
else
    echo "OFF"
fi
