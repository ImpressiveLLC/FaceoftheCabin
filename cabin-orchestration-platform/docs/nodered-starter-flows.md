# Node-RED Starter Flows

Import these into Node-RED (http://localhost:1880) via Menu → Import.

## Flow 1: Water Pressure Alert

```
[MQTT In: cabin/device/water-main-pressure/telemetry]
  → [Function: check PSI thresholds]
      if (msg.payload.psi < 30) → [MQTT Out: cabin/event/WARN + email]
      if (msg.payload.psi > 75) → [MQTT Out: cabin/event/WARN + email]
```

## Flow 2: Smoke Alarm Escalation

```
[MQTT In: cabin/device/+/telemetry] (filter type=SMOKE_ALARM)
  → [Switch: alarm == true]
      → [MQTT Out: cabin/event/CRITICAL]
      → [Email: smrekarfamilia@gmail.com]
      → [Delay 5min → repeat]
```

## Flow 3: Freeze Risk

```
[MQTT In: cabin/device/+/telemetry] (filter type=THERMOSTAT or TEMP_SENSOR)
  → [Switch: temp_f < 38]
      → [MQTT Out: cabin/event/CRITICAL "FREEZE RISK"]
      → [Email alert]
```

## Flow 4: Kafka Bridge (optional)

```
[Kafka Consumer: cabin.events.raw]
  → [Switch on severity]
      CRITICAL → [email + SMS]
      WARN     → [email]
      INFO     → [log only]
```

## Connecting Kafka in Node-RED

Install: `npm install node-red-contrib-kafka-manager`
Broker: localhost:9092
Group ID: nodered-cabin

## Connecting Gmail

Install: `npm install node-red-node-email`
Config: Gmail SMTP with App Password (smrekarfamilia@gmail.com)
Note: enable 2FA + App Password at myaccount.google.com/security
