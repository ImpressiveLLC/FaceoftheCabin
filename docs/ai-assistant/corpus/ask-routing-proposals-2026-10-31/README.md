# Ask routing proposals (2026-10-31)

Hand-off from Cowork to Code for W-117 item A (routing). Three files, one per corpus scope: alerts (PR #362), devices (PR #361), admin: workflows, access, storage, maintenance (PR #364). Each lists proposed categories, keywords, sections and audience, with the 1-based phrasing numbers each change answers (phrasings in `../ask-coverage-tools/phrasings.json`; replica router `../ask-coverage-tools/route.py`).

Nothing here edits `context-fixtures-r2.json`. Code applies the proposals to both copies (backend resource and `docs/ai-assistant/rag/`), keeps them byte-identical, keeps each category within the 6000-character budget (`ContextMapWiringTest`), and reports routing before and after on the 150 phrasings with a unit test pinning a few phrasings per topic.

## Rules that apply to all three files together

- **Audience.** Each section carries the audience proposed in its file (`household` for plain how-to and status text, `administrator` for configuration and access). Do not widen an administrator section to household yourself; a wider household audience for rules, device and maintenance answers is Nate's decision, ask in the PR.
- **Order.** Ties go to the earlier category. Declare the admin categories and the alerts categories before `rules_workflows`; declare `alert_silencing` before `status_checks` and `leak_response`. Devices categories append after the current 26.
- **Budget.** `rules_workflows` cannot take both the admin prepend and the alerts S8 append (about 7,500 characters); follow the notes in each file.
- **Conflicts when combined** (confirm with the replica after merging the three): phrasing 29 goes to `leak_response`, not `workflow_create`, because both have `shut off the water`; phrasings 38 to 40 are claimed by both `workflow_reset_undo` and `workflow_run_history`, so block order decides; phrasing 119 is claimed by devices' `camera_frames` but lands on `self_healing_recovery`; phrasing 24 goes to `zigbee_duplicate`.
- **Matcher.** Whole words, lowercase, straight apostrophes only; very broad keywords (`update`, `alert`, `room`, `area`, `storage`, `filter`) can pull questions into the wrong category, so test each against the 150 phrasings.
- Size estimates in the files are approximate; the budget test is the authority.

The simulations behind the numbers were run offline and are not committed; rerun them with the replica.
