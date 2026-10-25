# QA / Testing

## Doc 27 commissioning and Ask acceptance (planned)

Use [hardware acceptance](hardware/doc27-environmental-nodes.md#setup-maintenance-and-acceptance)
and [Ask acceptance examples](governance/handovers/HO-2026-10-16-doc27-poc2.md#ask-and-training-acceptance-examples-review-before-freezing).
Required evidence: exact SKU/count; sensor-source/unit fidelity; no unavailable-to-zero;
parent/service deduplication; wrong-hub denial; manual/restart/load tests; blocked
uncommissioned controls; exact-plan confirmation; no ordered-to-online answer claims.
These are pending acceptance criteria, not a recorded automated or live test pass.

Per-feature test coverage for this repo. Add a section per feature as it
gains test coverage — this is not meant to be exhaustive on day one.

---

## Family Hub: Family Notepad overlay

Spec: [`docs/PRODUCT_NOTES.md`](PRODUCT_NOTES.md) § "2026-07-30 — Family Hub: Family Notepad Overlay".
Code: `family-hub/family-hub.html` (CSS + markup + vanilla JS, no build step).

### Automated coverage

`family-hub/test/run.js` — a Playwright script that serves `family-hub.html`
locally and drives the real page (not a mock). Covers:

- Default state is slid-in (collapsed)
- Slid-out / slid-in widths are read live from `#chores-card` / `#dashboard-fab`
  / `#settings-btn` and match the largest / smallest of those, respectively
- The collapsed handle is fully on-screen and clickable (regression guard —
  an earlier flex/translateX layout bug pushed it entirely off-viewport;
  see git history on this file for the fix)
- Manual slide-out (handle click) and slide-in (chevron) both work
- Composing a note appends it to the list and clears the input
- A new note's default action is to auto-slide the panel out
- 24 hours after a note arrives, the panel force-collapses even if a user
  left it open — independent of any manual toggling in between
- The recent view caps at 12 messages
- Storage caps at 50 messages, oldest dropped first
- The history overlay shows all 50 saved messages
- The "last 50 notes are saved" hint is present
- No JS console errors during the run

**Run it — Docker (recommended, no host dependencies):**

```bash
cd family-hub
docker build -f test/Dockerfile -t family-hub-qa .
docker run --rm family-hub-qa
```

This is the form CI (or any machine — dev laptop, the M920q, a future
runner) should use: everything (Node, Playwright, Chromium, its system
deps) is pinned inside the image, so it's reproducible regardless of host
OS. This is also how we caught and fixed a real layout bug during
development — the host machine's OS wasn't officially supported by
Playwright's local install, which is exactly the kind of drift Docker
removes. Exit code is `0` on all-pass, `1` on any failure — safe to gate a
pipeline on.

**Run it — local Node (faster iteration while developing):**

```bash
cd family-hub
npm install                        # first time only — installs Playwright
npx playwright install chromium    # first time only — downloads the browser
npm test
```

### Manual QA checklist (not automated)

- [ ] Sign-in flow still renders correctly with the notepad panel present
      (the panel sits above the auth overlay by design — same pattern as the
      existing Dashboard/Settings FABs)
- [ ] Visual check across all 7 theme presets (Settings → Theme) — the panel
      uses theme CSS variables (`--glass`, `--gold`, `--teal`, etc.) so it
      should re-skin automatically, but confirm no contrast issues
- [ ] Narrow/kiosk-portrait viewport: panel repositions to bottom-anchored
      per the `@media (max-width: 900px)` rule and doesn't overlap the FABs
- [ ] Real 24-hour wait: leave a note, leave the tab open (or reload after
      the system clock advances 24h+), confirm it slides in on its own —
      the automated test fakes the elapsed time for speed, so this is the
      one behavior worth confirming against a real clock at least once
- [ ] Multi-tab/device check: open the same URL in two browsers, signed in on
      both — note **content** now syncs cross-device via `/api/notes` on
      `cabin-backend` (built 2026-08-01), polled every 20s; confirm a note
      sent on one device appears on the other within that window. The
      panel's open/collapsed **UI state** does not sync and isn't meant to
      — that's still deliberately per-browser (`notepadState`, localStorage
      only). If `CABIN_API_URL` isn't reachable or the visitor isn't signed
      in, notes silently fall back to the old localStorage-only behavior —
      worth checking that fallback explicitly too, not just the happy path.

### Known gaps

- No test yet for the responsive/mobile CSS breakpoint's actual visual
  layout (checked manually via screenshot during development, not asserted
  in `run.js`)
- No CI wiring yet — `npm test` is local-only until the CI/CD to-do in
  `CLAUDE.md` is built

---

## Home Location: Android/Termux Zigbee Collector (SMLIGHT SLZB-MR5U)

Spec: `ROADMAP.md`'s Phase 8 (Accessible Hardware Program).
Full run: [`docs/RUNLOG_2026-09-10_home-collector-mr5u-termux.md`](RUNLOG_2026-09-10_home-collector-mr5u-termux.md).

### Automated coverage

None yet — see Known gaps below.

### Manual QA checklist (bench-verified 2026-09-10, real hardware)

- [x] Coordinator discoverable on the local WiFi via mDNS (`slzb-mr5u.local`)
      when no static IP is known
- [x] TCP port `6638` (Zigbee coordinator socket) and `80` (web UI) both
      reachable from the collector device, not just the LAN generally
- [x] Termux SSH access reachable from a dev machine on the same network
      (key-based, not password) — enables scripted bring-up instead of
      on-device touch-typing
- [x] Zigbee2MQTT starts under Termux and completes a real coordinator
      handshake — `Coordinator firmware version:` line present in the
      startup log, not just a process that stays up
- [x] A real Zigbee network forms (not just a coordinator ping) — confirmed
      via a real PAN ID/extended PAN ID/channel assignment and a
      `coordinator_backup.json` written to disk
- [x] MQTT telemetry actually reaches the M920q's broker via Tailscale —
      **verified end-to-end 2026-09-11**, both client-side (`Connected to
      MQTT server`) and independently on the M920q itself
      (`mosquitto_sub` received the real message). Use the numeric
      Tailscale IP (`100.77.44.113`), not the `cabin-hub` MagicDNS name —
      Termux's own resolver doesn't pick that up even when connected
- [ ] At least one real Zigbee end device paired and visible as a topic
      under `zigbee2mqtt/` on the M920q's broker
- [ ] Survives a phone reboot / Termux process restart without manual
      re-entry of the config (Termux:Boot not yet set up)

### Known gaps

- **No automated smoke test exists.** Every checklist item above was
  verified by hand, once, on one specific phone/coordinator pair. The run
  log's own "Next steps" section proposes collapsing the bring-up into a
  single `provision-collector.sh` — a natural place to add an automated
  pass/fail check once that script exists, rather than inventing test
  infrastructure ahead of it.
- No test coverage for the three dev-gotcha fixes (mirror pin, `npm install`
  vs. clone+build, `~/.z2m/` config path) — they're operational knowledge in
  `docs/MAINTENANCE.md`, not asserted anywhere a regression would be caught.
- Untested: behavior across a Termux/Android update, a Zigbee2MQTT version
  bump, or a different phone model — this pass covered exactly one specific
  hardware/software combination.
## Smart-switch Monitoring release slice — 2026-10-23, PR #247

Scope: physical parent cards and source-qualified electrical values across both locations.
- Native metering parents and enabled HA measurement services use the same W/kWh display. V/A are available under Electrical details.
- Zero is a real reading; absent/unsupported values say unavailable. SONOFF's experimental W/V/A converter does not supply kWh.
- Config/diagnostic entities retain their own records and are not silently used as measured watts.
- IEEE plus location supplies read-only alias grouping. One assigned physical record wins; ambiguous assignments stay unresolved.
- An enabled service under a disabled assigned parent can appear on that parent's grouped card, explicitly labeled. This does not enable the parent or copy disabled candidate readings into an active card.
- Every observation ID, history record, label, enrollment and command route remains unchanged. No relay control is added.
- Values are labeled reported, with acquisition time unverified. Cached HA polls are not fresh-acquisition proof.

Traceability: #244 holds the broader measurement architecture and operator migration/converter work; #246 tracks recovered local work; #247 delivers this bounded UI/backend slice. Persistent identity/alias mutation and an authenticated apply/rollback migration remain separate work under #244, not implied completed by this read-only presentation change.

Regression evidence: source unit conversion and unknown clearing; IEEE rename and assignment ambiguity; location isolation; enabled/disabled enrollment; diagnostics and competing sources; realistic rendered disabled-parent service card. Full suite and deployment results are recorded on #247 at the checked head.


### Follow-up acceptance — PR #248
Live verification found battery temperature sensors classified by voltage/calibration capabilities.
Classification now requires a writable relay state. Monitoring also preserves a known temperature
parent's type even when stale metadata says smartSwitch. Tile chrome is reduced to name, primary
reading and state; source notes and electrical detail text are removed. All ordinary device tiles
open the known same-location parent in Devices by click, Enter or Space. Reorder mode does not
navigate; missing links/cycles do not jump to an arbitrary device. Tests cover these paths.

Cowork review addendum (2026-10-24): classification evidence also excludes lights, locks, covers,
fans, climate controls and water-valve actuators, and detects multi-gang relays (`state_l1`,
`state_l2`). A writable configuration setting no longer infers COMMAND, and voltage infers
POWER_MONITOR only alongside reported power, energy or current. Temperature sensors configured
while the defect was live keep their sticky capabilities until re-detected (W-93). Unresolved
electrical services are no longer summarized on the tile; they remain reachable from the parent
in Devices.

### Regression fix — Sonoff S61 rename and card state (2026-10-24, after #248)
Cause (read live, 2026-10-24): the S61's Z2M friendly name changed to `Smart Switch Zigbee | Dehumidifier`.
The adapter derives the registry id from the friendly name, so it registered a second CANDIDATE row
that received every reading (ON, 263 W) while the assigned, canonical `z2m-0xa4c1381f3884ffff` row stayed
empty (UNKNOWN, no state, no power). The card is built on the canonical row, so power and ON/OFF vanished.
Fix: the adapter resolves a device by IEEE address (legacy IEEE-named id, or one assigned row recording the
address) and keeps that id across a rename; state and availability follow the same mapping. The card also
falls back to a same-identity alias's live values and always shows relay state beside power (`State unknown`
when none is reported; ON at 0 W stays ON; state is never inferred from watts or ONLINE).
Classification: any writable-relay device is `POWER_METER` (S61, in-wall and multi-gang switches), excluding
lights, locks, covers, fans, climate, valves (by definition text or friendly name) and leak/smoke sensors
whose siren has a `state`. A device with no description match is classified from its exposes
(occupancy, contact, temperature, humidity) before falling back to a generic entity; a reed contact no
longer infers ACCESS_CONTROL. Gap: lights, covers, fans and valve actuators have no `DeviceType`, so they
remain generic until the vocabulary adds one.
W-93 dry run (offline, read-only; `ZigbeeRedetectDryRunTest`, run with `-Dredetect.bridge=` `-Dredetect.live=`
`-Dredetect.out=`): proposed changes only for the S61 (generic entity, no COMMAND -> POWER_METER with COMMAND),
the temperature sensors (COMMAND/POWER_MONITOR removed) and the motion/contact sensors (COMMAND, ACCESS_CONTROL,
POWER_MONITOR removed). Nothing was applied; applying needs Nate's approval.

