#!/data/data/com.termux/files/usr/bin/bash
# Keeps the Blink notification relay running under Termux (same pattern as z2m-supervise.sh).
# Install: copy this folder to ~/blink-relay on the phone, `cd ~/blink-relay && npm install`,
# then start it from Termux:Boot (see docs/BLINK_RELAY_SETUP.md) so a phone reboot restarts it.
termux-wake-lock
cd "$HOME/blink-relay" || exit 1
while true; do
  echo "[$(date '+%F %T')] starting blink relay" >> "$HOME/blink-relay-supervisor.log"
  node relay.js >> "$HOME/blink-relay.log" 2>&1 < /dev/null
  echo "[$(date '+%F %T')] relay exited rc=$?; restarting in 10s" >> "$HOME/blink-relay-supervisor.log"
  sleep 10
done
