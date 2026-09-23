# Ontology and Architecture Decisions — canonical record

| Field | Value |
|---|---|
| Status | **Canonical.** This file is the ratified record of D-decisions for the platform track. |
| Canonical since | 2026-09-23 (Nate decision; see [SO-2026-09-23-r1](../signoffs/SO-2026-09-23-r1.md) §0) |
| Imported from | Living Ontology artifact `a9c467c8-9958-4b17-95e5-f28b7af6e68f`, live version `1789408428-ca43` (content through 2026-09-14) |
| Import method | Mechanical HTML → GitHub Markdown conversion (pandoc). No wording changed. Styling, navigation and status chips dropped; section codes folded into headings. |
| Edit rule | Change by PR only. Ratification lines are Cowork's; implementation-status lines are the implementer's. Neither overwrites the other's lines. |
| Planned split | One file per decision (`D01-identity.md` …) is backlog item DOC-2. Until then, this single file is the record. |

The artifact becomes a read-only rendered view of this folder (backlog item DOC-3). Nobody edits the artifact directly from 2026-09-23.

---

Started 2026-08-26 Last updated 2026-09-12 17 decisions ratified · Sprint 5 ratified · Sprint 5 complete · D20 merged + deployed 2026-09-14, awaiting Cowork ratification · D19 A+B merged + deployed 2026-09-14 · D21 merged + deployed 2026-09-14 (2 follow-up fixes same evening) · D18 proposed

## AX — Axioms

These are not re-litigated. Every decision below must be consistent with them.

▸Device is the atomic unit; `entity_id` is the canonical identity

▸Location (cabin/home) and Area are first-class dimensions

▸Time is primary on every fact

▸FAIR applies at home scale

▸`snake_case` entity_ids are inviolable for automation stability

▸A documented schema fact and an implemented one are not the same claim until **ratified** — every schema gap carries an explicit status (`flagged` → `proposed` → `implemented — awaiting ratification` → `ratified`), tracked in Open Pins' Discrepancy Log, not silently assumed or guessed past. Added 2026-09-05 after a ratified-sounding claim (D13's `reporting_mode`) turned out unbuilt, and a Cowork assertion (motion/contact/leak service rows) turned out live-false — confirmed emphatically core to this doc's own referential value, not a one-off fix.

------------------------------------------------------------------------

## RC — Repository & Naming Canon

Resolved 2026-08-31. Use these names exactly — the old names are retired.

| Name                             | What it is                                                                                                                           |
|----------------------------------|--------------------------------------------------------------------------------------------------------------------------------------|
| `ImpressiveLLC/FaceoftheCabin`   | The GitHub repo — umbrella for the whole platform                                                                                    |
| `cabin-orchestration-platform/`  | Top-level directory inside FaceoftheCabin: Spring Boot backend + React frontend. Former informal name: `smrekar-platform` (retired). |
| `family-hub/`                    | Top-level directory inside FaceoftheCabin: vanilla HTML/CSS/JS ambient family dashboard.                                             |
| `ImpressiveLLC/CabinAutomations` | Separate repo: Home Assistant automations YAML only.                                                                                 |

Both app directories share git history and the M920q host. Each has its own Dockerfile and GitHub Actions deploy workflow. "Face of the Cabin" as a project name covers the full platform.

------------------------------------------------------------------------

## WS — WSJF Formula

    CoD = (Safety Value × 2) + Family Usability + Platform Extensibility + Time Sensitivity
    WSJF = CoD / Complexity
    Scale: 1–10 per component · Complexity: Fibonacci
    Secondary gate: each decision advances ≥2 FAIR properties

------------------------------------------------------------------------

## D1 — Identity

done

**Resolution:** `snake_case` entity_id IS the local URI stem → `cabin:{entity_id}` in JSON-LD

## D2 — Observation Model

done

**Resolution:** Two-layer model — `device_state` (fast/current) + `observations` (historical)

## D3 — Formalism

done

**Resolution:** JSON Schema + JSON-LD context. LLM serialization is a separate layer.

## D4 — Governance

done

**Resolution:** Provenance mixin on Definition layer only. Observation stream = `source_device_id` + timestamp only.

## D5 — KB Freshness

done

**Resolution:** `KnowledgeNode.source = auto_generated | manually_curated`. Safety-critical = curated only.

## D6 — Multi-location

done

**Resolution:** Location entity in schema from day one. Defaults to cabin.

------------------------------------------------------------------------

## D7 — Sensor Naming & Reporting Context

done

**Resolution: Option B** — keep primitive device name, derive semantic data elements per measurement type. Renaming breaks entity_ids, violating D1.

### New Ontology Properties (L2/L3)

| Property                  | Layer          | Description                                                                       |
|---------------------------|----------------|-----------------------------------------------------------------------------------|
| `Device.capabilities[]`   | L2 Semantic    | All measurement types this physical device produces                               |
| `Entity.measurement_type` | L3 Observation | Controlled vocab: temperature, humidity, motion, contact, leak, co, battery, rssi |
| `Entity.derived_from`     | L3 Observation | Parent device entity_id stem                                                      |
| `Entity.reports_to[]`     | L3→L4          | Named reporting log/chart categories this entity feeds                            |

**Reporting context rule:** Select by `measurement_type`, not device name pattern-matching.

✓ Sprint 1 (2026-08-30): D7 entity schema properties and device repo class wired to Postgres — shipped and verified live on M920q.

------------------------------------------------------------------------

## D8 — Zigbee Runtime & Catalog Strategy

done

### Decisions

- **Catalog blind spots only** — not operational fallback. Z2M is healthy; problem is description richness.
- **No second coordinator now** — WSJF backlog. Trigger: actual Z2M pairing failure. ZHA quirk signatures used as reference data only.
- **Priority failure mode:** wrong identity / missing capability. Next anticipated: Tuya variant ambiguity.

### Enrichment Policy

| Question           | Decision                                                                                                                                                       |
|--------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Cost tolerance     | Zero recurring for cabin. Small capped budget for home-location clone. Config-driven, disabled by default. Explicit approval per lookup.                       |
| Enrichment trigger | On-demand only. Never automatic on pairing.                                                                                                                    |
| Privacy            | Send only sanitized hardware fingerprints externally: manufacturer, model, endpoints, clusters. Never cabin names, IPs, friendly names, or behavioral history. |
| Activation         | Enrichment may propose a definition or capability. Must never automatically activate automations or safety-sensitive commands.                                 |

### Evidence Ledger Architecture ledger model — 2026-09-01

All four evidence layers are preserved as separate assertions. A resolved operational view is computed on top — no layer overwrites another. Disagreements between layers are retained and visible.

1.  **Live runtime evidence** — Z2M bridge/devices, observed attributes. Highest operational authority.
2.  **Independent community candidates** — ZHA quirks, deCONZ DDFs, Blakadder. Reference data only; stored but never auto-applied.
3.  **Local reviewed overrides** — human-approved external converters with source + checksum. Overrides the resolved view when present; does not delete other assertions.
4.  **Vendor documentation** — reference only. Never sufficient alone for safety-sensitive behavior. Does not outrank empirical observation in the resolved view.

**Resolved view:** computed from the ledger at query time. Runtime evidence wins for operational answers; vendor docs inform description fields only. Conflicting assertions surface as explicit flags, not silent overrides.

**Hard gates:** Zero fabricated sources. Zero automatic safety-action changes.

------------------------------------------------------------------------

## D9 — Tiny Helpdesk KB Policy

done

### Response Behavior

- **Operational questions** (valve reset, leak response, sensor states): KB required + citation required. Refusal if not in KB — no fabrication or generalization.
- **General background questions** (what is Zigbee, what does MQTT mean): model general knowledge acceptable, clearly framed as general knowledge. Never blend KB and general knowledge in a single answer without explicit distinction.

### KB Content — First Build Priority

1.  Safety-critical runbooks (leak response, valve reset, freeze protection, intrusion deterrent reset) — `manually_curated` only
2.  Device troubleshooting (sensor offline, pairing failure, state issues)
3.  Architecture and ontology reference (admin context)

### Audience & Authorization

**Current:** admin (Nate) only. **Target:** household users (family members). KB must be role-aware from the start — household-facing content must omit network topology, IP addresses, credential hints, and admin-only detail. Redaction policy must be designed before household access is enabled, not retrofitted.

### Sprint Milestone Split

- **Sprint 0 DoD (accepted):** Ollama + Open WebUI deployed, isolated, benchmarked. Infrastructure-only.
- **Sprint 2 DoD:** One grounded-answer test passing with citation. One correct refusal test passing. These gate "Tiny Helpdesk complete."

------------------------------------------------------------------------

## D10 — External Platform Import Pipeline

done

**Resolution:** Import from external platforms preserves user language as permanent metadata. AI translation proposes ontology vocabulary. User confirms before any `entity_id` persists.

### Platform Priority (WSJF)

| \#  | Platform                       | Rationale                                                           |
|-----|--------------------------------|---------------------------------------------------------------------|
| 1   | **Matter / Thread**            | Open standard, hardware-level, no vendor auth required              |
| 2   | **Google Nest SDM**            | Largest installed base for security + climate; SDM API stable       |
| 3   | **SmartThings**                | High DIY overlap; device graph structured; API integration-friendly |
| 4   | **Ring**                       | Dominant residential security; rich motion/camera event model       |
| 5   | **Philips Hue**                | Largest Zigbee-adjacent installed base; local API (no cloud)        |
| 6–n | Apple HomeKit, Lutron, Ecobee… | Backlog — re-WSJF when adoption data warrants                       |

### Import Flow

    External platform → PlatformImportProvider → raw import record
      → AI translation layer → proposed ontology mapping
      → user confirmation screen → entity persisted with provenance

Raw import record preserves verbatim: `original_name`, `original_location`, `original_platform`, `original_id` — permanent on the entity, never overwritten. Provenance tag: `imported:platform_name` (fifth evidence-architecture tag).

### Constraints

- Never automatic; never overwrite confirmed entity_ids on re-import
- Enrichment may propose, never activate safety-sensitive behavior
- `original_name` and `original_location` are local-only — never sent externally
- Matter adapter has no vendor-cloud OAuth dependency (uses commissioning credentials and fabric certificates natively); cloud OAuth adapters require Vaultwarden (Sprint 3+)
- **Paywall re-score (2026-09-01):** Nest SDM (+3 complexity, Google Cloud OAuth + Works-with-Google fee) and HomeKit (+3 complexity, Apple Developer Program \$99/yr — redundant once Matter is live) are deferred. Sprint 3 = SmartThings + Ring (no hard paywall). Sprint 4 = Matter + Nest SDM if still wanted; HomeKit deferred indefinitely.

------------------------------------------------------------------------

## D11 — Family Hub / Cabin Orchestration Boundary

done

**Resolution: Bounded-context modular platform (Option D).** Not a full merge. Not a literal air gap — one already does not exist. Reviewed 2026-08-31 with two independent agents (Code + Codex) against the live checkout.

### Factual Baseline Corrections

- **The apps are already integrated.** Family Hub calls the cabin Spring Boot backend via `CABIN_API_URL` for profiles, schedule rules, chores, assignments, notes, and cabin activity.
- **Auth partially exists and works correctly for family features.** `GoogleAuthInterceptor.java` validates Google bearer tokens for family-data paths. `actorId` is a family-feature attribution field — a parent can log a chore or schedule entry *for* a child. Authentication (Google token) and attribution (actorId) are intentionally separate concerns. Code audit 2026-09-01 confirmed actorId does not grant elevated permissions and is not a security gap.
- **The API is intentionally internet-reachable** for the public core. `CABIN_API_URL` defaults to `https://api.unicornpingpong.com` per the execution plan decision: "public core app, Tailscale-only admin surfaces." Confirmed live 2026-08-31.
- **The apps have release and design coupling.** Shared themes, cross-app links, cross-app theme-drift tests.

### Domain Ownership

| Domain                                     | Owns                                                                                                                                                                       |
|--------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Family Hub**                             | Schedules, chores, rewards, parental controls, raw child-device identifiers, precise location history, screen-time data                                                    |
| **Cabin Orchestration**                    | Devices, observations, alerts, rules, physical actions                                                                                                                     |
| **Permitted cross-domain (future, gated)** | Minimal aggregate presence assertion only: `{state, confidence, observed_at, expires_at, sources: [coarse classes], policy_version}`. No named child. No precise location. |

### Authorization Prerequisites

These are prerequisites for child device or new cross-domain data flows — not Sprint 3 enhancements:

**Cross-reference — D14 (Kiosk Auth Model, 2026-09-04):** CabinSession is the authentication mechanism this section was waiting on for gated-endpoint sign-in. It provides Google-identity-backed 30-day rolling auth without interactive OAuth popups, exactly matching "short-lived credentials" for browser clients. Review D14 before expanding the cross-domain prerequisites list — the session layer is now in place.

- Server-derived principals and per-route policy on *new* cross-domain endpoints (cabin sensor state → family scheduling; family presence → cabin security rules). Note: existing family-feature routes use `actorId` as an attribution field — a parent logging a chore for a child — intentionally separate from Google token authentication. Code audit 2026-09-01 confirmed this is correct design, not a gap.
- Explicit household roles: administrator, adult, child, kiosk/display, service
- Per-route policy enforcement and auditable reads/writes
- Short-lived credentials and denial tests for cross-domain access

### Kids' Device API Feasibility

| Capability                                 | Feasibility                                       |
|--------------------------------------------|---------------------------------------------------|
| Direct self-hosted Family Link API         | Low — no documented public backend API            |
| Screen-time without on-device component    | Low — Android UsageStatsManager is device-side    |
| Coarse phone presence via HA Companion App | High — supported, zone-only available             |
| Wi-Fi association as supplemental presence | Medium — useful corroboration, unreliable alone   |
| Generic smartwatch integration             | Low — no cross-vendor standard                    |
| Custom Wear OS presence publisher          | Medium — feasible but separate maintained app     |
| Aggregate presence into orchestration      | High — feasible after auth and policy gates exist |

### Allowed Initial Uses of Child-Derived Presence

Low-consequence, reversible only: occupancy display, comfort suggestions, or a notification asking an adult to confirm an arm-away change. **Not permitted initially:** automatic unlock, disarm, safety-valve operation, or suppression of critical alerts.

------------------------------------------------------------------------

## D12 — Guest Access & Non-Google Auth

Google auth is the primary mechanism and remains so. Two tiers provide access to external parties and eventual household members without Google accounts — without proxying or sharing any credential.

