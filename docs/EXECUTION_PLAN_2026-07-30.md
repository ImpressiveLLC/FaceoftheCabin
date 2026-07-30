# Execution Plan — 2026-07-30 Cloudflare + Subdomain Rollout

> Written overnight (2026-07-29 → 2026-07-30) by Claude Code while you were
> asleep, per your instruction to prep everything possible for a fast start.
> Everything below is a plan + prep work — nothing here touches your live
> Cloudflare account, DNS, or the M920q. Review before running anything.

---

## 0. What actually changed overnight (read this first)

All in `C:\dev\FaceoftheCabin` (your other local clone — not the one under
`H:\My Drive\...`), **committed locally, not pushed**:

1. **Family Hub notepad/chores overlap** — **NOT FIXED.** I could not find a
   "notepad" feature anywhere in this repo (git history, working tree) or in
   the `H:\My Drive\...` clone. The only other file named `family-hub.html`
   on this machine is under
   `C:\Users\nhsmr\Documents\Codex\2026-07-26\referenced-chatgpt-conversation-this-is-untrusted-2\`
   — a folder you (or a prior session) explicitly labeled **untrusted**, so I
   did not pull anything from it or guess at a fix. If the notepad feature
   only exists on the M920q's local filesystem (unpushed, like the compose
   file blocker below), that's where it needs to be found before it can be
   fixed here.
2. **Parenting schedule rewritten as versioned rules** — no longer a single
   flat anchor date. See §4. New anchor **July 27, 2026**, homeDays
   `[2,3,4,5,9,10]` (Wed/Thu/Fri/Sat week A, Wed/Thu week B — the schedule
   Rachel confirmed by text). The **old March 13, 2026 rule is preserved**,
   not deleted — historical dates before July 27 still compute under it.
3. **Holidays & Vacations** — new CRUD section in Settings: any signed-in
   user can add a date + name + "Dad's"/"Mom's" designation that overrides
   the regular cycle for that one day.
4. **Observations** — new reporting section in Settings: nights per parent
   for the current 2-week cycle, trailing/upcoming 3 months, or a discrete
   Jan 1–Dec 31 calendar year, split into "recorded" (past, from the daily
   custody log) vs "scheduled" (future, computed live).
5. **Rewards** — converted from a hardcoded list to full add/edit/delete,
   with a persisted, timestamped change history.
6. **Access log** — first-seen timestamp + session duration per signed-in
   user, visible/exportable only to `nhsmrekar@gmail.com`. Required adding
   `openid`/`email` scope to Google sign-in (previously the app only ever
   requested an opaque calendar/photos token — no identity at all).
7. **`docs/ontology.yaml`** — added 4 new entities (`parenting_schedule_rule_version`,
   `holiday_override`, `custody_day_log`, `observation_report`) and fixed
   3 **pre-existing** YAML syntax bugs unrelated to tonight's work (unquoted
   colons inside parenthetical values around lines 392/517/568 broke strict
   YAML parsing). One more pre-existing bug remains unfixed — the
   `custody_status` entity's `semantic:` line concatenates two quoted
   strings with `/`, which is invalid YAML; I left it since it's outside
   tonight's scope and I didn't want to touch entities I hadn't verified.
8. **`ROADMAP.md`** — updated the stored "Parenting anchor" table row to
   point at the new versioned-rule design instead of the old flat value.

Everything above passed a Node-based logic test (19 checks, all passing —
old-rule dates, new-rule dates across both weeks, holiday override, rule
amend-vs-new-version behavior, immutability of superseded rules) since I
could not get a live browser session against this file (sandbox policy
blocks the Browser pane from reaching `localhost` servers outside the
`H:\My Drive\...` project root). **You should still click through it once
in an actual browser before trusting it** — Node logic tests aren't a
substitute for seeing the Settings UI render correctly.

Not yet done, in scope from your last message, needs a clear head:
- Full mechanical rename of internal "home/away" naming (CSS classes,
  variable names) to explicit Mom's/Dad's — I kept the internal plumbing
  as-is and only changed user-facing language, to limit blast radius on a
  2400-line file with no one available to catch a bad edit overnight.
- A live "preview future impact before Save" view in the day-picker (you
  asked to see how a change ripples forward — right now you have to Save
  first, then check the 28-day schedule grid, which does update live).

---

## 1. Products & subdomains (from `README.md` / `ROADMAP.md`, already committed)

| Subdomain | Product | Backing service (per docs) |
|---|---|---|
| `hub.unicornpingpong.com` | Family Hub | `family-hub` container (nginx:alpine, port 80 — see `family-hub/Dockerfile`) |
| `cabin.unicornpingpong.com` | FaceOfTheCabin (cameras, sensors, alerting) | Frigate (5000/1984) + HA (8123) + Z2M, per `cabin-orchestration-platform/infra/docker-compose.yml` |
| `api.unicornpingpong.com` | Platform API (identity, ontology, event bus, audit trail) | Spring Boot backend, port 8080 |

**Your message mentioned `monitoring.`, `qa.`, `config.`, and "familyhub." —
those are not what's currently documented.** Reconciling:

- `familyhub.` vs `hub.` — the docs use `hub.unicornpingpong.com`. Pick one
  before wiring DNS; I did not rename anything to avoid guessing wrong.
- `monitoring.` — **not yet in README/ROADMAP**, but there's a real
  candidate for it: Grafana (port 3000) and/or Uptime Kuma + Homepage
  (mentioned in README's ops table but not yet containerized in the repo's
  `infra/docker-compose.yml`). Worth adding as a real subdomain if you want
  monitoring exposed publicly — otherwise it can just stay on Tailscale.
- `qa.` — not documented. `cabin-orchestration-platform/qa/cabin_qa.py`
  exists but produces local JSON reports, not a web service — nothing to
  point a subdomain at yet unless you want to build a small report viewer.
- `config.` — "Family Config" is a **panel inside the platform UI**
  (`api.unicornpingpong.com` / the React app), not a separate service. It
  doesn't need its own subdomain unless you specifically want to split it out.

**Recommendation:** wire `hub`, `cabin`, `api` first (they're documented,
scoped, and have real backing services). Add `monitoring` next since Grafana
already exists. Treat `qa` and `config` as later/maybe.

---

## 2. Cloudflare Tunnel — what's ready vs. what needs you

You said Cloudflare is already set up on your end (account, likely the
tunnel itself) and you'll add the actual hostnames tomorrow. I can't create
tunnels, DNS records, or touch your Cloudflare dashboard from here — that
needs your own login. What I *can* hand you is the exact config to paste in.

### `cloudflared` ingress config template

```yaml
# /storage/containers/compose/cabin/cloudflared/config.yml on the M920q
# (path per ROADMAP.md's documented compose location)
tunnel: <YOUR_TUNNEL_ID>
credentials-file: /etc/cloudflared/<YOUR_TUNNEL_ID>.json

