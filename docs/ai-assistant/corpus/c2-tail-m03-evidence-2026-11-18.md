# C2 tail M03 evidence: Grafana blank page outside the embedded panel (2026-11-18)

Status: offline routing and delivery evidence only. No model call, no live Ask, no host change. A1 NOT_MEASURED, `release_eligible` false, training NOT_RUN. Assigned by Cowork on #244 (6981320153); plan row M03 in [the C2 tail plan](../../governance/proposals/2026-11-18-c2-tail-plan.md).

## Change

- One zero-boost category, `recovery_grafana_outside_panel`, with the single three-word phrase "the embedded panel". It maps the existing **household** section `operations-recovery-reference.md#grafana-login-outside-the-embedded-panel`.
- No guide text, boost, matcher or budget change. The existing `recovery_grafana` category and its aliases are unchanged.
- The development set changes only by clearing `cov_M03`'s `knownGap` marker. Its question, expected category, expected section and household audience are unchanged.

**Why three words.** "grafana shows a blank" (four words) was tried first and dropped. It outscored credential wording ("Grafana shows a blank page; where is the admin password?" scored 4 against `credential_redaction`'s 3), so the credential guidance was lost. A three-word phrase still beats generic "sign in" (2), but only ties password, token and secret wording (1 + boost 2). On a tie, `credential_redaction` stays first by declaration order and delivers credentials-and-access whole.

## Delta (harness, `AskDeliveryHarnessTest`)

| Audience | Frozen baseline fd1f8676 | main c05e9a00 (after #500) | This change |
|---|---|---|---|
| administrator, delivered whole | 88 of 98 | 89 of 98 | 89 of 98 |
| household, delivered whole | 4 of 5 | 4 of 5 | **5 of 5** |

- **Rows changed against main:** only `cov_M03`. First category goes from `user_auth_role` to `recovery_grafana_outside_panel`, the section is delivered whole (900 characters, previously 1901 of an unrelated section), and `knownGap` is cleared. First-category agreement is unchanged by design, because the new category delivers the same expected section, as with C07.
- **Other checks:** audience leaks 0; over budget 0; largest context 5143. The 150 coverage-map phrasings still have 16 with no category, with the same ids.
- **All roles:** in a throwaway probe I compared the selected categories and delivered context for all 253 questions (the 150 phrasings plus the 103 development rows), under all six roles (the five `HouseholdRole` values plus no role), between `origin/main` c05e9a00 and this change. Exactly 6 role/question pairs differ, all of them the M03 question.

## Tests

New `AskGrafanaOutsidePanelContextTest` (3 tests):
- The reported question delivers the Grafana section with exact text equality, within 6000 characters, to every role and to no role. A shorter wording also routes there.
- Generic sign-in questions keep their category ("How do I sign in?" stays `user_auth_role`).
- Four Grafana credential questions (password, admin password, token, secret) select `credential_redaction` and deliver credentials-and-access whole, within budget.

All Ask test classes: 95 passed, 0 failed, including the harness gates and the 120 pins.
