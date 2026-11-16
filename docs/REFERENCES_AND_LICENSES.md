# References and licences: master register

Owner: Cowork. Created Sun 15 Nov 2026 at Nate's request ("site references and licenses in master docs ... alleviate re-researching later ... answer questions and make marketing of the platform available"). Git is canonical. A mirror sits in the Face of the Cabin Project docs (`reference/references-and-licenses.md`); never hand-edit the mirror. This is a working register, not legal advice. Anything that could create a legal duty goes to Nate, and to counsel if he chooses.

## 1. How to use this document

1. **Check here before re-researching** a source, a licence or a claim about a component. If the row exists, use it. If it is labelled unverified, verify it and change the label.
2. **Add a row whenever a new third-party source, dataset, model, library or font is used.** One row, one PR, same PR as the work that uses it.
3. **Labels.** `verified` = read at the stated date from the stated page. `carried` = copied from another repo document that holds the evidence (its path is given). `unverified` = general knowledge, not read; do not state it in public material until verified.
4. **Never copy third-party text.** Forum, Reddit, Lemmy and blog content is summarised in our own words with a thread id or URL. Datasets and code are used only under their stated licence. No hosted-model output is used for training until the generator's terms are checked (see the dataset register).
5. **No claim without a record.** Public statements about accuracy, safety or readiness must point to a git record. As of this date the Ask gates are not met (A1 `NOT_MEASURED`, `release_eligible: false`, weight training `NOT_RUN`).

## 2. Platform stack: components and licences

Read 2026-11-15 from each project's GitHub page (project licence only; bundled third-party parts and plug-ins carry their own).

| Component | Role here | Licence | Label | Note |
|---|---|---|---|---|
| Home Assistant core | Device integration and the safety automations (leak, freeze, siren) | Apache-2.0 | verified 2026-11-15, github.com/home-assistant/core | Product name and logo terms not checked |
| Zigbee2MQTT | Zigbee bridge | GPL-3.0 | verified 2026-11-15, github.com/Koenkk/zigbee2mqtt | Copyleft: any redistribution of the software, modified or not, carries the GPL notice and licence text and, for binaries, corresponding-source obligations (LICENSE sections 4 to 6). We run it and do not redistribute it |
| Frigate | Camera detection and recording | MIT | verified 2026-11-15, github.com/blakeblackshear/frigate | The "Frigate" name, "Frigate NVR" brand and logo are excluded from the MIT licence and governed by a trademark policy: do not use them as our product name or imply endorsement |
| Grafana | Dashboards | AGPL-3.0-only (page also says Apache-2.0 exceptions are listed in its LICENSING.md) | verified 2026-11-15, github.com/grafana/grafana | AGPL has a network-use clause: if we ever modify Grafana and let others use it over a network, the modified source must be offered. We run it unmodified |
| Homepage (launcher) | Link dashboard on the M920q | GPL-3.0 | verified 2026-11-15, github.com/gethomepage/homepage | GPL-3.0 redistribution duties apply to any copy we hand to others, modified or not; we only run it |
| Ollama | Local model server for Ask | MIT | verified 2026-11-15, github.com/ollama/ollama | Model weights have their own licence (next row) |
| Llama 3.2 (Meta) | The Ask intent/slot base model family | Llama 3.2 Community License | verified 2026-11-15 (summary), then checked against the licence text by the Codex review of this PR 22:17 CST; re-read the primary text before any public statement | See section 4: attribution ("Built with Llama"), a naming rule for models trained or improved using it that we distribute, an Acceptable Use Policy, a 700M monthly-active-user clause, no general trademark rights |
| TimescaleDB | Telemetry store | Dual licence files: Apache-2.0 plus a second licence the page could not identify | partly verified 2026-11-15, github.com/timescale/timescaledb | Which features fall under which licence is unconfirmed; read the second licence file before any claim |
| Mosquitto | MQTT broker | unverified (believed EPL-2.0 or EDL-1.0) | unverified | |
| Node-RED | Pre-existing automation flows | unverified (believed Apache-2.0) | unverified | |
| Apache Kafka | Event pipeline | unverified (believed Apache-2.0) | unverified | |
| PostgreSQL | Database | unverified (believed the PostgreSQL licence) | unverified | |
| Spring Boot | Backend framework | unverified (believed Apache-2.0) | unverified | |
| React, React DOM, Vite, Vite React plugin | UI | unverified (believed MIT) | unverified | `ui/package.json` pins these to `latest`, so a licence change would not be noticed: see open item 2 |
| lucide-react | Icons | unverified (believed ISC) | unverified | |
| Tailscale client | Remote access | unverified (client believed BSD-3; service terms separate) | unverified | |
| cloudflared | Public tunnel | unverified (believed Apache-2.0) | unverified | |
| Uptime Kuma, MediaMTX | Monitoring, stream relay | unverified (believed MIT) | unverified | |
| Blinkbridge | Blink camera bridge | not recorded | unverified | Find the upstream and its licence |
| Crackman font | Retro theme display text | CC0 (Raymond Larabie), `license.txt` ships with the font | carried from `docs/retro-theme-fonts.md` | |
| Baumans and Oxanium fonts | Retro theme general text | SIL OFL 1.1, pinned to Google Fonts commit b346dc3e | carried from `docs/retro-theme-fonts.md` | OFL licence files ship with each deployed copy; no renaming or modification |

## 3. Research, prior art and datasets

The evidence and full wording live in the files named here; this table is the index so nobody re-searches.

