# Platform backlog (WSJF)

The single prioritized list of platform work. Helpdesk / Ask / RAG work is scored separately in [`docs/ai-assistant/wsjf-backlog.md`](../ai-assistant/wsjf-backlog.md) and is not repeated here.

## Scoring

Ratified formula:

```
CoD  = (Safety Value × 2) + Family Usability + Platform Extensibility + Time Sensitivity
WSJF = CoD ÷ Size
Each component 1–10 · Size in Fibonacci (1 2 3 5 8 13)
```

Time Sensitivity for items tied to UC-1 is scored against the insurance policy renewal window.

`Origin` is either **planned** (in the ratified WSJF order before work started, with its source named) or **ad-hoc** (scored here when raised). Ad-hoc items carry the same governance record as planned ones.

`Status` values: `proposed` · `ratified` · `in progress` · `in review` · `merged` · `released` (Product DoD met) · `gated` (blocked by a named item) · `held` (waiting on a named decision).

## Active items

| ID | Work | UC | Traces to | Origin | Owner | CoD (S×2+U+E+T) | Size | WSJF | Status | Blocked by |
|---|---|---|---|---|---|---|---|---|---|---|
| W-16 | Home netscan TCP reachability on the collector's recorded address:port; unknown presence until fresh probe evidence, including demo home http_poll and unconfigured cameras | UC-3, UC-9 | D22, D8 | ad-hoc (Nate 2026-09-24; W-NEW-c) | Codex | 4+9+5+10 = 28 (proposed) | 5 | **5.6** | in progress | Collector rollout and live verification for release |
| W-17 | Preserve discovery description/vendor/model/serviceType/host through confirm and restart; re-check uses display name only when identity is empty; disable unsupported replacement | UC-3, UC-9 | D21, D8 | ad-hoc (Nate 2026-09-24; W-NEW-d) | Codex | 4+9+6+10 = 29 (proposed) | 3 | **9.7** | in progress | — |
| W-18 | Netscan hygiene: coalesce same-location host/IP aliases without deleting configuration; network device types; local-time staleSince | UC-3, UC-9 | D8, D22 | ad-hoc (Nate 2026-09-24; W-NEW-e) | Codex | 2+7+5+8 = 22 (proposed) | 3 | **7.3** | in progress | — |
| W-19 | Distinguish DuckDuckGo blocked/challenged/unavailable responses from genuine no-results; retain honest sources and confidence | UC-3 | D21 | ad-hoc (Nate 2026-09-24; W-NEW-a) | Codex | 2+8+5+8 = 23 (proposed) | 3 | **7.7** | in progress | — |
| W-20 | D21 amendment proposal comparing SearXNG and Brave; no provider switch, new service or paid subscription without ratification | UC-3, UC-6 | D21 | ad-hoc (Nate 2026-09-24; W-NEW-b) | Codex proposes; Nate decides | 2+5+6+6 = 19 (proposed) | 2 | **9.5** | proposed | W-19 evidence; provider decision |
| W-21 | History range contract: viewer-effective options, explicit clamp feedback, partial first-day label; normal/demo non-presence row parity and 60-day demo ceiling | UC-9 | D22 R-DM-8, Q-DM-2 | ad-hoc (Nate 2026-09-24 chart defect) | Codex | 4+9+6+10 = 29 (proposed) | 5 | **5.8** | in progress | — |
| W-8 | Decide recording retention for the claim period (continuous 5 days; alerts/detections 10 days) after checking free space on `/storage` | UC-2 | D18 | ad-hoc | **Nate** (production config); Code sizes disk | 8+3+2+10 = 23 | 1 | **23.0** | proposed | — |
| W-1 | Live-verify all four guest scopes on M920q with a logged-out browser; post evidence in the PR | UC-1 | D12 | ad-hoc (closes Bug Fix Sprint `8f424f6` evidence gap) | Code | 14+7+3+9 = 33 | 1 | **33.0** | proposed | — |
| W-4 | Remove or neutralize the "insurance adjuster/mediator" callout anywhere a token holder can see it | UC-1 | D12 | ad-hoc | Code | 4+6+1+9 = 20 | 1 | **20.0** | proposed | — |
| DOC-1 | Governance skeleton: this folder, canonical decisions import, redirects | UC-6 | D4 | ad-hoc | Cowork drafts; Code commits | 6+4+8+9 = 27 | 2 | **13.5** | in review | — |
| W-2 | Guest denial tests (all-scopes token): 403 on helpdesk, KB, presence, camera/Frigate, admin, settings, access-link routes; 403 on non-GET; 401 on expired and revoked; `?t=` redacted in access logs | UC-1, UC-5 | D12, D11 | ad-hoc | Code | 18+5+6+9 = 38 | 3 | **12.7** | **merged** ([PR #100](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/100)) | — |
| W-2b | `cabin-ui/nginx.conf`: explicit `log_format` using `$uri` (path only), not the default `$request` (full request line, query string included) — closes [DL-2026-09-23-08](discrepancy-log.md), the `?t=` guest-token leaking into nginx's own access log unredacted | UC-1 | D12 | ad-hoc | Code | not yet scored — Cowork to score; CoD components not given with this item | 1 | — | proposed | — |
| W-5 | Frigate `front_door` before/after measurement: record 24 h baseline (Reolink motion count vs Frigate events vs continuous recording) before merging branch `claude-code/frigate-front-door-motion-sensitivity` (`7d014d2`), then repeat 24 h after | UC-7, UC-2 | D18 | ad-hoc | Code | 10+5+4+6 = 25 | 2 | **12.5** | proposed | — |
| W-3 | Implement guest denial contract R-GD ([SO-2026-09-23-r1](signoffs/SO-2026-09-23-r1.md) §3.2) | UC-5 | D12 | ad-hoc | Code | 10+8+6+8 = 32 | 3 | **10.7** | **green-lit** — build on `main` after #100 | — |
| W-13 | Demo Access: `demo` scope, `/demo/{token}` full-app read-only mode, deny-by-default `DemoAccessPolicy`, `DemoRedactor` ("hidden for Demo viewers"), camera placeholder card, banner, admin preset — [D22](decisions/ontology-decisions.md#d22--demo-access--full-app-read-only-preview) R-DM-1 to R-DM-12 | UC-9 (serves UC-1) | D22, D12, R-GD | ad-hoc | Code | 12+8+8+10 = 38 | 8 | **4.8** | **merged** 2026-09-23 (`7604135`, [PR #102](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/102)); deployed to M920q same day, both workflows green; post-deploy checks passed (`localhost:4080` 200, `/demo/` headers present, no `?t=` or token in `cabin-ui` log); live demo-token check (ITER row 13 exit) still open; green-lit by Nate directive 2026-09-23 ("build right away"); ordering override of WSJF | W-3 (shared R-GD handler); nginx token-path masking per [DL-2026-09-23-11](discrepancy-log.md) (fold into W-2b) |
| W-6 | Clip download v2: `manifest.csv` (file, endpoint, event id, start time, SHA-256, export time) and optional zip | UC-2 | D18 | ad-hoc | Code | 8+6+4+7 = 25 | 3 | **8.3** | proposed | bulk-clip merge |
| DOC-2 | Split `decisions/ontology-decisions.md` into one file per decision; move historical Discrepancy Log entries into `discrepancy-log.md` | UC-6 | D4 | ad-hoc | Code (mechanical); Cowork reviews | 2+3+6+4 = 15 | 2 | **7.5** | proposed | DOC-1 |
| DOC-3 | Regenerate the Living Ontology artifact as a read-only rendered view of `decisions/`; add a banner stating git is canonical | UC-6 | D4 | ad-hoc | Cowork | 2+3+5+5 = 15 | 2 | **7.5** | proposed | DOC-1 |
| DOC-4 | Triage `ROADMAP.md` Priority Task List and `DEFINITION_OF_DONE.md` Next Session — Open Items into this backlog (scored) or the discrepancy log; leave pointers | UC-6 | D4 | ad-hoc | Cowork scores; Code moves | 2+4+6+5 = 17 | 3 | **5.7** | proposed | DOC-1 |
| DOC-5 | Retire claude.ai project copies (`handover/*`, `ontology/cabin-ontology-decisions.md`); replace with one pointer to `docs/governance/README.md` | UC-6 | D4 | ad-hoc | Cowork | 2+3+2+6 = 13 | 1 | **13.0** | proposed | DOC-1 merged |
| W-7 | Time-range clip export not tied to an event row | UC-2 | D18 | ad-hoc | Code | 8+7+4+6 = 25 | 5 | **5.0** | proposed | W-5 |
| W-9 | Pin/Keep for retention: event retain + continuous export, pin log, ntfy on pin/unpin, Pinned filter, admin-only release | UC-8, UC-2 | D18 | ad-hoc | Code | 16+7+5+8 = 36 | 3 | **12.0** | proposed | #96 merged |
| W-10 | Warm 30 d + Cold 1 y retention; Cold rules 1–2 (sensor-alarm link, alert-grade detection) | UC-2, UC-7 | D18 | ad-hoc | Code | 14+5+5+7 = 31 | 3 | **10.3** | proposed | W-8 retention PR |
| W-11 | `/storage` thresholds: warn 80%, oldest-first Hot→Warm purge at 90%, never Cold/Pinned, ntfy on each | UC-2 | D18, D19 | ad-hoc | Code | 16+4+4+8 = 32 | 2 | **16.0** | proposed | — |
| W-12 | Cold rules 3–5 (claim date-range tags, anomaly, daily timelapse) + cold compression | UC-2, UC-7 | D18 | ad-hoc | Code | 10+5+5+4 = 24 | 5 | **4.8** | proposed | W-10; 2026-10-07 review |
| W-14 | `deploy-family-hub.yml`: add the health-check + auto-rollback pattern from `deploy-cabin-backend.yml` (SHA-tagged `cabin-ui`/`family-hub` images, poll after `up -d`, redeploy the previous tag through `docker compose` on failure, job still fails). Today a bad `ui/nginx.conf` or build leaves `cabin-ui` down with no rollback, found gating W-13's nginx change | UC-6 | — | ad-hoc | Code | not yet scored — Cowork to score; CoD components not given with this item | 2 | — | proposed | — |

Video lifecycle MVP (W-9–W-12) build order: **W-11 → W-9 (after #96) → W-10 → W-12** — not WSJF descending. W-11 goes first because it protects the disk with no dependency; W-9 is next specifically because #96 (Camera Events selection UI) is its foundation, not because of score. Source: Cowork, 2026-09-23, `FotC-W9-W12-video-lifecycle-spec.md` — Nate's own framing: "hot all recent; warm motion-detected longer term; cold by other usefulness criteria; any camera user can pin a clip back into full retention with notification," accepted as MVP, to be optimized later. Full tier table and Cold usefulness rules 1–5 are in that spec file only as of this commit — not yet ported into a governed doc; flagging so this doesn't silently become the only copy of that detail (candidate for a future DOC item, not scoped here).

## Planned items carried from earlier records

| ID | Work | UC | Source | Status |
|---|---|---|---|---|
| S6-1 | CANDIDATE persistence and D10 provenance (PR #93) | UC-3 | Code handover r8, Sprint 6 WSJF #1 | **merged** 2026-09-23 — C-93-1 closed from source (see [DL-2026-09-23-01](discrepancy-log.md)), not from a live import; C-93-2 (CI green) satisfied at merge |
| S6-2 | Matter/Thread adapter | — | Code handover r8, Sprint 6 #2 | held — Nate to confirm cabin hardware |
| S6-3 | Google Nest SDM adapter | — | Code handover r8, Sprint 6 #3 | held — Nate to authorize program fee |

## Operator actions (not backlog items)

| Action | Owner | Source |
|---|---|---|
| Delete stale `vault.yml.bak-*` and `.env.bak-*` on M920q after diffing | Nate | Code handover r8 |
| Vaultwarden one-time Organization and API key setup | Nate | Code handover r8; `docs/MAINTENANCE.md` |

## 2026-09-24 Home Devices and demo correction plan

Nate directs W-16 and W-17 first because both affect UC-9, overriding numerical WSJF order for this batch. Then W-18, W-19, the W-20 proposal, and W-21. Scores are Codex proposals, not Cowork ratification. IDs checked against main and all three open PRs on 2026-09-24: W-15 is reserved by #105.

Evidence: Nate reports six Home http_poll devices stale after 30 minutes, UNKNOWN last-known state, Brother HL-L2480DW identity absent after assignment, duplicate LG TV service names, and chart discrepancies in both views. These are user-reported screenshot findings; this session has not independently inspected the running instance.

Acceptance and implementation plan: preserve allowlisted discovery metadata durably on lifecycle/config writes; use display name as the final identity fallback without forwarding raw network attributes externally; reject unsupported replace in both UI and API. TCP probes must execute on the Home collector, which can reach that LAN, not the cabin backend. Missing/expired probe evidence means presence unknown; a fresh failed probe means the service did not respond, not a proven device outage. Match netscan aliases within a location and preserve configured records. Render timestamps locally. Distinguish provider blocking from empty parsed results. W-20 changes policy proposal only. W-21 keeps presence series empty, returns range metadata, filters options to the viewer ceiling and marks the partial first day; test the same non-presence query in normal and demo views for identical rows.

Workflow: planning committed and pushed on the feature PR before code; regression tests and user/QA documentation ship with implementation. No merge or production/collector restart is authorized by this request. CI on the final head and separate live/collector validation must be reported honestly. Existing sign-offs and iteration ownership remain intact.
