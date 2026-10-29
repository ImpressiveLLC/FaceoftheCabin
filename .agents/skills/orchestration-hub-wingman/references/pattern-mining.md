# Pattern mining: learning how something is really done, within a budget

Read when you must act like someone else (a stand-in, `stand-in-role.md`), find out why something keeps happening (failed deploys, held PRs, slow acks), set a number from history (an SLA, a size, a threshold), check what a rule says against what people do, or write a rule that will bind others. Nate's ask of 29 Oct 2026 was to learn patterns, interpolate them into a new role, and look for larger patterns, bounded only by a reasonable amount of time and tokens relative to the value and size of the ask. Section 2 is that bound.

## 1. What it is, and what it is not

Pattern mining reads a body of records (comments, commits, check runs, deploy logs, schedule files, board history) and states, with counts and ids, how something is actually done. The output is a table, a rule or a template that someone else can use or test.

It is mining, not model training. No weights change, nothing is fine-tuned, exported, ingested or routed; those stay gated on Nate (`SKILL.md` section 7). "Deep" means more levels of analysis (section 4), more independent readers, and more checks, never more access: private data stays where it is, and git carries counts, ids and hashes only.

Do not mine when one `gh api` call answers the question, when the decision does not depend on the pattern, or when the source is private text that must not leave its place (mine the counts there and report only counts).

## 2. Budget tiers: how deep for how much

Match the dig to the value of the answer and the cost of being wrong. The numbers are defaults set on 29 Oct 2026; change them in this file when a tier proves too small or too large, and say why in the commit.

| Tier | Use when | Time ceiling | Token ceiling (all agents) | Readers | Ends with |
|---|---|---|---|---|---|
| T0 glance | one fact or a hunch; a wrong answer is cheap to fix | 5 minutes | 30k | you | two to five lines in the reply |
| T1 profile | cover one lane for days; write a rule that binds one lane; size a number from history | 30 minutes | 150k | you, plus one blind checker at most | a profile table with counts and a method line in a doc or a PR body |
| T2 deep | cover a gate (merge, grading, deploy); a recurring failure with real cost; a rule that binds several lanes | 2 hours elapsed, across wakes | 600k | up to 4 independent readers on disjoint slices, plus one blind replication | the profile, sequence, failure and system findings, a calibration, and the decisions for Nate |
| T3 exhaustive | an audit of a whole corpus, weeks of history, training data | set by Nate | set by Nate | as agreed | as agreed; starts only on his go |

Rules:
- Say the tier and its ceilings in one line before you start ("T1, Codex profile, 30 min, 150k, one reader"). If the dig will pass the ceiling, stop at it, report what is known, and say what going further would cost and what it could change.
- A tier is a ceiling, not a target. Stop earlier at saturation (the last fifth of the records read changed none of your statements), when the decision is already made, or when the next record cannot change it.
- Escalate one tier only when a finding is both uncertain and consequential: it would change an authority, a gate, a deploy, a secret, or a number others rely on. A finding that is only uncertain gets the label "likely" and the work moves on.
- Cheapest first. Count from fields and first lines before you read bodies; read bodies for a sample and for every rare, consequential case. Estimate tokens as characters divided by four before reading a file in full.
- Time is elapsed time and a wake is delivered only after the running turn ends, so a long dig runs in subagents or short turns, never one long turn.

## 3. The loop

1. **Observe.** Fix the population in one line: which records, which period, which source, which account. Fetch metadata first (id, time, author header, first line).
2. **Count.** Classify cheaply and count. A pattern without a denominator is an anecdote.
3. **Sample.** Read bodies for a stratified sample: each class, each period, the extremes, and the records your classifier could not place. Read every instance of the rare, consequential class (every HOLD, every failure).
4. **Hypothesize.** Write each pattern as a sentence that could be false ("a CLEAN is a first line with the 40-character head and the scope, then about four lines").
5. **Test.** Try to break it: look for counterexamples in records you have not read; split by period, by author, by PR type; fit on the earlier 80% by time and check the latest 20%.
6. **Synthesize.** Write the pattern as a rule, a table row or a template with its label (section 6) and its n.
7. **Interpolate.** Apply it to a case the record does not cover (section 8) and check the interpolation against a case you do know.
8. **Record.** Section 7: where it goes, with the method line, so a later session can redo it.

