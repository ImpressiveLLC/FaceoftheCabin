# W-15 — Reconnect `front_door` (Reolink) to Frigate across the Starlink network split

**Status: DRAFT for Nate's review, 2026-09-24. Docs only.** No host has been changed. The M920q was queried read-only (`ip`, `iw`, `nmcli` non-secret fields, Frigate's API, `ping` of one dead address). Nothing here is executed until Nate is present and approves each phase.

Backlog: [W-15](governance/backlog.md). Blocks [#97](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/97) (held) and W-5. Scope: option A only, as requested. Other options are named in "Not in scope" and not evaluated.

## Answer up front

1. **Does the M920q have a usable Wi-Fi interface?** Yes, exactly one, and it is already the box's only uplink. `wlo1` (Intel CNVi, `iwlwifi`) carries the whole LAN, the default route and the Tailscale path. The Ethernet port `eno2` has no carrier.
2. **It can hold only one Wi-Fi client connection at a time.** `iw list` reports `#{ managed } <= 1` in every valid interface combination. The radio cannot sit on the current network *and* the camera's network at once, so a second virtual interface is not possible on this hardware.
3. **Consequence for option A.** Joining the camera's Wi-Fi means leaving the current LAN for as long as the join lasts. With `never-default` (no default route via the joined network) the box also has no internet path during that time. Option A is therefore a **time-boxed test window**, not a durable fix. A durable connection to both networks needs a second radio or a different path, which is Nate's decision after the window.
4. **Step 0 has been run (2026-09-24, read-only): the camera is not reachable from the M920q.** Its current address is `192.168.1.121`, on a different subnet from the M920q's `192.168.2.0/24`. Ping and every camera port time out, and a hop trace shows the gateway answering and nothing beyond it. Step 1 is therefore the candidate, pending Nate's approval.

## What is known, and how

| Fact | Source |
|---|---|
| The camera is online through Starlink on the 2.4 GHz side, visible in the Reolink app from anywhere; the M920q is on a 5 GHz network. | Nate, 2026-09-24. Reported; not independently verified by Code. |
| The camera's address cannot be changed remotely. It needs someone on site with a cable. | Nate, 2026-09-24. |
| `wlo1` is the only uplink: 5 GHz, channel 161, −49 dBm, DHCP `192.168.2.46/24`, default route via `192.168.2.1`; `eno2` is `NO-CARRIER`. | Code, read-only `ip`, `iw`. |
| One managed interface at a time on this radio. | Code, `iw list` interface combinations. |
| A saved Wi-Fi profile for a 2.4 GHz network exists, has never been activated (`connection.timestamp` 0, `autoconnect no`), has its credential stored with the profile (`psk-flags 0`), and the network is in range, strong, channel 11. | Code, `nmcli` non-secret fields and cached scan (no new scan). The secret was never read. |
| Frigate `front_door`: 0.0 fps, 1,260 "No route to host" lines in 30 minutes, ARP `INCOMPLETE` for `192.168.2.200`. | Code, Frigate `/api/stats`, container log, `ip neigh`. |
| The camera's current address is `192.168.1.121` (MAC held by Nate, not recorded here). Before this, it was recorded nowhere in this repo or on the host; Frigate still holds the old `192.168.2.200`. | Nate, 2026-09-24; Code, repo search and Frigate config. |
| Step 0 result: from the M920q, `192.168.1.121` routes via gateway `192.168.2.1`; ping 3/3 lost; TCP 554, 80, 443, 8000, 9000 all time out (not refused); `tracepath` shows hop 1 (`192.168.2.1`) answering and hops 2–6 silent. | Code, read-only probes, 2026-09-24. |
| `ffprobe` is not on the Frigate container's PATH. The builds are `/usr/lib/ffmpeg/5.0/bin/ffprobe` and `/usr/lib/ffmpeg/7.0/bin/ffprobe`; the 7.0 build runs, and `FRIGATE_RTSP_PASSWORD` is present in the container environment. | Code, `docker exec`, 2026-09-24. |
| No passwordless `sudo`; every `nmcli` network-control action needs authentication from an SSH session. Code cannot change networking remotely even if permitted. | Code, `sudo -n`, `nmcli general permissions`. |
| The wireless regulatory domain is unset (`country 00`), which limits 5 GHz DFS channels and power. | Code, `iw reg get`. |
| Tailscale rides `wlo1`: no exit node, no accepted subnet routes. | Code, `tailscale debug prefs`. |
| `eno2` has a saved *shared* profile (`192.168.3.1/24`), last used 2026-09-14, documented nowhere in the repo. | Code, `nmcli`; repo search. Noted only; this plan does not use it. |
| Alerts at survey time: 10 WARN, 0 CRITICAL. | Code, `/api/alerts/active`. |

## The gap Step 0 closes

`No route to host` for `192.168.2.200` proves that nothing owns that address. It would look the same on a network where the camera is reachable at a different address. The reported band split is a plausible cause of a real isolation problem, but it has not been *tested*, because nobody has probed the camera's current address from the M920q. If the two bands are one LAN (or the router routes between them), Frigate only needs a new address. **Outcome: Step 0 was run and the camera is not reachable, so the split is real and not only a stale address.**

## Step 0 — probe the camera's current address (no network change)

Needs one input from Nate: the camera's current IP, from the Reolink app (device settings, network information) or the Starlink app's device list. Nate supplied `192.168.1.121`. On the M920q, read-only probes:

```bash
ip route get 192.168.1.121
ping -c3 -W2 192.168.1.121
nc -zv -w3 192.168.1.121 554
# Only worth running if the two above succeed. ffprobe is not on PATH in the
# container, so the full path is required. The password expands inside the
# container's shell and is never printed:
docker exec frigate sh -c 'timeout 15 /usr/lib/ffmpeg/7.0/bin/ffprobe -v error \
  -rtsp_transport tcp -show_entries stream=codec_name,width,height -of default=nw=1 \
  "rtsp://admin:${FRIGATE_RTSP_PASSWORD}@192.168.1.121:554/h264Preview_01_sub"'
```

| Result | Meaning | Next |
|---|---|---|
| Ping and port 554 answer, `ffprobe` prints a codec and size | The split is not blocking the M920q. Only the address in Frigate is stale. | A config PR changing `front_door`'s two inputs to `<CAMERA_IP>` (`cabin-orchestration-platform/infra/production-stack/frigate/config.yml`, deployed by `deploy-production-stack.yml` with auto-rollback). Remaining risk: the address changing again on a DHCP lease renewal. Nate decides how to pin it. W-15 shrinks. |
| No answer **(this is what happened, 2026-09-24)** | The networks are not reachable from each other as configured. Step 1 becomes worth its risk. | Step 1, pending Nate's approval. |

## Step 1 — option A: time-boxed join (only if Step 0 fails)

### What a join costs

While the radio is on the camera's network with `never-default`, the M920q has no default route and drops off the LAN and Tailscale. Expected effect (from `CLAUDE.md` and the stack docs, not measured):

| Stops working | Keeps working |
|---|---|
| Public site and Family Hub through `cloudflared`; ntfy push alerts; Blink cloud (`driveway` stream); cloud HA integrations (Kidde, Liebherr); SSH and Tailscale, including this session. | Zigbee (USB coordinator), local MQTT, Postgres, Kafka, Node-RED and HA local automation, local recording. |

Leak and freeze alerts cannot push while the window is open, so it must be short (target under three minutes) and never run with an active CRITICAL or leak/freeze alert.

### Safety design

The join is the risky part, because Nate is not on site and a failed revert strands the box. Three independent layers, each firing from the host itself:

1. **Fallback by profile.** The 5 GHz LAN profile keeps `autoconnect yes` and the joined profile keeps `autoconnect no`, so a failed join or a reboot returns to the LAN.
2. **Timed revert.** Two transient `systemd` timers armed *before* the join, at +180 s and +300 s, each running `nmcli connection up "<LAN_PROFILE>"`. They survive SSH dying. `systemd-run` is present; `at` is not. User-level timers would die at logout (`Linger=no`), so these are system timers and need `sudo`, which Nate types.
3. **Dead-man reboot (optional, recommended while nobody can reach the box).** A timer at +20 min running `systemctl reboot`, cancelled by Nate once he has confirmed a healthy state. A reboot returns to the LAN profile and restarts all containers, so it costs a short outage. It is only for the case where layers 1–2 both fail.

### Pre-flight (Nate present)

- [ ] `/api/alerts/active`: `CRITICAL` is 0 and no leak or freeze alert is active.
- [ ] No eval or training running on the M920q. Ollama's container is present; check the host for `ask_eval` / `eval_pipeline` processes.
- [ ] Record as-found state: `ip -br addr`, `ip route`, `ip -6 route show default`, `resolvectl status`, `tailscale status | head`.
- [ ] Record the as-found values of every profile field this plan changes (below), so the change can be undone exactly.
- [ ] Nate confirms which saved profile is the camera's network (the only 2.4 GHz network in the radio's cached scan is the never-used saved one, but that is inference, not confirmation) and gives the camera's MAC from the Reolink app for `<CAMERA_MAC>`.
- [ ] Decide on the optional reboot backstop.
- [ ] Consider `sudo iw reg set US` first (a runtime setting, lost on reboot). The unset domain restricts channels and may affect the join.

