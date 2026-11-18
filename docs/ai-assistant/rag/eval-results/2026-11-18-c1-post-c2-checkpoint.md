# C1 post-C2 checkpoint (2026-11-18)

Status: **checkpoint, offline and deterministic.** Measured with the existing [`AskDeliveryHarnessTest`](../../../../cabin-orchestration-platform/backend/src/test/java/com/cabin/orchestrator/helpdesk/AskDeliveryHarnessTest.java); no new test code. No model call, grading, network, database, sealed-set access, guide, routing map, budget or runtime change. Answer quality **NOT_MEASURED**; A1 NOT_MEASURED, `release_eligible` false, training NOT_RUN.

- **Scope:** Cowork's ruling on #244 ([6987067644](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6987067644)) and the plan in #509.
- **Pinned commit:** `main` 1a94b0be1f05c4d3a9e488cd9860032f8265d162, after #507 and C2 closure. Merges after this commit that change no harness input do not affect the numbers. If a later non-docs merge changes an input, this checkpoint is re-pinned and says so.
- **Frozen baseline:** [`2026-11-18-c1-s1-baseline.md`](2026-11-18-c1-s1-baseline.md) at fd1f8676. It is **unchanged** and stays the "before" record.

Both reports were produced by the same command, `mvn -Dtest=AskDeliveryHarnessTest test`, run at each commit. The baseline report came from a detached checkout of fd1f8676; its harness version is the one merged in #499. The checkpoint report came from 1a94b0be.

## Per audience

| Measure | Frozen fd1f8676 | Checkpoint 1a94b0be |
|---|---|---|
| administrator rows | 98 | 98 |
| administrator, first category is the expected one | 84 | 84 |
| administrator, expected category in the selected set | 89 | 89 |
| **administrator, expected section delivered whole** | **88** | **95** |
| administrator, no category | 2 | 0 |
| administrator, selected but not delivered | 1 | 1 |
| household rows | 5 | 5 |
| **household, expected section delivered whole** | **4** | **5** |
| audience leaks | 0 | 0 |
| over budget | 0 | 0 |
| largest delivered context (chars, budget 6000) | 5143 | 5143 |
| 150 coverage-map phrasings with no category | 16 (`route.py`, before the harness reported it) | 16 |

First-category and selected-set agreement do not change. That is by design: each tail fix added a new zero-boost category that maps the **same** expected section, so the frozen rows' expected category is not the one that now delivers. Delivered-whole is the measure that matters.

## The eight rows that changed

| Row | Fixed by | First category: frozen to checkpoint | Delivered chars: frozen to checkpoint |
|---|---|---|---|
| `cov_C07` | #500 (C07) | `managed_users` to `config_invite_destination` | 3967 (wrong section) to 1677 |
| `cov_M02` | #502 (M02) | none to `recovery_nodered_editor` | 0 to 1468 |
| `cov_M03` | #503 (M03) | `user_auth_role` to `user_auth_role` (the Grafana section is now kept as a tie) | 1901 to 2801 |
| `cov_M05` | #504 (M05) | `self_healing_recovery` to `recovery_heartbeat_commands` | 4648 (wrong section) to 2096 |
| `cov_I04` | #505 (I04) | `access_sharing` to `identity_guest_link_scopes` | 2832 (wrong sections) to 1211 |
| `cov_I02` | #506 (I02) | `user_auth_role` to `identity_session_duration` | 1901 (wrong section) to 1214 |
| `install_gap_3` | #507 | none to `recreation_instance_plan` | 0 to 2393 |
| `install_gap_4` | #507 | `device_import_lifecycle` to `recreation_instance_acceptance` | 4367 (wrong section) to 1486 |

No other row changed in selection or delivery. The 103 row ids are identical in both reports.

## The three remaining gaps, measured (no fix)

Two different blockers are involved (Codex finding [6987150981](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6987150981)). M04 is **selected but not delivered**: the budget alone blocks it. The two installation rows are **not selected at all**. Their budget deficit is conditional: it would apply only if their expected category were also selected.

