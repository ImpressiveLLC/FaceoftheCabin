# C2 tail M04 evidence: the external dead-man monitor (2026-11-18)

Status: offline routing and delivery evidence only. No model call, no live Ask, no host change. A1 NOT_MEASURED, `release_eligible` false, training NOT_RUN. Option 1 of the [residual-gap ordering](../../governance/proposals/2026-11-18-c1-residual-gap-ordering.md) (#513), assigned to Code by Cowork on #244 ([6988863034](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6988863034)) and conditional on #512 merging first.

## Change

- One zero-boost category, `recovery_external_deadman`, with the phrase "external dead-man". The phrase is qualified to the external host monitor. It maps the existing **administrator** section `operations-recovery-reference.md#external-dead-man-monitoring`.
- No guide text, boost, matcher or budget change. `self_healing_recovery` and `recovery_deadman` are unchanged.
- The development set changes only by clearing `cov_M04`'s `knownGap` marker.

**How it delivers.** "What does the external dead-man monitor do if the hub goes silent?" now scores 2 for the new category, against 1 each for `self_healing_recovery` and `recovery_deadman` (both on "dead-man"). The new category alone is selected, and the 1760-character dead-man section is delivered whole. The general 4648-character detect-and-recover section is no longer delivered for this question. That section holds no setup limits, and the question names the external monitor specifically. This is the trade the ordering doc put to Cowork, accepted in the ruling above.

**Phrase rejected before implementation.** "dead-man monitor" would also have matched "What does the Blink dead-man monitor do?" and replaced the section on Blink's separate motion dead-man alert (Codex review of #513). Blink and generic dead-man wordings are pinned as negatives.

## Delta (harness, `AskDeliveryHarnessTest`)

| Audience | Frozen baseline fd1f8676 | main 42bcc6da | This change |
|---|---|---|---|
| administrator, delivered whole | 88 of 98 | 95 of 98 | **96 of 98** |
| administrator, selected but not delivered | 1 | 1 | **0** |
| household, delivered whole | 4 of 5 | 5 of 5 | 5 of 5 |

- **Rows changed against main 42bcc6da:** only `cov_M04`. The selected set goes from `[self_healing_recovery, recovery_deadman]` to `[recovery_external_deadman]`, and delivery goes from 4648 characters of the wrong section to the dead-man section, whole (1760). `knownGap` is cleared.
- **Other checks:** audience leaks 0; over budget 0; largest context 5143. The 150 phrasings still have 16 with no category.
- **All roles:** in a throwaway probe I compared 253 questions (the 150 phrasings plus the 103 rows) under all six roles against `origin/main` 42bcc6da. Only the M04 question differs, with delivered context changing for the administrator only.
- **Order with #512:** this branch was measured on main before #512. When #512 merges, I merge main in and re-measure before READY. #512 changes only the two setup-section lengths, which this row does not use.

## Tests

New `AskExternalDeadmanContextTest` (3 tests):
- The exact question delivers the dead-man section with exact text equality, within budget, to an administrator, and to no other role.
- Blink and generic dead-man wordings ("What does the Blink dead-man monitor do?", "What is the Blink dead-man alert?", "What does the dead-man alert mean?", "Is there a dead-man check?") never select the new category. The Blink monitor question stays `self_healing_recovery`.
- External dead-man token, password and secret questions select `credential_redaction` and deliver credentials-and-access whole.

All Ask test classes: 112 passed, 0 failed.
