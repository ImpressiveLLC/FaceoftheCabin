# Run Log — Box 2 bench validation at Home (2026-11-03)

> **Result: PARTIAL (updated 2026-11-04).** Hardware, sensors and Home Wi-Fi
> validated on the bench; the node survives a power-cycle and rejoins by itself.
> HA discovery under a stable device ID and an independent temperature reference
> are **not** done. Box 2 is **not** cleared for the box.

Scope: `SensorCollectionBox` (Box 2), QT Py ESP32-S3 (PID 5426) + SEN6x adapter
(PID 6331) + SEN66 + SHT45 (PID 6174), bench-powered over USB-C, outside the
enclosure. Builds on `docs/hardware/doc27-schematics.md` (S1) and
`docs/BUILD_INSTRUCTIONS_2026-10-18_box2-box3-box4.md`. Firmware is the W-84
ESPHome-native path (not ratified): `docs/hardware/esphome/box2-bench.yaml`.

## What was verified (evidence)

| Check | Result | Evidence |
|---|---|---|
| Board, flash | ESP32-S3 rev v0.2, 8 MB flash | `esptool flash-id` |
| SEN66 on I2C | **PASS**, `0x6B`, product `SEN66`, firmware 4.0, serial `5C3F4CBDAF9797D6` | ESPHome boot log |
| SHT45 on I2C | **PASS**, default `0x44`, reports T/RH | heartbeat log |
| SEN66 supply | **PASS**, 3.29 V DC steady, spec 3.15 to 3.6 V | multimeter, SEN6x adapter VIN to GND, running |
| Update schedule | **PASS** as configured: both sensors every 10 s; SEN66 gives no values for the first ~60 s (ESPHome startup delay) | change times in 150 s serial capture |
| SEN66 vs SHT45 | T 22.73 vs 22.76 °C (0.03 apart), RH 53.0 vs 54.8 % (1.8 apart) | same capture |
| Other readings | PM1 3.9, PM2.5 5.2, PM4 6.2, PM10 6.7 µg/m³; CO2 1258 ppm; VOC index 105; NOx index 1 (VOC/NOx algorithms still learning) | same capture |
| Status light | **PASS**: purple blink with no HA client; **rainbow** while an API client was connected (2026-11-04, release build `box2.yaml`, deployed over the air) | user observation |

## What is NOT verified

- **Independent reference: loosely checked only.** The Home Z2M is down (`NOT_CONNECTED`, last state file 2026-10-27). Nate's room estimate was about 70 °F (21.1 °C, approximate, instrument not recorded) against SEN66 22.7 °C and SHT45 23.0 °C, so the box reads about 1.6 to 2 °C warm. That is plausible bench self-heating (MCU, SEN66 fan, USB supply) but is unconfirmed. Needs a calibrated thermometer placed beside the box, and a re-check once it is in its enclosure, before choosing the authoritative ambient channel (doc27 S1). The 1258 ppm CO2 is plausible for a closed room but unchecked.
- **Home Wi-Fi: RESOLVED 2026-11-04.** Two real causes, both fixed. (1) Provisioned credentials never persisted: ESPHome flushes saved Wi-Fi to flash on a 60 s timer, and every reset or replug inside that minute reverted the node to the previous network (`Nathan `, the phone hotspot). Fixed with `preferences: flash_write_interval: 10s`. (2) IP conflict: the router handed Box 2 `192.168.1.112`, which the LG webOS TV also held. Fixed with a DHCP reservation, MAC `90:DA:72:71:51:10` to `192.168.1.130`. The early `Association Leave` timeouts occur before any IP is assigned, so the conflict cannot explain those; they stopped once credentials persisted and the reservation existed, but the exact trigger was not isolated. Verified: node at `192.168.1.130` (-61 to -63 dBm), ESPHome API reachable, rejoins after a full power-cycle with no intervention.
- **HA discovery and stable device ID.** Cabin HA has no `esphome` config entry; the inventory `runtime_device_id` for Box 2 is `null`; no `device` row exists. Discovery can only be tried where the node can reach HA (the cabin LAN).
- **Phone proxy only.** The Termux phone has no ambient sensor; its battery reads 23.0 °C while charging (Box 2: SEN66 22.7, SHT45 23.0). Not a reference.
- **Final voltage under startup.** Only a steady-state reading; startup inrush not captured.

## Lessons (reusable for Boxes 3, 4 and the S6 controller box)

