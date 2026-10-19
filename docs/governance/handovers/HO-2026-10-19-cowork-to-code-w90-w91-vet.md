# HO-2026-10-19: Cowork to Claude Code, W-90 and W-91 vet (read before labeling or running the probe)

Baseline: `origin/main` 43bc9de (2026-10-19). Nate asked Cowork to vet W-90 and W-91 as specified in git and document it, while Code prepares the data. This supplements [`HO-2026-10-17-cowork-to-code-topic-taxonomy.md`](HO-2026-10-17-cowork-to-code-topic-taxonomy.md); where they differ, this file wins until W-90 and W-91 close. Nothing here changes a ratified threshold, and nothing here is a ruling. Items marked **Nate** need his confirmation; Code may proceed on the stated default and must record it in the PR.

**What was checked:** the W-90 and W-91 rows, plan sections 4 to 6, `PROTOCOLS.md`, `topics.json`, `targets.json`, `edge-tests.draft.md`, `verdict.schema.json`, `verdicts.py`, `questions.json`, `elements.json`. No W-90 or W-91 branch or PR existed at the time, so this vets the specification and the harness, not any labeling or probe output. Re-run at baseline: `questions_check.py` passes, 27 tests pass.

## Verdict

The harness is sound for what G1 asked of it. Seven things stand between it and a G2 and G3/G4 result that means what the plan says. F1 to F4 change what a gate can prove and need a decision before the work they affect; F5 to F8 are smaller.

| # | Finding | Blocks | Default Code proceeds on | Decides |
|---|---|---|---|---|
| F1 | No current Code or Cowork session can be a blind labeler | G2 | Blind by construction (below) | Nate |
| F2 | The probe cannot emit valid `duplicate_of` or `cannot_tell_apart_from`, and "open a topic" is undefined | G3, G4 | Define the opened-topic view; element targets must come from it | Code, Cowork reviews |
| F3 | G4 is per element, but the question set probes 13 elements | G4 | Staged coverage, recorded as a number | Nate |
| F4 | No controls exist yet, and 95% of a small n is a step function | G3 | Fix control counts in the W-91 PR | Nate ratifies counts |
| F5 | The human-session "screen" has no owner | G3 | Cowork builds it after the machine pass | Nate (where it lives) |
| F6 | "First-click find rate" is defined one way and computed another | G3, G4 | Report both, name them | Code |
| F7 | `elements.json` is stale after #174 | G2 | Regenerate before any label file | Code |
| F8 | Probe runs share the M920q with intent/slot training; runs are not reproducible without a manifest | G3, G4 | Run manifest; host rule | Code |

## F1. Blind labeling cannot be met by the people the plan names

`PROTOCOLS.md` section 1 says a labeler who has read `questions.json`, the proposed mapping or the review discussion declares it and is **excluded from agreement scoring**. Code wrote `questions.json`, whose `expected_topic` column is the mapping under test. Cowork wrote the plan's seed table of expected homes. The plan also names Code "the first blind labeler". As written, neither can score, and a recollection-based blindness rule cannot be audited anyway.

**Default (make blindness structural, not a promise):**
- Label with clean-context runs: fresh agent sessions or local models. A small runner assembles the prompt from an **allowlist only**: `topics.json`, `edge-tests.draft.md`, and one element record from `elements.json`. It never loads `questions.json`, `rulings.json` beyond what `edge-tests.draft.md` already states, notes, or the other labeler's file.
- Each label file records `inputs_sha256` (hash over the exact allowlisted bytes sent) next to `elements_sha256`. `saw_forbidden_material` is then false by construction and checkable.
- Two labelers from different model families, plus a local open model as the third (R6: same-family labelers share bias, so agreement can overstate reliability). Humans may label too; Nate audits the 10% sample as the plan says.
- Code and Cowork stay in the loop as authors of the instrument and as **analysts**, never as scored labelers.

Note on the audit: 10% of 154 is about 15 elements, so the 90% bar is "at most one miss"; one miss is 93%, two is 87%. Report the count, not only the percentage, and say so in the G2 report.

## F2. The probe cannot produce the verdicts the method depends on

Two linked gaps.

1. `verdicts.py` requires a `duplicate_of` or `cannot_tell_apart_from` `target` to be an **element id from `elements.json`**. The probe sees topic names and one-line descriptions only (plan section 5.2, W-91 row), so it has no element ids to name. Every such verdict would be discarded as an instrument defect. These are the verdicts that show a weak edge and that the four-cell routing table sends to "sharpen the edge", so the instrument would silently lose its most informative signal.
2. "Open a topic" is undefined. Hops count topics opened, but nothing says what an opened topic shows. With descriptions alone, a path is only an ordering and hops measure nothing about findability.

**Default:** define the opened-topic view in the W-91 PR: opening a topic lists the plain-language display labels of the elements it holds under the mapping being tested (`t1` after G2). Not data class, not definitions' rationale, not ids the person would never see. Then add one harness rule: a `duplicate_of` or `cannot_tell_apart_from` target must be an element the run actually saw in a topic it opened, else the record fails validation. Update `verdict.schema.json` notes and add a negative fixture for it. Keep this view out of the probe prompt's edge language: descriptions still carry the distinctions.

## F3. G4 says "per element"; the question set covers 13 elements

`questions.json` has 29 questions. 12 expect `no data yet`, and the remaining 17 name 13 distinct elements. G4 asks for find rate and top-two margin per element across the 154 non-conceptual elements. As built, G4 could pass while about 140 elements were labeled (G2) but never probed.

