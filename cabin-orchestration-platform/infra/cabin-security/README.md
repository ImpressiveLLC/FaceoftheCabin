# Cabin door-triggered water-alarm flow

This package adds a fail-safe Node-RED intrusion flow that uses the two
Zigbee door contacts as entry signals and the three Third Reality leak
alarms as audible sirens.

An alarm candidate requires both:

1. `input_boolean.cabin_security_armed_away` is on.
2. `person.natecabin` reports exactly `not_home` after a 30-second grace
   period.

`unknown`, `unavailable`, and missing presence all fail safe without an
alarm. The first door observation after Node-RED starts only initializes
state, so startup cannot create an alarm. Only a closed-to-open transition
is actionable. A ten-minute cooldown suppresses repeat triggers.

## Files

- `homeassistant/cabin_security.yaml`: Home Assistant helper plus retained
  MQTT publication of arm and presence state.
- `homeassistant/cabin_security_presence.yaml` +
  `homeassistant/check_nate_phone_wifi.sh`: WiFi-based presence detection
  for `person.natecabin`, feeding `cabin/presence/nate`. Added 2026-08-02.
  See the file's own header comment for why this is a direct LAN ARP
  check via `command_line` rather than a router integration (no
  HA-supported integration exists for the Starlink Gen3 gateway, and
  HA's own `nmap_tracker` is config-flow-only in this HA version, not
  YAML/package-manageable).
- `nodered/flows.json`: Node-RED flow using only core nodes.

## Known live drift — not yet reconciled

As of 2026-08-02, the **live** Node-RED flow on the M920q (edited directly
in the Node-RED editor) has diverged from what's checked in here:

- The committed `cabin_security.yaml` publishes arm state to
  `cabin/security/armed_away`; the live flow was repointed to listen on a
  different topic, `cabin/security/node_red_armed`, instead. The
  `input_boolean.cabin_security_armed_away` helper's HA-side toggle no
  longer controls anything live as a result.
- A new `cabin/security/enabled` gate exists live (both this and
  `node_red_armed` are currently retained `ON`, set via a one-off manual
  MQTT publish — no HA automation publishes either topic).
- The live siren output nodes are no longer node-disabled (the README's
  original "dry-run only" design), and a 2-minute auto-shutoff exists on
  the live flow that isn't in this file.

User confirmed (2026-08-02) this was intentional — enabled directly via
Node-RED. Not reconciled back into git yet; flagged here so the next
person reading this file doesn't trust the "dry-run only" framing above
without checking the live state first.

## Safety staging

The two MQTT output nodes named `LIVE SIRENS ...` are disabled in the
checked-in flow. Dry-run decisions publish to:

`cabin/security/intrusion/event`

Do not enable the live outputs until:

- a mobile-app device tracker is associated with `person.natecabin`;
- Home Assistant publishes `home` and `not_home` correctly;
- disarmed, home, unknown-presence, cooldown, and armed-away cases pass;
- a deliberate audible test confirms all three alarms can be stopped.

Manual silence topic:

`cabin/security/silence`

Any message on this topic builds OFF commands for all three alarms. The
live OFF output remains disabled during dry-run staging.
