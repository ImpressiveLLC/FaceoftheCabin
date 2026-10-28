# Context and communications registry

The definitive record of two things: **how the agents and Nate talk to each other**, and **where context is stored**, on every machine, in every Claude and Codex surface, and in GitHub. Owned by Cowork (Wingman skill, `.claude/skills/wingman/`). Canonical copy is this file in git. Any other copy (the packaged skill, a claude.ai Project doc, the Home Vault) is a mirror: regenerate it, never hand-edit it.

Last verified: 2026-10-27 16:26 CDT by Cowork. Legend for the **Check** column: `V` verified in a session on the stated date from the repo or the container, `R` reported by a repo doc or by Nate and not re-checked, `U` unknown, needs a look before anyone relies on it.

Rules that apply to every row:

1. **One fact, one home.** Git (`ImpressiveLLC/FaceoftheCabin`, branch `main`) is canonical. A fact that lives elsewhere must also be in git, or it does not exist for the next session.
2. **Mirrors flow one way** (git to mirror). The only reverse flow is a decision made where an agent cannot commit, which is handed back as text for `docs/DEFINITION_OF_DONE.md` "Next Session, Open Items".
3. **Never stored or mirrored anywhere in this registry's scope:** secrets, tokens, the Ansible Vault password, `.env` values, MAC addresses, IP addresses, sealed rows, private labels, raw model answers. Counts, hashes and ids only. Private evidence folders are described below by location class only: exact paths stay out of this public repository, and content never appears.
4. **Ephemeral places are not storage.** The Cowork container, `/tmp`, scratchpads and any chat transcript are reclaimed. Anything worth keeping is pushed to git before the session ends.

## 1. Communication patterns

All agent sessions post to GitHub as the one shared account `nhsmrekar`, so the author field says nothing. **Every comment starts with a speaker header** (`COWORK 15:06 CDT:`, `CODE 15:06 CDT:`, `CODEX ...`). Nate reads the Cowork chat and decides; he does not consume the agent threads directly, so anything another agent needs goes to GitHub, not chat.

| Channel | Between | Used for | Grammar and cadence | Where it is recorded |
|---|---|---|---|---|
| Cowork chat (this session; desktop, web or phone) | Nate and Cowork | Decisions, rulings, status, "go" for gated actions, purchases | Nate writes short; Cowork replies in caveman ULTRA, one line plus a one-word ask when a decision is needed. Phone push only for a decision or a critical breakdown, once per escalation | Chat transcript (ephemeral); anything durable is committed or becomes a board entry |
| Issue #244, the RTE board comment (id `6449313109`) | Cowork to Code and Codex | The single live board: goal, critical path, in flight, queue, Nate-only list | One comment edited in place by REST `PATCH` every cycle. Header carries the last update time | The edit history on GitHub; committed copy at `docs/governance/rte/schedule.md` at each change of plan |
| Issue #244, dated comments | All three agents | Handoffs, acks, verdicts, DONE notices, merge records | `ACTION <WHO> by <hh:mm CDT>:` handoff with link, ready-signal, post-back. Ack within 20 min. Verdict within 30 min (Codex) or 20 min (Cowork review). `DONE <item> <link>` names the next owner. Ids, counts and hashes only, never private text | GitHub comment thread |
| PR comments | Author, Codex, Cowork | Review verdicts, merge-order notes, merge records | Codex posts `CLEAN <head sha>` or `HOLD <reasons>`; a verdict binds to the head it names. Cowork posts a merge-order note before any overlap merge | GitHub PR thread |
| Heartbeat (scheduled task "Cowork RTE cycle") | Cowork to itself | Wake Cowork while any item is due | One-shot every 15 to 20 min, prompt states what to read, what to do per outcome, and to re-arm. Keep exactly one armed; delete duplicates | Scheduled-task list on the claude.ai account (`list_triggers`) |
| Code heartbeat | Code to itself | Poll #244 and its PRs | Every 20 min while a handoff is open (agreed 2026-10-27; the earlier 90 min broke the SLA) | Code session |
| Pasted handoff prompt | Nate to Code (M920q or Windows) | Start a Code session on the right machine | One self-contained prompt per `fotc-git-and-host-routing` "Handoff prompt pattern" | The prompt is a copy; the handoff itself lives on #244 |
| External reviews pasted by Nate | Reviewer to Cowork | Independent review (for example the ChatGPT Work review, received 2026-10-22) | Nate pastes; Cowork dispositions each finding on #244 with the received date | #244 and the governance decision it changed |
| Alerts to Nate's phone | Systems to Nate | Leak, freeze, disk, outage | ntfy and Home Assistant push. The backend ntfy topic is blank as of 2026-10-12 (W-51), so backend push does not reach a phone | `docs/NOTIFICATION_PATH.md`, `docs/CABIN_REACHABILITY.md` |
| Claim correspondence | Nate and the insurer, contractors | the insurance claim (insurer, claim number and contractor names stay out of git) | Gmail and the claim documents; never GitHub | Claude Project docs `claim/*`, the memory claim area, Gmail |