Addendum (2026-10-24, Nate): a Zigbee water-shut-off actuator (Tuya TS0001 `main_water_valve`, a plain "1 gang switch"
to Z2M) now gets its own safety class `WATER_VALVE` (zigbee_main_water_valve in the ontology), recognized by "valve" in
its definition text or friendly name plus a writable relay, so it is never a smart switch or an electrical card. An
unclassifiable Zigbee parent is logged at WARN instead of silently staying generic. UNKNOWN state right after a
backend restart is a populate delay, not a classification fault: Z2M state messages that arrive before
`bridge/devices` are dropped, and a battery sensor does not report again for hours (ONLINE needs a message with
`linkquality`). Restoring last-known state at startup is a separate change. Children of the valve (switch and
power-on-behavior entities) stay `HOME_ASSISTANT_ENTITY`, the service-entity class under a parent.

### W-93 apply and rollback (2026-10-24, not yet applied live)
`POST /api/devices/redetect/preview` (dry run), `POST /api/devices/{id}/redetect/apply` and
`POST /api/devices/{id}/redetect/rollback`, administrator only, covered by the signed-in write gate. Apply takes
`{"fields":["type","capabilities"]}`, changes only approved fields through `DeviceRegistry.replaceConfiguration`
(name, enabled, location and connection are never touched), returns a `rollback` object and publishes a
`DEVICE_REDETECT_APPLIED` event. Rollback uses this process's last apply or the returned object (survives a restart).
Nothing calls these on its own. Applying to live rows needs Nate's approval.