ingress:
  - hostname: hub.unicornpingpong.com
    service: http://family-hub:80
  - hostname: cabin.unicornpingpong.com
    service: http://localhost:5000   # Frigate UI — adjust if a unified cabin gateway exists
  - hostname: api.unicornpingpong.com
    service: http://localhost:8080   # Spring Boot backend
  - hostname: monitoring.unicornpingpong.com
    service: http://localhost:3000   # Grafana, if you want this public
  - service: http_status:404         # catch-all, must be last
```

### Docker Compose service to add (wherever the M920q's real compose file is)

```yaml
  cloudflared:
    image: cloudflare/cloudflared:latest
    container_name: cabin-cloudflared
    restart: unless-stopped
    command: tunnel --config /etc/cloudflared/config.yml run
    volumes:
      - ./cloudflared:/etc/cloudflared
    depends_on:
      - family-hub
```

**Important gap:** `infra/docker-compose.yml` in this repo does **not**
contain a `family-hub` service, a backend service, or a UI service — those
run outside Compose per `CLAUDE.md`'s dev instructions, and the *real*
M920q compose file lives only at `/storage/containers/compose/cabin/` on
the cabin machine itself, not in any repo (this is the same known
`[BLOCKER]` already flagged in `ROADMAP.md` line ~326: push the M920q
compose file to `CabinAutomations`). **Before wiring the tunnel, pull down
or inspect the actual M920q compose file** so the `service:` targets above
point at real container names/ports instead of my best guess from the docs.

### Manual steps only you can do (Google/Cloudflare account actions)

1. Cloudflare dashboard → your tunnel → add the four public hostnames above,
   pointing at the ingress rules.
2. Porkbun/Cloudflare DNS → confirm `unicornpingpong.com` nameservers are
   Cloudflare's (ROADMAP says this was still `_(in progress)_` as of
   2026-07-27 — verify it's actually resolved now).
3. Google Cloud Console → OAuth consent screen → **Authorized JavaScript
   origins**: add `https://hub.unicornpingpong.com` (ROADMAP already flags
   this as a to-do, currently still pointing at the Tailscale hostname).
