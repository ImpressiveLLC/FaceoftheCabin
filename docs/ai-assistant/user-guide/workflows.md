# Workflows in plain words

Audience: people who use Rules & Alerts. Behavior as of `main` on 2026-10-31, read from the app and backend source; nothing here was run on the live hub. The full trigger and action list, with internal ids, is in [Operating the platform: trigger and action catalog (K05)](operations.md#rules-and-alerts-trigger-and-action-catalog-k05).

## What a workflow is and where it runs

**Short answer.** A workflow is a rule you build in the app: "when this happens, do that." It lives in Rules & Alerts, in the **Workflows** card ("Real, persisted trigger -> action rules (separate from the rules below and from Node-RED)"). It runs in the app's own backend on the cabin hub.

The app is one of three places automations run, and they are separate:

- **Workflows** (this app): the rules in the Workflows card.
- **Home Assistant**: its own automations. The leak, freeze and siren automations live there and in Node-RED, not in workflows.
- **Node-RED**: its own flows, including the siren logic.

A rule you see in one of these is not proof it exists in another, and a workflow does not replace a Home Assistant or Node-RED safety automation. The **Backend Rules** card in the same panel (for example "Cabin Backend Rules") is read-only: it lists the backend's own built-in checks, not your workflows.

What a workflow can do is short. It has four actions: **Shut off the main water valve**, **Open the main water valve**, **Send a critical notification** and **Log this event only**. There is no workflow action for lights, locks, heaters, plugs or thermostats. Turning a heater or plug on or off is done from that device in Devices (its **Turn on** and **Turn off** buttons, when the device offers them), not with a workflow.

Evidence: `WorkflowRulesCard`, `BuiltinRules` and `DmPowerToggle` in `cabin-orchestration-platform/ui/src/App.jsx`; `SUPPORTED_ACTIONS` in `JdbcWorkflowVocabularyStore.java`; K05 and "Products and responsibility" in [operations.md](operations.md).

## Create a workflow

**Short answer.** In Rules & Alerts, press **+ New Workflow**, fill in the form, press **Save draft**, then press **Activate** on the new row. A new workflow never runs until you activate it.

The form, top to bottom:

1. **Name** and **Location** (which place the workflow belongs to).
2. **When**: "A device reports something" or "A person triggers it" (a button you press, see the next section).
3. For "A device reports something": **Specifically** picks the event, for example "Water leak detected", "Door contact opened", the freeze-risk choice (below 32 degrees F) or "Someone arrived". **On this device (optional)** limits it to one device; "Any device" means any device that reports that event.
4. **After it fires** (explained under "Workflow settings" below).
5. **Then, do this**: one or more actions. **+ Add another action** adds a step. Each action has an optional **sec cooldown**.

Example: to shut off the water when a leak is detected, choose When "A device reports something", Specifically "Water leak detected", Then "Shut off the main water valve". The valve action is fixed to the main water valve; **Change target device** exists but is meant for a real replacement only. Reopening the valve later is a separate, human-only step: do it only after the leak is found and fixed, following the owner's own valve procedure.

Choices marked **"not available yet"** are planned triggers or actions shown so you can see what is coming. They cannot be picked or run. The trigger and action lists come from the app's built-in catalog; you cannot add your own events or actions.

**+ New Workflow** appears only when you are signed in. Signed out, the card says "Sign in with Google (Configuration tab) to create or manage workflows." Guest links, demo links and read-only (Viewer) accounts cannot create or change workflows; see [Who can see and change what](access-and-storage.md#who-can-see-and-change-what).

Things the form does not do: it does not reset a device when the condition clears (build a separate workflow on the "cleared" event for that), and "Open the main water valve" is not offered for "A device reports something". Reopening the water is human-only, through a workflow set to "A person triggers it". There is no edit: to change a saved workflow's trigger or actions, create a new one and delete the old one. Only a broken target can be replaced in place.

Evidence: `WorkflowCreateForm`, `actionsFor` (privileged actions only for MANUAL) in `App.jsx`; `validateReopenGuard` message and create/activate validation in `RulesController.java`; no update route in `RulesController.java` other than `retarget`.

## Turn a workflow on or off, run it by hand, and see when it ran

**Short answer.** Each workflow row shows **draft** or **active**. **Activate** turns it on, **Deactivate** turns it off, **Delete** removes it (there is no confirmation step). These buttons appear only when you are signed in.

- **Activate** checks the workflow again before turning it on: every action that needs a device must point at a device that exists, can take commands and is assigned. If a check fails, the workflow stays a draft. As of main the row does not display the reason, so a row that still says draft after Activate means a check failed; look for a **Broken** label or a target device that is not assigned. Deactivate does no checks.
- **Fire now** appears only on an active "A person triggers it" workflow. It runs that workflow's actions immediately. Every press is a new run, recorded with your email.
- **History** on a row lists the 10 most recent runs with what each step reported: "ok", the water valve's command status, "skipped" with a reason such as a cooldown, or "failed" with the error. For the water valve, ACCEPTED means the command was sent, not that the valve has moved; check the valve. A notification marked ok was sent by the hub; whether it reaches a phone depends on the hub's notification setup (host-dependent).
- At the top of the Workflows card, **Recent** (shown only when there is something unseen) lists "Unviewed workflow firings" for the selected location. **Mark seen** removes one from that list.
- **Reset** on a run in History marks that run resolved.

Neither Reset nor Mark seen undoes anything. They never reverse an action and never reopen the water valve. There is no undo button. To reverse an action, run a separate workflow made for that, for example a workflow set to "A person triggers it" that opens the main water valve. Reopen the valve only after the leak is fixed, following the owner's own procedure.

Evidence: `WorkflowRow` (its `act` helper checks only the HTTP status, while activation failures return HTTP 200 with an `error` field), `WorkflowExecutionHistory` (`?limit=10`; shows `commandStatus` for a command step; Reset tooltip "does not undo or reverse its actions") and `RecentExecutionsList` (rendered inside `WorkflowRulesCard`, only when non-empty; Mark seen; "Undo is deliberately not offered") in `App.jsx`; `fireManual` ("Workflow is not active -- activate it first") in `RulesController.java`.

## Workflow settings: After it fires, cooldown and broken targets

**After it fires** has two choices:

- **Ready to fire again once the condition clears**: the workflow fires, then waits until the condition clears (for example "Water leak cleared") before it can fire again.
- **Stays fired until someone clears it manually**: after it fires once, it does not fire again until a person presses **Reset** on that run in History.

While a run is still active (not cleared), the workflow does not fire again for the same condition. A person can press Reset at any time in either mode.

A trigger with no "cleared" signal (for example "Camera detects motion" or "Home Assistant entity changed") is ready again as soon as it fires.

**sec cooldown** (per action): after the action last ran successfully, it is skipped for that many seconds. Use it to stop a workflow from notifying you over and over. Blank ("No limit") means it always runs. A skipped action shows as "skipped" in History.

**Broken** and **Degraded** on a row:

- **Broken**: a target device is missing, reassigned, or lacks a capability the workflow needs, so it will not run correctly as saved. When signed in, press **Choose replacement device**, pick a device and **Save**. The replacement is checked again before it is accepted.
- **Degraded**: every target is set up correctly, but at least one has not checked in recently. There is nothing to replace; look at that device in Devices.

Evidence: reset-mode options, cooldown field and health tooltips in `App.jsx`; `findActive` edge-detection guard, `STATE_TRIGGERS_WITH_CLEAR_SIGNAL` and cooldown handling in `WorkflowRuleService.java`; `retarget` in `RulesController.java`.

## Can a workflow run on a schedule or at a time of day

**No, not today.** A workflow starts only when a device reports one of the events in the catalog, or when a person presses **Fire now**. There is no time-of-day, daily, nightly or calendar trigger, so "every night at 10" or "turn on at 6 am" cannot be built as a workflow. Time and schedule triggers are planned (backlog W-71, status proposed) and are not built.

Evidence: a workflow's `triggerKind` is only `DEVICE_EVENT` or `MANUAL` (`WorkflowRule.java`); the 26 supported triggers in `JdbcWorkflowVocabularyStore.java` contain no time trigger; W-71 row in [backlog](../../governance/backlog.md), checked 2026-10-31.
