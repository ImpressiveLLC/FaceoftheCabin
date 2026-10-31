# Routing proposal: alerts scope (corpus merged in PR #362)

For Code, who owns `context-fixtures-r2.json` (backend copy and the `docs/ai-assistant/rag/` mirror). Nothing here edits the map. The backend copy on main has **26** categories (the coverage map said 27).

Phrasing numbers are the line numbers of `tools/routes.tsv` (1 to 150, in `tools/phrasings.json` order), with the row id.

Simulated with `ask-coverage-tools/route.py` against a proposed fixture (local simulation output, not committed). Every in-scope phrasing (1 to 25, 38 to 40, 105 to 109) routes to a category whose first section answers it. All proposed categories fit the 6000-character budget with the branch's text.

## Sections added or fixed

File `ai-assistant/user-guide/alerts-and-notifications.md` is new. Sizes are the section text as `ReviewedDocumentSource` cuts it.

| # | File | Heading | Anchor | Chars | Audience | Answers |
|---|---|---|---|---|---|---|
| S1 | alerts-and-notifications.md | Status Checks: what needs attention right now | `status-checks-what-needs-attention-right-now` | ~1760 | household | 1, 2, 3 (A1) |
| S2 | alerts-and-notifications.md | Alert levels and the marks on the tabs | `alert-levels-and-the-marks-on-the-tabs` | ~2100 | household | 4, 5 (A2); 20, 21 (A10) |
| S3 | alerts-and-notifications.md | Ignore, Remind me in and Restore | `ignore-remind-me-in-and-restore` | ~1920 | household | 6, 7 (A3); 8, 9 (A4); 10, 11 (A5); 12, 13 (A6) |
| S4 | alerts-and-notifications.md | Open device from an alert | `open-device-from-an-alert` | ~740 | household | 14, 15 (A7) |
| S5 | alerts-and-notifications.md | Phone notifications: what reaches your phone today | `phone-notifications-what-reaches-your-phone-today` | ~1990 | household | 16, 17 (A8) |
| S6 | alerts-and-notifications.md | A water leak alert and turning the water back on | `a-water-leak-alert-and-turning-the-water-back-on` | ~2780 | household | 22, 23 (A11); 19 (A9, second phrasing) |
| S7 | alerts-and-notifications.md | Reset, Mark seen and why there is no undo | `reset-mark-seen-and-why-there-is-no-undo` | ~1680 | household | 38, 39, 40 (B7) |
| S8 | alerts-and-notifications.md | Backend Rules card versus Home Assistant and Node-RED | `backend-rules-card-versus-home-assistant-and-node-red` | ~1010 | household | 18, 19 (A9) |
| S9 | alerts-and-notifications.md | Optimization Opportunities: Power and Duplicate readings | `optimization-opportunities-power-and-duplicate-readings` | ~1870 | administrator | 24, 25 (A12) |
| F1 | operations.md (fixed in place, anchor unchanged) | Who counts: location roles (ratified 2026-10-13, D24) | `who-counts-location-roles-ratified-2026-10-13-d24` | 3223 (was 1272) | household (as mapped today) | 105, 106, 107 (D6); 108, 109 (D7) |
| F2 | operations.md (fixed in place, anchor unchanged) | Opportunities and the Tech ID Service (K19) | `opportunities-and-the-tech-id-service-k19` | 2839 (was 2873) | administrator (as mapped today) | 25 (A12), technical detail |
| F3 | independent-installation.md (fixed in place, anchor unchanged) | Presence for each location | `presence-for-each-location` | 2900 | administrator (as mapped today) | none in the list; installer accuracy |

Budget note: `opportunity_status` today is K19 + `capability-map` = 5949 of 6000 after F2, so K19 was kept to a one-line correction with a pointer; do not add S9 to that category (it would exceed the budget and fail `ContextMapWiringTest`). `presence_roles_and_gaps` grows to 4871 with F1.

## Proposed map changes

Insert the new categories **before** `rules_workflows` so a tie with its generic `workflow` and `rule` keywords goes to them, and so `deployment_source`'s `push` loses to `push notification`.

| Category | New or existing | Keywords to add (whole words, singular) | Documents, in order | Total chars | Phrasings |
|---|---|---|---|---|---|
| `status_checks` | new | status check, what needs my attention, needs attention, need attention, what's wrong, alert, alert level, critical, warning, colored dot, dot, red dot, tab, open device, door left open | S1, S2, S4 (all household) | ~5130 | 1 to 5, 14, 15, 20, 21 |
| `alert_silencing` | new | ignore, ignored, snooze, snoozed, remind me, silence, silenced, restore an alert, bring back, go away, mute | S3, S1 (household) | ~3700 | 6 to 10, 12, 13 |
| `phone_notifications` | new | notification, push notification, notify, notified, notify me, phone alert, alert my phone, text me, ntfy | S5, S2 (household) | ~4100 | 16, 17 |
| `leak_response` | new | leak, water leak, water back on, turn the water, main water, water valve, shut off the water, flood | S6, S3 (household), then operations.md `rules-and-safety` (administrator) | ~5770 | 11, 19, 22, 23 |
| `workflow_reset_undo` | new, `boost: 1` | undo, reset, mark seen, reverse, take back, firing | S7, S6 (household) | ~4100 | 38, 39, 40 |
| `optimization_opportunities` | new | duplicate reading, drawing power, 48 hour, optimization opportunity, power card | S9, then K19 (both administrator) | ~4700 | 24, 25 |
| `rules_workflows` | existing | built-in rule, backend rule | append S8 (household) after its two sections | 5886 | 18 |
| `presence_roles_and_gaps` | existing | counted but not assigned, maintenance visit, maintenance person, primary and maintenance, make someone a maintenance | unchanged (F1 is its first section) | 4871 | 105 to 109 |

Notes for Code:

- `workflow_reset_undo` needs the boost (or to be placed first) because "How do I undo what a workflow did?" otherwise ties 1 to 1 with `rules_workflows` and `deployment_source` (both have `workflow`).
- Interactions with other scopes, seen in the simulation (no other phrasing changed route): 29 (B2, "make the cabin shut off the water when a leak is detected", was no category) now goes to `leak_response`; S6 says a workflow can shut the valve but is not the create-a-workflow how-to, so the admin scope's workflow section should outrank it (for example a "make the cabin" or "when a leak is detected" pattern in its category, or drop `shut off the water` here). 113 (E1, "What does the storage warning mean?", was no category) now goes to `status_checks` through `warning`; the admin scope's storage category should win with `storage`.
- `alert`, `critical`, `warning` and `dot`/`tab` are broad. They only ever add 1 point, so any more specific category still wins; review against the other scopes' keywords when merging all three proposals.
- Household audience for S1 to S8: they describe what anyone using the app sees and say what an administrator does, without exposing settings, ids or secrets. S9 is administrator because its card is administrator-only in the backend (`OpportunitiesController`).
- Household role and silencing: on main, any signed-in account except Viewer, guest and demo can Ignore/Snooze/Restore (no household-role check in `AlertController`). S3 states that as code behavior. If the household policy should be stricter, that is a code change, not a doc change.
