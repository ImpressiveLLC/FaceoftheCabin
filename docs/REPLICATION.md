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
- **Presence detection** — `PresenceService` derives the toolbar's
  presence pin from live MQTT signals on `{location}/presence/{personId}`
  (any location, any number of people — not "one specific person at one
  specific site"), auto-registering each new location/person the moment
  their first signal arrives, same pattern as device auto-registration.
  Real signals aren't required to run this app — a manual override
  (toolbar dropdown / `PUT /api/presence`) is always available and is
  exactly what's used until the first real signal ever arrives, see
  `docs/ontology.yaml`'s `active_presence_profile` entity — but any
  instance can plug in real detection just by publishing to that topic
  contract; nothing backend-side needs editing. This instance's examples:
  an HA automation doing a WiFi ARP check for a phone on the local
  network (publishing `home`/`not_home` to `cabin/presence/nate`, see
  `ontology.yaml`'s
  `automation_cabin_security_publish_nate_presence_from_phone`), and a
  GPS-zone automation for locations with no local network to ARP-scan
  (see §3's "Home/cabin GPS coordinates" callout below — **this second
  kind requires real address input at setup time**, not just accounts).

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
| Instance platform description | `.env`/compose `CABIN_INSTANCE_PLATFORM` | `Self-hosted — Lenovo ThinkCentre M920q, Ubuntu 24.04, x86_64` |
| Remote access method(s) | `.env`/compose `CABIN_INSTANCE_REMOTE_ACCESS` (comma-separated) | `Tailscale,Cloudflare Tunnel` |

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
- **Home/cabin GPS coordinates** (latitude/longitude), for every physical
  location where you want zone-based presence detection (see §1's
  Presence detection bullet) — **required input, not optional, if you want
  that location's presence pin to be real instead of manual.** Gather
  this at the same time as your other setup values (§4 below), not as an
  afterthought once the app is already live: it's used to define an HA
  `zone:` entry, so a location with no zone defined simply has no
  automatic presence signal for that site, same as if you'd skipped
  creating a Google OAuth Client ID. These values are deliberately **never
  committed to this repo** — they live only in each instance's own
  `homeassistant/packages/` config (untracked, same posture as every other
  per-instance secret/PII value — see §7), not in `.env`, not in any
  docs file, not in git history.
- **Whatever you call a location in this app's own UI is not the same as
  what Home Assistant is allowed to call its zone.** Found live 2026-08-21,
  the hard way: naming a new HA `zone:` entry literally `Home` registers it
  as HA's own reserved `zone.home` entity id — HA's built-in Person
  integration treats that id specially (any linked device_tracker entering
  it reports state literally `"home"`), which can wake up an unrelated
  automation elsewhere in the same HA instance that was written assuming
  only the *original* physical "home" zone could ever produce that state
  (a phone entering a second location's zone falsely triggered the
  *cabin's* own presence automation, since both zones resolved to the same
  reserved id). This app's own `hub_location`/"Add Place" concept (§1,
  §4's step 9) already separates a technical `id` from a free-text
  `label` correctly — this gotcha is one layer *below* that, inside
  Home Assistant's own zone-naming, not something this app's UI exposes
  or can fix. **Name every non-primary location's HA zone something that
  is not literally "Home"** (this instance uses `House`) to avoid the
  collision entirely.

## 4. Setup order

1. **Fork/clone** the repo into your own GitHub account or org.
2. **Find-and-replace** the values in §2 for your own org/domain/host names.
3. **Google Cloud** → APIs & Services → Credentials → new OAuth 2.0 Client
   ID (Web application). Authorized JavaScript origin = your Family Hub's
   eventual public URL (must be `https://`, exact host + port match — see
   `docs/EXECUTION_PLAN_2026-07-30.md` for the exact failure mode if this
   doesn't match precisely). Add your family's Google accounts as OAuth
   test users (Audience/OAuth consent screen tab). `ADMIN_EMAILS` is the
   actual entry gate — only accounts listed there can sign into the
   Orchestration Hub at all, in the Config panel's "Switch Google Account"
   or anywhere else in the app; add every account that should ever be able
   to sign in, not just the primary one. Switching to an account already
   listed in `ADMIN_EMAILS` works from the Config panel once that account
   is already signed into Google in the same browser — bringing in an
   account that isn't currently signed into Google there at all is not yet
   supported (tracked in `ROADMAP.md`).
4. **Instance metadata**: set `CABIN_INSTANCE_PLATFORM` (a short
   human-readable description of what you're actually running on — self-
   hosted hardware, a cloud VM, a specific provider) and
   `CABIN_INSTANCE_REMOTE_ACCESS` (comma-separated; defaults to `Tailscale`
   if unset, matching this guide's §3 mesh-VPN recommendation) in your
   compose environment. Both display verbatim in the Config panel's
   Platform / Remote Access cards — see `DashboardController`'s
   `/api/dashboard/config`.
5. **Home/cabin GPS coordinates** (see §3): gather the real latitude/
   longitude for every physical location you want zone-based presence
   detection at, at the same time you're gathering your other setup
   values — not something to circle back for after the app is already
   live and someone notices presence looks wrong. You'll enter these into
   an HA `zone:` block per location in step 9; nothing to do with them
   yet, just don't lose them between now and then.
6. **Host machine**: install Docker + Compose, join it to your mesh VPN.
7. **Domain + tunnel**: point your domain at your DNS/tunnel provider,
   configure it to forward to your host's Family Hub port.
8. **Secrets**: `.env` is Ansible Vault-managed now, not hand-copied from
   the example file — follow `ansible/README.md`'s Secrets section
   end to end (create your own vault password, `ansible-vault create
   ansible/group_vars/cabin/vault.yml`, fill in your own
   `POSTGRES_PASSWORD`/`GRAFANA_PASSWORD`/`HA_TOKEN` values, plus your new
   `GOOGLE_CLIENT_ID`/`ADMIN_EMAILS` from step 3 in
   `group_vars/cabin/vars.yml`). Your vault password is a brand-new secret
   for this instance — never reuse the original instance's.
9. **Bring up the stack**: `docker compose -f docker-compose.yml -f
   docker-compose.m920q.yml up -d --build` — or write your own override
   file (copy `docker-compose.m920q.yml` as a starting point) if your host
   doesn't need to coexist with a separately-managed Home Assistant/camera
   stack the way this instance's M920q does. If you're wiring up real
   presence detection, this is also where you add each location's
   `zone:` block (using the GPS coordinates from step 5) and matching
   automation to Home Assistant's own config — see §1's Presence
   detection bullet; these files live in HA's own config directory, not
   this repo, same "never committed" posture as every other per-instance
   secret.
10. **CI/CD**: follow `ansible/README.md` end to end against your own repo
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
- [ ] If real presence detection is wired up for a location: `GET
      /api/presence` shows a real signal for that location (not just the
      manual-fallback default), and walking a paired phone in/out of that
      location's zone (or WiFi range) actually changes it within one
      debounce cycle — confirmed live, not just "the automation looks
      right in the YAML."

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

## 10. Monitoring & cross-container health

Three layers answer different questions and should remain separate:

1. **Docker healthchecks** answer whether one running container can perform
   its own minimum useful operation. Docker exposes the result through
   `docker compose ps` and can use it for dependency/startup decisions.
2. **Uptime Kuma** answers whether one service can reach and observe another
   continuously, and pages a person when that path fails.
3. **The cross-container CI smoke test** answers whether a proposed compose
   or integration change breaks a real message path before it reaches a hub.
   That workflow is a separate deployment-gate phase; a green Docker or Kuma
   result does not replace it.

### Docker healthchecks shipped with the M920q stack

The six checks below are already part of the tracked compose definitions. A
fresh instance should show each service as `(healthy)` in `docker compose ps`
after its `start_period`; `running` without `(healthy)` is not equivalent.

| Service | Probe | What a passing result proves |
|---|---|---|
| `mosquitto` | Subscribe once to `$SYS/broker/uptime` with `mosquitto_sub` | The broker is processing an MQTT subscription and delivering its own live system message, not merely holding TCP port 1883 open. Compose uses `$$SYS` so Compose passes a literal `$SYS` topic to the container instead of treating `$SYS` as interpolation. |
| `zigbee2mqtt` | `wget` the service UI at `http://localhost:8080/` | The Zigbee2MQTT application has started far enough to answer HTTP inside its container. Coordinator/device-path coverage belongs in the cross-container smoke test and live monitoring; this check does not claim that every Zigbee device is reachable. |
| `cabin-postgres` | `pg_isready -U cabin -d cabin` | PostgreSQL is accepting connections for the configured application database/user. This is deeper than a port-open check, but it is not a query-level durability test. |
| `cabin-kafka` | `kafka-broker-api-versions --bootstrap-server localhost:9092` | A Kafka client can complete broker protocol negotiation. A listening socket alone would not pass. |
| `cabin-backend` | GET `/actuator/health`, require `"status":"UP"` | Spring Boot is serving its application health endpoint and its registered health contributors report UP. The body assertion prevents an arbitrary HTTP response from counting as healthy. |
| `cabin-discovery` | Python `urllib` GET `/health`, require HTTP 200 | The actual FastAPI process can route and answer a request. Its `python:3.12-slim` image intentionally has no curl/wget, so the probe uses its existing standard-library runtime rather than adding a package solely for healthchecking. The endpoint is process health, not a claim that an optional external discovery provider is reachable. |

The production services (`mosquitto`, `zigbee2mqtt`) live in
`cabin-orchestration-platform/infra/production-stack/docker-compose.yml`.
Postgres and Kafka are defined in `infra/docker-compose.yml`; the M920q
backend and discovery checks are in `infra/docker-compose.m920q.yml`. Read the
base and overlay together when evaluating the deployed stack.

Useful first checks on a replica are:

```bash
docker compose -f docker-compose.yml -f docker-compose.m920q.yml config
docker compose -f docker-compose.yml -f docker-compose.m920q.yml ps
docker inspect --format '{{json .State.Health}}' <container-name>
```

Do not disable a failing check just to make the table green. Inspect its
recorded health output and the service log, fix the failed function, then
confirm the check recovers.

### Frigate environment substitution is prefix-restricted

Frigate does not expand every container environment variable referenced as
`{VAR}` in `config.yml`. Its config loader only makes variables prefixed
`FRIGATE_` available for that substitution. Keep the two-layer mapping:

```yaml
# docker-compose.yml
environment:
  FRIGATE_RTSP_PASSWORD: "${CAMERA_PASSWORD}"

# frigate/config.yml
path: rtsp://admin:{FRIGATE_RTSP_PASSWORD}@camera/...
```

`${CAMERA_PASSWORD}` is Compose interpolation from the host-only `.env`;
`{FRIGATE_RTSP_PASSWORD}` is Frigate's later in-container substitution. A
plain `{CAMERA_PASSWORD}` placeholder can leave Frigate crash-looping even
when the original secret is present and correct. Never print or diff the raw
secret while diagnosing this; compare presence or length only.

### Uptime Kuma monitor set

The reusable target set is:

- MQTT `$SYS/broker/uptime` on `mosquitto:1883`;
- Zigbee2MQTT HTTP on `http://zigbee2mqtt:8080/`;
- cabin-backend `http://cabin-backend:8090/actuator/health`;
- cabin-discovery `http://<hub-lan-ip>:8091/health` (the discovery container
  deliberately does not join the production stack's `cabin_default` network,
  so Kuma reaches its published host port rather than container DNS);
- MQTT `cabin/camera/available` on `mosquitto:1883`, expected payload
  `online` — this is the direct regression monitor for Frigate being up but
  disconnected from MQTT; and
- the existing Frigate `driveway` `camera_fps > 0` JSON-query monitor, which
  catches a camera bridge that remains running but has stopped producing
  frames.

Attach the instance's default ntfy notification to every monitor that should
page; a green/red monitor with no notification route is only a dashboard.
Clear any stale retained `frigate/available` message once during migration so
the old topic cannot be mistaken for the current `cabin/camera/available`
contract.

#### Kuma config-as-code decision — POC passed, production approval pending

[Uptime Kuma 2.x removed JSON backup/restore](https://github.com/louislam/uptime-kuma/wiki/Migration-From-v1-To-v2).
Its supported backup is the whole `/app/data` directory, and monitor writes use
an internal, version-unstable Socket.IO API after admin authentication.
Therefore `uptime-kuma-monitors.json` must **not** be described as a Kuma 2.x
export or as something the UI can import. A Kuma API key cannot replace the
admin login for monitor management.

The disposable proof of concept completed on 2026-08-14 against Uptime Kuma
2.5.0 with pinned community client
[`uptime-kuma-api2==2.5.0`](https://github.com/pbarone/uptime-kuma-api2):

- an MQTT monitor for `cabin/camera/available = online` was created by its
  unique declared name;
- a second reconciliation updated the same monitor ID and left exactly one
  monitor, proving no duplicate was created;
- retained `online` produced a green heartbeat, retained `offline` produced a
  red message-mismatch heartbeat, and restoring `online` returned it to green;
- the test used disposable credentials and tmpfs storage, and the container,
  broker, network, client environment, and credentials were removed afterward;
- no production URL, vault, monitor, notification, broker topic, or
  `/app/data` directory was read or changed.

The proof establishes that an idempotent reconciliation is technically
possible; it does **not** authorize the first production write. Status as of
2026-08-15: item 1 below is done (`vault_uptime_kuma_username` and
`vault_uptime_kuma_password` were added to `group_vars/cabin/vault.yml` in
commit `659d37c`) — this closes only the credential-presence prerequisite.
Items 2-5 remain open and unauthorized:

1. ~~Nate/Claude approve adding `vault_uptime_kuma_username` and
   `vault_uptime_kuma_password` to `group_vars/cabin/vault.yml`, exposed only
   through non-committed resolved Ansible variables. Never log either
   value.~~ Done, `659d37c`.
2. Confirm the live Kuma version and its Tailscale/internal-only URL before
   selecting and pinning the compatible client. The original
   `uptime-kuma-api`/Ansible collection is not a v2-safe substitute for the
   tested `uptime-kuma-api2` continuation.
3. Review a separate repo-owned declarative monitor specification and
   reconciliation task. The task must use `no_log: true`, match by a stable
   declared key, create/update without pruning by default, and fail closed on
   duplicate declared keys or version mismatch.
4. Stop Kuma and take a complete `/app/data` backup immediately before the
   first approved production reconciliation. Do not write SQLite directly.
5. If credentials or the reconciliation implementation are not approved, use
   the documented UI target list above. Manual creation remains the supported
   fallback; this PR does not pretend fresh-instance seeding already exists.

The repository's no-new-Python constraint also remains in force. The POC's
temporary client environment is evidence, not checked-in product code. A
seeder implementation needs its own review rather than silently introducing a
Python maintenance surface through this documentation change.
