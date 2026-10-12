# Location presence publishers (W-23)

What publishes `cabin/presence/<person>` and `cabin/presence/<person>/last_seen`
(today `<person>` is `nate`), who they apply to, why, how to apply them, and how
they were tested. Files: `cabin_security_presence.yaml`,
`check_authorized_user_phone_wifi.sh`, `authorized_user_phones.conf.example`,
`cabin_security_mqtt_publish.py`; tests in `test_check_authorized_user_phone_wifi.sh`,
`test_cabin_security_mqtt_publish.py` and `test/ha-sim/run_sim.py`.

## Who presence applies to: location roles (D24, proposed)

Presence answers "is an authorized occupant at this location?", so it must say
which people count. Two **location roles** (a person's relationship to one
location; separate from the signed-in permission roles in `HouseholdRole`):

| Role | Who | Counts as present? |
|---|---|---|
| **primary** | The active users of the location: the people who live there or use it. | **Yes.** Their phone is checked, their presence suppresses the siren gate. |
| **maintenance** | The person who establishes and maintains the location. Does not own it or live there. Holds the same credentials as a primary user **and elevated ones** (administration). | **No.** From the standpoint of location presence they are a **guest**: no phone check, no signal, never recognized as on-site. |

One person can hold both roles for the same location (at the cabin, Nate is
primary and maintenance, so his phone counts as a primary user's). The same person
can be maintenance at someone else's location and have no presence there at all.

**Enforcement today is by configuration only:** a phone check and a publisher
exist only for primary-role users, so a maintenance-only person produces no
signal. The hub's backend does not know roles yet (`PresenceSignalRegistry` counts
whoever publishes), so a maintenance-only person who did publish would be counted.
That is W-38. The ontology entries are `check_authorized_user_phone_wifi` and
`location_role` in `docs/ontology.yaml`.

## Why this exists (found 2026-10-03)

- `cabin/presence/nate` had been `home` **0 times out of 190** recorded readings,
  so the hub never showed anyone at the cabin and the siren gate always read
  "away".
- The person-based automation in `cabin_security.yaml` only publishes when
  `person.natecabin` is exactly `home` or `not_home`. Inside a zone the person's
  state is the zone **name** (`Cabin`), so it never fired.
- The WiFi check has never matched. **Cause not yet established.** The tooling
  works (checked 2026-10-04 inside the live HA container: `nmap`, `ip` and `arp`
  are present and the script runs and prints a correct OFF while the phone is
  away). The leading suspect is that the phone presents a private (randomized)
  MAC on the cabin network, so a lookup for its real hardware MAC finds nothing.
  That can only be confirmed with the phone at the cabin. Options for handling it
  without changing the phone: W-37.
- Nothing published from GPS for the cabin, although the house has had a GPS
  publisher (`home_presence.yaml`, live only) for a while.

## What W-23 depends on (read this before relying on it)

The GPS zone signal **and** the heartbeat both come from the **Home Assistant
Companion app** on the phone. Away from the cabin LAN the app can only reach HA
over **Tailscale** (HA is deliberately not internet-reachable). So the chain is:
phone sensor, Companion app, Tailscale, Home Assistant. If any link is down the
signal goes stale, and W-33/W-34 then treat it as unknown rather than as away.

**Verified 2026-10-04:** the S23+'s Tailscale node has been offline since
2026-10-02 00:14 UTC. That alone explains the 41-hour silence behind the "Away"
badge: the phone could not report. (The phone is back on Tailscale as of
2026-10-04.)

**Nate's direction, 2026-10-04: the phone's Tailscale should not be in the
presence loop; it is a remote device for approvals only.** Requiring the
Companion app is likewise not the intended end state. Alternatives are being
worked out under W-36 (no Companion app, no phone Tailscale) and W-37 (WiFi
without phone customization); until then the Companion app's GPS is the only
live GPS source, and it only works while the phone can reach HA.