**There is no direct channel from Cowork to a Code or Codex session.** `ListAgents` shows none and `SendMessage` cannot reach another product. A nudge is a GitHub comment that names the item, or Nate. Silence is `unknown`, never `clear`.

**Silence ladder:** +20 min no ack, +40 min a named nudge on #244, +60 min on the critical path with no alternate owner, one phone push to Nate.

## 2. Where context lives

### 2.1 GitHub (canonical)

| What | Where | Check |
|---|---|---|
| Platform repo | `ImpressiveLLC/FaceoftheCabin`, branch `main` (previously `smrekarfamilia-sudo/FaceoftheCabin`; always use the org URL) | V 2026-10-27 |
| Other repos | `ImpressiveLLC/CabinAutomations` (Home Assistant YAML, leak/freeze/siren automations), `ImpressiveLLC/CabinSensorAutomationDetection` | R |
| Instruction files | `CLAUDE.md` (Claude), `AGENTS.md` (Codex, a derived mirror, see 4) | V |
| Skills | `.claude/skills/` (Claude sessions), `.agents/skills/` (Codex mirror) | V |
| Governance | `docs/governance/`: `backlog.md` (WSJF), `decisions/`, `use-cases.md`, `discrepancy-log.md`, `definition-of-done.md`, `iterations/`, `signoffs/`, `handovers/HO-*.md` (temporary), `evidence/`, `proposals/`, `packages/`, `verification/`, `rte/` | V |
| Live board | Issue #244 comment `6449313109`, committed copy `docs/governance/rte/schedule.md` | V |
| This registry | `docs/governance/rte/context-registry.md` | V |
| Ask, helpdesk, RAG | `docs/ai-assistant/` (`corpus/`, `rag/`, `training/`, `user-guide/`, `taxonomy-eval/`, `wsjf-backlog.md`). The context map is `docs/ai-assistant/rag/context-fixtures-r2.json`, mirrored into the backend classpath as `cabin-orchestration-platform/backend/src/main/resources/rag/context-fixtures-r2.json`; `ContextMapWiringTest` ties them together | V |
| Hardware and environmental nodes | `docs/hardware/doc27-*` | V |
| Operations | `docs/MAINTENANCE.md`, `docs/STORAGE_ARCHITECTURE.md`, `docs/REPLICATION.md`, `docs/CABIN_REACHABILITY.md`, `docs/NOTIFICATION_PATH.md`, `docs/DEFINITION_OF_DONE.md` | V |
| Deploy gates | `.github/workflows/`. Deploy to the M920q: `deploy-cabin-backend.yml` (runs `mvn test`, health-checks `/actuator/health`, rolls back on failure), `deploy-family-hub.yml`, `deploy-production-stack.yml`, `deploy-cabin-discovery.yml`. Scheduled: `rotate-secrets.yml` (monthly, 06:00 UTC on the 1st). Offline tests on pull requests: `intent-slot-tooling.yml`, `taxonomy-eval-tooling.yml`, `blink-relay-tests.yml`, `blinkbridge-tooling.yml`, `family-notes-backend.yml`, `kiosk-cabin-ui.yml`, `kiosk-family-hub.yml` | V |
| Host scripts | `scripts/host/` (storage prune and trim agent, backup-modular, w101-census) | V |
| Issue threads still in use | #244 (board), #246 (stranded local work audit), #207 (earlier cross-agent coordination) | V |
| Encrypted secrets | `ansible/group_vars/cabin/vault.yml` (Ansible Vault ciphertext in git; the password is never in git) | R |
| Dated purchase records (carts built for Nate, public product facts only, no order, address or account data) | `docs/governance/rte/purchases-<YYYY-MM-DD>.md`, linked from the backlog row it serves; procedure in `.claude/skills/wingman/references/carts-and-purchases.md` | planned |

