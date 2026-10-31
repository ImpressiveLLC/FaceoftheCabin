# Alerts, Status Checks and phone notifications

Audience: household members and administrators; each section names its audience. Evidence: source on `main` read on 2026-10-31 (app screens in [App.jsx](../../../cabin-orchestration-platform/ui/src/App.jsx), backend controllers and services named in each section). Behavior on the live hub was not re-checked; anything that depends on how the hub is set up is marked as host-dependent.

## Status Checks: what needs attention right now

Audience: household.

**Short answer.** Open **Rules & Alerts** in the left rail. The **Status Checks** card (the first card unless someone has dragged the cards into another order) is the one list of things that need a look at the location chosen in the toolbar. Critical items are listed first, then the newest. The banner at the top of Devices, Monitoring and Rules & Alerts counts the same list, and pressing it opens Rules & Alerts.

What can appear in the list:

- A device reporting an alarm, such as a leak sensor that is wet (critical). It stays while the device still reports it.
- A device that missed its check-in window (warning). Only devices that are both enabled and assigned are watched.
- Alerts from the backend's own rules, from workflows (including "Send a critical notification" steps and a valve that did not confirm a command), and disk-space warnings. These stay in the list for 24 hours after they happen, up to the 20 newest, and you must be signed in to see them.

Press **See more** on an item to see its See, Think and Act steps. If the same condition happened more than once, it is one row with "Seen N times" and the times underneath. While any item is critical, the card cannot be collapsed.

When the card says "No status checks need attention", nothing is waiting, unless it also says some items are silenced by you (see "Ignore, Remind me in and Restore" below).

Evidence: `StatusChecksCard`, `mergeStatusCheckItems`, `useAutomationAlerts` (24-hour window, limit 20, signed-in fetch) and `AlertControls` in App.jsx; [ActiveAlertService](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/alerts/ActiveAlertService.java) (DEVICE_ALARM critical, MISSED_CHECKIN warning, enabled and assigned devices only).

## Alert levels and the marks on the tabs

Audience: household.

**Short answer.** Status Checks items are critical or warning (a few notices, such as a disk that is back under its limit, are information only). A warning puts a small dot on the **Devices**, **Monitoring** and **Rules & Alerts** tabs. A critical item does not add a dot: it turns the tab's icon into a pulsing warning triangle, colors the label and adds a bar on its edge, in the theme's danger color. Hovering a tab says "attention needed" or "CRITICAL".

**What counts as critical?** A device reporting a water leak, smoke or an alarm; from the backend rules, a freeze risk, a safety alarm, and low water pressure or an unlocked lock while nobody is there; a workflow's "Send a critical notification" step; a valve command that was not confirmed; and the critical disk-space level.

**Why did a door left open not alert me?** Every device message is graded too: leak, smoke or alarm is critical; tamper, low battery or a door contact open is a warning; everything else is information only. Warning-level device messages are kept in the event history, but they do not add a Status Checks item and are never sent to a phone. To be told about a door, a workflow is needed with the trigger "Door contact opened" and the step "Send a critical notification".

The tab marks follow the Status Checks list exactly: silenced items, and items for the location not selected, do not light a tab. An ignored critical item is the exception: it stays in the list and still lights the tab.

Evidence: `navAlertLevelsFor`, `alertLevelFor`, `NavRail` in App.jsx and `.nav-critical`, `.nav-badge-warn` in styles.css; [AlertSeverityClassifier](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/events/AlertSeverityClassifier.java); [AutomationRuleService](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/automation/AutomationRuleService.java); `WORKFLOW_ACTION` and `WORKFLOW_UNCONFIRMED` in [WorkflowRuleService](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/workflow/WorkflowRuleService.java) (both critical).

## Ignore, Remind me in and Restore

Audience: household.

**Short answer.** Press **See more** on the item, then **Ignore for now** or **Remind me in** and a time. Both only hide the item. They do not fix anything, change the device, stop Home Assistant or Node-RED automations, or stop a phone notification.

- **Ignore for now** hides the item until someone restores it. It has no end time, so it also hides the same alert the next time it happens.
- **Remind me in** offers 1 hour, 1 day, 1 week or 1 month. After that time the item comes back if the condition is still there.
- **A critical item, such as a water leak, can only be reminded in 1 hour.** It has no Ignore for now, and it stays visible even if it was ignored before. This is a safety rule: the list must never hide the thing it exists to show.

Silencing is saved on the hub, so it hides the item for everyone, in the banner and on the tab marks as well. It needs a signed-in account; Viewer accounts, guest links and demo links cannot do it.

