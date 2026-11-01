# Autonomous delivery bot ("the factory") — reuse survey and design

Date: 2026-10-31. Status: proposed; documentation only. This design was written before the spike; nothing in this repo builds or installs it. The [delegation doc](2026-10-31-ask-delivery-raid-and-delegation.md) reports (session-reported by Code, not verified by Cowork) that a spike runner is now running on a Linux machine and has produced a first PR; where the two files differ, treat the delegation doc as later and the design as the plan. Requested by Nate in chat: a bot on his Windows PC,
movable to other home-network machines, that carries the remaining plan through design, architecture, ontology, taxonomy, data
orchestration, selections, PMO and review, with a CEO/stakeholder proxy, and minimal interaction once built. Target: **95% direct reuse of
existing open-source projects, 5% customisation.**

## Decisions already made (Nate, 2026-10-31)

- Models: Claude (headless CLI) for design, review and code, plus local Ollama on Linux for cheap routine steps.
- Authority: **tiered** (below). Safety, auth, credentials, host changes, money and deletes always stop for Nate.
- Placement: decide after this design (runner and vault may be split across machines). **Spike runner: a Linux machine, not WSL2 and not the M920q** (Nate, 2026-10-31); which one is still to be named.

## Reuse survey (what exists)

Verified means read from the project's own page in this session; unverified means only an aggregator list entry. At the time of writing nothing was installed or run.

