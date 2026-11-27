# Variable-speed appliance control: characterize, control, maintain

**Status:** first measurement taken 2026-11-23 at Home; implementation (W-78) not built. Everything below that says "measured" was observed on that date. Everything that says "expected" or "not yet verified" is a design statement, not a result.

**Applies to:** the CLOUDLINE S6 first (see [Doc 27](doc27-environmental-nodes.md), [D28 A1](../governance/decisions/ontology-decisions.md)), and any later appliance whose load changes with a setting: inline or exhaust fans, circulation pumps, dehumidifier fan speeds, dimmable or stepped heaters.

## 1. What this pattern is for

Three separate things are easy to confuse. The platform must keep them separate:

| Term | Meaning | Where it comes from |
|---|---|---|
| Commanded setting | What a person or automation asked for (for example "level 6") | The control path (dial, controller, or W-78 ESP32) |
| Reported setting | What the platform believes the appliance is doing | Read-back, or inferred from a measurement, as in this document |
| Actual effect | Airflow, heat or humidity change in the room | A sensor in the room, or nothing at all |

This pattern gives the platform a way to learn the **reported setting** of a manually controlled appliance, and to calibrate a future automated control path, using only a power-monitoring Zigbee plug that is already part of the design. It does not measure airflow. No airflow sensor was ordered, so airflow stays an unverified claim.

## 2. Use cases

1. **Read back the level of a manually set fan.** The supplied S6 controller has no data connection. The load current at a known supply voltage identifies the dial position, so the platform can report "level 6" without a networked controller. Levels 1 and 2 cannot be told apart (see the table in section 4).
2. **Calibrate the W-78 speed path.** When the ESP32-S3 sends PWM duty cycles through the isolator to the control port, the plug's current reading tells you which of the ten levels each duty cycle produced. Sweep the duty cycle, log current, and match it to the table. This is a closed loop that needs no instrument on the control port.
3. **Detect a failing or blocked fan.** At a known level the current should fall in a known band. A clogged filter, failed bearing or blocked duct moves it. This feeds the existing power-draw anomaly work (W-65).
4. **Prove commanded versus reported.** After W-78 exists, a command of "level 6" can be checked against the measured 0.25 A. A mismatch is an alert, not a silent success.
5. **Re-use for any variable load.** Section 8 states the generic recipe.

## 3. Devices used, and what was not needed

| Item | Role | Notes |
|---|---|---|
| CLOUDLINE S6 with its supplied speed controller | Appliance under test | Manual dial, 10 levels |
| SONOFF S61SZBTPB Zigbee smart plug | Power supply for the S6 and the only instrument | Exposes `state`, `power`, `current`, `voltage`, `energy_*`. Current resolution 0.01 A |
| Zigbee2MQTT bridge `home_z2m` on the SLZB-MR5U (Home) | Pairing and readings | Cabin readings use the `zigbee2mqtt` bridge instead |
| Mosquitto on the M920q, plus `ssh` to the M920q | Command and logging path | Any host that reaches the broker works |
| A person at the appliance | Changes the dial, says which level it is on | About 10 minutes in total |

**Not needed: a multimeter.** The plan in W-78 and the CLOUDLINE control decision said to "photograph and meter the shipped S6's connector" first. That was written as though a meter were required to learn how the speed levels behave. It was not. The monitoring plug that is already planned as the S6 power path produces the same characterization with no probing, no exposure to the control port, and no risk of shorting a pin. The earlier statement was made as a direct recommendation when the person had asked for options, and a cheaper option that was already on hand was not considered. See section 9 for the rule that follows from this.

A meter may still be needed for one thing: the W-78 acceptance criterion that isolation is verified before the ESP32 is connected. That is a safety check on the control port, not a way to learn the speed levels, and this document does not remove it. Nate decides whether it stays.

## 4. Measured result (2026-11-23, Home)

