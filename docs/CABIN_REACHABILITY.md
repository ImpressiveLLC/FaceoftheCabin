# Cabin reachability: knowing when the cabin goes dark (proposal, 2026-10-12)

Nate, 2026-10-12: "I need to know when the cabin goes offline because presumably power or internet connectivity is down", which means the alert has to come from something that is **not** at the cabin. Battery backup is a likely future step; for now he is weighing whether the whole app could run in a "limp mode" on the Termux phone or on `ILikeTheLights`. **Status: proposed.** Cowork scores W-56 to W-58; Nate answers the open questions at the end.

## The principle

A dead-man switch has to live outside the thing it watches. Everything the platform uses to notice problems today (Uptime Kuma, Node-RED, the backend's events, the new disk monitor) runs on the M920q, so when the cabin loses power or its Starlink link, **they all stop together and nothing says so.** The first sign would be that something is missing.

## What exists today (verified 2026-10-12, read-only)

| Fact | Evidence |
|---|---|
| **Nothing outside the cabin watches the cabin.** Uptime Kuma has six monitors (Frigate cams, driveway fps, Home Assistant, Homepage, Node-RED, a Tailscale ping) and all run on the M920q | `kuma.db`, container list |
| **The Home collector has been down for more than three weeks.** The Termux phone (`G3223`, an Android device that matches the Xperia collector) last appeared on Tailscale on 2026-09-19 06:35 UTC; the retained `home_z2m/bridge/state` is `offline` and no `home_z2m` traffic arrives. It was the obvious second vantage point, and it is also why Home's Zigbee devices and the W-47 Home-presence idea are stuck | `tailscale status`, `mosquitto_sub` |
| Other devices on the tailnet | `ILikeTheLights` (Windows, online), `Nathan's S23+` (Android, online), `bluefin` (Linux, offline since 2026-09-22; unknown, Nate to say what it is) |
| The only cabin-to-outside paths | Tailscale, and the Cloudflare Tunnel that serves the public Family Hub hostname |
| No push channel works from the cabin | The backend's ntfy topic is blank (W-51 S1); only Node-RED's own flow can push |

## Design: two mechanisms that fail differently

**A. Push heartbeat (cabin to an external service).** The cabin proves it is alive; the outside party alerts on *silence*. It covers every outage (power, router, Starlink, host crash), needs no always-on device of Nate's, and the alert originates outside. Candidate: a hosted heartbeat monitor such as healthchecks.io (open-source, a free tier whose current limits are to be confirmed, alerts to ntfy, email, Telegram and others, with a grace period); equivalents exist. A small host timer on the M920q pings its URL every minute, **two kinds of ping**: *host alive* (no dependencies, so it keeps going when the app is broken) and *platform healthy* (only when `cabin-backend`'s health is UP and the Zigbee bridge is online). Then silence of the first means "the cabin is dark" and silence of only the second means "the platform is sick". The ping URL is a secret and goes in the host `.env` and the vault like the other credentials.

**B. Pull probes from other vantage points (outside to cabin).** `ILikeTheLights`, the S23, a revived Home collector and any kiosk away from the cabin each check `GET /actuator/health` over Tailscale and the public Cloudflare hostname every minute. Alert only when **two independent vantage points** fail for the grace period, and only when the vantage point itself can reach a control URL, so "my own internet is down" never reads as "the cabin is down". Pull adds a diagnosis A cannot give: Tailscale unreachable while Cloudflare answers, or the reverse, points at different faults. Its weakness is that a vantage device can be asleep or off, which is exactly the Home collector's state today.

**C. Cloudflare's own tunnel-health notification (no code).** Cloudflare can email when the tunnel goes down. To be confirmed in Nate's account; if it is free it is a third, independent signal for the cost of a setting.

**Recommendation: A first** (one script, one free account, the strongest coverage), **then B** once the Home collector is back, **C** if the setting exists. Alert out through ntfy, with email as a second channel so one service cannot be the single point of failure. Parameters to start: heartbeat every 1 minute, grace **10 minutes** (Starlink drops are normal, and the whole stack restarted once at 00:00 CDT on 2026-10-09), a "still down" repeat each hour, and a "back" notice with the outage length.

**Power versus internet.** From outside they look identical. The cabin can tell them apart only with extra hardware: a UPS that reports "on battery" (NUT) over a second path such as an LTE modem, so a mains failure sends its own message before the battery runs out. That is the real answer to "power or internet" and the real continuity fix; it is W-58.

## "Limp mode": running the whole app elsewhere

Honest assessment before anyone starts: **the cabin's data is made at the cabin.** Sensors, cameras, the Zigbee radio and Home Assistant are there, so a copy of the app on another machine has nothing live to show when the cabin is dark. What it *could* give is last-known state and a place that raises the alarm.

- **Termux phone:** it cannot realistically run Spring Boot with Postgres and Kafka (memory, ARM, a battery that is also its own single point of failure). A small Node observer in the style of `network-scan-agent` could hold last-known state from MQTT, serve a one-page status view and host the probe logic.
- **`ILikeTheLights`:** Docker Desktop can run the compose stack (there is a local override), but the PC sleeps, travels, and is not always on; it suits a *warm replica for review*, not a watchdog.
- **Verdict:** do not port the full app. Build A and B (they answer "is the cabin dark?"), keep an observer small, and put the money into the UPS and LTE failover that give the cabin real continuity. Revisit limp mode if a use appears that the dead-man alert cannot meet.

## Build slices

| Slice | What | Needs |
|---|---|---|
| S1 | Host heartbeat script and timer on the M920q (two ping kinds), plus the external monitor and its alert route | Nate creates the free account and chooses channels; the ntfy topic (W-51 S1) |
| S2 | Revive the Home collector (`G3223`): power, Termux, Tailscale, Zigbee2MQTT | Nate, at Home |
| S3 | Pull probes with the two-vantage rule (`ILikeTheLights`, S23, Home collector) | S2 for the collector; Nate's choice of probe devices |
| S4 | Cloudflare tunnel-health notification | Nate checks his Cloudflare account |
| S5 | UPS with NUT and an LTE path | Hardware purchase and a decision |
| S6 | A small observer (last-known state and a status page) | S3; only if still wanted |

## Open questions for Nate

1. **External service:** is a free hosted heartbeat monitor acceptable (recommended), or must everything be self-hosted on your own devices?
2. **Vantage points:** which of these are reliably on and reachable: `ILikeTheLights` (does it sleep?), the S23, a revived Home collector? And what is `bluefin`?
3. **Home collector:** do you want it revived first (it has been down since 2026-09-19), or is the heartbeat enough for now?
4. **Grace period:** is 10 minutes right before it pages you, given Starlink's normal drops?
