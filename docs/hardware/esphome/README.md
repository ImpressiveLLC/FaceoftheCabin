# ESPHome sensor nodes (Doc 27 boxes): secrets, adoption and recovery

Master page for the QT Py ESP32-S3 nodes (Box 2 sensor, Box 3 and Box 4 sensor halves, later the S6 speed node). Firmware lives in `box2.yaml`, `box3.yaml` and `packages/`. This page covers what the firmware files deliberately leave out: the secrets each node holds, how to adopt a node into Home Assistant, and what to do when a secret is lost.

## The three secrets every node holds

Each node's firmware is built with three values from `secrets.yaml`. They are separate data elements in the ontology (`esphome_node_secret`, which extends `platform_secret`), not part of the device record.

| Secret | `secrets.yaml` name | Used for | Scope |
|---|---|---|---|
| API encryption key | `boxN_api_key` | Home Assistant, `read_box2.py` and any other client connecting to the node's native API (port 6053) | One per node |
| OTA password | `boxN_ota_password` | `esphome upload` over Wi-Fi | One per node |
| Setup access-point password | `ap_password` | Joining the node's own `fotc-boxN-setup` network when it has no Wi-Fi | Shared by all nodes |

Rules:
- Values are never committed, pasted into chat or tickets, or printed in logs. `secrets.yaml` is gitignored. `secrets.yaml.example` shows the names with placeholders.
- **System of record: Nate's password manager.** Vaultwarden is the planned home but is not deployed yet (`docs/MAINTENANCE.md`, cabin-monitor Backup note), so use the password manager he already uses until it is. Entry names are a convention, not yet populated: `esphome/fotc-qtpy-boxN` holding the API key and OTA password, plus `esphome/ap_password`. The build machine's `secrets.yaml` is a working copy, not the record.
- A node without its key cannot be adopted into Home Assistant or read by any client. The key is also baked into the firmware, so it cannot be read back from the node.

## Adopting a node into Home Assistant

Prerequisites: node powered and on the cabin Wi-Fi (LED purple, medium blink, no cyan or red), and the key that node was built with (from wherever it is stored; adoption needs the matching key, not a particular vault).

1. Open Home Assistant (cabin M920q: `http://100.77.44.113:8123` over Tailscale, or `http://192.168.2.46:8123` on the LAN). Settings, Devices & services.
2. Use the **Discovered** ESPHome card for `fotc-qtpy-boxN` (Configure), or Add integration, ESPHome, and enter the node's IP and port 6053. Discovery uses mDNS and does not cross subnets, so enter the IP by hand if there is no card.
3. Enter the API key when asked ("Please enter the encryption key for Box N"). Submit.
4. Confirm: the LED goes to rainbow, "Node status" is on, the SEN66 and SHT45 entities report.
5. Record the adoption in the node's row in `docs/RUNLOG_2026-11-03_box2-bench-validation.md` (date, IP, who adopted it). Never record the key.

An unadopted node reboots itself every 15 minutes (ESPHome `reboot_timeout`). That is harmless and stops once Home Assistant, or any logger such as `tools/read_box2.py log`, holds a connection.

Addressing: `manual_ip` in ESPHome 2026.10.2 is ignored because no `ssid` is configured, so nodes take DHCP addresses (Box 3 took `192.168.1.138`, not `.131`). Find the real address in the router's client list or the Home Assistant ESPHome device page. Reserve addresses in the router (open item in the runlog).

## LED meaning (from the firmware)

Blue solid starting; yellow slow blink Wi-Fi down; purple medium blink Wi-Fi up, waiting for Home Assistant; red fast blink SEN66 fault; cyan fast blink SHT45 fault; red and cyan alternating both faulted; rainbow healthy and connected.

## If the key is lost

Check in this order: the password manager, the build machine's `secrets.yaml` (next to the box YAML, never in git), any machine that ran `esphome upload` for that node. If none has it, the node must be rebuilt and reflashed with a new key. Reflashing the old `firmware.factory.bin` does NOT help: it still carries the old key.
1. Inputs: a complete `secrets.yaml` with every name the box YAML reads (`api_key` or `boxN_api_key`, the matching OTA password, `ap_password`). Keep the known OTA and setup-AP passwords unchanged; only the API key for the affected node is new. Generate it with `openssl rand -base64 32`, save it to the password manager first, then to `secrets.yaml`.
2. Validate: `esphome config boxN.yaml` passes.
3. Compile a fresh image: `esphome compile boxN.yaml`. Use the new `firmware.factory.bin` for USB flashing.
4. Flash over USB (bootloader entry per the runlog: unplug, hold BOOT, plug in, release after about 2 s). USB flashing erases saved Wi-Fi, so the node returns to `fotc-boxN-setup` at `http://192.168.4.1`; enter the cabin Wi-Fi there.
5. Verify: adopt in Home Assistant with the new key, or run `tools/read_box2.py` with it, and confirm authenticated readings and the rainbow LED.
Over-the-air reflash is only possible if the OTA password is still known; it keeps saved Wi-Fi.

Governance: no work-item or decision ID is allocated for this change; linkage to the W-84 firmware thread is pending the PMO.

## Status

| Node | Firmware | Adopted in HA | Notes |
|---|---|---|---|
| Box 2 (`fotc-qtpy-box2`) | flashed, NTP package | not adopted at Home (no HA there) | read with `tools/read_box2.py` |
| Box 3 (`fotc-qtpy-box3`) | flashed, NTP package | **pending**: HA discovered it 2026-11-13 and prompts for the key; key not located yet | left powered at the cabin, LED purple |
| Box 4 | none (W-84) | no | |

See also: `tools/README.md` (reading a node), `packages/README.md` (shared packages), `docs/MAINTENANCE.md` "ESPHome node secrets", `docs/REPLICATION.md` section on ESPHome nodes, ontology `esphome_node_secret`.