### 2.2 Cowork container (cloud, this session type)

| What | Where | Check |
|---|---|---|
| Repo clone | `/home/claude/faceofthecabin` | V |
| Scratch | `/tmp/claude-0/-home-claude/<session>/scratchpad`, `/tmp/claude-0/pmo` (PMO build inputs), `/tmp/rte_board.md` (board source text) | V |
| Session transcript | `/root/.claude/projects/-home-claude/<session>.jsonl` | V |
| Synced account skills | `/root/.claude/skills/synced/<org>_<account>/` with `manifest.json` | V |
| Device bridge | Linked to "Claude Desktop (Windows)": connected folders only, a Linux VM, no LAN reach. The M920q is not reachable from here; host evidence arrives through Code | V |
| Lifetime | Reclaimed after inactivity. Nothing here is storage | V |

### 2.3 claude.ai account (follows Nate across surfaces)

| What | Where | Check |
|---|---|---|
| Memory | Project subtree `/projects/<project id>/` (`overview.md`, `preferences.md`, `learnings-and-gotchas.md`, and files under `areas/`) plus account files (`/profile.md`, `/preferences.md`, `/topics/*`, `/areas/*`). Written by the background memory pass; explicit "remember" requests are written by the session | V |
| Project docs ("Face of the Cabin") | 23 docs on 2026-10-27, mostly claim documents and older handovers. Policy: handovers live in git; Project docs are a mirror or claim material | V |
| Chat history | Searchable with `conversation_search`; summaries only for old chats | V |
| Account skills | `anthropic-skills:*` (caveman-mode, karpathy-principles, fotc-git-and-host-routing, my-shopping-values, skill-creator, built-in-browser, chrome-browser, deep-research and others) | V |
| Artifacts | PMO dashboard `https://claude.ai/artifact/FTSVRRJqxDn7jEKEmZNFoc` (rebuilt from `backlog.md`, see the Wingman skill) | V |
| Scheduled tasks | "Cowork RTE cycle" one-shots bound to the persistent Cowork session | V |
| Connectors | Gmail, Google Calendar, Google Drive, Figma, memory | V |

### 2.4 M920q at the cabin (the head for both locations)

Reach: only through Code sessions or Nate. The permission layer refuses remote host writes from automated sessions (2026-10-04); stage the change in git and let Nate run the install.

