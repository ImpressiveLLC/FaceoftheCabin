# MQTT Topic Contract

Use MQTT as the common event bus.

## Topics

```text
cabin/device/{deviceId}/state
cabin/device/{deviceId}/telemetry
cabin/event/{severity}
cabin/automation/{ruleId}/action
```

## Example water pressure payload

```json
{
  "deviceId": "water-main-pressure",
  "psi": 52.4,
  "timestamp": "2026-06-08T12:00:00Z"
}
```

## Example smoke alarm payload

```json
{
  "deviceId": "kidde-smoke-main",
  "alarm": false,
  "battery": "ok",
  "timestamp": "2026-06-08T12:00:00Z"
}
```