| Source group | Licence or access | Where the detail is |
|---|---|---|
| Public QA, RAG and abstention sets and tools (UAEval4RAG Apache-2.0, ragas Apache-2.0, RAGBench cc-by-4.0, Doc2Dial cc-by-3.0, TechQA terms unclear, AbstentionBench family and community Q&A unverified) | As stated per row; subset terms and S6 to S8 unverified; no consumption clearance | `docs/ai-assistant/proposals/ask-public-qa-dataset-register.md` (carried; Codex confirmed S1, S2 and S5 licences) |
| Home Assistant request data and small-model papers (Home-Assistant-Requests-V2 MIT per the plan; arXiv 2502.12923; arXiv 2603.16207; Snips CC0; CLINC150 CC BY; HWU64 CC BY-SA share-alike, Nate decides before reuse) | As stated | `docs/ai-assistant/proposals/ask-intent-prompt-prior-art-and-improvement-plan.md` sections 6 and 9 (carried) |
| Community threads (HA forum, r/LocalLLaMA 1g1fole, r/homeassistant 1migi2p, Lemmy c/selfhosted 20 Oct) | Public pages, no licence stated, read through mirrors for the Reddit and Lemmy items; summarised in our own words, one anecdote each | same plan, section 6 and section 8 |
| Literature pass 2026-11-15: arXiv 2510.05152 (example delimiter), arXiv 2403.17536 (ILLUMINER), a blog on few-shot examples as precedent, a vendor blog on JSON from small models, the acon96/home-llm README | Papers and web pages read by URL; repository licence of home-llm not checked | Pending dependency: the linked plan's section 7a, these paper identifiers and the home-llm reference are added by PR #461, which must merge **before** this PR. Until it does, this row's target does not exist on main and must not be read as verified |

## 4. Obligations to remember (not legal advice)

- **Llama 3.2.** Per the Codex review of the primary text (Llama 3.2 LICENSE section 1.b.i): the conditions are triggered by **distributing or making available** the Llama materials or any product or service that includes them, not only by handing over a copy. Two kinds of duty follow and should not be merged: (a) **copy duties**, when a copy of the materials or a derivative is given to someone: provide the licence and the attribution notice; (b) **use and presentation duties**: show "Built with Llama" prominently with the product or service (and where documentation presents it), follow Meta's Acceptable Use Policy, and name any model we train or improve using the Llama materials or their outputs and then distribute **or make available** starting with "Llama". Whether our private, single-site deployment counts as making available is unresolved; Nate decides whether to ask counsel before any public release or white paper. Re-read the primary text before quoting any of this publicly.
- **AGPL (Grafana).** Fine for unmodified use. Revisit if we patch Grafana or host it for outside users.
- **GPL (Zigbee2MQTT, Homepage).** Running unmodified copies on our own machines is fine. Any redistribution to others, modified or not, brings the GPL notice, licence text and, for binaries, corresponding-source duties (Zigbee2MQTT LICENSE sections 4 to 6). A template or image bundle that includes them is redistribution.
- **Frigate trademark.** Describe it as the camera software we integrate with. Do not use its name or logo as ours or imply endorsement.
- **Third-party names generally** (Home Assistant, Tailscale, Cloudflare, Meta, Anthropic): refer to them as the tools we use; no endorsement claims. Trademark terms for names other than Frigate are not yet checked.
- **Forum and Reddit material.** Paraphrase, cite the thread, never reproduce posts.

## 5. Instruction guide and white paper opportunities

Each topic below lists the evidence we already hold and what is missing. Nothing here is a published claim.

| Topic | Audience | Evidence in git | Missing before publishing |
|---|---|---|---|
| Governed local intent routing: a small model classifies, the server resolves and confirms, nothing executes from model text | Home-lab builders, HA community | Express-need spec, resolver rules, proposal-only route (no executor), D27 | A measured result: gates are `NOT_MEASURED` |
| What prompt-only tuning of a 3B model did and did not do (E1, E2, E1b, E2b: repeatable fixture trades, no gate met, three identical runs each) | ML-curious practitioners | Ledger in the prompt plan, round files under `training/intent-slot/rounds/`, PRs #455, #460 | The contrast set (E3) and the ablation; a license check on any dataset named |
| Ontology-driven device adds (catalog generated from the ontology, unmatched devices flagged, never auto-enabled) | Smart-home integrators | D29 (recorded, not ratified), slices S0 to S8 | Ratification and a working first slice |
| Safety automations stay in Home Assistant; the platform is a management layer on top | Cabin and second-home owners | CLAUDE.md, D23, D24, deploy rollback record | A short architecture figure and a redaction pass (no addresses, hostnames or tokens) |
| Cabin sensor and relay build as a how-to | DIY builders | Doc 27 plans and device inventory | Purchase and assembly records; vendor licences |

Rules for any public piece: nothing from the insurance claim, no secrets, hostnames, IPs or credentials, no sealed set content, and every number traces to a record.

## 6. Open items

1. **Licence scan (Code).** From inside the real environment: read `docker inspect` image labels on the M920q, run a Node licence checker over `ui` and `family-hub`, and run an offline Maven licence report for `backend`. Post counts and any non-permissive licence on #244; update section 2 rows from `unverified` to `verified` with the date.
2. **Pin `ui/package.json`.** Five dependencies use `latest`. Pin exact versions so builds repeat and licence changes are visible. Small docs-and-config PR for Code.
3. **TimescaleDB.** Read the second licence file and record which features are Apache-2.0 and which are not.
4. **Blinkbridge** upstream and licence; **home-llm** repository licence; **Home Assistant, Tailscale and Cloudflare** trademark terms.
5. **Llama distribution question.** Nate decides whether to take the question to counsel before any public release.

## 7. Change log

- 2026-11-15 (Cowork): created. Verified eight project licences and the Llama 3.2 terms; carried fonts, dataset register and prior-art rows; listed open items.