**Where did the alert I ignored go? How do I bring it back?** At the bottom of Status Checks, press **N silenced** (N is a number). Each entry shows "Ignored" or "Snoozed until" a time, and "not currently active" if the condition has cleared. Press **Restore** to bring it back at once. Restore an ignored entry marked "not currently active" when you want to hear about that alert again, because the ignore would hide its next occurrence.

Evidence: `ignoreAlert`, `snoozeAlert`, `restoreAlert`, `SNOOZE_DURATIONS`, `CRITICAL_SNOOZE_DURATIONS`, `silencedStatusChecks` in App.jsx; [AlertController](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/AlertController.java) (writes signed-in only, reads open); [NtfyAlertPublisher](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/events/NtfyAlertPublisher.java) does not consult silencing.

## Open device from an alert

Audience: household.

**Short answer.** Press **See more** on a Status Checks item, then **Open device**. The app switches to **Devices** and selects that device. If your saved filters would hide it, the filters are reset to show everything, and a collapsed group holding it is expanded. If the device is not in the list for this location, a note says so, with **Dismiss**. Open device changes nothing on the device itself.

A disk-space alert for the storage disk (the one that holds backups) shows the **Trim old backups** control here instead of Open device. The alert for the other disk, the system disk, still shows Open device, which only leads to a note that it is not in the device list. Other items that are not about one device have no Open device button.

Evidence: `openDevice` and `STORAGE_HDD_SOURCE` in `StatusChecksCard` and the `pendingDeviceFocus` effect in `DeviceManagerPanel`, App.jsx (2026-10-12 change); `sourceId` in [StorageMonitorService](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/storage/StorageMonitorService.java) (`storage-hdd`, `root-nvme`).

## Phone notifications: what reaches your phone today

Audience: household.

**Short answer.** Do not count on a phone notification from this app. The app sends phone notifications only when an administrator has set its notification channel on the hub. On 2026-10-12 that setting was found blank on the cabin hub, so nothing from the app reached a phone, not even for a leak (backlog W-51). Nothing on `main` records it being set since. Whether it is set now is a host setting and was not checked.

