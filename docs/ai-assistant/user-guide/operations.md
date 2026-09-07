# Operating the platform

Audience: operators and maintainers; household instructions must be projected to the appropriate role. Evidence: repository baseline in the [index](../README.md); live behavior not reverified. Maintained jointly through PR review.

## Products and responsibility

Family Hub is the static HTML/JavaScript family experience. Cabin orchestration is the React interface with a Spring Boot backend. Both use platform capabilities, but the assistant must distinguish their user journeys and data ownership. Home Assistant, Node-RED and backend workflows are distinct execution systems; a rule visible in one is not proof it exists in the others. Source: [repository map](../../../README.md), [Family Hub source](../../../family-hub/family-hub.html), [app panels](../../../cabin-orchestration-platform/ui/src/App.jsx), [RulesController](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/RulesController.java).

## Capability map

This navigation map points to existing behavior and instructions. The [source inventory](../corpus/source-inventory.md) gives route-level references; the [coverage register](../corpus/coverage.md) identifies what still needs deeper instructions or live tests.

| Need | Where to start | What to verify |
|---|---|---|
| Family profiles, notes, chores/rewards and schedule | [User guide](../../USER_GUIDE.md), corresponding Family Hub controls | Signed-in versus local state, selected attribution and cross-device persistence; a local change is not proof of server sync. |
| Camera activity and media | User guide camera sections; cabin Camera Events panel | Sign-in, privacy level, selected camera/time range and actual available media; missing video is not equivalent to no event. |
| Devices and reporting context | Device Manager in [App.jsx](../../../cabin-orchestration-platform/ui/src/App.jsx); [DeviceController](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/DeviceController.java) | Lifecycle, health, configuration and display labels separately. Renaming a label must not rename canonical identity. |
| Telemetry/history and signal quality | [EventController](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/EventController.java), [SignalQualityController](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/SignalQualityController.java) | Timestamp, field, location, device and actual data availability. Static descriptions are not live readings. |
| Rules, active alerts and execution history | Rules & Alerts; [RulesController](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/RulesController.java), [AlertController](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/AlertController.java) | Which subsystem owns the rule, whether enabled, target health and real execution evidence. |
| Presence/security status | [PresenceController](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/PresenceController.java), [SecurityController](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/SecurityController.java) | Signal source/freshness versus manual override; do not infer automatic physical action from a display state. |
| Discovery/import | Device onboarding below; [PlatformImportController](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/PlatformImportController.java) | Proposal, confirmation, lifecycle acceptance and assignment are different steps. |
| Opportunities | [OpportunitiesController](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/OpportunitiesController.java) and app opportunity panels | Recommendation/status is not proof an enhancement or device action has been executed. |
| Help | [Ask and knowledge](ask-and-helpdesk.md) | Sources, provenance and whether the answer came from the model or fallback. |

## Users and family data

Start with the [user guide's sign-in versus attribution explanation](../../USER_GUIDE.md). Selecting a family profile tells the interface who is writing; it is not an authentication or privilege grant. The server derives household role from verified authentication. The enum includes ADMINISTRATOR, ADULT_HOUSEHOLD_MEMBER, CHILD, KIOSK_DISPLAY and SERVICE, but the enum comment explicitly says some values have no live derivation path yet. Do not promise every listed role can already be enrolled through an existing screen. Source: [HouseholdRole](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/security/HouseholdRole.java), [GoogleAuthInterceptor](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/security/GoogleAuthInterceptor.java).

If a note/profile/chore appears on one device only: preserve the local work, check the other device's sign-in and connection, then verify a real cross-device change. Do not call a server error “no data,” and do not erase the local copy as a first troubleshooting step. Existing sync behavior and troubleshooting are in [USER_GUIDE](../../USER_GUIDE.md); the requested consistent recovery experience is defined in [UX Journey Standard](../../UX_JOURNEY_STANDARD.md). Not all desired recovery UI is implemented.

## Device onboarding

1. Identify the supported source: existing adapter discovery, device-specific assisted discovery or an external platform import. Review prerequisites and permission before triggering a provider call.
2. Review proposed identity/type/location against the physical device. Keep confirmed `entity_id` stable; display labels are the presentation mechanism.
3. Confirm the import/proposal through its existing flow, then check lifecycle. External platform confirmation registers a **CANDIDATE**, not an assigned/operating device. Acceptance is a separate lifecycle action; configuration/assignment remains explicit.
4. Verify the device's actual telemetry/health and the intended display or rule target. A record existing is not proof the hardware works.

Sources: [DeviceDiscoveryController](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/DeviceDiscoveryController.java), [PlatformImportController](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/PlatformImportController.java), [DeviceController lifecycle](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/DeviceController.java), [DeviceLifecycleState](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/devices/model/DeviceLifecycleState.java).

Known limitation: the platform-import controller documents that an unaccepted candidate is memory-only and can disappear on backend restart. Do not represent a successful import confirmation as durable completion until acceptance/persistence is verified. Also, `GET /api/platform-import/{platform}/proposals` contacts the provider and upserts import records; it is not an innocuous health probe. `GET /api/platform-import/records` reads existing records and has its own role check. Source: the controller's `proposals`, `records` and `confirm` methods.

## Rules and safety

New backend workflows are created disabled; activation is separate. Creation and activation validate action targets and the valve-reopen guard. Inspect the owner and current rule definition before altering a workflow; do not duplicate an HA or Node-RED action merely because the assistant knows about it. Source: [RulesController `createWorkflow`/`activateWorkflow`](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/RulesController.java).

Safety-critical instructions need a reviewed applicable procedure. The existing sprint record reports incomplete safety curation; this is historical evidence, not a current content audit. This supplement does not invent leak-response or valve-reopen instructions. If the procedure is absent, say so and request the operator's approved procedure rather than improvising. Source: [Sprint 2 curation status](../../ontology/SPRINT-STATUS.md), [KB generator](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/devices/KbGeneratorService.java).