Conditions: S6 on its supplied controller, powered through the S61SZBTPB, supply about 120.5 to 121 V, 30 to 50 second holds, readings polled about every 5 seconds, first 12 seconds of each hold discarded, median reported. Duct, filter and mounting state during the test were not recorded. Treat the values as bench values until repeated installed (section 7).

| Level | Current (A) | Share of level 10 |
|---|---|---|
| 1 | 0.03 | 3% |
| 2 | 0.04 | 5% |
| 3 | 0.09 | 10% |
| 4 | 0.13 | 15% |
| 5 | 0.18 | 21% |
| 6 | 0.25 | 29% |
| 7 | 0.34 | 40% |
| 8 | 0.50 | 58% |
| 9 | 0.66 | 77% |
| 10 | 0.86 | 100% |

Reading the table:

- Current climbs steeply with level. Motor power rises faster than airflow, so current is a poor proxy for airflow at the low end and a decent one for "which dial position".
- Levels 1 and 2 differ by one count of the plug's resolution (0.01 A). Do not claim to tell them apart. If the platform must, add a second reading such as energy over a minute.
- The plug's `power` field showed 0 W at 0.11 A on the first read and later sat at 4.38 W, unrefreshed, while current changed. It was not requested by the poll. Do not use `power` for this pattern until it is requested explicitly and checked. Apparent power can be computed from current times voltage.
- L9 was measured twice. The first pass overlapped the L10 change and gave a misleading median of 0.77 A. The clean pass gave 0.66 A (27 readings, 0.65 to 0.66). L1 was measured twice, 0.03 A both times.
- The raw logs are `s6_ladder.log` (one JSON message per line, UTC timestamp first) and `s6_ladder_marks.log` on the M920q home directory, with a copy in the 2026-11-23 session scratchpad. They are not committed.

## 5. How to run it (setup)

Human requirements: someone physically at the appliance who can change its setting on request, and someone (or an agent) at a terminal. The appliance can be left running.

1. **Pair the plug.** Open the bridge's pairing window, then hold the plug's power button (a few seconds, until the LED blinks) so it joins.
   - Home bridge: publish `{"value": true, "time": 254}` to `home_z2m/bridge/request/permit_join` on the M920q broker.
   - **Close the window with `{"time": 0}`.** `{"value": false}` is rejected on this Zigbee2MQTT version with `Invalid payload`.
   - `POST /api/devices/permit-join` broadcasts to every configured bridge (MAINTENANCE.md, multi-location Zigbee). The 2026-11-23 handoff note that said it targets only the cabin bridge was out of date.
2. **Confirm the interview succeeded**, then rename. The Home bridge friendly name used here is `s6_fan_switch_home`. Platform device ids for Home devices derive from it (`z2m-home-<friendlyName>`).
3. **Test on/off** once and confirm `current` and `voltage` change.
4. **Start a logger** that records every message on the device topic with a timestamp, and a poller that sends a `get` for `state`, `current`, `voltage` about every 5 seconds. The plug otherwise reports only when a value changes.
5. **Climb the ladder.** Per level: the person sets the level and says it, the logger notes the start, the person holds 30 seconds (45 for a clean repeat), says "done", the logger notes the end. Log a start and an end mark for each level so one level's transition never lands in the next level's window.
6. **Reduce.** Discard the first 12 seconds of each hold, take the median and the min/max, and record the supply voltage median beside them.
7. **Record** the table, the date, the supply voltage, the appliance and its mounting state here and in the device inventory.
8. **Stop the logger and the poller.** Do not leave a standing 5-second poll running.

Mistakes made during the first run, so the next run avoids them:

- A `mosquitto_sub` writing to a file through `docker exec` buffers its output and writes nothing until the buffer fills. Use `docker exec -t` (a pseudo-terminal) and strip carriage returns.
- Never run `pkill -f` with a pattern that also appears in your own `ssh` command line; it kills your own shell. Select processes by an explicit PID.
- Never "clean up" a stray subscriber inside the broker container by killing process 1. That is the broker itself.
- Mark the start of a level when the person says it has started, and require an explicit "done" before they change level. Marks written after the change lose the first seconds.

