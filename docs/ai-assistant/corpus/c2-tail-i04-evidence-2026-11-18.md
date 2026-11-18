# C2 tail I04 evidence: guest-link scopes with reordered words (2026-11-18)

Status: offline routing and delivery evidence only. No model call, no live Ask, no host change. A1 NOT_MEASURED, `release_eligible` false, training NOT_RUN. Code's tail group per Cowork on #244 (6981947683, 6983140307); plan row I04 in [the C2 tail plan](../../governance/proposals/2026-11-18-c2-tail-plan.md).

## Change

- One zero-boost category, `identity_guest_link_scopes`, with the guest-link-qualified phrase "guest link have". It maps the existing **administrator** section `access-and-storage.md#what-the-server-checks-when-a-guest-link-is-made`.
- No guide text, boost, matcher or budget change. `identity_guest_link_checks` and `access_sharing` are unchanged.
- The development set changes only by clearing `cov_I04`'s `knownGap` marker.

**How it delivers.** "What scopes can a guest link have?" scores 3 for the new category against 2 for `access_sharing` ("guest link"). An administrator receives the scope section whole. All these sections are administrator-only, so household, child, kiosk, service and no-role receive nothing, exactly as before.

**Phrase tried and dropped.** The first draft (c6766ae8) used "scopes can", which tied `access_sharing` and kept the sharing sections too. It is not guest-link-qualified (Codex advisory 6983420254): "What scopes can a Google sign-in have?" and "What scopes can a Home Assistant account have?" each selected only this category and received the guest-link section. Both are now pinned as negatives, with the #503 boundary applied.

**Precision note.** "What does a guest link have access to?" also matches "guest link have" now. It receives the scope section, which answers what a guest link can read, instead of the general sharing sections. This is the same topic, administrator-only, and was not in the measured sets.

## Delta (harness, `AskDeliveryHarnessTest`)

| Audience | Frozen baseline fd1f8676 | main d4296aec (after #500, #502, #503, #504) | This change, integrated |
|---|---|---|---|
| administrator, delivered whole | 88 of 98 | 91 of 98 | **92 of 98** |
| household, delivered whole | 4 of 5 | 5 of 5 | 5 of 5 |

Measured on the integrated head: main d4296aec merged into this branch, with main's fixture kept byte for byte and only this category and note appended. The pre-integration measurement against 0aea2f56 gave the same single-row delta (90 to 91).

- **Rows changed against main d4296aec:** only `cov_I04`. The selected set goes from `[access_sharing]` to `[identity_guest_link_scopes]`, the expected section is delivered whole, and `knownGap` is cleared. First-category agreement is unchanged by design, because the expected category in the frozen row is `identity_guest_link_checks`, which maps the same section.
- **Other checks:** audience leaks 0; over budget 0; largest context 5143. The 150 phrasings still have 16 with no category, with the same ids.
- **All roles:** in a throwaway probe I compared 253 questions (the 150 phrasings plus the 103 rows) under all six roles against `origin/main` d4296aec, on the integrated head. Only the I04 question differs (6 pairs, with delivered context changing for the administrator only). For the administrator, it now receives the scope section instead of the two sharing sections. For the other five roles only the selected set differs, and the delivered context stays empty.

## Tests

New `AskGuestLinkScopesContextTest` (3 tests):
- The administrator receives the scope section with exact text equality, within budget, and no other role receives it.
- Scope questions about other products (Google sign-in, Home Assistant account, the monitoring key, Home Assistant tokens) select neither guest-link category and never receive the section, for any role. "How do I make a guest link?" keeps `access_sharing` alone.
- Guest-link token and secret questions select `credential_redaction` and deliver credentials-and-access whole.

All Ask test classes on the integrated head: 102 passed, 0 failed, including the harness gates and the 120 pins.