### The profile change (a persistent NetworkManager edit, so done only with Nate present)

```bash
sudo nmcli connection modify "<STARLINK_2G_PROFILE>" \
  ipv4.never-default yes ipv6.never-default yes \
  ipv6.method link-local \
  ipv4.ignore-auto-dns yes ipv6.ignore-auto-dns yes \
  connection.autoconnect no
```

`ipv6.method link-local` (rather than `auto`, `ignore` or `disabled`) is chosen so NetworkManager takes no router advertisement, and so the setting cannot leak into the LAN profile, which stays on `auto`. The window must confirm no IPv6 default route exists. The M920q currently learns one by router advertisement on `wlo1`.

### The window (draft, untested, for review)

Run detached from SSH so it survives the link dropping, then read the log after the box is back:

```bash
sudo systemd-run --unit=w15-window --collect /bin/bash /var/tmp/w15-window.sh
```

```bash
#!/usr/bin/env bash
# DRAFT, untested. Run as root on the M920q with Nate present.
set -u
LAN="<LAN_PROFILE>"; ALT="<STARLINK_2G_PROFILE>"; CAM="192.168.1.121"
CAM_MAC="<CAMERA_MAC>"   # from the Reolink app; compared below before any credential is sent
LOG=/var/tmp/w15-window-$(date +%F-%H%M%S).log
exec > >(tee -a "$LOG") 2>&1

echo "== baseline =="; ip -br addr show wlo1; ip route; ip -6 route show default

echo "== arm backstops (fire even if this script or SSH dies) =="
systemd-run --unit=w15-revert-a --on-active=180s nmcli connection up "$LAN"
systemd-run --unit=w15-revert-b --on-active=300s nmcli connection up "$LAN"

echo "== join =="
nmcli --wait 30 connection up "$ALT" || echo "JOIN FAILED"
sleep 5

echo "== state in window (expect NO default route, v4 or v6) =="
ip -br addr show wlo1; ip route; ip -6 route show default

echo "== camera tests =="
ip route get "$CAM"
ping -c3 -W2 "$CAM"
nc -zv -w3 "$CAM" 554
# Identity gate: only send the RTSP password to the device whose MAC matches.
SEEN=$(ip neigh show "$CAM" | awk '{print tolower($5)}')
echo "camera MAC seen: ${SEEN:-none}"
if [ -n "$SEEN" ] && [ "$SEEN" = "$(echo "$CAM_MAC" | tr A-Z a-z)" ]; then
  docker exec frigate sh -c "timeout 15 /usr/lib/ffmpeg/7.0/bin/ffprobe -v error \
    -rtsp_transport tcp -show_entries stream=codec_name,width,height -of default=nw=1 \
    \"rtsp://admin:\${FRIGATE_RTSP_PASSWORD}@${CAM}:554/h264Preview_01_sub\""
else
  echo "MAC MISMATCH or not seen -- credentials NOT sent"
fi

echo "== revert now =="
nmcli --wait 30 connection up "$LAN"
sleep 10

echo "== verify =="
ip route | grep '^default'; ip -6 route show default
ping -c2 -W2 192.168.2.1
tailscale status | head -2
curl -s -o /dev/null -w 'backend health: %{http_code}\n' http://localhost:8090/actuator/health
curl -s http://localhost:5000/api/stats | python3 -c \
  "import sys,json; print({n:v.get('camera_fps') for n,v in json.load(sys.stdin)['cameras'].items()})"
systemctl stop w15-revert-a.timer w15-revert-b.timer
echo "== done, cancel any dead-man timer manually =="
```

