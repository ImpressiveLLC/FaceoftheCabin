# HO-2026-10-31: Ask weekend test, state and next steps

Written by Cowork, Sat 2026-10-31 01:15 CDT, at Nate's request so the next session (Cowork, Code or Codex, who returns Saturday) can pick up by searching "HO-2026-10-31" or "W-133". Goal (W-117): Ask answers configuration and maintenance questions factually, from the corpus, in the words people use (rules, workflows, alerts, device management). Order: C2 corpus, C1 evaluated routing, C3 one-stop Ask. Nate wants to test Ask at the end of this weekend.

## Where things stand (main 297246a)

Merged this session, all with an independent `CODEX-PROXY` CLEAN on the exact head:
- Corpus: #358 coverage map and replica tools, #361 devices guide, #362 alerts guide, #364 workflows, access and disk-space guide.
- Hand-off: #365 routing proposals (`docs/ai-assistant/corpus/ask-routing-proposals-2026-10-31/`, read its README first).
- Boundary set: #359 question-versus-command candidates; measured 1 of 58 questions routed to an action, 0 of 55 clear questions; 2 of 34 commands answered as a question.
- Backend, deployed green: #367 administrator check on access-token and managed-user routes, #360 question-shape guard.

Open:
- #363 device-details command safety: HOLD at f5490fc. The gap detail was handed to Nate and Code outside GitHub. Do not merge before a new head is re-reviewed.
- #369 Ask routing (Code, item A): needs an independent review; Nate's deploy go was given for the batch in order (2026-10-31 00:43 CDT), confirm per PR.
- #373 review nits for #360 and #367, #371 breaker-box fix (not Cowork-reviewed).

## Next steps, in order

1. #363 repair on a new commit, re-review, deploy.
2. #369: review that the admin categories are declared before `rules_workflows`, `alert_silencing` before `status_checks` and `leak_response`, no category over 6000 characters (`ContextMapWiringTest`), `workflow_basics` measured, and the before and after numbers on the 150 phrasings are reported. Audience stays administrator-only unless Nate decides.
3. W-132 managed-session hardening (neutral description; detail was given to Code privately).
4. After each deploy: rerun the 150 phrasings and the boundary set, report no-category and wrong-category rates and the question-to-action rate. Unrun runtime gates stay `NOT_MEASURED`.
5. Sunday test: baseline of today's Ask first; an improved test only if routing, the UI fixes and the deploy go land Saturday. Firm yes or no by Saturday midday.
6. Backlog hygiene (neutral wording only): the P3 notes from W-117, W-38 and the #367 and #360 reviews.

## Decisions that stay with Nate

Household audience for rules, device and maintenance answers (default administrator-only); a stronger answer model (the 3B model is the quality limit); deploy go per backend PR; the parked items already listed in the board (do not re-nag).

## Process notes

- Independent review pattern: a fresh reviewer, read-only, verdict file ending `CLEAN|HOLD <full sha> - reason`; restate the verdict in that exact form on the PR for the merge gate to read it.
- Public-repo rule: security-gap detail never goes in a GitHub comment or doc; the classifier blocks it. Use neutral wording and hand detail over privately.
- The RTE board is the pinned comment on #244; check it and the latest `CODE` and `COWORK` comments there.
