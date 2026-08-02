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
- `nodered/flows.json`: Node-RED flow using only core nodes.

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
