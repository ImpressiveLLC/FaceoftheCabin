# On the Way preheat (Home Assistant package)

`cabin_on_the_way.yaml` is the source of truth in git for the helper `input_boolean.on_the_way_to_cabin` and the preheat automation behind the dashboard's "On the Way" button (W-28). Before this file, those two objects existed only on the live Home Assistant and in no repository (discrepancy DL-2026-10-02-04). The `ImpressiveLLC/CabinAutomations` repository does not contain them either (checked 2026-11-16: its only automation file is `leak_freeze_automations.yaml`).

**Status: written and tested offline only. Not applied to the live host. Whether the live HA has an equivalent helper and automation today is unverified.**

## What it does

When the helper turns on (the button calls `POST /api/ha/services` with `input_boolean.turn_on`, allowed by `HaServiceController`):

1. If `sensor.temp_mech_room_temperature` has a known value above 38 F and `switch.heater_mech_room` is off, it turns the heater on and publishes a `HEATER_PREHEAT_STARTED` info event to `cabin/event/INFO`.
2. Otherwise (reading at or below 38 F, unknown or unavailable, or heater already on) it does not touch the heater and publishes `HEATER_PREHEAT_SKIPPED` with the reason. At or below 38 F the freeze-protection automation already owns the heater.
3. It then turns the helper off so the next tap triggers again.

It never turns the heater off. That stays manual, as in `docs/ai-assistant/knowledge/heater-control.md`.

Differences from the knowledge entry, to be confirmed against the live automation before applying: the entry mentions a push notification with the current temperature; this package records the same information as an info event on the hub instead, because no push notify service name is recorded anywhere in the repository. If a phone push is wanted, add the notify action with the real service name in a follow-up.

## Apply (host work, needs Nate's go; not part of this PR)

Do this only after reading the live Home Assistant first. Loading the package while a UI-created helper or automation with the same name exists gives duplicate entities and a preheat that fires twice.

1. In Home Assistant, check Settings, Devices and services, Helpers for `On the way to the cabin` (`input_boolean.on_the_way_to_cabin`) and Settings, Automations for the existing preheat automation. Note their configuration (a screenshot or YAML) in the PR or run log.
2. Delete that helper and automation in the UI (or rename them) so only this package defines them.
3. Copy `cabin_on_the_way.yaml` into `/config/packages/` inside the `homeassistant` container (the same path rule as `cabin_security.yaml`), then `docker restart homeassistant`.
4. Verify: tap On the Way with the heater off and the mech room above 38 F; the heater turns on and an info event appears. Tap it with the heater already on; a skipped event appears.

Rollback: remove `/config/packages/cabin_on_the_way.yaml`, restart, and re-create the previous helper and automation from the notes taken in step 1.

## Tests

`test_cabin_on_the_way.py` checks that the package names the same helper as the dashboard button, that the backend allowlist permits that call, that the entity names match the heater knowledge entry, and that the heater can only be started in the guarded branch (known temperature above 38 F and heater off). It runs in the `homeassistant package tests` workflow on pull requests that touch these files. It does not run Home Assistant, so it cannot prove the automation behaves on the host; step 4 above does that.
