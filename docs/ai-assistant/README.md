# Platform assistant knowledge

This is the shared, source-grounded knowledge collection for explaining, operating and recreating FaceoftheCabin. The current delivery is **corpus foundation r1**, not an enhanced RAG service or a verified one-command installer.

Source baseline: `cd145620bcbe7deaa30e236779ec7e1134db7880`. Reviewed from repository source; no new live-runtime or clean-room-installation verification has been performed. Human/Claude/Codex contributors use the same versioned documents and evidence. Missing D13–D16 or handover revisions do not block work supported by available sources.

## Read by task

| Task | Start here |
|---|---|
| Understand the products and find supported capabilities | [Operating the platform](user-guide/operations.md) |
| Configure an instance or trace a setting | [Configuration](user-guide/configuration.md) |
| Create an independent instance | [Independent installation](user-guide/independent-installation.md) |
| Diagnose, deploy or recover | [Maintenance supplement](user-guide/maintenance.md) |
| Understand Ask, citations and knowledge limits | [Ask and knowledge](user-guide/ask-and-helpdesk.md) |
| Contribute a correction or enhancement | [Shared contribution procedure](contributing.md) |
| Check documentation completeness | [Coverage and gaps](corpus/coverage.md) |
| Find exact source declarations | [Route/service/configuration inventory](corpus/source-inventory.md), [machine-readable inventory](corpus/source-inventory.json) |
| Measure usefulness before LLM/RAG changes | [Corpus evaluation questions](rag/eval-questions.md) |
| Read the first live baseline | [2026-09-07 baseline report](rag/baseline-2026-09-07.md) |
| Read the supported answers and their evidence | [Evaluation answer set](corpus/eval-answer-evidence.md) |
| Reconcile earlier Claude work and retired project names | [Recovered local evidence](corpus/local-history-evidence.md) |

The initial WSJF proposal remains separately reviewable in [PR #34](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/34). This corpus delivery neither changes Cowork's Ratification fields nor claims a WSJF item complete.

## Required guide topics and canonical destinations

Existing manuals stay canonical where sufficient; supplements explain gaps and verified corrections instead of creating competing copies.

| Handover topic | Destination |
|---|---|
| Quick start | [Independent installation](user-guide/independent-installation.md), with explicit clean-host limitations |
| Configuration | [Configuration](user-guide/configuration.md) and generated name/source index |
| Accounts/packages | [Installation inputs and steps](user-guide/independent-installation.md#minimum-inputs) and [existing account guide](../REPLICATION.md#3-accounts-you-need-independent-of-this-instances) |
| Credential management | [Configuration: credentials](user-guide/configuration.md#credentials-and-access) and [Vaultwarden runbook](../MAINTENANCE.md#vaultwarden-credential-vault) |
| Tokens/security | [Configuration: credentials](user-guide/configuration.md#credentials-and-access) and [existing security posture](../REPLICATION.md#7-security--credential-handling-posture) |
| Device onboarding | [Operations: device onboarding](user-guide/operations.md#device-onboarding) |
| User onboarding | [Operations: users and family data](user-guide/operations.md#users-and-family-data) |
| Integrations | [Operations: capability map](user-guide/operations.md#capability-map), inventory and existing replication integration guidance |
| Roadmap | [Existing ROADMAP](../../ROADMAP.md) and [status](../ontology/SPRINT-STATUS.md); planned statements are not current capability claims |
| Maintenance | [Maintenance supplement](user-guide/maintenance.md) plus existing runbooks |
| Ask/helpdesk | [Ask and knowledge](user-guide/ask-and-helpdesk.md) |

## Content and publication boundary

These operator-facing documents contain technical context. Do not indiscriminately expose the entire corpus to household/kiosk users. Credential values, `.env`, vault contents, session logs and sensitive household observations are never part of this corpus. Source indices are discovery aids, not permission to invoke every endpoint. In particular, some GET operations have side effects.

No ingestion or model changes are included. Before using these pages in the assistant, review audience applicability, superseded material, document/section citations and the gap register. A paragraph describing planned behavior must not become an operational instruction merely because it was retrieved.

## Reproduce the source index

From the repository root, with Python 3 and Git available:

```text
python docs/ai-assistant/scripts/build-corpus-inventory.py --check
```

The default reads the pinned Git revision, not working `.env` files or production. To propose a refresh, run the script with `--revision <reviewed-commit>` and review the changed inventory, gap register and eval oracles in a PR. The script writes only the two source-inventory artifacts; it does not ingest documents, contact a provider, or deploy anything. Parser coverage is intentionally distinguished from reviewed semantic coverage.
