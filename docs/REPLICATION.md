# Replicating This Template — Independent Instance Guide

> For standing up a **genuinely separate, independently-running** copy —
> your own accounts, your own domain, your own host, your own GitHub repo.
> Not a second location sharing this family's config (that's what
> `cabin-orchestration-platform/locations/home/` is for) — a completely
> independent stack for a different family/user, using this repo as a
> template.
>
> "Equal to but not necessarily the same as" the current setup: this
> reference instance happens to use GitHub Actions, Tailscale, and
> Cloudflare Tunnel because they're free at personal scale and match this
> project's cost philosophy (see `CLAUDE.md`'s design constraints) — not
> because the architecture requires those specific vendors. Swap any of
> them for an equivalent (GitLab CI, WireGuard, another tunnel provider)
> and the underlying pattern — host-driven config baked at build time,
> Docker Compose overrides per host, a self-hosted CI runner pulling
> instead of a push-based deploy — still applies.

---

## 1. What's already agnostic (no changes needed)

- **Family data** — profiles (names/roles/avatars), chores, rewards,
  holidays, parenting schedule rules: all fully configurable through the
  app itself. `DEFAULT_PROFILES` (Sam/Emma/Nathan/Frankie) is first-run
  seed data, not a hard assumption — edit or delete it from the Family tab.
- **OAuth origin** — detected at runtime via `window.location.origin`,
  never hardcoded to a specific domain.
- **Google Client ID / admin emails** — host-configurable via Docker build
  args (`GOOGLE_CLIENT_ID`, `ADMIN_EMAILS` in `.env`), not baked into
  source. Every device that loads the app from your host inherits them
  automatically — see `family-hub/Dockerfile` and `host-config.js`.
- **Location-awareness** — the Spring Boot backend and device registry are
  already designed to be location-aware (`"cabin" | "home"` today), not
  hardcoded to a single site.

## 2. What still needs your own values (find-and-replace)

These are hardcoded as *this instance's* values, expected to be edited by
whoever forks the repo — not bugs, just template points:

| What | Where | Current value |
|---|---|---|
| GitHub org/repo | `.github/workflows/*.yml`, `ansible/site.yml`, `ansible/README.md` | `ImpressiveLLC/FaceoftheCabin` |
| Domain | README/ROADMAP examples, OAuth authorized origins | `unicornpingpong.com` |
| Org domain | README/ROADMAP | `impressive.llc` |
| Host machine name / Tailscale hostname | docs + `ansible/inventory.ini` examples | `nates-little-m920q` / `100.77.44.113` |
| Deploy user | `docker-compose.m920q.yml`, `ansible/inventory.ini`, workflow `DEPLOY_PATH` fallback | `nate` |
| Google Calendar/Photos account | `.env` examples, Settings UI default | `smrekarfamilia@gmail.com` |
| Admin account(s) | `.env` `ADMIN_EMAILS` | `nhsmrekar@gmail.com` |

## 3. Accounts you need (independent of this instance's)

- A **GitHub account/org** to fork into — this is where your CI/CD workflow
  and repo live.
- A **Google Cloud project** — for your own OAuth 2.0 Client ID (Calendar +
  Photos Picker scopes). Free tier is sufficient; keep the OAuth consent
  screen in Testing mode and add your family's emails as test users unless
  you specifically want public verification.
- A **domain + DNS provider** — this instance uses Porkbun (registrar) +
  Cloudflare (DNS + free Tunnel). Any registrar works; a tunnel provider
  with a free tier is what actually matters if your host is behind CGNAT
  (common on residential ISPs/Starlink) and can't accept inbound
  connections directly.
- A **host machine** — any always-on Linux box with Docker. This instance
  uses a Lenovo ThinkCentre M920q; any similarly-specced machine (or a
  cheap VPS, if you don't mind moving off "self-hosted for free") works.
- A **mesh VPN** for private/admin access (SSH, Grafana, Home Assistant) —
  this instance uses Tailscale (free tier, generous device limits).

## 4. Setup order

1. **Fork/clone** the repo into your own GitHub account or org.
2. **Find-and-replace** the values in §2 for your own org/domain/host names.
3. **Google Cloud** → APIs & Services → Credentials → new OAuth 2.0 Client
   ID (Web application). Authorized JavaScript origin = your Family Hub's
   eventual public URL (must be `https://`, exact host + port match — see
   `docs/EXECUTION_PLAN_2026-07-30.md` for the exact failure mode if this
   doesn't match precisely). Add your family's Google accounts as OAuth
   test users (Audience/OAuth consent screen tab).
4. **Host machine**: install Docker + Compose, join it to your mesh VPN.
5. **Domain + tunnel**: point your domain at your DNS/tunnel provider,
   configure it to forward to your host's Family Hub port.
6. **`.env`**: copy `cabin-orchestration-platform/infra/.env.m920q.example`
   to `.env` in that same directory, fill in your own values — including
   the new `GOOGLE_CLIENT_ID` and `ADMIN_EMAILS` from step 3.
7. **Bring up the stack**: `docker compose -f docker-compose.yml -f
   docker-compose.m920q.yml up -d --build` — or write your own override
   file (copy `docker-compose.m920q.yml` as a starting point) if your host
   doesn't need to coexist with a separately-managed Home Assistant/camera
   stack the way this instance's M920q does.
8. **CI/CD**: follow `ansible/README.md` end to end against your own repo
   and host — it's already written generically (`{{ ansible_user }}`,
   configurable inventory groups), just needs your registration token and
   inventory values.

## 5. What this guide deliberately doesn't cover

Family-specific product configuration (parenting schedule, chores,
rewards, holidays) — that's all done through the running app itself once
it's up, not through code or `.env`. This guide is only about getting an
*independent instance* running; day-to-day family configuration is the
whole point of the app's own Settings/Dashboard, not something to hardcode
per-fork.
