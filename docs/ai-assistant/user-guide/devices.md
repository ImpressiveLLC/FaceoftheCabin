# Devices: add, review, change and remove

Audience: household members and administrators; each section names its own audience. Source-reviewed at `main` on 2026-10-31 against the app's screens and the backend code named in each Evidence line. Nothing below was run on the hub (`NOT_RUN`), so it describes what the code does, not what a particular hub shows today. Anything that depends on how the hub is set up is marked "host setting".

## Where devices are managed

Audience: household. Open the **Devices** tab. The panel is called Device Manager and has four views: **See** (browse and open a device), **Change** (edit a device), **Add** (bring in a new device) and **Remove** (take a device out of the app). **Refresh** reloads the list. **Advanced (Z2M)** opens the Zigbee2MQTT admin page, which works only from the cabin network or Tailscale.

Looking is open to anyone who can load the page. Changing a device (pairing, saving, enabling, removing, turning on or off) needs a signed-in account that may write: an administrator, any signed-in Google account, or a managed account set to "Household member (read/write)". Guest links, demo links and "Viewer (read-only)" accounts cannot change devices. Importing from SmartThings or Ring is administrator only.

Evidence: `PANELS` and `DM_VIEWS` in [App.jsx](../../../cabin-orchestration-platform/ui/src/App.jsx); [GoogleAuthInterceptor](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/security/GoogleAuthInterceptor.java) (device reads open, every other method needs a write credential); [PlatformImportController](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/PlatformImportController.java).

## What Candidates, Available, Assigned, Saved for later and Ignored mean

Audience: household. Every device is in one of five states. The State filter and the badge on each row use these words.

| State | What it means for you |
|---|---|
| Candidates | Found by an integration (pairing, a scan, Home Assistant, Zigbee2MQTT) and waiting for a person's decision. Looking at it, renaming it or closing the view leaves it a candidate. It is not monitored. |
| Available | Someone chose **Use this device**. It is accepted but not set up: it stays off and unmonitored until Enabled is turned on and saved (Change > Enabled > **Save changes**, or the switch on its row). Saving any other change, such as a new name, makes it Assigned but leaves it off. |
| Assigned | Set up for use. Assigned does not mean switched on: an Assigned device with Enabled off is not monitored and refuses the app's own command buttons (see [The Enabled switch](#the-enabled-switch)). |
| Saved for later | Someone chose **Not now**. Kept for a later decision, hidden from the normal list and from Monitoring, and not checked or commanded. Find it with **Review previously exposed**. |
| Ignored | Someone chose **Ignore**, or removed the device. Hidden the same way, with its data kept. |

**A new device showed up, what do I do?** Open it in See. The "New device candidate" card offers **Use this device** (moves it to Available), **Not now** (Saved for later), **Ignore**, and **Review details without deciding** (opens Change without making a decision). **Recognize this device** looks the device up and proposes a name and type; nothing changes until you confirm. A decision is saved; an undecided candidate found by pairing or scanning is not, so after a hub restart it appears again only when its integration next reports it.

Evidence: `DeviceLifecycleState`, `DeviceLifecycleAction` (ACCEPT to AVAILABLE, DEFER to DEFERRED, IGNORE to IGNORED, REVIEW to CANDIDATE), `DeviceRegistry.applyLifecycleAction` (every decision leaves the device disabled) and `saveConfiguration` (a saved change moves Available to Assigned; enabling a candidate assigns it), `DeviceHealthMonitor.getCheckinDetails`; `LIFECYCLE_LABELS` and `DmDeviceDetail` in [App.jsx](../../../cabin-orchestration-platform/ui/src/App.jsx).

## Pair a Zigbee device

Audience: household (signed in). Devices > **Add** > **Pair a Zigbee device**. Put the device in pairing mode (usually hold its button until the light flashes), then press **Open pairing window**. A countdown of 4 minutes 14 seconds runs, new devices are listed as they appear, and **Stop early** closes the window. If something paired, choose **Add another** or **See all devices**; if not, **Try again**. A newly paired device arrives as a candidate; decide on it as described above.

