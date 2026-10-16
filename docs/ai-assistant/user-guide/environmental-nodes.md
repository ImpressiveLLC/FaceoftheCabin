# Environmental nodes and climate equipment

## Ordered equipment and current scope

The Doc 27 environmental project has three planned Cabin nodes: a main-level Sensor
Collection Box for monitoring, a bathroom Sensor Relay Box for exhaust/heat, and a
mechanical-room Sensor Relay Box with active controls. The lower-level monitoring-only
node is deferred. Each active node uses SEN66 and SHT45 sensing. Six SONOFF SNZB-02P
sensors are ordered for Home: mechanical room, laundry, dishwasher/sink area, garage,
basement storage, plus a spare. The Midea Cube MAD50PS1QGR dehumidifier and CLOUDLINE
S6 circulation fan are also ordered; “air pump” refers to the S6, not a separate item.
Ordered does not mean received, assembled, discovered, online or safe to operate.

The canonical [build plan](../../hardware/doc27-environmental-nodes.md) and
[master inventory](../../hardware/doc27-device-inventory.json) distinguish those states.
The assembled nodes share type names; location and purpose distinguish instances.
Device IDs remain stable when display names or rooms change.

## What the devices can and cannot establish

SEN66 provides particles, CO2, VOC/NOx indexes and temperature/humidity; SHT45 adds a
separate ambient temperature/humidity source. These are not CO, smoke, oxygen or mold
alarms. Sensor values require recent observations and source attribution. Air mixing
does not prove lower-level conditions equal main-level conditions, and recirculation
does not establish moisture or CO2 removal.

The S6 is related to heating, cooling, humidity and climate because air movement may
support those purposes. Those relationships do not grant heating, cooling or humidity
setpoint controls. Its supplied speed controller is manual; remote speed requires
additional verified hardware/integration. A commissioned smart outlet may provide
on/off after motor-load and restart checks, not speed or airflow measurement.

Midea local HA control is a candidate integration, not yet verified for the delivered
unit. Cloud-assisted provisioning may still be needed. Actual humidity, modes,
bucket/pump diagnostics and controls must come from discovery, never guesses.
Do not recommend repeatedly cutting compressor power to regulate humidity.

## Setup and maintenance

Follow the [schematics and incomplete construction schedule](../../hardware/doc27-schematics.md)
and [commissioning checklist](../../hardware/doc27-environmental-nodes.md#setup-maintenance-and-acceptance).
Only the low-voltage and functional designs are documented; final mains details need
actual load/terminal evidence. Preserve manual controls and appliance safety limits.
Keep sensor airflow clear and inspect dehumidifier drains/filter and fan cleanliness.
Unavailable values are unknown, not zero; a retained online message is not fresh evidence.

## Ask behavior and execution boundary

Answer inventory/capability/setup questions from the linked documentation, naming
uncertainty. For current readings, require authorized live inventory and timestamps.
No ordered device is an executable target until discovered, commissioned and guarded.
“I'm cold” is POC2 `express_need` with concept `cold`, not a direct heater command.
Classifying the need has no side effect; any derived action requires its own exact
resolved plan and confirmation. New bathroom, fan and dehumidifier controls are outside
W-75's cabin mech-room heater-only exception.

User-authored workflows belong to the existing platform engine; cabin leak/freeze/
siren automations stay in HA. Topic/relationship membership is not authorization.
See [model and shadow decision](../../governance/handovers/HO-2026-10-16-doc27-poc2.md).
This document is in the reviewed-document allowlist, but adding a file alone does not
wire a context category, ingest a KB or train a model. Routing and answer tests remain
an explicit implementation task; no live Ask update is claimed by this PR.