### Pass and abort

- **Proves never-default:** inside the window there is no default route, IPv4 or IPv6.
- **Proves reachability:** ping, port 554 and `ffprobe` all succeed at `<CAMERA_IP>`.
- **Proves the revert:** afterwards the default route is back via the LAN gateway, Tailscale is connected, backend health is `200`, and the cameras that were live before the window report frames again (at survey time `driveway` reported 5.1 fps; it is a motion-gated Blink relay, so compare against the pre-window reading rather than expecting a fixed number). `front_door` staying at 0 fps is expected: Frigate still holds the old address.
- **Abort at once** on any CRITICAL alert, or if Nate says stop: `sudo nmcli connection up "<LAN_PROFILE>"`.

## After the window (a decision for Nate, not made here)

| If the camera was reachable | If it was not |
|---|---|
| A durable link to both networks needs a second radio (a USB Wi-Fi adapter would allow the never-default profile to stay joined permanently, with no timed revert), or fixing the camera's address in person. Frigate then gets a config PR for the new address. | The cause is something other than the band split (for example client isolation on the camera's network, or the camera's own network settings). Check those before spending on hardware. |

## Not in scope

- Any change to the camera itself, the routers, or the Starlink configuration.
- The Frigate config change for a new address (a follow-on PR once an address is known).
- Buying hardware, and alternative options to A (including the unused `eno2` shared profile). Not evaluated.
- Merging #97. It stays held: its `front_door` tuning cannot be measured while the camera delivers no frames, and merging auto-deploys production Frigate config.
