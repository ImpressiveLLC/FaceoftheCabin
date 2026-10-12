# Notification path (proposal, 2026-10-12)

How the M920q tells you something is wrong when no app of ours is open. **Status: proposed.** Nate chose the channels and shaped the rule on 2026-10-12; Cowork scores the items (W-51 to W-54) and Nate confirms the open questions at the end.

## What exists today (verified on the M920q, 2026-10-12, read-only)

| Path | State |
|---|---|
| Node-RED "Camera Overnight Alerts" (ntfy) | **Works as built, but could not fire.** Needs a Frigate *detection* event (Reolink detection was off 2026-09-30 to 2026-10-10), `cabin/security/armed_away` ON (it is OFF) and `cabin/presence/nate` not_home. Uses its own ntfy topic |
| Uptime Kuma, channel "ntfy - cabin alerts" | Exists, per `MAINTENANCE.md` |
| Backend `NtfyAlertPublisher` | **Dead: `CABIN_ALERT_NTFY_TOPIC` is blank**, so no CRITICAL event (leak, smoke, alarm, the UI's "Notify me" rules) reaches a phone |
| Home Assistant `notify` services | Only `notify.mobile_app_nates_s23` (the Companion app's push), plus the built-in `persistent_notification`, `notify.notify` and `notify.send_message`. No ntfy, Google, Telegram or email integration |
| Google | The `cast` and `cloud` (TTS) integrations are loaded, but **no Cast/Google Home speaker entity exists**. Nothing calls Google today |
| In-app | No notification feed. Rules & Alerts has a 24-hour Automation alerts card; Config's "Notification Preferences" is text |

Note: the Companion app's *push* does not need Tailscale. Home Assistant sends it through Google's push service, so the phone only needs internet. Tailscale matters only for the phone reporting its *location* back.

## Decisions (Nate, 2026-10-12)

1. **Channels:** ntfy push, **and** Google Home speaker announcements.
2. **"Nate is away from the cabin" cannot be decided by his phone leaving the cabin Wi-Fi alone.** He sometimes shares the phone's hotspot for meetings when the Starlink Wi-Fi is inconsistent, and that must not look like an absence. His alternative: alert when presence is indicated at the cabin and Nate has not been on the Wi-Fi for **2 hours**, and he is not at Home.
3. **Nate at Home** while presence is indicated at the cabin is an alert, immediately.

## Proposed rule

An **occupancy indication** at the cabin is any of: the entry motion sensor, a door contact opening, a Frigate person detection, and later the mesh signal (W-54). Require two indications within 10 minutes, or one from a door contact or a person detection, so a single spurious motion event does not alert.

An alert fires on an occupancy indication when **any** of these is true:

| Case | Condition | Delay |
|---|---|---|
| A | The system is armed away | none |
| B | Nate is at Home (`home/presence/nate` = home, fresh) | none |
| C | None of Nate's signals has been seen for 2 hours: his phone on the cabin Wi-Fi, a fresh GPS fix inside the Cabin zone, or any other signal listed under "Open questions" | 2 hours of silence, then immediately on the next indication |

A hotspot session shorter than 2 hours is covered, because C needs *every* signal silent for 2 hours, not just the Wi-Fi. **Known gap:** a longer one, with no other signal reporting (phone GPS needs the Companion app to reach Home Assistant), will alert on the next movement in the cabin. That is why open question 2 matters, and until it is answered an acknowledge or snooze is the way out. Authorized other people (primary-role users, D24) and maintenance visits must not trigger it once W-38 enforces roles; until then, arming and the rule above are the only filter, so expect to snooze.

**On firing:** ntfy at the highest priority with the camera snapshot and which indication fired, repeated every 10 minutes until acknowledged or the indication clears. If a Google speaker exists at the cabin, also announce a spoken warning there (a deterrent for whoever is present; it does not reach Nate). Acknowledge or snooze from the ntfy action buttons.

**A check error never reads as an intrusion or as "Nate is away"** (the same rule as W-36 and W-37).

## Where it lives

- **Policy:** Node-RED, extending "Camera Overnight Alerts". It already has the ntfy node and subscribes to `cabin/security/armed_away` and `cabin/presence/nate`; it needs `home/presence/nate`, the Zigbee occupancy topics and a persisted "last seen" per Nate signal. Per `CLAUDE.md` the platform does not duplicate automation logic.
- **Delivery:** ntfy for the phone; Home Assistant's `cast`/`tts` for a speaker announcement.
- **The platform (this repo):** shows what fired (an in-app feed, W-51), reports whether each channel is actually configured, and sets the backend ntfy topic so leak, smoke and alarm events reach the phone too.

## Build slices

| Slice | What | Blocked by |
|---|---|---|
| S1 | Set the backend ntfy topic (reuse the Node-RED subscription, so no new one on the phone): vault `vault_cabin_alert_ntfy_topic`, `.env`, recreate `cabin-backend`, then confirm with a test event. Fixes leak and smoke pushes at once | Nate's go on copying the topic |
| S2 | "Notify me" says whether anything will be delivered; Config shows the real channel map | S1 |
| S3 | The occupancy-alert flow in Node-RED with rule A to C | Nate's answers below; presence trustworthy (W-23, W-37) for C |
| S4 | In-app notification feed | S1 |
| S5 | Google Home announcement | Speakers added to Home Assistant; a Google sign-in from Nate |
| S6 | Mesh occupancy signal as one more indication (W-54) | An evaluation of the existing Zigbee LQI prototype |

## Open questions for Nate

1. Are there Google Home or Nest speakers at the cabin (and at Home)? Home Assistant sees none; they may be on the Starlink 2.4 GHz subnet (`192.168.1.0/24`), which the M920q's Wi-Fi is not on.
2. Beyond his phone's Wi-Fi and GPS, what else tells us Nate is at the cabin: a laptop or tablet that joins the cabin Wi-Fi, his own Bluetooth, an "I'm here" tap?
3. Reuse the Node-RED ntfy topic for the backend (recommended: nothing new to subscribe to), or make a new random one?
4. Should arming away be required for case C, or is "2 hours of silence" enough on its own?