| Row | Expected section | Selected at checkpoint | Delivered at checkpoint | Blocker | Deficit if the expected section were added |
|---|---|---|---|---|---|
| `cov_M04` | `operations-recovery-reference.md#external-dead-man-monitoring` (1760) | `self_healing_recovery`, `recovery_deadman` (tie) | `maintenance.md#what-detects-and-what-recovers` only, 4648 | budget: tie selected, section skipped | 4648 + 1760 = 6408, **408 over** |
| `install_gap_1` | `independent-installation.md#establish-the-actual-installation-plan` (2393) | `installation` only | `minimum-inputs` + `verified-clean-host-limitations`, 4560 | **selection miss**, then budget | 4560 + 2393 = 6953, **953 over** |
| `install_gap_2` | `independent-installation.md#acceptance-record` (1486) | `installation` only | the same two sections, 4560 | **selection miss**, then budget | 4560 + 1486 = 6046, **46 over** |

Per the rulings (6979177326, 6984259185, recorded in #508), none of these is changed here: no budget raise, no dropping of setup limits, no score workaround. Codex's design on `codex/c1-context-budget-design` covers the 46-character row and cites this checkpoint.

## Input fingerprints at the checkpoint (SHA-256, LF-normalised, from the harness report)

| Input | SHA-256 |
|---|---|
| `docs/ai-assistant/corpus/c1-dev-retrieval-set-2026-11-18.json` | `376b9823915d7f15ba5bb9a44c16dc52b9289bf3e79d8102a208ae91da9af786` |
| `docs/ai-assistant/rag/context-fixtures-r2.json` | `7f157e56e276d12f76359d7259c37928444206e18177d641250be2e43d86ea47` |
| `cabin-orchestration-platform/backend/src/main/resources/rag/context-fixtures-r2.json` | `7f157e56e276d12f76359d7259c37928444206e18177d641250be2e43d86ea47` |
| `docs/ai-assistant/corpus/ask-coverage-tools/phrasings.json` | `316d3c6a61991a10055ab0342045035fc532988b328a5b0436c1bafebeb5b6e2` |

Consumed guides:

| Guide | SHA-256 |
|---|---|
| `docs/ai-assistant/user-guide/access-and-storage.md` | `264436b177617f212928f3dd65673e898bc86dd20ecf15b54a8d49323638a2e9` |
| `docs/ai-assistant/user-guide/capability-routes.md` | `05b81f2eba535b74e60e59a3f56a2217a26bfaf38f69b3fd195ea26de7ae2278` |
| `docs/ai-assistant/user-guide/configuration.md` | `030c2967d28bd6a732db59615c22173b2c9fef2f9aa84dcc06a318aa9b8d1a2b` |
| `docs/ai-assistant/user-guide/independent-installation.md` | `7d193a436585ae265d435dddf0c70f477f16b09d9d2d869362ebbc7c4676aff7` |
| `docs/ai-assistant/user-guide/maintenance.md` | `c62a24f32aa5bf704acd619bc67812330dd283538dd3c6053ed48ffca9118ab6` |
| `docs/ai-assistant/user-guide/operations-recovery-reference.md` | `f08c035d0fe48de24f93ffdfed0c523982e2fa97d020dbd4a62a1debfafef849` |
| `docs/ai-assistant/user-guide/operations.md` | `95202fa66cc22d3eb1fca44815cf3d1d976fe71919af0b6db41fc8b75ea00724` |
| `docs/ai-assistant/user-guide/stack-map.md` | `5fa19cb062f114fafaef926a91803dcf124a8b6bfff967aa4696f1121b0f36a0` |

All eight guide fingerprints are identical to the frozen baseline's. The C2 tail changed only the routing map and the development set (cleared `knownGap` markers), not the guide text.

## The 16 coverage-map phrasings with no category at the checkpoint

`C19-1`, `C19-2`, `D1-2`, `D2-1`, `D3-1`, `D4-1`, `D4-2`, `D5-1`, `D8-2`, `E8-2`, `E13-1`, `E13-2`, `E14-1`, `E15-2`, `E16-2`, `E18-1`

## Reproduce

From `cabin-orchestration-platform/backend` at each commit: `mvn -Dtest=AskDeliveryHarnessTest test`, then read `target/c1-s1-delivery-report.json`. The report's `inputs.measuredRevision` must equal the commit measured.