4. Google Cloud Console → OAuth consent screen → **Test users**: add
   `rachelgholman@gmail.com` and `rachelgholman@yahoo.com` if you want them
   able to sign in while the app is in Testing mode. This is a Google
   Cloud Console setting — I have no access to your Google account, so this
   has to be you, not code.

---

## 3. CI/CD & Ansible

You mentioned a placeholder for both already exists somewhere — I could not
find `.github/`, any `ansible*` file, or CI/CD config anywhere in either
local clone (`C:\dev\FaceoftheCabin` or the `H:\My Drive\...` copy). If it's
on the M920q only (same pattern as the unpushed compose file and the
notepad feature), it needs to be located and pulled into the repo before I
can wire anything to it. If it genuinely doesn't exist yet, a minimal
starting point once you confirm GitHub Actions is the choice:

- `.github/workflows/deploy.yml` — on push to `main`, SSH to M920q (via
  Tailscale, using a deploy key — not a password) and run `git pull &&
  docker compose up -d --build` in the compose directory.
- Ansible: a single playbook that idempotently ensures `cloudflared`,
  Docker, and the compose stack are present — useful mainly if you ever
  stand up a third location beyond cabin/home, less critical for two boxes
  you already manage by hand.

Flagging rather than building blind, since I don't know what you already
started.

---

## 4. Parenting schedule — data model reference

For whoever (you, or future-me) needs to reason about this later:

- `localStorage['smrekar_schedule_rules']` — array of
  `{ id, effectiveFrom, anchor, homeDays, label, createdAt, createdBy }`.
  `ruleForDate(dateKey)` picks the latest rule with `effectiveFrom <= dateKey`.
  Saving in Settings with the same `effectiveFrom` as the current rule
  amends it in place; a new date pushes a new version. Old versions are
  never mutated once superseded — this is what makes "day0 minus all
  previous days" stay factual.
- `localStorage['smrekar_holidays']` — array of
  `{ id, date, name, owner: 'mom'|'dad' }`. Checked before the rule for any
  given date.
- `localStorage['smrekar_custody_log']` — `{ 'YYYY-MM-DD': 'dad'|'mom' }`,
  materialized forward from the earliest rule's anchor through today on
  every app load. This is the "recorded" side of Observations.
- `ownerOfDate(date)` is the one function everything else should call —
  holiday override, else rule lookup. `isKidsHome(date)` is now just
  `ownerOfDate(date) === 'dad'`, kept only because ~15 existing call sites
  (schedule grid, chore/reward eligibility) already depend on that exact name.

---

## 5. Suggested order for tomorrow

1. Open the Family Hub in an actual browser, click through Settings →
   Parenting Schedule, Holidays, Observations, Rewards. Confirm nothing
   visually broke (I could only logic-test this, not see it render).
2. Locate the real M920q compose file + find out where the notepad feature
   and any CI/CD/ansible placeholder actually live — all three are
   apparently only on the M920q's local disk, unpushed, same as the
   previously-flagged compose-file blocker.
3. Reconcile the subdomain list (§1) — confirm `hub` vs `familyhub`,
   decide on `monitoring`, drop or scope `qa`/`config`.
4. Wire the Cloudflare Tunnel hostnames + do the two Google Cloud Console
   steps in §2.
5. Only after Rachel has actually opened the hub and you've talked to her
   about it — add the vacation request to the calendar, per your note that
   this needs to sit for a day or two first.
