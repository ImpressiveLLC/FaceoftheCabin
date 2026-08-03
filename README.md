# Smrekar Platform

**ImpressiveLLC / FaceoftheCabin** · unicornpingpong.com _(live — hub/cabin/api all public)_

A family-scale, self-hosted platform unifying cabin automation and family coordination under a shared event bus, identity layer, and living ontology. Deployed on a Lenovo M920q at the cabin, exposed via Cloudflare Tunnel, connected privately via Tailscale.

---

## Northstar Goals

1. **Unified family experience** across cabin display, home machine, and phone via single domain (`unicornpingpong.com`)
2. **See → Think → Act** — every element and attribute supports this user interaction model at every UI surface
3. **Living ontology as primary differentiator** — every data point is findable, traceable, and actionable across all platform verticals
4. **FAIR data throughout** — Findable, Accessible, Interoperable, Reusable — enabling AI/RAG/LLM at any layer
5. **Event-driven architecture** — camera → detection → normalized event → automation → action, with full audit trail
6. **Self-improving discovery** — the platform actively monitors for new integrations, APIs, and alternatives for every cataloged device/service, and notifies the owner proactively

---

## Products

| Product | Subdomain | Description |
|---------|-----------|-------------|
| **Family Hub** | `hub.unicornpingpong.com` | Ambient display: clock, parenting schedule, calendar, photos, chores, rewards, family notepad |
| **FaceOfTheCabin** | `cabin.unicornpingpong.com` | Cabin automation: cameras, sensors, alerting, presence detection |
| **Platform API** | `api.unicornpingpong.com` | Shared services: identity, ontology, event bus, audit trail |

---

## ⚠️ Security note: camera activity on Family Hub

Family Hub's Overview page shows a live camera-activity widget from
FaceOfTheCabin — by default it shows **which camera, what was detected,
and when**, refreshed every 20 seconds, visible to **anyone looking at the
screen, no sign-in required**. This is genuinely useful (at a glance, is
anyone at the cabin right now) but it is also, unavoidably, a presence/
absence indicator — the same information that tells you "someone's home"
also tells anyone else looking that "no one's home right now."

**This is configurable, not fixed.** Settings → Cabin Camera Activity has
three levels:
- **Full detail** (default) — camera, object, timestamp.
- **Activity only** — just "recent activity: yes/no," no camera name, no
  object, no precise time.
- **Off** — hides the widget entirely.

If this display sits somewhere more people can casually glance at than
just your own family (a shared space, a device you don't fully control
who sees), turn it down. The same warning is shown directly next to the
widget itself, with a link straight to this setting.

**Video viewing (built 2026-08-02, see `ROADMAP.md` Phase 6) is a
separate, more sensitive surface from the widget above.** cabin-ui's
Camera Events panel — authenticated, Google sign-in required, **not**
part of this public Family Hub widget — now shows real snapshots and
event clips, plus a live per-camera view. This is a meaningfully bigger
exposure than the metadata-only widget above: an image or video frame is
categorically more sensitive than "motion detected at 3:14pm." Concretely:

- **Event clips**: 15 seconds before to 60 seconds after each detected
  event, kept 10 days.
- **Continuous (24/7) recording**: every camera's full feed, kept 5 days
  — a deliberately short retention window given one camera's recording
  stream is full 4K resolution (storage cost scales fast; extended only
  after real usage is measured, not a guessed number).
- **Live view**: on-demand streaming, not itself separately recorded —
  covered by continuous recording above.

None of this is reachable from the public, no-sign-in Family Hub widget
described earlier in this note — it lives entirely behind cabin-ui's own
Google sign-in (`cabin.unicornpingpong.com`), gated server-side on every
request, not just hidden client-side. If you ever add another signed-in
user to cabin-ui, they get this same video access — there's no separate,
finer-grained permission tier for video vs. the rest of cabin-ui today.

---

## Repository Map

| Repo | Org | Contents |
|------|-----|----------|
| [FaceoftheCabin](https://github.com/ImpressiveLLC/FaceoftheCabin) | ImpressiveLLC | cabin-ui (React), cabin-backend (Spring Boot), family-hub (static HTML), infra (Docker Compose) |
| [CabinAutomations](https://github.com/ImpressiveLLC/CabinAutomations) | ImpressiveLLC | Zigbee pairing, HA config, Node-RED flows |
**GitHub accounts:** `nhsmrekar` (primary dev) · `smrekarfamilia-sudo` (family org) · `ImpressiveLLC` (shared/platform)

---

## Infrastructure Quick-Reference

| Item | Value |
|------|-------|
| Primary server | Lenovo M920q · Ubuntu · Docker Compose at `/storage/containers/compose/cabin/` |
| Tailscale IP | `100.77.44.113` |
| Public access | Cloudflare Tunnel → `unicornpingpong.com` — live, all three subdomains |
| Private mesh | Tailscale — cabin ↔ home, admin/SSH |
| Domains | `unicornpingpong.com` — platform (hub/cabin/api subdomains) · `impressive.llc` — org domain |
| Domain registrar | Porkbun |
| DNS | Cloudflare free tier |
| Monitoring | Uptime Kuma + Homepage on M920q |
| Google OAuth owner | `nhsmrekar@gmail.com` |
| Calendar / Photos | `smrekarfamilia@gmail.com` |
| Zigbee coordinator | `/dev/ttyACM0` · adapter: ember · 14 devices paired |
| Parenting schedule anchor | March 13 2026 · 14-day cycle · kids-home-days: [0,1,4,5,8,9,12,13] |
| Secrets | Ansible Vault (`ansible/group_vars/*/vault.yml`) — see `ansible/README.md`'s Secrets section, not hand-edited `.env` |

---

## Documentation Index

| Document | Location | Contents |
|----------|----------|----------|
| **Platform Roadmap** | [`ROADMAP.md`](ROADMAP.md) | Strategic brief, architecture, ontology design, event pipeline, priority task list |
| **Product Notes** | [`docs/PRODUCT_NOTES.md`](docs/PRODUCT_NOTES.md) | Dated design decisions, persona research, four-role architecture review |
| **Ontology Contract** | [`docs/ontology.yaml`](docs/ontology.yaml) | Canonical entity definitions, device registry, naming contract |
| **Platform README** | [`cabin-orchestration-platform/README.md`](cabin-orchestration-platform/README.md) | Technical architecture, backend/UI, Docker Compose reference |
| **QA / Testing** | [`docs/QA.md`](docs/QA.md) | Per-feature test coverage, how to run automated checks, manual QA checklists |
| **Definition of Done** | [`docs/DEFINITION_OF_DONE.md`](docs/DEFINITION_OF_DONE.md) | Per-session (not per-app) exit checklist, prioritized next-session plan |
| **Replicating This Template** | [`docs/REPLICATION.md`](docs/REPLICATION.md) | Standing up a fully independent instance — own accounts, domain, host, repo |

---

## Quick Start — M920q Deploy

```bash
# SSH in via Tailscale
ssh nate@100.77.44.113

# Deploy / rebuild a single service
cd ~/FaceoftheCabin && git pull
docker compose \
  -f cabin-orchestration-platform/infra/docker-compose.yml \
  -f cabin-orchestration-platform/infra/docker-compose.m920q.yml \
  up -d --build <service-name>
```

Services: `family-hub` · `cabin-ui` · `cabin-backend` · `cabin-grafana` · `postgres` · `kafka`
