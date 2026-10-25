# Blink motion relay: phone setup and pulse alert

Why: Blink own motion API never fires on this account. The Blink app push notification is the only motion signal, and it
used to depend on Nate S23 (travels, sleeps, loses Tailscale). The always-on Home Termux phone now relays it, and the
backend alerts when the relay goes quiet.

Chain: Blink app push on relay phone -> `termux-notification-list` -> `blink-relay` (Node) -> MQTT `cabin/blink/motion`
-> cabin-backend `MqttBridgeService` -> `BlinkLiveviewService` -> blinkbridge `/liveview/.../start`.
The HA Companion path still works; both can fire (liveview start is idempotent).

Heartbeat: `cabin/blink/relay/heartbeat` (retained JSON, every 30 s, only while the phone can read its notification tray).
`BlinkRelayPulse` turns an age of 10+ minutes (`cabin.blink.relay.staleAfterMinutes`) into the active alert
"Blink motion relay has gone quiet" (Rules & Alerts, WARN). No alert until the first heartbeat ever arrives.

## One-time phone setup (Home Termux phone, plugged in)

1. Install the **Blink** app, sign in with the same Blink account, allow notifications, and make sure motion
   notifications arrive for AldrichFront and the driveway (Blink app settings).
2. Install **Termux:API** and **Termux:Boot** (same source as Termux). Grant Termux:API **notification access**
   (Android Settings, Notifications, Device and app notifications, Termux:API). Set battery to **Unrestricted** for
   Blink, Termux, Termux:API and Termux:Boot.
3. In Termux: `pkg install termux-api nodejs`, copy `cabin-orchestration-platform/home-collector/blink-relay/` to
   `~/blink-relay`, then `cd ~/blink-relay && npm install && chmod +x blink-relay-supervise.sh`.
4. Boot start: `mkdir -p ~/.termux/boot && printf '#!/data/data/com.termux/files/usr/bin/bash\nsetsid nohup ~/blink-relay/blink-relay-supervise.sh >/dev/null 2>&1 </dev/null &\n' > ~/.termux/boot/blink-relay && chmod +x ~/.termux/boot/blink-relay`, then start it once by hand the same way.
5. Tailscale must be connected on the phone. The default broker is `mqtt://100.77.44.113:1883` (M920q Tailscale IP;
   MagicDNS is unreliable in Termux). Override with `BLINK_RELAY_BROKER`.

## Check

```bash
tail -f ~/blink-relay.log                              # on the phone: "mqtt connected", then "motion -> home_aldrich_front"
docker exec mosquitto mosquitto_sub -v -t 'cabin/blink/#' -W 40   # on the M920q: heartbeat within 30 s
```

Trigger a real motion at AldrichFront; the phone log shows `motion ->`, the bridge goes live
(`curl http://<blinkbridge ip>:8811/liveview/AldrichFront/status`).

## When the alert fires

| Cause | Fix |
|---|---|
| Phone off / rebooted, Termux:Boot missing | power it, open Termux once; check `~/.termux/boot/blink-relay` |
| Tailscale off | reconnect; set Tailscale to always-on VPN |
| Notification access revoked (Android update) | re-grant to Termux:API; the relay stops heartbeating exactly then, by design |
| Relay crashed | `tail ~/blink-relay.log ~/blink-relay-supervisor.log` |

The alert is visible on the Rules & Alerts panel. It is not pushed to a phone until `CABIN_ALERT_NTFY_TOPIC` is set (W-51).