## 6. Moving from Home (bench) to the cabin (installed)

The ladder was taken at Home. The S6 and its plug are intended for the cabin mechanical room and box 4 (Doc 27). When the plug moves:

1. Remove it from the Home bridge in Zigbee2MQTT and factory-reset it. Do not leave a stale `z2m-home-s6_fan_switch_home` device in the platform; archive or retire it through the device lifecycle.
2. Pair it to the cabin bridge. Its platform id will follow the cabin convention (`z2m-<friendlyName>` for the cabin, no location segment), so it is a new device and parent links must be set again.
3. Take current at one or two levels (1 and 10 are enough) with the fan ducted. Compare with section 4. Duct resistance changes the load on the motor, so a different result is expected, not a fault. Re-run the full ladder if the two points differ by more than the plug's resolution.
4. Update section 4 with an "installed" table beside the bench one.

The Home registration of this plug in the platform device list has **not** been done as of 2026-11-23. A database read that day found no `z2m-home-s6_fan_switch_home` row and no candidate for the IEEE address `0xa4c1381f38eaffff`. Registration is a signed-in Device Manager action (location Home, type POWER_METER, parent: the S6 when it is registered). Note the cabin dehumidifier switch is a different device with a similar address (`0xa4c1381f3884ffff`); do not confuse them.

## 7. Maintenance and replacement

| Event | What to do |
|---|---|
| Plug fails | Replace with the same model if possible. Pair it, then give it the **same friendly name** so the platform id and automations are unchanged. If the model differs, re-check that `current` and `voltage` are exposed and repeat one level. |
| Fan replaced (same model) | Repeat levels 1, 5 and 10. Update the table if any differs by more than 0.02 A. |
| Fan replaced (different model) | Repeat the full ladder. Do not reuse the table. |
| Duct, filter or mounting changed | Repeat levels 1, 5 and 10. |
| Controller dial replaced | Repeat the full ladder; step positions may differ. |
| Seasonal check | Once a year, read level 10 and compare. A drift of more than 10% is an inspection prompt, not a conclusion. |
| W-78 ESP32 built | Calibrate duty cycle against this table (section 2, use case 2) before any `set_level` is exposed. |
| Supply voltage differs by more than a few volts from the table | Do not compare raw current across sites without noting voltage. |

Keep the table next to the inventory entry for the appliance (`doc27-device-inventory.json`, `circulator`) so a replacement finds it.

## 8. Generic recipe for other variable loads (settings discovery)

1. Put a monitoring plug (or any metered supply) between the mains and the appliance, subject to its rated load and a restart test.
2. Choose the variables to discover and write them down: the setting positions (levels, duty cycles, timer steps), the readable channel (current, power, energy), and the resolution.
3. Write the human requirements before starting: who sits at the appliance, how long, which words to say.
4. Run the ladder as in section 5. For timers, step through the timer values and log when the load drops to zero. For multi-input devices (speed plus mode), vary one input at a time and record the others as constants.
5. Reduce to a table and record resolution and ambiguities as findings. Levels that cannot be told apart are a result, not an error.
6. Store the table with the device, with date, supply voltage and mounting.
7. Define what changes the table (section 7) so someone knows when it expires.

## 9. Process rule recorded from this work

When asked for options, give the options. Do not state a single piece of equipment as required ("you need a meter") unless every cheaper or already-owned alternative was considered and ruled out. State what was ruled out and why. Before saying that something is needed or appropriate, check whether an instrument already in the design can do the job. This applies to hardware choices, test methods and tooling alike.

## 10. Open items

- The control-port signal of the supplied S6 controller is still unknown (steady DC versus PWM, levels, ground reference). W-78 acceptance criteria already require identification before any speed claim.
- The platform has no airflow measurement. No claim about CFM or heating benefit is supported by this data.
- The installed ladder (section 6) has not been taken.
- The `power` channel behaviour on this plug at low load needs an explicit test (request it with `get`, compare with current times voltage).
- Registration of the plug in the Home device list.
