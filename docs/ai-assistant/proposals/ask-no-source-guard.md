# Ask no-source guard: specification before any code

Status: proposed 2026-11-05, revised twice after review holds on 3a50173d (CODEX, comment 6673125376) and 1ee01190 (CODEX-PROXY, comment 6674406840) (Cowork, from E1 grading and Codex's method ruling on #244). Specification only. It changes no Ask behavior, authorizes no deploy and allocates no W-, D- or UC-IDs. Any code that follows needs a PR, independent review, Nate's head-bound go and a fresh sealed fourth set (W-151).

## Problem

E1 (offline replay, never deployed, claims nothing for A1) showed two things the answer-first wording does not fix:

1. When no section answers the question, a 3B model sometimes fills the gap with an unsupported fact instead of saying so.
2. Probes (questions the guides cannot answer) do not reliably state the coverage limit; some say only "I don't know", which the grading rubric scores as failing the coverage-limit criterion.

Offline graders knew whether a supplied section was the answering one (`source_available`). Production cannot: having retrieved text is not the same as having an answering source, and a lexical signal cannot prove either coverage or its absence.

## What this spec does and does not claim

- **Does:** define a server-written **notice** that states facts the server actually knows about one request (what was supplied, whether the answer cites it). It never edits the model's text.
- **Does not:** prevent fabrication, detect unsupported sentences, withhold answers, or classify operational content. A fabricated command attached to a real supplied label would pass any citation check; this spec does not try to catch it.
- **Deferred (Tier 2, not specified here):** withholding ungrounded text needs a structured answer format, for example one claim per line each carrying its own source label, so that "which part is supported" is a parse rather than a guess. That is a prompt and contract change with its own proposal, review and approval. Until it exists, no document, UI string or PR may say the guard stops unsupported answers from reaching an operator.

## Constraints (from Codex, 2026-11-05)

- The trigger is an observable runtime condition. No grader labels, oracle sections or eval question IDs appear in any rule, threshold or runtime code path.
- Lexical signals cannot prove that the guides do not cover a question, and they are not triggers in this spec. `overlap` may be logged for development analysis only.
- Justified refusals stay: the notice never alters model text, so a correct refusal is returned unchanged with a true, neutral notice.
- Empty context, routed-with-nodes-only, fallback-only, role filtering, conflicting sources and the unreachable-model path are each specified, with tests.
- Model output remains advisory text. The guard adds no entity IDs, services, topics, credentials or actions.

## Observable signals (computed in `TinyHelpdeskService.ask`, after role redaction)

| Signal | Definition against the code | Meaning |
|---|---|---|
| `contextKind` | one of `EMPTY` (no nodes at all), `FALLBACK_ONLY` (`buildContext` returned empty, nodes came from `retrieveRelevant`), `NODES_ONLY` (`buildContext` non-empty but no `REVIEWED_DOCUMENT` node), `WITH_DOCS` (at least one `REVIEWED_DOCUMENT` node) | what kind of material the model was given |
| `docSections` | count of `REVIEWED_DOCUMENT` nodes in the role-filtered list | documentation sections that reached the prompt for this caller |
| `answerLabels` | labels written as `[source: X]` in the model answer, found by bracket pattern, not sentence splitting (labels contain `.` and `#`) | what the model says it used |
| `suppliedCited` | `answerLabels` whose `X` equals a label supplied in this request | citations that can be checked |
| `unsuppliedCited` | `answerLabels` not supplied in this request | citations to material the model was not given |
| `modelBacked` | the answer came from the model, not the raw-facts fallback | whether the guard applies |

`routed` as a separate signal is dropped: it only restated `contextKind`. `redacted` is dropped: it is not used by any case, and the redactor lengthens administrator content, so "visible context is smaller" was not a general truth.

## Cases and required behavior

Notice text is fixed, role-independent, and never echoes a label, section title or role-gated detail. A non-administrator whose matching documentation is administrator-only is in the same `contextKind` as any caller with no documentation, so the notice cannot reveal that a gated section exists.

1. **`EMPTY`.** Unchanged: no model call, fixed reply "I don't have any information about that yet."
2. **`FALLBACK_ONLY`.** Model answer unchanged; append N1: "No documentation section was among the material supplied for this question, so this answer is not tied to the guides." Not triggered by any overlap threshold.
3. **`NODES_ONLY`.** Same N1 (the fixed set is knowledge nodes, not documentation). This closes a gap the earlier draft left: a category routed to nodes with no document was in no case.
4. **`WITH_DOCS`, `modelBacked`.**
   - `suppliedCited` empty: append N2: "This answer does not cite the documentation sections supplied with it." This is a statement about the answer, true for a correct refusal as well, and it does not claim the answer is wrong or unsupported.
   - `unsuppliedCited` non-empty: append N3: "This answer cites a source that was not supplied for this question." (no label echoed).
   - Otherwise no notice.
   Whether the answer is actually right is not asserted. A supported answer that cites a supplied label is returned with no notice and byte-identical text.
5. **Raw-facts fallback (model unreachable).** The guard does not apply; the existing fallback behavior and `answeredByModel=false` stand. `citations` stays the supplied list in every case; the notice does not change it.
6. **Conflicting sources.** The server does not detect conflicts (free Markdown has no reliable key/value form). The existing prompt rule stands: report, do not obey. The guard is silent. Tests assert the notice never echoes a label and that a non-administrator conflict case leaks no gated text.
7. **Injected instructions inside a source.** Unchanged: text in a source is reported, never obeyed. The guard adds nothing; its tests include a source containing an instruction to suppress the notice.

Neutral limits stated openly: for a probe routed to some documentation section whose answer cites it, this guard stays silent and cannot tell that the section does not answer the question. Probe coverage-limit statements remain a prompt and evaluation matter, not something this notice fixes.

## Where it runs

Post-call, server-side, in `TinyHelpdeskService.ask` after `ollamaClient.generate` returns, appending the fixed sentence to the returned answer text. No prompt change and no pre-call skip (the empty case already skips). It leaves `answeredByModel`, `citations` and redaction behavior as they are.

## Tests required before a guard PR merges

All tests inspect the returned content (the answer text the caller receives), not only that a notice string exists.

- One unit test per `contextKind` and per notice (N1, N2, N3), including the role matrix: ADMINISTRATOR, household adult, null role. Notice text must be identical across roles for the same `contextKind`, and no test output may contain a label or section title from a gated section for a non-administrator.
- Notice-only: the model text is a byte-identical prefix of the returned text in every case; no wording says "verified", "safe" or "correct".
- Supported answers: a recorded real-model answer that cites a supplied label returns with no notice and unchanged. The recorded answers come from an offline replay of the development sets run on the real model by the existing harness; hand-written canned answers are not accepted as evidence because they cannot fail. If recorded answers cannot be committed, the test regenerates them through the harness.
- Citation parsing: labels containing `.`, `#`, `/` and `-`, a label split across a line, a repeated label, an unsupplied label, and a bare "I don't know" with no label.
- Raw-facts fallback: guard inactive; unchanged text and citations.
- `Citations` list identical with and without the notice in every case.
- Production hygiene: no eval question ID, suite ID or grader label appears in main source or resources. The routing map (`context-fixtures-r2.json`) names documentation sections by design and is exempt; the ban covers question and suite IDs and grader labels, not section names. The static check scans main and resources only.
- Fixtures are separate: test fixtures may carry identifiable suite IDs under test directories so results trace to the suite; they never load from main code.
- WriteGateAudit and DemoAccess guards still pass; the route and role gating are unchanged.

## Evidence plan

1. Offline on the development sets with the two-grader method: report how often each notice fires, and for fired and unfired answers the grader-assessed wrong-fact and needless-refusal counts, next to the unguarded baseline. Because the notice does not change model text, wrong-fact counts cannot change; what is measured is whether the notice fires where wrong facts and unsupported answers are and stays quiet on supported ones. Small counts are reported as observed; no non-inferiority claim.
2. If the notice looks worth shipping: PR, review of the head, Nate's head-bound go, then one run on a fresh sealed fourth set authored blind to every earlier set. E1 counts do not substitute for it.

## Not in this spec

Withholding or replacing ungrounded text (Tier 2) needs the structured claim format above and its own proposal. Temperature, seed, `num_ctx` and `keep_alive` (W-154) are not dropped; E1 showed no effect in a small sample, which is not proof of none. The context budget (`ask.context.max-document-chars`) is a separate decision: it cuts the latency tail but removes the answering section for some questions. Answer-first wording is likewise separate and needs its own approval. Nothing here starts, deploys or changes a runtime setting.
