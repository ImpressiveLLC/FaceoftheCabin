# C2 tail I04 evidence: guest-link scopes with reordered words (2026-11-18)

Status: offline routing and delivery evidence only. No model call, no live Ask, no host change. A1 NOT_MEASURED, `release_eligible` false, training NOT_RUN. Code's tail group per Cowork on #244 (6981947683, 6983140307); plan row I04 in [the C2 tail plan](../../governance/proposals/2026-11-18-c2-tail-plan.md).

## Change

- One zero-boost category, `identity_guest_link_scopes`, with the two-word phrase "scopes can". It maps the existing **administrator** section `access-and-storage.md#what-the-server-checks-when-a-guest-link-is-made`.
- No guide text, boost, matcher or budget change. `identity_guest_link_checks` and `access_sharing` are unchanged.
- The development set changes only by clearing `cov_I04`'s `knownGap` marker.

**How it delivers.** "What scopes can a guest link have?" scores 2 for `access_sharing` ("guest link") and 2 for the new category, a tie. Both are kept, `access_sharing` stays first, and an administrator receives its two sections plus the scope section whole: 4043 characters, within 6000. All three sections are administrator-only, so household, child, kiosk, service and no-role receive nothing, exactly as before. A three-word phrase would have won outright and displaced the sharing guidance, so two words were chosen to tie.

## Delta (harness, `AskDeliveryHarnessTest`)

| Audience | Frozen baseline fd1f8676 | main 0aea2f56 (after #500, #502, #503) | This change |
|---|---|---|---|
| administrator, delivered whole | 88 of 98 | 90 of 98 | **91 of 98** |
| household, delivered whole | 4 of 5 | 5 of 5 | 5 of 5 |

- **Rows changed against main 0aea2f56:** only `cov_I04`. The selected set goes from `[access_sharing]` to `[access_sharing, identity_guest_link_scopes]`, the expected section is delivered whole (2832 to 4043 characters), and `knownGap` is cleared. First-category agreement is unchanged by design.
- **Other checks:** audience leaks 0; over budget 0; largest context 5143. The 150 phrasings still have 16 with no category, with the same ids.
- **All roles:** in a throwaway probe I compared 253 questions (the 150 phrasings plus the 103 rows) under all six roles against `origin/main` 0aea2f56. Only the I04 question differs. For the administrator, it gains the scope section. For the other five roles only the selected set differs, and the delivered context stays empty.

## Tests

New `AskGuestLinkScopesContextTest` (3 tests):
- The administrator receives the scope section with exact text equality, within budget, and no other role receives it.
- "Scopes can" wording about other things (the monitoring key, Home Assistant tokens) never puts this category first. "How do I make a guest link?" keeps `access_sharing` alone.
- Guest-link token and secret questions select `credential_redaction` and deliver credentials-and-access whole.

All Ask test classes: 101 passed, 0 failed, including the harness gates and the 120 pins.
