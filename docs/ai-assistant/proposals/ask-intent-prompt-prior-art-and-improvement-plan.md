# Intent/slot prompt: prior art, lessons and improvement plan (r10 candidates)

Cowork, Sat 14 Nov 2026, at Nate's request (15:32 and 15:42 CST). Status: proposal. Docs only. It changes no prompt, gold row, schema, threshold or gate. Mirror: the Face of the Cabin Project doc `ask/2026-11-14-prompt-prior-art-and-lessons.md`. Git is canonical; if they differ, this file wins.

## 0. Intent and use of this document

**Intent.** Give the prompt-only track outside, checkable evidence of how others built and fixed the same thing, so candidate prompts start from reported "would do next time" lessons instead of from one person's wording. Nate asked that prompt language not rest on his or any single opinion, and that the work reach outside the corpus (GitHub, forums, open papers, open datasets).

**What it is.** A reference and a work plan. The lessons table is an input to candidate design. It is not a spec, not gold, not an adoption decision.

**What it is not.** It adds no training data, no gold rows and no gate. Outside text never enters the dev gold. Nothing here is an execution key for dev-sft-r1 or any run. A1 stays NOT_MEASURED, `release_eligible: false`, training NOT_RUN.

**How each role uses it.**

| Role | Use |
|---|---|
| Code | Builds candidates E1 to E5 (section 2) from the "Mirror" and "Customize" rows. Each PR and each round note cites the lesson row it implements and the source licence. |
| Cowork | Reads each candidate against the rows it claims, checks provenance and licence, posts the read on #244, asks Nate for adoption. Updates the outcome ledger (section 4). |
| Codex | When back (Sun 15 Nov 22:00 CT), reviews candidate PRs and may challenge any lesson row it finds unsupported. |
| Nate | Decides adoption. Decides the HWU64 share-alike question and the host for training. |

**Rules for outside material.**
1. One change per candidate, scored on the existing dev gold (49 rows, sha 1d33f622) with the existing evaluator and recorded with `round_ledger.py` like rounds r8-a to r9-b.
2. Every outside source used needs a provenance line (URL, licence, date read) in the PR. CLINC150 is CC BY (attribution). Snips is CC0. HWU64 is CC BY-SA: no reuse without Nate's licence decision. The acon96 dataset is MIT.
3. Outside rows go only to few-shot examples or a separate, labelled stress set. Never into dev gold, never into custody sets, and sealed content never goes in git, chat, cloud sync or a PR.
4. Forum posts are anecdotes. A lesson from a forum post may justify a candidate, never a claim that it works.
5. Reported numbers are the authors' own and are not reproduced by us.

## 1. Baseline and target

Dev baseline: gold 49 rows, sha 1d33f622, best prompt-only run r9-b: intent accuracy 0.837, slot micro F1 0.581, 13 critical failures. Package gates (unchanged): structured output at least 95%, intent accuracy at least 90%, slot micro F1 at least 90%, zero critical failures. Prompt-only results are development evidence, not A1.

Target of the prompt track: reduce critical failures and close the question-versus-command and clarify behaviour without lowering intent accuracy. The route is the intent/slot route (config questions reach `answer_question`, never treated as commands). It is not W-117 factual answers.

## 2. Candidates (Code, one change each, in this order)

| Id | Change | Lesson rows | Acceptance |
|---|---|---|---|
| E1 | Add an ordered decision list to `system.txt`: question first, clear device command, generic command, ambiguity gets one short clarification naming only the ambiguity, garbled input asks to repeat | ordering, ambiguity loops | critical failures not higher than r9-b; intent not lower |
| E2 | One worked example per intent, taken from existing fixtures (never sealed rows) | concrete example | same |
| E3 | Contrast pairs in fixtures: nonexistent room or device, quantity with no source, reply that must not claim an action happened. Expected: clarify or no action | forced grounding, hallucinated weather, claimed success | new rows are labelled and kept apart from the 49 until Nate approves adding them; report the candidate on the 49 and on the contrast set separately |
| E4 | Low temperature and fully described schema fields | structured output | same as E1 |
| E5 | Best of E1 to E4 on the served quantized Ollama tag, plus a small noisy, human-written, out-of-domain stress set built from CLINC150 out-of-scope utterances (CC BY), labelled separate from gold | synthetic overstates, quantization | report both; no gate claim |

