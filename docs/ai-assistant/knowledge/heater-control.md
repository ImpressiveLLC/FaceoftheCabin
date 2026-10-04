# Heater Control — Knowledge Entries

> Added 2026-10-02 with W-27/W-28 (dashboard heater controls). **Not yet
> supplied to Ask:** `ReviewedDocumentSource` only reads
> `docs/ai-assistant/user-guide/**` (plus `contributing.md`) and only the
> sections mapped in `context-fixtures-r2.json`, so this file needs a
> reviewed-map entry (and an eval run) before the helpdesk can answer from
> it. The Home Assistant automations referenced below (freeze protection,
> preheat on `input_boolean.on_the_way_to_cabin`) live in HA /
> `ImpressiveLLC/CabinAutomations`, not this repo.

## Q: How do I manually turn on the cabin heater?
A: Two ways. (1) Dashboard: go to cabin.unicornpingpong.com → Device Manager → See → find heater_mech_room → tap its On/Off power button. You must be signed in; the button sends the command through Home Assistant. (2) HA directly: http://100.77.44.113:8123 → Developer Tools → States → search heater_mech_room → toggle. The heater is a THIRDREALITY smart plug (switch.heater_mech_room) controlling a plug-in electric heater in the mechanical room.

## Q: Is there an automatic freeze protection for the cabin heater?
A: Yes. There is a Home Assistant automation that monitors sensor.temp_mech_room_temperature and turns on switch.heater_mech_room if the temperature drops below 38°F. To confirm it is armed, go to http://100.77.44.113:8123/config/automation/dashboard and verify the freeze-protection automation is enabled (toggle is On).

## Q: How do I turn on the cabin heater before I arrive so it is warm?
A: Use the "On the Way" button on the cabin dashboard (cabin.unicornpingpong.com, Device Manager header; sign in first). It turns on input_boolean.on_the_way_to_cabin, which triggers the preheat automation in Home Assistant: it turns on the mechanical room heater if the indoor temperature is above 38°F (below that, freeze protection already has it on) and sends a push notification with the current temp. The dashboard also shows the mech room temperature when you tap the button. The heater stays on until you manually turn it off.

## Q: Why can't I toggle the heater from the dashboard?
A: Check these in order. (1) You're not signed in — the power button and "On the Way" both require sign-in; the button's tooltip says "Sign in to control devices". (2) The cabin backend is unhealthy — check its /actuator/health. DOWN has had two causes so far: a Postgres password mismatch after a rotation, or a blank HA_TOKEN (see docs/MAINTENANCE.md → Secrets → "2026-10-01 incident"). (3) Home Assistant rejected the command — the tooltip says "Home Assistant didn't accept the command"; check HA is up and the entity exists. (4) The device has no power button — only cabin switch-type devices that report On/Off get one, and valves and locks never do. Home Assistant at http://100.77.44.113:8123 is the fallback for all of these.
