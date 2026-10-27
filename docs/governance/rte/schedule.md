# RTE schedule and operating rules (Cowork, RTE and PM)

Source of truth for who acts, when and how. Live status is the edited comment on [#244](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6449313109); this file is the committed copy of the rules and the critical path as of 2026-10-27 12:50 CDT, refreshed by Cowork at each change of plan.

### Operating rules (effective now)
1. **Handoff = a comment that starts with `ACTION <who> by <time CDT>:`** plus the exact link, the ready-signal and what to post back. Anything not addressed this way is not a handoff.
2. **Listening:** Code and Codex poll GitHub issue #244 and the PRs they own every 20 minutes while a handoff to them is open. Cowork polls every 20 minutes (session heartbeat) and on every GitHub event. Direct nudge to Code or Codex only when an item is 40 minutes past its time.
3. **Ack in 20 minutes** (one line: "got it, ETA hh:mm"). No ack by +40 minutes: Cowork nudges directly and re-routes if the owner is unavailable.
4. **Review SLA:** Codex verdict within 30 minutes of a head being posted; Cowork source review within 20 minutes.
5. **Merge SLA:** a PR that is CLEAN on its pinned head, green and mergeable is merged by Cowork within 10 minutes, one at a time, pinned to the head SHA. No waiting on Nate. A PR with a backend/** change also needs Code to watch the deploy gate. Stop and call Nate only for: a failed deploy that did not roll back, a sealed-data or secret exposure, a live device write, anything irreversible.
6. **Record:** every merge, verdict and rerun result is a comment with ids, counts and hashes only, never private text. Cowork keeps this board and `docs/governance/backlog.md` current.
7. **Notify back:** the owner posts `DONE <item> <link>` and the next owner is named in the same comment.

### Critical path to the first C2 measurement (the training-focus path)
| # | Step | Who acts | Trigger | Ready signal | Due | Notify next |
|---|---|---|---|---|---|---|
| 1 | Deployed-state attestation for #288 (context-map sha256 equals docs copy, image/commit, per-category chars). Counts and hashes only | CODE | now | `DONE attestation` comment with hashes | 13:30 CDT | Cowork |
| 2 | Cowork posts the explicit start for the 72-trial run | COWORK | step 1 consistent | `START C2-RUN-1` comment | within 10 min of 1 | Code, Codex |
| 3 | Host run, read-only, new private folder, per run-spec.json (no deploy, no model change) | CODE | step 2 | raw-run sha256 + 72/72 completeness count on #244 | 3 h after start (est. 16:30) | Codex |
| 4 | Independent grading under oracle r3, rubric r2 | CODEX | step 3 | graded sidecar hash, PASS/72, 24-question table, flags | 2 h after step 3 | Cowork |
| 5 | Ratify, compare to 11/72, name failing questions and which C2 domain or map entry to fix next | COWORK | step 4 | ratification comment + backlog update | 1 h after step 4 | Code (fix list) |
| 6 | Fix loop on failures; rerun on the next change | CODE, CODEX, COWORK | step 5 | new run id | rolling | |

### In flight (fix loop on Codex holds)
| Item | Owner | State | Next action | Due |
|---|---|---|---|---|
| #291 D3 formerDeviceIds code + tests | CODE | pushed 1459eb2, Codex HOLD was at 9b9e809 | CODEX re-verify the three regressions and the seed comment at 1459eb2 | 13:20 CDT; Cowork merges within 10 min of CLEAN |
| #290 W-101b dry-run plan | CODE | Codex HOLD at 295f224 (S06 router, rollback method) | CODE push per my D1 amendment (router parent created disabled/CANDIDATE, rollback = host statement); CODEX verify | push 13:30; verify +30 min |
| #292 W-101c withdrawn-plan record | CODE | Codex CLEAN as hold record at f0c011c | CODE refresh title/body to the withdrawn state; Cowork merges on that push | 13:00 CDT |
| K19 Opportunities/Tech ID guide section + context-map entry + test | CODE | not started | draft PR, source-first, no model call | 15:30 CDT |

### Queue behind it (Cowork plans, not started)
K14 installation and K05 trigger/action catalog guide sections; K17 stays gated on G07 (role-filtered projection and denial tests); W-101b apply is a separate later step with per-row Nate approval and needs #290 and #291 merged first.

### Nate-only, parked, none blocking today
Sealed-file placement and S5 review time (training data), host installs, ntfy topic (W-51), security files #118 #122, rotate-secrets check 1 Nov 06:00 UTC, which of the other four Home cameras exist (blocks W-59 seed removal only), saving the `cabin-cowork-orchestration` skill card.
