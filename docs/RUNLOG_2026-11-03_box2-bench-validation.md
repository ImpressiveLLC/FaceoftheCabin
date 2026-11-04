# Run Log — Box 2 bench validation at Home (2026-11-03)

> **Result: PARTIAL.** Hardware and sensors validated on the bench. Home Wi-Fi
> association, HA discovery under a stable device ID, and an independent
> temperature reference are **not** done. Box 2 is **not** cleared for the box.

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
| Status light | **PASS**, blinking purple = Wi-Fi up, waiting for HA client, as designed | user observation |

## What is NOT verified

- **Independent reference.** The Home Z2M is down (`NOT_CONNECTED`, last state file 2026-10-27), so no live Zigbee temperature was available. Needs a room thermometer or a live SNZB-02P. The 1258 ppm CO2 is plausible for a closed room but unchecked.
- **Home Wi-Fi.** Against the Home router (`Unicorn Ping-Pong`, 2.4 GHz, BSSID `48:F8:B3:F6:4E:2A`, channel 11) every association attempt timed out with `Association Leave` and full signal bars, across firmware revisions, on USB and wall power, with and without the SEN66, router client not blocked. It joined once early on (no sensors attached). It **does** join a phone hotspot (WPA2, 2.4 GHz, channel 6, -63 dB), on the second attempt (first: `Authentication Failed` or `Auth Expired`). Cause not found.
- **HA discovery and stable device ID.** Cabin HA has no `esphome` config entry; the inventory `runtime_device_id` for Box 2 is `null`; no `device` row exists. Discovery can only be tried where the node can reach HA (the cabin LAN).
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
12. A multimeter reading is DC volts (`V` with a straight line). Probe the adapter VIN against GND with the board running.
13. Spray foam inside the box is a bad idea near the SEN66 (VOC off-gassing, blocked inlet and fan, trapped heat, no rework), and over mains terminals or relays. Use standoffs, foam blocks, RTV dabs; mask the light pipe.

## Status light (bench firmware)

15 % brightness, NeoPixel on `GPIO39`, power on `GPIO38`. Blue solid: starting (first 8 s). Yellow slow blink: Wi-Fi connecting or lost. Purple blink: Wi-Fi up, waiting for HA. Red fast blink: sensor fault. Rainbow: healthy.

## Next

1. Independent reference reading (thermometer or live SNZB-02P).
2. Re-provision the cabin Wi-Fi (2.4 GHz) at the cabin and confirm it joins there; if association fails there too, treat it as a firmware or board issue.
3. Add the ESPHome integration in cabin HA with the node's API key (vault, not repo), confirm discovery picks it up, then record `runtime_device_id` under D1/D28.
4. Release build: `logger: INFO`, drop the heartbeat, decide the final update interval.
5. Correct the build doc per PR #258 (still unapplied on main as of this run).
