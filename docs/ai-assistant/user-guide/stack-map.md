# Stack map: hosts, containers and connections

Administrator reference. Source baseline: `e8b32bb2c5bcd4f2281a213c33456e89b955c873` (2026-11-18). These are repository declarations, not a live inventory or permission to start, expose or reconfigure services. Runtime state, firewall enforcement and external tunnel rules are NOT_VERIFIED. No instance addresses or credential values are included.

## Stack ownership and networks

**The stack map has two Compose layers and a separate device stack.** The application deployment combines [base Compose](../../../cabin-orchestration-platform/infra/docker-compose.yml) with the [M920q overlay](../../../cabin-orchestration-platform/infra/docker-compose.m920q.yml). The [production device stack](../../../cabin-orchestration-platform/infra/production-stack/docker-compose.yml) declares Home Assistant, Mosquitto, MediaMTX, Frigate, blinkbridge, Node-RED, Zigbee2MQTT, Homepage and Uptime Kuma.

The overlay puts base `mqtt`, `homeassistant`, `nodered`, `frigate`, `watchdog` and `grafana` behind the `disabled` profile. They remain declarations, not a second set to start alongside the device stack. A separately defined `cabin-grafana` replaces base Grafana. Starting base Compose alone is not equivalent to the cabin deployment.

`cabin-backend` joins the application project network and external `cabin_default`: the first reaches Postgres, Kafka and application services; the second reaches device-stack services. The overlay requires that external network to exist. The production file does not explicitly name its default network, so the project name and installed network must be checked before claiming the two stacks are connected.