If the window will not open, the screen says why: you are signed out (sign in under Config), the session is read-only (guest or demo), or the hub could not be reached. The countdown shows only that the hub accepted the request; if Zigbee2MQTT itself is down nothing pairs and the window ends with "No new devices were found". Pairing opens on every Zigbee network the hub is set up for (the cabin's by default, and Home's too if the hub is configured for it, a host setting), whichever location you have selected. It does not set the device up in Home Assistant.

Evidence: `ZigbeePairingFlow`, `pairingRefusedMessage` in [App.jsx](../../../cabin-orchestration-platform/ui/src/App.jsx); `DeviceController.permitJoin` (254 seconds); `Zigbee2MqttAdapter.permitJoin` (sends to every configured bridge, logs and returns if not connected); `CABIN_ZIGBEE_TOPIC_PREFIXES`/`CABIN_ZIGBEE_LOCATIONS` default to the cabin only.

## Scan for WiFi devices at Home

Audience: household (signed in). Devices > **Add** > **Scan for devices** > **Start scan**. It looks for devices that are already on WiFi (smart speakers, TVs, printers, HomeKit or Matter accessories) for 4 minutes 14 seconds. No pairing mode is needed: power the device on and make sure it has joined the WiFi.

What it does not do: it scans **Home's** network only, through the scan app on Home's phone collector, not the cabin's network, whichever location is selected. It needs that phone collector running (host setting). Found devices arrive as Home candidates with a generic type, and nothing is controlled or set up by the scan. The scan screen does not report a refused request, so if nothing is found while you are signed out, sign in and try again.

Evidence: `NetworkScanFlow` in [App.jsx](../../../cabin-orchestration-platform/ui/src/App.jsx); `DeviceController.networkScan`; `MqttBridgeService.handleNetworkScanResult` (registers each result as a Home candidate of type HOME_ASSISTANT_ENTITY, discovered from "Home network scan (mDNS)").

## Register a device manually

Audience: meant for administrators (the app lets any signed-in account that may write use it). Devices > **Add** > **Register manually** adds a device by its id: a Home Assistant entity, an RTSP camera or an MQTT device. Fill in Location, Device ID, Display Name, Type, Protocol Adapter and Connection String, then **Register Device**. Unlike pairing, the device is saved straight away as Assigned and enabled, so the app starts checking it. Registering does not create anything in Home Assistant, the camera or the MQTT broker; the thing named must already exist there. The form does not show an error if the hub refuses the save, so check that the device appears in See.

Evidence: `ManualAddForm` in [App.jsx](../../../cabin-orchestration-platform/ui/src/App.jsx); `DeviceController.registerDevice`; `DeviceRegistry.registerConfiguredDevice` (saves ASSIGNED).

## Import devices from SmartThings or Ring

Audience: administrator. Devices > **Add** > **Import from a connected platform**. An administrator must have connected the SmartThings or Ring account first. Pick the Platform, then **Fetch devices from SmartThings** (or Ring) to ask that service for its device list; **Refresh list** only re-reads what was already fetched. Open a pending device, check Entity ID, Display Name, Type and Location, and press **Confirm as Device**.

