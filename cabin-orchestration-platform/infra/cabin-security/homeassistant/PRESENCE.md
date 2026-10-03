# Cabin presence publishers (W-23)

What publishes `cabin/presence/nate` and `cabin/presence/nate/last_seen`, why,
how to apply it, and how it was tested. Files: `cabin_security_presence.yaml`,
`cabin_security_mqtt_publish.py`, tests in `test_cabin_security_mqtt_publish.py`
and `test/ha-sim/run_sim.py`.

## Why this exists (found 2026-10-03)

- `cabin/presence/nate` had been `home` **0 times out of 190** recorded readings,
  so the hub never showed anyone at the cabin and the siren gate always read
  "away".
- The person-based automation in `cabin_security.yaml` only publishes when
  `person.natecabin` is exactly `home` or `not_home`. Inside a zone the person's
  state is the zone **name** (`Cabin`), so it never fired.
- The WiFi check (`binary_sensor.nate_phone_on_wifi`) has never matched. Most
  likely cause: the phone presents a private (randomized) MAC on the cabin
  network, so the ARP lookup for its real MAC finds nothing.
- Nothing published from GPS for the cabin, although the house has had a GPS
  publisher (`home_presence.yaml`, live only) for a while.

## Publishers after this change

| Automation | Topic | Role |
|---|---|---|
| `cabin_presence_publish_nate_presence_from_phone_gps` | `cabin/presence/nate` | **Primary.** Phone enters/leaves `zone.cabin`. `home` immediately, `not_home` after 3 continuous minutes away (`mode: restart` cancels the pending `not_home` if the phone comes back). A copy of the house automation. |
| `cabin_security_publish_nate_presence_from_phone` (WiFi) | `cabin/presence/nate` | **Backup.** `home` when the phone is on the cabin WiFi. Its `not_home` is suppressed while GPS says the phone is inside `zone.cabin`, because a phone can drop off WiFi to sleep while still at the cabin and a false "away" is the dangerous direction for the siren gate. |
| `cabin_security_publish_presence_at_startup` | `cabin/presence/nate` | After an HA restart, republishes `home` if WiFi is on **or** GPS has the phone in `zone.cabin`. Before this it republished `not_home` over a correct GPS `home`. |
| `cabin_presence_publish_nate_last_seen` | `cabin/presence/nate/last_seen` | Heartbeat, every 5 minutes. See below. |
| `cabin_presence_note_ha_start` | (event only) | Records each HA start so the heartbeat can ignore restart noise. |

`cabin_security.yaml`'s person-based publisher is left as it is: harmless, and
not in this change's scope.

## The heartbeat contract

`cabin/presence/nate/last_seen` is a **retained UTC ISO-8601 time**
(`2026-10-04T08:15:00Z`): the moment the phone's Companion app last **reported
to Home Assistant**, read from `last_reported` of `sensor.nates_s23_battery_level`.
It is not "now", so a silent phone's heartbeat does not advance and ages out.

Consumers: the siren gate (W-33, `nodered/flows.json`) and the hub badge (W-34,
`/api/presence`). Both treat **no heartbeat ever seen** as "age unknown", never
as stale, so the publisher can be applied before or after them.

Design points, each found by simulating this in a real Home Assistant 2026.7.4:

- It **polls** (`time_pattern`, every 5 minutes). HA refuses automation triggers
  on `state_reported`, and template sensors do not re-render when a sensor
  re-reports an unchanged value, so a state trigger would miss every report
  where the battery stayed at 100%. A healthy phone on a charger would look dead.
- **Restart guard.** After an HA restart every restored sensor is rewritten with
  the restart time, which would make a dead phone look freshly seen. A heartbeat
  counts only if `last_reported` is newer than the last recorded HA start. After
  a restart nothing is published until the phone genuinely reports; the broker
  keeps the last real value meanwhile. The guard looks the marker up by its
  **entity id** (`automation.cabin_presence_note_home_assistant_start`, which HA
  derives from the alias, not from `id:`) and **fails closed** if that entity is
  missing, so renaming the alias stops the heartbeat instead of letting restart
  noise through. (A first draft used the `id:` as if it were the entity id and
  the guard silently passed everything; the simulation caught it.)
