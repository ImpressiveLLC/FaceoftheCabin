# Integration Strategy

## Use Home Assistant as a device compatibility bridge

For locks, Google-compatible devices, thermostats, and smoke alarms, the practical path is:

1. Device vendor app / local protocol
2. Home Assistant integration
3. Home Assistant REST/WebSocket API
4. Cabin Orchestrator API/UI

This avoids building brittle custom integrations for every vendor.

## Cameras

Use RTSP where possible. Add Frigate later for detection.

## Water pressure

Best path:
- ESP32 or Shelly/analog bridge
- 0.5V–4.5V pressure transducer
- MQTT publish to `cabin/device/water-main-pressure/telemetry`

## Remote access

Use Tailscale instead of exposing ports.