## 4. Going deeper than a profile

A profile (what one record looks like) is level 1. Deeper levels find the larger patterns:

| Level | Question | Typical finding |
|---|---|---|
| 1 Record | what does one item look like (form, fields, length, vocabulary)? | the verdict is a first line with the head, then about four lines |
| 2 Sequence | what follows what, and how long does each step take? | ack, verdict, pointer; push, re-review, merge; rounds to CLEAN |
| 3 Failure | what went wrong, what caught it, what would have caught it earlier? | a deploy gate stops what PR CI cannot see; one cause behind two failures |
| 4 System | where are the queues, handoffs and gaps between written rule and practice, and how do they drift? | the header convention drifted across the period; a wake waits for a turn to end |

Techniques, each with when to use it:
- **Cluster by cause, not by symptom.** Group failures by root cause. The Spring "No default constructor found" deploy failures of 2026-10-16 and 2026-10-29 were one cause, and became one guard test (`BeanConstructorGuardTest`).
- **Timeline reconstruction.** Put one item's events in order from REST timestamps and read the gaps. #324: opened 15:49, head moved 15:55, proxy review complete 16:15:51, merged 16:16:35, review posted 16:19. The race is visible only on the timeline.
- **Contrast pairs.** Take two similar items with different outcomes (CLEAN on the first pass, HOLD on the first pass) and list only what differs.
- **Rule versus practice.** Write the written rule, count the cases that follow it, list the exceptions with ids. Exceptions are either drift (fix the practice) or a wrong rule (fix the rule through a docs PR).
- **Holdout and leave-one-out.** Fit on part of the record, predict the rest, compare. For an interpolation, hide one known case, apply the ladder, and compare with what the peer did: the miss rate is the honest error.
- **Fan-out readers.** Independent agents on disjoint slices (by period or class) with the same coding rules and 10% overlap to measure agreement. Each writes counts and ids to a file and returns the path with ten lines, not a transcript.
- **Cross-source triangulation.** Confirm a pattern in two sources that do not depend on each other (comments and CI run logs; the board and the committed schedule; the PR body and REST).
- **Blind replication.** An independent agent derives the profile or grades the same items without seeing yours; compare item by item (`stand-in-role.md` section 6).

## 5. Method: a comment census (the 29 Oct 2026 recipe)

REST only (GraphQL can be blocked) and no `--paginate`: ask for explicit pages of 100, stop at an empty page, and fail closed. A page that cannot be read is a gap, not the end of the list.

```python
import json, subprocess
def pages(path, since):
    out, p = [], 1
    while True:
        r = subprocess.run(["gh", "api", f"{path}?per_page=100&page={p}&since={since}"], capture_output=True, text=True)
        if r.returncode: raise SystemExit(f"page {p} failed: {r.stderr[:200]}")   # a gap is not an empty page
        d = json.loads(r.stdout)
        if not d: return out
        out += d; p += 1
comments = pages("repos/ImpressiveLLC/FaceoftheCabin/issues/comments", "2026-10-01T00:00:00Z")
```

