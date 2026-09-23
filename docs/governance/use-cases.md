# Use cases

The traceability root. Every backlog item, PR and sign-off names at least one UC-ID. A new need gets a new UC here before work is scored.

| ID | Use case | Actor | Stated by | Serves decisions |
|---|---|---|---|---|
| UC-1 | Show the policy-servicing insurance agent (Liberty Mutual; not the Foremost adjuster) current and historical cabin conditions and the alert record, read-only, with no account, to support renewal underwriting and a possible rate reduction | External viewer | Nate, 2026-09-23 | D12, D14 |
| UC-2 | Retrieve camera footage of claim-relevant events and save it locally, named by location, camera and start time, without opening Frigate | Administrator | Nate, 2026-09-23 | D18 (proposed) |
| UC-3 | Platform-imported devices awaiting confirmation survive a backend restart, and their origin platform stays visible and filterable | Administrator | Code handover r8, Sprint 6 WSJF #1 | D10 |
| UC-4 | Ask / Tiny Helpdesk answers operational questions from the knowledge base with citations, and refuses rather than fabricating | Administrator; later household | D9; governed in [`docs/ai-assistant/`](../ai-assistant/wsjf-backlog.md) | D9 |
| UC-5 | A guest-link viewer who reaches something outside their access sees that it is limited by design, not that the system failed | External viewer | Nate, 2026-09-23 | D12 (requirement R-GD) |
| UC-6 | Every agent and human finds each governed fact in exactly one place, changed only by reviewed PR | Nate, Cowork, Code, Codex | Nate, 2026-09-23 | D4 |
| UC-7 | Frigate records the motion events the cameras' own detection sees, so claim-relevant activity is not missing from the record | Administrator | Nate, 2026-09-23 | D18 (proposed) |
