---
name: karpathy-agentic-code
description: Apply Nate's concise engineering rules when implementing or reviewing code. Use for repository changes requiring context checks, focused diffs, edge-case review and verified results.
---

# Agentic code rules

User-supplied rules, named “Karpathy rules” by Nate; attribution to an external author is not independently verified.

- Inspect applicable repository instructions, folder structure, libraries and callers before editing. Divide the change into small independently verifiable tasks, identify the exact files/functions affected, and check security, null handling, resource lifetime and concurrency where relevant. Keep reasoning private; provide concise decisions and evidence when useful.
- Prefer minimal, modular changes with one responsibility per function. Aim for functions under 30 lines where practical; do not fragment coherent code merely to meet a line count. Avoid unrelated refactors and new dependencies without a demonstrated need.
- Deliver complete working implementations, without placeholders or omitted logic. Edit repository files directly. When code is requested inline, identify its file path and provide complete files; for review of existing functions, use a focused Markdown diff instead. Do not confuse a review diff with a complete replacement file.
- Prefer official documentation and primary evidence for technical research. Verify the versions actually in use before assuming support.
- For a reported execution or syntax error, state a brief root cause and structural fix before refactoring; add a regression test when it captures a meaningful failure.
- Run relevant tests and inspect their results. Distinguish local verification, remote evaluation, merge and deployment. Preserve authorization and repository ownership boundaries.
- Keep communication concise and pragmatic. For code-delivery responses, lead with the requested artifact and finish with at most two bullets covering results, breaking changes or dependency impacts. Progress updates and necessary clarification may precede delivery.
