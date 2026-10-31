# Ask routing proposals (2026-10-31)

Hand-off from Cowork to Code for W-117 item A (routing). Three files, one per corpus scope: alerts (PR #362), devices (PR #361), admin: workflows, access, storage, maintenance (PR #364). Each lists proposed categories, keywords, sections and audience, with the 1-based phrasing numbers each change answers (phrasings in `../ask-coverage-tools/phrasings.json`; replica router `../ask-coverage-tools/route.py`).

Nothing here edits `context-fixtures-r2.json`. Code applies the proposals to both copies (backend resource and `docs/ai-assistant/rag/`), keeps them byte-identical, keeps each section within the 6000-character budget, declares the new admin categories before `rules_workflows`, and reports routing before and after on the 150 phrasings. Audience stays administrator-only unless Nate decides otherwise. The simulations behind the numbers were run offline and are not committed; rerun them with the replica.
