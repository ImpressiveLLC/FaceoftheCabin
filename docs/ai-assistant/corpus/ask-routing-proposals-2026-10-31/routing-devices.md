# Routing proposal: devices scope (W-117 corpus)

Corpus text merged in PR #361. For Code to apply to `context-fixtures-r2.json` (both copies). Nothing here edits the map.

Map on `main` has **26** categories (the brief said 27). None of them carries a device, pair, add, remove, heater, offline, camera or plug keyword today. I propose **7 new categories** rather than stretching existing ones, because the existing device categories (`device_identity`, `device_import_lifecycle`) both point at the admin-only, jargon `operations.md#device-onboarding` and would pull that in for household callers' questions. Keep those two as they are.

Simulated with the offline router replica (`tools/route.py`) over all 150 phrasings, map file: (local simulation output, not committed). Result: all 38 C1 to C18 phrasings (55 to 92) route to the intended category below; E11 and E12 (133 to 136) too. Out-of-scope changes: only #24 (A12 "What does Duplicate readings mean?", now to the W-88 section, which is the correct answer per the coverage map) and #119 (E4 "How do I restart the Blink bridge?", now to the camera section, which has that restart). No other phrasing changed route. Earlier keyword drafts stole B5, B6, D2, D6, A6 and E8; those keywords were dropped (`turn on`, `turn off`, `by hand`, `assigned`, `bring back`, bare `blink`).

All sizes are well inside the 6000-character budget (largest: `device_management` 5626).

## Sections (file, heading, anchor, audience, phrasings)

| File | Heading | Anchor | Audience | Phrasings |
|---|---|---|---|---|
| devices.md | Where devices are managed | `where-devices-are-managed` | household | context for all; not routed on its own (see note) |
| devices.md | What Candidates, Available, Assigned, Saved for later and Ignored mean | `what-candidates-available-assigned-saved-for-later-and-ignored-mean` | household | 61, 62, 63, 64, 65, 67 |
| devices.md | Pair a Zigbee device | `pair-a-zigbee-device` | household | 55, 56 |
| devices.md | Scan for WiFi devices at Home | `scan-for-wifi-devices-at-home` | household | 57 |
| devices.md | Register a device manually | `register-a-device-manually` | administrator | 58 |
| devices.md | Import devices from SmartThings or Ring | `import-devices-from-smartthings-or-ring` | administrator | 59, 60 |
| devices.md | Remove a device and bring it back | `remove-a-device-and-bring-it-back` | household | 68, 69, 70 |
| devices.md | Find a device: filters, groups and layout | `find-a-device-filters-groups-and-layout` | household | 66, 67, 74, 75, 76, 77, 78 |
| devices.md | Rename a device or set its room and area | `rename-a-device-or-set-its-room-and-area` | household | 71, 72, 79, 80 |
| devices.md | The Enabled switch | `the-enabled-switch` | household | 81, 82 |
| devices.md | Turn a device on or off from the app | `turn-a-device-on-or-off-from-the-app` | household | 83, 84 |
| devices.md | On the Way: warm up the cabin before you arrive | `on-the-way-warm-up-the-cabin-before-you-arrive` | household | 91, 92 |
| devices.md | Why a device says not responding, late or has a blank card | `why-a-device-says-not-responding-late-or-has-a-blank-card` | household | 87, 88, 133 |
| devices.md | Change how a device shows on Monitoring | `change-how-a-device-shows-on-monitoring` | household | 85, 86 |
| maintenance.md (fixed) | A Zigbee plug or sensor is listed twice, charts twice, or shows power as unknown (W-88, 2026-10-21) | `a-zigbee-plug-or-sensor-is-listed-twice-charts-twice-or-shows-power-as-unknown-w-88-2026-10-21` (unchanged) | administrator (could be household: no secrets; it names admin-side checks) | 73, 89, 90, 134; also 24 |
| maintenance.md (fixed) | A camera is not delivering frames | `a-camera-is-not-delivering-frames` (unchanged) | administrator (host commands) | 135, 136; also 119 |

Household audience rationale: these sections explain what a screen shows and what each control does and does not do; none contains a secret, address or host command. Note for the reviewer: the brief says household members cannot change devices, but the code lets **any signed-in account that may write** change devices (`GoogleAuthInterceptor`: device reads open, every other method needs a write credential; any non-admin Google sign-in is ADULT_HOUSEHOLD_MEMBER; managed "Household member (read/write)" may write; guest, demo and Viewer may not). The guide states the code's behavior. Only platform import (proposals, confirm) is administrator-only in code. If the intent is that household members should not change devices, that is a code gap to raise, not something the guide should claim.

Note: `where-devices-are-managed` is short and general; optionally append it to `device_management` (budget would reach about 6900, over the cap), so I left it unrouted. Map it only if a category has room.

## Proposed categories (append after the current 26)

