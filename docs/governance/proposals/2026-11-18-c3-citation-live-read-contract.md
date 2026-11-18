# C3 citation contract and live-read boundary (proposal, 2026-11-18)

Status: **design only; NOT_IMPLEMENTED / NOT_RUN.** Assigned by [Cowork on #244](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6994148775), covering prerequisites 3 and 4 in [the C3 prerequisite proposal](2026-11-18-guest-link-tidy-and-c3-prerequisites.md). Source pin: **203d4cdc9bdea27330772aadad91f37e79dd67d0**. No endpoint, response, prompt, routing map, UI, role, host or model change is made here. C1 answer-quality authorization and ratification remain separate; training NOT_RUN, A1 NOT_MEASURED, `release_eligible` false.

## Recommended decisions

1. **Use the existing `sources` list as the document/knowledge citation contract.** Preserve `question`, `answer`, `sources` and `answeredByModel` and each existing KnowledgeNode field. Do not add a parallel `citations` array or a second document store.
2. **Keep operational observations distinct from document citations.** A later approved live-read implementation may add one optional `observations` field for bounded, typed read evidence; missing means no read evidence, not healthy or zero. Older clients continue to use the four existing fields. This proposal defines the minimum information needed, not permission to implement that field now.
3. **Start the new Ask live-read path administrator-only**, on the selected server, with explicit site/device resolution and a field-level allowlist. This is a proposed limit on a new integration, not a change to existing public kiosk/device reads or existing household Ask document access. Expand roles only through a separately reviewed contract.
4. **No actions and no model-selected URLs.** Use existing passive read capabilities, with bounded scope and deadlines. Static knowledge must never stand in for a current observation. Unknown freshness remains unknown.

## Existing behavior that the design must preserve

| Source at the pin | What it establishes |
|---|---|
| [TinyHelpdeskAnswer](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/helpdesk/TinyHelpdeskAnswer.java) and [KnowledgeNode](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/devices/model/KnowledgeNode.java) | The current four-field answer and source nodes with `entityRef`, `chunkType`, `content`, `source`, `generatedAt`; no claim-level citation index, model-usage metric or typed live observation |
| [TinyHelpdeskService](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/helpdesk/TinyHelpdeskService.java), `ask` and `buildPrompt` | Document audience gates and credential-pointer redaction precede prompt input, fallback material and returned sources; they are not a generic privacy filter for every knowledge chunk. Reviewed documents receive `[source: path#section]` prompt labels. Model prose is returned without a factual/citation validator. An empty source list returns a no-information answer with `answeredByModel=false` |
| [AskContextBuilder](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/helpdesk/AskContextBuilder.java), `resolveDocuments` | Document identity is `doc:path#section`, source is `REVIEWED_DOCUMENT`, content is the delivered section or explicitly truncated excerpt; audience gates run before delivery |
| [ReviewedDocumentSource](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/helpdesk/ReviewedDocumentSource.java) | Existing path/root allowlist and heading parser; document `generatedAt` originates from file modification time, not a Git revision, review approval time or sensor observation |
| [CredentialPointerRedactor](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/devices/model/CredentialPointerRedactor.java) | Only CREDENTIAL_POINTER content is rewritten: non-administrators receive the contact message; administrator output formats the stored pointer content. Other chunk types pass unchanged. This is not an arbitrary-secret detector or authority to retrieve a value |
| [HelpdeskPanel](../../../cabin-orchestration-platform/ui/src/App.jsx), exported component | Plain answer text, source badges and source-content title; no full document viewer or verified claim-to-source graph. `answeredByModel=false` does not by itself prove the model was unreachable |

## Citation contract

### Identity and evidence

- A document citation identifies **one source actually delivered in this answer**, using its exact returned `entityRef` (`doc:` prefix plus the allowlisted path and anchor). A knowledge citation uses the existing natural key `entityRef + chunkType`. Deduplicate by those identities without altering canonical IDs or conflating different sections.
- The permitted citation set comes from the actual response sources after the existing document-audience and credential-pointer rules, never from a model-provided URL, an arbitrary local path or the full repository. A source that was selected but skipped for budget is not citeable. A partial excerpt supports only the supplied text; the truncation marker must remain visible when its content is shown.
- A recognized `[source: path#section]` label may resolve only to a matching delivered document source. Unknown labels remain unverified plain text, cannot become links, and count as invalid citation evidence in evaluation. A syntactically matching citation still does **not** prove that its text supports the associated claim; grading must check support. No automatic second model call repairs a citation.
- Reuse the delivered response's `content` as an escaped plain-text excerpt when a person opens a source in the panel. Escaping prevents active markup; it does not establish that all source text is privacy-sanitized. Do not fetch a newer document and imply it is the text that supported an older answer. Do not render source HTML or activate embedded instructions, arbitrary URLs or actions. A separate full-document viewer is not required for this first citation slice.
- `generatedAt` remains its existing source timestamp. For documents it is file mtime; for knowledge it is generation time. Neither is proof of current physical state, a content digest or human approval. Exact source/version fingerprints belong in evaluation/deployment evidence; this design does not invent a revision field from timestamps.

### Display and failure behavior

Keep the existing node shape and source labels compatible. Show a human-readable document/section label alongside its stable identity and delivered excerpt. A curated-source badge identifies the source's curation status, not verification of every generated claim. A reviewed document is not a live reading.

No sources means no citation claim. Missing/invalid source metadata gives an explicit unavailable/unverified source display, without a guessed link. A fallback can display its actual supplied sources, but `answeredByModel=false` should be explained as a response produced without a model answer; it must not assert a network outage that the boolean cannot establish. Useful source-gap recovery remains a C3 capability to implement and verify, as recorded in [the journey-harness design](2026-11-18-c3-ask-panel-journey-harness.md), not a capability created by this proposal.

Audience and credential rules apply before model input, fallback, response and expanded excerpts. Do not make previously inaccessible source content available through a new citation control. No public GitHub link or new source-serving endpoint is needed to satisfy this contract.

## Passive read contract

### Resolve and authorize before reading

The existing Ask controller derives role from the authenticated request, not the question or a client role field. A later implementation must preserve that identity and enforce the new read policy **server-side**, before any read or model context. UI hiding is not the boundary. Null role, non-administrator, guest/demo/scoped token and unsupported authentication yield no new live-read evidence; existing document-answer behavior remains governed by its current policy.

Resolve a requested device through the existing [AssistantInventoryService](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/assistant/AssistantInventoryService.java), preserving canonical entity ID, lifecycle eligibility and explicit `site`. Do not invent a device/site from a display name or let the model supply an endpoint. Ambiguous identity, unknown site, a wrong-site match or a device outside the selected inventory requires clarification/unavailable status. Disabled inventory entries may supply cached values only as explicitly labeled **disabled / historical-only** evidence; never as a current reading, and never by activating or polling the device. If the request needs current operation, return unavailable with that reason. The inventory's existing adult-role access does not automatically authorize this proposed administrator-only read slice.

Restrict the new integration to the current trusted server and its explicitly resolved device/site. There is no cross-server token forwarding, background federation or URL supplied by model/source/user text. Platform-wide summaries must be labeled as that server's registry, not as a site-filtered result. Do not change the existing kiosk GET carve-outs in [WebConfig](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/security/WebConfig.java): public reachability is not permission for a broader Ask data aggregation path.

### Initial allowlist: existing operations, projected fields

The endpoint names below identify existing read contracts. Reuse their service logic and enforce equivalent request policy; do not add duplicate readers or assume direct service calls inherit HTTP interceptor/redaction attributes. No new outward endpoint is proposed.

| Existing operation | Permitted purpose and returned evidence | Explicit limit |
|---|---|---|
| `GET /api/assistant/inventory` | Resolve canonical ID, site and lifecycle from the eligible inventory | Identity only. `generatedAt` is snapshot time, `lastObserved` copies device activity, and `version` is not a measurement-freshness token |
| `GET /api/devices/{deviceId}` | Latest stored state for one resolved device; only the requested reviewed numeric measurement, its existing unit metadata and necessary identity/time fields | Do not return the whole heterogeneous `attributes` bag. Unknown device can return null; null/absent reading is unavailable, never zero. No motion, person, occupancy, camera, lock/access, credential, host or topology fields in this first projection |
| `GET /api/devices/checkin-details` | Reporting-health status and expected interval for that same device | Filter the bulk result to the selected device before model input or response. Reporting health is not proof of physical operation or measurement freshness |
| `GET /api/system/health` | Administrator question about this server's platform: aggregate counts, bridge state and reporting-status counts | Whole-registry scope. Omit raw stale-device lists/identities unless a later reviewed contract permits them. `checkedAt` is read-time construction, not the last scheduled classification or each sensor's observation time |

Sources: [DeviceController](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/DeviceController.java), [DeviceStatus](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/devices/model/DeviceStatus.java), [SystemController](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/SystemController.java), [DeviceHealthMonitor](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/devices/DeviceHealthMonitor.java), and [AssistantInventory](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/assistant/AssistantInventory.java).

The first implementation must name the small supported measurement-key/unit set from existing device metadata before coding; this proposal does not authorize a wildcard metric bag, infer units from a raw number, or silently coerce unknown types. If a requested metric lacks a reviewed key/unit mapping, explain that it is unsupported. Existing measurement definitions should be reused rather than copied into a competing ontology.

### Freshness is part of the answer

“Live read” means a request-time read of the backend's latest stored record, **not** an active poll of a physical device. Device `lastSeen` is activity time: [Zigbee2MqttAdapter](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/integrations/zigbee/Zigbee2MqttAdapter.java) merges new attributes into old ones, so one metric can be older than the most recent activity; retained messages and missing/null fields have their own handling. [HomeAssistantAdapter](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/integrations/homeassistant/HomeAssistantAdapter.java) can stamp fetch time rather than the underlying measurement time. Do not manufacture a per-field observation timestamp from either.

For every permitted read, keep these facts separate:

- **Read time:** when this integration obtained the stored record.
- **Device activity time:** the original `lastSeen`/`lastObserved`, if supplied.
- **Measurement time:** only a provider-supported timestamp for that exact field, otherwise unknown.
- **Reporting-health classification:** original status, expected window and its limitations, never a universal freshness threshold. The monitor runs periodically; `checkedAt` is not the classifier's run timestamp. `getCheckinDetails` may default an unchecked device to ON_SCHEDULE, and event-driven CO devices have special rules. ON_SCHEDULE does not certify a fresh numeric value; LATE does not prove physical failure.

Use “latest recorded value; measurement time unknown” when that is all the source establishes. A missing timestamp, null value, unsupported unit, failed read or conflicting source is unknown/unavailable, not healthy, empty, safe or zero. An offline device's last value may be shown only as historical/last recorded with its status, not as measured now. Never infer presence, absence, authorized disarm or water/lock safety from these records.

### Proposed observation envelope and execution limits

The optional future `observations` list is separate from `sources`: operational readings must not be disguised as `REVIEWED_DOCUMENT` or use document `generatedAt` as a sensor timestamp. Its minimum per-read record is the canonical entity/site or explicitly server-wide scope; the allowlisted read kind; projected value/unit and reporting status; read time; nullable original device activity and measurement times; and an explicit availability/freshness limitation. These are projections of existing read results plus read time, not new persisted entities or a second inventory. Missing `observations` means the older API supplied none. Failure may supply an unavailable record without value; an empty list never proves the system is healthy.

If readings enter model context, only this bounded authorized projection may enter, with the same evidence returned to the caller; the model cannot widen fields, choose operations or turn text into a command. Citing an observation uses its returned identity and times, never a document citation. Legacy clients still render the four existing answer fields. The concrete optional-field schema and serialization compatibility tests must be part of a separately approved implementation slice; no response change is made by merging this design.

Proposed bounded execution: at most one resolved device per answer and three passive reads (inventory, device, optional reporting health), or one platform-summary read, within a total three-second read deadline. No automatic retry, scheduled poll or fan-out. These are proposed limits for review, not measured performance promises. On timeout/401/403/unknown identity, return unavailable/clarification and preserve any independently supported document explanation without presenting it as a live answer. Cache reuse across users, roles, sites or answers is excluded initially; do not overwrite source timestamps with the read time.

Excluded: device commands, security/valve operations, scans, discovery/refresh/probe triggers, import proposals, automation writes, credential lookup, camera/media/presence, broad event history and arbitrary URLs. **GET alone is not proof of passivity:** [PlatformImportController](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/PlatformImportController.java) has a GET proposal path that calls providers and upserts records, so it is excluded. No raw actuator/environment diagnostics or forced device fetch is included. Instructions may explain a supported next action; they never claim an action was executed.

## Acceptance evidence for later approved implementation

- Citation tests: existing four fields/node shape retained; exact delivered document labels resolve; skipped/unknown/forged citations do not become links; truncated text stays visibly partial; no HTML/script/path traversal; source-audience and credential-pointer protection applies to expanded excerpts and fallback too. Rendering a citation is distinct from grading its factual support.
- Read-policy tests: server-derived administrator succeeds only for the allowlist and resolved site/device; all other roles/token classes and cross-site/ambiguous requests get no new read data. Unknown ID, wrong lifecycle, disabled-device historical-only labeling/current-reading denial, whole attribute-bag exposure and direct-service policy bypass are covered. No regression to existing kiosk or household document access.
- Freshness tests: stale field with recent device heartbeat, null/missing value, unknown unit, retained telemetry, HA fetch timestamp, unchecked ON_SCHEDULE default, LATE, unavailable provider and timeout. Assert honest last-recorded/unknown wording; never safety or occupancy inference.
- Network/action negatives: arbitrary host, unapproved GET and any command path cannot be reached; deadline/read-count limits hold; no elevated-session fallback. Existing reads only, with no device/provider refresh as a hidden side effect.
- Compatibility and journey evidence: older responses without `observations` still work, optional metadata is bounded, source cards and read evidence remain visually distinct, and synthetic UI tests do not claim model quality or live verification. Follow [#521's design](2026-11-18-c3-ask-panel-journey-harness.md); a real browser and live model need their separate gates.

## Rulings requested, not implementation authorization

Cowork: accept the existing-sources citation contract; accept or narrow the proposed administrator-only allowlist, freshness semantics, optional observation separation and read bounds. Before a live-read implementation, approve its exact metric-key/unit projection and additive schema; these unresolved specifics are recorded, not delegated to model discretion. Citation rendering can be a smaller independent slice once assigned, without adding live reads.

Nate's separate authorization remains necessary for live model evaluation; [#520's measured-run protocol](../../ai-assistant/proposals/2026-11-18-c1-measured-run-protocol.md) covers only its fixed 72-trial batch, not new browser journeys or live-read integration tests. No C3 completion, C1 ratification, new role permission, runtime change or deployment follows from this design alone.
