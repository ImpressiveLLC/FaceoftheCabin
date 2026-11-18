# Feature prerequisites, API bases and caller boundaries

Administrator reference. Source baseline `e8b32bb2c5bcd4f2281a213c33456e89b955c873` (2026-11-18). This is a bounded map of core feature families, not a complete endpoint catalogue or a live permissions audit. No request, device command, model execution or training was performed. Feature availability, source configuration and successful end-to-end operation are distinct. Caller boundaries below describe the default enabled authentication interceptor (`cabin.security.googleAuth.enabled=true`); deployed settings were not audited.

## Feature prerequisites and evidence

**Feature dependencies include the browser, backend, required data and the selected integration.** A visible button does not establish that the whole path works.

| Feature family | Required pieces | Evidence that is still needed |
|---|---|---|
| Ask documentation answers | Signed-in caller permitted to POST, eligible reviewed context or knowledge and backend; working model service only for generated answers | Whether an answer used the model or raw-facts fallback, and whether its sources answer the question |
| Device inventory and sensor history | Registry/state or event records, selected device/field/time range, backend read routes | Recent observations from the correct device/location, not merely a registered device |
| Home Assistant actions | Permitted signed-in caller, allowed service/target, reachable configured HA and usable credentials | Actual resulting state; a sent request is not proof an automation or heater ran |
| Camera media | Permitted caller, camera identity, configured media integration and available event/clip/live data | Requested camera/time has playable media; no clip is not proof no event occurred |
| Family data | Correct Family Hub API base, identity and backend persistence | Server-confirmed data and cross-device sync, not only a local edit |
| Rules and alert acknowledgments | Correct owning subsystem, permitted mutation route, valid rule/target or acknowledgment policy | Execution/audit evidence and current state; a displayed rule is not proof of a physical action |