When the channel is set, the app sends every critical event (for example a leak, smoke or alarm report, a freeze risk, or a workflow's "Send a critical notification" step, including camera **Notify me**) and a few warnings marked for it, such as disk space. Door, low-battery and tamper warnings are never sent.

Separate paths, each set up outside this app:

- Home Assistant can send its own notifications through its Companion app. Which of its automations do is configured in Home Assistant and is not verified here.
- Node-RED's overnight camera alert sends one only when a camera makes a detection while the cabin is armed away and the one person it tracks shows as away.
- The service monitor (Uptime Kuma) can send one when a hub service goes down; that is set up on the hub.

**What does not exist.** The app has no notification feed or bell, and Config's **Notification Preferences** card is text only, with no controls. Nothing outside the cabin watches it yet, so if the cabin loses power or internet, no path can send anything (W-56, proposed).

Evidence: [NOTIFICATION_PATH.md](../../NOTIFICATION_PATH.md) (2026-10-12 host check); W-51 and W-56 in [the backlog](../../governance/backlog.md); [NtfyAlertPublisher](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/events/NtfyAlertPublisher.java) (no-op while `CABIN_ALERT_NTFY_TOPIC` is blank; critical, or payload `notify=true`); `CameraNotifyToggle` and the Notification Preferences card in App.jsx.

## A water leak alert and turning the water back on

Audience: household.

**Short answer.** A wet leak sensor that is enabled and assigned shows as a critical Status Checks item, "(sensor name) reports an alarm", and the Rules & Alerts tab turns critical. **This guide has no reviewed step-by-step leak procedure.** Follow your household's or operator's approved procedure, and ask an administrator if you do not have one.

What the app does and does not do:

- The cabin's leak, freeze and siren automations run in Home Assistant and Node-RED, not in this app. A workflow can also shut the main water valve; check the Workflows card in Rules & Alerts to see whether one is active.
- The item can only be reminded in 1 hour (see above). It clears by itself when the sensor stops reporting a leak.
- A phone notification is not guaranteed (see "Phone notifications" above).

**How is the water turned back on?** The app never reopens the main water valve by itself, and a workflow started by a device event is not allowed to open it. The way the app is built to reopen it is **Fire now** on a workflow that is set to **A person triggers it**, has the step **Open the main water valve** and is active (Fire now appears only on an active workflow); a signed-in person presses it. Whether such a workflow exists on your hub is not recorded here. Reset on a firing does not reopen the valve. Do not use the Devices page to turn the water valve on or off. Reopen the valve only after the leak is fixed, following the owner's own procedure.

The app does not check that the leak sensor is dry before sending the open command. A sent command is not proof the valve moved: if the valve does not report open in time, a critical item "Commanded device did not confirm the expected state" appears, so check the valve in person. If the command could not be sent at all, no alert appears; that firing's **History** shows the step as failed. This has happened: once, a Home Assistant automation closed the valve again within a second of a reopen.

Evidence: [ActiveAlertService](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/alerts/ActiveAlertService.java); `validateReopenGuard` and `fireManual` in [RulesController](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/RulesController.java) and [WorkflowRuleService](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/workflow/WorkflowRuleService.java) (2026-08-14 note on a reverted reopen; `executeValveCommand` records REJECTED or PUBLISH_FAILED as a failed step with no alert; `fireManual` refuses an inactive workflow); `WorkflowExecutionHistory` and the Fire now button in App.jsx; [rules and safety](operations.md#rules-and-safety).

## Reset, Mark seen and why there is no undo

Audience: household.

**Short answer.** There is no undo. **Reset** and **Mark seen** are bookkeeping: neither reverses anything a workflow did. They do not reopen a valve and cannot take back a notification.

- **Reset** is on a firing marked "Active" in a workflow's **History** (Rules & Alerts, Workflows card). It marks that firing resolved, with your email address. While a firing is active the workflow does not fire again, so Reset lets it fire the next time its trigger happens. A workflow set to "Ready to fire again once the condition clears" resets itself when the condition clears; one set to "Stays fired until someone clears it manually" waits for Reset.
- **Mark seen** is in the **Recent** list ("Unviewed workflow firings"). It only takes the firing off that list. It does not reset it.
- If the workflow sent a critical notification, its Status Checks item stays for 24 hours after the firing; neither button removes it.

Both need a signed-in account. To reverse what a workflow did, run a separate workflow, for example **Fire now** on an "Open the main water valve" workflow (see the leak section above).

Evidence: `WorkflowExecutionHistory` (Reset, tooltip "does not undo or reverse its actions") and `RecentExecutionsList` (Mark seen) in App.jsx; `clearExecution` and `markViewed` in [RulesController](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/RulesController.java); `findActive` edge detection and `handleTriggerCleared` in [WorkflowRuleService](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/workflow/WorkflowRuleService.java).

## Backend Rules card versus Home Assistant and Node-RED

Audience: household.

**Short answer.** The **Backend Rules** card in Rules & Alerts (titled with the location, for example "Cabin Backend Rules") lists the rules this app's backend checks on its own. It is read only. Its rules are: Water Pressure Low, Water Pressure High, Freeze Risk (below 38 F by default), Safety Alarm, Motion Presence Policy and Lock Unlocked Presence Policy. Their alerts appear in Status Checks.

The cabin's leak, freeze and siren automations are not these rules: they run in Home Assistant and Node-RED, which this app does not show or change. Workflows are a third, separate engine. A rule shown in one place is not proof it exists in another.

Evidence: `BuiltinRules` in App.jsx; `ruleStatuses()` in [AutomationRuleService](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/automation/AutomationRuleService.java); [products and responsibility](operations.md#products-and-responsibility).

## Optimization Opportunities: Power and Duplicate readings

Audience: administrator.

**Short answer.** The **Optimization Opportunities** card in Rules & Alerts lists patterns worth a look, found by scanning device readings. It never changes a device. Only an administrator can see it; others see "Admin access required to view optimization opportunities." It is not the **Opportunities** tab in the left rail, which lists outside findings.

- **Power**: a power meter whose readings have not dropped to 1 W or below for 48 hours or more; the card shows the draw in watts and for how many hours. It means worth a look, not waste: the mechanical-room heater is expected to draw power in cold weather.
- **Duplicate readings**: a device and one of its services are both enabled and report the same reading, so it is carried twice. The card lists each service and the readings it repeats. Turn the service's Enabled switch off unless a workflow watches it; disabling never deletes its history. Open device goes to the device.

**Acknowledge** keeps a card but marks it seen. **Resolve** hides it; that is your decision, not proof the pattern ended. A scan never closes a card. A resolved Duplicate readings card stays closed until a different set of services repeats the device; a Power pattern that comes back opens a new card.

Evidence: `OptimizationOpportunitiesCard` in App.jsx; [OpportunityType](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/opportunities/OpportunityType.java), [DuplicateReadingAnalyticsService](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/opportunities/DuplicateReadingAnalyticsService.java), `cabin.opportunities` in [application.yml](../../../cabin-orchestration-platform/backend/src/main/resources/application.yml); technical detail in [K19](operations.md#opportunities-and-the-tech-id-service-k19).
