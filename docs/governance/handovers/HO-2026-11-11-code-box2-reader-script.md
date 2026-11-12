# Handover: reading Box 2 from any session (Code, 2026-11-11)

**From:** Code session "Monitoring package local validation". **To:** whichever session needs live Box 2 readings (peer chat session at Nate's request).

## What is delivered

- `docs/hardware/esphome/tools/read_box2.py`: read-only reader (`read` snapshot, `log` CSV).
- `docs/hardware/esphome/tools/README.md`: setup, key handling, scheduling for Windows and cron.

## State of Box 2 (2026-11-11 18:50 CST)

Up at `192.168.1.130` on Home Wi-Fi, in its metal box, firmware built 2026-11-09 03:12 (24 entities plus derived dew point and absolute humidity). Not adopted into Home Assistant; no stable platform device ID yet. Last snapshot: SEN66 23.6 C / 44.1 % / CO2 811 ppm / PM2.5 2.2 ug/m3 / VOC 107 / NOx 1; SHT45 26.0 C / 39.6 %; Wi-Fi -79 dBm; chip 76.4 C.

## Not tested / not done

- The scheduled-task and cron lines are written but not run on any machine.
- The reader was tested on Windows with Python 3.14 and `aioesphomeapi` 46.12.0; not on the M920q.
- Not done: Starlink DHCP reservation for `192.168.1.130` (MAC `90:DA:72:71:51:10`), adoption into HA, supply-voltage measurement, ontology entries for the derived channels.

## Rules for the reader

- **The API key is not in the repo and must not be added.** Omission is not permission: this note lists what to do, not everything that is allowed. Do not reflash, rename, restart, re-pair or change firmware on Box 2 without Nate's go.
- A connected API client holds the node out of its 15-minute no-client reboot loop. Stopping a long-running logger returns it to that loop at Home.
