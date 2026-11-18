# Guest-link option 2: a tie-preserving category, measured (design, 2026-11-18)

Status: **design only; nothing implemented.** Requested by Cowork on #244 ([6992192161](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6992192161)), following ruling A ([6991093994](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6991093994)) and the [guest-link and C3 proposal](2026-11-18-guest-link-tidy-and-c3-prerequisites.md). Measured on `main` 6d3360fd844c7aefbbc59489f9c6da5a4a457f77 with the real `AskContextBuilder` (no knowledge nodes, reviewed docs, 6000-character budget), using temporary fixture copies in a throwaway probe that was not committed. No fixture, guide, matcher, budget or runtime change. A1 NOT_MEASURED, `release_eligible` false, training NOT_RUN.

## Result in one line

**No measured candidate passes every gate. Recommend keeping ruling A's pinned limitation and not implementing option 2 with phrase matching alone.**

## The problem

Three mixed guest-link plus installation wordings deliver only the installation section. The three-word installation phrases (score 3) beat `access_sharing`'s "guest link" (score 2), so the two sharing sections (administrator-only, 1673 + 1159 = 2832 characters) are dropped:

- "What is the installation plan for a guest link?" (and "for the guest link?")
- "Did the installation acceptance pass for the guest link?"

All of them are pinned today by `knownLimitationMixedGuestLinkPlanOmitsSharingContext` (#516).

## Gates

A candidate must:
1. Deliver the installation section **and** both sharing sections whole for the mixed wordings, within 6000.
2. Leave plain sharing questions, I04 and the C1 development rows unchanged in delivered sections.
3. Keep credentials-and-access first and whole on credential wording.
4. Not capture unrelated "guest" questions.
5. Change nothing else across 253 questions × 6 roles: the 150 coverage-map phrasings plus the 103 development rows, for every `HouseholdRole` and no role.

## Candidates measured

Each candidate is one separate zero-boost category that maps the same two sharing sections as `access_sharing`.

| Candidate | Phrases (words) | Mixed wordings | Plain sharing and I04 | Credential wording | Unrelated "guest" | 253 × 6 changes | Verdict |
|---|---|---|---|---|---|---|---|
| A | "a guest link", "the guest link" (3) | Fixed: installation + both sharing sections, 5225 / 4318 | **Fails.** "Who can see the guest link activity?" loses `who_can_change`'s section (2058). I04 and "What does a guest link have access to?" gain the sharing sections | Kept first and whole; sharing sections added (4759) | Not captured | 2 questions, administrator only: I04 gains the sharing sections; **"Do export permissions allow a guest link to export all history?" loses `cap_events`' event-routes section** | Rejected: one in-corpus regression and one out-of-corpus regression |
| B | "for a guest link", "for the guest link" (4) | **Fails.** It beats the installation phrase, so the installation section is lost instead | Unchanged | **Fails.** It beats credential wording: "Where is the token for a guest link?" loses credentials-and-access | Not captured | 0 | Rejected: reverses the loss and displaces credential guidance |
| E | "for a guest", "for the guest" (3) | Fixed: 5225 / 4318 | Unchanged, including I04 and "Who can see the guest link activity?" | Kept first and whole; sharing sections added (4759) | **Fails.** "Is there a heater setting for the guest room?" loses both device categories' sections (four sections) to the sharing sections. "What is the Wi-Fi password for the guest room?" gains sharing sections. "Is the dashboard available for a guest?" goes from no category to sharing | **0** | Rejected: clean in the measured sets, but it captures "guest room" questions outside them |

### Why phrase matching cannot meet every gate

To keep the installation section, the guest-link phrase must **tie** the three-word installation phrases. That means it needs three words of its own.

- A three-word phrase that contains "guest link" (A) also scores 3 on plain guest-link questions. There it beats the two-way ties `access_sharing` already forms with other categories ("who can", export), so it removes their sections.
- A three-word phrase without "link" (E) matches unrelated "guest" questions.
- Four words (B) win outright and displace both the installation phrase and credential wording.

Every option either drops someone's section or captures unrelated questions. Fixing this properly would need a matcher or tie-policy change, for example keeping lower-scoring categories that share a matched span. That is outside the C1 rulings, which exclude matcher, budget and boost changes.

## Recommendation

1. **Keep ruling A as is.** The pinned limitation test stays, and the three mixed wordings remain a documented, administrator-only limitation outside the measured sets.
2. **Do not implement A, B or E.** A and E each introduce a regression that is worse than the limitation they fix: A in-corpus (the export question), E for plausible household "guest room" wording.
3. **Record the data for later.** If a matcher or tie-policy change is ever authorized, as a separate design with its own ruling, these three mixed wordings, plus the "who can" and export ties, are the first regression set it should satisfy.

## Reproduce

At 6d3360fd, append one zero-boost category to a copy of the fixture, mapping `access-and-storage.md#share-read-only-access-with-a-link-guest-access` and `#give-someone-a-demo-link` (administrator), with each candidate's phrases. Build `AskContextBuilder` on that copy and the real docs. Then:
- compare `categoryIdsFor` and `buildContext(question, role)` against the unchanged fixture for the questions above;
- compare the same for the 150 phrasings plus the 103 development rows, under all six roles.
