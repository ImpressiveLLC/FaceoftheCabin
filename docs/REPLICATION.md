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
6. **Secrets**: `.env` is Ansible Vault-managed now, not hand-copied from
   the example file — follow `ansible/README.md`'s Secrets section
   end to end (create your own vault password, `ansible-vault create
   ansible/group_vars/cabin/vault.yml`, fill in your own
   `POSTGRES_PASSWORD`/`GRAFANA_PASSWORD`/`HA_TOKEN` values, plus your new
   `GOOGLE_CLIENT_ID`/`ADMIN_EMAILS` from step 3 in
   `group_vars/cabin/vars.yml`). Your vault password is a brand-new secret
   for this instance — never reuse the original instance's.
7. **Bring up the stack**: `docker compose -f docker-compose.yml -f
   docker-compose.m920q.yml up -d --build` — or write your own override
   file (copy `docker-compose.m920q.yml` as a starting point) if your host
   doesn't need to coexist with a separately-managed Home Assistant/camera
   stack the way this instance's M920q does.
8. **CI/CD**: follow `ansible/README.md` end to end against your own repo
   and host — it's already written generically (`{{ ansible_user }}`,
   configurable inventory groups), just needs your registration token and
   inventory values.

## 5. New Instance Acceptance Test

A replica isn't "done" because containers are running — verify these
concretely before considering setup complete. Each of these has, at some
point in this project's own history, silently failed while everything
*looked* fine (see `MAINTENANCE.md`'s Known Issues log) — don't skip
straight to "it's up" without checking the actual behavior.

- [ ] Family Hub loads at your public domain, not just `localhost`.
- [ ] Google Sign-In completes without an origin-mismatch error, from a
      **real browser**, not just a `curl` check (CORS preflight behavior
      cannot be validated with `curl` — see `MAINTENANCE.md`).
- [ ] A note posted on one device/browser appears on a second, signed-in
      device/browser within one sync cycle (~20s) — this is the actual
      cross-device sync working, not just the API returning 200.
- [ ] A chore marked complete on one device shows complete on another.
- [ ] Adding a Friends & Family profile on one device makes it appear on
      another.
- [ ] The public camera-activity widget (if cameras are configured)
      respects its Full/Coarse/Off setting.
- [ ] A secrets rotation (`ansible/playbooks/rotate-secrets.yml`) runs
      successfully end to end against the real host, and the validation
      step genuinely catches a bad rotation (temporarily point it at a
      wrong value once, confirm it fails loudly, not silently).
- [ ] A push to `main` touching `family-hub/**` triggers an automatic
      deploy, confirmed against the live served page — not just a green
      Actions checkmark.

## 6. Onboarding a new device or integration

This project deliberately follows one repeatable pattern for adding any
new device, camera, or third-party integration — established across
Zigbee sensors, Frigate cameras, smart appliances, and WiFi-based
presence detection. Follow it for anything new rather than improvising
per-device:

1. **Check what already exists.** Most devices have a real integration
   already — check the platform's installed integration list (Home
   Assistant's `manifest.json` files, or the relevant service's own
   plugin/integration registry) before writing custom bridge code. This
   project's Liebherr/Bosch appliance work found both had first-class HA
   integrations already installed and available, just unconfigured.
2. **Identify what account/credential linking is actually required**,
   and be explicit about what can and can't be automated. Consumer
   cloud-connected devices (Liebherr SmartDevice, Bosch Home Connect)
   typically need the owner's own account login or a developer-portal
   API registration — steps only the account owner can complete, not
   something to script around.
