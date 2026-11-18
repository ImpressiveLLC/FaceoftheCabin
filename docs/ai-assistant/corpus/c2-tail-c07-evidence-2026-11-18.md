# C2 tail C07: invite-destination delivery evidence

Assigned by Cowork on #244 comment6980218567; ACK6980249056. Before commit: `fd1f8676fe55dd1ee9c1efaed1e1784abf96ca6e`, the merged C1-S1 baseline. Captured its report before editing. The frozen [baseline record](../rag/eval-results/2026-11-18-c1-s1-baseline.md) remains unchanged. No model, training, sealed access, host or invite action.

## Change and rationale

The exact question "Where do invite links point, and which setting controls it?" previously delivered managed-user guidance, omitting the instance-setting reference. One new zero-boost category, `config_invite_destination`, matches only `invite links point` and reuses the existing configuration section. No guide content or matcher change. Appending this three-word phrase to existing `config_env_instance` would inherit boost2 and score5, outranking a single credential keyword plus its boost (3). The separate score3 mapping ties credential guidance instead, and both whole sections fit. No generic invite, link or point alias is added.

Only cov_C07 loses its knownGap marker. Its question, expected section, role and expected category remain frozen. Therefore first-category and selected-set agreement do not improve: the newly selected category has a different name but supplies the same required section. Delivery is the acceptance metric; expectations were not rewritten to manufacture category agreement.

## Measured before and after

| Measure | Before | After |
|---|---:|---:|
| Administrator whole delivery |88/98|89/98|
| Household whole delivery |4/5|4/5|
| Administrator first-category agreement |84/98|84/98|
| Administrator expected-category selected |89/98|89/98|
| Known gaps |11|10|
| Audience leaks / over-budget contexts |0/0|0/0|
| Maximum delivered characters |5143|5143|

Only cov_C07 changes among the103 row results: managed_users to config_invite_destination, expected whole delivery false to true. All remaining row results are identical.

## Regression and preservation checks

- Before fix: exact-question whole-section assertion FAIL1/1. After fix:92focused tests PASS, zero failures/errors/skips, including120routing pins and the real delivery harness.
- Administrator receives the exact full instance-settings section. Adult household, child, kiosk, service and null receive none of that administrator section. All six audiences are explicitly checked.
- Nine password/token/secret variants, including repeated destination phrases and reversed question order, retain both complete credentials-and-access and destination sections within6000characters. Ordinary managed-user routing remains unchanged.
- Actual Java before/after probe over all150existing phrasings: selected categories and complete context lists unchanged for all six roles;16no-category both sides. Probe PASS1/1. The retired route.py replica was not used.
- Existing fixture fields/category/change-note prefixes preserved; only one category and note appended. Mirrored bytes identical. Development JSON differs only by removing cov_C07 knownGap. No frozen baseline file changed.

## Input identity

Runtime and guide content are unchanged from the pinned before commit; harness is the merged C1-S1 harness. Hashes below are LF-normalized SHA-256 from the before/after reports; candidate report revision is verified again after committing.

| Input | Before | After |
|---|---|---|
| `docs/ai-assistant/corpus/c1-dev-retrieval-set-2026-11-18.json` | `81f8fd7d2a255b8c3489bd65ec1c32604b414d6cc0ee8c062e87899b77c989d4` | `291aef2e5a0174012152a2ad1f44b21762c5565e9075dad325f443c081827ea2` |
| `docs/ai-assistant/rag/context-fixtures-r2.json` | `b2bbac7a033231e88a2483ff584545be2151f95b7823b74a9e737972733fb3dd` | `74c585112435a5651afa012a7f672712408cfb1401b5b7c5ab7eb1b5848e7a2b` |
| `cabin-orchestration-platform/backend/src/main/resources/rag/context-fixtures-r2.json` | `b2bbac7a033231e88a2483ff584545be2151f95b7823b74a9e737972733fb3dd` | `74c585112435a5651afa012a7f672712408cfb1401b5b7c5ab7eb1b5848e7a2b` |

All consumed-guide fingerprints match before/after. Reports are development evidence, not acceptance or answer-quality evidence. A1 NOT_MEASURED, release_eligible false. Independent Cowork exact-head review, CI and the named merge/deploy owner remain required. Author checks are not CLEAN.
