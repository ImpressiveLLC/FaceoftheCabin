# Reading Box 2 from any session

`read_box2.py` reads the ESPHome node `fotc-qtpy-box2` over its encrypted native API. It is read-only: it never reflashes, renames, restarts or re-pairs anything.

## The key is not in the repo

The API encryption key lives only in the ESPHome `secrets.yaml` on the build machine (and the vault). Never commit it, paste it in chat, or print it. Give it to the script one of two ways:

```
# environment variable (preferred for scheduled jobs)
BOX2_API_KEY=<key>

# or point at a secrets.yaml that has an api_key: line, kept OUTSIDE this repo
--secrets C:\path\outside\repo\secrets.yaml
```

A session that does not have the key cannot read the node. Ask Nate for it through the usual secret path (password-manager entry `esphome/fotc-qtpy-boxN`); do not look for it in the repo. See `../README.md` for adoption and lost-key recovery.

## Setup

```
pip install "aioesphomeapi==46.12.0"
```

The pin is the version Box 2's firmware was built and tested against (ESPHome 2026.10.2). On Windows, a fresh venv avoids the broken `aioesphomeapi` package that was found in the old ESPHome build venv on 2026-11-11 (its `__init__.py` was missing).

## Use

```
python read_box2.py read                                   # one snapshot of every entity
python read_box2.py log --duration 900 --out box2_log.csv  # append rows: t_s, iso, name, value
```

Host defaults to `192.168.1.130` (Box 2's static address; override with `--host` or `BOX2_HOST`). Numeric entities only are logged; text sensors (SSID, BSSID, IP, version, reset reason) show in `read` only.

You must be on the same network as the node. Box 2 is 2.4 GHz only, on `192.168.1.0/24` at Home and at the cabin. At the cabin the M920q reaches that subnet through its cabled `eno2` port, not its 5 GHz Wi-Fi (`192.168.2.0/24`); see `docs/MAINTENANCE.md`.

## Scheduling (not yet run on any machine; verify the first run)

Why 14-minute starts with 15-minute windows: ESPHome's API has a default `reboot_timeout` of 15 minutes. With no API client connected, the node reboots itself every 15 minutes, which is what happens at Home with no Home Assistant (purple light). A logger that keeps a client connected overlaps and stops the reboots, and also gives continuous data.

Windows Task Scheduler (set `BOX2_API_KEY` as a user environment variable first, then):

```
schtasks /Create /TN "Box2Log" /SC MINUTE /MO 14 /TR "cmd /c python C:\tools\read_box2.py log --duration 900 --out C:\data\box2_log.csv"
```

Linux or the M920q (cron, key in a root-only file `/etc/box2.key`, not in the repo):

```
*/14 * * * * BOX2_API_KEY="$(cat /etc/box2.key)" python3 /opt/box2/read_box2.py log --duration 900 --out /var/log/box2_log.csv
```

The CSV grows by about 7 rows per 10 seconds per 14 numeric entities; rotate or trim it. Nothing here writes to the node.

## Known behavior to account for

- The first minutes after a reboot are warm-up for the SEN66 (CO2 and VOC) and the SHT45; do not chart or alert on them.
- Box 2's Wi-Fi signal in the metal box has measured -70 to -86 dBm depending on orientation (see `docs/RUNLOG_2026-11-03_box2-bench-validation.md`).
