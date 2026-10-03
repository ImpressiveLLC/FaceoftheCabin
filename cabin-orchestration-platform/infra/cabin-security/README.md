# Cabin door-triggered water-alarm flow

This package adds a fail-safe Node-RED intrusion flow that uses the two
Zigbee door contacts as entry signals and the three Third Reality leak
alarms as audible sirens.

**The live sirens are enabled** (as of the 2026-10-04 sync of `nodered/flows.json`
to the deployed flow). Treat every change here as a change to a real alarm.

## What makes the sirens sound

`security-gate` evaluates a closed-to-open door transition, after a
30-second presence grace period. A siren-driving alarm candidate needs all of:

1. **Enabled:** retained `cabin/security/enabled` is `ON`.
2. **Armed:** retained `cabin/security/armed_away` is `ON`.
3. **Presence is exactly `not_home`:** the retained MQTT topic
   `cabin/presence/nate` (published by Home Assistant; see below). The gate
   reads this topic, **not** the `person.natecabin` entity directly. An
   earlier version of this README said otherwise.
4. **Presence is fresh** (W-33, below).
5. No siren raised in the last 10 minutes.

`home` blocks the alarm. Anything other than `home` or `not_home` (unknown,
unavailable, missing) fails safe with no alarm. The first door observation
after Node-RED starts only initializes state, so startup cannot create an
alarm. Only a closed-to-open transition is actionable.

Every decision is published (not retained) to `cabin/security/intrusion/event`
with the reason and the inputs, including the presence age.

## Stale presence: push only, no siren (W-33)

Presence is published only when it *changes*, so the retained value can be
days old and still be right (you have been at the cabin all weekend). What
matters is whether the **phone is still reporting**, not when the value last
changed. So Home Assistant also publishes a heartbeat:

| Topic | Payload | Meaning |
|---|---|---|
| `cabin/presence/nate/last_seen` | retained ISO-8601 UTC, e.g. `2026-10-04T09:00:00Z` | When the phone's Companion app last reported to Home Assistant |

The flow remembers it (`presence-seen-state`). If it is **older than 6 hours**
when a door opens while enabled and armed:

- **no siren is sounded** (alarm output stays empty);
- a CRITICAL event goes to `cabin/event/critical`, which the hub stores and
  pushes through ntfy (the hub needs `CABIN_ALERT_NTFY_TOPIC` set);
- the audit decision is `stale_push_only`;
- pushes are rate limited to one per 10 minutes, and the siren cooldown is not
  started.

Staleness is checked before the presence value, so a stale `home` is not
trusted either. If no heartbeat has **ever** been seen, the rule stays
inactive and the flow behaves exactly as before, so this can deploy before the
Home Assistant side (W-23) without changing anything. Once heartbeats flow, a
publisher that stops will age out and trigger the rule.

**Changing the limit:** set the environment variable `PRESENCE_STALE_HOURS` on
the Node-RED container (a positive number; anything else falls back to 6).
**Trade-off to know about:** if the phone is off Tailscale or its battery is
dead for more than the limit, an armed door opening while you are away sends a
push instead of sounding the siren.

## Tests

`nodered/test/security-gate.test.js` runs the function-node code that is in
`flows.json` (not a copy) with a fixed clock. From `nodered/`:

```
node --test test/security-gate.test.js
```

It covers fresh, stale, never-seen, disabled, disarmed, home, unknown,
cooldowns, the limit override, the heartbeat node, and the wiring (the siren
path is reachable only from the gate; no wildcard subscription can swallow the
heartbeat).

## Applying a change to the live flow (needs Nate's OK)

Nothing in git deploys itself to Node-RED. To apply `nodered/flows.json`:

1. **Back up** the live flow: copy `/storage/services/nodered/flows.json`
   (on the M920q) somewhere safe. Rollback is putting that file back and
   restarting Node-RED, or re-importing it.
2. In the Node-RED editor, on the **Cabin Intrusion Alarm** tab, import
   `nodered/flows.json` choosing *replace this flow*, then **Deploy**.
   `flows.json` here holds only that tab and its broker node; the other live
   tabs (water shutoff, camera alerts) are not in git and must not be touched.
3. Confirm the new nodes show a status ("phone seen N h ago" once a heartbeat
   arrives).

### Live test, safely (siren output disabled first)

1. **Disarm**, then in the editor **disable the `live-siren-on` node** so that
   even a bug cannot sound anything.
2. Publish an old heartbeat: `mosquitto_pub -r -t cabin/presence/nate/last_seen -m 2026-10-03T00:00:00Z`.
3. Arm, make sure presence reads `not_home`, open a door contact, and wait
   for the 30-second grace. Expect: audit decision `stale_push_only`, a push
   on the phone, and no `alarm_candidate`.
4. Publish a fresh heartbeat (the current UTC time) and open the door again.
   A stale event does not start the siren cooldown, so no wait is needed.
   Expect: decision `alarm_candidate` in the audit (the siren output is still
   disabled, so nothing sounds).
5. **Re-enable `live-siren-on`**, then disarm. Delete the test heartbeat only
   if the Home Assistant publisher (W-23) is not live yet:
   `mosquitto_pub -r -n -t cabin/presence/nate/last_seen`.

The Home Assistant half (zone simulation in Developer Tools) is described with
W-23's change.

## Files

- `homeassistant/cabin_security.yaml`: Home Assistant helper plus retained
  MQTT publication of arm and presence state.
- `homeassistant/cabin_security_presence.yaml` +
  `homeassistant/check_nate_phone_wifi.sh`: WiFi-based presence detection
  (a LAN ARP check via `command_line`, because no HA-supported integration
  exists for the Starlink Gen3 gateway and HA's `nmap_tracker` is
  config-flow-only in this HA version). It feeds `cabin/presence/nate`.
  Added 2026-08-02.
- `nodered/flows.json`: the **Cabin Intrusion Alarm** tab and its broker
  config node, as exported from the live Node-RED. Core nodes only.
- `nodered/test/security-gate.test.js`: tests for the gate and heartbeat.

## Reconciliation history

On 2026-08-02 the live flow had been edited directly in the Node-RED editor
and drifted from git (siren outputs enabled, a `cabin/security/enabled`
control, a 2-minute auto-shutoff, and later enable/arm/disarm/silence
controls). `nodered/flows.json` was re-synced to the live flow on 2026-10-04
(11 nodes existed only live and 8 differed). **If the live flow is edited
again, export it back into `flows.json` in the same sitting.**

Manual silence topic: `cabin/security/silence`. Any message on it builds OFF
commands for all three alarms. The live OFF output is enabled.
