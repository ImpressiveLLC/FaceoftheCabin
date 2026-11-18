# C2 tail M03 evidence: Grafana blank page outside the embedded panel (2026-11-18)

Status: offline routing and delivery evidence only. No model call, no live Ask, no host change. A1 NOT_MEASURED, `release_eligible` false, training NOT_RUN. Assigned by Cowork on #244 (6981320153); plan row M03 in [the C2 tail plan](../../governance/proposals/2026-11-18-c2-tail-plan.md).

## Change

- One zero-boost category, `recovery_grafana_outside_panel`, with the single three-word phrase "the embedded panel". It maps the existing **household** section `operations-recovery-reference.md#grafana-login-outside-the-embedded-panel`.
- No guide text, boost, matcher or budget change. The existing `recovery_grafana` category and its aliases are unchanged.
- The development set changes only by clearing `cov_M03`'s `knownGap` marker. Its question, expected category, expected section and household audience are unchanged.

**Why three words.** "grafana shows a blank" (four words) was tried first and dropped. It outscored credential wording ("Grafana shows a blank page; where is the admin password?" scored 4 against `credential_redaction`'s 3), so the credential guidance was lost. A three-word phrase still beats generic "sign in" (2), but only ties password, token and secret wording (1 + boost 2). On a tie, `credential_redaction` stays first by declaration order and delivers credentials-and-access whole.

## Delta (harness, `AskDeliveryHarnessTest`)

| Audience | Frozen baseline fd1f8676 | main 0b0fa651 (after #500 and #502) | This change, integrated |
|---|---|---|---|
| administrator, delivered whole | 88 of 98 | 90 of 98 | 90 of 98 |
| household, delivered whole | 4 of 5 | 4 of 5 | **5 of 5** |

Measured on the integrated head: main 0b0fa651, which carries C07 (#500) and M02 (#502), merged into this branch with this change on top. The main fixture is kept byte for byte, and only this category and note are appended. The first measurement, against main c05e9a00 before #502, gave the same single-row delta.

- **Rows changed against main 0b0fa651:** only `cov_M03`. First category goes from `user_auth_role` to `recovery_grafana_outside_panel`, the section is delivered whole (900 characters, previously 1901 of an unrelated section), and `knownGap` is cleared. First-category agreement is unchanged by design, because the new category delivers the same expected section, as with C07.
- **Other checks:** audience leaks 0; over budget 0; largest context 5143. The 150 coverage-map phrasings still have 16 with no category, with the same ids.
- **All roles:** in a throwaway probe I compared the selected categories and delivered context for all 253 questions (the 150 phrasings plus the 103 development rows), under all six roles (the five `HouseholdRole` values plus no role), between `origin/main` 0b0fa651 and the integrated head. Exactly 6 role/question pairs differ, all of them the M03 question (the same result as against c05e9a00 before integration).

## Tests

New `AskGrafanaOutsidePanelContextTest` (3 tests):
- The reported question delivers the Grafana section with exact text equality, within 6000 characters, to every role and to no role. A shorter wording also routes there.
- Generic sign-in questions keep their category ("How do I sign in?" stays `user_auth_role`).
- Four Grafana credential questions (password, admin password, token, secret) select `credential_redaction` and deliver credentials-and-access whole, within budget.

All Ask test classes on the integrated head: 97 passed, 0 failed, including the harness gates, the 120 pins and #502's recovery tests.