**Default (staged coverage, with the number reported):**
1. After G2, generate one problem per element that both labelers placed in a topic, from a reviewed template, and hand-review any that name the element or a topic (the section 5.1 leading-phrase rule applies).
2. Probe first: the 13 existing elements, every element on a hard edge, and every element either labeler marked `ambiguous` or disagreed on. Then a seeded stratified sample of the rest.
3. G4 reports **coverage as a count** ("probed 61 of 112 placed elements") and states that unprobed elements rest on G2 plus the audit. Run time: pilot 20 runs first and measure it; do not assume it.
4. `none`-labeled elements are not probed.

**Nate:** this changes what a G4 pass vouches for. If he prefers restating G4 per question, that is acceptable if the report says so plainly.

## F4. Controls do not exist, and small counts make the thresholds brittle

`questions.json` has no control fields. G3 needs positive controls, negative controls (planted mis-homes), unanswerable questions (12 exist) and state-invariance pairs. Negative controls need a **planted taxonomy variant** (a distinct `taxonomy_version`, e.g. `t1-planted`, with a few elements deliberately misplaced). State-invariance needs paired `start_context` states for the same problem.

The thresholds are 95% found and 90% flagged. With fewer than 20 positive controls, 95% allows **zero** misses; with 10 negatives, 90% allows one. Fix the counts in the W-91 PR so the bar means something: **at least 20 positive and 10 negative controls, at least 6 state-invariance pairs**, and say what one miss does to each rate. **Nate** ratifies the counts, as with the other targets.

## F5. The human session has no instrument or owner

`PROTOCOLS.md` section 2 specifies one-tap verdicts with a fixed reason list, 3 to 5 graders, at most 12 questions and 10 minutes each, and "the screen". No work item builds the screen, and every tap must become a schema-valid record.

**Default:** Cowork builds it once the machine pass has frozen the question set and the opened-topic view (F2), as a private hosted page that stores each tap as a verdict record and exports JSONL for `verdicts.py` to validate and commit. Graders appear only as codes; reports are by question and neighbor pair, as the protocol says. **Nate:** hosted page versus a page inside the repo's own UI. Do not start G3's human half before this exists.

## F6. Pin the metric definitions before any run

`targets.json` and plan section 5.4 define **first-click find rate** as the share of runs whose **first** topic matches `expected_topic`. `verdicts.build_record` sets `found` from the **last** topic in `path` (the topic where the verdict was given). They differ whenever a run wanders. Report both and name them: `first_click_find_rate` (from `path[0]`) and `found_rate` (from `found`). The G4 criterion keeps the ratified wording, which is first-click; `found_rate` feeds the four-cell reading. State the top-two margin formula in the report code (share choosing the top judged topic minus the second, per element, over its runs) and test it.

## F7. Regenerate the element snapshot

`python -B extract_elements.py verify` reports `elements.json` out of date: #174 added `human_need_concept`, conceptual 73 to 74. The population of 154 non-conceptual elements is unchanged, but label files pin `elements_sha256`, so run `extract_elements.py write` and record the new hash and commit before any label file is produced.

## F8. Reproducibility and the host

- Add a **run manifest** per run set (a file beside the run logs, not a field in the verdict record, whose schema is closed): model name and quantization, temperature and seed, host, prompt sha256, `topics.json` and `elements.json` sha256, `taxonomy_version`, `probe_version`.
- Intent/slot training rounds (r4 to r7, PRs #194 to #198) use the M920q. `fotc-git-and-host-routing` says not to stack general work on it during training or eval. Run the probe on the M920q only when none is active, or on `ilikethelights` with a small local model, and record the host.
- Constrained decoding is allowed (plan 5.2). Do not let the runner repair or retry a record that fails validation: it is discarded and counted.

## Do not adopt (from the pasted local-LLM pipeline)

Informational only, as Nate said. It pre-seeds R3 and R4 rulings in code and rewrites model output (`self_apply_ruling`), asks the model to report `found`, puts edge rules in the probe prompt, and lets a model "issue final R3/R4 rulings". Each breaks a harness rule: rulings are Nate's and apply at analysis time, `found` is harness-computed, and the probe sees no edge rules. Its `validate_rules` also has dead and wrong branches. `verdicts.py` already does this correctly.

## What is already sound (do not redo)

- Verdict schema and cross-field rules match plan section 5.2, with 10 valid and 14 rejected fixtures.
- 29 questions meet the section 5.1 rules; every topic and view has at least 3.
- Targets are ratified as written; R2 to R4 and R7 are settled and recorded (`rulings.json`, `targets.json`).
- R1 is answered (the naming taxonomy and its effect on Ask; D5 does not block).

## Order of work

1. F7, then F1's runner and the W-90 labeling (Code owns the runner; labels come from clean-context runs).
2. F2, F4, F6, F8 in the W-91 PR before the first scored run. Dry-run the probe on 20 runs and report time.
3. F3's question growth after G2 closes. F5's page after the machine pass.
4. Each step is its own PR from fresh `main` with the DoD governance record, and ids for any new work item come from the PR that introduces it.

## Report back with

The F1 default as built (runner, allowlist, `inputs_sha256`), the opened-topic view (F2), the control counts (F4), the pilot run time (F3), and which defaults Nate overruled.

## Closing

The PR that closes the last of W-90, W-91 and W-92 deletes this file with the 2026-10-17 handover.