Prompt changes follow the existing mirror rule: package schema, `contract.py`, `system.txt`, the Java contract and backend prompt copy stay in agreement, and existing fixtures keep passing. If a candidate would need a schema change, stop and ask Cowork.

## 3. Work orders and times (CST)

| WO | Owner | Due | What | Ready-signal |
|---|---|---|---|---|
| P-0 | Cowork | Sat 14 Nov 16:15 | Open this PR; post ACTION to Code | PR link and #244 comment |
| P-1 | Code | Sat 14 Nov 17:00 | Open the `prepare_sft` wording PR (branch code/prepare-sft-audit-wording), no threshold or check change; report rows refused by the new leading-newline rule | PR link on #244 |
| P-2 | Code | Sun 15 Nov 12:00 | E1 and E2 rounds recorded, results on #244 (counts, hashes, per-round ids) | round ids and a one-line table |
| P-3 | Code | Sun 15 Nov 20:00 | E3 fixtures plus E4 round | same |
| P-4 | Code | Mon 16 Nov 12:00 | E5 on the served quantized tag and the stress set | same |
| P-5 | Cowork | within 20 min of each result | Read candidate against its lesson rows and provenance; post read; ask Nate for adopt, hold or discard | COWORK read on #244 |
| P-6 | Cowork | Sun 15 Nov | Second research pass: Reddit and other DIY communities by thread URL; extend the lessons table in a follow-up docs PR | PR link |

Each of P-1 to P-4 needs an ack within 20 minutes of the ACTION (`got it, ETA hh:mm`). Silence ladder applies (+40 min named nudge; +60 on the critical path, one phone push to Nate). Code was silent on earlier work orders; if P-1 is missed at 17:00 Cowork posts one nudge and tells Nate once.

## 4. Outcome ledger (filled as rounds finish)

| Candidate | Round id | Intent | Slot F1 | Critical failures | Verdict (Nate) |
|---|---|---|---|---|---|
| r9-b (baseline) | r9-b | 0.837 | 0.581 | 13 | n/a |
| r9-b rerun (noise check) | 2026-11-15-r10-base | 0.837 | 0.569 | 13 | n/a |
| E1 | 2026-11-15-r10-e1 | 0.857 | 0.617 | 12 | **Hold** (Nate, 2026-11-15 01:10 CST) |
| E2 | 2026-11-15-r10-e2 | 0.837 | 0.699 | 12 | **Hold** (Nate, 2026-11-15 01:10 CST) |
| E1, E2 and base repeats | `-rep2`, `-rep3` of each | same as first runs | same (base 0.569, 0.569, 0.581) | same | repeats stable; Hold stands |
| E1b | 2026-11-15-r11-e1b | 0.816 | 0.5625 | 14 | Fails acceptance (intent below base, critical above); not adopted. Process ruling below |
| E2b (three changes together) | 2026-11-15-r11-e2b | 0.837 | 0.699 | 12 | Aggregate scores and failed-intent set equal E2 (the clarify-example wording changed, so outputs are not asserted identical on every fixture); not adopted. Process ruling below |
| Ablation control (r12, PARTIAL) | r12 (Code comment 6933053208, 17:54 CST; round files not yet in git) | 0.857 | 0.615 | 12 | Not a candidate; matched control for the leave-one-example-out ablation, no verdict. Run 1 of 9 arms done, run 2 done for 7 of 9, run 3 pending at 17:54 CST; final record owed by Code, which supersedes this row |
| E3 |  |  |  |  |  |
| E4 |  |  |  |  |  |
| E5 |  |  |  |  |  |

