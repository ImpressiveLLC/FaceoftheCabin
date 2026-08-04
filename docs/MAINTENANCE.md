# Maintenance & Operations Guide

*Audience: whoever is operating, deploying, or debugging this platform.
For product/day-to-day usage, see [`USER_GUIDE.md`](USER_GUIDE.md). For
standing up an independent instance, see [`REPLICATION.md`](REPLICATION.md).
For the strategic/architectural brief, see [`../ROADMAP.md`](../ROADMAP.md).*

---

## Architecture at a glance

```
                          Cloudflare Tunnel (public)
                                    │
        ┌───────────────┬──────────┼──────────┬───────────────┐
        │               │          │           │               │
   hub.…com         cabin.…com  api.…com   (Tailscale-only, not tunneled)
  family-hub         cabin-ui  cabin-backend      HA · Node-RED
  (static HTML)      (React)   (Spring Boot)      Frigate · Z2M
                                    │              Grafana
                          ┌─────────┼─────────┐
                     Postgres    Kafka       MQTT (Mosquitto)
```

- **family-hub** (static HTML, no build step) — the ambient Family Hub
  display. Deployed as-is, no compilation.
- **cabin-ui** (React + Vite) — the cabin-side control app, includes the
  authenticated Camera Events panel and the Opportunity Map panel (Tech
  ID Service findings, presented per the See/Think/Act model — see
  `docs/PRODUCT_NOTES.md`'s 2026-08-03 review).
- **cabin-backend** (Spring Boot) — the shared API: notes, chores,
  profiles, camera media proxy, device status, event ingestion, Tech ID
  Service findings intake and action log (`/api/tech-id/findings` — see
  below), ontology entity lookup (`/api/ontology/entities` — resolves
  raw entity ids to display names, backing the Opportunity Map's
  lineage chips; parses `docs/ontology.yaml` directly via a **read-only
  bind mount**, `../../docs:/app/docs:ro` in
  `docker-compose.m920q.yml` — the Docker build context is
  `../backend` only, so this file is not otherwise reachable inside the
  container; missing the mount degrades lookups to a best-effort
  humanized label rather than failing). Talks to Postgres (state),
  Kafka (event bus), and MQTT (device/camera telemetry in).
- **Frigate** — NVR: camera detection, recording, live streaming. Publishes
  detection/motion events to MQTT.
- **Home Assistant + Node-RED** — device automation (leak/freeze sensors,
  water shutoff, intrusion alarm, WiFi-based presence, camera alerts).
  Tailscale-only; not exposed through the tunnel.
- **Zigbee2MQTT** — Zigbee coordinator bridge (14 paired sensors/actuators).

**Public vs. private surface, by design**: `hub`/`cabin`/`api` are public
through Cloudflare Tunnel — that's the actual product. Everything else
(HA, Node-RED, Frigate's own admin UI, Zigbee2MQTT's UI, Grafana) is
Tailscale-only. This isn't an oversight to "fix" — it's the deliberate
boundary between the product surface and the reconfiguration surface. See
`docs/ontology.yaml` and `ROADMAP.md` for the full reasoning.

---

## Deployment

### Automated (family-hub, cabin-ui)

A self-hosted GitHub Actions runner on the M920q polls for pushes to
`main` and auto-deploys `family-hub` and `cabin-ui` when their paths
change (`.github/workflows/deploy-family-hub.yml`). No inbound ports, no
secrets stored in GitHub — the runner connects outbound over Tailscale.
Setup/recovery instructions: [`../ansible/README.md`](../ansible/README.md).

### Manual (cabin-backend) — **do this every time you change the backend**

`cabin-backend` is **deliberately excluded** from the automated workflow
— it's stateful/sensitive enough to warrant a considered rollout, not a
blanket rebuild on every push. After merging a backend change:

```bash
ssh nate@nates-little-m920q.tailb20f8b.ts.net
cd /home/nate/FaceoftheCabin
git pull
cd cabin-orchestration-platform/infra
docker compose -f docker-compose.yml -f docker-compose.m920q.yml build cabin-backend
docker compose -f docker-compose.yml -f docker-compose.m920q.yml up -d cabin-backend
```

**Always verify after deploying**, don't trust a green build alone:

```bash
curl -s http://localhost:8090/actuator/health   # expect {"status":"UP",...}
```

A real, repeated lesson from this project: a passing build and a healthy
container status are not proof the *feature* works. Verify against the
actual live endpoint/response, not just process status — see "Known
Issues" below for cases where everything *looked* fine and wasn't.

---

## Secrets

Every credential (`POSTGRES_PASSWORD`, `GRAFANA_PASSWORD`, `HA_TOKEN`,
`CAMERA_PASSWORD`, `TECH_ID_API_KEY`) is Ansible Vault-managed — encrypted
at rest, committed to git (that's the point of Vault), templated into
`infra/.env` by the `secrets` role rather than hand-edited. Full setup and
rotation instructions: [`../ansible/README.md`](../ansible/README.md)'s
Secrets section.

`TECH_ID_API_KEY` (→ `cabin.techid.apiKey`) gates `POST
/api/tech-id/findings` — the shared secret any Tech ID Service provider
(this instance's own scheduled scan, an operator's paid tier, or an
instance owner's own AI pipeline) presents via `X-Tech-Id-Api-Key` to
submit findings. Unset by default; submission returns `503` until an
operator opts in. See `ROADMAP.md`'s "Tech ID Service — Provider Model"
and `REPLICATION.md` §8 for the full design and setup steps.

**Quick reference:**
```bash
cd ansible
# Template .env from the current vault (after any vault edit)
ansible-playbook -i inventory.ini site.yml --limit cabin --vault-password-file ~/.ansible_vault_pass --tags secrets

# Rotate POSTGRES_PASSWORD end to end (generate, apply live, re-encrypt, re-template, restart, validate)
ansible-playbook -i inventory.ini playbooks/rotate-secrets.yml --limit cabin --vault-password-file ~/.ansible_vault_pass
```

Runs automatically monthly via `.github/workflows/rotate-secrets.yml`.
`GRAFANA_PASSWORD`/`HA_TOKEN` rotation is not automated yet — different
mechanics needed (Grafana's admin API, HA's own token UI), still a manual
`ansible-vault edit` + matching account-side change.

**If running Ansible directly on the M920q** (as opposed to from a
separate machine with SSH access), self-targeting via its own Tailscale
hostname doesn't work — it's a hairpin routing limitation, not a bug in
the playbook. Use `-c local -e ansible_become=false` in that case.

**Never diff a secret by its raw value.** Compare by presence/absence, a
hash, or a boolean the script prints — never `diff <(grep KEY old)
<(grep KEY new)` where the value itself lands in a terminal or log. This
project had a real incident where a password briefly appeared in a
session transcript this way; it's a very easy mistake to repeat if you're
not deliberately avoiding it.

---

## CI/CD

Self-hosted GitHub Actions runner, registered on the M920q, connecting
outbound over Tailscale — no inbound ports, no SSH keys stored in GitHub.
Chosen specifically because the M920q is behind Starlink CGNAT (no public
IP to receive inbound connections on) and a GitHub-hosted runner has no
network path to a Tailscale-only host. Full setup/recovery runbook:
[`../ansible/README.md`](../ansible/README.md).

---

## Database & Storage

- **Postgres** (`cabin-postgres`) — application state: notes, chore
  completion, family profiles, camera events. Password is Vault-managed
  (see Secrets above); `POSTGRES_PASSWORD` only takes effect at *first*
  volume initialization — changing the env var alone does not change a
  running instance's actual password, only `ALTER USER` does (the
  rotation playbook handles this correctly; a naive env-var-only change
  would silently break the connection).
- **Kafka** (`cabin-kafka`) — single-broker event bus for camera/device
  events. **Known gotcha**: the internal `__consumer_offsets` topic
  defaults to replication factor 3, which silently fails to create on a
  single-broker cluster — the symptom is every consumer group hanging
  forever with zero partitions assigned, no visible error anywhere except
  a `FIND_COORDINATOR` timeout if you probe directly. Fixed via
  `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1` (and the matching
  transaction-log settings) in `docker-compose.yml` — already applied,
  but worth knowing if you ever see consumers silently not consuming.
- **`/storage`** (`/dev/sda1`, ~931GB, separate from the boot NVMe) —
  where Frigate writes recordings. Check headroom before increasing any
  retention window: `df -h /storage`. Continuous recording is currently
  capped at 5 days deliberately conservative — the `front_door` (Reolink)
  camera's record role uses its full 4K stream, which at realistic
  bitrates could plausibly consume 80-160+ GB/day once it's back online.
  Re-measure real usage before extending retention, don't trust an
  estimate.

---

## Cameras (Frigate)

Live config lives at `/storage/services/frigate/config.yml` on the
M920q — **this file has zero git history** (a known, real gap; the file
predates this project's git tracking and hasn't been retrofitted in).
**Always back it up before editing**:

```bash
cp /storage/services/frigate/config.yml /storage/services/frigate/config.yml.bak-$(date +%Y%m%d-%H%M%S)
```

Edit locally, validate with `python3 -c "import yaml; yaml.safe_load(open('config.yml'))"`
*before* copying it to the host — a YAML syntax error in a live config
will prevent Frigate from starting cleanly. Never build the file inline
inside a quoted SSH command on a Windows/Git Bash client — backticks and
certain special characters get expanded by the *local* shell before
transmission, which has genuinely corrupted this file once already. Write
the file locally, `scp` it over, then apply.

**Current cameras**: `front_door` (Reolink RLC-820A, 4K record / 640×480
detect, currently off-network — physical/WiFi issue, needs on-site
checking) and `driveway` (a Blink camera bridged via `blinkbridge` +
`mediamtx`, no true continuous stream — only "live" when Blink's own
cloud has already detected motion, so its detection is a second,
finer-grained pass on top of Blink's own trigger, not a replacement for
it). Both physically cover the same front-door/driveway area from
different angles — the names describe available semantic slots, not
fixed device identities; one is expected to eventually relocate to cover
the building's rear.

Restart after any config change: `docker restart frigate`, then verify:
```bash
curl -s http://localhost:5000/api/config | python3 -c "import sys,json; print(list(json.load(sys.stdin)['cameras'].keys()))"
```

**There are two Frigate container definitions on this host — only one is
real.** Confirmed 2026-08-03 after an external review flagged an
apparent "config disagrees with git" drift. `docker-compose.yml`'s own
bundled `frigate:` service (container name `cabin-frigate`, config
`cabin-orchestration-platform/infra/frigate.yml`, git-tracked) exists in
this repo but has never actually been started (`docker inspect
cabin-frigate` shows `Status=created`, `RestartCount=0`) — it's inert,
using zero resources, but its config file is genuinely stale (still has
the pre-rename camera names/settings) and reads as a live source of
truth if you don't check container state first. **The container that
actually matters is `frigate`** (no `cabin-` prefix), managed outside
this repo by CabinAutomations, config at `/storage/services/frigate/config.yml`
per the section above — `cabin-backend`'s `FRIGATE_URL` defaults to
`http://frigate:5000`, which resolves to this one on the shared
`cabin_default` Docker network. When debugging a camera/Frigate issue,
always check `docker ps` for which container is actually running before
trusting any config file's contents. Not yet cleaned up (removing the
orphaned service from `docker-compose.yml` is a real infra edit,
flagged for the user to confirm rather than done unilaterally) — this
note exists so the next person doesn't lose time to the same confusion.

**Blink camera (`driveway`) went dark for hours with no auto-recovery
— found and fixed 2026-08-03.** External review's "no camera-event
pipeline activity" finding traced to `docker exec frigate curl
localhost:5000/api/stats`: both cameras showed `camera_fps: 0.0` — no
incoming video at all, so zero detections were possible regardless of
anything downstream (MQTT, Kafka, Postgres were all healthy and
correctly wired the whole time). Two independent causes:
- `front_door` (Reolink, 192.168.2.200): `ffmpeg` logs showed `No route
  to host` — the existing, already-documented off-network issue above,
  unrelated to this incident, still needs on-site checking.
- `driveway` (Blink): `blinkbridge`'s logs showed a transient failure
  reaching Blink's own cloud API (`rest-e003.immedia-semi.com`, "Cannot
  connect to host") around 05:17 UTC that morning, after which
  `blinkbridge` logged `too many failures, disabling` and never
  retried — the container itself never crashed or restarted
  (`RestartCount=0`), it just gave up internally and sat idle for
  hours, so no restart policy would have caught it. `mediamtx` logs
  confirmed nothing was publishing to `rtsp://mediamtx:8554/outdoor_4_-_dhee`
  (Frigate's configured source for this camera) the whole time, hence
  its own repeating `404 Not Found` / `Unable to read frames` errors.
  **Fixed with `docker restart blinkbridge`** — re-authenticated with
  Blink immediately, stream resumed, Frigate's `camera_fps` went from
  `0.0` to `5.1` within seconds. **Not yet fixed**: `blinkbridge` has no
  self-healing behavior for this specific failure mode (an internal
  "give up" state that a container restart clears but nothing else
  does) — worth an Uptime Kuma check against `mediamtx`'s stream health
  or Frigate's own `camera_fps` so this doesn't require someone to
  notice "no events in days" before catching it next time.

### Real on-demand liveview for the Blink camera (built 2026-08-03)

**The bug this fixes**: `driveway`'s "live view" was never actually
live. `blinkbridge` only republishes Blink's motion-triggered clips on
a loop (see the Frigate config section above), so watching "live"
during the day could show a stale frame from hours-old nighttime
motion — reported directly by the user ("camera view is still
nighttime and it's daytime now, so it's not live") after the previous
incident's fix already had the camera streaming again.

**The fix**: an actual on-demand Blink liveview session, triggered only
while someone is watching (explicit decision — Blink battery cameras
aren't built for continuous streaming; see
`docs/ontology.yaml`'s `cabin_camera_live_view` entry for the full
write-up). Three pieces:

1. **`blinkbridge` itself** (source at `/storage/services/blinkbridge-src`
   on the M920q — **also zero git history**, same known gap as
   `frigate/config.yml`, backed up to a timestamped copy in the same
   directory before this session's edits). Added:
   - A small `aiohttp.web` control API (`POST /liveview/{camera}/start`
     and `/stop`, port `8811`, reachable only from other containers on
     `cabin_default`, no host port published) that opens a real
     `blinkpy` `BlinkLiveStream` session and relays it into the same
     `mediamtx` path Frigate already reads from — nothing downstream
     (Frigate config, cabin-backend) needed to change.
   - Automatic fallback: if Blink's own liveview server ends the
     session early (observed twice during testing — see the `blinkpy`
     bug below), or after a 5-minute hard cap if nothing calls `/stop`,
     the motion-clip loop resumes on its own. Before this, a camera
     stuck in a dead live session had no recovery path but a manual
     `/stop` call.
   - **Found and fixed a real bug in the third-party `blinkpy`
     library** while building this: its `BlinkLiveStream.recv()` reads
     protocol data with `StreamReader.read(n)`, which asyncio does
     **not** guarantee returns exactly `n` bytes — reliably killed
     every liveview session within ~5 seconds against the real camera
     ("Insufficient data for payload"). Confirmed present in both the
     installed version (0.25.7) and the latest on PyPI (0.25.9) at the
     time — upgrading would not have helped. Patched at runtime via
     `blinkbridge/patches.py` (`readexactly()` instead of `read()`,
     applied by monkey-patching the class at import time) rather than
     forking `blinkpy` — delete that file if a future `blinkpy` release
     fixes this upstream.
2. **`cabin-backend`**: `CameraMediaController` gained
   `POST /api/camera/{cameraName}/liveview/start|stop`, gated the same
   as the rest of `/api/camera/**`. Config-driven and camera-agnostic —
   `cabin.devices.cameras.blinkCameraMap` (`TECH_ID`-style
   `cabinName:blinkDeviceName` pairs, e.g. `driveway:Outdoor 4 - DHEE`)
   maps this platform's camera names to Blink's own device names. Any
   camera not in that map (the Reolink) gets a harmless no-op —
   correct, since it's already continuously live over native RTSP and
   has nothing to "start."
3. **`cabin-ui`**: `CameraEventsPanel`'s existing "Watch live" toggle
   now fires the start/stop calls via a `useEffect` keyed on
   `liveCamera` — switching cameras, clicking "Stop," or leaving the
   panel all correctly end the previous session through the same
   cleanup path, no special-casing needed per trigger.

**Verified against the real camera**, not just code review: a snapshot
pulled directly from Frigate mid-session showed genuine, current
daylight footage (sunlit trees, visible ground debris) — a stark
contrast from the stale nighttime IR frame every attempt showed before
the `blinkpy` patch.

**Rebuild/redeploy procedure** (for the next time `blinkbridge`
needs a code change):
```bash
# On the M920q, after editing /storage/services/blinkbridge-src/blinkbridge/*.py
cp -r /storage/services/blinkbridge-src /storage/services/blinkbridge-src.bak-$(date +%Y%m%d-%H%M%S)
docker build -t cabin-blinkbridge:new /storage/services/blinkbridge-src
PASS=$(docker inspect blinkbridge --format '{{range .Config.Env}}{{println .}}{{end}}' | grep ^BLINK_PASSWORD= | cut -d= -f2-)
docker stop blinkbridge && docker rm blinkbridge
docker run -d --name blinkbridge --network cabin_default --restart unless-stopped \
  -v /storage/services/blinkbridge:/config \
  -v /storage/cameras/blinkbridge:/working \
  -e BLINKBRIDGE_CONFIG=/config/config.json \
  -e BLINK_USERNAME=nhsmrekar@gmail.com \
  -e "BLINK_PASSWORD=$PASS" \
  cabin-blinkbridge:new
unset PASS
```
Never print `$PASS` — keep it inside the remote shell only. (This
project had a real incident where `docker inspect`'s full environment
output was dumped without filtering and printed a live password into a
session transcript — see the Secrets section's "never diff a secret by
raw value" note; the same discipline applies here.)

---

## Overnight Camera Alerts (Node-RED)

A dedicated Node-RED tab ("Camera Overnight Alerts") pushes a real
notification via [ntfy.sh](https://ntfy.sh) when Frigate detects an
alert-tier object while the cabin is armed-away and no one's present.
Gated on the same MQTT-published state
(`cabin/security/node_red_armed`, `cabin/presence/nate`) the existing
intrusion-alarm flow already tracks, subscribed independently rather than
sharing Node-RED flow-context directly (flow context is tab-scoped by
default; MQTT pub/sub is the intended decoupling point).

**Editing this flow**: prefer Node-RED's Admin API (`POST`/`PUT
/flow/:id` at `http://localhost:1880`) over hand-editing
`/storage/services/nodered/flows.json` directly — the file gets
overwritten by the running editor's own autosave, and if anyone is
actively editing a different tab in the Node-RED UI at the time, a raw
file edit risks losing their in-progress work.

**Known timing gotcha**: immediately after a fresh deploy via the Admin
API, retained MQTT state may not be delivered to a brand-new
subscription's local flow-context yet — a detection tested within the
first second or two of deploy can be incorrectly gated out. This
self-resolves within about a minute as the existing WiFi-presence
automation (`cabin_security_presence.yaml`) republishes state every 60s
regardless. Not a broken subscription — confirmed via direct testing that
a fresh (non-retained) publish is received correctly immediately.

**Known gap, not yet fixed**: `cabin/security/node_red_armed` has no
live, ongoing publisher — it was set via a one-off manual `mosquitto_pub`
when the intrusion flow was first armed directly in Node-RED. If that
retained value is ever lost (e.g. a broker restart without persistence),
both the intrusion flow and the camera-alert flow would silently stay
gated closed until manually republished.

---

## Grafana — Off-Tailscale Access via Cloudflare + Google OAuth

**Status as of 2026-08-04: code/config side done and deployed; several
steps only the account owner can do (Google Cloud Console, Cloudflare
dashboard) are still open.** Grafana was Tailscale-only by design,
alongside HA/Node-RED/Zigbee2MQTT (see "Public vs. private surface, by
design" at the top of this doc) — this is a deliberate, one-off
exception for Grafana specifically, made after the redirect-bug fix
above prompted the user to ask for real off-Tailscale reachability, not
just a working Tailscale link. **Node-RED, Frigate's admin UI, HA, and
Zigbee2MQTT are unchanged** — still Tailscale-only, still not exposed
through the tunnel.

**Design**: Grafana gets its own HTTPS hostname
(`grafana.unicornpingpong.com`) through the existing Cloudflare Tunnel
(`cloudflared` and `cabin-grafana` already share the `infra_default`
Docker network, so the tunnel routes straight to
`http://cabin-grafana:3000` internally — no host port involved), gated
by a Cloudflare Access policy at the edge (email-based One-Time PIN,
**not** Google — see below for why), then Grafana's own login requires
Google OAuth using the **same** Google Cloud OAuth client
family-hub/cabin-ui already use (`GOOGLE_CLIENT_ID`), not a second
registered app. Explicit user requirement: "I don't want to oauth
twice." Realistic version of that: one OAuth *client* to manage (done),
not literal zero-click SSO across apps (Grafana's OAuth is its own
server-side redirect — not something this setup collapses into cabin-
ui's session). Cloudflare Access uses email OTP rather than its own
Google sign-in specifically so the *Google* OAuth prompt only ever
happens once, at Grafana's own login — stacking two separate Google
sign-in prompts (Access, then Grafana) would have been a real "OAuth
twice" in the way the user meant it, even though technically
same-account, different mechanism.

### What's done (code side)

- `ansible/group_vars/cabin/vars.yml` / `roles/secrets/templates/env.j2`
  / `.env.m920q.example`: new `GOOGLE_CLIENT_SECRET` — a **real** secret
  (unlike `GOOGLE_CLIENT_ID`, which is safe client-side by design),
  needed because Grafana's OAuth is a server-side authorization-code
  flow. Empty by default; Grafana's Google auth block stays inert
  (falls back to its own admin/password login) until this is set.
- `docker-compose.m920q.yml`'s `cabin-grafana` service: `GF_AUTH_GOOGLE_*`
  block (enabled, reuses `GOOGLE_CLIENT_ID`/`GOOGLE_CLIENT_SECRET`,
  `GF_AUTH_GOOGLE_ALLOWED_EMAILS` reuses `ADMIN_EMAILS`), and
  `GF_SERVER_ROOT_URL` updated to `https://grafana.unicornpingpong.com/grafana`
  (was the Tailscale IP — see the redirect-bug entry above).
- `cabin-ui`'s `VITE_CABIN_GRAFANA_URL` build arg updated to match, so
  the embedded dashboard panel and the "Grafana" quick-link both point
  at the new hostname.
- **Not yet verified**: `GF_AUTH_GOOGLE_ALLOWED_EMAILS` is a real
  Grafana setting in reasonably recent releases, but this hasn't been
  confirmed against whatever `grafana/grafana:latest` resolves to at
  actual deploy time. Confirm on first real login (step 6 below) that
  an email *not* in `ADMIN_EMAILS` is genuinely refused, not just
  untested.

### What's still open — only the account owner can do these

1. **Rotate the Cloudflare Tunnel token first.** It was accidentally
   printed in full during this work (`docker inspect cloudflared
   --format '{{.Config.Cmd}}'`, before the lesson below was learned) —
   treat it as compromised. Cloudflare Zero Trust dashboard → Networks
   → Tunnels → the tunnel → Configure → rotate/regenerate the token,
   then on the M920q: `docker rm -f cloudflared && docker run -d
   --name cloudflared --restart unless-stopped --network cabin_default
   cloudflare/cloudflared:latest tunnel --no-autoupdate run --token
   <NEW_TOKEN>` (confirm the exact image/network flags against the
   currently-running container's config first, filtering for
   non-secret fields only — see the credential-handling lesson two
   entries below).
2. **Get the Google OAuth Client Secret.** Google Cloud Console → APIs
   & Services → Credentials → the existing Web application OAuth
   client (same one `GOOGLE_CLIENT_ID` comes from) → "Client secret."
3. **Add it to the vault yourself** — don't paste it into a chat with
   any AI assistant, including this one; that puts a real secret in a
   transcript the same way the tunnel token above ended up exposed.
   On the M920q:
   ```bash
   cd ~/FaceoftheCabin/ansible
   ansible-vault edit group_vars/cabin/vault.yml --vault-password-file ~/.ansible_vault_pass
   # add: vault_google_client_secret: "<paste here>"
   ansible-playbook -i inventory.ini site.yml --limit cabin --vault-password-file ~/.ansible_vault_pass --tags secrets
   ```
4. **Add the OAuth redirect URI.** Same Google Cloud Console
   Credentials page, same client → Authorized redirect URIs → add
   `https://grafana.unicornpingpong.com/grafana/login/google` exactly
   (Grafana's OAuth callback path, with the `/grafana` sub-path prefix
   from `GF_SERVER_SERVE_FROM_SUB_PATH`). Google will likely reject a
   raw IP or plain-HTTP URI here — this is exactly why Grafana needed a
   real hostname to make OAuth possible at all.
5. **Cloudflare Zero Trust dashboard**:
   - Networks → Tunnels → the tunnel → add a **Public Hostname**:
     `grafana.unicornpingpong.com` → service `http://cabin-grafana:3000`
     (internal Docker DNS name, not the host's `3002` port — see
     "Design" above for why that's reachable directly).
   - Access → Applications → **Add an application** (Self-hosted) for
     `grafana.unicornpingpong.com`. Policy: Allow, matching **emails**
     (list the same accounts as `ADMIN_EMAILS`), identity method
     **One-Time PIN** — deliberately not "Login with Google" here, per
     the "Design" section above (keeps the Google prompt to exactly
     once, at Grafana's own login).
6. **Redeploy and test end to end**:
   ```bash
   ssh nate@nates-little-m920q.tailb20f8b.ts.net
   cd ~/FaceoftheCabin/cabin-orchestration-platform/infra
   docker compose -f docker-compose.yml -f docker-compose.m920q.yml up -d cabin-grafana
   docker compose -f docker-compose.yml -f docker-compose.m920q.yml build cabin-ui
   docker compose -f docker-compose.yml -f docker-compose.m920q.yml up -d cabin-ui
   ```
   Then from a browser with **no Tailscale connection active**, visit
   `https://grafana.unicornpingpong.com/grafana/` — should prompt
   Cloudflare Access's One-Time PIN first, then Grafana's own "Sign in
   with Google." Also test with an email genuinely outside
   `ADMIN_EMAILS` to confirm the allow-list is actually enforced at
   both layers, not just the one that happens to work.

---

## Known Issues & Operational Lessons

*A working incident log — real problems found and fixed, kept here so
the next person (or session) doesn't have to rediscover them.*

### Grafana redirected every remote request to "localhost" (found and fixed 2026-08-03)

Reported as "won't load off Tailscale" — cabin-ui already carries an
honest warning label next to the Grafana embed to that effect
(`App.jsx`: "Won't load off Tailscale — Grafana is cabin-network-only"),
so the report read at first like a case of that label finally getting
noticed, not a bug. It was a bug. `GF_SERVER_ROOT_URL` was set to
`"%(protocol)s://%(domain)s:%(http_port)s/grafana"` — Grafana fills
`%(domain)s`/`%(http_port)s` from its own `[server]` settings, neither
of which was ever configured, so they silently defaulted to
`domain=localhost` and `http_port=3000` (Grafana's *internal* container
port — it has no idea this host remaps it to `3002`). Confirmed via
`curl -I http://100.77.44.113:3002/`: connection succeeded fine (so it
was never actually a Tailscale/network/firewall issue), but the
response was `301` to `http://localhost:3000/grafana/` — "localhost"
meaning the *viewer's own machine*, which has nothing listening on port
3000. Every real browser hit this same redirect and failed to load,
regardless of network path. **Fixed** by making `GF_SERVER_ROOT_URL`
fully explicit (`http://${GRAFANA_EXTERNAL_HOST:-100.77.44.113}:3002/grafana`,
new optional env var, see `.env.m920q.example`) instead of relying on
template substitution that had no way to know about the host's port
remap. **Lesson**: when a Docker port mapping remaps the external port
(`3002:3000`), any app-level "what's my own URL" template variable
needs to be told the *external* port explicitly — it cannot infer the
remap from inside the container.

**Follow-up, 2026-08-04**: once this redirect bug was fixed, the user
asked a fair follow-up — did they actually want Tailscale-only, or did
they want Grafana reachable from anywhere? They chose the latter.
`GRAFANA_EXTERNAL_HOST`'s meaning and default changed accordingly (now
a public hostname, not the Tailscale IP) — see the new "Grafana — Off-
Tailscale Access" section below for the real fix that followed this one.

### CORS preflight requests were silently rejected (found 2026-08-03)

`GoogleAuthInterceptor` checked for a valid Bearer token on *every*
request matching its gated paths, including CORS preflight `OPTIONS`
requests — which never carry a real credential, by design. Browsers
require the preflight response itself to be a plain 2xx or they refuse
to send the real request at all, **regardless of which CORS headers are
present on a non-2xx response**. `curl` does not enforce this rule,
which is exactly why extensive `curl`-based testing repeatedly (and
wrongly) looked correct. **Real impact**: this silently broke notes,
chores, profile, and camera sync from any real browser this entire
project, the whole time — each has a graceful offline fallback, which is
exactly why it went unnoticed. **Lesson**: `curl` cannot validate CORS
preflight behavior. Any endpoint gated by an auth interceptor and called
cross-origin needs a real-browser test, not just a `curl` check, however
thorough the `curl` check looks.

### Kafka `__consumer_offsets` replication factor (found 2026-08-02)

See "Database & Storage" above — single-broker Kafka silently fails to
create the internal offsets topic at its default replication factor 3,
with consumers hanging forever and no visible error.

### `-parameters` compiler flag missing (found 2026-08-02)

Unnamed `@RequestParam`/`@PathVariable` arguments throw
`IllegalArgumentException` at the *first real invocation* without
Spring Boot's `-parameters` javac flag — not at compile time, not at
startup, only when the endpoint is actually hit. Fixed globally in
`pom.xml`'s compiler plugin config, since it silently affected every
other unnamed `@RequestParam` in the codebase, not just the one endpoint
that surfaced it.

### JDBC `jsonb` columns return `PGobject`, not `String` (found 2026-08-02)

Casting a `jsonb` column's value directly to `String` in a generic
`JdbcTemplate` row-mapping throws `ClassCastException` — the driver
returns a `PGobject`. Call `.toString()` on the raw `Object` first.

### Docker Compose build args silently dropped without matching `ARG`/`ENV` (found 2026-08-02)

A `docker-compose.yml` `args:` entry does nothing unless the Dockerfile
has a matching `ARG` *and* re-exports it via `ENV` — otherwise Vite (and
similar build tools) never sees the value, and the build silently falls
back to whatever default is baked into the source. Confirmed this had
been happening for `VITE_CABIN_HA_URL`/`VITE_CABIN_Z2M_URL`/
`VITE_CABIN_FAMILY_HUB_URL` for some time before being caught.

### Cloudflare edge-caching a build-time config file (found 2026-08-01)

`host-config.js` (carries `GOOGLE_CLIENT_ID`/`ADMIN_EMAILS`/`CABIN_API_URL`,
regenerated on every deploy) got caught by a blanket `.js` cache rule
meant for genuinely static assets, then cached at Cloudflare's edge for
7 days as `immutable`. A stale cached copy silently disables
cross-device sync for real visitors with no visible error — it just
degrades to the offline/localStorage-only fallback. Fixed with an
exact-match no-cache rule for this specific file; **an already-cached
edge copy still needs a manual Cloudflare purge** to take effect
immediately rather than waiting out the TTL.

### Ansible role search path breaks when a playbook is nested (found 2026-08-02)

`ansible/playbooks/rotate-secrets.yml`'s `include_role: name: secrets`
failed to find `ansible/roles/secrets` — Ansible's default role search
path is relative to the *playbook's own directory*, not the `ansible/`
root. Fixed via `ansible/ansible.cfg`'s `roles_path`, discovered via
cwd-relative lookup (matches every documented usage's `cd ansible`
first).

### Self-targeting via Tailscale hostname fails when run from the same host (found 2026-08-02)

See "Secrets" above — a genuine hairpin routing limitation, not an
Ansible bug.

### Never diff secrets by raw value (found 2026-08-03)

See "Secrets" above.

### Manual MQTT test publishes leave permanent fake events behind (found 2026-08-03)

Testing the overnight camera alert flow via `mosquitto_pub` directly
against the live `cabin/camera/events` topic worked correctly for
verifying the pipeline, but `MqttBridgeService` has no way to
distinguish a real Frigate detection from a manually-injected test
message on the same topic — every test publish became a permanent,
real-looking `cabin_event` row (complete with a plausible `person`/`dog`
label and confidence score), indistinguishable from a genuine detection
in `/api/events` or the Camera Events panel. Found when the user
reasonably asked why "detected" events had no video — they were never
real detections, just leftover test artifacts nobody cleaned up.
**Lesson**: after any live MQTT test against a production topic, delete
the resulting event rows explicitly (`DELETE FROM cabin_event WHERE
event_id IN (...)`, exact IDs only, never a broad time-range or label
match) — don't leave test data mixed into a table real users see through
the actual product UI. This action correctly requires explicit user
confirmation (Claude Code's auto-mode classifier blocks unconfirmed
`DELETE`s against a live database) — that's working as intended, not a
tool to route around.

### `RemoteTrigger` scheduled routine returns 403 without explicit repo access grant (found and resolved 2026-08-03)

Creating a claude.ai cloud routine (the mechanism behind the Tech ID
Service's reference scheduled scan — see `ROADMAP.md`'s "Tech ID Service
— Provider Model") against `ImpressiveLLC/FaceoftheCabin` failed with
`HTTP 403: "You don't have access to a repository this routine uses."`
even with full push access to the repo via git/SSH. Root cause: the
general claude.ai GitHub connector (repo read/search) is a separate
grant from the GitHub App's own per-repo access used by Code
environments/routines — the latter needs explicit approval for an
org-owned repo (`ImpressiveLLC`), which a personal-account connector
doesn't cover. **Resolved**: the org owner opened a regular (non-
scheduled) Code session against the repo from claude.ai, which
succeeded once GitHub access was actually in place and provisioned a
real environment. Routines require `job_config.ccr.environment_id` (or
`self_hosted_runner_pool_id`) — confirmed via a live `400` from the
`RemoteTrigger create` API when a bare repo URL was tried instead — and
that Code session's own chat UUID turned out to double as its
`environment_id`, so no separate lookup UI was needed once the session
existed. The reference routine (`trig_0188XfA9eewXVEoumKr7tmkC`,
monthly, `0 8 1 * *` UTC) is now live using that environment.

**Still open**: the routine's cloud sandbox has repo access but no path
to the Ansible-vaulted `TECH_ID_API_KEY`, so it currently reports
findings via a PR against `docs/ontology.yaml` rather than `POST
/api/tech-id/findings` — hardcoding a real secret into a stored,
remotely-visible trigger definition was rejected as unsafe, and no
secrets/env-injection field was found on the trigger schema during
setup. The prompt checks for `TECH_ID_API_KEY` as a sandbox env var and
POSTs opportunistically if present, but nothing sets it today.

### Camera panel: stale auth looked signed-in, failures rendered as silent blanks (found and fixed 2026-08-03)

An external code review of cabin-ui's Camera Events panel (no code
changed by the reviewer — read-only findings from a real browser
session) found the underlying bugs were in cabin-ui's own error
handling, not the backend or Frigate:

- **Expired Google token treated as authenticated.** `useGoogleAuth`
  stored the access token in `sessionStorage` with no expiry tracking,
  so a genuinely expired token still rendered "signed in" (email shown,
  "Sign out" present) while every authenticated request 401'd and
  callers quietly converted that into an empty list. **Fixed**: token
  storage now includes `expires_in` (with a 30s safety margin); a
  stored token already past that expiry is refused on load, not
  resurrected; and every authenticated call now goes through one
  `authedFetch` helper that clears the session and sets
  `sessionExpired` on any `401`, instead of each caller independently
  swallowing the failure.
- **A dead camera stream rendered as an unexplained blank box.**
  `CameraLiveView`'s `<img>` against Frigate's MJPEG stream had no
  `onLoad`/`onError` handling — a broken stream produced a "completed"
  load event with `naturalWidth`/`naturalHeight` of `0`, which looked
  identical to a working-but-quiet camera. **Fixed**: explicit
  `loading`/`ok`/`error` status, a zero-dimension "load" now counts as
  a failure, an 8s bounded timeout catches a request that never
  resolves either way, and an error state renders "Camera unavailable"
  instead of nothing.
- **The "API offline" badge was checking the wrong thing.** It pinged
  `/actuator/health` directly from the browser, which — unlike every
  business endpoint (`@CrossOrigin`) — has no CORS configuration, so the
  browser blocked reading the response every time regardless of whether
  the backend was actually up. **Fixed**: "connected" is now derived
  from whether the `/api/devices` fetch this panel already depends on
  actually succeeded, rather than a second, separately-broken check.
  Deliberately did not add CORS to Actuator just to drive a status
  badge — that would widen Actuator's exposure for a cosmetic fix.

See `cabin-orchestration-platform/ui/src/App.jsx`'s `useGoogleAuth`,
`CameraLiveView`, and `App()`'s `refreshDevices` for the fixed code —
each carries an inline comment dated the same day explaining the bug.

### Blink camera silently stopped producing frames for hours, no auto-recovery (found and fixed 2026-08-03)

Same external review flagged `/api/events` returning empty and asked
whether Frigate detection, MQTT, or the event adapter were at fault.
Root-caused via `docker exec frigate curl localhost:5000/api/stats`:
both cameras showed `camera_fps: 0.0` — genuinely no incoming video, so
MQTT/Kafka/Postgres (all confirmed healthy) had nothing to carry. Full
detail and fix in "Cameras (Frigate)" above — summary: `blinkbridge`
disabled itself after a transient Blink cloud-API failure and never
retried; `docker restart blinkbridge` recovered it immediately
(`camera_fps` `0.0` → `5.1`). `front_door`'s `0.0` is the separate,
already-documented off-network issue, not part of this incident.

---

## Monitoring

Uptime Kuma + Homepage on the M920q. (Deeper runbook — alert thresholds,
what each check actually monitors — is a known gap; not yet written up
beyond "they're running." A reasonable next addition to this file.)

---

## Incident Response — quick triage

1. **Public site unreachable** — check `docker ps` for `cloudflared`
   first; a crashed tunnel container is the most common single cause.
   `docker restart cloudflared` if it's not running.
2. **A feature "isn't syncing"** — check the browser's Network tab for
   the actual request/response, not just that the UI looks broken. A
   surprising number of real bugs in this project (see Known Issues
   above) looked like application bugs but were actually silent
   network-layer failures invisible to `curl`-based testing.
3. **`cabin-backend` returns 401 unexpectedly** — check
   `GoogleAuthInterceptor`'s OPTIONS bypass is still in place (see Known
   Issues) before assuming it's a token problem.
4. **A container won't start after a config change** — check the
   relevant service's logs first (`docker logs <container> --since 1m`),
   and confirm the config file is valid before assuming the container
   itself is broken.
