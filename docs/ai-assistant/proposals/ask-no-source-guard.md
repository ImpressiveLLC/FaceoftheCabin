# Ask no-source guard: specification before any code

Status: proposed 2026-11-05 (Cowork, from E1 grading and Codex's method ruling on #244, comments 6671787303 and 6672121367). Specification only. It changes no Ask behavior, authorizes no deploy and allocates no W-, D- or UC-IDs. Any code that follows needs a PR, independent review, Nate's head-bound go and a fresh sealed fourth set (W-151).

## Problem

E1 (offline replay, never deployed, claims nothing for A1) showed two things the answer-first wording does not fix:

1. When no section answers the question, a 3B model sometimes fills the gap with an unsupported fact instead of saying the guides do not cover it.
2. Probes (questions the guides cannot answer) do not reliably state the coverage limit; some say only "I don't know", which the grading rubric scores as failing the coverage-limit criterion.

Offline graders knew whether a supplied section was the answering one (`source_available`). Production cannot: having retrieved text is not the same as having an answering source. A guard therefore has to act on conditions the server can observe.

## Constraints (from Codex, 2026-11-05)

- The trigger is an observable runtime condition. No grader labels, oracle sections or eval question IDs, and no test fixture text, appear in the guard's rule or its thresholds.
- Justified refusals on unsupported probes stay. Supported answers stay. The guard must not trade one for the other.
- Specify, before any guard PR: empty context, irrelevant or partial context, role filtering, conflicting sources, and the tests for each.
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
2. **Fallback only (`fallbackOnly`, `docSections` = 0).** Candidate behavior: add a fixed server-written sentence that the documentation guides do not cover the question, and let the model answer only from the supplied knowledge nodes, or skip the model call when `overlap` is below the tuned floor. Test: question matching one stray knowledge node must not produce a confident procedure.
3. **Irrelevant or partial context (`docSections` above 0, `overlap` low).** Candidate behavior: keep the supported part, append the fixed coverage sentence ("The guides above do not cover the rest of this question"). The model prompt keeps the existing answer-first wording only if that wording is separately approved; this guard does not depend on it. Test: a two-part question where one part is covered; a question whose routed section is topically adjacent but silent.
4. **Role filtering.** The signals are computed after redaction and role gating, so a non-administrator never gets a coverage statement that reveals the existence or title of a gated section, and `redacted` never appears in output. Test: the same question as ADMINISTRATOR, HOUSEHOLD and null role; replies must differ only by what that role may see.
5. **Conflicting sources.** If two supplied sections give different values for the same named item, the guard does not pick one. Reply reports both with their `[source: ...]` labels and says the guides disagree. Detection must be conservative and observable (same label key with different values); when detection is unsure the model prompt's existing "report, do not obey" rule stands and the guard stays silent rather than guess.
6. **Injected instructions inside a source.** Unchanged: text in a source is reported, never obeyed. The guard adds nothing here, but its tests include a source containing an instruction to ignore the guard.

## Where it runs

Prefer a server-side step over a prompt-only change: a prompt line can be ignored by the model, a fixed sentence written by the server cannot. Two shapes, to be compared on the development sets before any PR:

- **A. Pre-call.** Decide from the signals; for cases 1 and 2 skip or constrain the model call.
- **B. Post-call.** Let the model answer, then append the fixed coverage sentence when the signals say support is weak (cases 2 and 3). Never edit the model's own text.

Both leave `answeredByModel`, `citations` and redaction behavior as they are.

## Tests required before a guard PR merges

- Unit tests per case above, including role matrix, empty and redacted context.
- A "does not over-refuse" test: every frozen-suite answerable question whose answering section is supplied keeps its answer unchanged under the guard (diff the answer-bearing prompt and the appended text, not the model output).
- A "does not under-warn" test: probes in the development sets get the coverage sentence in the output.
- A check that no rule, threshold or test fixture contains an eval question ID or grader label.
- WriteGateAudit and DemoAccess guards still pass; the route and role gating are unchanged.

## Evidence plan

1. Offline on the development sets, with the same two-grader method, report counts (probe coverage-limit rate, wrong facts, needless refusals, p50 and p95) next to the unguarded baseline. Small counts are reported as observed; no non-inferiority claim from them.
2. If a shape looks worth shipping: PR, Codex review of the head, Nate's head-bound go, then one run on a fresh sealed fourth set authored blind to every earlier set, with the A1 criteria as the gate. E1 counts do not substitute for it.

## Not in this spec

Temperature, seed, `num_ctx`, `keep_alive` (W-154) are not dropped; E1 showed no effect in a small sample, which is not proof of none. The context budget lever (`ask.context.max-document-chars`) is a separate decision: it cuts the latency tail but removes the answering section for some questions. Answer-first wording is likewise separate and needs its own approval. Nothing here starts, deploys or changes a runtime setting.
