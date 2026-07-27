# Smrekar Platform

**ImpressiveLLC / FaceoftheCabin** · unicornpingpong.com _(in progress)_

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
| **Family Hub** | `hub.unicornpingpong.com` | Ambient display: clock, parenting schedule, calendar, photos, chores, rewards |
| **FaceOfTheCabin** | `cabin.unicornpingpong.com` | Cabin automation: cameras, sensors, alerting, presence detection |
| **Platform API** | `api.unicornpingpong.com` | Shared services: identity, ontology, event bus, audit trail |

---

## Repository Map

| Repo | Org | Contents |
|------|-----|----------|
| [FaceoftheCabin](https://github.com/ImpressiveLLC/FaceoftheCabin) | ImpressiveLLC | cabin-ui (React), cabin-backend (Spring Boot), family-hub (static HTML), infra (Docker Compose) |
| [CabinAutomations](https://github.com/ImpressiveLLC/CabinAutomations) | ImpressiveLLC | Zigbee pairing, HA config, Node-RED flows |
| [smrekar-platform](https://github.com/ImpressiveLLC/smrekar-platform) | ImpressiveLLC | Platform monorepo — identity, ontology API, shared UI shell ⚠️ _not yet pushed_ |

**GitHub accounts:** `nhsmrekar` (primary dev) · `smrekarfamilia-sudo` (family org) · `ImpressiveLLC` (shared/platform)

---

## Infrastructure Quick-Reference

| Item | Value |
|------|-------|
| Primary server | Lenovo M920q · Ubuntu · Docker Compose at `/storage/containers/compose/cabin/` |
| Tailscale IP | `100.77.44.113` |
| Public access | Cloudflare Tunnel → `unicornpingpong.com` _(tunnel config in progress)_ |
| Private mesh | Tailscale — cabin ↔ home, admin/SSH |
| Domain registrar | Porkbun — `unicornpingpong.com` |
| DNS | Cloudflare free tier |
| Monitoring | Uptime Kuma + Homepage on M920q |
| Google OAuth owner | `nhsmrekar@gmail.com` |
| Calendar / Photos | `smrekarfamilia@gmail.com` |
| Zigbee coordinator | `/dev/ttyACM0` · adapter: ember · 14 devices paired |
| Parenting schedule anchor | March 13 2026 · 14-day cycle · kids-home-days: [0,1,4,5,8,9,12,13] |

---

## Documentation Index

| Document | Location | Contents |
|----------|----------|----------|
| **Platform Roadmap** | [`ROADMAP.md`](ROADMAP.md) | Strategic brief, architecture, ontology design, event pipeline, priority task list |
| **Product Notes** | [`docs/PRODUCT_NOTES.md`](docs/PRODUCT_NOTES.md) | Dated design decisions, persona research, four-role architecture review |
| **Ontology Contract** | [`docs/ontology.yaml`](docs/ontology.yaml) | Canonical entity definitions, device registry, naming contract |
| **Platform README** | [`cabin-orchestration-platform/README.md`](cabin-orchestration-platform/README.md) | Technical architecture, backend/UI, Docker Compose reference |

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