- The publisher script validates the payload with a strict pattern **and** a real
  date parse (`2026-13-40T99:00:00Z` is refused), same fail-closed rule as the
  fixed allow-list.

## Apply on the M920q (needs Nate's OK; nothing here has been applied)

Live paths (checked 2026-10-03): HA config is `/storage/services/homeassistant`
(`/config` in the container). At that time the live `packages/cabin_security_presence.yaml`
and `check_nate_phone_wifi.sh` were identical to git `main`, and the live
`cabin_security_mqtt_publish.py` differed from this version only by the W-23
additions, so applying is a clean two-file copy. **Do not copy
`cabin_security.yaml`**: the live copy has drifted from git and is not part of
this change.

```bash
cd /storage/services/homeassistant
d=$(date +%Y%m%d)
cp -p packages/cabin_security_presence.yaml packages/cabin_security_presence.yaml.bak-$d
cp -p cabin_security_mqtt_publish.py       cabin_security_mqtt_publish.py.bak-$d
# copy the two files from a checkout of this PR's branch over them, then:
docker exec homeassistant python -m homeassistant --script check_config -c /config
```

The check must print no errors. Then **reload automations** (Developer Tools >
YAML > Automations, or `automation.reload`). A restart is not needed: only
automations changed, the `command_line` sensor block is untouched, and the
publisher script runs fresh on every call. Reload also keeps the restart guard
inactive (no start has been recorded yet), so the heartbeat starts as soon as
the phone has reported.

Rollback: copy the two `.bak-$d` files back and reload automations.

### Checks after applying

1. Within about 5 minutes: `mosquitto_sub -h 127.0.0.1 -t cabin/presence/nate/last_seen -C 1 -W 400`
   shows a recent UTC time, and `GET /api/presence` shows `lastSeen` /
   `signalAgeSeconds` with `stale: false`.
2. **Not yet verified live:** that the Companion app re-sends unchanged sensor
   values so `last_reported` advances while the battery level is steady (shown
   only in simulation). If the heartbeat stalls while the phone is clearly
   online and its battery value is unchanged, that assumption is wrong; the
   fix is to heartbeat from a sensor that always changes (for example the
   phone's "last update trigger" sensor).
3. Developer Tools > States: set `device_tracker.nates_s23` attributes to a point
   inside `zone.cabin` to see `home` publish immediately; move it out and `not_home`
   follows 3 minutes later. Doing this against the **production** tracker
   publishes real presence to the alarm, so do it only with the siren output
   disabled, or use the offline simulation below.

## Next visit to the cabin

Set the phone to **Use device MAC** (not "Use randomized MAC") for the cabin
network. That makes the WiFi check a real backup instead of a signal that never
matches. Until then GPS is the only working cabin presence signal.

## Tests

- `python -m unittest test_cabin_security_mqtt_publish` (from this directory):
  6 tests for the allow-list and heartbeat validation (paho is stubbed, no broker).
- `test/ha-sim/run_sim.py`: throwaway **offline** Home Assistant (Docker image
  `ghcr.io/home-assistant/home-assistant:2026.7.4`, fake zones, a logging stand-in
  for the publisher, nothing connects to the real broker or phone). Loads the real
  package and runs the zone crossings with debounce and cancel, startup republish,
  WiFi backup and suppression, and the heartbeat (unchanged re-report, silent
  phone, restart guard, unavailable). Fast mode shortens the 3 minute debounce to
  8 s and the 5 minute tick to 5 s; `--full` runs the shipped timings.
  `--check-config` only loads the package and prints HA's config check.
  This is the "dev-tools zone simulation": it exercises the same trigger
  and condition code, without touching the production alarm.