**Superseded 2026-09-04 — see D14:** ~~/api/devices gated behind Google auth — consistent with /api/alerts/\*\* and /api/events/\*\*. All data endpoints now require auth.~~ This blanket approach broke the app's core glanceable/kiosk use case for negligible security benefit -- D14 reverses it for /api/devices, /api/alerts, and /api/events's two sensor-history sub-paths specifically, keeping only the actual presence-revealing signal (/api/events's bare camera/motion collection) gated.

### Tier 1 — Cabin View Tokens Sprint 3

For external parties without Google accounts (insurance adjusters, remediation teams, contractors). No account creation required.

|                    |                                                                                                                                                                                                                                                                                                                                                                                                                                           |
|--------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **DB table**       | `cabin_access_tokens` — id, token (UUID v4), label, scope\[\], expires_at (nullable), revoked_at, created_by, created_at                                                                                                                                                                                                                                                                                                                  |
| **Auth path**      | Parallel to Google interceptor. `Authorization: CabinToken {token}` or `?t={token}`. Token principal is always role `VIEWER` — writes return 403.                                                                                                                                                                                                                                                                                         |
| **Allowed scopes** | `dashboard`, `device_states`, `alerts_read`, `observations_read` (→ `/api/events/telemetry-history`) Note (2026-09-04): `device_states` and `alerts_read` now gate endpoints that D14 also made fully public (`/api/devices`, `/api/alerts/active`). These scopes are intentional redundancy — a Tier 1 token still declares intent even when the underlying endpoint no longer strictly requires it. No change needed; D12 predates D14. |
| **Route format**   | `/view/{token}/**` — separate read-only controller. `X-Robots-Tag: noindex, nofollow` on all responses.                                                                                                                                                                                                                                                                                                                                   |
| **Admin UI**       | Settings → Access Links: create (label + scope + optional expiry), copy URL, revoke. No self-service for token holders.                                                                                                                                                                                                                                                                                                                   |

**Use case:** Insurance adjuster + remediation team need read access to cabin conditions for an active claim. Admin creates token scoped to `dashboard + device_states + alerts_read`, 30-day expiry, label "Insurance Claim Sep 2026." Adjuster receives URL, no account created, reads current conditions. Admin revokes when claim closes.

### Tier 2 — Managed Users (passwordless) Sprint 4

For trusted recurring non-Google users (household members, co-owners) who need persistent access beyond a time-bounded link.

|                        |                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
|------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **DB table**           | `managed_users` — id, email, name, role (VIEWER \| HOUSEHOLD_MEMBER), active, created_by, created_at (as shipped 2026-09-02; no separate `last_login_at` column). Plus `managed_user_magic_links` (single-use, 30min expiry) and `managed_user_sessions` (90-day, explicitly revocable) — two more tables than originally scoped here.                                                                                                                                                                                        |
| **Auth flow**          | Admin invites by email. User receives time-limited one-use magic link. Clicking it establishes a short-lived session token. No password stored anywhere. Backend shipped 2026-09-02 · Admin UI (create/list/deactivate/reactivate/invite) + the magic-link landing page itself shipped 2026-09-05 · [e64ba6b](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/e64ba6b)                                                                                                                                                 |
| **Principal contract** | **As shipped:** folded directly into `GoogleAuthInterceptor` (an `Authorization: ManagedSession {token}` check, same file as Tier 1's guest-token path) rather than a separate `ManagedUserAuthInterceptor` class — deliberate, for consistency with Tier 1's already-shipped pattern. Sets the same `REQUEST_ATTR_EMAIL` attribute a Google token sets, so downstream code sees one consistent principal shape regardless of auth path. Functional contract matches what's described here; only the class structure differs. |

**What this is not:** Not a public API (Tier 1 links expose a view layer, not raw endpoints). Not a shared-credential system (no one logs in as admin). Not anonymous (every view-token request is logged with token id, timestamp, and IP).

------------------------------------------------------------------------

## D13 — Service-Level Data Lineage

Multi-function physical devices (e.g., the Kidde CO + Temperature + Humidity monitor) expose multiple independently-reportable measurement services from a single physical device. The D7 entity model has the right primitives (`derived_from`, `measurement_type`, `reports_to[]`) but lacked explicit identity, display label, and lineage rules for each service. This decision makes the **service instance** a first-class entity — the atomic data element in every data lineage context.

### Problem captured from production (2026-09-03)

The Grafana humidity chart labels its series `temp_kitchen` and `temp_mech_room` — the parent device names — even though the chart is rendering the humidity service of those devices. The device name is meaningless as a series label in a humidity reporting context. Similarly, the Kidde CO device is labeled "CO2 Measurement" in the sensor card UI — a factual error (Kidde detects carbon monoxide, not carbon dioxide).

Root cause: reporting contexts are pulling the parent device entity_id for display rather than the service-level entity_id and its display label. `measurement_type` is stored correctly but not surfaced in the display layer.

### Resolved: Service as atomic data element

Each (device, measurement_type) pair is a **Service Entity** — the indivisible unit for every reporting context, data lineage trace, and UI label. Relationships:

|                                             |                                                                                                                                                                                                                                                                                                                                                                           |
|---------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Device (1)**                              | Physical device. entity_id is the physical asset name (e.g., `temp_kitchen`). Name describes the hardware, not any service it provides.                                                                                                                                                                                                                                   |
| **Service Entity (1..many per device)**     | One entity per measurement_type the device produces. entity_id = `{device_entity_id}_{measurement_type}` (e.g., `temp_kitchen_humidity`). Has its own `display_label` — the human name for this service in UI contexts (e.g., "Kitchen Humidity"). `derived_from` → parent device. `measurement_type` → controlled vocabulary. `reports_to[]` → named reporting contexts. |
| **Observation (1..many per service)**       | Each time-series record belongs to a Service Entity, not to a Device. Foreign key is the service entity_id, never the device entity_id.                                                                                                                                                                                                                                   |
| **Reporting context (0..many per service)** | Named log/chart category (e.g., "humidity", "temperature", "air_quality"). A service appears in a context only if it has that context name in `reports_to[]`. The display label shown in that context comes from the service entity's `display_label`, never the device name.                                                                                             |

### New ontology properties (extends D7)

| Property                         | Layer          | Description                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                               |
|----------------------------------|----------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ServiceEntity.display_label`    | L2 Semantic    | Human-readable label for this service in all UI contexts. Set at curation time, never auto-derived from device name. Required.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| `ServiceEntity.measurement_type` | L3 Observation | Canonical vocab entry (temperature, humidity, motion, contact, leak, co, co2, battery, rssi). **co** = carbon monoxide, **co2** = carbon dioxide — distinct entries, never to be confused. *Correction 2026-09-04 (Code, verified against live code + a real live entity): co2 already exists in D7's own measurement_type vocabulary and backs a real, correctly-functioning DeviceType.CO2_SENSOR — the Kidde unit's separate co2-level HA entity (device_class carbon_dioxide, friendly_name "CO₂ Level"), confirmed live via direct DB query. The 2026-09-03 production bug was a classification mixup (the CO-level entity has no device_class or unit, so it fell through to the wrong DeviceType) — fixed at the source in HomeAssistantDiscoveryService.inferType() via a friendly_name-based fallback, not by removing co2 from the vocabulary.* |
| `ServiceEntity.derived_from`     | L3 Observation | Parent device entity_id. The lineage relationship: Service → Device → Physical asset.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| `ServiceEntity.reports_to[]`     | L3→L4          | Reporting context names this service feeds. Drives both Grafana dashboard routing and cabin UI sensor card placement.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| `ServiceEntity.reporting_mode`   | L3 Observation | **Documentation concept — not a runtime DB column.** `continuous` / `event_driven` / `polled`. Governs health inference and UI presentation. *Implementation: DeviceType-keyed hardcode in `DeviceHealthMonitor.isEventDriven()` (CO_SENSOR-specific today); will become a real column only when a second event-driven device type actually needs onboarding. Ratified 2026-09-05.*                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |

### Reporting mode — why it matters

Not all sensors emit data continuously. The Kidde CO + Temp + Humidity monitor is the canonical example: its MOX sensors (temperature, humidity) report continuously, while its electrochemical CO sensor reports *only on state change*. Kidde's cloud API explicitly strips continuous CO telemetry; HACS inherits this. A CO entity that appears static or has no recent time-series data is the expected healthy state — it means no change, not device failure.

| Service              | Sensor type      | reporting_mode | Resting behavior                                                                                                                                                                                                                           |
|----------------------|------------------|----------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Temperature          | MOX (continuous) | `continuous`   | Streams regularly; use for device liveness                                                                                                                                                                                                 |
| Humidity             | MOX (continuous) | `continuous`   | Streams regularly; use for device liveness                                                                                                                                                                                                 |
| CO (carbon monoxide) | Electrochemical  | `event_driven` | Static Clear state at rest; new data point only on state change (Clear → Warning → Alarm). Resting silence = correct operation. Kidde cloud API strips continuous CO telemetry; a flatline or static entity is the expected healthy state. |

**⚠ Device liveness rule:** A device's online/offline status MUST be inferred from its `continuous`-mode service entities only. An `event_driven` service with no recent data is NOT evidence of device failure — it is evidence the monitored condition has not changed.

**Display rule (non-negotiable):** Any UI element — chart series label, sensor card title, telemetry table column header, export CSV column — that presents a value from a service entity MUST use `display_label` from that service entity. Using the parent device entity_id as the display string is a bug, not a default. For `event_driven` services (e.g., CO): show current state value + last-state-change timestamp — do not render a time-series graph for a service that does not stream continuously.

**CO vs CO2 — revised 2026-09-04:** `measurement_type=co` means carbon monoxide; `measurement_type=co2` means carbon dioxide — both are real, valid, distinct entries, and both back real Kidde-unit entities today (co-level and co2-level are separate HA entities on the same physical device). The bug was never that CO2 lacks a place in the vocabulary — it was that the CO-level entity (no device_class, no unit) was falling through discovery's classification logic and landing on the wrong DeviceType, so a CO reading could show up mislabeled under a CO2-titled card. Fixed by giving `HomeAssistantDiscoveryService.inferType()` a friendly_name-based fallback for CO_SENSOR — the classification bug is gone; CO2_SENSOR itself was never the problem and is not being removed.

------------------------------------------------------------------------

## D14 — Kiosk Auth Model — Public Read Tier + CabinSession

done

Direct user pushback, 2026-09-04, following the Sprint 3 gate: this app's whole purpose is glanceable, zero-friction "See" status — a kiosk/wall display can't stop and OAuth every time someone looks at it. "Being secure doesn't make it work" if the thing it secures no longer functions for its actual use case.

**Provenance note (Code, 2026-09-05):** The threat-model reframing above, the specific per-endpoint public/gated split in the table below, the instruction to keep camera events and every write path gated rather than expand the public surface further ("again, LETS KEEP IT GATED if that's easier, it seems it is"), and CabinSession's four concrete requirements (a visible sign-in prompt, a clear path to authenticate, a redirect when unauthenticated, 30-day rolling persistence on the device) were Nate's own direct, explicit product/security instructions, given in-session to Code — not Code's independent architecture judgment that happened to route around the Cowork/WSJF design process. Session pace meant several of these went straight from instruction to implementation without a design round-trip through Cowork first; that reflects sequencing under active user direction, not Code asserting authority over a product or security decision on its own. D14's resolution should be read as user-directed, not as an engineering proposal open to revision independent of Nate's own say-so.

### Problem captured from production (2026-09-04)

- Monitoring and Device Manager showed zero devices/services and an "API offline" badge that linked only to raw `/actuator/health` JSON — no path back to working, for anyone, signed in or not.
- A second, independent regression: `/api/events/telemetry-history` and `/api/events/reported-fields` (SensorHistoryPanel's charting/dropdowns/history — Kidde CO, humidity) had been swept into the camera-motion-event gate purely by sharing the `/api/events/**` prefix, even though neither carries the "is anyone home" signal that gate exists to protect.
- The assumed cross-app single-login (sign in once via Family Hub, cabin-ui recognizes it too) was searched for directly in both apps' source — no token-passing bridge, no shared storage key, no `postMessage` handler exists anywhere. Each app has always had its own fully independent Google sign-in. Flagged directly rather than assumed either way; may have been discussed/decided outside this repo but was never actually built here.

### Resolved: threat model determines the gate, not "gate everything by default"

This project's actual security concern is physical break-in, which doesn't depend on whether a humidity reading is also visible online — a would-be intruder already knows where the house is independent of this app. The one thing that *does* matter for that threat: whether an outside observer can tell if anyone is home right now. That reframes the gate around a single question per endpoint — does this reveal occupancy — not "is this technically an API."

| Endpoint                                                                                                                                                                                 | Status | Why                                                                                                                                                                                                                                                                                                                                                                                                                              |
|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `/api/devices`                                                                                                                                                                           | public | Device inventory (room names, vendor/model, capabilities) — same category as a background object in a news broadcast, per direct user argument. Ungated 2026-09-04.                                                                                                                                                                                                                                                              |
| `/api/alerts/active`                                                                                                                                                                     | public | Leak/CO/battery alert state — doesn't reveal occupancy. Ungated 2026-09-04.                                                                                                                                                                                                                                                                                                                                                      |
| `/api/events/telemetry-history`, `/reported-fields`                                                                                                                                      | public | Numeric sensor history — same category as `/api/devices`, just over time. Carved out of the `/api/events/**` gate with an exact-path GET exemption (GoogleAuthInterceptor), same shape as the existing `/api/rules`/`/api/kb` read carve-outs.                                                                                                                                                                                   |
| `/api/presence`, `/api/security`                                                                                                                                                         | public | Never gated in the first place — confirmed directly against WebConfig's pattern list, contrary to an earlier assumption they might need protecting.                                                                                                                                                                                                                                                                              |
| `/api/events` (bare collection)                                                                                                                                                          | gated  | Camera/motion event replay — the actual "is anyone home" signal. The one thing worth protecting under this project's own threat model. Presence detection's known inaccuracy (see Open Pins) makes this *more* important to keep gated, not less: a wrong "away" reading while someone is actually home is worse than no reading, since it invites a confrontation risk the person publishing the (wrong) signal never intended. |
| All write/admin paths (notes, chores, profiles, camera actions, schedule, rules writes, kb writes, cross-domain, helpdesk, platform-import, platform-info, access-tokens, managed-users) | gated  | Unchanged — a break-in threat model has nothing to do with whether an anonymous caller can mutate state.                                                                                                                                                                                                                                                                                                                         |

### CabinSession — 30-day rolling recognition for what stays gated

For the tier that does stay gated (camera events, every write action), the explicit requirement: "known users, signed in, able to access anytime" via a rolling 30-day window — sign in once, stay recognized for 30 days from your *last* use, from either the cabin app or Family Hub, no interactive Google popup in between.

|                  |                                                                                                                                                                                                                                                                                                                                                                                                                                |
|------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| **Exchange**     | `POST /api/auth/session` — a real Google-authenticated caller (or an existing ManagedSession/CabinSession, whichever the interceptor already vouched for) trades that proof for a fresh `CabinSession` token.                                                                                                                                                                                                                  |
| **Storage**      | `Authorization: CabinSession {token}`. Frontend persists it in `localStorage` (survives tab close/reload) — unlike the raw Google token, which stays `sessionStorage`-backed and ~1 hour lived. `authedFetch` prefers CabinSession over the raw token when both are present.                                                                                                                                                   |
| **Window**       | Genuinely sliding, not fixed: every successful validate pushes `expiresAt` another 30 days out from that moment (`CabinSessionService.validateAndExtend`). Deliberately NOT the same table/semantics as Tier 2's `ManagedUserSession` (fixed 90-day expiry-from-creation) — reusing that would have meant changing already-shipped, tested passwordless-session behavior to get a sliding window this use case actually needs. |
| **Access level** | Full read/write, same as a live Google token — not VIEWER-limited the way a Tier 2 managed session can be. This represents someone who already proved a real Google identity, not a passwordless invite.                                                                                                                                                                                                                       |

**Explicitly out of scope for this pass:** cross-app SSO between Family Hub and cabin-ui (each still has its own independent sign-in — building an actual token-passing bridge between two different origins is a separate, larger piece of work, not attempted here) and CabinSession support for the camera live-view `<img>` query-param path (still keys off the raw ~1-hour Google token only).

------------------------------------------------------------------------

## D15 — Energy Device Ontology

shipped 2026-09-05

Nate sees devices labeled "Energy" in the UI with no indication of what they are, where they are, or what they're called. These are Zigbee smart outlets/plugs reporting power consumption — the ontology has no vocabulary for them, so they appear nameless and locationless in every view.

**Resolution:** Add energy measurement types to the controlled vocabulary, introduce an outlet/smart_plug device class, add user-facing name properties to Device, and define non-negotiable UI display rules for energy service entities.

**Shipped and live-verified (Code, 2026-09-05):** [de4084a](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/de4084a). Root cause was purely display-layer, confirmed live: `kpiTileFor`'s `POWER_METER` case hardcoded `label: "Energy"` (never `device.name` or a curated label, unlike every sibling tile type) and read `device.attributes.state_w` — a field that doesn't exist on either real Third Reality smart plug. Live data confirmed the real keys are `power`/`energy`/`current`/`voltage`, already flowing correctly the whole time. Also fixed real drift found while extending the vocabulary: `Zigbee2MqttAdapter` had its own independent, un-synced duplicate of the measurement_type vocabulary despite `D7MeasurementTypes`' own doc comment already claiming it was shared — it now calls that class directly.  
  
**Delivered:** `power`/`energy`/`current`/`voltage` added to the controlled vocabulary (D7MeasurementTypes) and `DeviceType.telemetryFields()`; tile label now `curatedLabel("power") || device.name`, matching every other measurement-type tile; tile value combines power (primary) + energy (secondary) into one line, matching the existing temperature+humidity combo pattern; voltage/current kept off the compact tile per the "tertiary/collapsed" rule but made chartable via new Sensor History field options; curated labels seeded for the two real plugs ("Mech Room Heater Power", "Breaker Box Switch Power").  
  
**Deliberately not built this pass — flagged, not silently skipped:** a separate `Device.device_class`/`user_provided_name`/`device_name_from_system` schema. `DeviceType` already serves the categorization role `device_class` would add, and Z2M's `friendly_name` (already `device.name`) already is the user-editable identity for both real devices — confirmed live there's no concrete case today where the split would change behavior. The "{Area} Outlet — {measurement}" display_label template is illustrative, not literal: there's no populated Area field for either device yet (a real column, but nothing writes it for Zigbee devices), and per the same curation discipline already used for D13's seeds ("Kitchen Humidity", not "Kitchen Sensor — Humidity"), a specific name beats a fabricated one. 279 frontend + 368 backend tests green.

### measurement_type controlled vocabulary additions (extends D7/D13)

| Value     | Meaning                                 | Unit        |
|-----------|-----------------------------------------|-------------|
| `power`   | Real-time power draw                    | W (watts)   |
| `energy`  | Cumulative consumption since last reset | kWh         |
| `current` | Electrical current                      | A (amperes) |
| `voltage` | Line voltage                            | V (volts)   |

### New entity taxonomy (L2 Semantic layer extension)

| Property                         | Layer       | Description                                                                                                                                                 |
|----------------------------------|-------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `Device.device_class`            | L2 Semantic | Device type from controlled vocabulary. New values: `outlet`, `smart_plug`. Z2M adapter recognizes outlet/plug device types and tags them at pairing time.  |
| `Device.user_provided_name`      | L2 Semantic | The human label the admin assigned in Z2M or HA (if set). Persisted verbatim. This is the user's own name for the device — never overwritten by the system. |
| `Device.device_name_from_system` | L2 Semantic | The Z2M/HA assigned name. Fallback when `user_provided_name` is absent.                                                                                     |

### UI display rules (non-negotiable)

**Device name:** Show `user_provided_name` if set; fall back to `device_name_from_system`. Never show the raw entity_id as the primary label.

**Area — show when known, omit gracefully when null:** Show `Area` (location) alongside the device name on any sensor card or list entry when it is populated. Omit gracefully when null — do not show "null" or an empty label. *Shipped and live-verified 2026-09-05 (Code, [e53e481](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/e53e481)), WSJF \#1 from Cowork's Sprint 5 handover: `DeviceRegistry.withOntologyMetadata()` now surfaces `DeviceMetadata.area` into `/api/devices`'s `attributes.area` (omitted entirely when unset, per this rule's own "omit gracefully" text — no bare `"area": null`). New `PATCH /api/devices/{id}/area`; `DmDeviceRow`/`DmDeviceDetail`/SensorHistoryPanel's Topic-tab chips all show it; a new `DmAreaEditor` in `DmEditForm` writes it. **Deviation flagged, not silently resolved:** no adapter write path was built — Z2M's real `bridge/devices` payload carries no area/location field, and `friendly_name` is the same string that already becomes a device's `entity_id`, so deriving area from it would be the exact "fabricate area from entity_id" anti-pattern this same rule's hard-rule sibling forbids. Admin PATCH is the only write path for now — open question for Cowork/product on whether that's permanent or a real upstream location signal is still wanted. Also: `upsert()`'s COALESCE-based write can't clear an existing area (shared constraint with manufacturer/model), so the endpoint 400s on blank rather than pretending to clear. 9 new backend + 6 new frontend tests, live-verified end-to-end against `z2m-motion_entry` in production.*

**ServiceEntity.display_label format for energy:** `"{Area} Outlet — {measurement}"`. Example: "Living Room Outlet — Power", not "energy_monitor_1_power". Same non-negotiable display_label rule as D13.

**Sensor card layout for outlets:** Device type ("Smart Outlet") + Area + user name as card header. Current power draw (watts) as the primary metric. Energy (kWh) is secondary. Voltage and current are tertiary/collapsed by default.

**⚠ voltage/current topic assignment requires device context:** `voltage` and `current` are dual-use measurement_types — they appear on smart outlets (power monitoring) *and* on battery-powered sensors (battery health diagnostics). The `energy` topic assignment for these two types must be conditioned on `DeviceType.POWER_METER` only. Battery voltage/current from any other device type must resolve to `reports_to: []` (no topic assignment). Fix location: capture layer in `Zigbee2MqttAdapter` or, equivalently, the `topicFor()` mapping must accept device context as a second parameter for these two types. Ratified 2026-09-05.

------------------------------------------------------------------------

## D16 — Reporting Topics IA

Sprint 4 — complete

The existing reporting UI risks becoming a sprawling dropdown of 15+ measurement types as the platform grows. The design principle here: organize reporting around **user questions**, not device types or measurement_type lists.

**Resolution:** Pivot the entire reporting IA around five user-question-driven Topics. Each Topic maps to a `reports_to[]` controlled vocabulary value. No UI dropdown of raw measurement_types — the Topic picker is the only navigation layer.

**Fully implemented and live-verified 2026-09-05 (Code), ratified 2026-09-05 (Cowork):** [add8a75](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/add8a75) → [2f38b23](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/2f38b23) → [3a9f9e1](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/3a9f9e1). All three assignable topics are wired: `comfort_air` (temperature/humidity/co/co2/air_quality_index), `energy` (power/energy/current/voltage, gated to real `POWER_METER` devices only), and `security_presence` (motion/contact/leak, gated to the matching DeviceType). `reportsTo` is computed at read time in `DeviceController.ReportingRelationshipView` (not a stored column, not on the raw `DeviceReportingRelationship` record — it needs device-type context that record doesn't carry). `alert_history`/`occupancy` deliberately have no per-service wiring — see the Discrepancy Log below for why. Live-verified: `z2m-motion_entry`'s occupancy, both door contacts' `contact`, and `z2m-leak_mech_room`'s `water_leak` all resolve `security_presence`; the two door sensors' incidental battery `voltage` now correctly resolves no topic (previously mislabeled `energy`); `z2m-heater_mech_room`'s real `power` still resolves `energy`. 374 backend tests green.

**Topic-picker UI shipped and live-verified 2026-09-05 (Code):** [380d227](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/380d227), deployed live at `cabin.unicornpingpong.com`. `SensorHistoryPanel`'s field/measurement `<select>` is now scoped under a 5-tab horizontal strip (Comfort & Air / Security & Presence / Energy / Alert History / Occupancy), 1:1 with the Topic table above — no more raw dropdown of every measurement_type. Unconditional fields (everything except voltage/current) resolve from a static frontend mirror of `ReportingTopics.UNAMBIGUOUS_BY_MEASUREMENT_TYPE`; voltage/current ask the real `reportsTo` the backend already computes via `/api/devices/reporting-relationships`, so a battery sensor's own voltage reading can never surface under the Energy tab (browser-verified live against the two real Third Reality plugs vs. the door contacts' battery voltage). Security & Presence and Occupancy have zero chartable numeric fields today (binary state changes; Sprint 5 design work respectively) and say so honestly rather than rendering an empty picker — Occupancy's tab is additionally disabled/grayed "Soon", unclickable. Alert History surfaces the existing `GET /api/alerts/active` snapshot inline (current active alerts, explicitly labeled as not a full historical log — no dedicated alert-history endpoint exists yet). 5 new frontend tests (284 total green); live-verified against real production data on all four reachable tabs, including a real active WARN alert.

**Ratified (Cowork, 2026-09-05):** Topic-picker tab-strip is the agreed-on design — 5 horizontal tabs (Comfort & Air / Security & Presence / Energy / Alert History / Occupancy), 1:1 with the D16 Topic table, replacing the raw field \<select\> entirely. Static frontend mirror of `UNAMBIGUOUS_BY_MEASUREMENT_TYPE` for unconditional fields; `/api/devices/reporting-relationships` for voltage/current context — implementation shape confirmed correct. D16 is fully complete end-to-end.

**Cowork decisions ratified 2026-09-05 (resolves all 4 Discrepancy Log items — all confirmed and complete):**

1.  **reports_to\[\] shape — computed-singular ratified and built.** No measurement_type under the current 5-topic scheme needs dual Topic assignment. The spec's array notation was defensive overdesign. Computed, singular, resolved with device-type context at read time, is the ratified implementation. If a future type genuinely needs two Topics, revisit then.
2.  **alert_history and occupancy — pure computed views, not per-service assignments.** These two Topics do not belong in `reports_to[]` at the per-service level at all. `alert_history` is a view over the alert event log (all types); `occupancy` is an aggregate inference over motion + contact patterns (Sprint 5 scope). Neither is a property any single ServiceEntity has.
3.  **Binary-device capture extension — in D16 scope, built.** `Zigbee2MqttAdapter` now captures binary (motion/contact/leak) exposes into `device_reporting_relationship`, DeviceType-keyed (MOTION_SENSOR / CONTACT_SENSOR / WATER_LEAK_SENSOR) exactly as decided, live-verified. See Discrepancy Log.
4.  **voltage/current topic assignment — fixed at capture-time resolution.** `energy` topic is conditioned on `DeviceType.POWER_METER` only, live-verified. See Discrepancy Log.

### Five Reporting Topics

| Topic                   | User question                | Primary measurement_types              | reports_to\[\] value |
|-------------------------|------------------------------|----------------------------------------|----------------------|
| **Comfort & Air**       | What are conditions like?    | temperature, humidity, co, air_quality | `comfort_air`        |
| **Security & Presence** | Who's there / what happened? | motion, contact, leak + alert log      | `security_presence`  |
| **Energy**              | What's being used?           | power, energy, current, voltage        | `energy`             |
| **Alert History**       | What went wrong / when?      | all types → alert event log            | `alert_history`      |
| **Occupancy**           | How busy is it?              | aggregate motion + contact patterns    | `occupancy`          |

### Sprint 4 implementation scope

- Seed the five `reports_to[]` taxonomy values as controlled vocab constants in the codebase
- Wire existing humidity/temp/CO `ServiceEntity.reports_to[]` entries to `comfort_air`
- Wire motion/contact/leak entries to `security_presence`
- Wire D15 energy measurement_type entries to `energy`

### Presence/Occupancy logging (Sprint 5 design — not yet implemented)

Each motion trigger and contact state change is already an observation in the observations table. Occupancy inference: aggregate motion + contact → `{state: occupied|unoccupied|unknown, confidence: low|medium|high, observed_at, expires_at}`.

- No named individuals, no precise location (per D11 boundary contract)
- Do not implement automatic physical actions from presence inference without adult confirmation
- Log to a separate `presence_states` table or add `inferred_presence` as a derived observation type
- Expires quickly — configurable TTL, default 30 min with no new motion

**What NOT to build:** A UI dropdown listing all 15+ measurement_types. The Topic picker is the only navigation. Adding a raw measurement_type selector would revert to the pattern this decision exists to prevent.

------------------------------------------------------------------------

## PR — WSJF Priority Order

01Identity scheme done28.0

02KnowledgeNode source tagging done27.0

03KB Generator v1 done14.5

04Provenance mixin done12.5

05Two-layer observation model done11.3

⬛**Authorization model — server-derived principals, roles, per-route policy, auditability** shipped 2026-09-03 `HouseholdRole` enum (5 roles), server-derived role in `GoogleAuthInterceptor`, `CrossDomainController` with per-route role gate + denial tests — [a2e3ef1](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/a2e3ef1). Gate is real. 461+ tests green.

⬛**JSON-LD context** shipped 2026-09-06 Extends D1's existing `/api/context/cabin-context.jsonld` (not a new document/endpoint — see Discrepancy Log) with KnowledgeNode/ServiceEntity/DeviceType/HouseholdRole/measurement_type/reports_to/derived_from/capabilities/reporting_mode/display_label/policy_version. `GET /api/kb/nodes` now sends a Link header pointing at it — [bea74d8](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/bea74d8). 410 backend tests green.

08Actor/Permission model + Vaultwarden + `CREDENTIAL_POINTER` KnowledgeChunkNodes shipped 2026-09-036.2

09Platform import pipeline — SmartThings + Ring (Sprint 3); Matter + Nest SDM (Sprint 4) shipped 2026-09-03—

10ZHA operational fallback / second coordinator — re-WSJF on trigger event

⬛**D20 — Live MQTT tile: real read-only SSE relay** merged + deployed 2026-09-14, awaiting Cowork ratification WSJF 3.4 scoring stands (see Discrepancy Log pin) -- Option A (Server-Sent Events over the existing `/api/` path) is the one built. New `GET /api/events/live`: a new `EventStreamBroadcaster` wired into `EventConsumer.pollLoop()` right after persistence, so a relayed event is never one this platform hasn't already durably recorded; falls under the existing `/api/events/**` gate, same as the bare collection it mirrors. Frontend's raw-WebSocket `useMqttTelemetry` replaced by EventSource-based `useLiveEventStream`; `nginx.conf`'s inert PR \#58 `/mqtt-ws` placeholder removed and replaced with a buffering-off `/api/events/live` location. Merged to main at commit `feded58` on Nate's go-ahead; `deploy-cabin-backend.yml` ran `mvn test` for real in CI (Testcontainers-backed, not just local compile) and passed, health check green, no rollback; `deploy-family-hub.yml` also succeeded. Live-verified anonymous path on `cabin.unicornpingpong.com`: tile renders cleanly, no crash, no wasted request. [PR \#63](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/63), commit `d31301e`. Not yet ratified by Cowork -- see Discrepancy Log.

⬛**Kiosk session/token-refresh layer** shipped 2026-09-04 Landed as D14 -- not a full read-gate (the opposite: /api/devices, /api/alerts, and sensor-history reads went public), but the seamless re-auth/token-refresh half predicted here shipped as CabinSession's 30-day rolling window. 597 backend + 272 frontend tests green.

⬛**D15 — Energy Device Ontology** shipped 2026-09-05 `power`/`energy`/`current`/`voltage` vocab added; Monitoring tile's hardcoded "Energy" label fixed to a curated label/device.name — [de4084a](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/de4084a). device_class/user_provided_name/device_name_from_system deliberately not built — see D15's own section.

⬛**D16 — Reporting Topics IA** shipped end-to-end — 2026-09-05 all three assignable topics (comfort_air/energy/security_presence) wired and live-verified, device-type-gated voltage/current mislabeling fixed — [3a9f9e1](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/3a9f9e1). Topic-picker UI (5-tab strip replacing the raw field dropdown) also shipped and live-verified — [380d227](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/380d227). All items fully ratified 2026-09-05.

⬛**Optimization Opportunities** ratified — 2026-09-06 WSJF ≈4.4, "START HERE" in Cowork's 2026-09-05 r5 handover. `POWER_DRAW_ANOMALY` analytics job + `/api/opportunities` + Rules & Alerts sidebar card — [3a105d5](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/3a105d5). Deviation ratified: POWER_DRAW_ANOMALY accepted; COMFORT_DELTA deferred. See Discrepancy Log pin.

⬛**Family Hub presence contract** ratified — 2026-09-06 WSJF \#1, "START HERE" in Cowork's r6 handover. `GET /api/presence/contract` (D11 minimal aggregate assertion) + Family Hub presence badge — [fa012ed](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/fa012ed) + [6d9ef25](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/6d9ef25). Three deviations ratified: (1) family-hub.html is vanilla JS not React — CLAUDE.md was right, handover was wrong; (2) separate `/contract` endpoint rather than reshaping existing `GET /api/presence` — correct call, reshaping would have broken cabin-ui's live PresenceToggle; (3) binary confidence — the two real signals have no fractional value to report. See Discrepancy Log pin.

------------------------------------------------------------------------

## SP — Sprint Status

Sprint 0 Complete — 2026-08-29

Ollama (llama3.2:3b) + Open WebUI live at 100.77.44.113:8081 (Tailscale-only, authenticated)

Committed to cabin-orchestration-platform/infra/docker-compose.m920q.yml

Benchmark: ~14 tok/s warm generation on i7-8700T, no GPU

Sprint 1 Complete — 2026-08-30

Device repo class wired to Postgres

D7 entity schema properties (measurement_type, reports_to, derived_from, capabilities\[\])

KB Generator v1 — persisted KnowledgeNode records, retrieved via Tiny Helpdesk (not cabin_kb.md pipeline — shipped)

device_state + observations tables

Provenance mixin on Definition layer

Local-catalog evidence provider via Zigbee2MqttAdapter.java MQTT listener

Close Sprint 0 benchmark gaps

Fix production stack .bak-file deploy blocker — both files removed, device-smoke-test fixed, live-verified via a green deploy run resolved 2026-09-03 · [d7a84be](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/d7a84be)

Sprint 2 Essentially cleared

KB Generator producing grounded answers with citations

One grounded-answer test + one correct refusal test passing

KnowledgeNode schema, JSON-LD context

Household user authorization/redaction policy designed

Sprint 3 Complete — 2026-09-04

Vaultwarden container (Tailscale-only, admin token through vault); `CREDENTIAL_POINTER` `KnowledgeChunkType` (seeded; `MANUALLY_CURATED` only; enforced guard in `KbGeneratorService` rejects any auto-generation attempt); `actor_role` gate in Tiny Helpdesk, `/api/kb/nodes`, and `/api/helpdesk/**` all gated in `WebConfig` — shipped 2026-09-03 · [523eb3c](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/523eb3c)  
Codebase deviations: enum is `KnowledgeChunkType` (not KnowledgeNodeType); seeding uses `@PostConstruct` (no Flyway in this codebase); Vaultwarden image is `vaultwarden/server`. Gap found + closed: `/api/kb/nodes` was open to unauthenticated callers; `vault_blink_username/password` were missing from vault despite being required — transcribed from live value, resolved.

Authorization model: `HouseholdRole` enum, server-derived role in `GoogleAuthInterceptor`, `CrossDomainController` per-route gate, 7/7 denial tests — actorId is deliberate design, not a gap — shipped 2026-09-03 · [a2e3ef1](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/a2e3ef1)

`/api/devices` gated behind Google auth (D12) — all data endpoints now require auth shipped 2026-09-01

Cabin View Token system (D12 Tier 1) — `cabin_access_tokens` table, `/view/{token}` read-only controller, Settings → Access Links admin UI shipped 2026-09-02

`managed_users` table stubbed (no UI — schema ready for Sprint 4 without migration)

D14 public-read carve-outs — exact-path GET exemptions for `/api/events/telemetry-history` and `/api/events/reported-fields` in `GoogleAuthInterceptor`; `/api/devices` and `/api/alerts/active` ungated; camera/motion bare-collection `/api/events` remains gated shipped 2026-09-04 · [e3620fe](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/e3620fe)

CabinSession backend — `POST /api/auth/session` exchange endpoint; `CabinSessionService.validateAndExtend` pushes `expiresAt` +30 days on each validate (genuinely sliding, not fixed); stored in `Authorization: CabinSession {token}` header shipped 2026-09-04 · [310b28a](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/310b28a)

Frontend `authedFetch` prefers CabinSession over raw Google token; `localStorage` persistence (survives tab close) vs. `sessionStorage`-backed raw token; 272 frontend tests green shipped 2026-09-04 · [ffa051c](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/ffa051c)

`ADMIN_EMAILS` env variable config fix — `ADMINISTRATOR` role had been unreachable in production since WSJF \#6 shipped; `CrossDomainController`, platform-import, and Platform Info endpoints were validated by unit tests only (which inject admin email directly); config-only fix shipped 2026-09-04 · [68f73e0](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/68f73e0)

Platform Info Liebherr correction — Liebherr fridge ("Loonie Mc Frigerton") IS paired and reporting multiple services; Refrigeration row's "none paired" text was wrong; fixed in Platform Info data layer shipped 2026-09-04 · [53cc842](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/53cc842)

Platform import pipeline: SmartThings + Ring OAuth adapters; dedup constraint `(original_platform, original_id)` UNIQUE; `PlatformImportProvider` interface; `ADMINISTRATOR`-gated endpoints; fixture-based unit tests for 3+ device types each — shipped 2026-09-03 · [f37930d](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/f37930d)  
Deviation, not fully green: `OAuthCredentialStore` is a real seam (no token ever reaches `.env`, the DB, or a log line) but its only implementation, `VaultwardenOAuthCredentialStore`, throws rather than calling Vaultwarden's API — WSJF \#8 stood up Vaultwarden with no Organization/API key, and that live retrieval flow was explicitly deferred as "post-Sprint-3" work \#8 itself never finished. Neither adapter can actually run live until that's built. *Update 2026-09-05: that gap is closed code-side — see the Vaultwarden pin under Infrastructure Debt and the matching Sprint 4 entry. One-time operator setup (Organization + API key + master password) is still pending before either adapter can actually run live.* `confirm()` is also a deliberate stub — DeviceRegistry wiring is separate follow-up work alongside the frontend confirmation screen.

Sprint 4 Complete — 2026-09-06

Managed Users backend — `ManagedUser`/`ManagedUserRole`, magic-link issuance/consumption, sessions, Resend email integration, folded into `GoogleAuthInterceptor` (see D12 Tier 2 note on class structure) shipped 2026-09-02

Device lifecycle vocabulary endpoint — `/api/devices/{id}/lifecycle`; `DeviceLifecycleVocabulary` enum (CANDIDATE, ACTIVE, IGNORED, RETIRED) surfaced as structured API response shipped 2026-09-04 · [4d46bb4](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/4d46bb4)

Real `targetDeviceId` validation — workflow engine now validates the referenced device exists and is ACTIVE before binding a rule; previously accepted any string shipped 2026-09-04 · [251fc89](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/251fc89)

Ignored-since timestamps — IGNORED lifecycle state now records `ignored_since` for audit trail; Device Manager surfaces this in the candidate/ignored list view shipped 2026-09-04 · [50767d3](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/50767d3)

Workflow health + retarget — `/api/workflows/health` endpoint identifies rules whose `targetDeviceId` refers to a non-ACTIVE device; includes retarget suggestion payload for Device Manager's bulk-remediation UI shipped 2026-09-04 · [90fd77d](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/90fd77d)

Matter/Thread adapter (no cloud dependency, no vendor-cloud OAuth, no paywall — commissioning credentials + fabric certificates natively) — **deferred**, per Cowork's 2026-09-05 r5 handover: Nate has not yet confirmed Matter/Thread hardware exists at the cabin to test against; a protocol integration with nothing to verify against isn't startable.

Nest SDM adapter — paywall re-scored +3 complexity (Works with Google Home program fee) — **deferred**, per r5 handover: not startable without Nate authorizing that spend.

HomeKit: deferred indefinitely — redundant once Matter ships; paywall avoided

Family Hub presence contract (D11 minimal aggregate assertion, r6 handover WSJF \#1, "START HERE") — new `GET /api/presence/contract`, deliberately a separate endpoint from the existing `GET /api/presence` rather than reshaping it in place: that response's own shape (profile/options/autoDerived/signals, including a real `personId`) backs cabin-ui's own `PresenceToggle` picker, so changing it to the six-field contract would have broken a live feature, not refined one. **Two real deviations from the handover's own spec, flagged not guessed past:** (1) family-hub.html is vanilla JS, not React as the handover's "hook"/"component" framing assumed — CLAUDE.md had this right; shipped as a plain `refreshPresenceBadge()`/`renderPresenceBadge()` function pair mirroring the file's own existing `refreshCabinActivity()` pattern. (2) confidence is a binary 1.0/0.0, not a real probability — the underlying signals (WiFi ARP check for `cabin/*`, HA Companion App GPS zone crossing for `home/*`, verified against `docs/ontology.yaml`'s two real presence automations) have no fractional confidence score to report. A manually-set profile (no timestamp to check freshness against, and the spec says no new persistence) always resolves to `unknown`. See the matching Discrepancy Log pin below. A real bug was caught by family-hub's own Playwright suite before this ever reached production traffic unnoticed: the badge's hide/show used the `hidden` attribute, which `.glass-card`'s own `display:flex` rule silently defeated (author CSS always outranks the UA `[hidden]` default) — fixed to this file's own established `style.display` idiom. 11 new backend unit tests + 8 new Playwright checks (81 total, 0 failed) + the two pre-existing presence test classes, all green. Live-verified: `/api/presence/contract` 401s anonymous, and the badge correctly stays hidden (not stuck visible) for a real signed-out visitor at `hub.unicornpingpong.com`. shipped 2026-09-06 · [fa012ed](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/fa012ed) + [6d9ef25](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/6d9ef25)

Optimization Opportunities — analytics job, `/api/opportunities`, new card in the Rules & Alerts sidebar (WSJF ≈4.4 in Cowork's 2026-09-05 Sprint 5 r5 handover, "START HERE"). **Real deviation from the handover's own spec, flagged not guessed past — see the matching Discrepancy Log pin below for the full reasoning:** `POWER_DRAW_ANOMALY` replaces the handover's ENERGY_IDLE rule (the only two real `POWER_METER` devices include the mechanical-room freeze-prevention heater, which is *supposed* to draw power while unoccupied in cold weather — "waste" framing would have been actively unsafe messaging); COMFORT_DELTA is deferred outright (zero real thermostats exist anywhere in this codebase to ever trigger it). New `optimization_opportunity` table (open/acknowledged/resolved, admin-driven only — the hourly `@Scheduled` job never auto-resolves a row), `GET`/`PATCH /api/opportunities` (ADMINISTRATOR-only, gated in `WebConfig`), new card in the existing Rules & Alerts panel rather than a new nav tab (avoids confusion with the pre-existing Tech ID Service "Opportunities" tab, a different data source entirely). 19 new backend + 12 new frontend tests; real `mvn test` against Testcontainers/real Docker on the M920q CI runner confirmed green, not just compiled locally. shipped 2026-09-06 · [3a105d5](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/3a105d5)

D15 Energy Device Ontology — measurement_type vocab added, Monitoring's hardcoded "Energy" label fixed to a real curated label/device.name shipped 2026-09-05 · [de4084a](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/de4084a)

D16 Reporting Topics IA — five reports_to\[\] constants + comfort_air/energy wiring shipped 2026-09-05 · [add8a75](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/add8a75); security_presence binary-expose capture + voltage/current POWER_METER scope ratified · [3a9f9e1](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/3a9f9e1); alert_history/occupancy confirmed as computed views only; Topic-picker UI (5-tab strip in SensorHistoryPanel) shipped and live-verified 2026-09-05 · [380d227](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/380d227)

Area pipe (D15's own Sprint 5 prerequisite, WSJF \#1 in Cowork's 2026-09-05 Sprint 5 handover) — `DeviceMetadata.area` now flows adapter-repository → `GET /api/devices` → Device Manager rows/detail/edit form → SensorHistoryPanel Topic-tab chips; new `PATCH /api/devices/{id}/area` is the only write path (Z2M has no location field to auto-derive from, see D15's own pin). Live-verified end-to-end in production. shipped 2026-09-05 · [e53e481](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/e53e481)

Vaultwarden OAuth credential store — real `store()`/`retrieve()` via the official `bw` CLI as a subprocess, replacing the always-throws stub (WSJF 3.9 in Cowork's 2026-09-05 Sprint 5 handover; see the Vaultwarden pin under Infrastructure Debt for the full encryption-model finding that changed the original REST-client plan, and `docs/MAINTENANCE.md` for the one-time Organization + API key + master password setup an operator still needs to complete). shipped 2026-09-05 · [004fc9b](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/004fc9b) + [a38f5d9](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/a38f5d9)

Managed Users admin UI + magic-link landing page (WSJF 3.6 in Cowork's 2026-09-05 Sprint 5 handover) — new `ManagedUsersCard` in Settings/Config (list/create/deactivate/reactivate/invite, mirrors the Tier 1 Guest Access card) and `MagicLinkLanding` at `/auth/magic/{token}` (consumes the emailed link, stores a distinct `ManagedSession` credential, redirects to `/`) — the two pieces that shipped 2026-09-02's backend never had a UI. A real, previously-dormant privilege-escalation hole was found and closed in the same commit: `POST /api/auth/session` already accepted a ManagedSession-authenticated caller (reachable since D14's 2026-09-04 exchange endpoint, just never exercised) and would have minted a full 30-day `CabinSession` with unrestricted `ADULT_HOUSEHOLD_MEMBER` trust for that email — silently dropping VIEWER's read-only rule and the deactivate-revokes-immediately guarantee, since `CabinSessionService` has no concept of a managed user at all. Fixed by rejecting that exchange outright when the request carries `REQUEST_ATTR_MANAGED_USER_ID`; `authedFetch` always sends a managed session as its own `Authorization: ManagedSession` scheme, never upgraded. 23 new/updated backend tests (2 regression) + 8 new frontend tests. 385 backend (30 pre-existing local Docker-unavailable errors, matching every prior session on this dev machine) + 287 frontend, all green. shipped 2026-09-05 · [e64ba6b](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/e64ba6b)

Bug Fix Sprint Complete — 2026-09-05 · [8f424f6](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/8f424f6)

**1. Guest view scopes** — `GoogleAuthInterceptor` guest-token path now maps each scope to covered path prefixes; `observations_read` covers both `/api/events/telemetry-history` and `/api/events/reported-fields`. Frontend `GuestDashboard` fetches each granted scope's endpoint concurrently; "invalid link" is now reserved for genuine 401s. Insurance adjuster use case (humidity, temp, CO, air quality via `device_states` + `observations_read`) verified. 8f424f6

**2. Kidde CO false-OFFLINE** — `DeviceHealthMonitor`: CO_SENSOR-typed devices are exempt from time-based staleness (resting silence = healthy, per D13's `event_driven` reporting_mode rule); self-corrects devices already parked at OFFLINE. New `DeviceEventLookup` provides 30-min recent-telemetry cross-check for all other device types. 8f424f6

**3. Kidde CO-level entity classification** — CO-level entity had no `device_class` or unit, causing it to fall through `HomeAssistantDiscoveryService.inferType()` to the wrong DeviceType. Fixed via friendly-name fallback ("… CO Level" → CO_SENSOR). Grafana `cabin-telemetry.json`: corrected one panel description that mislabeled the combined query. Note: `co2` vocabulary and `CO2_SENSOR` are real, correctly-functioning, distinct entries — the Kidde unit has a separate co2-level HA entity (MOX sensor, continuous) that was unaffected. 8f424f6

**4. ServiceEntity.display_label (D13)** — `DeviceReportingRelationship` + `JdbcDeviceReportingRelationshipRepository`: `display_label` column; set-only-on-existing-row semantics (never fabricates or clobbers). `DeviceController`: GET/PATCH pair. New `ServiceDisplayLabelSeeder`: seeds the 7 live-verified service entities (Kitchen/Mech Room humidity+temp, Upstairs CO/Temp/Humidity). Wired into `SensorHistoryPanel` legend/table headers and `kpiTileFor` tile titles. 8f424f6

**5. Platform Info/Settings panel (admin-only)** — new `PlatformInfoService`: live-fetched HA/Z2M/Ollama/cabin-backend versions with graceful degradation; 6-row hardware catalog; AI-inference disclosure (local model, M920q, Tailscale-only). Refrigeration row corrected in follow-on commit: Liebherr fridge ("Loonie Mc Frigerton") IS paired and reporting. 8f424f6 + [53cc842](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/53cc842)

586 backend · 272 frontend tests green at commit time.

Sprint 5 Complete — 2026-09-07

WSJF \#1 — HA_TOKEN deploy-time health guard. New `HaTokenCabinHealthIndicator`/`HaTokenHomeHealthIndicator` (Spring Boot Actuator) contribute to the existing `/actuator/health` aggregate; `deploy-cabin-backend.yml`'s existing health check already fails closed on a blank `HA_TOKEN`, no new CI step needed. New explicit `HOME_HUB_DEPLOYED` flag (default false) replaces the handover's proposed blank-`HOME_HA_URL` inference, which docker-compose.m920q.yml's own default would have made unreliable. Found and fixed a real bug before it shipped: Boot's default health-status order ranks `OUT_OF_SERVICE` above `UP`, which would have made home's permanent not-yet-deployed status fail every future deploy — `management.endpoint.health.status.order` added, verified empirically via a dedicated `ApplicationContextRunner` test. 10 new tests, 409 backend tests green. Live-verified: `/actuator/health` returns `{"status":"UP"}` in production right now. shipped 2026-09-06 · [a13fb55](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/a13fb55)

WSJF \#2 — JSON-LD context extension for KB Generator ontology terms. See the WSJF Priority Order entry above and the matching Discrepancy Log pin for the two deviations (extends D1's existing context/endpoint rather than a new one; dual snake_case/camelCase term aliases for real live JSON fields). shipped 2026-09-06 · [bea74d8](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/bea74d8)

WSJF \#3 — SmartThings/Ring adapter completion. `PlatformImportController.confirm()` (previously a `NOT_IMPLEMENTED` stub) now writes a real `DeviceRegistry` entry at `DeviceLifecycleState.CANDIDATE` and marks the import record confirmed; OAuth token-refresh added to both `SmartThingsImportProvider` and `RingImportProvider` (each reading its own platform-specific fields from `OAuthCredential.extra()`, no new credential surface); Device Manager gained a three-option "Add device" flow (`PlatformImportFlow` + `PendingImportRow`). Three real deviations from the r7 handover's own spec, flagged not guessed past — see the matching Discrepancy Log pin below: (1) `confirm()` lives on the controller, not either adapter class; (2) `DeviceLifecycleState` has no `ACTIVE` value — confirm deliberately lands at `CANDIDATE`, matching the enum that actually exists; (3) `registerCandidate()`'s in-memory-only persistence for CANDIDATE state means a confirmed device won't survive a backend restart until separately ACCEPTed — a real architectural gap, flagged not fixed. Also fixed a real regression this change surfaced: `OAuthCredential.isExpired()` needed `@JsonIgnore` to avoid breaking `VaultwardenOAuthCredentialStoreTest`'s strict round-trip test, caught via full-suite run. 425 backend tests / 0 failures (33 pre-existing Docker-unavailable errors, unchanged baseline) + 317 frontend tests / 0 failures. Live-verified: `/api/platform-import/records` correctly 401s anonymous. shipped 2026-09-06 · [cd14562](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/cd14562)

Matter/Thread adapter — deferred, pending Nate confirming hardware exists at the cabin.

Nest SDM adapter — deferred, pending Nate authorizing the Works-with-Google-Home paywall spend.

------------------------------------------------------------------------

## Open Pins

⚠ Ontology Schema Discrepancy Protocol — read before assuming a D-section is fully built

Why this exists new 2026-09-05

Found while implementing D16: Cowork's own Q1 answer asserted "motion/contact/leak service entities already exist in `device_reporting_relationship` with `reporting_mode: event_driven`." Live-verified false on both counts (see log below) — not a small slip, since implementing on top of it would have silently produced nothing (no rows to tag). This is at least the second time a ratified or asserted ontology fact didn't match the actual schema (D13's `reporting_mode` property and D7's `reports_to[]` were both documented as real before either existed in code at all). A documented decision and an implemented one are not the same claim, and this doc doesn't currently distinguish them anywhere. That's the actual gap — not any one wrong fact.

Protocol — status vocabulary + escalation rule ratified 2026-09-05

The core rule now lives permanently in this doc's Axioms section ("A documented schema fact and an implemented one are not the same claim until ratified") — user's exact words on adopting it: "emphatically yes, these statuses are core to the usability and referential value of the ontology itself." The operational detail stays here, since it's the how-to, not the standing rule itself.  
  
**1. Verify before asserting, always.** A claim about the schema needs a stated verification method (live query, direct code read, a specific test) before it goes in a D-section as fact. "The ontology says so" is not itself verification of what's actually built.  
  
**2. Every schema-shaped decision gets one of four explicit statuses**, and the status travels with the fact wherever it's written (D-section prose, this log, a commit message):

- `flagged` — a real gap or contradiction found, no fix proposed yet.
- `proposed` — 2-3 concrete resolution options on the table, no side has picked one.
- `implemented — awaiting ratification` — Code (or Cowork) built something to close the gap under time pressure without a synchronous round-trip; it's live, but not yet confirmed as the agreed-on shape.
- `ratified` — both sides agree; safe to write into a D-section as settled, no longer belongs in this log.

**3. Two different kinds of gap get two different response speeds:** a *documentation drift* (a stale claim, a wrong count, a fact that's cheap to fix and doesn't change behavior — matching how the D13 CO/CO2 wording and the Refrigeration row were already handled this project) gets corrected directly, no approval needed, marked `ratified` immediately since there's nothing to disagree about. A *real unimplemented property or architectural choice* (reporting_mode's actual shape, reports_to\[\] as array-vs-computed, whether ServiceEntity should be a real class) gets `proposed`, not guessed — implementation waits for a decision, exactly like Q1's binary-expose-capture question was paused this session instead of silently building on the wrong premise.  
  
**4. No live synchronous channel exists between Code and Cowork sessions** (checked directly — ListAgents finds no other session running). The practical mechanism is this log: either side adds/updates an entry here, and the other reconciles it into the relevant D-section on their next pass. Escalate through the user in chat only when a decision is actively blocking shipped work and can't wait for an async artifact round-trip.

Discrepancy Log

`ServiceEntity.reporting_mode` — documentation concept, DeviceType-gated hardcode ratified ratified 2026-09-05

**Decision (Cowork, 2026-09-05):** Keep as a DeviceType-keyed hardcode (`DeviceHealthMonitor.isEventDriven()` CO_SENSOR check) until a second event-driven device type actually needs onboarding. `reporting_mode` in D13's property table is relabeled from "Required" to a documentation concept — it describes the semantic distinction (continuous vs. event_driven vs. polled), not a runtime column. Will become a real DB column with a Flyway migration when a second event-driven device type appears. D13's property table updated to reflect this.

`Entity.reports_to[]` — computed-singular ratified ratified 2026-09-05

**Decision (Cowork, 2026-09-05):** `DeviceReportingRelationship.reportsTo()` as a singular computed value (from measurement_type, not a stored column) is the ratified implementation. The spec's array notation was defensive overdesign — no measurement_type under the current 5-topic scheme needs dual Topic assignment, and adding a stored array solely for hypothetical future use would be something to keep in sync for no present benefit. If a future measurement_type genuinely needs two Topics, the shape can be revisited with a real migration at that point. Array notation in D7/D13/D16 prose is kept as written (it describes the concept correctly) but the implementation is explicitly singular-computed.

Motion/contact/leak binary-expose capture + `security_presence` wiring ratified 2026-09-05

**Decision (Cowork, 2026-09-05):** In D16 scope, not split. Scope: MOTION_SENSOR / CONTACT_SENSOR / WATER_LEAK_SENSOR → `security_presence`, DeviceType-keyed second `topicFor()` overload.  
  
**Shipped (Code, 3a9f9e1):** Binary-expose capture extended into `Zigbee2MqttAdapter.extractVendorReportedFields()`; `security_presence` wired via a DeviceType-keyed overload of `topicFor()` — implementation tightened to match Cowork's artifact revision. 374 backend tests green.  
  
**Ratified (Cowork, 2026-09-05):** DeviceType-keyed `topicFor()` overload shape and scope confirmed as the agreed-on design.

D15 Area display rule — pipe shipped, admin-curated not adapter-derived ratified (softened) 2026-09-05, pipe shipped 2026-09-05

**Decision (Cowork, 2026-09-05):** Rule softened from "non-negotiable, always show" to "show Area when known, omit gracefully when null." The end-to-end Area pipe (adapter writes it → DB column → main `/api/devices` response → UI) is a Sprint 5 prerequisite tracked separately. D15's UI display rules updated accordingly.  
  
**Shipped (Code, 2026-09-05, [e53e481](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/e53e481)):** read + write path both built and live-verified end-to-end. **Real deviation from the original "adapter writes it" framing, flagged rather than guessed past:** Z2M's actual `bridge/devices` payload has no area/location field to write from, and its `friendly_name` is the same string that already becomes the device's `entity_id` — an adapter-side heuristic parsing area out of it would be exactly the "fabricate area from entity_id" pattern this doc's own hard rule forbids elsewhere. The admin PATCH (`PATCH /api/devices/{id}/area`) is the only write path shipped.  
  
**Product decision (Cowork, 2026-09-06):** Admin PATCH is the permanent write path. Z2M's actual `bridge/devices` payload carries no area/location field — confirmed directly against the live payload. A heuristic parsing area from `entity_id` would be the exact "fabricate area from entity_id" anti-pattern this document's own hard rule forbids. The `PATCH /api/devices/{id}/area` write path is correct, intentional, and final. This pin is fully closed.

D15 voltage/current scope — fix at capture, POWER_METER only ratified 2026-09-05

**Decision (Cowork, 2026-09-05):** Fix at capture. `voltage`/`current` energy topic assignment scoped to `DeviceType.POWER_METER` only; all other device types resolve to `reports_to: []`.  
  
**Shipped (Code, 3a9f9e1):** Live regression fixed — door contact sensors' battery voltage no longer tagged `reportsTo: "energy"`; real power meters' voltage still correctly does.  
  
**Ratified (Cowork, 2026-09-05):** Device-context injection point and POWER_METER-only scope confirmed as the agreed-on shape.

`Device.device_class` / `user_provided_name` / `device_name_from_system` — accepted deviation ratified 2026-09-05

**Decision (Cowork, 2026-09-05):** Code's deviation is accepted. `DeviceType` already provides the categorization role `device_class` would add; Z2M's `friendly_name`, already stored as `device.name`, already is the user-editable identity — so the `user_provided_name`/`device_name_from_system` split has no concrete case where it would change behavior today. These three properties are removed from the "owed" list; they will be revisited only when a concrete situation (a second identity source that conflicts with Z2M's name, or a device type whose categorization DeviceType can't represent) actually materializes. D15's new entity taxonomy table retains them as a specification reference — the correct architecture if the split is ever needed — but they are not a pending implementation gap.

Optimization Opportunities — POWER_DRAW_ANOMALY replaces ENERGY_IDLE; COMFORT_DELTA deferred ratified 2026-09-06

**Found while shipping Sprint 5's WSJF ≈4.4 item (Cowork's 2026-09-05 r5 handover, "START HERE"), before writing any code, not guessed past:** the handover's two starter opportunity types don't hold up against the real device inventory, verified via direct research rather than assumed either way.  
  
**COMFORT_DELTA (HVAC setpoint vs. ambient) — deferred outright:** zero real thermostats exist anywhere in this codebase. Both `THERMOSTAT` descriptors in `DeviceRegistry` are disabled, home-location, pending future hardware — this would have shipped as dead code with nothing to ever trigger it. Deferred alongside Matter/Thread and Nest SDM, same treatment.  
  
**ENERGY_IDLE ("power draw during unoccupied hours = waste") — replaced, two disqualifying problems, either one alone would be enough:** (1) "unoccupied hours" has no historical, queryable signal anywhere — `PresenceService`/`PresenceSignalRegistry` are current-only and never persisted, so there is no way to ask "was anyone home at time T" for any past T. (2) The only two real, live `POWER_METER` devices are `zigbee_heater_mech_room` (the mechanical-room **freeze-prevention heater**) and `zigbee_smart_switch_breaker_box`. The heater is *supposed* to draw power while the cabin is unoccupied in cold weather — that is exactly its job. Flagging that as "wasted energy" in a cabin with a documented freeze/flood history (the active insurance claim this project already tracks) would have been actively unsafe messaging, not a nitpick.  
  
**Shipped (Code, 2026-09-06, [3a105d5](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/3a105d5)):** `POWER_DRAW_ANOMALY` — self-relative (a device drawing power continuously past its own recent pattern, detected by an hourly `@Scheduled` job querying `cabin_event` history via two new `CabinEventService` primitives; never compared against an occupancy judgment) and never asserts "this is waste," only "worth a look" — true and useful for every device type, including the heater itself (a stuck contactor or sensor fault keeping it on unnecessarily is exactly the kind of thing this should surface). New `optimization_opportunity` table (open/acknowledged/resolved, admin-driven only — the job creates or refreshes a row but never resolves one, so an admin's decision is never silently overwritten), `GET`/`PATCH /api/opportunities` (ADMINISTRATOR-only on every method, gated in `WebConfig`), and a new card in the existing Rules & Alerts sidebar rather than a new nav tab — avoids nav-rail bloat and confusion with the pre-existing Tech ID Service "Opportunities" tab (externally-sourced findings, a different data source and lifecycle entirely). 19 new backend tests (store/job/controller) + 5 new `CabinEventService` tests + 12 new frontend tests; real `mvn test` against Testcontainers with actual Docker on the M920q CI runner confirmed all green, not just compiled locally.  
  
**Ratification (Cowork, 2026-09-06):** Both substitutions ratified. `POWER_DRAW_ANOMALY` is the correct rule given the real hardware: no historical occupancy signal exists, and "idle waste" framing on a freeze-prevention heater would have been actively unsafe in a cabin with a documented freeze/flood history. The self-relative anomaly approach is a strictly better rule regardless of device type — it surfaces a stuck contactor on the heater as readily as unnecessary draw on the breaker-box switch, without requiring occupancy data that doesn't exist. COMFORT_DELTA deferred pending real thermostat hardware — not a gap, a correct call. This pin is fully closed.

Family Hub presence contract — separate endpoint, not a reshape; binary confidence; vanilla JS architecture ratified 2026-09-06

**Found while shipping Sprint 4's remaining WSJF \#1 (Cowork's r6 handover, "START HERE"), before writing any code, not guessed past:** two places the handover's spec didn't hold up against the real codebase.  
  
**"React front-end" framing — factually wrong, not a nitpick:** the handover asked for a `usePresenceContract` hook and a `PresenceBadge` component. `family-hub/family-hub.html` is vanilla JS with no build step (confirmed directly: no React import, no JSX, no hooks anywhere in the file; `family-hub/package.json`'s own description says "static, no build step") — CLAUDE.md's existing description was correct, the handover's was not. Shipped instead as a plain function pair, `refreshPresenceBadge()`/`renderPresenceBadge()`, mirroring this file's own pre-existing `refreshCabinActivity()`/`renderCabinActivityWidget()` pattern exactly (same state-machine-into-a-render-function shape, same hand-built `Authorization: Bearer` header every other authenticated call in this file already uses — there's no `authFetch`/`authedFetch` wrapper in this file at all).  
  
**`GET /api/presence/contract` is a new, separate endpoint — the handover asked to "confirm or extend" the existing `GET /api/presence` in place:** that endpoint's real shape (`profile`/`options`/`autoDerived`/`signals[]`, the latter including a real `personId` like `"nate"`) is what cabin-ui's own `PresenceToggle` picker and `usePresence` hook already consume live. Reshaping it to the handover's six-field contract in place would have been a real regression to a shipped, working feature, not a refinement — so the contract ships as an additive sibling endpoint instead, both covered by the same existing `/api/presence/**` WebConfig gate.  
  
**Confidence is binary (1.0/0.0), not the spec's implied continuous 0–1 score:** the two real presence signals this instance has — a WiFi ARP check for `cabin/presence/*`, an HA Companion App GPS zone crossing for `home/presence/*` (both confirmed directly against `docs/ontology.yaml`'s `automation_cabin_security_publish_nate_presence_from_phone` and `automation_home_presence_publish_nate_presence_from_phone_gps` entries) — are themselves binary detections with no fractional confidence of their own. Synthesizing a fake fractional value would fabricate precision nothing upstream actually has. A manually-set profile (`PresenceService.set()`, the cabin-ui toolbar override) always resolves to `unknown` in this contract specifically: no timestamp exists to check freshness/expiry against, and the handover's own "no new persistence" constraint rules out adding one just to support this case.  
  
**Shipped (Code, 2026-09-06, [fa012ed](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/fa012ed) + [6d9ef25](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/6d9ef25)):** `PresenceContractV1` (new file, `presence/` package) is a point-in-time projection over `PresenceSignalRegistry`'s live signals — state home/away/unknown, a 30-min freshness/expiry window, coarse `sources` (`wifi_presence` / `phone_gps`, mapped from each signal's own `location` field, never a person id). Family Hub's badge polls every 5 minutes, flips to unknown exactly at `expires_at` via a local timer (no extra network call), and treats an unrecognized `policy_version` as unknown with a console warning. 11 new backend unit tests + 8 new Playwright checks in `family-hub/test/run.js` (81 total, 0 failed) — the real Playwright suite caught one genuine bug before it shipped further: the badge's hide/show used the `hidden` attribute, silently defeated by `.glass-card`'s own `display:flex` rule (author CSS always outranks the UA `[hidden]` default regardless of specificity), fixed to this file's own established `style.display` idiom in a same-day follow-up commit. Live-verified: `/api/presence/contract` 401s anonymous, and the badge correctly stays hidden (not stuck showing default text) for a real signed-out visitor at `hub.unicornpingpong.com`.  
  
**Open question for Cowork:** is a separate `/contract` sub-path the right long-term shape, or should `GET /api/presence`'s own response eventually be versioned/content-negotiated instead of growing a second endpoint? No urgency either way — both endpoints are additive and nothing currently depends on unifying them — but flagging so this doesn't silently calcify as two endpoints by default.  
  
**Ratification (Cowork, 2026-09-06):** All three deviations ratified. (1) **Vanilla JS architecture**: CLAUDE.md had this right; the handover's React/hook/component framing was wrong. The plain function pair (`refreshPresenceBadge()`/`renderPresenceBadge()`) mirroring the file's own existing pattern is correct and consistent. (2) **Separate `/contract` endpoint**: the right call. `GET /api/presence`'s real shape serves cabin-ui's live PresenceToggle; reshaping it would have been a regression, not a refinement. The endpoint-shape question — separate sub-path vs. eventual versioning of `/api/presence` directly — is an open architectural note carried forward as low-urgency: both endpoints are additive today, nothing depends on unifying them, and the decision can wait for a concrete pressure that makes one shape clearly better than the other. (3) **Binary confidence**: correct — the underlying signals (WiFi ARP, GPS zone crossing) have no fractional confidence to report; fabricating one would invent precision the data doesn't have. This pin is fully closed.

HA_TOKEN health guard — no new CI step; HOME_HUB_DEPLOYED flag instead of blank-URL inference ratified 2026-09-07

**Found while shipping Sprint 5 WSJF \#1 (Cowork's r7 handover, "START HERE"), before writing any code, not guessed past:** two places the handover's spec didn't hold up against the real deploy pipeline and compose config.  
  
**No new CI step needed:** the handover asked for a new post-deploy step to GET `/actuator/health` with `show-details: always` via a local call. `deploy-cabin-backend.yml`'s existing health check already does `curl -sf .../actuator/health | grep -q '"status":"UP"'` — Spring Boot's aggregate `status` field and its HTTP-code mapping reflect every registered `HealthIndicator`'s contribution regardless of `show-details` (that setting only governs whether the `details` map is rendered, confirmed directly against this app's own D14-era comment on why `show-details: never` was chosen). A blank `HA_TOKEN` flips the aggregate to `DOWN` and the existing check already fails on that, no new step or detail-exposure mechanism required — which also means `show-details: never` (the 2026-09-01 fix for a real public-info leak) never had to be touched.  
  
**`HOME_HA_URL` blank isn't a usable "not deployed yet" signal, as the handover assumed:** `docker-compose.m920q.yml` sets `HOME_HA_URL: ${HOME_HA_URL:-http://home-hub:8123}`, so the container-side env var is never actually blank — it always resolves to that default, which is also the real intended hostname once home ships (per CLAUDE.md's own Tailscale hostname table). A blank-URL check would either never fire or misfire once home is genuinely live and still using that hostname. Added an explicit `HOME_HUB_DEPLOYED` flag instead (default false, operator-set once real) — `infra/.env.m920q.example` and `docker-compose.m920q.yml`'s `cabin-backend` environment block (an explicit allowlist, not an `.env` passthrough — same lesson as this project's own Vaultwarden-wiring history).  
  
**Real bug caught before it shipped, not by the handover's spec:** Spring Boot's default health-status order ranks `OUT_OF_SERVICE` above `UP` in the aggregate. Home's own indicator permanently reports `OUT_OF_SERVICE` today (home hub genuinely isn't deployed) — left at the default order, that alone would have dragged every future deploy's health check to non-`UP` forever, rolling back good releases. Added `management.endpoint.health.status.order: down,up,out-of-service,unknown`, verified empirically (not just from documentation) with a dedicated `ApplicationContextRunner` test that reproduces the failure with the override removed, then confirms the fix.  
  
**Shipped (Code, 2026-09-06, [a13fb55](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/a13fb55)):** `HaTokenCabinHealthIndicator`/`HaTokenHomeHealthIndicator`, 10 new tests, 409 backend tests green. Live-verified: `https://api.unicornpingpong.com/actuator/health` returns `{"status":"UP"}` / HTTP 200 right now, confirming both indicators are live and the status-order fix correctly keeps `UP` despite home being `OUT_OF_SERVICE`. A separate, unrelated `deploy-production-stack.yml` run failed on this same push — the already-tracked `.bak`-file dirty-worktree blocker (Infrastructure Debt pin below), not caused by this change.  
  
**Ratification (Cowork, 2026-09-07):** All three deviations ratified. (1) **No new CI step:** correct — the existing `curl -sf .../actuator/health | grep -q '"status":"UP"'` already fails closed on a bad indicator; adding a second step would have been redundant and would have required exposing `show-details` unnecessarily. (2) **`HOME_HUB_DEPLOYED` flag:** correct — blank-URL inference was never viable given compose's own default-value resolution; an explicit boolean is the right design and matches the pattern this project uses elsewhere for "not yet deployed" gating. (3) **Spring Boot status-order fix:** good catch — an `OUT_OF_SERVICE` home indicator permanently dragging the aggregate below `UP` would have silently rolled back every good future deploy with no visible cause; empirical verification via `ApplicationContextRunner` rather than documentation trust is the right bar for a Spring Boot default-behavior assumption. This pin is fully closed.

JSON-LD context — extends D1's existing endpoint, doesn't create a new one; dual-cased term aliases ratified 2026-09-07

**Found while shipping Sprint 5 WSJF \#2 (Cowork's r7 handover), before writing any code, not guessed past:** D1's JSON-LD identity work already exists and is live — `JsonLdController`/`JsonLdService`/`cabin-context.jsonld`, shipped "Sprint 2" per `docs/ontology/SPRINT-STATUS.md` ("Shipped, verified live"), served at `GET /api/context/cabin-context.jsonld` under the `cabin:` IRI base `https://cabin.unicornpingpong.com/entity/`. The handover's ask for a new `cabin-ontology.jsonld` document at a new `GET /api/ontology/context` endpoint, under a different proposed base (`https://unicornpingpong.com/ontology/v1/`), would have fragmented the vocabulary space this project already made real and dereferenceable, not extended it — especially since `cabin:{entity_id}` IRIs are D1's whole point.  
  
**Extended the existing file/endpoint instead** with every term the handover named: `KnowledgeNode`, `KnowledgeChunkType`, `ServiceEntity`, `DeviceType`, `HouseholdRole`, `measurement_type`, `reports_to`, `derived_from`, `capabilities`, `reporting_mode`, `display_label`, `policy_version` — reusing established vocabularies per the handover's own instruction where one fits exactly (`prov:wasDerivedFrom`, `ssn-system:hasSystemCapability`, `schema:schemaVersion`, `sosa:observedProperty`), coining `cabin:`-prefixed terms only where none does.  
  
**Dual snake_case/camelCase terms where the real live JSON field differs from the ontology docs' own snake_case naming:** `measurementType`/`reportsTo`/`displayLabel` are real fields (confirmed on `DeviceReportingRelationship` and `DeviceController.ReportingRelationshipView`), added alongside their snake_case documentation-facing counterparts so the same context can process real API responses, not just serve as prose-adjacent reference. Deliberately did NOT alias `derived_from` to `DeviceReportingRelationship.deviceId` — that field name means "this device's own identity" almost everywhere else in the API, so a blanket alias would be wrong more often than right.  
  
**Shipped (Code, 2026-09-06, [bea74d8](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/bea74d8)):** `GET /api/kb/nodes` now sends a `Link: </api/context/cabin-context.jsonld>; rel="http://www.w3.org/ns/json-ld#context"; type="application/ld+json"` header per the handover's own instruction not to embed the context inline. 1 new backend test (real `ObjectMapper` parse confirming every required term resolves) + 1 new Link-header test + 3 fixed call sites for the now-`ResponseEntity`-wrapped `nodes()` method. 410 backend tests total, 0 failures.  
  
**Also found in passing, not touched:** `docs/ontology/DECISIONS.md` already carries an explicit "no longer source of truth, see the web artifact" banner (added 2026-09-04) — the file-based ontology docs and this live artifact are NOT in silent conflict, someone already reconciled this. `docs/ontology/SPRINT-STATUS.md` lacks the same banner but reads clearly as a historical Sprint 0-2 record, not actively misleading.  
  
**Ratification (Cowork, 2026-09-07):** All three deviations ratified. (1) **Extending existing endpoint:** correct — creating a new document at a different IRI base would have fragmented the vocabulary space that `cabin:{entity_id}` IRIs already anchored; D1's whole point is a single, coherent, dereferenceable identity scheme. (2) **Dual camelCase/snake_case aliases:** correct — a context that can only process documentation-facing snake_case terms but not the real API's camelCase fields isn't actually usable against live responses; the dual aliases close that gap without changing the live API shape. The `derived_from`/`deviceId` non-alias is also correct: `deviceId`'s meaning is too overloaded in this API to safely alias globally. (3) **Context split open question:** carried as a low-urgency architectural note. No action now — the file is still small and internally coherent, and splitting it before a consumer requires the split creates a maintenance burden for no immediate benefit. The right trigger is a second consumer that genuinely needs a context scoped to only a subset of the vocabulary. This pin is fully closed.

SmartThings/Ring `confirm()` — no ACTIVE lifecycle state exists; CANDIDATE-only, restart-durability gap, no provenance-tag column ratified 2026-09-07

**Found while shipping Sprint 5 WSJF \#3 (Cowork's r7 handover), before writing any code, not guessed past:** three places the handover's spec didn't hold up against the real codebase.  
  
**`confirm()` doesn't live where the handover assumed:** the handover described wiring `confirm()` onto the SmartThings/Ring adapter classes themselves. The real stub was always on `PlatformImportController.confirm()` — `SmartThingsImportProvider`/`RingImportProvider` only implement `PlatformImportProvider`'s `platform()`/`listDevices()` and have no `confirm`-shaped method to wire at all.  
  
**`DeviceLifecycleState` has no `ACTIVE` value, contradicting the handover's repeated "set lifecycle to ACTIVE via PATCH" language:** the real enum is `CANDIDATE, AVAILABLE, ASSIGNED, DEFERRED, IGNORED` (confirmed directly against the enum and its own `isInScope()`/`allowsActiveUse()` predicate methods), and the real endpoint is `POST /api/devices/{id}/lifecycle` with an action name in the body (e.g. `{"action":"ACCEPT"}`), not a PATCH to a target state — both already built, no new endpoint needed. `confirm()` deliberately registers the device at `CANDIDATE` via `DeviceRegistry.registerCandidate()` and stops there; promoting it to active use is the existing, unchanged Accept action from Device Manager's own See → Candidates view.  
  
**Real, unfixed architectural gap surfaced by this design, not papered over:** `registerCandidate()` is in-memory-only for CANDIDATE state — nothing persists it to Postgres until a later `applyLifecycleAction()` call. Platform-imported devices have no ongoing rediscovery loop (unlike Z2M/HA's continuous re-announce), so a confirmed-but-not-yet-Accepted device will silently vanish from the registry on a backend restart. Flagged here rather than fixed — closing it means deciding whether to persist CANDIDATE rows directly or add a scheduled re-sync job against `platform_import_record`, a real architectural call, not a quick patch.  
  
**No DB column exists for D10's "provenance tag: `imported:platform_name`":** `DeviceMetadata`/`DeviceRepository.upsert()` has no matching field, and `upsert()` itself is a no-op until a real `device` row exists — which CANDIDATE state doesn't create. Passed `importedFrom`/`vendor` as a runtime `DeviceStatus.attributes` entry via `registerCandidate()`'s `discoveryAttributes` map instead of inventing a schema change; this is a display-layer stand-in, not the persisted provenance tag D10 describes.  
  
**Shipped (Code, 2026-09-06, [cd14562](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/cd14562)):** `PlatformImportController.confirm()` real implementation (admin gate, field validation, 404/409 conflict handling, `DeviceRegistry.registerCandidate()` + `PlatformImportRecordRepository.markConfirmed()`); OAuth refresh for both adapters via `OAuthCredential.extra()`; three-option Device Manager Add flow. 15 new/updated controller tests + 3 refresh-fixture tests per adapter + 3 `OAuthCredential.isExpired()` tests. 425 backend / 317 frontend tests, 0 failures.  
  
**Ratification (Cowork, 2026-09-07):** All three deviations ratified. (1) **`confirm()` on the controller:** correct — the handover had the location wrong; the adapter interface only exposes `platform()`/`listDevices()`, and confirmation is a controller-level orchestration concern, not a per-provider concern. (2) **No ACTIVE state, CANDIDATE-only:** correct — the existing Accept action (`POST /api/devices/{id}/lifecycle` + `{"action":"ACCEPT"}`) is the right promotion path; inventing a new PATCH-to-ACTIVE would have duplicated an existing lifecycle mechanism. Landing at CANDIDATE and letting the admin drive acceptance is the right design for a platform-imported device that hasn't been reviewed yet. (3) **Restart-durability gap + missing provenance-tag column — open question answered:** one WSJF item, not two. Both gaps share the same root cause (DeviceRegistry doesn't persist early lifecycle states) and the same fix path (decide whether CANDIDATE rows go directly to Postgres or get reconstructed via a scheduled re-sync against `platform_import_record`). That architectural call should be made consciously before the first real household device comes through the SmartThings/Ring flow — splitting the gaps would force two separate schema decisions that are really one. Priority: moderate — no urgency today (no production platform-imported devices exist to be lost on a restart), but must be resolved before the pipeline sees real household use. Schedule as a single WSJF item: "CANDIDATE persistence + D10 provenance tag." This pin is fully closed.  
  
**Shipped (Code, 2026-09-08, [PR \#46](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/46)):** went with direct-to-Postgres persistence over the scheduled-re-sync alternative -- the re-sync option would depend on each platform's live OAuth API, which can't run today anyway since the Vaultwarden one-time operator setup above is still pending. New `DeviceRegistry.registerPersistentCandidate()` persists through the existing `DeviceLifecycleStore`/extraAttributes JSONB plumbing (the same mechanism "room" already established) instead of a new column or subsystem; `PlatformImportController.confirm()` now calls it instead of the in-memory-only `registerCandidate()`. Closes both halves: the CANDIDATE state survives a restart, and `importedFrom` now rides that same durable slot instead of being an ephemeral runtime attribute. 4 new tests (3 DeviceRegistry + 1 PlatformImportController) proving restart-durability and provenance-tag survival via the existing shared-store test pattern; full backend suite 714 tests, 0 failures. Not yet re-ratified by Cowork.

Proposed D17 -- place/room graph edge, D6 revisited, new `data_class: product` lineage tier implemented — awaiting ratification 2026-09-11

**Found while doing the ontology groundwork Nate asked for before scoping Home as a real second location** (his own framing: "definitions of place/reason/definition/state/schema relationships/primitive vs derived vs product data will need enhancement") -- three real gaps in `docs/ontology.yaml`'s own place/lineage model, verified directly against the file rather than assumed.  
  
**1. `device_room` \<-\> `hub_location` relationship edge was missing entirely.** `hub_location` (the real, Postgres-backed site/property entity -- Cabin, Home, etc.) had relationships to `device_descriptor`, `cabin_grafana_public_access`, `hub_brand_block`; `device_room` (the room-within-site, L1 location) had relationships to `display_name`, `device_location_zone`, `device_location_formatted`, `audit_log`, `location_context` -- neither pointed at the other. The ontology could not formally answer "which site does this room belong to," only assume it implicitly via `DeviceDescriptor.location`'s own bare string. Added a new relationship type, `located_in` (`device_room` -\> `hub_location`, N:1), and its inverse `consumed_by` (`hub_location` -\> `device_room`, 1:N). Pure documentation, zero code change, verified bidirectional.  
  
**2. D6 revisited, still correctly deferred -- not escalated to a Java enum/FK migration.** `DeviceDescriptor.location` remains a bare Java `String` ("cabin"\|"home"), exactly as D6's own repo-local schema doc (`docs/ontology/schema/location.schema.json`) already flagged once as "a Sprint 1 stub... not a full migration... out of scope for Sprint 1." Reassessed now that a second real location is actually being scoped, and the call is the same: a real enum/FK migration touches `DeviceDescriptor`, every persistence path, and ~30 `App.jsx` call sites simultaneously -- real scope, correctly still deferred, not something to fold into a documentation pass. Flagging this explicitly as "still deferred" rather than silently letting D6 read as fully closed.  
  
**3. New `data_class: product` lineage tier, extending the existing `raw|derived|conceptual|composite` vocabulary (not a competing field).** Reused the exact versioning methodology this file already used once for v0.3.0-\>v0.4.0 (lifecycle_status/first_used/deprecated_date): bumped the file's own `version` field 0.3.0-\>0.5.0 (also independently found and flagged: that top-level field had drifted stale at 0.3.0/2026-07-28 even though per-entity `schema_version` tags already referenced 0.4.0/0.4.1 work done since -- not fully reconciled, just bumped and flagged), added `product` as optional, applied only to new/touched entries, no backfill. Definition: *data deliberately packaged/curated for direct external or customer-facing delivery (a curated KB answer, a marketing claim, a customer-facing report) -- defined by a human curation act with an accountability/freshness contract, not by computation method; `primitive` is always `false` for `product`.*  
  
**Concrete anchor for the new tier, closing a second real gap at the same time:** `docs/ontology.yaml` had zero entries for `knowledge_node`/`knowledge_chunk_type` despite the Tiny Helpdesk KB layer being live in code since Sprint 1 (2026-08-30, KB Generator v1). Added both. `knowledge_node`'s `data_class` is genuinely bimodal by row, not fixed for the class: `AUTO_GENERATED` rows (written only by `KbGeneratorService`, always device-backed) are `derived`; `MANUALLY_CURATED` rows (written only by `POST /api/kb/curate`) are `product`. The first `MANUALLY_CURATED` row not backed by any real `DeviceDescriptor` at all -- `entityRef: home-zigbee-coordinator-mr5u`, curated 2026-09-10 for the Home-collector POC -- is exactly the case that forced this distinction into the open rather than staying implicit.  
  
**Shipped (Code, 2026-09-11, [c925a03](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/c925a03)):** all three changes live in `docs/ontology.yaml` now. YAML validated (parses clean, no duplicate ids, edge confirmed bidirectional programmatically). Also flagged in the same commit, not silently fixed: the header's own relationship-type vocabulary comment (`derives_from | consumed_by | triggers | depends_on | replaces | extends | complements | monitors | controls | notifies`) has drifted stale relative to actual usage for a while -- `related_to`, `derived_from`, `applies_to`, `scoped_to`, `produces`, `consumes` all appear in real entries without ever being added to that list; not reconciled beyond adding `located_in`, since a full reconciliation is a separate task from this one.  
  
**Open question for Cowork:** should this land as a single new D17 covering all three (they share one motivating investigation), or split -- D17 for the place/room graph edge, D18 for the data-lineage tier? No strong preference either way; proposing the bundled framing above since D13 itself set the precedent of bundling several related property additions under one decision number.

Proposed D18 -- Camera-derived media entity naming (audio/motion clips) + download/access-log provenance concatenation proposed 2026-09-12

**Found while extending a live UI fix, not guessed past:** answering Nate's own question about wiring the toolbar's dead "devices/services" toggle to Device Manager's parent/service distinction, he extended it directly to a real product need -- naming and downloading camera-derived sub-attributes (audio clips, motion clips) with full inherited context, e.g. his own example: key name `sound_camera_blink_driveway`, technical name `blink_audio_clip` bounded by location `[driveway]` and a timestamp context tag, user-facing name "blink driveway audio."  
  
**Checked directly against this doc before proposing anything, per this doc's own Axioms rule -- two real conflicts found, both resolved directly with Nate before writing this pin:**  
  
**1. Term collision -- "DTM" already means something different and more specific.** `docs/ontology.yaml` ratifies `cabin_camera_continuous_recording` as "Continuous (DTM) Recording" -- 24/7 footage, browsable by date/time, explicitly defined as distinct from `cabin_camera_event_clip` (the buffered window around one detection). There's also a separate, already-shipped "DTM STAMP" UI feature (2026-08-07): a timestamp overlay rendered on event thumbnails. Nate's "context \[dtm\]" phrasing risked conflating a new, different concept (a media artifact's own timestamp) with the already-distinguished continuous-recording stream. **Resolved:** Nate confirmed directly he means only "this artifact carries a timestamp" -- the continuous-recording stream is not involved. The concatenation scheme below therefore needs its own distinct token for this, not the literal string `dtm`, to avoid colliding with the already-ratified meaning.  
  
**2. Curation-timing model -- appeared to conflict with D13/D15's `display_label` rule.** D13 and D15 both require a service entity's human-facing label to be "set at curation time... never dynamically assigned" -- specifically because that rule fixed a real production bug (Grafana/sensor-card labels showing the wrong thing). Nate's original phrasing ("assigned by user selections when they actually playback") read like a live, per-view assignment, which would have been a real deviation from that rule. **Resolved:** Nate confirmed the label must be fully computed and already present by the time the UI can first render it (i.e., at artifact-creation time) -- playback only ever selects among an already-stable label, never mints one live. This satisfies D13/D15's "stable before display" requirement, but via a different mechanism than D13's own human-typed curation (e.g. "Kitchen Humidity"): here the label is system-computed at creation time from inherited context (parent device + capability + location), not manually authored per instance. Flagging that distinction explicitly rather than letting it read as identical to D13's mechanism.  
  
**Confirmed directly against the real codebase, not assumed:** zero `audio` entity, zero camera-derived-service naming, and zero download-filename/access-log convention exist anywhere in `docs/ontology.yaml` or the backend today (`CameraMediaController`'s `snapshot()`/`clip()` proxy raw bytes with no `Content-Disposition` filename control at all). D13's `ServiceEntity` model is scoped to numeric `measurement_type` values feeding the `observations` table -- it has no coverage today for STREAM-capability derived media artifacts (event-anchored files via Frigate/Blink), which are structurally a different kind of thing than a time-series numeric reading. This is genuinely new ground, not a case of something already decided a different way.  
  
**Two concrete options, no side has picked one:**

- **Option A -- extend `ServiceEntity` itself** to also cover STREAM/media capabilities: `entity_id = {parent_device_entity_id}_{derived_capability}` (e.g. `blink_driveway_audio`), mirroring D13's `{device}_{measurement_type}` shape exactly, extending the controlled vocabulary beyond numeric `measurement_type` to include media capability names (`audio_clip`, `motion_clip`, ...). `display_label` computed automatically at creation time from device name + capability + location (e.g. "Blink Driveway Audio"), stored on the artifact/event record itself, optionally overridable later the same way D15's `user_provided_name` pattern allows. Pro: reuses an already-ratified pattern, minimal new vocabulary. Con: D13's `Observation` model is numeric/measurement_type-shaped -- a recorded media file doesn't fit that same row shape, so this would still need a sibling artifact-record concept alongside `Observation`, not instead of it.
- **Option B -- new sibling concept, `MediaArtifact`, parallel to `ServiceEntity`, not an extension of it.** Keeps D13's `ServiceEntity` strictly scoped to numeric telemetry as already ratified, and introduces a new entity kind for event-anchored recorded media (audio/video/snapshot) with its own `derived_from` (parent device + originating `CabinEvent`/`frigateEventId`), its own capability-type vocabulary, and the same "stable before render, computed at creation" `display_label` guarantee via a different mechanism than D13's human-typed one. Pro: doesn't stretch D13's already-ratified numeric shape to cover something structurally different. Con: two parallel "named derived entity" concepts in the ontology instead of one.

**Download filename / access-log convention (needed regardless of A vs B):** full snake_case concatenation of parent device identity + derived capability + location + a distinct timestamp token (not `dtm` -- proposing `ts`, or the literal timestamp value itself, e.g. `blink_driveway_audio_20260912t143000z`), computed backend-side at download-serve time from the artifact's already-stored fields, used as both the downloaded file's name and the access-log record's identifying string.  
  
**Open question for Cowork:** Option A or B, and what's the actual initial list of camera-derived capability types worth naming now (`audio_clip`, `motion_clip`, `snapshot` -- others?) -- that list is the controlled vocabulary this whole scheme hangs off of. Not implemented; per this doc's own protocol, waiting for a decision before any code is written.

Proposed D19 -- Local telemetry backup / log-shipping for cabin_event's hot tier (none exists today) A+B merged + deployed 2026-09-14 (deploy blocker found and fixed same day) -- C deferred to backlog

**Found while recovering from the same-day Zigbee mesh outage** (see `docs/MAINTENANCE.md`'s 2026-09-13 entry): the only reason that incident's ~34-hour data gap was recoverable at all is that Zigbee2MQTT's own `docker logs` happened to retain the whole window -- a lucky side effect of a different service's own log retention, not a designed recovery path.  
  
**Verified directly, not assumed either way (Nate asked specifically before this was documented as fact):** `cabin-postgres` (holding `cabin_event`) lives in a Docker-managed named volume on the SSD-backed root filesystem (`nvme0n1`), not on the separate HDD-backed `/storage` volume (`sda1`) at all. Frigate's recordings, Mosquitto's own data/log directories, and Zigbee2MQTT's service directory (confirmed via its own `coordinator_backup.json`) are all bind-mounted directly onto `/storage` -- Nate's recollection was correct for those three. It was *not* correct for `cabin_event` specifically: `TelemetryArchivalService` (shipped 2026-08-25) does export to `/storage/archives/cabin_event/*.jsonl.gz`, but only for rows already 3+ months old -- the live hot tier (exactly what this incident lost) has exactly one copy, full stop. No Logstash or equivalent log-shipping process exists anywhere on this host today.  
  
**Nate's own framing, worth keeping:** "one alternative is logstash or similar parallel process where we're backing up logs to another local drive... probably what I'll use another android for onsite anyway, backup server with a HD" -- explicitly an onsite, connectivity-independent design goal (re-spool everything locally during an outage), not just "add a backup job."  
  
**Three concrete options, no side has picked one:**

- **Option A -- extend `TelemetryArchivalService` itself** with a second, frequent (e.g. hourly) pass that incrementally appends new `cabin_event` rows to a `/storage`-backed JSONL file, independent of the existing 3-month cutoff. Minimal new surface, reuses the existing export format and cron infrastructure. Con: still depends on `cabin-backend`'s own process being healthy to run it -- would not have helped during this exact incident, where `cabin-backend` itself was the thing silently failing.
- **Option B -- an independent local log-shipping side-car** (Logstash, Vector, or a small purpose-built service) subscribed directly to the same MQTT topics `cabin-backend` consumes, appending raw messages to `/storage` continuously and completely independently of `cabin-backend`'s own health. True redundancy: would have made this incident's recovery push-button instead of a bespoke log-parsing exercise, since it doesn't depend on the thing that was actually broken. Con: a second consumer of the same MQTT stream, new service to operate and monitor.
- **Option C -- Nate's own onsite-Android-as-backup-host idea**, mirroring the existing Home-collector precedent (SLZB-MR5U + Termux): a second, cheap, physically separate device with its own attached HDD, running a lightweight MQTT-subscribe-and-log service. Most resilient option -- survives a whole-M920q failure (disk, power), not just a `cabin-backend` code bug, and matches Phase 8's Accessible Hardware Program direction already ratified elsewhere in this doc. Con: most new infrastructure to stand up and maintain.

**Open question for Cowork/Nate:** which option (B and C are not mutually exclusive with A), and what retention/rotation policy for whichever mirror gets built. Not implemented -- per this doc's own protocol, waiting for a decision before any code is written.  
  
**Decision (Nate, 2026-09-14):** ship A and B together now -- both were reasonable effort. Defer C (onsite Android backup host) to the WSJF backlog: no second device is provisioned for that role yet, and building it speculatively against hardware that doesn't exist isn't worth it. This directly targets "the MVP that aligns closest with a direct ideal resolution in the case of the exact same failure" -- Option B is the piece that actually answers the traced incident (it never depends on `cabin-backend`'s own health), Option A is the cheap complementary addition for every other failure mode.  
  
**Implemented (Code, 2026-09-14, [PR \#65](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/65), commit `c59a054`):** **Option A** -- new `TelemetryArchivalService.exportIncremental()`, hourly, additive-only: appends new `TELEMETRY` rows to a watermark-tracked JSONL file at `/storage/archives/cabin_event/incremental/`, never deletes from the live table, independent of the existing monthly archive-and-delete job. Explicitly flagged in its own javadoc as a partial mitigation -- it still depends on `cabin-backend` being alive to run the job, exactly what was NOT true during the traced outage. **Option B** -- new standalone Docker side-car, `infra/telemetry-backup-agent/` (same Node.js + `mqtt` shape as the Home `network-scan-agent`), subscribed directly to mosquitto on `cabin/#`/`home/#`/`zigbee2mqtt/#`/`home_z2m/#`, appending every message verbatim to `/storage/telemetry-backup/`, daily-rotated, 30-day default retention, pruned by the agent itself. Runs on the `cabin_default` network only -- no `depends_on` on `cabin-backend`/postgres/kafka, since the whole point is that it keeps recording even when `cabin-backend` doesn't. See `docs/MAINTENANCE.md`'s new D19 section for the full recovery procedure.  
  
**Merged 2026-09-14, commit `3070bea` on `main` -- but `deploy-cabin-backend.yml` went RED, found live while verifying an unrelated push (Code, D21 above), not by the authoring session:** the PR's own test-plan checklist left `TelemetryArchivalServiceTest`'s new coverage unchecked ("Testcontainers-gated, no Docker on this dev machine -- will run for real in CI"). It ran for real in CI on merge and two of its tests failed -- `exportIncrementalWritesNewTelemetryRowsWithoutDeletingThem` and `exportIncrementalOnlyExportsRowsAfterThePreviousWatermarkOnASecondRun`, both reporting the incremental JSONL file never got written. Because `deploy-cabin-backend.yml`'s test gate stops the deploy on any failure, `cabin-backend` stopped redeploying at all from that point -- Option A's own code sat on `main` without ever going live, and every subsequent backend-touching push kept failing the same gate.  
  
**Root-caused and fixed (Code, 2026-09-14, stewardship assumed at Nate's direct request, [PR \#68](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/68), merged):** a test-only bug, not a production one -- `TelemetryArchivalServiceTest` constructs the service via a bare `new`, bypassing Spring's IoC container entirely, so `@Value("${cabin.telemetryArchival.enabled:true}")` never runs and `enabled` keeps Java's raw boolean default, `false`. `exportIncremental()`'s own first line is `if (!enabled) return;` -- both failing tests were silently returning before the query or filesystem write ever happened. Three other tests in the same file already knew to set `enabled` explicitly via `ReflectionTestUtils`; this was a gap specific to the two new D19 tests (plus a third that was passing for the same wrong reason, fixed alongside). Production code was never affected -- the real Spring context injects `enabled=true` correctly.  
  
**Confirmed live, deployed (2026-09-14):** `deploy-cabin-backend.yml` ran `mvn test` for real in CI this time -- green, image built, health check passed, `cabin-backend` redeployed successfully. Option A's own code is now genuinely live on the M920q, not just merged to `main`. **Not yet ratified by Cowork** on the original D19 implementation shape (Options A+B) -- unaffected by this fix, which was purely about the test gate.

Proposed D20 -- Live MQTT tile: real read-only relay, not a broker proxy merged + deployed 2026-09-14 — awaiting Cowork ratification

**Found while fixing an unrelated mixed-content browser error** (cabin-ui's Live MQTT tile connecting to `ws://100.77.44.113:9001` from an `https://` page, blocked by every browser). The obvious-looking fix -- proxy the WebSocket same-origin through `cabin-ui`'s own nginx -- was caught and stopped mid-implementation, not shipped: it would have made mosquitto's raw broker (real MQTT pub/sub, `allow_anonymous true`, no topic ACLs) reachable from the public internet, silently reversing a deliberate prior decision documented in `docker-compose.m920q.yml`'s own `VITE_CABIN_WS_BASE` comment ("Left Tailscale-only deliberately: raw MQTT pub/sub (device control channel, not just viewing)... a meaningfully different risk than the read-only status/link-out this round is actually about").  
  
**Applying D14's own framework directly, not inventing a new one:** D14's threat model asks "does this reveal occupancy" for read surfaces, and explicitly keeps every write/admin path gated regardless. A raw, unscoped, unauthenticated MQTT connection isn't a read surface at all by that framework -- it's a de facto write/control path (anyone who can open the WebSocket can publish to any topic, including `main_water_valve/set` or a lock's command topic), so D14 already answers this: it must stay gated, full stop. The nginx-proxy approach was rejected on that basis alone, not on a new judgment call.  
  
**Nate's own decision (2026-09-13), Occam's-razor call after reviewing D14/D11's own trajectory directly:** build a real, read-only, sanitized relay *inside cabin-backend* instead of ever proxying to the broker -- the same mediation pattern literally every other endpoint in this app already uses (nothing else lets a browser talk to mosquitto directly). Explicitly deferred to a fresh, separately-reviewed PR rather than built same-session as an incident follow-up: this is real new backend surface touching a security boundary, not an urgent fix, and the tile has never worked for a public visitor anyway -- nothing is regressed by waiting a normal review cycle.  
  
**Three concrete implementation shapes, no side has picked one:**

- **Option A -- Server-Sent Events over the existing `/api/` path** (e.g. `GET /api/events/live`), pushing already-classified `CabinEvent`s as they're published. One-directional by construction (no client-to-server channel at all, so no publish-capability risk even in principle), needs zero new Docker networking or nginx config -- reuses the exact proxy path `/api/` already has. Gate it the same way D14 gates `/api/events`'s bare collection today (camera/motion excluded) so this doesn't quietly reopen that boundary via a second door.
- **Option B -- a real Spring WebSocket endpoint** (STOMP or a raw handler) that only ever pushes sanitized `CabinEvent` data server-side, never accepts an inbound publish. Closer to the existing tile's own mental model (a WebSocket, not an EventSource) but more moving parts to build and secure correctly for a purely one-directional need.
- **Option C -- keep it Tailscale-only, on purpose, and stop trying to make it public**: detect non-Tailscale visitors and simply don't attempt the connection (or show an explicit "Tailscale-only" state) instead of building a new relay at all. Cheapest option; accepts the tile stays a Tailscale-only feature rather than a public one.

**Open question for Cowork/Nate:** A, B, or C -- and if A/B, what the exact gating rule should be (mirror D14's `/api/events` split exactly, or something narrower specific to a live/streaming context). Not implemented; per this doc's own protocol, waiting for a decision before any code is written. cabin-ui's `nginx.conf` currently has an inert, non-functional `/mqtt-ws` location (returns 502 -- the LAN-IP path it tries doesn't actually reach mosquitto from cabin-ui's container) left in place as a placeholder, not a working fix; to be replaced, not extended, once A/B/C is decided.  
  
**Scoped for WSJF (2026-09-13):** Safety 3 / Family Usability 3 / Platform Extensibility 6 / Time Sensitivity 2 → CoD 17, Complexity 5 (Fibonacci) → **WSJF 3.4** -- see the WSJF Priority Order list (#11). Deliberately scored low, not inflated to force urgency: the tile has never worked for a public visitor at all, so nothing decays by this waiting in backlog. Passes the FAIR secondary gate on Accessible + Reusable (see the WSJF entry for the full reasoning). This ranking is not the safety control here -- this pin's own explicit warning above is what actually stops the broker-proxy shortcut from being reached for again; the WSJF score only orders when the real fix gets built, not whether the risk is understood.  
  
**Implemented (Code, 2026-09-13, [PR \#63](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/63), commit `d31301e`):** Option A picked -- Server-Sent Events over the existing `/api/` path, one-directional by construction (EventSource can't publish back through it, unlike the WebSocket it replaces). New `EventStreamBroadcaster` (subscriber bookkeeping + fan-out) wired into `EventConsumer.pollLoop()` immediately after `eventService.save()`, so a live-relayed event is never one this platform hasn't already durably recorded -- answers this pin's own "already-classified CabinEvents only" framing directly. New `GET /api/events/live` in `EventController` falls under the existing `/api/events/**` prefix in `WebConfig`, gated exactly like the bare collection it mirrors -- deliberately not exempted the way telemetry-history/reported-fields are, matching this pin's own gating instruction to mirror D14's split. EventSource can't set a custom Authorization header, so the browser passes its session as a query param (`cabin_session` preferred, falling back to `access_token`), the same technique `CameraLiveView` already uses for the identical reason. `nginx.conf`'s inert PR \#58 `/mqtt-ws` placeholder (confirmed dead, 502, never reached mosquitto from cabin-ui's own container) removed and replaced with a dedicated, buffering-off `/api/events/live` location -- nginx buffers a proxied response by default, which would sit on every event until the buffer filled, defeating the point of "live." No new networking otherwise: the endpoint reaches `cabin-backend` through the exact same proxy `/api/` already uses. Tests: new `EventStreamBroadcasterTest` (subscriber bookkeeping; `SseEmitter` send/completion-callback behavior needs a real servlet container, verified live instead of stubbed); `EventControllerTest`/`EventPipelineIntegrationTest` updated for the broadcaster's new constructor arg.  
  
**Merged + deployed (Nate's go-ahead, 2026-09-14, commit `feded58` on `main`):** PR \#63 merged (fast-forward, branch deleted). `deploy-cabin-backend.yml` ran for real this time (Testcontainers-gated tests execute in CI, unlike this dev machine's Docker-unavailable local runs) -- `mvn test` passed, image built, health check green (`cabin-backend:feded58` promoted to `:latest`), no rollback. `deploy-family-hub.yml` (cabin-ui/nginx.conf changes) also completed successfully. **Live-verified on `cabin.unicornpingpong.com` post-deploy (anonymous browser session, no CabinSession):** the renamed "Live Events — Cabin" tile renders cleanly with its "No live events yet" empty state -- no crash, no raw error surfaced to the user, and no wasted network call to `/api/events/live` at all (the frontend hook correctly never opens the EventSource without a session token, rather than opening it and eating a 401). Rest of the Monitoring panel (device grid, telemetry charts, camera health) confirmed unaffected by the `nginx.conf`/`App.jsx` changes. **Not yet verified:** the actual authenticated streaming path (a signed-in CabinSession actually receiving live `CabinEvent`s as they're published) -- that needs Nate's own Google-signed-in session, not something this session can do itself. **Not yet ratified by Cowork:** Option A was chosen and built directly rather than round-tripped through this log first; flagging that explicitly rather than closing this pin. This pin stays open until Cowork reviews the implementation shape.

D21 -- Device self-discovery lookup: dropped Anthropic, keyless web search instead; a real capability found untracked since 2026-08-13 ratified — Nate's own direct decision, 2026-09-14

**Found via a keyword search Nate asked for, specifically because he suspected this area's progress "has been hap-hazard thus far":** that suspicion was correct. `cabin-discovery` (a Python FastAPI microservice, `discovery-service/`) has existed since 2026-08-13, is deployed, healthy, has its own dedicated CI/CD pipeline (`deploy-cabin-discovery.yml`, test-gated), and had already run 45 real discovery lookups against production devices (19 applied) -- yet it appears nowhere in `ROADMAP.md`, `PRODUCT_NOTES.md`, `DEFINITION_OF_DONE.md`, or `MAINTENANCE.md`, and was never scored or logged as a D-decision here the way D1-D20 were. Its only documentation was a `docs/ontology.yaml` schema entry. **Correction, not just a framing note:** the 45 runs / 19 applied figure does not mean 19 devices have accurate metadata -- Nate confirmed he applied some results deliberately to validate the save/apply mechanism itself, with full knowledge he'd redo them device-by-device once real identification actually works. Recorded here so it isn't read as more settled than it is.  
  
**Real privacy-policy conflict found while investigating, not a style complaint:** the service's online-lookup provider called the Anthropic API with the full raw `discoveryAttributes` blob in its prompt -- which, for a network-scanned (mDNS) device, can include a locally-chosen name. D8's own already-ratified Enrichment Policy says exactly this must never be sent externally ("Never cabin names, IPs, friendly names, or behavioral history"). Never actually triggered in production (no working `ANTHROPIC_API_KEY` has existed since 2026-09-01 -- confirmed live, 0-length in the running container), but real, latent code, not a hypothetical.  
  
**Nate's own decision (2026-09-14), stated directly and repeatedly, not an engineering proposal open to independent revision:** no vendor-LLM API for a task this small -- identifying a device from vendor+model is a couple of cacheable sentences, not a job needing an LLM or a paid API key. Offered three acceptable directions himself: reuse the Ollama/Tiny-Helpdesk corpus-intake pipeline, a simple cached ping to a major search engine (logged, not live-crawled), or a live search-engine ping outright -- explicit only that Anthropic specifically was off the table.  
  
**Shipped (Code, 2026-09-14, [PR \#66](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/66), merged commit `4645484`):** new `web_lookup.py` -- a single keyless HTTP GET against DuckDuckGo's HTML search endpoint, stdlib `urllib` only, no SDK, no account, no key. Same "never invent a source" contract as the module it replaces: a real citation only ever comes from a URL DuckDuckGo itself returned; confidence capped at `medium` (never `high`) even with a real hit. Fixes the D8 conflict directly: only `vendor`+`model` are ever sent externally now, never `description` or raw attributes -- a device known only by `description` (e.g. a netscan/mDNS find with no vendor/model at all, like the router that opened this whole investigation) gets the pre-existing local-only result, not a web search, since sending its description isn't safe by the same D8 rule. `anthropic_lookup.py`, its tests, and the `anthropic` dependency removed; `ANTHROPIC_API_KEY`/`ANTHROPIC_WORKSPACE_ID` dropped from `docker-compose.m920q.yml` and `.env.m920q.example`; `docs/ontology.yaml`'s `device_discovery_result` entry corrected to match (also fixed a second stale claim found in the same entry -- it said "not yet wired into the frontend," untrue for some time).  
  
**Real, live-verified finding, not assumed:** a first attempt with an honest, self-identifying User-Agent got DuckDuckGo's 202 anti-bot holding page and zero results, confirmed by inspecting the raw response; a standard browser User-Agent returns real results. Documented in-code as the accepted tradeoff of an unofficial, keyless endpoint versus a paid/keyed search API. A live query for "SONOFF SNZB-04P" returned the actual product page, title, and snippet. 23/23 discovery-service pytest tests green (Python 3.13 venv -- this dev machine's default 3.14 can't build `pydantic-core`'s wheel, a pre-existing, unrelated environment gap).  
  
**Correction, same day (Code, 2026-09-14, [PR \#67](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/67), merged commit `bd6cc30`):** the "known, deliberate gap" above -- description-only devices (no vendor/model, the shape every netscan/mDNS find has) getting no search at all -- turned out to be over-conservative, not correctly scoped. Nate asked directly to confirm a real device (an LG webOS TV found by Home's network scan) would get a genuine result, and it didn't. Re-examined: a netscan device's `description` is its own mDNS-broadcast service name -- already visible in cleartext to anything on the LAN, not a private Z2M-style `friendly_name` assigned inside this app -- so searching on it doesn't cross D8's actual line (occupancy/behavior/location), the same public/gated distinction D14 already draws for device-inventory data. `web_lookup.py` now falls back to `description` when vendor+model are both blank; raw `discoveryAttributes` (IP, port, mDNS TXT records) is still never sent, regardless. **Live-verified:** `DiscoverRequest(description="LG webOS TV OLED42C5PUA")` now returns LG's real official product page, title, and snippet. 23/23 tests green (2 updated). This closes the gap the pin above flagged -- there is no longer a known class of device this feature can't search for.  
  
**Not yet done:** live post-merge verification on the M920q that a real `/discovery/run` call through the deployed container (not just the module directly) returns a genuine cited result for a real candidate device.  
  
**Second real gap found the same evening, via Nate's own live testing (Code, 2026-09-14, [PR \#70](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/70)):** looking up a real Cabin device ("motion_entry Occupancy - Y or N") still returned "nothing to search for" after \#66/#67. Root cause was upstream of everything above: `HomeAssistantDiscoveryService`'s attrs map is HA's raw per-entity state attributes, verbatim -- it never populated `vendor`/`model`/`description` at all, so **every** HA-discovered candidate (Kidde, Liebherr, every `ha-cabin-*`/`ha-home-*` entity) has been silently unsearchable since `cabin-discovery` shipped 2026-08-13 -- not something \#66/#67 could have touched, since those only changed what `cabin-discovery` does with whatever it's handed. Confirmed live: this exact entity's underlying physical device (`z2m-motion_entry`, the same SONOFF SNZB-03PR2, discovered separately via `Zigbee2MqttAdapter`) already has real vendor/model data -- the identity exists in this platform, just not attached to this HA-side duplicate view of the same sensor. Fixed with the same minimal shape as the earlier netscan/mDNS fix: `description` now populated from `friendly_name` + `device_class`. 2 new tests, run locally (mocked adapter, no Docker needed), green. **Flagged, not solved:** properly inheriting identity across an HA entity and its originating Zigbee device (via the `haDeviceId` this class already resolves) is a real, separate design question -- touches D13's Service Lineage model, and a wrong cross-match would misattribute vendor data, so it needs a real decision, not a guessed heuristic under time pressure.

Cross-session reconciliation, 2026-09-13 -- Home LAN discovery closed, compose-profile question predates this thread, two WSJF backlogs cross-linked documentation drift, corrected 2026-09-13

**Found while reconciling the session that produced D17 against everything that shipped after it** (the Home network-scan-agent, the 2026-09-13 Zigbee mesh outage, D18/D19/D20) -- three items worth closing the loop on explicitly rather than leaving as stale implications in this repo's own docs.  
  
**1. Home LAN device discovery -- closed, not open.** The D17-proposing session's own manual ARP/port-scan audit flagged one device (`192.168.1.119`) as needing physical identification by Nate. That approach is now fully superseded: the shipped `network-scan-agent` (mDNS-based, phone-side -- `MqttBridgeService.handleNetworkScanResult()`, live-verified 2026-09-12) found 6 real devices on Home's actual WiFi and registered them as candidates automatically. No manual-identification action remains open.  
  
**2. The "full-stack vs. collector-hub" architecture question was never actually open -- it predates this artifact's D17 entry by over a month.** The D17-proposing session flagged `locations/home/docker-compose.yml`'s "Full Stack" design as an unresolved ambiguity worth a WSJF score. Direct read of `ROADMAP.md` and `docs/HANDOFF_2026-08-08_codex-fork.md` shows Nate decided this 2026-08-08, in his own words: "I need something at home to be up all the time, get devices online, and then to the 920q in my instance" -- collector-hub, not a full independent peer, decided before that file's own header was ever written. The real, still-open item is narrower and cheaper: the file itself is stale config contradicting a month-old decision, and now further contradicted by what's actually live (the Termux Zigbee bridge + network-scan-agent, both routing to `cabin-backend`). Needs a deprecation banner or removal, not a new architecture decision -- there is no decision left to make here.  
  
**3. Two independently-scored WSJF backlogs, cross-linked for the first time.** This artifact's own D-decisions/WSJF Priority Order (reviewed by Cowork) and `docs/ai-assistant/wsjf-backlog.md`'s C1-C4 (the Ollama `llama3.2:3b` corpus/RAG/Tiny-Helpdesk track, reviewed by Codex) have run in parallel with no cross-reference until now. Concretely: this session's home-collector corpus additions (`home-collector-evidence.md`, eval seed `CC-01`, the `home_collector_termux` context-fixtures category, all added 2026-09-10/11) and D17's own `knowledge_node`/`knowledge_chunk_type`/`data_class: product` ontology entries landed after Codex's 2026-09-07 baseline and aren't yet folded into C1's next re-baseline scope or C2's coverage manifest. Recorded as `docs/ai-assistant/wsjf-backlog.md`'s new DEP09 and `docs/ai-assistant/corpus/coverage.md`'s new G11 -- no implementation authorized by either, awareness only. `README.md`'s doc index now lists both backlogs side by side.  
  
No code changed. `docs/DEFINITION_OF_DONE.md`'s Next Session Open Items and "Last full session close-out" footer updated to match all three findings -- see commit [b876ecd](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/b876ecd).

⚠ Urgent — Security & Authorization

BLINK_MOTION_WEBHOOK_API_KEY — rotated and live-verified resolved 2026-09-05

Exposed in a Code session transcript; this WSJF item (Safety 9 / Usability 2 / Extensibility 1 / Time Sensitivity 10, CoD 31, Complexity 1 → WSJF 31.0) called for immediate rotation. Note: the WSJF item's original phrasing ("generate new key in Blink console") was factually wrong — direct code read (`BlinkMotionWebhookController.java`, `docs/MAINTENANCE.md`) confirmed this is a locally self-issued shared secret (plain string-equality check against `cabin.blinkMotionWebhook.apiKey`), not a Blink-cloud-API-issued credential. Corrected before executing.  
  
New 64-hex-char value generated (`secrets.token_hex(32)`), written into `BLINK_MOTION_WEBHOOK_API_KEY` at the real deploy-path `.env` (`/home/nate/FaceoftheCabin/cabin-orchestration-platform/infra/.env`) and into `vault_blink_motion_webhook_api_key` in `ansible/group_vars/cabin/vault.yml` — same sitting, matching the discipline already established for the `HA_TOKEN` pin above, so this can't silently regress to blank on a future Ansible re-template. Automated write attempts were classifier-blocked twice; Nate applied the exact values manually via `nano`/`ansible-vault edit`, verified end-to-end by byte-length at each step (never by printing the raw secret). `cabin-backend` restarted.  
  
**Live-verified via a real functional call, not just "requires auth now":** `POST /api/webhooks/blink-motion?camera=driveway` with the old key → `401`; with the new key → `200`, `{"camera":"driveway","ok":true}`, correctly triggering the driveway camera's liveview exactly like a real motion event would (a real, harmless, idempotent side effect per that controller's own documented guarantee).  
  
**Moot as of 2026-09-06:** the concern raised here (a stale MacroDroid automation still holding the old key) no longer applies — MacroDroid is fully uninstalled, not just superseded as primary path, and the live HA automation (see the MacroDroid replacement pin below) never depended on this key at all. Nothing left holding the old value anywhere.  
  
**Separate incident, found and fixed 2026-09-03:** this same key was never wired into `ansible/roles/secrets/templates/env.j2` at all — a live, real value existed only in a hand-edited `.env`, so a routine Ansible re-template (unrelated, for a new Resend key) silently dropped it, breaking the phone motion-notification webhook with no loud error (degrades to `503`, not a crash). Restored from an old backup at the time; now properly wired through the vault, and this rotation confirms that fix held.

API exposure — remediated resolved 2026-09-01

Live audit 2026-08-31 confirmed: `/api/devices`, `/api/alerts/active`, `/api/events`, and `/actuator/health` were all returning HTTP 200 with no auth. **All resolved:** `/api/alerts/**` and `/api/events/**` gated in `WebConfig.java` — committed ([1dd69ac](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/1dd69ac), [76bcdb9](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/76bcdb9)). `App.jsx` and `family-hub.html` updated with `authFetch()` — committed ([8198331](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/8198331)). `/api/devices` — now gated via D12 (Sprint 3). `/actuator/health` — `show-details: never` in `application.yml`; nothing exposed to any caller (stronger than `when-authorized`, which would not have evaluated without a Spring Security Principal).

actorId client-supplied field — investigated, not a gap resolved 2026-09-01

Direct code read (`GoogleAuthInterceptor.java`'s own doc comment, the four family-feature controllers) confirmed this is deliberate: any signed-in family member can attribute a chore/schedule/holiday action to any household member, matching `family-hub.html`'s own actor picker — authentication and attribution are intentionally separate concerns here. Forcing server-derived attribution would be a product regression (a parent could no longer log a chore for a child), not a security fix. Left unchanged.

Infrastructure Debt

Production stack .bak-file deploy blocker resolved 2026-09-03

Both stale files (`infra/.env.bak-20260821-174405`, diffed and confirmed to have no unique value left; `infra/grafana/provisioning/datasources/timescale.yml.bak-20260825-094334`, diffed and confirmed to differ only by one now-superseded line) removed from the M920q checkout. Same investigation also found the device-smoke-test step had been silently regressed to always-401 since Sprint 3's `/api/devices` auth gate shipped (2026-09-01) — invisible only because the .bak blocker always failed first. Fixed by switching detection to an auth-free `docker logs` grep for `Zigbee2MqttAdapter`'s own discovery log line ([d7a84be](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/d7a84be)). **Live-verified:** the next production-stack deploy run ([33716422993](https://github.com/ImpressiveLLC/FaceoftheCabin/actions/runs/33716422993), triggered by the merge that landed both fixes) completed successfully — every container recreated and started, job green, no rollback.

Same .bak-file deploy blocker recurred with new files — actively blocking `deploy-production-stack.yml` right now open

Found 2026-09-05 while verifying this session's own pushes: [run 33990795670](https://github.com/ImpressiveLLC/FaceoftheCabin/actions/runs/33990795670) failed at the exact same fail-closed dirty-worktree preflight the resolved pin above already fixed once (2026-09-03) — except this time the untracked files are `ansible/group_vars/cabin/vault.yml.bak-20260905T055529Z` and `cabin-orchestration-platform/infra/.env.bak-20260905T055312Z`, sitting in the M920q's own deploy clone at `/home/nate/FaceoftheCabin`. Timestamps (~2 minutes apart, early 2026-09-05 UTC) match the shape of a routine `ansible-playbook ... --tags secrets` re-template (each edit backs up both files first) — possibly the monthly `rotate-secrets.yml` cron mentioned in `docs/MAINTENANCE.md`'s CI/CD section, though not confirmed. Not something this session caused or can fix directly — no SSH access to the M920q from this environment, and the preflight is correctly refusing to auto-discard them (its own comment explains why: a prior version of this exact situation destroyed a real, legitimate, not-yet-committed vault edit with zero warning). **Needs Nate directly:** SSH to the M920q, inspect both files (diff against the current tracked versions the same way the 2026-09-03 fix did), and either delete them if genuinely stale or commit the vault change if it's real and not yet captured. `cabin-orchestration-platform/infra/.env.bak-*` in particular is a live secrets-hygiene concern sitting as an untracked file in a git working tree — worth deleting promptly regardless of whether it's stale, not just leaving it until the next deploy attempt. Confirmed this does *not* block `deploy-cabin-backend.yml` or `deploy-family-hub.yml` (this session's own WSJF \#2/#3 pushes deployed successfully through both) — it's specific to the production-stack pipeline's own preflight script.

Intermittent external-registry timeouts on the M920q's own network -- npm side mitigated, Docker Hub side needs prompt attention recommend resolving ASAP -- no clean quick-fix found for the remaining half

**Found live, 2026-09-14 (Code), two real production deploy failures in one evening, back-to-back, on a pipeline (`deploy-family-hub.yml`) that had otherwise run 10+ consecutive clean successes before that -- checked directly against real run history, not assumed:** a Docker Hub manifest-fetch timeout pulling `node:20-alpine` (`net/http: timeout awaiting response headers`), then on the very next push an npm-registry timeout during `npm install` (exit 146, "npm error network").  
  
**Investigated for a root cause, not just patched blind:** checked DNS resolution (instant, correct, both registries) and live connectivity (`curl` over both IPv6 and IPv4 directly to `registry-1.docker.io`) from the M920q itself -- both clean, fast, no errors, at the time of checking. No `/etc/docker/daemon.json` override, nothing configuration-side that stands out. This reads as genuine transient network flakiness (packet loss, brief upstream congestion, a DNS/connection race) rather than a reproducible bug this session could pin down further -- the connectivity is fine right now, which is exactly why there's no clean quick-fix to point at for the Docker Hub half specifically.  
  
**Partial fix shipped (Code, 2026-09-14, [PR \#69](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/69)):** npm retry flags (`--fetch-retries`/`--fetch-retry-mintimeout`/`--fetch-retry-maxtimeout`) added to cabin-ui's Dockerfile -- a standard, low-risk mitigation directly targeting the npm-registry half of what actually failed tonight.  
  
**Not fixed, and no clean quick-fix identified:** the Docker Hub base-image pull has no equivalent simple retry knob at the Dockerfile/BuildKit level the way npm's CLI flags provide -- a real fix would mean either a registry mirror/pull-through cache configured at the Docker daemon level on the M920q (infra, not source), or accepting occasional deploy re-runs as the cost of a residential-grade connection. **Recommending this get resolved promptly if it recurs** rather than left as a one-off shrug: two failures in one evening on an otherwise rock-solid pipeline is worth someone with M920q access watching for a pattern over the next few days -- if it keeps happening, a local Docker registry mirror (e.g. pointing at a \`registry-mirrors\` config, or a self-hosted pull-through cache) is the real fix, not more retry flags.

Sprint 0 benchmark gaps measured 2026-09-08

**Found while picking up the next ontology WSJF item:** Sprint 1's card already checked off "Close Sprint 0 benchmark gaps" (2026-08-30), but no actual numbers existed anywhere in this doc -- exactly the "ratified-sounding claim turns out unmeasured" pattern this doc's own Axioms entry warns about. Measured live against the real M920q Ollama container rather than left unverified.  
  
**Measured (Code, 2026-09-08, live on M920q, ollama/ollama:latest v0.33.2, model `llama3.2:3b`, digest `a80c4f17acd5`, Q4_K_M quantization, 2.0GB on disk):**

- **Cold start** (model not resident, confirmed via `ollama ps` showing empty first): `total_duration` 10.06s = `load_duration` 4.61s + `prompt_eval_duration` 1.67s (34 tokens, ~20 tok/s) + `eval_duration` 3.78s (42 tokens, ~11 tok/s generation).
- **Warm** (same prompt, model already resident): `total_duration` 3.38s -- `load_duration` ~0ms, `prompt_eval_duration` 0.077s (34 tokens, ~443 tok/s), `eval_duration` 3.30s (40 tokens, ~12 tok/s generation). Cold-start penalty is almost entirely `load_duration` (~4.6s to bring the 2GB quantized model off disk) plus a slower first prompt-eval pass, not generation speed itself -- generation throughput (~11-12 tok/s) is stable cold vs. warm.
- **Memory under load:** ~2.44-2.45GiB resident once loaded, against 30GiB host RAM (12GiB available at measurement time) -- comfortable headroom, not a capacity concern at this single-model scale. CPU pins to 100-124% of one core during generation (i7-8700T, CPU-only per D9's own "no GPU" note -- this is expected, not a regression).
- **Two simultaneous requests:** NOT parallelized -- fired concurrently, combined wall time 6.24s with individual `total_duration`s of 2.95s and 6.16s (the second effectively queues behind the first). Real capacity-planning fact: concurrent household Ask usage will serialize, not run in parallel, on this single CPU-only instance.
- **Context:** runtime default `num_ctx` is Ollama's standard 4096 (confirmed via `ollama ps`'s `CONTEXT` column during a live request), well under the model's architecture max of 131072 -- not yet tested at a larger context; a future eval round with long corpus context injected should confirm behavior near or above 4096 rather than assuming the architecture max applies at runtime.
- **Restart/volume persistence:** confirmed via `docker-compose.m920q.yml`'s `ollama_data:/root/.ollama` named volume + `restart: unless-stopped` -- not live-restart-tested, deliberately, to avoid a disruptive outage on the container backing the live Tiny Helpdesk/C1a Ask path for a benchmark that config inspection already answers.

  
**Also found, not yet reconciled:** `docs/ai-assistant/rag/EVAL-ENVIRONMENTS.md` documents Ollama as reachable at `http://localhost:11434`, but the live compose file publishes no host port for it (`docker port ollama` returns nothing) -- it's only reachable inside `infra_default`'s docker network (by `open-webui` and, presumably, `cabin-backend`). Either that doc line is stale or there's a host-level port-forward this session didn't find; flagged rather than silently corrected since it wasn't this session's focus.

Two-stack documentation

Service/volume/port ownership table, explicit project+network names, backup/restore instructions for Open WebUI and Ollama models.

Vaultwarden API not actually wired — blocks SmartThings/Ring from ever running live code-side blocker resolved 2026-09-05 one-time operator setup still pending

**Original plan in this pin (a raw REST client against Vaultwarden's `/identity` + `/api` endpoints) turned out to be non-viable, not just unbuilt.** Verified directly (2026-09-05) against a real community tool implementing this exact flow (`Turbootzz/Vaultwarden-API`): Bitwarden/Vaultwarden cipher content is end-to-end encrypted client-side with a key derived from the account's *master password*. An Organization API key's `client_credentials` grant only bypasses 2FA for authentication — it does not hand back that decryption key. A raw REST client with just an API key would receive encrypted blobs it structurally cannot read, no matter how correctly implemented.  
  
**Fix shipped instead:** `VaultwardenOAuthCredentialStore` now shells out to the official `bw` CLI as a subprocess (new `BwCliRunner`/`ProcessBwCliRunner` seam, secrets via env vars never argv) — Bitwarden's own maintained crypto does the decryption, this class only orchestrates `login --apikey` / `unlock` / `get item` / `create item` / `edit item`, with the session key cached per-JVM-lifetime. `backend/Dockerfile` installs it via `@bitwarden/cli` (npm) rather than Bitwarden's standalone binary, which is glibc-only and doesn't run on this image's Alpine/musl base. 12 new unit tests via a fake CLI runner (no Docker/bw binary needed to test). [004fc9b](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/004fc9b) (store implementation) + [a38f5d9](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/a38f5d9) (env var wiring through Ansible + compose — the first commit alone would have silently done nothing, since `docker-compose.m920q.yml`'s `environment:` block is an explicit allowlist, not an `.env` passthrough). Deployed and health-checked live on both commits.  
  
**Deviation from this pin's own suggested vault variable names:** shipped as `vault_vaultwarden_client_id`/`vault_vaultwarden_client_secret` (matching this pin's suggestion minus the `_org_` infix, and matching the existing `vaultwarden_admin_token` naming convention already in `vars.yml`) plus one the original pin didn't name at all — `vault_vaultwarden_master_password`, required because of the encryption-model finding above. Full one-time setup runbook (create Organization → generate API key → the three vault entries → re-template → verify) now lives in `docs/MAINTENANCE.md`'s new Vaultwarden section, which this class's own error messages point to.  
  
**Still genuinely open, tracked here rather than silently closed:** the three real secrets (Organization client_id/client_secret + the account's master password) haven't been generated/entered yet — that's a one-time action only Nate can do (needs his own Vaultwarden login). Until then, `listDevices()` on both adapters still fails loudly by design, same as before, just with a much shorter path to actually fixing it. Also explicitly deferred, not attempted: SmartThings'/Ring's own OAuth token-refresh logic (checking `credential.expiresAt()` and calling each platform's refresh endpoint) — neither adapter has any refresh logic today, and building it needs each platform's own OAuth app client_id/secret, which don't exist yet either.

MacroDroid replacement — shipped, deployed, live-verified end-to-end fully closed 2026-09-06

Nate's own direct ask (2026-09-05): move off MacroDroid for the phone-side Blink-motion-notification → webhook automation. Reason stated directly: it's adware-driven and requires watching a video to use for free.  
  
**Direction chosen after weighing options:** Blink's own IFTTT "Motion detected" trigger only polls Blink's cloud every 5 min (Pro) / hourly (Free) — too slow for a driveway liveview trigger. Chose the HA Companion App's own "Last Notification" sensor instead — already-installed, zero cost, zero ads, matching this codebase's own existing push-bridge pattern (the Kidde CO alarm bridge) exactly.  
  
**Backend shipped (Code, [69409d6](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/69409d6)):** new `cabin/blink/motion` MQTT topic, deliberately *not* retained (a motion notification is a one-off event, not ongoing state — same lesson `Zigbee2MqttAdapter`'s own retained-message bug taught). `MqttBridgeService.handleBlinkMotionTopic()` calls `BlinkLiveviewService.start()` directly. `BlinkMotionWebhookController`'s HTTP endpoint kept as an untouched manual/fallback trigger.  
  
**Two real bugs found and fixed live during rollout, not caught by any test:** (1) Nate's Companion App was on 2026.6.5 — two release cycles behind current (2026.8.4) — and the Last Notification sensor didn't even appear in Manage Sensors on that version; confirmed via the app's own GitHub changelog that notification-sensor initialization bugs were fixed in between (PR \#7206). Updating the app resolved it. (2) The automation was first deployed to `/config/cabin_security.yaml` (the HA container's config root) — parsed fine, sat completely inert, because HA's `configuration.yaml` only loads packages from `/config/packages/`. Every pre-existing automation in that same file loaded correctly (from the real, separate Aug-21-dated copy at `/config/packages/cabin_security.yaml`) while the new one silently didn't exist anywhere — caught only by directly querying HA's own `/api/states` for the automation entity, not by log inspection (HA logged no error either way). Fixed by copying into the real path and restarting; the stale root-level copy removed; the real deployment path now documented at the top of the file in-repo so this can't repeat silently again.  
  
**Real attribute data corrected two guesses:** the package attribute is the bare `package` key, not `android.package` (doesn't exist); the real notification text lives in `android.title` ("Motion detected at your AldrichFront.") — `android.text`/`android.subText` report the literal string `"null"` when unset on this device, not empty, which a reject-filter now strips.  
  
**Live-verified end-to-end 2026-09-05, real Blink motion event, ~22 second round trip:** phone push → HA's `sensor.nates_s23_last_notification` → automation matched "AldrichFront" → published `cabin/blink/motion` → `cabin-backend` log: `"Blink motion push (HA notification bridge) triggered liveview start for home_aldrich_front: ok=true"`, immediately followed by Frigate auto-registering real frames from the camera — confirming the bootstrap mechanism (these are on-demand Blink cameras with no continuous stream) works as designed.  
  
**Known, accepted limitation:** HA's Last Notification sensor won't re-fire on two identical back-to-back notifications from the same app before the first is dismissed — confirmed Blink's real title text has no timestamp, so this is a real (if narrow) gap, not just theoretical. Accepted: MacroDroid's own trigger had no stronger guarantee, and `BlinkLiveviewService.start()`'s idempotency means a missed second trigger just skips a redundant session-restart, not a broken liveview.  
  
**Last step, done (Nate, 2026-09-06):** MacroDroid uninstalled from the phone, satisfied the HA-automation replacement has run reliably. Nate's own framing on the record, worth keeping rather than only the harsher "adware-driven" phrasing above: he doesn't see it as part of an elegant solution long-term, but it's a validated tool that genuinely worked as an interim bridge — a fair complement to the native HA path, not just a thing to bad-mouth on the way out. This pin is fully closed; the codebase's own MacroDroid references (docs/DEFINITION_OF_DONE.md, docs/MAINTENANCE.md, application.yml, ontology.yaml, and several code comments) were correspondingly reframed the same day from "still in use" to "validated, superseded" language.

`HA_TOKEN` silently blank in production — restored, recurrence still needs a structural fix cabin restored 2026-09-05 recurrence — open

Found 2026-09-05 while chasing Platform Info's "HA unreachable or token not configured" line: the *running* `cabin-backend` container's compose project is `/home/nate/FaceoftheCabin/cabin-orchestration-platform/infra` — a different checkout from `~/repos/FaceoftheCabin/...`, which has its own separate (non-live, also-not-real) `.env`. At the real deploy path, both `HA_TOKEN` and the vault's `vault_ha_token`/`vault_home_ha_token` were byte-exact empty (confirmed by length, never by printing a value). Compose wiring itself was already correct (`HA_TOKEN: ${HA_TOKEN:-}`) — this was a missing credential value, not a wiring bug like `ADMIN_EMAILS` was. Root cause of the "we reset it a week ago" gap: Home Assistant shows a long-lived access token's value exactly once, at creation — Nate's prior reset was never captured anywhere durable (not the live `.env`, not the vault, not even the secondary checkout), so it was unrecoverable rather than just stale.  
  
**Cabin resolved and live-verified:** Nate generated a fresh HA long-lived token; written into `HA_TOKEN` at the real deploy-path `.env` (timestamped backup taken first) and into `vault_ha_token` in `ansible/group_vars/cabin/vault.yml` (same sitting, per this project's own credential-lifecycle discipline — no drift-waiting-to-happen this time), `cabin-backend` recreated, health check green. Live-verified three ways, not just "the file has a value": (1) `/api/system/platform-info`'s `homeAssistant` version field went from "unavailable" to a real version string (`2026.7.4`); (2) the "HA token not configured for location 'cabin'" log warning stopped appearing on every scheduling tick, while the equivalent 'home' warning correctly still does; (3) every Kidde and Liebherr ("Loonie Mc Frigerton") entity in `/api/devices` shows a fresh `lastSeen` timestamp from the restart and `state: ONLINE`, not stale pre-outage data.  
  
**`HOME_HA_TOKEN`/`HOME_HA_URL` — checked, confirmed NOT the same bug:** both are blank in the real `.env`, but `.env.m920q.example`'s own comment says why: `# ── Home hub (not yet deployed — leave blank)`. Nate confirmed directly (2026-09-05) that the home-location Home Assistant genuinely doesn't exist yet — there's no second hub running to authenticate against. This is accurate pending-deployment state, not a silently-drifted credential gap like cabin's was; setting a token or URL now with nothing real behind it would just trade one failure mode (blank token) for a noisier one (every scheduling tick timing out against an address nothing answers). Revisit this exact spot in `.env` when the home hub is actually stood up — same restore process as cabin above applies then.  
  
**What still needs Cowork's prioritization — the recurrence itself:** this is the identical failure mode already recorded once before (blank HA_TOKEN silently disabling Kidde+Liebherr discovery for weeks, 2026-08-21), now recurred a second time via a different mechanism (an unrecoverable one-time-shown token value, not a template re-run overwrite). There is still no CI-level guard that fails loudly on a blank/missing instance-wide integration token post-deploy (only `/actuator/health` is gated today), and the credential still lives only in a hand-edited, gitignored `.env` plus an Ansible vault with no drift/presence monitoring — the same storage gap already flagged for Vaultwarden above, just for a different credential. Recommend: a deploy-time health check that fails loudly when an instance-wide integration token is blank (extending the existing health-check/rollback pattern), and/or migrating `HA_TOKEN`/`HOME_HA_TOKEN` into Vaultwarden once its API is wired (same pin above) so a token's only-shown-once value gets captured durably at creation time instead of depending on a human remembering to paste it in three places.

Unresolved D14 Tensions

`/api/presence` and `/api/security` — gated resolved 2026-09-05 · [2d6f019](https://github.com/ImpressiveLLC/FaceoftheCabin/commit/2d6f019)

D14's own stated principle is that endpoints revealing occupancy are the one thing worth protecting; per Cowork's own pre-authorization ("Code's call to execute... gate them under D14's framework"), both are now added to `WebConfig`'s gated pattern list. Neither has a non-occupancy sub-path worth carving out the way `/api/devices` or telemetry-history did — presence's GET is literally the at-home/at-cabin/away profile, and security's GET (armed_away) directly implies nobody's home — so both are fully gated, no GET carve-out.  
  
`usePresence`/`useSecurityState` (App.jsx) threaded through `cameraAuth.authedFetch` so a signed-in kiosk (30-day CabinSession) keeps working exactly as before; an anonymous caller now gets a raw 401 instead of full occupancy data, and the frontend degrades to the safe fallbacks that already existed for the "no signal yet" case (`PresenceToggle`'s default option list, `SecurityBadge`'s "Unknown") rather than applying an error body to state.  
  
**Live-verified**: anonymous GET on both → 401; the same real CabinSession → 200 with full data; browser-verified with a cleared session that the toolbar renders completely (no crash, no raw error text) — presence dropdown falls back to its default list, security badge shows "No armed/disarmed signal received yet for this location," matching the pre-existing "no signal" state exactly. 275 frontend + 328 backend tests green (30 pre-existing local Testcontainers/Docker-unavailable errors on this dev machine only, all passed in CI where Docker exists).

`ADMIN_EMAILS` blast radius — live smoke test resolved 2026-09-05

Live-verified with Nate's real Google session (nhsmrekar@gmail.com) against the deployed production stack, not just unit tests injecting the admin email directly. `GET /api/system/platform-info` → 200 with real data (Platform Info card rendered live versions + hardware catalog in the browser); `GET /api/cross-domain/cabin-status-summary` → 200; `GET /api/platform-import/records` → 200. All three return 401 for an unauthenticated caller (re-confirmed same session). `ADMINISTRATOR` resolution is confirmed working end-to-end in production, not just in test fixtures.  
  
**Unrelated finding surfaced by this same check — since resolved, see the `HA_TOKEN` pin above (Infrastructure Debt):** Platform Info's live Home Assistant version lookup returned "unavailable (HA unreachable or token not configured)" at the time of this smoke test. Root-caused and fixed same day (2026-09-05).

Home/Away & Presence

Home/away detection inaccuracy

Person entity shows "away" when home. Check companion-app/geofencing config on adults' phones first — highest-confidence fix, no cross-platform work required. HA Companion App zone-only mode on kids' phones is the right eventual path for child presence. Both Code and Codex confirmed.

Platform Boundary Governance (D11)

Existing family/cabin backend seam governance

Declare data ownership per existing family dataset. Freeze new cross-domain data flows until authorization roles and server-derived identities exist.

Define "air gap"

Decide: separate deployments/databases, or separately governed domains sharing infrastructure? Current system provides neither a literal air gap nor a fully enforced logical one.

Presence assertion contract ratified 2026-09-06

Minimal schema (`state / confidence / observed_at / expires_at / sources / policy_version`) defined and shipped as `PresenceContractV1` via `GET /api/presence/contract`. D11 prohibited uses (no actuator writes, no safety-alert suppression, no named individuals, no precise location, no automatic unlock/disarm) hard-coded in the implementation. See the matching Discrepancy Log pin for deviations ratified. This pin is fully closed.

Platform import duplicate detection — resolved 2026-09-03

`(original_platform, original_id)` UNIQUE constraint implemented as part of WSJF \#9 (commit f37930d). Re-import presents as a conflict, not a duplicate.