This map does not install an integration or enable an action. Existing [capability navigation](operations.md#capability-map), [device guidance](devices.md), [workflow ownership](workflows.md#what-a-workflow-is-and-where-it-runs) and [access matrix](access-and-storage.md#who-can-see-and-change-what) remain the detailed user procedures. The route sections below identify source boundaries, not instructions to call endpoints directly.

## API bases and browser destinations

**An API base chooses the server; a route chooses the resource on that server.** Main UI captures `VITE_CABIN_API_BASE` and `VITE_HOME_API_BASE` at build time. Its trusted origin set is the page origin plus those build origins. The credential-bearing fetch helper accepts only HTTP(S), rejects embedded URL credentials and untrusted origins, and rejects redirects. A server-supplied location entry cannot silently expand that trusted set.

Family Hub reads `window.HOST_CONFIG.cabinApiUrl` from `host-config.js`; its Docker build generates that configuration from `CABIN_API_URL`. Family Hub and the main UI are separate builds. Editing a running container environment does not rewrite already served browser JavaScript. Family Hub's OAuth cache is origin- and tab-scoped session storage, not a credential copied through cross-app links.

When a screen loads but its data does not, compare the browser-served nonsecret API base with the intended backend and the exact request path. Do not paste tokens or redirect credentials to a new origin to make a request work. A trusted destination is not an authorization grant: the server still applies the caller and route rules.

Source: [main UI destination guard](../../../cabin-orchestration-platform/ui/src/App.jsx), [Family Hub configuration consumer](../../../family-hub/family-hub.html), [Family Hub build](../../../family-hub/Dockerfile), [tab-scoped session cache](../../../family-hub/hub-session.js). See [configuration bindings](configuration.md#trace-a-setting-to-its-consumer) for the build/runtime distinction.

## Caller boundaries and read exceptions

**Route access depends on method, exact path, credential type and any additional role check.** The backend does not use a rule that every GET is public or every signed-in caller may write.

- Device and location GET/HEAD reads have explicit open carve-outs. Numeric sensor history and reported-field GET routes have narrow open exceptions; the broader event collection and camera/motion replay do not inherit them.
- A managed Viewer is refused non-GET requests by the managed-session gate, including POST endpoints that appear conversational or read-like. A managed session never gains administrator authority merely because its email appears on an admin list.
- A valid guest token with `roof_spec` scope may POST exactly `/api/roof-spec/entries`, with matching raw and normalized paths; other guest writes are refused. Guest reads have named scopes. Demo access is a separate restricted path. Neither is a general replacement for a household or administrator session.
- Authenticated routes may still require administrator or household roles, allowed actions/targets, confirmation, or feature enablement. UI visibility is not proof those checks passed.

Use the [existing caller matrix](access-and-storage.md#who-can-see-and-change-what) and its identity guidance rather than copying a second role inventory. For an access failure, record the method, normalized route, credential kind and returned status without the credential value. Do not remove a gate because a neighboring read route is open.

Source: [interceptor registration](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/security/WebConfig.java), [method/path and session checks](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/security/GoogleAuthInterceptor.java), [demo filter](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/security/demo/DemoAccessFilter.java). The feature sections name relevant exceptions; they do not supersede controller-specific checks.

## Ask routes and proposal prerequisites

**The helpdesk endpoint for an answer is `POST /api/helpdesk/ask`, with `question`.** It accepts validated Google or CabinSession callers and managed Household Members. Managed Viewers cannot POST, even though asking sounds read-only; guest and demo links are not accepted. The server-resolved role controls eligible document context and credential-pointer redaction. Context/model failure may produce a raw-facts fallback or refusal; inspect `answeredByModel` and sources rather than inferring success from HTTP alone.

`POST /api/helpdesk/propose` is separate. Its controller returns 404 while the intent feature is disabled; when enabled it requires an administrator and bounded nonblank `text`. It returns proposals, not execution. A valid proposal is not permission to operate a device. `GET /api/assistant/inventory` requires administrator or adult-household role; the adult view excludes camera and lock types. Neither route is a general public inventory.

The `/ask` controller has no feature-enabled check; a working model service is needed for generated text, not the raw-facts fallback. The separate `/propose` route does require its feature to be enabled. Backend identity and context remain necessary; an Ollama container alone proves neither. No model execution, proposal or device action is requested by this reference.

Source: [Ask controller](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/TinyHelpdeskController.java), [answer service](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/helpdesk/TinyHelpdeskService.java), [proposal controller](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/HelpdeskProposeController.java), [inventory controller](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/AssistantInventoryController.java), [inventory filtering](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/assistant/AssistantInventoryService.java). See [Ask behavior](ask-and-helpdesk.md#current-behavior).

## Device routes and Home Assistant actions

**Device routes distinguish observation, configuration and execution.** `GET /api/devices` and device GET/HEAD reads have an open interceptor exception. Writes such as `POST /api/devices/{deviceId}/command`, lifecycle changes and configuration edits require an eligible signed-in identity and their own validation. Device commands require an enabled, active-use registry target and adapter. Valves are refused; locks are limited to secure locking. Re-detect preview/apply/rollback has an additional administrator check.

`POST /api/ha/services` is a different route. It allows the supported `switch` or `input_boolean` domain with `turn_on`, `turn_off` or `toggle`, a matching valid entity, supported location and safety checks excluding valve/lock targets. Its controller does not impose an administrator-only role check: eligible Google/CabinSession and managed Household Member callers may reach it; managed Viewers, guests and demos cannot POST.

A usable HA configuration and credential are prerequisites for a successful upstream call. Timeout means the outcome is unknown, not that nothing happened. Sending the On the Way toggle does not prove a heater started; HA owns the downstream automation. Candidate discovery is not lifecycle acceptance or command permission.

Source: [device controller](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/DeviceController.java), [registry dispatch](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/devices/DeviceRegistry.java), [re-detect role check](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/DeviceRedetectController.java), [HA service controller](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/HaServiceController.java). Existing [device guide](devices.md) describes the user journeys; this is not a direct-call procedure.

## Event routes and telemetry prerequisites

**Event routes do not all share the same access boundary.** Exact `GET /api/events/telemetry-history` and `GET /api/events/reported-fields` are open read exceptions for numeric sensor history/field names. History needs the requested device and field, a supported time range and stored observations. A blank graph can mean missing data or a mismatched filter; an inventory record alone does not establish observations.

`GET /api/events`, `/api/events/status-checks` and `/api/events/live` require a valid authenticated session; managed Viewers can use these GETs. The open numeric-history exceptions do not expose the broader camera/motion event log. Demo access is separately filtered/redacted and is not equivalent to an ordinary signed-in session.

`GET /api/events/export` additionally requires an administrator, valid filters/date ordering and an available export slot. Guest/demo links do not grant export authority. Browser streaming also depends on the proxy delivering server-sent events, not buffering them as an ordinary response.

Source: [event controller](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/EventController.java), [export controller](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/TelemetryExportController.java), [exact read exceptions](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/security/GoogleAuthInterceptor.java), [demo policy](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/security/demo/DemoAccessPolicy.java). These paths describe available interfaces, not a fresh data/export verification.

## Camera routes and media prerequisites

**Camera routes require an authenticated session; media retrieval additionally depends on available camera/media data.** Status reconciliation and configuration listing do not require a playable clip. Under `/api/camera`, GET routes include `/list`, `/reconciliation/status`, `/events/{frigateEventId}/snapshot`, `/events/{frigateEventId}/clip`, `/events/{eventId}/clip-by-time` and `/{cameraName}/live`. Guest/demo links are denied. Managed Viewers can read but cannot POST live-view controls.

`POST /api/camera/{cameraName}/liveview/start` and `/stop` are live-view controls, not pan/tilt/zoom. The source has no backend PTZ route at this baseline. Start/stop requires an eligible signed-in caller; it has no additional administrator-only check. Blink uses the configured camera mapping/service and returns skipped for an unmapped camera.

Frigate and the requested camera/media must be available. Clip-by-time also needs the corresponding stored event and recording; live GET proxies MJPEG. Missing media, no recent frames and no recorded motion are different conditions. A successful login or camera listing is not proof a requested clip exists.

Source: [camera media controller](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/CameraMediaController.java), [caller checks](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/security/GoogleAuthInterceptor.java). Existing [device guidance](devices.md) remains the operational reference; do not turn a media read failure into an unrequested camera command.

## Family notes routes and synchronization

**Family notes routes require both identity and backend persistence.** `GET /api/notes` lists notes for an authenticated session, including a managed Viewer. `POST /api/notes` requires an eligible Google/CabinSession or managed Household Member; guest/demo access is denied. The service validates author, text, kind/date and an existing target for replies.

The note's `authorId` is supplied in the request body; this controller does not bind it to the authenticated principal. Selected attribution and authentication are therefore separate facts. Do not describe a displayed author as cryptographic proof of who submitted the note.

Family Hub needs the browser-served API base and usable sign-in for server synchronization. Local browser state and a successful-looking edit are not cross-device persistence evidence; verify the server result and the receiving device. This section maps notes/sticky lists/replies, not every chore, reward or calendar endpoint.

Source: [notes controller](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/family/NotesController.java), [note validation/persistence](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/family/FamilyNoteService.java), [Family Hub client](../../../family-hub/family-hub.html). Use the existing [user guide](../../USER_GUIDE.md) for the household interaction.

## Workflow routes and alert acknowledgments

**Workflow routes and alert routes separate public status from mutations.** `/api/rules` GETs include workflow lists/details, vocabulary and execution history and have an open read exception. Creation, activation, deactivation, firing, retargeting and deletion require an eligible signed-in caller plus operation validation. Creation is disabled-first; activation checks targets/reopen guards; firing requires an existing enabled MANUAL workflow. Retargeting validates targets but does not have an administrator-only check in this controller.

`GET /api/alerts/active`, `/rules` and `/acknowledgments` are open status reads. Exact `GET /api/alerts/history` requires a session and rejects guest/demo access. `POST /api/alerts/acknowledgments` and `DELETE /api/alerts/acknowledgments/{alertKey}` require an eligible signed-in caller. Mode/time/severity policy still applies: a current CRITICAL alert needs matching explicit severity acknowledgment or the POST returns 409. Authentication does not waive the critical snooze limit.

A backend workflow is distinct from an HA or Node-RED automation. An acknowledgment changes alert presentation, not the underlying fault, and a recorded execution is not by itself physical outcome verification. See [workflow ownership and safety](workflows.md#what-a-workflow-is-and-where-it-runs) and [alert guidance](alerts-and-notifications.md).

Source: [rules controller](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/RulesController.java), [alert controller](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/AlertController.java), [acknowledgment policy](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/alerts/AlertAcknowledgmentPolicy.java), [method/path gate](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/security/GoogleAuthInterceptor.java).