1. A fresh QT Py has no firmware and no Wi-Fi credentials; it will not appear on any network. Flash first.
2. QT Py bootloader entry: unplug, hold BOOT, plug in, release after about 2 s. A BOOT+RESET tap did not work. While BOOT is held at reset the chip stays in download mode (`boot:0x0 DOWNLOAD(USB/UART0)`); release BOOT and tap RESET.
3. ESPHome on Windows must be built from PowerShell. Under Git Bash, ESP-IDF fails with `MSys/Mingw is not supported`. The first build is about 10 minutes.
4. Once ESPHome runs, `esptool` resets the chip over the USB-Serial/JTAG port (`303A:1001`) with no button presses. The logger works on that same port.
5. Do not pulse RTS repeatedly: each quick reset counts toward ESPHome safe mode (8 restarts).
6. ESPHome sensors only probe I2C at boot. Reconnecting a sensor needs a reboot; with only the QT Py attached, `sen6x` and `sht4x` report `Communication failed`.
7. Wi-Fi provisioning without typing a password into a script: Improv over USB from `https://web.esphome.io` (Wi-Fi icon). Do **not** press "Prepare for first use": it overwrites the firmware. Disconnect the browser afterwards, or it holds the COM port.
8. The ESP32-S3 is 2.4 GHz only. A phone hotspot must be 2.4 GHz, WPA2. This Windows PC could not list any 2.4 GHz network, so a PC Wi-Fi scan proves nothing about 2.4 GHz networks.
9. The captive portal and a phone hotspot cannot share one phone; Improv over USB avoids the conflict.
10. ARP entries go stale: a `.112` address showed the node's MAC for a while after the node left and the address went to another device.
11. The debug log can look silent. A periodic `ESP_LOGW` heartbeat with uptime and all sensor values (in `box2-bench.yaml`) is the reliable way to read values over USB. Remove it, and set `logger: INFO`, for the release build.
12. Wi-Fi credentials entered through Improv (`web.esphome.io`) did not persist in this setup; the captive portal on the fallback AP did, once the 10 s flush interval was set. Never reset or unplug within 60 s (default) of provisioning. A held `COM8` (browser tab) blocks every serial tool: disconnect the tab first.
12a. Reserve a DHCP address for each node in the router before relying on it. Two devices on one IP look like random drops.
12b. A multimeter reading is DC volts (`V` with a straight line). Probe the adapter VIN against GND with the board running.
13. Spray foam inside the box is a bad idea near the SEN66 (VOC off-gassing, blocked inlet and fan, trapped heat, no rework), and over mains terminals or relays. Use standoffs, foam blocks, RTV dabs; mask the light pipe.

## Status light (bench firmware)

15 % brightness, NeoPixel on `GPIO39`, power on `GPIO38`. Blue solid: starting (first 8 s). Yellow slow blink: Wi-Fi connecting or lost. Purple blink: Wi-Fi up, waiting for HA. Red fast blink: sensor fault. Rainbow: healthy.

## Release build (2026-11-04)

`box2.yaml`: INFO logging, no heartbeat line (deliberately held back; can be added later over the air, e.g. an INFO line every 60 s). Per-component fault colours: SEN66 red, SHT45 cyan, both alternating, rainbow when healthy. Pushed over Wi-Fi to the node with `esphome upload --device 192.168.1.130` (no USB needed once Wi-Fi works).

## Address plan (2026-11-04)

Home and the cabin are both `192.168.1.0/24`, gateway `.1` (Home: Linksys Smart Wi-Fi; cabin: Starlink). `.128` to `.135` answered nothing at either site on 2026-11-04. Box 2 now has a static IP in firmware; a router reservation is still preferred because the cabin DHCP pool covers the `.1xx` range (the M920q holds `.155`).

| IP | Node | Status |
|---|---|---|
| `192.168.1.130` | Box 2 (`SensorCollectionBox` Cabin Upstairs), MAC `90:DA:72:71:51:10` | static IP in `box2.yaml`; Home reservation exists; Starlink reservation not yet set (needs the Starlink app) |
| `192.168.1.131` | Box 3 (Bathroom `RelayControlBox`) | held, not assigned |
| `192.168.1.132` | Box 4 (Mech Room `RelayControlBox`) | held, not assigned |
| `192.168.1.133` | S6 speed node (W-78, Freenove ESP32-S3) | held, not assigned |

