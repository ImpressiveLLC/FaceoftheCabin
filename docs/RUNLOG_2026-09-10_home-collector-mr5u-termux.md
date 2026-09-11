# Run Log — MR5U + Termux Home Collector Bring-Up (2026-09-10)

> **Result: PASS — full end-to-end, not just the coordinator half.**
> Termux on a stock, non-rooted Android 8.0 phone formed a real Zigbee
> network against the SMLIGHT SLZB-MR5U over a plain TCP socket
> (`Coordinator firmware version: EmberZNet 7.4.2 [GA]`, network formed
> on PAN ID 23611 / channel 11, coordinator backup written to disk), and
> — as of 2026-09-11, ~30 minutes after Tailscale sign-in completed on
> the phone — its Zigbee2MQTT MQTT traffic reached the M920q's real
> broker for real: `Connected to MQTT server` / `Zigbee2MQTT started!`
> in the phone's own log, independently confirmed by subscribing on the
> M920q itself (`mosquitto_sub -t zigbee2mqtt/bridge/state` returned the
> real `{"state":"online"}` message, not just trusting the client side).
> This answers `ROADMAP.md`'s Phase 8 open question completely — Termux
> is viable for this role, the Pi 4 fallback is not needed, and there is
> no longer an open MQTT-routing question. See "Step 5 — MQTT routing
> resolved" below for the exact fix (numeric Tailscale IP, not the
> `cabin-hub` MagicDNS name — Termux's own resolver didn't pick that up).
>
> Companion to `docs/POC_2026-08-08_termux-zigbee-collector.md` (the
> original playbook) and `ROADMAP.md`'s Phase 8. That playbook says
> "capture the real result" — this is that capture, kept as a literal,
> replayable log (not a summary) specifically so the same bring-up can
> be repeated on a fresh phone/tablet/Pi later, ideally reduced to a
> single script on an SD card. Every command below actually ran; nothing
> here is a guess at what *should* work.
>
> **Hardware confirmed:** SMLIGHT SLZB-MR5U (network-attached, dual
> EFR32MG24 radios) — matches `ROADMAP.md`'s Phase 8 final pick, not a
> Sonoff device. The physical unit's packaging insert is generic
> SMLIGHT SLZB-06/06M documentation (shared across their product line),
> which caused a momentary identity mix-up — the device's own mDNS
> hostname (`slzb-mr5u.local`) settled it.
>
> **Collector device:** Sony Xperia, real model **G3223** (Xperia XZ1
> Compact) confirmed via `adb shell getprop ro.product.model` — Windows'
> MTP driver mislabeled it "Xperia XA1 Ultra" in Device Manager; that
> label is wrong and should not be trusted for this device again.
> Android 8.0.0. `com.termux` + `com.termux.api` both pre-installed.
>
> **Network:** Both devices on **`Unicorn Ping-Pong`** (2.4 GHz, the
> home WiFi). A second SSID, **`Unicorn Ping-Pong_5GHz`**, is confirmed
> live and in normal use by other devices (per Nate directly) — neither
> this PC nor the phone happened to be associated with it during this
> session, so it's untested for this specific bring-up, not broken.

---

## Step 1 — Identify the MR5U on the LAN

**Dead end, logged so it isn't retried:** the MR5U was first connected
via Ethernet through **ilikethelights' own dock** (a Lenovo USB-C hub),
using the PC as a passthrough. This produced only a self-assigned
APIPA address (`169.254.x.x`) on the PC's `Ethernet 2` adapter with no
DHCP or ICS bridging active — the MR5U was never actually reachable
this way, and no cross-verification is expected to work if this
shortcut is tried again in future. **Fix: always give the coordinator
its own direct Ethernet drop to the router**, not a PC-passthrough link,
unless ICS is deliberately configured first.

Once moved to a direct router port:

```powershell
# From ilikethelights (PowerShell) — confirm own WiFi/LAN identity first
netsh wlan show interfaces
# SSID: Unicorn Ping-Pong, IPv4 192.168.1.111/24, gateway 192.168.1.1
```

```bash
# ARP + full port-80 subnet sweep did NOT find it by IP guessing —
# logged as a dead end, not a recommended discovery method for next time.
arp -a | grep "192.168.1\."
for i in $(seq 1 254); do
  (timeout 1 bash -c "echo >/dev/tcp/192.168.1.$i/80" 2>/dev/null && echo "192.168.1.$i:80 OPEN") &
done; wait
```

