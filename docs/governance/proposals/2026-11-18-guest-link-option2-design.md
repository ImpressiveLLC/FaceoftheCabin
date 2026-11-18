# Guest-link option 2: a tie-preserving category, measured (design, 2026-11-18)

Status: **design only; nothing implemented.** Requested by Cowork on #244 ([6992192161](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6992192161)), following ruling A ([6991093994](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6991093994)) and the [guest-link and C3 proposal](2026-11-18-guest-link-tidy-and-c3-prerequisites.md). Measured on `main` 6d3360fd844c7aefbbc59489f9c6da5a4a457f77 with the real `AskContextBuilder` (no knowledge nodes, reviewed docs, 6000-character budget), using temporary fixture copies in a throwaway probe that was not committed. No fixture, guide, matcher, budget or runtime change. A1 NOT_MEASURED, `release_eligible` false, training NOT_RUN.

## Result in one line

**None of the three measured candidates passes every gate. Recommend keeping ruling A's pinned limitation and implementing none of these candidates.** Other phrase designs are unmeasured.

## The problem

Three mixed guest-link plus installation wordings deliver only the installation section. The three-word installation phrases (score 3) beat `access_sharing`'s "guest link" (score 2), so the two sharing sections (administrator-only, 1673 + 1159 = 2832 characters) are dropped:

- "What is the installation plan for a guest link?" (and "for the guest link?")
- "Did the installation acceptance pass for the guest link?"

Only the first, "What is the installation plan for a guest link?", is pinned, by `knownLimitationMixedGuestLinkPlanOmitsSharingContext` (#516). The "the guest link" variant and the acceptance wording are measured examples of the same behavior. No test pins them.

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

### What the measurements show, and what they do not

The three candidates fail for different reasons:
- **A** contains "link" and scores 3 on plain guest-link questions, which beats existing two-way ties ("who can", export).
- **E** omits "link" and matches unrelated "guest" questions.
- **B** has four words, so it wins outright, displacing both the installation phrase and credential wording.

These results apply to these three candidates only. They do **not** show that every phrase-only design fails. `AskContextBuilder` sums the word counts of distinct matched pattern spans (`CompiledCategory.score`), so a score of 3 can come from several shorter phrases rather than one three-word phrase. Combinations like that are unmeasured. They also do not show that a matcher or tie-policy change is necessary. Such a change is one possible route, and it is outside the current C1 rulings, which exclude matcher, budget and boost changes.

## Recommendation

1. **Keep ruling A as is.** The pinned limitation test stays, and the three mixed wordings remain a documented, administrator-only limitation outside the measured sets.
2. **Do not implement A, B or E.** A and E each introduce a regression that is worse than the limitation they fix: A in-corpus (the export question), E for plausible household "guest room" wording. B reverses the loss and displaces credential guidance.
3. **Record the data for later.** Any later design, whether another phrase combination or a separately ruled matcher or tie-policy change, should use the three mixed wordings plus the "who can" and export ties as its first regression set. Two of the mixed wordings are not pinned by a test today.

## Reproduce

At 6d3360fd, append one zero-boost category to a copy of the fixture, mapping `access-and-storage.md#share-read-only-access-with-a-link-guest-access` and `#give-someone-a-demo-link` (administrator), with each candidate's phrases. Build `AskContextBuilder` on that copy and the real docs. Then:
- compare `categoryIdsFor` and `buildContext(question, role)` against the unchanged fixture for the questions above;
- compare the same for the 150 phrasings plus the 103 development rows, under all six roles.
