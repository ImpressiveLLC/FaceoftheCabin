# Home Collector (SMLIGHT SLZB-MR5U + Termux) — Evidence

Reviewed-evidence citation doc, matching `local-history-evidence.md`'s
own pattern: a curated synthesis of an existing project artifact, not a
transcript dump or a new source of operational authority. Every claim
below is reconciled against the cited source, not taken at face value.

## E01 — Coordinator hardware and bring-up mechanism

**Claim:** Home's Zigbee coordinator role is validated on a SMLIGHT
SLZB-MR5U, bridged via Zigbee2MQTT running under Termux on a stock,
non-rooted Android phone (Sony Xperia G3223, Android 8.0), reachable over
a plain TCP socket — no USB-OTG path involved.

**Evidence:** `docs/RUNLOG_2026-09-10_home-collector-mr5u-termux.md`
(Steps 1–4). The device model was confirmed via `adb shell getprop
ro.product.model` (`G3223`), not trusted from Windows' own MTP device
label (which reported the wrong model, "Xperia XA1 Ultra"). The
coordinator's IP was found via a raw mDNS query for `slzb-mr5u.local`,
not IP-range scanning or vendor documentation alone.

## E02 — Real network formed, not just a firmware ping

**Claim:** Zigbee2MQTT under Termux didn't just receive a coordinator
firmware-version response — it formed a real, addressable Zigbee network
(PAN ID 23611, extended PAN ID, channel 11), and a coordinator backup was
written to disk.

**Evidence:** `docs/RUNLOG_2026-09-10_home-collector-mr5u-termux.md`, the
literal startup log quoted in Step 4: `zh:ember: [INIT FORM] New network
formed!`, `z2m: Coordinator firmware version: '{"meta":{...,"revision":
"7.4.2 [GA]",...},"type":"EmberZNet"}'`, `zh:controller: Wrote
coordinator backup to '.../coordinator_backup.json'`. Cross-referenced
against `ROADMAP.md`'s Phase 8 section, which records this as the
condition that retires the Raspberry Pi 4 hardware fallback.

## E03 — Three reusable bring-up gotchas

**Claim:** three non-obvious problems (a corrupted apt mirror, no
Android target for TypeScript 7's native compiler, Zigbee2MQTT's `cli.js`
silently redirecting config to `~/.z2m/`) were found and fixed, and are
general to Termux/Zigbee2MQTT bring-up, not specific to this one device.

**Evidence:** `docs/RUNLOG_2026-09-10_home-collector-mr5u-termux.md`,
Step 4's full narrative — each finding includes the literal command/error
that surfaced it (`Ign:` hash-sum-mismatch lines; `npm view
@typescript/typescript-android-arm64` returning nothing; the
`process.env.ZIGBEE2MQTT_DATA` default in `cli.js` read directly from the
installed package). Also recorded operationally in
`docs/MAINTENANCE.md`'s "Home Location — Android/Termux Collector
Bring-Up" section.

## E04 — What is NOT yet resolved

**Claim:** the coordinator/network result does not by itself make Home a
live collector — MQTT routing from the phone to the M920q is a separate,
still-open item.

**Evidence:** `docs/RUNLOG_2026-09-10_home-collector-mr5u-termux.md`'s own
"Open question, not yet resolved" note (Step 3) and "Next steps" section;
restated in `docs/DEFINITION_OF_DONE.md`'s open-items list (2026-09-10
entry). As of this doc's writing, Tailscale is installed on the
collector phone but not yet signed in/connected (confirmed via `adb`
network-capability inspection showing no active VPN interface) — do not
assert the routing question is closed until that changes and is verified
against a real `mosquitto_sub` or equivalent check on the M920q side.

## Curated KnowledgeNode rows (live, not this doc)

Two `MANUALLY_CURATED` `knowledge_node` rows exist for `entityRef:
home-zigbee-coordinator-mr5u` (`DESCRIPTION`, `TROUBLESHOOTING`), curated
via `POST /api/kb/curate` on 2026-09-10 and live-verified against a real
`POST /api/helpdesk/ask` call (`answeredByModel: true`, both nodes cited
as top sources). This is the first curated `KnowledgeNode` not backed by
a real `DeviceDescriptor` — worth knowing if `KnowledgeNode.java`'s own
comment (which assumes `entityRef` is always a device id) is ever revised.