| Project | License | What it gives | Gap against this plan | Status |
|---|---|---|---|---|
| [chipping-orchestrator](https://pypi.org/project/chipping-orchestrator/) 0.14.0 (2026-10-22) | Apache-2.0 | Turns GitHub issues into reviewed PRs: decomposer, implementer (own git worktree), reviewer (default `claude` implements, `codex` reviews), caps on rounds and retries, systemd user service, **all workflow state in GitHub issue labels** (no database to move) | Linux and Python 3.12+ only; GitHub only; never merges (stops at `in_review`); agents run with sandbox checks off, so the host account is the boundary; no PMO, ontology or CEO-proxy roles. Cautions: single maintainer, no provenance attestation, and breaking changes can land on main between tagged releases, so pin the exact version | verified |
| [headless-claude-automation-template](https://github.com/cmAIdx/headless-claude-automation-template) | MIT | Dev, review and fix agents as GitHub Actions workflows, turn limits, 5-round fix cap | Needs an Anthropic API key and GitHub Actions minutes; human merge; 17 stars | not confirmed (maturity and claims unchecked) |
| [Crewly](https://github.com/stevehuang0115/crewly) | MIT badge only | Role teams (developer, QA, PM, orchestrator) of Claude Code, Codex, Gemini CLI, OpenCode agents, shared memory, web dashboard | Licence and maturity not confirmed; heavier runtime | partly verified |
| Paperclip, agent-swarm, Rovai, Tale, 5dive, Delegatus, Open Session, no_human, Orbi, cyrus | various | Org-chart and approval-gate style orchestrators; `no_human` is MIT, issue to reviewed PR with human merge | Only list entries read; maturity, licence and activity unknown | unverified |
| OpenHands, MetaGPT, CrewAI, LangGraph, AutoGen | mostly permissive | Sandboxed coding agent (OpenHands); role pipelines (MetaGPT); build-your-own graphs with human checkpoints (LangGraph, CrewAI) | Frameworks, not a ready delivery team: reuse would be well under 95% | list-level |

Finding: nothing found does the whole job. The closest ready piece for the implement and review loop is chipping-orchestrator, because its
state lives in GitHub (which this project already treats as the single source of truth), it is a systemd service (machine-agnostic), and it
already separates implementer and reviewer models. Nothing found merges autonomously by design; that is the part to customise, and it
should be small and deliberately conservative.

## Proposed architecture

```
 GitHub (issues, labels, PRs, branch protection)   <- the vault: board, queue, decisions, history (already how we work)
        ^                    ^
        |                    |
 [runner container, any Linux box]          [vault repo `factory-vault` (git)]
   chipping-orchestrator (reused)             role prompts, tier policy, memory notes, digests, logs
   + role pack (our skills, small)            (plain files; move by cloning)
   + merge actor (small, tier-gated)
   + Ollama for triage, polling, summaries
```

- **Runner** is stateless: a container (or systemd user service) with the Claude CLI logged in, Git, Python 3.12+ and a fine-grained GitHub
  token. Moving it means starting the same container on another machine against the same GitHub repos and vault.
- **State** is GitHub plus a plain git vault, so "execute on one machine, store and oversee on another" is just which machine runs the
  container and which holds the clone; no database to migrate.
- **Windows:** the reused core is Linux-only. The Windows PC would run it in WSL2, or the first runner goes on a Linux machine. Not the
  production M920q: it is the cabin head, and agents here run unsandboxed.

## Roles (prompts and checklists, not new software)

Architect, Ontologist and Taxonomist, Data-orchestration, Selections (spec-first purchasing), PMO (WSJF board, handoffs, heartbeats), Implementer,
Independent Reviewer (a different CLI or model from the implementer), Safety Gate, and **CEO-proxy** (applies Nate's recorded rules to the tier
table, writes a short daily digest, escalates). Most of this text already exists in this repo: the eight project skills, the governance
folder, the RTE rules and the Cowork and Codex role definitions. Reuse them as the role pack.

## Tiered authority (proposed; GitHub enforcement is not in place today)

| Tier | Examples | Who merges |
|---|---|---|
| A: bot may merge after an independent CLEAN on the exact head | **until a deploy policy is ratified: docs, tests and backlog rows only.** Ask content and routing, UI polish and anything under `backend/**`, `ui/**` or `infra/**` trigger host deploys and stay out of tier A | merge actor |
| B: bot opens and reviews, **Nate approves** | auth and roles, credential paths, host or deploy config, anything touching locks, valves, sirens, `infra/`, secrets, deletes, money | Nate |
| C: never by the bot | secrets, host changes over SSH, destructive data actions, paid purchases | Nate only |

Proposed enforcement (**not in place**: this repo has no CODEOWNERS, branch protection or rulesets today, and the bot and Nate would share one GitHub
identity, so CODEOWNERS alone could not tell them apart; a separate bot account or fork-based token would be needed first): path-based required
reviewers and branch protection, so a tier-B change cannot merge without Nate even if the bot tries; the bot's token has no admin rights and no bypass.
The merge actor only acts on a PR whose changed paths are all tier A. Until then tier B is enforced by process.

## What is custom (the ~5%)

Role pack wiring, the tier table as config, the merge actor (small script: check CLEAN on the exact head, tier-A paths only, one at a time,
wait for deploy), the digest, and a mapping of Ollama to the cheap steps. Honest estimate: the role text may push this above 5% of the
effort; the spike below measures it instead of promising it.

## Spike (one day, no production access)

1. A throwaway private GitHub repo and a Linux VM or WSL2 distro with no keys to the cabin.
2. Install chipping-orchestrator, point it at the repo with a Claude implementer and a second-CLI reviewer.
3. File three issues (a doc fix, a test, a deliberately tier-B change). Pass if: the first two reach `in_review` unattended, the reviewer
   rejects a seeded defect, the tier-B change cannot merge without a human, and the runner restarts cleanly on a second machine from the
   clone alone.
4. Report measured reuse, token cost per issue, and failure modes. Only then decide placement and go or no-go.

## Risks and cautions

- Agents run with sandbox checks off: use a dedicated unprivileged account or container, no SSH keys or Home Assistant tokens in reach.
- **This repository is public.** The factory's vault and any private findings must not be committed here.
- Claude CLI logins and usage limits apply per machine; cost per issue is unmeasured.
- Project maturity beyond chipping-orchestrator and the template is unverified.

## Open questions for Nate

1. Which Linux machine hosts the spike runner? Decided: Linux, not WSL2, not the M920q. Keep machine names and access details in the private vault, not here.
2. Is a second reviewer CLI available (Codex login) or should the reviewer be a second Claude role with different instructions? (Delegation doc R2 reports no Codex login yet; Nate to confirm.)
3. Daily digest to where (GitHub issue comment, email, ntfy)?