E1 and E2 evidence: Code's PR #455 (round files under `training/intent-slot/rounds/`), one run each on the M920q Ollama, dev gold only (sha 1d33f622), valid output 1.0. Each of base, E1 and E2 was then run three times in total (Code, 2026-11-15 01:41 CST, PR #455): intent and critical counts did not move across runs, only the base slot F1 varied (0.012), and the failed-fixture sets were identical in all three runs. The first-run differences and the per-fixture regressions are therefore real and repeatable, not noise. E1 fixed gold-07 and gold-38 and breaks gold-48 every run; E2 fixed gold-13, gold-14 and gold-38 and breaks gold-02, gold-16 and gold-43 every run (two new critical failures, to review before iterating). Neither meets any package gate. `system.txt` is unchanged; nothing is adopted.

Would do differently (E1, E2): the baseline prompts already quote one gold text ("I am cold", gold-23), which can flatter dev numbers; replace it with a non-gold example in a later round and note it here. Hand review of gold-48, gold-16 and gold-43 (not yet done) comes before E5 combines anything; the regressions repeat, so they are a property of the prompt change.

r11 evidence (Code, PR #460, 2026-11-15 14:46 CST): E1b and E2b, three runs each, identical within each candidate, dev gold only, `system.txt` unchanged. Against E1, E1b fixed one fixture (gold-48) and regressed three (gold-07, gold-35, gold-43; intent 42/49 to 40/49); gold-38 already passed E1. Codex corrected this on 2026-11-15 22:18 CST (review 6907192709); the merged round note still carries the older wording until Code's correction PR lands. E2b moved only clarification wording; gold-02, gold-16 and gold-43 still fail as in E2. Code's reading: the pull comes from the worked-example format as a whole, not from the specific lines swapped. Independent review before merge: a fresh-context reviewer re-checked the numbers against the round JSON files (proxy CLEAN, Cowork, 2026-11-15 15:23 CST).

Process ruling (Cowork acting as Nate's proxy, 24 hours from 2026-11-15 15:22 CST; Nate can veto): wording-only rounds on this tag are closed, because r10 and r11 show repeatable fixture trades with no candidate near a gate, and the literature pass (section 7a) only says that wording swings on a 49-row set are within prompt sensitivity. No further E1 or E2 variants. Next: E3 contrast fixtures (authoring only), a leave-one-example-out ablation on the current prompt (no new wording), then E4 as a descriptions-only schema candidate under `rounds/`, then E5. Prompt-only edits stay development evidence, not A1.

After each verdict Cowork adds a one-line "would do differently" note per candidate, so this document keeps the same shape as the prior art it mirrors.

## 5. Training track (parallel, blocked)

P4 on the M910 is measured not viable (Code comment 6884429419: about 19 minutes per step, bf16 emulated, fp32 does not fit). Host order from Nate (15:32): bluefin first (needs his own words in Code's session for ssh host key and login), then a free-tier GPU notebook (his account step; invented data only, never sealed rows), then park training. This session's container has no GPU, so Claude offers none. P1 to P8 stand. No purchases. No execution key is supplied here.

## 6. Prior art (what others built)

| Source | What it is | Licence / access | Useful for us |
|---|---|---|---|
| Home-Assistant-Requests-V2 (acon96, Hugging Face) | Template-generated HA requests and replies, 5 languages, several assistant personalities | MIT | Phrasing variety for commands. The card does not say it has refusals or questions-vs-commands, so check before leaning on it |
| arXiv 2502.12923, on-device LLMs for Home Assistant | Fine-tuned 0.5B and 1.5B models on the acon96 data | Paper | Synthetic scores overstated real use: about 98 to 99% synthetic, about 84 to 86% on 81 human-written prompts, 80 to 87% on 60 out-of-domain queries. 4-bit quantization hurt JSON output most. Authors name fallback for malformed or incomplete output as a gap |
| arXiv 2603.16207, "Proactive Rejection and Grounded Execution" (DS-IA) | Two-stage agent: classify an instruction valid, invalid or mixed against current home state, then verify each action by room, device and capability | Paper | Closest match to our need. Reported: baseline rejected 14% of invalid single-device requests and mapped a nonexistent "store room" dehumidifier to a "study room" device; the two-stage design raised rejection to 87%. Matches our fail-closed resolver and the rule that a bedroom constraint does not make the mech-room heater serve it |
| HA forum, "My Journey to a reliable and enjoyable locally hosted voice assistant" | One user's long-running local voice setup | Forum, public | Ordered decision hierarchy in the system prompt; concrete output example beats abstract rules |
| HA forum, "tiny local LLM as a command router" | ACTION / WEB / CHAT buckets, Python validates entity, model fills the service | Forum, public | Three-bucket routing; pre-resolving in code before prompting made casual phrasing worse; no accuracy numbers |
| HA forum, "Embedding based Voice Assistant Pipeline" | Vector match first, 1B splitter for compound commands, non-HA requests returned separately | Forum, public | A small model that never sees the entity list can split and classify; self-reported 95% of HA commands under 500 ms; no failure discussion |
| HA forum, "LLM just not calling an intent" | Users whose model says it did the action without a tool call | Forum, public | Claimed success without action; lower temperature helped partly; explicit per-device descriptions and intent mappings helped |
| Snips (CC0), CLINC150 (CC BY, has out-of-scope utterances), HWU64 (CC BY-SA, share-alike) | Public intent/slot sets from the 2207.13211 survey | As stated | CLINC150 out-of-scope utterances are the closest public match to "should not act". HWU64 share-alike needs a licence decision before reuse |
| Reddit r/LocalLLaMA, "Working with limits of smaller models" (thread id 1g1fole) | A user whose function-calling prompt worked on a hosted small model but not on Llama 3.2 3B; commenters suggest fixes | Public; read through a third-party Redlib mirror 2026-11-15, post date not shown there; no licence stated, summarised in own words | Closest match to our model size. Advice is commenters' suggestions, not tested results in the thread |
| Reddit r/homeassistant, local LLM with Whisper and Piper for HA Voice (thread id 1migi2p) | Setup and latency question for Llama 3.2 on a small GPU | Public; read through a third-party mirror 2026-11-15, post date not shown; summarised in own words | Latency measured by pipeline stage; fewer exposed entities and aliases; area context left unanswered |
| Lemmy c/selfhosted, "Intent recognition for HomeAssistant without an LLM?" (posted 20 Oct, replies to 23 Oct, about 16 comments) | Alternatives between sentence templates and an LLM | Public; read through a third-party mirror 2026-11-15; summarised in own words | Templates precise but brittle; embedding similarity and minimal-keyword matching as non-LLM options |
| Reddit coverage note | P-6 second pass | n/a | Three community threads reached by thread id through mirrors, not by search of reddit.com itself. Still not reached: threads with measured before and after numbers. Mirror pages carry no stable dates and may drop comments, so treat each row below as one anecdote, not evidence

## 7. Lessons, "would do next time"

| Observation (source) | What we do with it |
|---|---|
| The prompt carries the experience; an ordered hierarchy worked: questions first, clear device command executes, generic command resolves the area first, ambiguity gets one short clarifying question naming only the ambiguity, garbled input gets "repeat", everything else gets a silent marker (HA voice journey) | **Mirror** the ordering idea (E1). Our order is already question-versus-command first; add the explicit "name only the ambiguity, do not list options" rule to the clarify branch and test it |
| Concrete output example beats abstract rules (same) | **Mirror** (E2): one worked example per intent from fixtures, not from sealed rows |
| Models claim success without acting (HA forum, "not calling an intent") | **Customize** (E3): our route returns a proposal only and has no executor, so the equivalent risk is a reply that sounds like confirmation. Add a critical test: no phrasing of an answer may state an action happened |
| Hallucinated weather: the local intent ran with no weather entities, fell through to the LLM, which invented values from unrelated sensors (HA voice journey) | **Mirror as a test** (E3): a question about a quantity with no exposed source must produce "cannot answer" or a clarify, never a number. Fits the config-question-must-reach-answer_question boundary |
| Wrong-room action: a request for one room switched a light in another (HA voice journey) | **Mirror**: keep room as a stated constraint, never substitute (already in the express_need spec) |
| Nonexistent entity forced onto a similar real one (DS-IA) | **Mirror** (E3): contrast pairs where the named device or room does not exist; expected result is clarify or no action. Serves P6 |
| Ambiguity loops: model asks again when unsure (HA forum) | **Customize** (E1): cap at one clarification, and put the uncertainty source in the question |
| Over-deterministic pre-processing hurt casual phrasing (tiny-router thread) | **Customize**: keep resolution server-side but leave the model free on wording; no rewrite rules that strip casual language before classification |
| Small models under about 4B are frustrating for tool use (thread comment, untested there); quantization hurts structured output first (2502.12923) | **Customize** (E5): measure the served quantization, not only fp16 weights. Gate numbers must come from the served tag |
| Synthetic test sets overstate accuracy (2502.12923) | **Mirror** (E5): the held-out set must include human-written, noisy, out-of-domain prompts. Never report a synthetic-only score as a gate result |
| Lower temperature and clear field definitions help structured output (HA forum, N150 thread) | **Mirror** as a candidate (E4); dev gold only |
| Disable reasoning for short tasks (N150 thread, `/no_think` on a reasoning-capable model) | **Not applicable** to Llama 3.2 3B Instruct, which has no thinking mode. Note only |
| Reported there: fewer exposed entities may help small models; 53 entities was unreliable for a 4B model (HA voice journey; a suggestion and one anecdote, not a demonstrated improvement) | **Customize**: the Ask resolver already filters by site, room and permission server-side. Keep the model out of entity selection |
| Small models often copy the in-context examples instead of generalising; the same thread reports examples can amplify copying (r/LocalLLaMA 1g1fole, commenter experience, untested here) | **Customize** (E2b; our own E2 hand review suspected the model repeated the clarify example's reply on gold-02 and gold-43, reported but not verified, and the E1b/E2b round note says that hypothesis is not supported because swapping the example left the failures unchanged). Make examples conceptually matched but topically and verbally different from likely requests; do not give one intent a single example that shares its leading verb with common commands |
| Negative examples with a short reason might help a small model avoid copying (same thread; an untested hypothesis, not a settled lesson, and the thread's own experience shows examples can amplify copying) | **Customize** (E3, to be tested, not assumed): pair an example with a one-line "not this, because". Invented text only, never gold or sealed rows |
| Tagged sections, numbered instructions and explicit edge-case handling (same thread) | **Mirror, already done**: `system.txt` has numbered checks. E1b put the need check on its own line but was not adopted (it regressed three fixtures against E1); treat that placement as an untested idea, not a kept rule |
| Decompose a multi-step request into smaller steps (same thread) | **Already our design**: the model classifies; resolution and confirmation are server-side. Do not add multi-step reasoning to the prompt |
| Try a newer small model before heavy prompt work (same thread, commenter) | **Not applicable now**: the base model is fixed by the training contract. Note only; revisit only through a contract change that Nate rules on |
| Measure each pipeline stage before blaming the model; the thread suggests checking whether a stage is placed on the CPU, a proposed diagnostic only (the author reports GPU execution; r/homeassistant 1migi2p) | **Mirror** (E5): report M920q p50 and p95 per stage (load, prompt, generation), not one number |
| Suggested there: fewer exposed entities and aliases may help matching (same thread; not demonstrated) | **Already our design**: the resolver filters by site, room and permission server-side; aliases belong to D27 and W-68. No change |
| Templates are precise but brittle; minimal keywords and embedding similarity are cheap non-LLM options (Lemmy selfhosted thread) | **Note only**: a deterministic pre-check could one day catch garbled input before the model, but the earlier lesson that over-deterministic pre-processing hurt casual phrasing applies. Not in this plan |
| Microphone and speech-to-text quality dominate errors (same thread) | **Not applicable**: our route takes typed text, not speech |

## 7a. Lesson application tracker (kept so no lesson is dropped)

Status as of 2026-11-15 15:25 CST, except the leave-one-example-out row, added 2026-11-16 from Code's 17:54 CST partial. Applied = tested in a recorded round; Queued = assigned to a named round; Not applicable = reason given. Every row needs a status change in a docs PR when it moves.

| Lesson | Status | Where applied or why not |
|---|---|---|
| Ordered decision list, question first | Applied | E1 and E1b (r10, r11). Against E1, E1b fixed gold-48 and regressed gold-07, gold-35 and gold-43; no adoption |
| Concrete worked example per intent | Applied | E2 and E2b. Possible copying on gold-02 and gold-43 is reported, not verified; see the examples rows below |
| Examples get copied; vary leading verb and topic | Tested within bundled E2b | Aggregate scores and failed-intent set unchanged; individual effect not isolated (three changes ran together) |
| Negative example with a one-line reason | Queued | Inside the E3 contrast pairs (each pair carries a short reason); not repeated as a wording round |
| One clarification, naming only the ambiguity | Applied | E1 clarify branch |
| Need check on its own line | Tested in E1b, not adopted | E1b regressed three fixtures against E1; no kept rule |
| Replace the quoted gold-23 "I am cold" in baseline prompts with an invented line | Queued | Applied once, in one revised control prompt (see the ablation row below), not as a change that differs between arms; noted so dev numbers are not flattered |
| Leave-one-example-out ablation (matched control) | Partial, run 3 pending | Code, r12, comment 6933053208 (17:54 CST), dev gold only, one served tag, nothing sealed (Code's record says dev gold only and nothing sealed; the 49 rows and sha 1d33f622 are the dev gold named in section 3 rule 1 of this plan, not restated by Code's comment). Control: intent 0.857, slot F1 0.615, critical 12, 7 failed; against the old base it fixes gold-38 and breaks nothing, a prompt-sensitivity datum and not a gain. Deletion arms against the control (failed-set change): del1 breaks gold-08 (intent 0.837); del2 fixes gold-14, breaks gold-48 (0.857); del3 breaks gold-48 (0.837); del4 breaks gold-08 and gold-38 (0.816); del5 breaks gold-48 (0.837); del6 breaks gold-16 and gold-38, critical 15 (0.816); del7 fixes gold-07, breaks gold-16 (0.857); del8 breaks gold-16 (0.837). No arm beats the control on intent; no gate met; no adoption. Reading, labelled partial: the examples-bind lesson is supported (each deletion arm broke at least one fixture, and del2 and del7 each also fixed one), not confirmed until run 3 and not generalised beyond this set and tag. Cowork wrote this row from the partial; Code's final record supersedes it |
| Contrast pairs: nonexistent room or device, quantity with no source, reply must not claim an action, wrong-room substitution | Queued | E3 fixtures, authoring only, apart from the 49; Nate approves before any row joins gold. Also adds near-miss pairs (surface of one intent, meaning of another) per the precedent finding below |
| Low temperature | Not applicable | Already 0 in `evaluate.py`; recorded as a finding, not a candidate |
| Fully described schema fields | Queued | E4 as a descriptions-only candidate schema under `rounds/`, served schema untouched, no type, enum, required or additionalProperties change, own sha, scored on the 49 and on the E3 set |
| Measure the served quantized tag; human-written, noisy, out-of-domain stress set (CLINC150 out-of-scope) | Queued | E5 |
| Report M920q p50 and p95 per stage | Queued | E5 |
| Never report a synthetic-only score as a gate result | Standing | Every round note |
| Wrong-room action: room stays a stated constraint | Standing | express_need spec; E3 pair |
| Over-deterministic pre-processing hurts casual phrasing | Standing | No rewrite rules before classification |
| Fewer entities and aliases; decompose; newer model; STT quality; reasoning mode | Not applicable or already our design; these were suggestions in the thread, not demonstrated improvements | See section 7 rows |

Literature pass (Cowork, 2026-11-15, public papers and projects read by URL; none contacted, none reproduced by us; one anecdote or one paper each):

| Source | What it says | What we do with it |
|---|---|---|
| arXiv 2510.05152 (single-character example delimiter) | Changing only the separator between few-shot examples shifted accuracy substantially (the abstract reports about 23 percent on MMLU across several open models; the wider model span is unverified); stating the delimiter helped | Wording-level swings on a 49-row set are within prompt sensitivity, so judge candidates per fixture and on contrast slices, not on one aggregate. State the example delimiter explicitly in the prompt and record it in each round note. Different task (MMLU), so only the direction transfers |
| Few-shot examples as binding precedent (tianpan.co blog) | Models carry over the surface of the most similar example; test with near-miss slices, ablate examples one at a time, prefer rules over examples, label each example's scope | Leave-one-example-out ablation, matched design: first fix ONE revised control prompt that carries both the invented replacement for the quoted gold-23 line and the explicit example delimiter, and record its hash; every deletion arm is that control minus one example, so the only difference between an arm and the control is the deleted example. Never compare deletion arms with the unrevised baseline; near-miss pairs in E3; keep examples only for patterns hard to state as rules. Blog, not peer reviewed |
| Small local models and JSON (zimaspace vendor blog) | Flatten schemas, allow an unknown value, describe each field, validate after parsing and reject rather than invent | Supports E4 (descriptions only); our resolver already fails closed. Vendor blog, weakest source |
| acon96 home-llm (project README) | Home Assistant control by fine-tuned 270M to 3B models; the README gives no evaluation method; tool-call parsing needed auto-repair for malformed JSON | Fine-tuning is the community route for small models; supports the training-track host decision. No evaluation numbers to borrow |
| arXiv 2403.17536 (ILLUMINER) | Parameter-efficient fine-tuning matched full fine-tuning with under 6% of the training data, on an 11B model | Supports a PEFT plan when a GPU host exists; the model size is not ours, so the effect size does not transfer |

## 8. Gaps and risks
- Reddit and other DIY communities reached only partly (P-6, 2026-11-15): three threads by id through third-party mirrors, no stable dates, no measured before-and-after numbers. One fetched page carried text addressed to the reader (formatting and quoting rules); it was ignored as page content, not instructions. Treat every community row as one anecdote.
- Source numbers are reported by the authors; none are reproduced by us.
- HWU64 is share-alike: Nate decides before any reuse.
- The prompt-only evaluation runs on the existing runner; Cowork has not re-verified host, tag or runner details for E5. Code confirms them in its ack.
- Nothing here changes any gate.

## 9. Sources
- https://huggingface.co/datasets/acon96/Home-Assistant-Requests-V2/blob/main/README.md
- https://ar5iv.arxiv.org/html/2502.12923
- https://arxiv.org/html/2603.16207
- https://community.home-assistant.io/t/my-journey-to-a-reliable-and-enjoyable-locally-hosted-voice-assistant/944860
- https://community.home-assistant.io/t/experiment-using-a-tiny-local-llm-as-a-home-assistant-command-router-without-intents/995434
- https://community.home-assistant.io/t/embedding-based-voice-assistant-pipeline/991584
- https://community.home-assistant.io/t/anyone-else-have-issues-with-their-llm-just-not-calling-an-intent/845697
- https://community.home-assistant.io/t/running-a-local-llm-on-an-intel-n150-mini-pc-for-home-assistant-a-practical-journey/1007724
- https://ar5iv.labs.arxiv.org/html/2207.13211
- https://arxiv.org/pdf/2510.05152
- https://tianpan.co/blog/2026-06-03-the-few-shot-example-your-model-treated-as-binding-precedent
- https://shop.zimaspace.com/blogs/tech-ai-hub/small-local-models-hallucinate-json-output
- https://github.com/acon96/home-llm
- https://arxiv.org/abs/2403.17536v1
