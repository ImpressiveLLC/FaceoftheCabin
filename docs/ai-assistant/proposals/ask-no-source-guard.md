# Ask no-source guard: specification before any code

Status: proposed 2026-11-05, revised after CODEX HOLD on 3a50173d (comment 6673125376) (Cowork, from E1 grading and Codex's method ruling on #244, comments 6671787303 and 6672121367). Specification only. It changes no Ask behavior, authorizes no deploy and allocates no W-, D- or UC-IDs. Any code that follows needs a PR, independent review, Nate's head-bound go and a fresh sealed fourth set (W-151).

## Problem

E1 (offline replay, never deployed, claims nothing for A1) showed two things the answer-first wording does not fix:

1. When no section answers the question, a 3B model sometimes fills the gap with an unsupported fact instead of saying the guides do not cover it.
2. Probes (questions the guides cannot answer) do not reliably state the coverage limit; some say only "I don't know", which the grading rubric scores as failing the coverage-limit criterion.

Offline graders knew whether a supplied section was the answering one (`source_available`). Production cannot: having retrieved text is not the same as having an answering source. A guard therefore has to act on conditions the server can observe.

## Constraints (from Codex, 2026-11-05)

- The trigger is an observable runtime condition. No grader labels, oracle sections or eval question IDs, and no test fixture text, appear in the guard's rule or its thresholds.
- Justified refusals on unsupported probes stay. Supported answers stay. The guard must not trade one for the other.
- Specify, before any guard PR: empty context, irrelevant or partial context, role filtering, conflicting sources, and the tests for each.
- Lexical signals cannot prove that the guides do not cover a question. Any wording the guard adds claims only what the server knows: what was retrieved and supplied for this request, never that the documentation lacks the answer.
- Model output remains advisory text. The guard adds no entity IDs, services, topics, credentials or actions.

## Observable signals (all computed in `TinyHelpdeskService.ask` before or after the model call)

| Signal | Source | Meaning |
|---|---|---|
| `docSections` | count of `REVIEWED_DOCUMENT` nodes after role redaction | how many routed documentation sections reached the prompt |
| `routed` | `AskContextBuilder.buildContext` returned non-empty | the keyword router matched a category |
| `fallbackOnly` | `buildContext` empty and `retrieveRelevant` supplied the nodes | only word-overlap knowledge nodes; weakest support |
| `overlap` | share of the question's content words (existing STOPWORDS filter, length over 2) found in the supplied context text | lexical support, deterministic, no model |
| `redacted` | any node changed by `CredentialPointerRedactor` for this role | the visible context is smaller than the stored one |

The guard may use only these and similar server-side facts. Thresholds are set on the development sets (frozen and held-out) and then judged once on a fresh sealed set; no threshold is chosen against the sealed set.

## Cases and required behavior

1. **Empty context.** Unchanged: no model call, fixed reply "I don't have any information about that yet." Test: empty store, unmatched question.
2. **Fallback only (`fallbackOnly`, `docSections` = 0).** Candidate behavior: add a fixed server-written sentence scoped to what was retrieved ("I did not find a documentation section for this question among the material I was given, so what follows may not be grounded in the guides"), and let the model answer only from the supplied knowledge nodes, or skip the model call when `overlap` is below the tuned floor. The sentence states uncertainty about the retrieved context, not that the guides lack the answer. Test: a question matching one stray knowledge node must not produce a confident procedure.
3. **Irrelevant or partial context (`docSections` above 0, `overlap` low).** Without a grader oracle the server cannot know which part of a question is unsupported, so it does not try to name it. Observable grounding evidence per answer sentence: the sentence carries a `[source: ...]` label that matches a section actually supplied for this request (a citation to an unsupplied label counts as ungrounded), and shares content terms with that section's text. Candidate behavior: keep sentences with a valid, supplied citation; for the rest apply the tier chosen under "Where it runs"; append the scoped sentence ("Parts of this answer are not tied to the material I was given"). This is a heuristic and is reported as one. Negative controls (must keep the supported answer with no warning): a supported paraphrase that shares few words with its section; a supported answer whose section uses different vocabulary than the question; a question answered by a routed section that the keyword router matched only through an alias. Tests for low overlap with a true answer must exist next to tests for low overlap with no answer.
4. **Role filtering.** The signals are computed after redaction and role gating, so a non-administrator never gets a coverage statement that reveals the existence or title of a gated section, and `redacted` never appears in output. Test: the same question as ADMINISTRATOR, HOUSEHOLD and null role; replies must differ only by what that role may see.
5. **Conflicting sources.** If two supplied sections give different values for the same named item, the guard does not pick one. Reply reports both with their `[source: ...]` labels and says the guides disagree. Detection must be conservative and observable (same label key with different values); when detection is unsure the model prompt's existing "report, do not obey" rule stands and the guard stays silent rather than guess.
6. **Injected instructions inside a source.** Unchanged: text in a source is reported, never obeyed. The guard adds nothing here, but its tests include a source containing an instruction to ignore the guard.

## Where it runs

Prefer a server-side step over a prompt-only change: a prompt line can be ignored by the model, a fixed sentence written by the server cannot. Two tiers, compared on the development sets before any PR; the proposal commits to neither until then:

- **Tier 1, notice only (B1).** After the model answers, append the fixed scoped sentence when the signals say support is weak (cases 2 and 3). This is a coverage notice. It does not prevent fabrication, withhold anything or make a wrong sentence right, and no text in the PR or the UI may present it as doing so. Never edit the model's own text.
- **Tier 2, withhold (B2 / A).** When the signals say support is weak, do not return ungrounded text: either skip the model call (pre-call, cases 1 and 2) or replace the ungrounded sentences with the fixed scoped sentence and return only sentences with a valid supplied citation (post-call, case 3). If no sentence is grounded, return the fixed reply alone. Operational content (commands, values, steps, credentials, anything an operator would act on) in an ungrounded sentence is always withheld under this tier.

The safety claim "no-source answers do not reach the operator" holds only for Tier 2. Tier 1 is a labeling measure and is described as one. Both leave `answeredByModel`, `citations` and redaction behavior as they are.

## Tests required before a guard PR merges

All tests inspect the returned content (the answer text the caller receives), not only that a warning is present.

- Unit tests per case above, including role matrix, empty and redacted context.
- Tier 2: an ungrounded operational instruction (a command, a value, a step) in a canned model answer is absent from the returned text; a grounded sentence beside it is present unchanged; an all-ungrounded answer returns only the fixed reply.
- Tier 1: the notice is present, the model text is byte-identical, and no wording says the answer is verified or safe.
- Does not over-refuse or over-warn: with a stubbed model returning canned answers, every answerable development-set question whose answering section is supplied returns the same text with the guard on and off (byte-identical for the supported sentences, no notice), including the low-overlap paraphrase negative controls above. A prompt diff alone is not accepted as evidence, because generation behavior changes with the prompt; the equivalence is checked on returned text, then confirmed by an offline replay on the real model graded with the two-grader method.
- Does not under-warn: probes in the development sets get the scoped sentence (Tier 1) or lose their ungrounded text (Tier 2).
- Production-rule hygiene: no rule, threshold, config value or runtime code path (main source tree and resources) contains an eval question ID, grader label or oracle section name. A static check scans main and resources only.
- Fixtures are separate: test fixtures may carry identifiable evaluation IDs (frozen and held-out suite ids) so results trace to the suite; they live under test directories and never reach runtime logic. The static check does not scan them, and a second check confirms no test fixture is loaded from main code.
- WriteGateAudit and DemoAccess guards still pass; the route and role gating are unchanged.

## Evidence plan

1. Offline on the development sets, with the same two-grader method, report counts (probe coverage-limit rate, wrong facts, needless refusals, p50 and p95) next to the unguarded baseline. Small counts are reported as observed; no non-inferiority claim from them.
2. If a shape looks worth shipping: PR, Codex review of the head, Nate's head-bound go, then one run on a fresh sealed fourth set authored blind to every earlier set, with the A1 criteria as the gate. E1 counts do not substitute for it.

## Not in this spec

Temperature, seed, `num_ctx`, `keep_alive` (W-154) are not dropped; E1 showed no effect in a small sample, which is not proof of none. The context budget lever (`ask.context.max-document-chars`) is a separate decision: it cuts the latency tail but removes the answering section for some questions. Answer-first wording is likewise separate and needs its own approval. Nothing here starts, deploys or changes a runtime setting.