The [backend workflow](../../../.github/workflows/deploy-cabin-backend.yml) uses base plus overlay; the [frontend workflow](../../../.github/workflows/deploy-family-hub.yml) rebuilds and recreates its selected services with the same pair. A deployment workflow does not start every declared service. For a new host, use [the installation ownership checks](independent-installation.md#establish-the-actual-installation-plan), not an unreviewed whole-stack start.

## Application ports and internal listeners

**A host port and a container port are different endpoints.** In this table `4080 -> 80` means host port 4080 forwards to container port 80. An unpublished listener is not a host-port mapping. These source declarations do not prove reachability or access restrictions.

| Service | Declared host -> container port | Role |
|---|---|---|
| `cabin-ui` | 4080 -> 80 | Main static frontend and API proxy |
| `family-hub` | 4081 -> 80 | Family frontend |
| `cabin-backend` | 8090 -> 8090 | API and orchestration |
| `cabin-discovery` | 8091 -> 8000 | Assisted discovery lookup |
| `cabin-grafana` | 3002 -> 3000 | Monitoring UI |
| `postgres` | 5432 -> 5432 | TimescaleDB/Postgres |
| `kafka` | 9092 -> 9092 | Event broker; controller 9093 is internal, unpublished |
| `open-webui` | 8081 -> 8080 | Separate chat UI |
| `vaultwarden` | 8222 -> 80 | Credential vault service |
| `ollama` | None; internal 11434 | Model runtime |
| `prometheus` | None; internal 9090 | Metrics collection |
| `telemetry-backup-agent` | None | Independent MQTT log shipping |

Source: [base Compose](../../../cabin-orchestration-platform/infra/docker-compose.yml), [overlay](../../../cabin-orchestration-platform/infra/docker-compose.m920q.yml) and [Prometheus datasource](../../../cabin-orchestration-platform/infra/grafana/provisioning/datasources/prometheus.yml). Grafana's host port is 3002 here; base Grafana's 3000 mapping is profile-disabled. Comments calling an interface Tailscale-only do not establish firewall enforcement: these mappings are not explicitly bound to a tailnet interface. Do not open a firewall or publish a listener from this table.

## Device stack ports

**Support ports belong to the separate device stack.** Read its declarations rather than the profile-disabled copies in base Compose.

| Service | Declared host -> container port | Role |
|---|---|---|
| `mosquitto` | 1883 -> 1883; 9001 -> 9001 | MQTT broker and WebSocket listener mapping |
| `mediamtx` | 8554 -> 8554; 8888 -> 8888; 8889 -> 8889; 9997 -> 9997 | Media relay listeners |
| `frigate` | 5000 -> 5000; 8555 -> 8555 TCP and UDP | NVR and media connectivity |
| `nodered` | 1880 -> 1880 | Flow automation editor/runtime |
| `zigbee2mqtt` | 8080 -> 8080 | Zigbee bridge frontend |
| `homepage` | 3000 -> 3000 | Support dashboard |
| `uptime-kuma` | 3001 -> 3001 | Uptime monitoring |
| `blinkbridge` | None; source HTTP default 8811 | Blink video bridge |
| `homeassistant` | Host networking; backend targets 8123 | Device integrations and automations |

Host networking means Home Assistant does not use a Compose host-to-container port mapping. The backend reaches its configured host endpoint, not a same-network container-name assumption. Base Frigate's 1984 mapping is not the device-stack declaration: that base service is disabled by the overlay.

Source: [production Compose](../../../cabin-orchestration-platform/infra/production-stack/docker-compose.yml), [overlay](../../../cabin-orchestration-platform/infra/docker-compose.m920q.yml), [Blink HTTP default](../../../cabin-orchestration-platform/infra/blinkbridge/blinkbridge/config.py). Bind-mounted configurations and the local Blink build context are additional dependencies; a port declaration alone proves neither a listener nor a working camera. See [configuration bindings](configuration.md#reference-home-assistant-mqtt-and-kafka-addresses) for changes that an environment-file edit cannot override.

## Browser requests and API proxy

**The browser route is not a direct connection to every container.** Main UI nginx serves static files on container port 80. Its `/api/` and `/actuator/` paths proxy to `cabin-backend:8090`. `/api/events/live` uses that same backend with buffering and caching disabled and a longer read timeout, so server-sent events can stream to the browser.

Family Hub's nginx serves static content and has no matching API proxy. Its browser API endpoint is build configuration; the backend, not Family Hub nginx, owns the API. Browser configuration and container environment are distinct: changing a running container's environment does not rebuild its JavaScript.

An API proxy does not grant administrator access: backend authentication and authorization still apply. A successful static page load does not prove the API or its upstream integrations work. Cloudflared and token-managed tunnel routes are outside these Compose/Ansible declarations; this map does not infer their current public routing or access rules.

Source: [main UI nginx](../../../cabin-orchestration-platform/ui/nginx.conf), [Family Hub nginx](../../../family-hub/nginx.conf), [overlay frontend builds](../../../cabin-orchestration-platform/infra/docker-compose.m920q.yml), [external tunnel ownership](../../../ansible/group_vars/cabin/vars.yml). Existing [configuration guidance](configuration.md#trace-a-setting-to-its-consumer) explains build-time settings.

## Telemetry and camera connections

**The telemetry path carries observations through the backend, not directly from MQTT into the browser.** `MqttBridgeService` subscribes to named device, camera, presence and other topic families. Its event-producing paths call `EventPublisher`, which publishes `cabin.events.raw` to Kafka. `EventConsumer` reads that topic, saves events through `CabinEventService` in Postgres, then fans them out to the browser event stream and other consumers. Not every MQTT message is a persisted event; handlers have different purposes.

A separate video path has blinkbridge feeding MediaMTX, with Frigate consuming the relay stream and publishing camera MQTT messages. The backend also uses configured Frigate and Blink HTTP endpoints. Camera frames, motion events, MQTT liveness and stored event history are different evidence; one working path does not prove all the others.

For metrics, Prometheus scrapes the declared Frigate metrics endpoint; Grafana reads Prometheus and Postgres through its provisioned datasources. Neither a dashboard load nor a connected broker proves recent telemetry. Check the named source, timestamp and downstream consumer when diagnosing a gap.

Source: [MQTT bridge](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/mqtt/MqttBridgeService.java), [publisher](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/kafka/EventPublisher.java), [consumer](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/kafka/EventConsumer.java), [production Compose](../../../cabin-orchestration-platform/infra/production-stack/docker-compose.yml), [Frigate configuration](../../../cabin-orchestration-platform/infra/production-stack/frigate/config.yml), [Prometheus](../../../cabin-orchestration-platform/infra/prometheus.yml), [Grafana datasources](../../../cabin-orchestration-platform/infra/grafana/provisioning/datasources).

## Model runtime connections

**The model connection is internal to the application network.** The overlay declares Ollama on the project `default` network with no published host port; backend model clients and Open WebUI use the configured Ollama endpoint, whose source default is `ollama:11434`. Open WebUI is a separate chat frontend, mapped from host 8081 to container 8080; it is not the main UI's API proxy.

One overlay comment calls the Ollama network `cabin_default`, but the actual service declaration joins `default`. The declaration is the source for this map; the installed network is not verified here. Starting a model container or passing its `ollama list` health check does not prove a required model tag/digest is installed, that an answer is correct, or that training occurred.

Source: [overlay Ollama/Open WebUI services](../../../cabin-orchestration-platform/infra/docker-compose.m920q.yml), [Ask client](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/helpdesk/OllamaHttpClient.java), [intent client](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/assistant/OllamaChatClient.java). The existing [configuration disagreement](configuration.md#explicit-source-disagreement) records the Open WebUI authentication claim separately; this map does not change it. Model execution and training are not part of this source review.

## Home collector and cabin host

**The Home collector is not a second cabin backend.** Source scripts run Zigbee2MQTT supervision, local network scanning and a Blink notification relay under Termux. The local scanner observes Home's LAN because mDNS multicast does not cross the cabin-to-Home remote network hop; requests and results travel through MQTT. The relay also publishes to the cabin broker.

The overlay points both location UI API bases to the same backend. `HOME_HA_DEPLOYED` falls back to legacy `HOME_HUB_DEPLOYED`, then defaults to false unless overridden. The flag indicates whether a separate Home Assistant at Home is expected; it does not deploy or disable HA, and it does not disable the Home collector. A collector process, local coordinator connection, broker connection and backend observation are separate checkpoints. None is verified live by this guide.

Source: [Zigbee supervisor](../../../cabin-orchestration-platform/home-collector/z2m-supervise.sh), [Home scanner](../../../cabin-orchestration-platform/home-collector/network-scan-agent/agent.js), [Blink relay](../../../cabin-orchestration-platform/home-collector/blink-relay/relay.js), [overlay](../../../cabin-orchestration-platform/infra/docker-compose.m920q.yml), [Home health flag](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/health/HaTokenHomeHealthIndicator.java). This map does not instruct an operator to start another backend, expose a broker, or change the collector.
