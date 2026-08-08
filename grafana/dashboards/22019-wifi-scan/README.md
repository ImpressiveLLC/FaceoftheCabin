# Candidate: Grafana Dashboard #22019 "Wifi Scan" — evaluated, not adopted

Saved here as a reference/candidate artifact, not wired into the live
Grafana instance (`cabin-orchestration-platform/infra/grafana/provisioning/dashboards/`
is where anything actually provisioned lives — this directory is
deliberately separate). Evaluated 2026-08-08 against the user's proposal
to use WiFi signal-intensity change as a presence/proximity detection
and alerting trigger.

## What this dashboard actually measures

This is grafana.com community dashboard `22019` ("Wifi Scan" by
`lux4rd0`), paired with a companion Python tool ("WiFiScan Collector")
that runs the Linux `iw scan` command on a wireless interface. That
command is a **network site survey**: it enumerates nearby Wi-Fi
**access points/networks** (their ESSID, the AP radio's own MAC/BSSID,
band, channel) and records the signal strength of each AP's *beacon
frame* as received at the scanning device, into an InfluxDB
`wifi_scan` measurement (field: `signal_level`, dBm).

**This is a different signal than what was proposed.** The presence-
detection idea logged in `ROADMAP.md` was pinging signal strength
between *already-connected client devices* (a smart switch, say) and
the network dongle — link-quality RSSI for a *specific known device*.
`iw scan` instead observes *other networks' AP beacons* from one fixed
vantage point. Related RF techniques, not the same data.

## Can it still be used for presence/motion sensing?

Yes, with real caveats — this is a legitimate, if crude, technique
sometimes called passive RF/WiFi sensing via RSSI fluctuation:

- **Requires deliberate physical placement.** The scanner and a
  *known, stationary* reference AP (e.g. a mesh node in another room)
  need to sit on either side of the space you actually want to sense —
  a body moving between them attenuates the signal measurably. Nothing
  about installing this tool does that automatically; it has to be
  engineered per-location.
- **Coarse by default.** The dashboard's default bucketing is 5-minute
  intervals (configurable down to 15s) — fine for a site survey, not
  obviously fine for "someone just walked in."
- **Real false-positive surface.** RSSI drifts from channel contention,
  2.4GHz interference (microwaves, Bluetooth), temperature/humidity,
  even furniture moving — not just people. Using raw signal_level as a
  binary presence trigger without a baseline + anomaly-detection layer
  on top would very plausibly make tonight's other finding (alerts that
  fire for reasons nobody can identify) *worse*, not better — this
  needs to feed something smarter than a threshold before it's trusted
  for alerting.
- **No alerting logic exists in this dashboard.** It's pure
  visualization — no Grafana alert rules defined in the JSON at all.
  Any actual trigger has to be built on top, wherever it's built.

## Infra fit — this is the bigger decision

This platform has no InfluxDB anywhere today. `cabin-backend` uses
Postgres; the one time-series path that exists (Frigate monitoring) goes
through Prometheus + Grafana, already live. Adopting this dashboard
as-published means either:

1. **Stand up InfluxDB** alongside the existing Prometheus stack — a
   second TSDB, running solely for this one feature. Against this
   project's own stated cost/complexity philosophy (see
   `docs/REPLICATION.md`'s framing: self-hosted, free-tier, minimal
   moving parts).
2. **Re-point the same idea at Prometheus instead** — have an
   equivalent collector expose `signal_level` as a Prometheus gauge
   (labels: essid/mac/band/channel) scraped by the already-live
   `cabin-prometheus`, and build the equivalent panel/alerting there.
   Consistent with how Frigate monitoring was already wired up this
   session — no new datastore.
3. **Skip the TSDB/dashboard path for the alerting use case entirely**
   and have a small collector publish directly to MQTT in the same
   `{location}/...` pattern this session already established for
   presence (`cabin/presence/{personId}`) and armed state
   (`cabin/security/armed_away`) — so a derived "occupancy-likely"
   signal flows through the *same* `MqttBridgeService` →
   `AutomationRuleService` severity pipeline as everything else, rather
   than living in a separate Grafana-alerting silo the main UI doesn't
   know about. Given the user's own alert-clarity concern from earlier
   the same session, a disconnected alert path is a real regression
   risk, not just an inconsistency — keep Grafana for trend
   visualization, keep the *trigger* on the platform's one alert path.

**Recommendation, not yet actioned:** treat this dashboard as prior art
for the sensing *technique*, not something to adopt verbatim. If
pursued, prefer option 3 (or 2, if trend visualization independent of
alerting is also wanted) over introducing InfluxDB. Needs the user's
decision before any of this gets built — this file exists to make that
decision informed, not to presuppose it.

See `ROADMAP.md`'s WiFi RSSI presence-detection planning item for the
tracked backlog entry this evaluation feeds.
