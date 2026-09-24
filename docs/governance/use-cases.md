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
| UC-8 | Any user with camera access can pin a clip or continuous range for indefinite retention and review; each pin notifies the household and only an admin can release it | Any camera-authorized role | Nate, 2026-09-23 | D18 (proposed) |
| UC-9 | A prospective partner or on-site party (the policy-servicing agent; contractors already admitted to the property) sees the real product, read-only and without an account: live monitoring, alerting, automated actions (for example water shutoff on a leak), reporting and present-day history. Cameras, locks and other presence-class devices load in their normal card layout with their contents labeled "hidden for Demo viewers". Purpose: (a) the agent can assess live-monitoring discounts, (b) the agent can judge it as a lower-cost, broader alternative to commercial monitoring, (c) the agent can judge it as a tool for customers in claims and remediation (is someone on site, are conditions improving, does the work match the plan), and contractors understand how and why the property is monitored | External viewer (agent, contractor) | Nate, 2026-09-23 | D22 (proposed), D12 |
