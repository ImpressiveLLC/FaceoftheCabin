# On the Way preheat (Home Assistant package)

`cabin_on_the_way.yaml` is the source of truth in git for the helper `input_boolean.on_the_way_to_cabin` and the preheat automation behind the dashboard's "On the Way" button (W-28). Before this file, those two objects existed only on the live Home Assistant and in no repository (discrepancy DL-2026-10-02-04). The `ImpressiveLLC/CabinAutomations` repository does not contain them either (checked 2026-11-16: its only automation file is `leak_freeze_automations.yaml`).

**Status: written and tested offline only. Not applied to the live host. Whether the live HA has an equivalent helper and automation today is unverified.**

## What it does

When the helper turns on (the button calls `POST /api/ha/services` with `input_boolean.turn_on`, allowed by `HaServiceController`):

1. It turns the helper off straight away, before anything that can fail, so a failure later in the run can never leave the helper on (a helper stuck on would make the next tap produce no off-to-on change, and nothing would run).
2. If `sensor.temp_mech_room_temperature` has a known value above 38 F and `switch.heater_mech_room` is off, it calls `switch.turn_on` on the heater, waits up to 15 s for the heater to report on, and then publishes `HEATER_PREHEAT_STARTED` to `cabin/event/INFO` only if the heater reports on. If the call failed or the heater never reports on, it publishes `HEATER_PREHEAT_FAILED` instead and never claims the heater started. A failed heater call or MQTT publish does not stop the run.
3. Otherwise (reading at or below 38 F, unknown or unavailable, or heater already on) it does not touch the heater and publishes `HEATER_PREHEAT_SKIPPED` with the reason. At or below 38 F the freeze-protection automation already owns the heater.

It never turns the heater off. That stays manual, as in `docs/ai-assistant/knowledge/heater-control.md`.

Differences from the knowledge entry, to be confirmed against the live automation before applying: the entry mentions a push notification with the current temperature; this package records the same information as an info event on the hub instead, because no push notify service name is recorded anywhere in the repository. If a phone push is wanted, add the notify action with the real service name in a follow-up.

## Apply (host work, needs Nate's go; not part of this PR)

Do this only after reading the live Home Assistant first. Loading the package while a UI-created helper or automation with the same name exists gives duplicate entities and a preheat that fires twice.

1. In Home Assistant, check Settings, Devices and services, Helpers for `On the way to the cabin` (`input_boolean.on_the_way_to_cabin`) and Settings, Automations for every automation that triggers on that helper (open each automation's trigger list; the old one may have any name). Save their configuration as YAML (Edit in YAML) in the run log or PR: this is the rollback evidence.
2. **Disable** each old automation that triggers on `input_boolean.on_the_way_to_cabin` (or delete it after saving its YAML). Renaming is not enough: a renamed automation keeps its trigger and would still fire on the same helper, so the heater call would run twice. Delete or disable the old UI-created helper too, so only the package defines it.
3. Copy `cabin_on_the_way.yaml` into `/config/packages/` inside the `homeassistant` container (the same path rule as `cabin_security.yaml`), then `docker restart homeassistant`.
4. Verify only the intended automation responds: in Settings, Automations, confirm exactly one enabled automation lists `input_boolean.on_the_way_to_cabin` as a trigger (`Cabin - On the Way Preheat`); then tap On the Way with the heater off and the mech room above 38 F. The heater turns on, one `HEATER_PREHEAT_STARTED` event appears (not two), and the helper is off again. Tap with the heater already on: one `HEATER_PREHEAT_SKIPPED` event appears. Each tap must leave the helper off.

Rollback: remove `/config/packages/cabin_on_the_way.yaml`, restart, and restore the previous helper and automation from the YAML saved in step 1 (re-enable them if they were only disabled). Keep that saved YAML until the package has run correctly once.

## Tests

`test_cabin_on_the_way.py` checks that the package names the same helper as the dashboard button, that the backend allowlist permits that call, that the entity names match the heater knowledge entry, and that the heater can only be started in the guarded branch (known temperature above 38 F and heater off), that the helper reset is the first action, that a failed heater call or publish does not abort the run, and that `HEATER_PREHEAT_STARTED` is published only under a condition that the heater reports on (a failed start is recorded as `HEATER_PREHEAT_FAILED`). It runs in the `homeassistant package tests` workflow on pull requests that touch these files. It does not run Home Assistant, so it cannot prove the automation behaves on the host or that Home Assistant honours `continue_on_error` as documented (<https://www.home-assistant.io/docs/automation/building-blocks/#continuing-when-a-step-fails>); step 4 above does that.