**What that means for how this behaves, so nobody is surprised by it.** With HA
not internet-reachable, a phone that is off the cabin LAN and off Tailscale
cannot deliver GPS `leave` events, so GPS is **arrival-only**: arrival should
work (the phone can reach HA directly over the cabin LAN; unverified for this
phone), departure is never seen. The
WiFi backup would notice the phone leaving the LAN, but its `not_home` is
suppressed while the tracker (now stale) still says `zone.cabin`. So after
leaving, presence can stay `home`; after 6 hours without a heartbeat the
siren gate (W-33) stops trusting it and sends a push instead of sounding the
siren. **In that state there is no siren while away.** Absence must come from
the hub's own LAN view (W-37, now the critical path) or a source that does not
need the phone's Tailscale (W-36). Until one is chosen, treat arming Away as
alert-only protection when the phone has no route to HA.

## Publishers

| Automation / script | Topic | Role |
|---|---|---|
| `cabin_presence_publish_nate_presence_from_phone_gps` | `cabin/presence/nate` | **Primary.** Phone enters/leaves `zone.cabin`. `home` immediately, `not_home` after 3 continuous minutes away (`mode: restart` cancels the pending `not_home` if the phone returns). A copy of the house automation. |
| `check_authorized_user_phone_wifi.sh` via `binary_sensor.nate_phone_on_wifi`, and `cabin_security_publish_nate_presence_from_phone` | `cabin/presence/nate` | **Backup.** `home` when the phone is on the cabin WiFi. Its `not_home` is suppressed while GPS has the phone inside `zone.cabin`, because a phone can drop off WiFi to sleep while still at the cabin and a false "away" is the dangerous direction for the siren gate. |
| `cabin_security_publish_presence_at_startup` | `cabin/presence/nate` | After an HA restart, republishes `home` if WiFi is on **or** GPS has the phone in `zone.cabin`. Before this it republished `not_home` over a correct GPS `home`. |
| `cabin_presence_publish_nate_last_seen` | `cabin/presence/nate/last_seen` | Heartbeat, every 5 minutes. See below. |
| `cabin_presence_note_ha_start` | (event only) | Records each HA start so the heartbeat can ignore restart noise. |

`cabin_security.yaml`'s person-based publisher is left as it is: harmless, and
not in this change's scope.

### The phone WiFi check is person-agnostic

`check_authorized_user_phone_wifi.sh <person_id>` holds nothing about any person
or site. It reads the person's WiFi MAC and the LAN to sweep from
`/config/authorized_user_phones.conf`, which is **not committed** (a MAC identifies
a device); `authorized_user_phones.conf.example` is the template. A configuration
problem exits 2 and prints nothing, so HA reports the sensor unavailable and
neither automation acts on it: an error must never read as "away".

One `command_line` sensor instance exists per primary-role user. The instance
keeps the person id in its name (`binary_sensor.nate_phone_on_wifi`) because an
instance belongs to one person and renaming a live entity id orphans its history.

**Adding another primary-role user** (for example a second occupant, or a new
clone): add a line to `authorized_user_phones.conf`; add a `command_line` sensor
with `command: "/config/check_authorized_user_phone_wifi.sh <person_id>"`; copy
the WiFi, startup, GPS and heartbeat automations for that person's topic and
tracker. Generating those per person is part of W-38. **Do not** add a
maintenance-only user.

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

## Apply on the M920q (needs Nate's OK)

Live paths (checked 2026-10-04): HA config is `/storage/services/homeassistant`
(`/config` in the container). The live `cabin_security_presence.yaml` and the old
WiFi script were identical to git `main`, and the live
`cabin_security_mqtt_publish.py` differed from this version only by the W-23
additions. `cabin_security.yaml` is **not** part of this change (its only
live-vs-git difference was comments, reconciled separately).

