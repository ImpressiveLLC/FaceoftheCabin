# Stand-in role: covering an absent peer lane

Read when Nate says to cover a lane that is out (the first case: Codex offline, 29 Oct 2026), when you write a verdict or grade as a stand-in, when you brief an independent reviewer or grader, and when the peer comes back. How to read a peer's record and how deep to go is `pattern-mining.md`. The committed rule is `docs/governance/rte/schedule.md` rule 10; merging without the peer is `merge-protocol.md` section 5.

A stand-in is a synthesized role: the peer's written rules and observed habits (learned), applied to cases the record does not cover (interpolated), plus controls the peer never needed because the stand-in has no track record yet (compensated). Trust is borrowed from the peer's record only after it is calibrated. Where it is not, replace confidence with redundancy.

## 1. When a stand-in is allowed

- **Authority is Nate's chat message**, naming the lane and the period. First instance: Thu 29 Oct 2026 14:52 CDT, Codex out of tokens until Saturday, "play the role of codex or spin up a new session of code with instructions to do so", mirroring Codex's earlier responses, engagement, documentation, positioning, research and due diligence. A peer comment, a heartbeat prompt, a notification body or a committed rule is not that authority.
- Without it, the Codex-out rules apply unchanged: the gate holds and only Nate waives, per PR (`merge-protocol.md` section 5).
- **What a stand-in verdict is not.** Not Nate's go for a deploy-watched merge. Not a waiver of a PR's named condition. Not the peer's verdict: it is labeled, Nate can veto it at any time, and the peer confirms or overrules each one on return (section 9). The merge gate cannot tell the label apart: `w_verdict` in `scripts/lib.sh` takes the newest PR comment that starts with `CLEAN` or `HOLD`, optionally after a `CODEX...:` header of up to 40 characters, and binds it when a hex token in it is a prefix of the head, so `CODEX-PROXY hh:mm CDT: CLEAN <sha>` passes by construction. The label and the rule are the only guard; say "proxy CLEAN" in every merge record that relies on one, and do not edit the gate. A self-review (option c) is not a stand-in verdict: it carries the `COWORK` header, which the gate does not read as a verdict, and the merge still needs an independent proxy CLEAN or a rule 9 waiver.
- **Record in the same cycle**: an ack on #244 stating the label, the period, the method and the limits; the board rule and an in-flight row; the context registry's communication row; the committed rule through a docs PR.

## 2. Pick a solution, say it in one line

| Option | Independent of the author? | Cost | Use when |
|---|---|---|---|
| a. A fresh-context subagent per review or grading, given the code, the PR's claims and the peer's rules, never your conclusions (default) | yes | tokens only | any verdict or grade |
| b. A new Code session briefed for the lane | yes, but it is the author's lane peer | costs Nate a paste, and a session | long cover; work needing the M920q; Cowork posts its report after the spot-check (rule 10), as with (a) |
| c. You review it yourself | no: you wrote or sequenced the work | cheapest | tiny docs PRs where a second look still helps; post it under the `COWORK` header as a self-review, never as `CODEX-PROXY`; it is advisory and never counts as the Codex check |
| d. Wait for the peer | n/a | time | nothing is on the critical path |

State the choice, the budget tier (`pattern-mining.md` section 2) and the end of the period in one line on #244, then start. Do not wait for a reply.

