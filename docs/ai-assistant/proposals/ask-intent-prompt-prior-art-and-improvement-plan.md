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
| E3 |  |  |  |  |  |
| E4 |  |  |  |  |  |
| E5 |  |  |  |  |  |

E1 and E2 evidence: Code's PR #455 (round files under `training/intent-slot/rounds/`), one run each on the M920q Ollama, dev gold only (sha 1d33f622), valid output 1.0. Slot F1 moved 0.012 on an identical input (r9-b rerun), so small F1 differences are noise. E1 fixed gold-07 and gold-38 and broke gold-48; E2 fixed gold-13, gold-14 and gold-38 and broke gold-02, gold-16 and gold-43 (two new critical failures, to review before iterating). Neither meets any package gate. `system.txt` is unchanged; nothing is adopted.

Would do differently (E1, E2): the baseline prompts already quote one gold text ("I am cold", gold-23), which can flatter dev numbers; replace it with a non-gold example in a later round and note it here. Review E2's two new critical failures before combining it in E5.

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
| Not reached | Reddit and other DIY communities | n/a | Gap: search surfaced the HA forum, not Reddit threads. Second pass is P-6 |

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
| Fewer exposed entities help small models; 53 entities was unreliable for a 4B model (HA voice journey) | **Customize**: the Ask resolver already filters by site, room and permission server-side. Keep the model out of entity selection |

## 8. Gaps and risks
- Reddit and other DIY communities not reached; the HA forum is the only community source read in depth (P-6 closes this).
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