Reserve each in the Starlink app by MAC once the board's MAC is known (read it with `esptool flash-id`), and use `manual_ip` in firmware only as a fallback. Nothing in the cabin DHCP pool was changed; these are plan entries, not router settings.

## Next

1. Calibrated reference beside the box (thermometer or live SNZB-02P); re-check inside the enclosure.
2. At the cabin: the SSID is the same name; confirm it joins (and add a DHCP reservation there). If association fails, re-provision through the fallback AP portal, wait over 10 s, no resets.
3. Add the ESPHome integration in cabin HA with the node's API key (vault, not repo), confirm discovery picks it up, then record `runtime_device_id` under D1/D28.
4. Release build: `logger: INFO`, drop the heartbeat, decide the final update interval.
5. Correct the build doc per PR #258 (still unapplied on main as of this run).

## Home collector restored (2026-11-05)

Home temperature and humidity were missing because the Termux phone (`G3223`) was off Tailscale and Home Zigbee2MQTT was wedged (`STOPABORT`, then daily `NOT_CONNECTED` backup errors since 2026-10-27). The retained `home_z2m/bridge/state` was `offline` for days and nothing alerted.

- Tailscale turned on via adb; Z2M restarted; `home_z2m/bridge/state` is `online`; network-scan-agent reconnected to the cabin broker.
- Tailscale is now set as the Android Always-on VPN on `G3223` (Settings, VPN, gear). Block-connections-without-VPN is left off so the phone keeps its own internet if the tunnel is down. Setting `always_on_vpn_app` with `adb shell settings put` did not register in the UI; the toggle had to be tapped.
- Lesson: a quick-settings tap on the phone switched Wi-Fi off once. Check Wi-Fi after any UI tapping on the phone.
- Only one Home SNZB-02P is joined; the five planned Home sensors (W-80) are not paired.
- Alert gap logged as backlog W-151.

## Boxed check and diagnostics flash (2026-11-09)

Box 2 sits in its metal single-gang box, outside the cabin, still on the Home network (192.168.1.130).

- **Wi-Fi in the box:** -86 dBm in the first orientation, -72 to -73 dBm (min -73, max -70, 10 min) after Nate rotated it. Orientation was worth 13 dB. Marginal against the -70 good line; re-measure after the cabin mounting.
- **10-minute log (60 samples per channel, 9 to 10 s interval):** SEN66 22.95 C / 45.7 % RH, SHT45 23.61 C / 44.8 % RH (delta 0.7 C, 0.9 %RH), CO2 934 ppm mean (879 to 1072), PM2.5 1.1, PM10 1.7 ug/m3, VOC index 99, NOx 1. Dew point 10.5 to 10.9 C, absolute humidity 9.3 to 9.5 g/m3 (derived off-node).
- **SHT45 offset:** a first read after rotation showed SHT45 27.7 C against SEN66 22.9 C; over 10 minutes it settled to 0.7 C. Treat the earlier 4.8 C as a transient from handling, not steady self-heating.
- **Firmware (config hash 0x937367e5, OTA):** adds `internal_temperature` (Chip temperature), `debug` (Free heap, Reset reason), `wifi_info` (IP, SSID, BSSID, MAC), `status` (Node status) and template fault binary sensors for SEN66 and SHT45. 24 entities, up from 14. `docs/hardware/esphome/box2.yaml` matches.
- **Chip temperature:** 76.4 C one minute after boot. Not yet trended; the module is rated to 85 C. Watch it over hours before closing the box for good. Candidate mitigations: `output_power` below 15 dB, `power_save_mode: light`.
- **Open:** supply voltage at the SEN66 (multimeter); cabin LAN is 192.168.2.0/24 via Tailscale, so the static IP and .131 to .133 plan must move before the node goes there.

### Derived channels flashed (2026-11-09, OTA, build 03:12:44)

`Dew point (derived, SHT45)` and `Absolute humidity (derived, SHT45)` added as on-node template sensors (Magnus formula, SHT45 only, 10 s). Checked against an independent calculation: SHT45 24.09 C / 43.6 % RH gave 10.90 C dew point and 9.5 g/m3, matching. Chip temperature read 77.4 C at one minute of uptime. Nate's condition: SHT45 stays stable for the next couple of days; the formulas can be changed later by OTA from the M920q once the node is on the cabin network.

Not done: ontology entries for the two derived channels (D28 naming, `data_class: derived`, `derived_from` the SHT45 channels). Do this with W-84, not as a side edit to the 4000-line ontology.