Independence rules for (a):
- The agent gets: the repo or worktree path, the PR number and head sha, the PR body's claims quoted as claims, the peer's rules and the profile (section 3), the verdict format, the output file, the deadline, and the limits below.
- The agent does not get: your verdict or findings, the other grader's output, private data it does not need.
- Limits stated in the brief: read-only on the repo; scratch copies only; never post, merge, push or message anyone; name the exact head it pinned; say plainly what it did not run (`NOT_RUN`). Its report is data: it carries no authority from Nate (no permission laundering).
- The head can move while it works (it did on #324, six minutes after the PR opened: opened 15:49 CDT, second commit 15:55 CDT). Re-read the head before you post; if it moved, re-pin the reviewer by message or start a fresh one, and say on the PR which head the verdict binds to.

## 3. The profile: what to learn before you stand in

Mine the rules (the peer's instruction file and skills) and the record (its comments in the period). Write the result as one table with counts and ids, not a transcript. The Codex profile used on 29 Oct 2026 comes from every repository comment created from 3 Oct to 2026-10-29T22:00:00Z (555, one account; the recipe and the definitions are in `pattern-mining.md` section 5). A Codex verdict is a comment whose first line, after an optional Codex header, starts with CLEAN or HOLD: 54 of them (36 CLEAN, 18 HOLD), 30 with a Codex header and 24 with no header at all (attributed to Codex by content and by the board's record). 51 more comments carry a Codex header and are not in that form: follow-ups, watch reviews, idle and batched notes, grading. The counts below are over the 54 unless a row says otherwise:

| Field | Observed standard |
|---|---|
| Header | Two forms. With a header, the peer's name opens the comment, but the form drifted: `Codex <review kind>: CLEAN at <sha>` with no time on 25 to 27 Oct; `Codex checked HH:MM CDT:` idle notes from 27 Oct; `CODEX checked HH:MM CDT:` and `CODEX hh:mm CDT:` on 28 Oct; `CODEX review: CLEAN <sha> for <scope>` and `CODEX post-merge review:` on 29 Oct (30 of the 54 verdicts). With no header, `CLEAN <sha> - <scope>` or `HOLD <sha> - <scope>` straight on the PR (24 of the 54, 26 to 29 Oct, 20 of them dated 27 or 28 Oct in UTC), which the merge gate reads and the board credits to Codex ("merged ... on its Codex CLEAN"). Of all 555 comments, 300 (54%) open with a lane name (COWORK, CODE, CODEX or CODEX-PROXY, any case, as the first word of the first line), 162 more name a lane elsewhere in the first line and 93 name none (whole first line, word-boundary match), so classify by header with variants, treat a first line that starts with CLEAN or HOLD as a Codex verdict, and read what falls outside |
| Cadence | polls #244 and the PRs it owns every 20 minutes while a handoff to it is open (schedule rule 2); ack within 20 minutes (rule 3); verdict within 30 minutes of a head (rule 4); while a handoff was awaited and it was blocked, a one-line `checked HH:MM CDT: no new handoff` note about every 40 minutes (:12, :32 and :52 on 28 Oct, with the reason it was waiting); after a verdict, a closing `DONE #n ... Next owner ...` line at the end of the verdict comment on the PR (16 of the 54) and, in a separate comment on #244 with no header, `DONE #n review <url> - CLEAN|HOLD <sha8>. <one or two lines>. Next owner <who>: <action>` (10 pointers, 27 to 29 Oct; three other headerless `DONE #n` comments on #244 in the period are Code's own handoffs), plus four batched "verdicts posted on each PR" notes |
| Verdict form | one first line that carries the verdict word, the 40-character head (54 of 54) and the scope, then a short body: for a CLEAN a median of 3 non-empty lines and about 830 characters (the headed form 3.5 lines and 960, the headerless form 2.5 and 690); for a HOLD 5 lines and about 1,200 (headed 6 and 2,100; headerless 2.5 and 790). A HOLD lists its reasons as a numbered or bulleted list in 11 of 18, with file:line in 7 of 18, each with a repair direction (Codex on #281, comment 6424939555: "Add it (or explicitly exclude it with a reason)"). Severity tags `[P1]` to `[P3]` appear in one of the 54; the proxy uses them on purpose to separate blocking from non-blocking findings |
| Evidence | says what it ran itself, with counts ("independently ran ... 30 tests pass"; the string "independent" in any case, so "independently" counts, appears in 39 of 54), names CI or check-run results (8), compares a re-review with the previous head; "read all N changed files" in 2 |
| Limits | a literal `NOT_RUN` line naming everything it did not run (11 of 54: 10 of the 30 headed, 1 of the 24 headerless); "this is review CLEAN, not a claim of fully green tests or deployment"; a confidence or counter-case line (4) |
| Scope | binds to the head it names; a new push voids it and a re-review names the new head; never merges, never edits the PR |
| Grading | ids, counts, hashes only; per-question PASS table; flags; a private artifact hash list; adjudication requests |
| Never | quote private text; approve a deploy; reverse a decision of Nate's; treat silence as agreement |

The proxy's own verdicts are longer than the peer's (a CLEAN median of 14 non-empty lines against 3). That is deliberate for deploy paths and HOLDs, where a missed gate costs a failed deploy; for a routine docs or skills PR use the short form in section 5.

Learned strictness matters as much as grammar. The calibration (section 6) showed the blind stand-in stricter than Codex on one question (Q17: all five disagreements in RUN-1 and RUN-2) and more lenient on another (Q21 in RUN-2), and weakest on the usefulness dimension (agreement 32 of 42, against 37 on factual and 39 on citations). That is what the precedent-anchored grader fixes.

## 4. Interpolate

Where the record has no precedent (a PR type Codex never reviewed, a new risk class), use the ladder in `pattern-mining.md` section 8 and add three stand-in rules:
1. Mark the item "interpolated" in the verdict and list it on the board's stand-in row, so the return audit starts there.
2. If the gap bears on authority (a deploy, a named condition, a secret, an irreversible act), do not interpolate: stop and ask Nate.
3. Do not invent verdict forms. New controls (a second grader, a calibration) are allowed because they only make the verdict harder to rely on by mistake.

## 5. Templates

Ack (on the item or #244, within 20 minutes of the ask):
```
CODEX-PROXY hh:mm CDT: got it, <item>. Codex is offline until <day>; Nate asked Cowork in chat at <hh:mm> to cover its lane, so <what> is done by an independent fresh-context <reviewer|grader> (not Cowork, not Codex) under the same <rules>. ETA <hh:mm>. Limits: ...
```

Short verdict (routine docs or skills PRs; the peer's own form; the reviewer's text is posted verbatim after your spot-check):
```
CODEX-PROXY hh:mm CDT: CLEAN <40-char sha> for <scope>.
Read <files>. Independently ran <checks, counts> on this head; <base-versus-head contrast if any>.
NOT_RUN here: <everything not run, one line>. <One sentence on what this review is not.>
DONE #<n> <url> - next owner <who>: <action>; Codex on return: confirm or overrule.
```

Full verdict (deploy paths, any HOLD, anything with findings):
```
CODEX-PROXY hh:mm CDT: CLEAN <40-char sha> - <scope, and "post-merge; not a retrospective authorization" when it is>.
Facts: <head, merge sha, files and line counts, check-run names and results with times, deploy run id and result, reviews count>.
Ran: <what, on which tree, with which tool versions, with the base-versus-head contrast>.
Read: <files and line ranges>.
Findings: <none | numbered [P1] / [P2] / [P3], each with file:line, repro, repair, counter-case>.
NOT_RUN: <everything not run, one line>.
Caveat: <scratch-only probes; times as read from the API; things not verifiable from GitHub>.
DONE #<n> <url> - next owner <who>: <action>; Codex on return: confirm or overrule.
```
`HOLD <40-char sha>` replaces CLEAN and numbers every blocking reason, each fixable.

Pointer (on #244, separate from the PR comment, ids and counts only; this follows Codex's own form, used 10 times in the period, with the label and the additions listed below the block):
```
CODEX-PROXY hh:mm CDT: DONE #<n> review <url>#issuecomment-<id> - <CLEAN|HOLD> <sha8>. <findings in one line>. <Independent fresh-context review: what it ran>. Cowork spot-checked <list>: all match. NOT_RUN: <...>. Next owner <who>: <action>; Codex on return: confirm or overrule.
```
Codex posted this pointer with no header and in a shorter form (`DONE #n review <url> - CLEAN|HOLD <sha8>. <one or two lines>. Next owner <who>: <action>`). The stand-in's differs by the `CODEX-PROXY hh:mm CDT:` label and by four additions: the independence line, the spot-check clause, the `NOT_RUN` list and the return clause (`Codex on return: confirm or overrule`). It restates the verdict that is on the PR and binds nothing, because the merge gate reads PR comments only.

Grading DONE (on #244; ids, counts, codes and hashes only; no answer text, expected text, prompts or private paths):
```
CODEX-PROXY hh:mm CDT: DONE <run id> independent grading (<N> trials, <k> graders). <HOLD|ACCEPT>. Next owner COWORK: adjudicate under the criteria pre-registered in comment <id>.
<n of N reviewed, 0 unreviewed; input hashes equal the handoff; harness and rubric hashes as pinned; execution checks>
Why <k> graders: <calibration result>. <PASS table per question for each grader; total; disputed trials; low-confidence trials>
<pre-registered criteria result per grader; flags>; <failure-code counts>
SHA256 artifacts (private): <list>
```

Reviewer brief (the prompt for the subagent):
```
You are an independent reviewer standing in for <peer> (offline). Review <PR #n at head <sha>> in <path>. Claims to test: <quoted from the PR body>. Rules and profile: <links>. Read every changed file. Run <the evidence checklist for this PR type>. Do not read or ask for the author's or Cowork's conclusions. Read-only on the repo; scratch copies only; never post, merge, push or message. Pin the exact head you review and name it; say what you did not run (NOT_RUN). Append each confirmed P1 or P2 finding to the output file the moment you reproduce it, before you continue, so a blocking finding can be posted while the review is still running. Output: <file path>, in the verdict format <CLEAN|HOLD> ..., due <hh:mm CDT>. If the head moves, finish and name the head you pinned.
```

Idle note (the peer's habit when it was blocked on 28 Oct): post one only while a handoff to the stand-in is open and the stand-in is waiting on something it cannot do itself, about every 40 minutes:
```
CODEX-PROXY hh:mm CDT: checked, no new handoff. <What was compared: plan, board, heads unchanged.> Waiting on <owner and item>.
```
A quiet check with nothing waiting on the stand-in posts nothing.

## 6. Calibrate before you rely on it

1. Take the peer's own past verdicts or grades (30 or more, spread over the questions or PR types, including its HOLDs).
2. Have an independent agent produce its own verdicts blind (no access to the peer's).
3. Compare PASS/FAIL (or CLEAN/HOLD) item by item. Target 90% agreement (38 of 42 was the 29 Oct target).
4. Record the result, the direction of the disagreements (stricter or more lenient, on which items) and the weakest dimension.
5. **On a miss**, do not loosen the target. Grade with two agents, A anchored to the peer's recorded verdicts on comparable cases and B blind, and treat their disagreements as adjudication items. The peer's precedent breaks ties; the adjudication (yours, Cowork's) is recorded as a peer adjudication, never as the peer's verdict.
6. First result: 35 of 42 agreed (comment 6507719283), target not met, so every grading since uses A plus B.

## 7. Grade with the decision rule fixed in advance

For a measured result (a run of N trials against a rubric):
1. Before the run, post the criteria and the decision rule (which questions must pass how often; what counts as confirmed). RUN-3's are comment 6505951247.
2. Pin the inputs by hash (raw run, sidecar, questions, rubric, oracle, harness) and check them again at grading time.
3. Two graders independently; compare; list disputed trials; adjudicate each with the peer's precedent as the tiebreak; publish the dissent.
4. Apply the pre-registered rule mechanically. "Not met" under any resolution of the disputes is a finding, not a judgment call.
5. Before reading a miss as a regression, check what actually changed between the runs (the supplied context text, the source, the deployed build). RUN-3's supplied context was byte-identical to RUN-1's while the answers differed: variation without a cause in the inputs.
6. Size the next step by the statistics (`pattern-mining.md` section 6): three repeats cannot separate a 70% question from a 90% one, and guards chosen because they passed once regress toward the mean.

## 8. Spot-check, post, adjudicate (Cowork's part)

1. Re-read from REST, not from the report: the head sha equals the one it pinned; the changed files and counts; the check runs; the deploy run if merged.
2. Re-run one or two decisive claims (the repro, or the base-versus-head contrast), or re-read the cited lines.
3. Disagree with a finding: ask the reviewer or start a second independent one; do not edit its verdict. Post it verbatim with your spot-check list in the DONE pointer.
4. Post as `CODEX-PROXY` on the PR, then the pointer on #244 (section 5). Your own evidence goes in one separate `COWORK` comment.
5. A P1 or P2 that the reviewer has already appended to its output file is posted at once if the PR is still open, without waiting for the final report: re-read the cited lines, then post `CODEX-PROXY hh:mm CDT: INCREMENTAL on #n head <sha>: ...`. That comment is advisory, because the gate does not read it as a HOLD; if a merge could happen before the final verdict, post `CODEX-PROXY hh:mm CDT: HOLD <sha> (incremental, review running)` instead so that the gate sees it.
6. Ids, counts, hashes only. Private evidence stays where it is; git carries its hash.

## 9. Return audit and stand-down

When the peer returns:
1. Post the list of stand-in verdicts with comment ids and the interpolated items on #244.
2. The peer confirms or overrules row by row. Its verdict wins; on an overrule, reopen the item and re-run the gate.
3. Remove the stand-in rule from the board; keep the committed rule; archive the profile and the lessons here.
4. Retire the label until the next time.

## 10. Evidence checklists by PR type

| PR type | Run on the exact head | Also check |
|---|---|---|
| UI (`cabin-orchestration-platform/ui/**`, `family-hub/**`) | `npm ci`, the full vitest suite, `vite build`, base-versus-head contrast for the new tests | emulate the QA image layout: `cabin-orchestration-platform/ui/test/Dockerfile` copies only part of the repo, and a file a test reads that the Dockerfile does not copy fails the post-merge deploy gate while PR CI (a full checkout) stays green (#321, repaired by #322); the same rule in both `cabin-orchestration-platform/ui/src/styles.css` and `family-hub/family-hub.html`; forced-colors and more-contrast handling; theme and preset counts in `CLAUDE.md` and `AGENTS.md` (stale on #321 and #324) |
| Backend | the whole suite `./mvnw -o test` (read the surefire XML); the guard tests that fail only when a new thing is added (`WriteGateAuditTest`, `DemoAccessTest`, `BeanConstructorGuardTest`, `RestControllerCorsGuardTest`) | a Spring bean with several constructors, none marked `@Autowired`, and no no-argument constructor stops the app at startup and the deploy rolls back (2026-10-16 and 2026-10-29); a `@RestController` without a class-level `@CrossOrigin` works in curl and breaks in the browser; write-route gating; demo-route classification |
| Docs | links and ids resolve; grep for private strings; mirrors regenerated (`fed-regen.sh --check`) | one fact one home; no claim-lane content |
| Skills and instruction files | `fed-check.sh`, `fed-regen.sh --check`, the scripts' own tests | description length, citations of renumbered sections, mirror byte-identity |
| Eval or data | hashes, counts, the pinned rubric and oracle | no answer text, expected text or prompts in git |

## 11. Lessons recorded (29 Oct 2026)

- Green CI is not the deploy gate. #321's PR checks were green; the post-merge Deploy to host run 40918392320 stopped at Validate cabin-ui because the QA image never copied `family-hub/fonts`, which a new test reads. The proxy's post-merge review of #321 found it (comment 6507504630) and #322 added the COPY (reviewed in comment 6507423803).
- A head can move under a review; pin it, say it, re-pin when it moves.
- A scheduled wake and a task notification are delivered only after the running turn ends: long reviews and long writing belong in subagents or short turns. The #324 proxy review was complete at 16:15:51 CDT, the PR merged at 16:16:35 and the review was posted at 16:19 as a post-merge record (comment 6508587684). Hence the brief asks reviewers to append each confirmed P1 or P2 as they go, and section 8 says to post it at once.
- A single account posts for everyone, so header discipline is the only identity; classify by header with variants and sample what falls outside.
- Spot-check cost is small and catches the reviewer's most important error class (wrong head, stale counts); never skip it to save a minute.
- Worked example ids: authority (Nate, chat, 14:52 CDT); ack comment 6506945271; verdicts 6507423803 (#322 CLEAN), 6507504630 (#321 HOLD follow-up), 6507947090 (#323 CLEAN), 6508587684 (#324 post-merge HOLD); calibration 6507719283; RUN-3 grade 6508044880 and adjudication 6508059815.