Notes from the first run:
- `since` filters on the updated time, so older comments that were edited (the board) arrive too; keep rows by `created_at`.
- All agents post as one account. Identity is the header, so classify by the opening word with variants (COWORK, Cowork, CODE, CODEX, CODEX-PROXY, any case), then separate those that name a lane elsewhere in the first line from those that name none, and sample the rest.
- Exclude the stand-in's own comments from the population you profile the peer from. The 29 Oct first draft counted the proxy's `[P1]/[P2]` tags as Codex's habit; counting only Codex's own verdicts (54 in the final count) showed Codex used a severity tag once.
- Pull-request review comments (`pulls/comments`) are a separate endpoint; on 29 Oct there were none.
- Write the census to a scratch file: id, created_at, issue number and the whole first line (the 29 Oct file kept only 80 characters, which moved 55 of the 160 comments that name a lane after the opening into "none", so a recount from REST disagreed with it). Compute counts in code, not by reading. Keep the file out of git (first lines can hold private strings); commit counts and ids only.
- **Definitions used for the Codex profile (29 Oct 2026)**, so that a later agent can re-derive the counts. Population: every issue and pull-request conversation comment created from 2026-10-03T08:24Z through 2026-10-29T22:00:00Z (555). State a cutoff, because the count grows with every new comment: the first run's 540 stopped at 21:06:33Z, and a recount at 17:00 CDT found 15 more. Header word: the first word of the first line, any case, among COWORK, CODE, CODEX and CODEX-PROXY. Verdict: a comment whose first line, after an optional Codex header other than `CODEX-PROXY` (any case, up to 40 characters before the colon), starts with CLEAN or HOLD; the stand-in's four `CODEX-PROXY` verdicts before the cutoff (comments 6507423803, 6507504630, 6507947090 and 6508587684) are not Codex's and would make the count 58 instead of 54; a comment with no header in that form is attributed to Codex (the board credits those on #305 to #309 to Codex). "Independent" is that string in any case anywhere in the comment; a CI result is the word CI or the phrase "check run"; a confidence line contains "confidence" or "counter-case"; a list is a numbered or bulleted line; file:line is a path-like token followed by a colon and digits; median lines count the non-empty lines of the whole comment.
- Cost on 29 Oct: 555 comments in the final recount (540 in the first), a 62 KB first-line census file, 55 comment bodies (77 KB, a sample read, with every count computed in code over all comments), one 42-item blind calibration, and one independent review of the result that found two wrong facts (below). A T2 for a gate-bearing lane.

## 6. Statistics: what the numbers can and cannot say

