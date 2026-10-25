---
name: cabin-opportunity-work-sharing
description: Plan and coordinate weekly review of contextual relationship gaps and automation opportunities in FaceoftheCabin, including UC reuse, evidence, WSJF and implementation handoffs. Use for Opportunities discovery/review planning, not as permission to execute hardware actions.
---

# Opportunity review and work sharing

Start from docs/governance/proposals/2026-10-16-context-opportunities.md. Read current use-cases.md, backlog.md, D27 and the relevant source/owner configuration before claiming a feature exists.

1. Reuse an existing UC when its actor/outcome fits. Record conflicting user statements as an explicit policy question; never silently replace one. Separate user assertions, Git design, code implementation and live proof.
2. For each candidate, identify need, stable entities, relation meaning, source/hash/time, scope, uncertainty, gap kind and next evidence step. Use D27 predicates; new vocabulary follows Q-OC-6. A relationship does not grant device capability or authorization.
3. Route private operational evidence to administrator-only Opportunities review. Public Tech ID findings must not acquire private presence/configuration detail. Use references to existing source records instead of duplicate queues.
4. Weekly review disposition is separate from OPEN/ACKNOWLEDGED/RESOLVED and from implementation status. Deduplicate, preserve rejection/defer reasons, and reopen only for materially new evidence or a due review. Acceptance means planning, not execution. Do not install a scheduler merely because this skill was loaded.
5. Propose WSJF using 2*Safety + Usability + Extensibility + Time, divided by Fibonacci size. Mark scores provisional; compare with the canonical backlog and apply dependencies/safety gates. Keep Ask work in its own backlog. Allocate IDs only after checking main/open PRs and peer ownership.
6. Publish a work-sharing table in Git: lane, acknowledged owner, files, contract, dependency, test evidence, next handoff. One editor owns each shared governance table. Missing peer acknowledgment means unassigned/unknown, not agreed.
7. Before push/merge use cabin-peer-agent-collaboration and fotc-peer-merge-sequencing from their actual checkout paths. Check live sessions and open PRs, simulate overlap, record agreed order on the PR. Messaging requires user authorization; this skill does not grant it.
8. Keep existing HA/Node-RED ownership; new platform workflows follow D27. Stop, snooze and resume require explicit semantics, persisted expiry and owner receipts. Never infer success from HTTP acceptance, equate buzzer output with leak state, or silence a shared leak demand to resolve intrusion.
9. Close with source evidence, UC/work/PR links, actual validation, remaining gaps and next review. Preserve separate proposed/reviewed/implemented/live-verified states. Update only the canonical home of a fact.