3. **Add the ontology entity first**, even before the integration is
   configured — `migration_status: planned`, with `notes:` stating
   plainly what's built vs. not and what's blocking it. This project's
   ontology entities for unconfigured appliances (see
   `docs/ontology.yaml`'s `smart_appliance_*` entities) are the template:
   documented as real, findable entities from day one, not silently
   absent until someone gets around to wiring them up.
4. **Verify against the real, installed source or live API response**,
   not generic vendor documentation — config schemas, label sets, and
   default behaviors drift between versions. Every device-integration
   decision in this project's history that assumed a generic default
   instead of checking the real installed version eventually turned out
   wrong in some detail (see `MAINTENANCE.md`'s Known Issues log for
   concrete examples).
5. **Flip `migration_status` to `complete` only after a live, real-device
   test** — not after the config merely validates or the container starts
   cleanly.

## 7. Security & credential handling posture

Worth being explicit about, since this differs meaningfully by surface:

- **Public surfaces** (`hub`/`cabin`/`api` subdomains): the actual
  product. Notes/chores/profiles/camera-media write endpoints are gated
  by a real server-side check (Google OAuth token validated against
  Google's own tokeninfo endpoint on every request) — not merely hidden
  client-side. Anyone with a valid Google account and a token can write;
  this is a deliberate "same trust as a fridge note" model, not a gap —
  see `docs/ontology.yaml` for the reasoning if you need a stricter model
  for your own instance.
- **Admin/reconfiguration surfaces** (Home Assistant, Node-RED, Frigate's
  own admin UI, Zigbee2MQTT, Grafana): Tailscale-only, never exposed
  through the public tunnel. This is the actual security boundary in this
  architecture — not the product surface itself.
- **Secrets**: Ansible Vault-managed, encrypted at rest, safe to commit.
  The vault password itself is the one secret that must live outside the
  repo, generated fresh per instance — never reuse a forked instance's
  vault password. See `MAINTENANCE.md`'s Secrets section for the actual
  rotation mechanics.
- **Never diff or log a secret by its raw value**, in any tooling,
  including AI-assisted sessions working on this repo — compare by
  presence/hash. This project had a real, if brief, incident where a
  password appeared in a session transcript this way.

## 8. Tech ID Service — choosing a scanning tier

See `ROADMAP.md`'s "Tech ID Service — Provider Model" for the full
design; this section is only the practical setup choice for a new
instance. The findings API (`POST`/`GET`/`PATCH
/api/tech-id/findings`) ships with every instance regardless of which
option below you pick — it's the one piece that isn't optional.

**Step 1 — decide who does the scanning.** Three options, not mutually
exclusive (an instance can run more than one provider concurrently; the
API doesn't distinguish):

1. **Use the free reference routine** — a scheduled Claude Code cloud
   routine (or any equivalent scheduled job) that runs against your own
   repo/ontology on a recurring cron and POSTs findings to your own
   instance. This is what the original instance runs. No extra cost
   beyond whatever AI usage the routine itself consumes.
2. **Pay the platform operator for a higher tier** — if you'd rather not
   run your own scanning job, ask about an operator-run scanning
   service pointed at your instance's `/api/tech-id/findings`. This is
   a commercial relationship between you and whoever operates that
   tier, not something this template configures for you.
3. **Bring your own AI/research pipeline** — point any script, agent, or
   vendor research API you already pay for at your own instance's
   endpoint instead. Cheaper, volume-priced models work fine here — the
   findings contract (`entityId`, `provider`, `findingType`, `summary`,
   `confidence`, `sources[]`) doesn't care which model produced a
   finding, only that it's shaped correctly.

**Step 2 — set the shared secret.** In `group_vars/cabin/vars.yml` (or
your instance's equivalent vault-backed vars file), set
`tech_id_api_key` and reference it from `.env` as `TECH_ID_API_KEY`
(mirrors the existing `POSTGRES_PASSWORD`/`HA_TOKEN` pattern — see
`ansible/README.md`'s Secrets section). Leaving it unset is a valid
choice: submission returns `503` and the instance simply never receives
automated findings, which is fine if you don't want this capability at
all yet.

**Step 3 — configure the scheduled kickoff.** This is a *time-based*
trigger, not a git-driven one — it should fire on a cron schedule
regardless of whether anything changed in the repo that day. If using
the reference routine, this means setting up a `RemoteTrigger` cloud
routine (or your CI provider's own scheduled-pipeline feature) with:
your repo URL, a cron expression (the original instance uses monthly:
`0 8 1 * *`, UTC), and a prompt that has the routine research your
cataloged ontology entities and `POST` results — with the
`X-Tech-Id-Api-Key` header — to `https://<your-api-domain>/api/tech-id/findings`
rather than (or in addition to) opening a PR directly against
`ontology.yaml`. **Requires granting your CI/scheduling provider's app
access to your fork** — for a GitHub-App-based scheduler this is a
one-time step in that provider's own settings, scoped to your specific
repo/org, distinct from any other GitHub App permission.

**Step 4 — adjudicate.** Findings land in the `new` state and don't
change anything on their own. A signed-in human reviews them (`PATCH
/api/tech-id/findings/{id}`, `status: reviewed | actioned | dismissed`)
and, for anything actually worth keeping, updates the corresponding
entity's `discovery:` fields in `docs/ontology.yaml` by hand — this
reconciliation step is intentionally manual today (see `ROADMAP.md`
Phase 4's open items).

## 9. What this guide deliberately doesn't cover

Family-specific product configuration (parenting schedule, chores,
rewards, holidays) — that's all done through the running app itself once
it's up, not through code or `.env`. This guide is only about getting an
*independent instance* running; day-to-day family configuration is the
whole point of the app's own Settings/Dashboard, not something to hardcode
per-fork. For that, see [`USER_GUIDE.md`](USER_GUIDE.md).
