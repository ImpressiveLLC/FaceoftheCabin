#!/data/data/com.termux/files/usr/bin/bash
# Keeps Home's Zigbee2MQTT running under Termux. Z2M exits (code 2, restart=false) when
# its serial socket to the MR5U or its MQTT socket drops; without this it stays down
# until someone restarts it by hand. Install: copy to ~/z2m-supervise.sh on the phone,
# chmod +x, then: setsid nohup ~/z2m-supervise.sh > /dev/null 2>&1 < /dev/null &
termux-wake-lock
cd "$HOME/z2m-run/node_modules/zigbee2mqtt" || exit 1
export Z2M_ONBOARD_NO_SERVER=true ZIGBEE2MQTT_DATA="$HOME/.z2m"
while true; do
  echo "[$(date '+%F %T')] starting z2m" >> "$HOME/z2m-supervisor.log"
  node index.js >> "$HOME/z2m.log" 2>&1 < /dev/null
  echo "[$(date '+%F %T')] z2m exited rc=$?; restarting in 15s" >> "$HOME/z2m-supervisor.log"
  sleep 15
done