Confirming creates a candidate, not a working device: accept it afterward in See. Fetching contacts the provider and saves what it returns, so it is not a harmless check. Provider details and what a confirmed import keeps across restarts are in [Operations: device onboarding](operations.md#device-onboarding).

Evidence: `PlatformImportFlow`, `PendingImportRow` in [App.jsx](../../../cabin-orchestration-platform/ui/src/App.jsx); [PlatformImportController](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/PlatformImportController.java).

## Remove a device and bring it back

Audience: household. **Remove** takes a device out of the app without deleting anything. Devices > **Remove**, select the device, press **Remove this device**, then **Confirm remove**.

What Remove does: the device disappears from Device Manager and Monitoring, is marked Ignored and switched off in the app, and the app stops checking or commanding it. It stays removed even when its integration reports it again. What it does not do: it deletes no history, and it does not unpair the device from Zigbee2MQTT or remove it from Home Assistant, so Home Assistant automations that use it keep running. The Remove screen shows no error if the hub refused (for example when you are signed out), so check that the device is gone.

**Bring it back:** in See, tick **Review previously exposed**. Removed and "Not now" devices are listed. Select the device and choose **Use this device** (it becomes Available and stays off until you turn Enabled on and **Save changes** in Change) or **Return to candidates**.

Evidence: `DmRemoveView` in [App.jsx](../../../cabin-orchestration-platform/ui/src/App.jsx); `DeviceController.removeDevice` (applies IGNORE, no delete); `DeviceRegistry.registerCandidate` (keeps a saved decision on rediscovery); `GET /api/devices/previously-exposed`.

## Find a device: filters, groups and layout

Audience: household. The toolbar in See and Change controls what is listed. Filter choices are remembered in this browser only.

- **Parent devices only** hides a device's services (the Home Assistant entities listed underneath a Zigbee device) and shows only the top-level devices.
- **State** chooses which of Candidates, Available, Assigned, Saved for later and Ignored are listed. The default is Candidates, Available and Assigned. Saved for later and Ignored devices are fetched only while **Review previously exposed** is ticked.
- **Kind** narrows to Battery devices, Wired / mains devices or Services / data.
- **Imported from** appears only when a SmartThings or Ring device exists, and shows one platform's devices.
- **Review previously exposed** lists removed and "Not now" devices, and while ticked it ignores the other filters.
- **Reset Filters** puts Group (Type), Parent devices only, State, Kind, Imported from and Review previously exposed back to their defaults.
- **Group** sorts the list into sections by None, Type, Source, Room, Status, Lifecycle or Workflow. Grouping by Lifecycle always shows every state. **Collapse all** / **Expand all** fold every section. A section holding a device in alarm cannot be collapsed, on purpose.
- In See, **Layout: Side-by-side** / **Layout: Stacked** switches the section layout, and **Reorder** lets you drag devices (press **Done** when finished). Alarm devices stay pinned at the top and Ignored ones at the bottom.

**I can't find a device I added.** Check the State filter first, and tick **Review previously exposed** for a "Not now" or removed device; then Parent devices only, Kind and Imported from, and the selected location. "No devices match this view." means a filter is hiding them; **Reset Filters** clears it.

Evidence: Device Manager toolbar, `LIFECYCLE_FILTER_OPTIONS`, `DEFAULT_LIFECYCLE_FILTER`, `filterDeviceManagerDevices`, `importedFromOptions`, `groupDevices` in [App.jsx](../../../cabin-orchestration-platform/ui/src/App.jsx); `DEVICE_KIND_OPTIONS` in [deviceKinds.js](../../../cabin-orchestration-platform/ui/src/deviceKinds.js).

## Rename a device or set its room and area

Audience: household (signed in). Devices > **Change**, select the device.

- **Display Name**, then **Save changes**, renames it in this app. Workflows follow the device's id, not its name, so renaming does not break them, and the device id never changes. Once you save a name, a later report from Home Assistant or Zigbee2MQTT does not overwrite it. Renaming here does not rename the device in Home Assistant or Zigbee2MQTT. For a Zigbee device with Home Assistant services under it, rename the Zigbee (parent) device: its card and chart use that name.
- **Room**, then **Save changes**, is what Group > Room uses ("Room not assigned" when blank). Clearing it and saving removes it.
- **Area** has its own **Save** button. It is shown on the device's row and is the place Ask's device descriptions use. An area cannot be cleared once set, only changed.
- **Parent device** puts a device underneath another one at the same location.

Saving a change on an Available device assigns it but does not turn it on (only Enabled does); renaming a candidate leaves it a candidate.

Evidence: `DmEditForm`, `DmAreaEditor` in [App.jsx](../../../cabin-orchestration-platform/ui/src/App.jsx); `DeviceController.patchDeviceConfig` and `setArea` (blank area rejected); `DeviceRegistry.mergeDiscoveryDescriptor` (a saved name is kept); `KbGeneratorService.buildDescription` (uses the area).

## The Enabled switch

Audience: household (signed in). Enabled decides whether the app actively uses a device. It is the switch at the end of each row in See and Change, and the Enabled toggle in Change (save with **Save changes**).

- **On**: the app checks the device's check-ins, and the command buttons in its details (Turn on, Turn off, Lock, Unlock) work.
- **Off**: the app stops checking it and those detail buttons are refused, but it keeps the device, its settings and its history. This is the way to stop using a device without removing it. The row's On/Off button goes straight to Home Assistant and is not blocked by Enabled.
- On a candidate, turning Enabled on is a decision to use it: it accepts and assigns the device in one step.

Enabled does not reach Home Assistant or Zigbee2MQTT: the device keeps working there, and Home Assistant automations still use it.

Evidence: `DmRowEnableToggle` ("Enabling accepts and assigns this device") and `DmEditForm` in [App.jsx](../../../cabin-orchestration-platform/ui/src/App.jsx); `DeviceRegistry.saveConfiguration` and `sendCommand` (commands only to an enabled, Assigned device); `DeviceHealthMonitor.checkHealth` (disabled devices get no check-in schedule).

## Turn a device on or off from the app

Audience: household (signed in). **How do I turn the heater on?** Devices > See, find the heater's row and press its **On**/**Off** button. The button goes through Home Assistant and needs you signed in; signed out, a press fails and its tip says "Not changed: Sign in to control devices". It flips at once, then follows what the device reports.

Where on/off buttons appear:
- The row's On/Off button: only on cabin devices that report On or Off as a Home Assistant switch and are Available or Assigned. Valves and locks never get it.
- In a device's details in See, **Turn on** / **Turn off** (or **Open** / **Close** for a cover) appear for Assigned devices that accept commands, and work only while Enabled is on. Other command-capable devices show a note that their type has no one-tap buttons yet.
- Assigned locks show **Lock** and **Unlock** in their details. The screen does not confirm the result; check the lock's state.

**Why is there no on/off button?** The device is at Home, is not a Home Assistant switch, has not reported On or Off yet, is a candidate, Saved for later or Ignored, or is a valve or lock.

**"Timed out waiting for Home Assistant. The command may or may not have been carried out: check the device's state before trying again."** This is the row button's tip when the hub got no answer in time. The command may have run, so check the device before pressing again. "Not changed: Home Assistant didn't accept the command, so it is not known to have run" means it was refused.

The **Turn on** / **Turn off** buttons in See details cannot tell a timeout from a refusal: both show "Action was not accepted. ...". A command that timed out may still have run, so check the device's state before pressing again.

Freeze protection and leak shutoff are Home Assistant automations, not these buttons.

Evidence: `DmPowerToggle`, `powerToggleTarget`, `callHaService`, `DmCapabilityActions`, `DmLockActions` in [App.jsx](../../../cabin-orchestration-platform/ui/src/App.jsx); [HaServiceController](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/HaServiceController.java) (signed in; on/off for switches and on/off helpers only; any id naming a valve or lock refused; a timeout answers "outcome unknown", W-130); detail buttons go through `HomeAssistantAdapter.sendCommand`, which reports a timeout as not accepted.

## On the Way: warm up the cabin before you arrive

Audience: household (signed in). **On the Way** tells the cabin you are coming so Home Assistant can start its preheat. It is on My Places ("Heading to the cabin?"), and in the Devices and Monitoring toolbars. Press it once, signed in.

- "Preheat started. Mech room is ..." means Home Assistant accepted the request, and shows the last mech room temperature (or "not reporting").
- "Preheat: no answer from Home Assistant. It may or may not have started" means the outcome is unknown: check the mech room heater before trying again.
- "Couldn't start preheat: ..." means it did not start; signed out, the reason is "Sign in to control devices".

What it does not do: the app turns on only Home Assistant's "on the way" switch. What gets heated, and any phone notification, is decided by Home Assistant's own preheat automation, which is not part of this app; whether it is set up on the hub is not verified here. The app never turns the heater off afterward. To turn the heater on or off directly, use its On/Off button (see above).

Evidence: `OnTheWayButton`, `mechRoomTemperature` and the My Places "On the Way" card in [App.jsx](../../../cabin-orchestration-platform/ui/src/App.jsx); [HaServiceController](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/HaServiceController.java); W-28 in the [backlog](../../governance/backlog.md); DL-2026-10-02-04 in the [discrepancy log](../../governance/discrepancy-log.md) (the Home Assistant side is unconfirmed).

## Why a device says not responding, late or has a blank card

Audience: household. The badge on a device comes from its check-ins, not from a live test. Open the device in See: **Last seen** shows its last report, and "Why is this status shown?" gives the reason and how long the app waits.

| Badge | Meaning |
|---|---|
| No check-in schedule | The app is not watching it: a candidate, Available, or Enabled is off. |
| Late checking in | Its expected report has not arrived yet. This does not prove it is offline. |
| Not responding | No report for three times its usual interval (for Home Assistant devices, also a direct check failed). Its state then reads OFFLINE. |

Saved for later and Ignored devices show no check-in badge at all, only their raw state.

Usual intervals: mains-powered Zigbee devices 10 minutes, battery Zigbee sensors 26 hours (they sleep between reports), cameras 5 minutes; thermostats, locks, smoke and CO alarms, appliances (dishwasher, washer, dryer) and power meters 15 minutes; everything else 30 minutes. A device can also carry its own expected interval, which replaces these. A sensor that only reports when something changes (the CO sensor) is never marked late for being quiet.

For a blank card, a card listed twice or power shown as unknown, see [A Zigbee plug or sensor is listed twice](maintenance.md#a-zigbee-plug-or-sensor-is-listed-twice-charts-twice-or-shows-power-as-unknown-w-88-2026-10-21).

Evidence: `checkinStatusLabel`, `DmDeviceDetail` in [App.jsx](../../../cabin-orchestration-platform/ui/src/App.jsx); [DeviceHealthMonitor](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/devices/DeviceHealthMonitor.java) (`staleThresholdFor`: per-device `expectedCheckinMinutes`, then Zigbee mains/battery, then by type; MISSED_MULTIPLIER 3; event-driven exemption; only visible devices are checked, so Saved for later and Ignored get no check-in status).

## Change how a device shows on Monitoring

Audience: household (signed in). Monitoring > **Change**, select a device. You can set a **Custom display name**, a **Severity override** (Default, OK, Warn or Alert, which sets the tile's color) and **State label overrides** (show a raw status such as ONLINE as your own word), then **Save overrides**.

These settings apply to the current presence profile only (At Home, At Cabin, Away or Both Occupied, shown above the list), so set them again for each profile you want. Monitoring > **Remove** clears a device's overrides for that profile; it does not remove the device. The overrides change only how the tile looks: they do not change alerts, Status Checks or the device itself. The form says "Saved" even if the save failed (for example when signed out), so check the tile.

**Can I hide a tile?** There is no hide option on Monitoring. Removing the device in Devices takes it off Monitoring, as described in [Remove a device](#remove-a-device-and-bring-it-back).

Evidence: `MN_VIEWS`, `MnChangeView`, `MnRemoveView`, `DisplayConfigForm` (errors ignored), `PROFILE_LABELS` in [App.jsx](../../../cabin-orchestration-platform/ui/src/App.jsx); `DeviceController` display-config routes (the backend does not use the severity override for alerts).
