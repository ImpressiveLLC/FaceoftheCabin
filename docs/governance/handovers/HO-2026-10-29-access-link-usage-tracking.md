# HO-2026-10-29: Access link usage tracking (Cowork to Code)

**Status: GO from Nate, 2026-10-29.** Build it. Not yet scored or allocated a W-number (see Governance).

Because Nate said go without answering Q-AU-1 to Q-AU-3, build to the recommendations below. He can override in the PR review; each is a small change.

## Why
Nate cannot see who opened a demo or guest link, when, or what they looked at. Use case: the insurance agent got a demo link (UC-9); Nate wants to know if and how it was used before the follow-up conversation. Same need applies to contractor links.

## What exists today (verified in `main`, 2026-10-29)
- `DemoAccessFilter` writes one INFO line per demo request: `demo request token={id} route={path} class={ALLOW|ALLOW_REDACT}`. Lives only in the `cabin-backend` container log, subject to Docker log rotation. No UI.
- nginx (`cabin-ui`) logs IP, time, path, status, user agent. Token segment is masked (`/demo/[token]`), so it cannot say which link.
- `cabin_access_tokens` columns: id, token, label, scope, expires_at, revoked_at, created_by, created_at. No last-used or count.
- Tier 1 `/view/{token}` requests: no per-request log line at all.
- No analytics of any kind in the UI code (grep for gtag, GA, Plausible, Umami, PostHog: nothing).

## What Cloudflare shows (checked live by Nate and Cowork, 2026-10-29, Free plan, last 30 days)
Conclusion: Cloudflare cannot replace this build.
- Both `cabin.` and `api.unicornpingpong.com` are proxied. Cloudflare Web Analytics is already active on the cabin host (the `/cdn-cgi/rum` beacon is in its traffic). Nothing to add there.
- `cabin.` host: about 1,310 requests and 310 visits in 30 days. Top paths are `/` and static assets. No `/demo/<token>` or `/view/<token>` loads appear, even though Cowork opened a demo link on 2026-10-09. Cloudflare's path data is not a reliable record of tokenized page loads.
- Cloudflare has no notion of which token a request used and sees nothing of in-app interaction (it is one SPA page making API calls).
- Request logs and Instant Logs are Enterprise only, so per-request export is not available.
- Side findings, not in this scope: `74.188.6.106` makes about 1.2M of the 1.27M zone requests (one Firefox client polling `/api/alerts/active`, `/api/devices`, `/api/security`, `/api/presence`, `/api/rules/workflows`, `/api/frigate-metrics`); many other IPs also hit those open (D14) endpoints on `api.`; one Netherlands IP requested `/demo/.git/config` 30 times on 2026-10-09 (all 5xx, nothing exposed). Evidence for DL-2026-09-23-10.

## Proposed scope
1. **Table `cabin_access_events`** (created in `JdbcCabinAccessTokenStore` the same way as the token table): `id`, `token_id` (by value, not the token string), `occurred_at`, `tier` (view|demo), `route`, `policy_class`, `status`, `client_ip`, `user_agent`, `hits`.
2. **Write path.** Record from `DemoAccessFilter` (keep the existing INFO line too) and from the Tier 1 scope check in `GoogleAuthInterceptor`. Denied requests are recorded with their status. Never store the token string.
3. **Noise control.** The UI polls. Collapse repeats: one row per (token_id, route, 5-minute bucket) with a `hits` counter, via upsert. Record the `/demo/{token}` page load as its own event so "opened the link" is distinct from API chatter. The page load is a SPA route that never reaches the backend, so have the demo-mode UI send one lightweight authenticated GET on mount (an existing ALLOW route is enough, for example `/api/dashboard/config`, tagged by the filter as `page_open` when called with `X-Demo-Page-Open: 1`), and record that.
4. **Summary columns** on `cabin_access_tokens`: `first_used_at`, `last_used_at`, `use_count`. Updated in the same write.
5. **Admin read API.** `GET /api/access-tokens/{id}/activity?days=30` returning events newest first. Already covered by the existing `DENY` on `/api/access-tokens/**` for demo tokens; add a test that proves a demo token still gets 403 on it.
6. **UI.** Settings, Access Links: show "Last opened" and "Opens" on each row; a "View activity" expander listing time, panel (route mapped to a panel name: Sensor History, Alerts, Rules, Devices), status. Admin only.
7. **Retention.** Purge events older than 90 days (scheduled job, `cabin.access.events-retention-days`, default 90).
8. **Client IP.** Behind Cloudflare use `CF-Connecting-IP`, else first `X-Forwarded-For`, else remote address.

## Decisions (defaults applied because Nate said go; he can override at PR review)
- Q-AU-1: store full client IP, 90-day retention, admin-only.
- Q-AU-2: no change to the demo banner copy (no viewer disclosure). Nate's call to revisit.
- Q-AU-3: no third-party analytics. Google Analytics would send the page URL, which contains the live token, to Google, defeating R-DM-10. Cloudflare Web Analytics is already on and stays as is.

## Tests (Code)
1. Demo request writes an event with the token id, never the token string.
2. Two identical requests inside one 5-minute bucket produce one row with `hits = 2`.
3. A denied demo route (for example `/api/camera/list`) is recorded with status 403.
4. Tier 1 `/view/{token}` request is recorded.
5. `GET /api/access-tokens/{id}/activity` returns 403 for a demo token and 200 for an admin session.
6. Purge job removes rows older than the retention setting.
7. Demo page-open call records a `page_open` event.
8. UI (Vitest): Access Links row renders last opened and opens count; activity expander lists events.

## Governance
- Traces to UC-9 (and the contractor-link use case if one exists). Related: D12, D14, D22 R-DM-10, DL-2026-09-23-10, DL-2026-09-23-11.
- W-number: allocate the next free one at commit time per the ID rule (main topped out at W-120 on 2026-10-29; re-check open PRs and branches). Log a DL entry if the number changes.
- WSJF: TBD (Cowork scores with Nate after build; does not gate it, Nate has given go).
- DoD: Level 1 for backend plus UI; add a live check on the M920q that a real demo-link visit shows up in Access Links.
- Deploy: normal path (merge deploys backend and UI). Table is created on boot, no manual migration.
- Freshness: follow `fotc-git-and-host-routing` (fresh from main before and after).

## Not in scope
Real-time alerts when a link is opened. Per-viewer identity. Heatmaps or session replay. Changing D14 anonymous reads (DL-2026-09-23-10): those leave no token trail and stay that way until Nate rules. Rate-limiting or blocking the scanner traffic.

## Report back
1. W-number allocated and PR number.
2. Any change to the Q-AU defaults as recorded in the PR.
3. M920q live check result.

_Cowork draft: 2026-10-29. Go from Nate: 2026-10-29._