**What actually worked — raw mDNS query.** Windows' `Resolve-DnsName`
cannot resolve third-party mDNS responders reliably; a hand-built mDNS
UDP multicast query does. Script used (kept for reuse):

```python
# mdns_query.py — sends a raw mDNS A-record query to 224.0.0.251:5353
# and parses any A records back. See this repo's scratchpad session for
# the full ~60-line script if it needs recreating; the call that worked:
python mdns_query.py slzb-mr5u.local
# => response from ('192.168.1.142', 5353), possible A records: ['192.168.1.142']
```

**Verified at `192.168.1.142`:**
```bash
ping -n 2 -w 1000 192.168.1.142                 # 3ms RTT, TTL=64
timeout 3 bash -c "echo >/dev/tcp/192.168.1.142/6638" && echo OPEN   # Zigbee coordinator socket — OPEN
timeout 3 bash -c "echo >/dev/tcp/192.168.1.142/80" && echo OPEN     # web UI — OPEN
curl -s --compressed http://192.168.1.142/ | head -c 500  # confirmed genuine SMLIGHT firmware (smPreloader/smCircle CSS)
```

**Takeaway for next time:** don't bother scanning by IP or guessing
Ethernet-passthrough config. Go straight to `mdns://slzb-mr5u` (or
whatever `slzb-<model>` applies to the unit in hand) the moment it's on
a real router port. This matches the vendor's own documented `mdns://`
support (from the box's packaging insert, SLZB-06 family docs).

---

## Step 2 — Termux SSH access (so commands run from ilikethelights, not a phone keyboard)

**Dead end, logged so it isn't retried:** password-based SSH auth was
set up first (`passwd` in Termux) but abandoned — the phone has no
physical/Bluetooth keyboard, and the plan needs repeatable, pasteable
commands, not on-device typing.

**Also a dead end:** the first key-based attempt failed because the
68-character public key was hand-typed into Termux's on-screen keyboard
and got mistyped (`l`/`1` ambiguity in the base64 string) — `Permission
denied (publickey,password,keyboard-interactive)`. **Never hand-type a
key into a touchscreen again; use the ADB file-push method below.**

**What actually worked:**

```bash
# In Termux on the phone:
pkg update -y && pkg install -y openssh
whoami            # u0_a220 — this is the SSH username, varies per install
sshd              # starts on port 8022 (not 22)
```

```bash
# From ilikethelights — generate a dedicated keypair
ssh-keygen -t ed25519 -f "$HOME/.ssh/mr5u_termux_ed25519" -N "" -C "ilikethelights-mr5u-poc"
```

```bash
# Push the PUBLIC key file over USB (adb), bypassing on-screen typing entirely.
# NOTE the double-leading-slash on the remote path — MSYS/Git-Bash on Windows
# otherwise mangles "/sdcard/..." into a Windows path and the push silently fails.
adb shell pm grant com.termux android.permission.READ_EXTERNAL_STORAGE
adb shell pm grant com.termux android.permission.WRITE_EXTERNAL_STORAGE
adb push "$HOME/.ssh/mr5u_termux_ed25519.pub" //sdcard/Download/mr5u_id.pub
```

```bash
# In Termux — one short line instead of retyping the whole key:
cp /sdcard/Download/mr5u_id.pub ~/.ssh/authorized_keys && chmod 700 ~/.ssh && chmod 600 ~/.ssh/authorized_keys
```

```bash
# From ilikethelights — now works:
ssh -i "$HOME/.ssh/mr5u_termux_ed25519" -p 8022 u0_a220@192.168.1.127
```

**Takeaway for next time:** on any *fresh* phone/tablet, the exact
sequence is: install openssh + start sshd → generate (or reuse) the
keypair on the PC side → `adb push` the `.pub` file to `/sdcard/Download/`
→ one `cp` + `chmod` line in Termux → SSH in. Skip the password/typing
detour entirely — it never needs to happen again.

---

## Step 3 — Confirm both halves of connectivity, from the phone itself (not just the PC)

```bash
SSH="ssh -i $HOME/.ssh/mr5u_termux_ed25519 -p 8022 u0_a220@192.168.1.127"

$SSH 'termux-wifi-connectioninfo'
# ssid: "Unicorn Ping-Pong", rssi -47, ip 192.168.1.127

$SSH 'ping -c 2 -W 2 192.168.1.142; (echo > /dev/tcp/192.168.1.142/6638 && echo OPEN)'
# both succeeded from the phone's own network stack

$SSH 'curl -s -o /dev/null -w "cabin: HTTP %{http_code}\n" https://cabin.unicornpingpong.com/'
$SSH 'curl -s -o /dev/null -w "api: HTTP %{http_code}\n" https://api.unicornpingpong.com/actuator/health'
# both HTTP 200
```

**Result: local-LAN reachability to the MR5U, and internet reachability
to the M920q's public endpoints, are both confirmed from the collector
device itself.** This is the literal go/no-go signal the original ask
was after, before any Zigbee2MQTT install work started.

**Open question at the time — resolved, see Step 5 below:** the
*documented* routing path (`docs/POC_2026-08-08_termux-zigbee-collector.md`
Phase 5) uses **Tailscale** to reach `cabin-hub:1883` (the M920q's
Mosquitto broker directly), not the public HTTPS domain. Confirming
`cabin.unicornpingpong.com` returns 200 proved the *web* path was open;
it did **not** prove MQTT (port 1883) was reachable that way, since
Cloudflare Tunnel typically only proxies HTTP(S)/WebSocket unless
explicitly configured otherwise. Tailscale was not yet installed on this
phone at this point in the session.

---

## Step 4 — Install Zigbee2MQTT (complete — PASS)

```bash
$SSH 'pkg update -y'
$SSH 'pkg install -y nodejs git'
```

**Deviation from the documented playbook, logged not silently
followed:** the playbook specifies `nodejs-lts`; this run used plain
`nodejs` (Termux's current/latest build, resolved to **v26.4.0** —
very new, not an LTS line) because it was already in-flight before the
mismatch was noticed. Zigbee2MQTT's own `package.json` historically
pins an `engines` range to an LTS major, and its `serialport` dependency
ships prebuilt native bindings per Node ABI version — real risk that
`npm ci` either warns/fails on the engine check or can't find a
prebuilt binding for such a new Node major on aarch64/Android and falls
back to a from-source compile (slow, and needs a C toolchain Termux may
not have by default). **Watch for this specifically** when running
`npm ci` below; if it fails on either front, `pkg install nodejs-lts`
and repoint at that instead of debugging the current build further.

**Real, hard-hitting problem — the auto-selected mirror serves corrupt
files.** `pkg update`/`pkg install` picked `linux.domainesia.com`
(Indonesia) with no explicit mirror chosen, and it silently corrupted
the two largest packages: `apt` printed `Ign:` lines for `libicu`
(10.2 MB) and `nodejs` (10.3 MB) — a checksum/hash-sum failure, not a
slow download — while smaller packages fetched fine. This looked like
a hang at first (no error, just no progress) and wasted real time. Also
learned the hard way: `pkill -f "apt install"` over a live SSH session
can flip the SSH command's own exit code to something unrelated (255)
even though it worked — check with a fresh, separate SSH call
afterward, don't trust that exit code.

**Fix, confirmed working — pin the official Termux CDN mirror
explicitly** instead of trusting auto-selection:
```bash
echo "deb https://packages.termux.dev/apt/termux-main stable main" > \
  /data/data/com.termux/files/usr/etc/apt/sources.list
apt clean
pkg update -y && pkg install -y nodejs git
```
`packages.termux.dev` itself load-balances across a pool (this run
landed on a Cloudflare-backed edge, `packages-cf.termux.dev`, after
briefly probing a couple of dead candidates) — that's normal and fine;
the point is avoiding the single fixed regional mirror `pkg` picks by
default on first run. **Do this mirror pin as step zero on any future
device**, before the very first `pkg install`, to skip this whole
detour.

Confirmed installed: `node v26.4.0`, `npm 11.19.1`, `git 2.55.0`.

**Second real deviation from the playbook, more serious than the
`nodejs-lts` one:** `npm ci` failed outright (`EUSAGE`) because this
repo has no committed `package-lock.json`/`npm-shrinkwrap.json` — `npm
ci` requires one and refuses to generate it. Substituted `npm install`
(228 packages, ~2 min, non-fatal `EBADENGINE` warning as anticipated
above). Two packages' install scripts were skipped by npm's newer
script-safety gate (`@serialport/bindings-cpp`, `esbuild`) — approved
both with `npm install-scripts approve <pkg>`, and confirmed neither
actually needed a native compile: `@serialport/bindings-cpp` ships a
prebuilt `prebuilds/android-arm64/*.node` binary already, so no C
toolchain (clang/make/python — none of which are installed) was ever
required. Good news to carry forward: **serialport's native binding is
not a blocker on Android/Termux**, contrary to what the original
playbook worried about for the USB path.

**The actual, hard blocker — TypeScript 7's native compiler has no
Android target at all.** `npm run build` (`tsc && node index.js
writehash`) failed immediately:
```
Error: Unable to resolve @typescript/typescript-android-arm64. Either
your platform is unsupported, or you are missing the package on disk.
```
Confirmed this isn't a fixable install-order/config issue — TypeScript
7.0.2's own `optionalDependencies` list (`node_modules/typescript/package.json`)
covers win32/linux/darwin/aix/freebsd/netbsd/openbsd/sunos across every
architecture, but **no `android` entry exists at all**, and `npm view
@typescript/typescript-android-arm64` returns nothing — the package
doesn't exist in the registry to install even manually. `process.platform`
correctly reports `android` on this Node build, which is exactly why
npm's optional-dependency platform matching silently skips every
android-targeted variant (there are none) instead of falling back to
`linux-arm64` (a different platform tuple, not eligible by npm's own
matching rules regardless of underlying kernel similarity).

**Workaround found — skip local compilation entirely.** `zigbee2mqtt`
is published to the npm registry as a normal package
(`npm view zigbee2mqtt version` → `2.14.1`, matching the git clone's
version), which ships pre-built JS rather than raw TypeScript source —
consumers installing via `npm install zigbee2mqtt` were never expected
to run `tsc` themselves. Switched from "clone the repo + build locally"
to "`npm install zigbee2mqtt` directly" to sidestep the whole native-
TypeScript-compiler gap. **Carry this forward as the correct method for
any future device**, not the git-clone-and-build path the original
playbook's Phase 4 assumed — that path is a real, confirmed dead end on
Termux specifically because of TypeScript 7's platform support, not
anything specific to this phone or this Zigbee2MQTT version.

**Third real deviation, the actual root cause of a long-running mystery
— `cli.js` silently redirects to a completely different data
directory.** After fixing the config, launching still failed with the
exact same USB-fallback error, even though the config file being
edited (`node_modules/zigbee2mqtt/data/configuration.yaml`) visibly had
the correct `serial.port`/`adapter` values. Confirmed via direct,
isolated tests that every individual layer was correct in isolation —
the JSON schema accepted the values, `objectAssignDeep` merged them
correctly, even the exact `yaml.read()` call the app uses returned them
correctly when tested standalone. The actual cause, found only by
reading `cli.js` itself:
```js
process.env.ZIGBEE2MQTT_DATA = process.env.ZIGBEE2MQTT_DATA || path.join(process.env.HOME, ".z2m");
```
The real, live config the running process reads is **`~/.z2m/configuration.yaml`**,
not `<package>/data/configuration.yaml` — a `~/.z2m/` directory had been
silently auto-created on the *very first* launch attempt (the one that
hit the onboarding wizard) via `settings.writeMinimalDefaults()`, with
an empty `serial: {}` and `onboarding: true`. Every edit made to the
package's own `data/` folder before this was correctly written but
never once read by the running app. **Carry forward for any future
device: always edit `~/.z2m/configuration.yaml`, never the package's
bundled `data/configuration.yaml.example` location, unless
`ZIGBEE2MQTT_DATA` is explicitly set otherwise.**

**Phase 4 result: PASS — full go signal, not just a firmware ping.**
After fixing the real config file and removing the leftover
`onboarding: true` flag:
```
[info] zh:ember: Adapter version info: {"ezsp":13,"revision":"7.4.2 [GA]",...}
[info] zh:ember: [INIT FORM] New network formed! {"panId":23611,"extendedPanId":[...],"radioChannel":11,...}
[info] zh:controller: Wrote coordinator backup to '/data/data/com.termux/files/home/.z2m/coordinator_backup.json'
[info] z2m: zigbee-herdsman started (reset)
[info] z2m: Coordinator firmware version: '{"meta":{...,"revision":"7.4.2 [GA]",...},"type":"EmberZNet"}'
[info] z2m: Currently 0 devices are joined.
```
Termux on this Android 8.0 Sony Xperia (G3223) didn't just see the
coordinator respond — it actually **formed a real Zigbee network**
against the MR5U over a plain TCP socket. The process then exited
cleanly (not a crash) on `MQTT failed to connect ... ECONNREFUSED
127.0.0.1:1883` — expected, since `mqtt.server` was deliberately left
pointed at a non-existent local broker per the original playbook's own
"doesn't need to be real yet for this test" guidance. At this point in
the session, MQTT routing to the M920q was the one remaining open item —
**resolved same session, see Step 5.**

---

## Step 5 — MQTT routing resolved: Tailscale confirmed, full end-to-end PASS (2026-09-11)

Tailscale (installed earlier via `adb install` of the official
`tailscale-android` GitHub release APK) was signed in on the phone by
Nate directly — the one step that genuinely required physical
interaction (OAuth/SSO consent), not scriptable from `ilikethelights`.

**One real gotcha, worth carrying forward:** `cabin-hub` (the documented
MagicDNS hostname) does **not** resolve from Termux's own shell, even
with Tailscale actively connected and routing:
```bash
$SSH 'ping -c 3 cabin-hub'   # ping: unknown host cabin-hub
```
Termux appears not to pick up Android's system/VPN-provided DNS resolver
for MagicDNS names specifically (the numeric IP works fine — this is a
Termux/Android resolver quirk, not a Tailscale connectivity problem).
**Fix: use the M920q's numeric Tailscale IP directly** — confirmed via
`README.md`'s own Infrastructure Quick-Reference table (`100.77.44.113`):
```bash
$SSH 'ping -c 3 100.77.44.113'
# 64 bytes from 100.77.44.113: icmp_seq=1 ttl=64 time=107 ms   (real tailnet hop, ttl=64 — not an internet route)
$SSH '(echo > /dev/tcp/100.77.44.113/1883 && echo "1883 OPEN")'
# 1883 OPEN
```

**Wired and verified end-to-end:**
```bash
$SSH 'sed -i "s|server: mqtt://localhost:1883|server: mqtt://100.77.44.113:1883|" ~/.z2m/configuration.yaml'
# kill + relaunch Zigbee2MQTT (same Z2M_ONBOARD_NO_SERVER=true launch as Step 4)
```
```
[info] zh:ember: [INIT TC] Adapter network matches config.
[info] z2m: zigbee-herdsman started (resumed)          <- "resumed", not "reset" -- recognized the existing network from the Step 4 backup
[info] z2m: Coordinator firmware version: '{"meta":{...,"revision":"7.4.2 [GA]",...},"type":"EmberZNet"}'
[info] z2m: Connecting to MQTT server at mqtt://100.77.44.113:1883
[info] z2m: Connected to MQTT server
[info] z2m:mqtt: MQTT publish: topic 'zigbee2mqtt/bridge/state', payload '{"state":"online"}'
[info] z2m: Zigbee2MQTT started!
```

**Independently confirmed from the M920q's own broker**, not just
trusting the phone's client-side log:
```bash
ssh nate@100.77.44.113 'mosquitto_sub -h localhost -p 1883 -t "zigbee2mqtt/bridge/state" -C 1'
# {"state":"online"}
```

**This closes the loop completely.** Every open item from Step 3/Phase 4
is now resolved: coordinator handshake, real Zigbee network formation,
and MQTT delivery to the M920q's actual broker, all real and
independently verified from both ends. Nothing about this setup is
theoretical or "should work" anymore.

---

## Next steps toward "plug and play on an SD card"

The full collector path (coordinator → Zigbee network → MQTT → M920q) is
now proven end-to-end. Remaining, recorded here as the concrete target,
per Nate's explicit ask to invest the logging effort now:

1. **Collapse Steps 1–5 above into one script** (`provision-collector.sh`)
   that a fresh phone/tablet/Pi could run after nothing more than: install
   Termux + Termux:API + Termux:Boot, grant storage permission, and run
   one command referencing a pre-generated keypair. The MR5U-discovery
   step (mDNS query) and the SSH-bootstrap step (ADB key push) are
   already fully scriptable from what's logged above — the coordinator's
   own IP/model name would be the only per-device variable. Bake in the
   two real fixes Step 5 found: pin the numeric Tailscale IP for
   `mqtt.server` (not the `cabin-hub` MagicDNS name, which Termux's own
   resolver doesn't pick up), and expect a "reset" vs. "resumed" log line
   depending on whether a coordinator backup already exists.
2. **Termux:Boot integration** (Phase 7 of the original playbook) so the
   script re-runs automatically on power-on, which is the actual
   "plug the SD card in and it just launches" behavior being asked for
   — SD-card portability itself is an Android storage/OS question
   (moving Termux's app data, not just files, to removable media),
   worth a separate feasibility check before assuming it's possible on
   stock Android 8.