| What | Where | Check |
|---|---|---|
| Deploy worktree (what Ask mounts read-only at `/app/docs`) | `/home/nate/FaceoftheCabin-deploy` | R (#244, 2026-10-27) |
| Working clone for eval, training, helpdesk | `~/FaceoftheCabin` | R (`fotc-git-and-host-routing`) |
| Older checkout, **not** what Ask reads | `~/repos/FaceoftheCabin` (was at `e8b2c9c`) | R |
| Private evidence, C2 run 1 | Code's host, a private folder (exact path held by Code and Nate, not in git; ids, counts and hashes are posted, contents stay there) | R |
| Cold tier | `/storage` (HDD): backups, `archives/cabin_event/`, `cameras/frigate/`, `services/` (Home Assistant, Zigbee2MQTT, Frigate, Node-RED, mosquitto, uptime-kuma, blinkbridge), `.trim/` spool | R (`docs/STORAGE_ARCHITECTURE.md`) |
| Hot tier | `/` NVMe: Docker, Postgres volume `infra_cabin_pgdata`, OS, `/home` | R |
| Live secrets | The uncommitted `.env` beside the compose files in the deploy worktree (its template is `infra/.env.m920q.example`); the Ansible Vault password file in the host user's home | R, never read or copied |
| Host logs and config | `/var/log/cabin-storage-prune.log`, `/etc/cabin-storage-prune.conf`, `/etc/cabin-storage-trim-agent.env` | R |
| Claude Code state | `~/.claude/` (transcripts, user skills). Ask: Code, on the next M920q session, report directory names only | U |
| OS release | `CLAUDE.md`, `docs/REPLICATION.md` and `docs/ontology.yaml` say Ubuntu 24.04. Nate's notes say 26.04, which no repo document states. Ask: Nate or Code, `lsb_release -a` settles it before anything depends on the release | R |

### 2.5 Windows PC `ILikeTheLights` (Minneapolis)

| What | Where | Check |
|---|---|---|
| Short-path worktrees for repair and review | `C:\Users\nhsmr\wtNNN` | R (`cabin-peer-agent-collaboration`) |
| Private grading folder, C1 | Nate's Windows PC, under his Documents (exact path held by Nate, not in git) | R |
| Patch handoffs | `Downloads` (files for `git am`) | R |
| Older dev checkout path in `CLAUDE.md` | `H:\My Drive\cabin-orchestration-platform-expanded\...`, which is inside Google Drive sync. A git working tree inside a cloud-sync folder can corrupt `.git`; confirm it is no longer in use. `docs/EXECUTION_PLAN_2026-07-30.md` calls `C:\dev\FaceoftheCabin` "your other local clone". Ask: Nate, is the `H:` path still a live checkout (yes or no)? | U |
| Claude Code state | `%USERPROFILE%\.claude` (transcripts, user skills), the documented default; not inspected from here | R |
| Claude Desktop state (Cowork workspace, built-in browser profile with its sign-ins and carts, locally stored scheduled tasks) | The app's own data folder; not inspected from here. Ask: Nate, only if a session ever needs to read it | U |

### 2.6 Phones and Home

| What | Where | Check |
|---|---|---|
| Galaxy S23+ | Home Assistant Companion app (presence and heartbeat source), Tailscale, Claude app; the planned authenticator and passkey device | R |
| Termux phone (Xperia, collector role) | `~/.z2m/configuration.yaml` (Zigbee2MQTT), network-scan agent, Blink relay agent; offline from 2026-09-19 to 2026-10-16, revived under W-82 | R |
| Home Zigbee coordinator | SMLIGHT SLZB-MR5U, network attached | R |
| `bluefin` | A Linux machine on the tailnet, offline since 2026-09-22; Nate has not said what it is (`docs/CABIN_REACHABILITY.md`). The training Dockerfile guesses it may have a GPU, unconfirmed. Ask: Nate, what is it and should it stay on the tailnet? | U |
| Home Vault node | Planned encrypted, append-only backup node on the Home Linksys network (W-120; see section 3). Design, sizing, checks and rejected options: `.claude/skills/wingman/references/vault-and-keys.md` Part A | planned |
| Security keys and recovery material | Custody classes K0 to K4, roles (primary on the keychain, secondary at the home desk, backup away from both and never at the cabin) and the per-service plan are in `.claude/skills/wingman/references/vault-and-keys.md`. The keys, PINs, recovery codes and password values are never in git, chat or any agent-visible place | rule |

### 2.7 Codex side

| What | Where | Check |
|---|---|---|
| Instructions | `AGENTS.md`, `.agents/skills/` in each Codex checkout | V (generated by `fed-regen.sh`, see 3) |
| Work | Codex cloud tasks (task ids appear in #244 comments) and local checkouts | R |
| Review output | PR comments `CLEAN <sha>` or `HOLD`, never private text | V |

## 3. Federation map

| Source of truth | Mirror | Direction | How to verify | Known state |
|---|---|---|---|---|
| `main` | M920q deploy worktree | git to host, by deploy workflows and by Nate | `git -C /home/nate/FaceoftheCabin-deploy rev-parse HEAD` equals `origin/main`, and the worktree is clean. **A docs-only or scripts-only merge does not update the worktree** (`docs/STORAGE_ARCHITECTURE.md`, `HO-2026-10-18`): the host needs `git pull --ff-only origin main` there. Ask reads its corpus from that worktree, so a merged corpus change does not reach Ask until the pull | R |
| `docs/ai-assistant/rag/context-fixtures-r2.json` | Backend classpath copy and the live Ask context | git to image | sha256 of both files equal; `ContextMapWiringTest`; attestation (deployed HEAD, map hash, image id) | Equal at attestation 2026-10-27 22:26 CDT (map `05d8dc3a`, HEAD `af214e2`, after #300; it was `41056722` at `2803b3d`) |
| `CLAUDE.md` | `AGENTS.md` | derived, regenerate with `fed-regen.sh` | `.claude/skills/wingman/scripts/fed-check.sh` | Generated, three changes from the source (W-119); the check WARNs on a stale copy |
| `.claude/skills/` | `.agents/skills/` | derived, regenerate with `fed-regen.sh` | same script | Byte copy, modes included (W-119); the check FAILs on drift |
| `docs/governance/rte/context-registry.md` | Packaged Wingman skill (`references/05-context-registry.md`), optional Project doc | git to mirror, stamped with source commit | `fed-check.sh --registry` | new |
| `main` (git bundle), M920q configs, Postgres dumps, BI telemetry archives, Ansible Vault ciphertext | Home Vault node (client-side-encrypted restic repository, append-only), offline NVMe copy (W-120). Private evidence folders only if Nate rules them in scope | the host pushes to the vault; the vault cannot be reached for deletion from the cabin | restic `check` and a one-file restore test, weekly | planned |
| Decisions made in claude.ai web at the cabin | git (`DEFINITION_OF_DONE.md` Next Session list) | hand-back by text | Nate pastes, or Cowork commits | rule only |

## 4. Findings recorded 2026-10-27 (F1 and F2 merged in #303, the rest open)

| # | Finding | Evidence | Proposed repair |
|---|---|---|---|
| F1 | `AGENTS.md` is a stale, partly corrupted copy of `CLAUDE.md`. It tells Codex to load `.Codex/skills/...` (that path does not exist), reads "(Codex, Codex, Cowork)", turns facts about Claude web into facts about Codex web, and lacks the 2026-10-26 priority paragraph and the unit rule. 11 lines differ even after its own substitution | `fed-check.sh` 2026-10-27 | Regenerate from `CLAUDE.md`, changing only the title line and the one "gives Claude Code full context" sentence; keep `fed-check.sh` as the drift check (W-119). **Merged 2026-10-27** (#303, `b74169c`, on Nate's waiver of the Codex verdict; `fed-regen.sh`; the generated-file header is the one addition beyond those two changes). Open, blocking nothing: Codex's read of the merged result and a run on a real Windows checkout |
| F2 | `.agents/skills/cabin-peer-agent-collaboration/` and `.agents/skills/fotc-peer-merge-sequencing/` differ from `.claude/skills/` by agent-name substitution and carry the same corrupted path | `fed-check.sh` 2026-10-27 | Byte copy of `.claude/skills/` (W-119). **Merged 2026-10-27** (#303, `b74169c`; `fed-regen.sh`) |
| F3 | Two heartbeat tasks were armed for the same minute | `list_triggers` 2026-10-27 15:11 CDT | Keep exactly one; delete extras at each re-arm |
| F4 | The Windows dev path in `CLAUDE.md` sits under Google Drive sync | `CLAUDE.md` "Dev setup" | Confirm and remove from the doc, or move the clone (owner: Nate) |
| F5 | `docs/ai-assistant/` hand-built panels in the PMO dashboard were stale | artifact dated 2026-10-26 | `pmo-build.mjs` regenerates from `backlog.md`, `schedule.md` and the open PR list |

## 5. Session checklists

**Start:** current time; read #244 board and comments since the last stamp; open PRs; the memory snapshot; `CLAUDE.md`; this registry; confirm the clone is fresh (`ctx-audit.sh`). **End:** board edited, committed copy updated if the plan changed, backlog row for any state change, nothing unpushed, hand-back text for any decision made where git was unreachable.

## 6. Change log

| Date | Change |
|---|---|
| 2026-10-27 | First version (Cowork, Wingman skill). Findings F1 to F5 |
| 2026-10-27 16:26 CDT | Added the vault-and-keys and carts-and-purchases references (custody classes, Home Vault design, security-key plan, cart protocol) and rows for security keys and purchase records. Project docs count 22 to 23. OS release U to R (repo says 24.04, Nate's notes say 26.04, one-line ask recorded). One-line asks added to the remaining U rows. Packaged-skill mirror unchanged |
| 2026-10-27 22:31 CDT | Refreshed after #300 and #303: the map row is now `05d8dc3a` at HEAD `af214e2` (Code's `DEPLOY OK`, #244 comment 6459878936); F1 and F2 marked merged (#303, `b74169c`), with the Codex read and a real Windows run still open. The last-verified stamp is unchanged: only these rows were refreshed |