```json
{"id":"device_add","keywordPatterns":["pair","paired","pairing","add a device","add a new","new zigbee","new zigbee sensor","scan for devices","network scan","printer","tv","register manually","manually","home assistant entity","import","smartthings","ring","bring in"],
 "contextDocuments":[
  {"path":"ai-assistant/user-guide/devices.md","section":"pair-a-zigbee-device","audience":"household"},
  {"path":"ai-assistant/user-guide/devices.md","section":"scan-for-wifi-devices-at-home","audience":"household"},
  {"path":"ai-assistant/user-guide/devices.md","section":"register-a-device-manually","audience":"administrator"},
  {"path":"ai-assistant/user-guide/devices.md","section":"import-devices-from-smartthings-or-ring","audience":"administrator"}]},
{"id":"device_management","keywordPatterns":["use this device","not now","saved for later","available and assigned","new device","showed up","remove a device","removing a device","removed","get back","bring a device back","previously exposed","can't find","cannot find","disappeared","where did my","parent devices only","imported from","smartthings device","ring device","group devices","collapse","filter","delete its history"],
 "contextDocuments":[
  {"path":"ai-assistant/user-guide/devices.md","section":"what-candidates-available-assigned-saved-for-later-and-ignored-mean","audience":"household"},
  {"path":"ai-assistant/user-guide/devices.md","section":"remove-a-device-and-bring-it-back","audience":"household"},
  {"path":"ai-assistant/user-guide/devices.md","section":"find-a-device-filters-groups-and-layout","audience":"household"}]},
{"id":"device_settings","keywordPatterns":["rename a device","renaming a device","display name","room","area","enabled switch","stop using","monitoring page","tile","hide a tile","display override"],
 "contextDocuments":[
  {"path":"ai-assistant/user-guide/devices.md","section":"rename-a-device-or-set-its-room-and-area","audience":"household"},
  {"path":"ai-assistant/user-guide/devices.md","section":"the-enabled-switch","audience":"household"},
  {"path":"ai-assistant/user-guide/devices.md","section":"change-how-a-device-shows-on-monitoring","audience":"household"}]},
{"id":"device_control","keywordPatterns":["heater","turn it on","turn it off","on/off","power button","on the way","warm up","preheat","lock","unlock"],
 "contextDocuments":[
  {"path":"ai-assistant/user-guide/devices.md","section":"turn-a-device-on-or-off-from-the-app","audience":"household"},
  {"path":"ai-assistant/user-guide/devices.md","section":"on-the-way-warm-up-the-cabin-before-you-arrive","audience":"household"}]},
{"id":"device_health","keywordPatterns":["offline","say offline","says offline","not responding","late checking in","blank","last seen","stopped reporting","check-in"],
 "contextDocuments":[
  {"path":"ai-assistant/user-guide/devices.md","section":"why-a-device-says-not-responding-late-or-has-a-blank-card","audience":"household"}]},
{"id":"zigbee_duplicate","keywordPatterns":["listed twice","twice","duplicate","duplicate readings","power reading","power as unknown","no power","zigbee2mqtt","two cards","two lines"],
 "contextDocuments":[
  {"path":"ai-assistant/user-guide/maintenance.md","section":"a-zigbee-plug-or-sensor-is-listed-twice-charts-twice-or-shows-power-as-unknown-w-88-2026-10-21","audience":"administrator"}]},
{"id":"camera_frames","keywordPatterns":["camera","video","no events","frames","frigate","blink camera","blink bridge"],
 "contextDocuments":[
  {"path":"ai-assistant/user-guide/maintenance.md","section":"a-camera-is-not-delivering-frames","audience":"administrator"}]}
```

Each needs `description`, `questions`, `contextNodes` (empty) and `oracleRefs` filled per the map's conventions; I left those to Code.

## Collision notes for whoever merges other scopes

- `sensor` (live_sensors) and `listed`, `power draw` (opportunity_status) are why C9, C15, C16 misrouted before; the proposals win on 2- and 3-word phrases. A household "Why does my sensor say offline?" ties at 1 against `live_sensors` if only `offline` is used, hence `say offline`/`says offline`.
- `heater` alone routes "Can a workflow turn on the heater?" (B5) to `rules_workflows` by declaration order (tie 1 to 1). If the rules scope adds a "workflow cannot turn on the heater" sentence, keep it in the rules section; do not add `turn on` here.
- `lock`/`unlock`, `room`, `area`, `filter`, `camera`, `tile`: none matched any other phrasing in the 150, but they are broad. If another scope's map adds a camera category for Camera Events, merge with `camera_frames` rather than adding a second `camera` keyword.
- `zigbee_duplicate` keyword `duplicate` also catches A12 ("Duplicate readings"). That is the right section today because K19 in operations.md is stale; if the rules/alerts scope fixes K19, decide which section owns "Duplicate readings".