- **Small n is a count, not a rate.** Report k of n with the interval. Wilson 95% intervals: 3 of 3 is 44% to 100%; 7 of 10 is 40% to 89%; 10 of 10 is 72% to 100%; 0 of 10 is 0% to 28% (the rule of three: with none seen in n tries the upper bound is about 3/n); 35 of 42 is 69% to 92%.
- **Three repeats cannot separate a 70% question from a 90% one.** The chance of 3 of 3 is 34% at a true rate of 70%, 51% at 80%, 73% at 90%. Size the repeats before the run.
- **Regression to the mean.** Items picked because they passed once will do worse on replication. The RUN-3 guards were chosen because they passed in RUN-1.
- **"Not met" is not "proved below".** 35 of 42 missed a 38 of 42 target, but its interval (69% to 92%) still contains 90%. A pre-registered rule decides the action; the interval says how much to read into it.
- **Multiple comparisons.** Test twenty patterns and one will look real by chance. Pre-register the few you care about; label the rest hypotheses.
- **Survivorship and self-contamination.** Records show what was posted, not what was tried and dropped, and they include your own earlier output.
- **Base rates.** Compare to the overall rate before reading a trait into a case (Codex's HOLD share was 18 of 54 verdicts, 33%: 12 of 30 headed, 6 of 24 headerless).
- **Drift.** Split by period and weight the latest; Codex's header form changed between 25 and 29 Oct, and 24 of its 54 verdicts carry no header (14 of them dated 28 Oct, UTC).
- **Agreement is not accuracy.** Two readers agreeing at 90% share their blind spots; report the direction of disagreements, not only the rate.
- **Labels.** Mark each statement **seen** (counted, with ids and n), **likely** (consistent, small n or one source) or **rule** (written down and followed, file cited). Never promote a label without new evidence.

## 7. Output: where a finding goes

Every dig ends with: the statements with label, n and ids; the method line (population, period, tier, readers, what was not read); the decision it supports; and its home.

| Finding | Home |
|---|---|
| a reusable profile, template or ladder | the owning reference file (this skill), same PR as the change it justifies |
| a one-off fact behind a decision | the PR body or the #244 comment that records the decision |
| live state (who holds what, the stand-in period) | the board, edited in place |
| a gap in the rules or the docs | a docs PR, and a backlog row if it is work |
| a choice only Nate can make | one question to Nate, with the default you will take |
| a null result | one line saying what was looked for, n, and that nothing was found |

Token hygiene: write corpora and results to files and read slices; hand a reader a path, not pasted text; ask readers for counts, ids and a short summary; quote at most one line of a source. Private strings, answers, prompts and claim-lane content never go into a prompt that is saved, a PR body or a comment.

## 8. Interpolation ladder

For a case the record does not cover, use the first rung that applies and say which:
1. The nearest recorded precedent in the same class (same PR type, same risk).
2. The intent of the written rule, cited by file and number.
3. The stricter of the two nearest precedents when harm is asymmetric.
4. Stop and ask Nate, always when the gap touches authority, a deploy, a secret, or anything irreversible.

Write down the rung, mark the output "interpolated", and list it where the return audit will look. Check the ladder on a known case first.

## 9. Worked example: the Codex profile (29 Oct 2026)

Ask: stand in for Codex until Saturday, covering merge-gate verdicts and C2 grading. Value: high, since both gate other work. Tier: T2.
- Population: the 555 repository comments created 3 Oct to 2026-10-29T22:00:00Z, one account (definitions in section 5); 54 Codex verdicts (36 CLEAN, 18 HOLD; 30 headed, 24 headerless), counted in code, a sample read.
- Findings (**seen**, counts in `stand-in-role.md` section 3): the verdict opens with one first line carrying the verdict word, the 40-character head and the scope, and runs about three lines in all; a `NOT_RUN` line in 11 of 54; the string "independent" in 39 of 54, usually with test counts; a closing `DONE #n ... Next owner` line on the PR verdict comment (16 of 54) and a separate headerless `DONE #n review <url> - CLEAN|HOLD <sha8>` pointer on #244 (10); an idle note about every 40 minutes while blocked; cadence of 20, 20 and 30 minutes from the committed rules.
- Corrections to the dig's own first draft: severity tags (1 of 54) and "read all N changed files" (2 of 54) are not Codex's standard (the draft had promoted them, and the other tagged comments were the stand-in's own); the header convention changed over the period; the share of comments that open with a lane name is 54% (300 of 555). The independent review of the result then found two more: the first census counted only comments that open with the Codex header, so it missed 24 headerless verdicts and 10 headerless pointers on #244; and "verdict-like" and the cutoff were never defined, so nobody could re-derive the counts. A fix made during that review (ce232d6, committed 16:57:25 CDT) then added a false sentence that Codex posted no pointer on #244: it acted on a reviewer finding without recounting it from REST, and the reviewer withdrew that finding a minute later (at 16:58, posted at 17:07); the re-review caught it. Lessons: define the population and the cutoff before counting, look for the same speaker without the marker you are counting on, and recount a reviewer's finding from REST before acting on it.
- Test: a blind agent regraded 42 of Codex's own trial grades: 35 agreed (83%, interval 69% to 92%), the 38 target was not met, the stand-in was stricter on one question and more lenient on another. Consequence: grading with two agents, one anchored to Codex's precedent and one blind, with Cowork adjudicating.
- Larger pattern (system level): the stand-in's review of #324 finished 44 seconds before the PR merged and was posted about three minutes after the merge; reviewers now append confirmed P1 and P2 findings as they go.