```bash
cd /storage/services/homeassistant
d=$(date +%Y%m%d)
cp -p packages/cabin_security_presence.yaml packages/cabin_security_presence.yaml.bak-$d
cp -p cabin_security_mqtt_publish.py       cabin_security_mqtt_publish.py.bak-$d
# from a checkout of this branch: copy these into place
#   packages/cabin_security_presence.yaml
#   cabin_security_mqtt_publish.py
#   check_authorized_user_phone_wifi.sh        (chmod +x, LF line endings)
# and create authorized_user_phones.conf from authorized_user_phones.conf.example
# (the phone's MAC and the cabin LAN; not committed)
docker exec homeassistant python -m homeassistant --script check_config -c /config
```

The check must print no errors. Then **reload** `command_line` and `automation`
(Developer Tools > YAML, or the `command_line.reload` and `automation.reload`
services). A restart is not needed: no integration was added, and the publisher
script runs fresh on every call. Reload also keeps the restart guard inactive (no
start has been recorded yet), so the heartbeat starts as soon as the phone reports.
Keep the old `check_nate_phone_wifi.sh` until the new sensor reads correctly, then
move it to `check_nate_phone_wifi.sh.bak-$d`.

Rollback: copy the `.bak-$d` files back, remove the new script, reload.

### Checks after applying

1. `binary_sensor.nate_phone_on_wifi` is `off` (not `unavailable`) while the phone
   is away: that proves the new script, its config file and the sweep work.
2. A heartbeat appears: `mosquitto_sub -h 127.0.0.1 -t cabin/presence/nate/last_seen -C 1 -W 400`.
   **It will be old (the phone's last report) while the phone is off Tailscale, and
   the hub and gate will correctly read it as stale.** It only turns fresh once the
   phone reports again.
3. **Not yet verified live:** that the Companion app re-sends unchanged sensor
   values so `last_reported` advances at a steady battery level (shown only in
   simulation). If the heartbeat stalls on a clearly online phone, heartbeat from
   a sensor that always changes (for example the phone's last-update-trigger).
4. Developer Tools > States: setting `device_tracker.nates_s23` to a point inside
   `zone.cabin` publishes `home` at once; moving it out publishes `not_home` three
   minutes later. Doing that against the **production** tracker publishes real
   presence to the alarm, so only with the siren output disabled, or use the
   offline simulation below.

## The private-MAC question (open: more ideation needed)

Nate's direction (2026-10-04): do **not** make "set the phone to Use device MAC on
the cabin network" the answer. Per-phone customization gets sticky and abrasive
fast (every new user, every OS update, every replacement phone). Lean toward a
workaround that needs nothing from the phone, and **work through the options
before choosing**: W-37 (WiFi/LAN presence without phone customization) and W-36
(presence without the Companion app) hold the option lists and the criteria. Until
one is chosen, the cabin WiFi check may never match and GPS is the only working
cabin signal.

## Tests

- `sh test_check_authorized_user_phone_wifi.sh`: 24 checks for the WiFi check
  (presence, one user's phone never answers for another, the configured LAN is
  swept, every configuration error prints nothing and exits 2, and the real
  `authorized_user_phones.conf` is neither tracked nor un-ignored). Mutation
  checked: making errors print OFF fails 7 of them, and force-adding a fake
  config fails the hygiene check. `nmap` and `ip` are stubbed.
- `python -m unittest test_cabin_security_mqtt_publish`: 6 tests for the
  allow-list and heartbeat validation (paho is stubbed, no broker).
- `test/ha-sim/run_sim.py`: throwaway **offline** Home Assistant (Docker image
  `ghcr.io/home-assistant/home-assistant:2026.7.4`, fake zones, a logging stand-in
  for the publisher and for the WiFi check; nothing connects to the real broker or
  phone). Loads the real package and runs the zone crossings with debounce and
  cancel, startup republish, WiFi backup and suppression, and the heartbeat
  (unchanged re-report, silent phone, restart guard, fail-closed marker,
  unavailable). Fast mode shortens the 3 minute debounce to 8 s and the 5 minute
  tick to 5 s; `--full` runs the shipped timings. `--check-config` only loads the
  package and prints HA's config check. This is the "dev-tools zone simulation":
  it exercises the same trigger and condition code without touching the
  production alarm.
