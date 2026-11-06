# Ask source notice: specification before any code (formerly "no-source guard")

Status: proposed 2026-11-05, revised four times after review holds on 3a50173d (CODEX, comment 6673125376), 1ee01190 (CODEX-PROXY, 6674406840), e377dcf5 (CODEX-PROXY, 6674531776) and 3d9cbccd (CODEX-PROXY, 2026-11-05 evening) (Cowork, from E1 grading and Codex's method ruling on #244). Specification only. It changes no Ask behavior, authorizes no deploy and allocates no W-, D- or UC-IDs. Any code that follows needs a PR, independent review, Nate's head-bound go and a fresh sealed fourth set (W-151).

The title says "notice", not "guard": nothing here stops an unsupported answer. The absence of a notice is not assurance that an answer is supported.

## Problem

E1 (offline replay, never deployed, exploratory, claims nothing for A1) showed two things the answer-first wording does not fix:

1. When no section answers the question, a 3B model sometimes fills the gap with an unsupported fact instead of saying so.
2. Probes (questions the guides cannot answer) do not reliably state the coverage limit; some say only "I don't know", which the grading rubric scores as failing the coverage-limit criterion.

Offline graders knew whether a supplied section was the answering one (`source_available`). Production cannot: having retrieved text is not the same as having an answering source, and a lexical signal cannot prove coverage or its absence.

## What this spec does and does not claim

- **Does:** define a server-written **notice**, carried in its own response field, that states facts the server knows about one request (what kind of material was supplied, whether the answer cites it in a recognizable form). It never edits the model's text.
- **Does not:** prevent fabrication, detect unsupported sentences, withhold answers or classify operational content. A fabricated command attached to a real supplied label passes any citation check. A notice also does not say an answer is right.
- **Deferred (Tier 2, not specified here):** withholding ungrounded text needs a structured answer format, for example one claim per line each carrying its own source label, so that "which part is supported" is a parse rather than a guess. That is a prompt and contract change with its own proposal, review and approval. Until it exists, no document, UI string or PR may say the notice stops unsupported answers from reaching an operator.

## Constraints (from Codex, 2026-11-05)

- The trigger is an observable runtime condition. No grader labels, oracle sections or eval question IDs appear in any rule, threshold or runtime branch.
- Lexical signals cannot prove that the guides do not cover a question, and are not triggers here. `overlap` may be logged for development analysis only.
- Justified refusals stay: the model text is never altered, so a correct refusal is returned unchanged.
- Empty context, routed-with-nodes-only, fallback-only, documentation-supplied, role filtering, conflicting sources, injected instructions, the unreachable-model path and the intent-proposal path are each specified. Cases that fire a notice are tested for the exact notice; cases that do not are tested for its absence; cases that depend on real-model behavior (injection, conflicts) are covered only by guard-regression tests with a stubbed model, and real behavior by the offline replay.
- Model output remains advisory text. The notice adds no entity IDs, services, topics, credentials or actions.

## Observable signals (computed in `TinyHelpdeskService.ask`, after role redaction)

| Signal | Definition against the code | Meaning |
|---|---|---|
| `contextKind` | `EMPTY` (no nodes at all), `FALLBACK_ONLY` (`buildContext` returned empty, nodes from `retrieveRelevant`), `NODES_ONLY` (`buildContext` non-empty, no `REVIEWED_DOCUMENT` node), `WITH_DOCS` (at least one `REVIEWED_DOCUMENT` node) | what kind of material the model was given (matches `ask()` as it stands) |
| `modelBacked` | the answer came from the model, not the raw-facts fallback (`answeredByModel`) | whether the notice applies |
| `suppliedLabels` | the labels the prompt actually carried: `entityRef` minus the `doc:` prefix, one per `REVIEWED_DOCUMENT` node | what could legitimately be cited |
| `answerLabels` | labels found in the model answer by the extraction rule below | what the model says it used |
| `suppliedCited` / `unsuppliedCited` | `answerLabels` that do / do not match `suppliedLabels` under the matching rule below | checkable citations |

`docSections` is dropped as a separate signal (it equals the count of `suppliedLabels`). `routed` and `redacted` stay dropped.

### Label extraction and matching (normative)

- Extraction pattern: case-insensitive `\[\s*source\s*:\s*([^\]]+?)\s*\]`, with `\s` including newlines, so a label split across a line inside the bracket is found and, after whitespace removal, matches. A bracket holding several labels separated by `,` or `;` yields each label. Labels contain `.`, `#` and `/`, so the answer is never split into sentences.
- Normalization, applied to each extracted label: remove all whitespace (so a label split across a line inside the bracket is rejoined), strip surrounding backticks, quotes and trailing `.`, `,`, `;`, `:`, `)`, drop a leading `doc:`.
- Matching: case-insensitive comparison. An answer label matches a supplied label when it equals it, or equals its path part before `#` (an abbreviation to the file). Any other form, including a dropped directory, a humanized slug or a paraphrased title, does not match and is `unsuppliedCited`; N3 below says exactly that and no more.
- Forms the pattern does not recognize (for example `(source: x)`, a bare path, prose "according to the install guide") count as no citation. This is a stated limit, not a bug: the notice wording below says only that no label in the expected form was found.

## Notices

Notice text is fixed, role-independent and never echoes a label, section title or role-gated detail. At most one notice per answer.

| Code | Fires when | Fixed text |
|---|---|---|
| N0 | never fires for `EMPTY` (existing fixed reply, no model call) | unchanged: "I don't have any information about that yet." |
| N1 | `modelBacked` and `contextKind` is `FALLBACK_ONLY` or `NODES_ONLY` | "No documentation section was part of the material supplied for this answer." |
| N3 | `modelBacked`, `contextKind` is `WITH_DOCS`, and `unsuppliedCited` is non-empty | "This answer names a source label that does not match any source supplied for this question." |
| N2 | `modelBacked`, `contextKind` is `WITH_DOCS`, `unsuppliedCited` empty and `suppliedCited` empty | "No documentation label in the expected form was found in this answer." |

Order: N3 before N2; they never both fire. N3 and N2 are not evaluated for N1 contexts: with no documentation supplied, any citation is already outside the supplied material and N1 says so.

N1 is a true statement and will be the highest-volume notice: device-fact questions answered from knowledge nodes are `FALLBACK_ONLY` or `NODES_ONLY` by design. It does not warn that an answer is wrong. It has no special status: its fire rate is reported next to N2 and N3 and it is held to the same ship criterion below, so it ships only if it separates unsupported from supported answers; if it fires on most answers without doing so, it is dropped. The final N1 text is the one in the table above; the implementation PR may not reword it. In the UI the source lines already carry a per-source badge ("Verified" only for manually curated nodes, "Auto-generated" for generated ones); the notice is a separate line above the sources, and the PR must show that placement and have it reviewed before ship.

Neutral limits stated openly: for a probe routed to a documentation section whose answer cites it, no notice fires and the server cannot tell the section does not answer the question. Probe coverage-limit statements remain a prompt and evaluation matter.

## Where it runs and how it is returned

- **Separate field, not appended text.** `TinyHelpdeskAnswer` gains a `notice` field (code plus fixed text, or null); `answer` is exactly the model's output (after the client's existing trimming). The notice is rendered by the UI beside the answer. Mixing server text into the model string is rejected: it would blur what graders and clients read as the model's words and change existing `answer` equality tests.
- Response field name and UI wording are decided in the implementation PR and reviewed there; the contract change is additive.
- `sources` (the supplied node list, existing field) is unchanged in every case, including the raw-facts fallback.
- **Intent-proposal path.** `IntentProposalService` returns the `TinyHelpdeskAnswer` object itself inside its response (the existing test asserts `assertSame` on it), so the new field is serialized on `/api/helpdesk/propose` too, additively. Decision: carry it; the route is administrator-only and flag-off, and the notice is a fixed string. No proposal logic reads `notice`: a test asserts that the plan hash, target and status of a proposal are identical with the notice on and off, and the existing `assertSame` tests pass unchanged.
- **Record change.** `TinyHelpdeskAnswer` gains a fifth component. JSON is additive, Java is not: every construction site (7 today, main and test) is updated in the implementation PR. Controller routing, demo-access and write-gate policies are unaffected.
- **Raw-facts fallback (model unreachable).** `modelBacked` is false; no notice; `answeredByModel=false` stands.
- No prompt change and no pre-call skip (the empty case already skips).

## Cases

1. **`EMPTY`.** Unchanged.
2. **`FALLBACK_ONLY` and `NODES_ONLY`.** N1. Not triggered by any overlap threshold.
3. **`WITH_DOCS`.** N3, else N2, else none. A supported answer citing a supplied label gets no notice.
4. **Role filtering.** Signals are computed after redaction and role gating. A non-administrator whose matching documentation is administrator-only gets a `contextKind` of `FALLBACK_ONLY` or `NODES_ONLY` and the same N1 as any caller with no documentation; the notice cannot reveal that a gated section exists, and `suppliedLabels` never includes a gated section. The same question as ADMINISTRATOR, household adult and null role may differ in `contextKind` and notice only through what that role may see.
5. **Conflicting sources.** The server does not detect conflicts (free Markdown has no reliable key/value form); the existing prompt rule stands (report, do not obey) and the notice is silent about it.
6. **Injected instructions inside a source.** Unchanged: reported, never obeyed. Tests with a stubbed model are guard regressions only; real-model behavior comes from the offline replay.

## Tests required before a notice PR merges

All tests inspect the returned content: the `answer` text and the `notice` field.

- One unit test per `contextKind` and per notice code (N1, N2, N3, none), including the role matrix: ADMINISTRATOR, household adult, null role. Notice text is identical across roles for the same code and never contains a label or section title from a gated section.
- `answer` is byte-identical to the pre-feature answer (the model output, or the fixed empty reply, or the raw-facts text) with the feature on and off, in every case; `sources` identical; existing `TinyHelpdeskServiceTest` answer-equality assertions pass unchanged.
- Extraction and matching: labels with `.`, `#`, `/`, `-`; a label split across a line (expected: found); several labels in one bracket (each found); a repeated label; an abbreviated supplied label (path before `#`: supplied); an unsupplied label (N3); `(source: x)` and a bare path (not recognized: counts as no citation, so N2); a bare "I don't know" with no label (N2, text unchanged); order N3 over N2.
- Evaluation tooling: `scripts/ask_eval.py` `project_response` whitelists three fields and would drop `notice`; the implementation PR extends the whitelist (and its tests) so fire counts are recorded, rather than recomputing them from the answer text.
- Raw-facts fallback: no notice; unchanged text and sources.
- Intent-proposal path: output identical with the feature on and off.
- Supported answers: recorded real-model answers in the production label format (including some that actually carry a `[source: ...]` bracket, and some that do not) (captured by the existing replay harness on the development sets, committed only after review confirms they hold nothing sensitive) that cite a supplied label produce no notice. Hand-written canned answers are not accepted as evidence. The unit test reads the recording; regenerating recordings is a separate manual harness step, never part of `mvn test`.
- Production hygiene: the static check loads the actual question IDs and grader labels from the evaluation suite files (test side, so the list is exact and not guessed by pattern) and scans main Java and main resources for whole-token matches; ordinary text such as timestamps (`T12:00`) cannot match. Two data files are explicitly exempt: `rag/context-fixtures-r1.json` and `rag/context-fixtures-r2.json` (they hold question IDs, `oracleRefs` and `baseline.passingIds` for evaluation tooling and name every routed section by design). A separate test asserts that `AskContextBuilder`, `TinyHelpdeskService` and the notice code never read those evaluation fields.
- Test fixtures may carry suite IDs under test directories so results trace to the suite; they never load from main code.
- WriteGateAudit and DemoAccess guards still pass; route and role gating are unchanged.

## Evidence plan and ship criterion

1. Offline on the development sets with the two-grader method, graders judging the model `answer` only (the notice excluded, so grading cannot change by construction): report how often each notice code fires, and for fired and unfired answers the grader-assessed wrong-fact and needless-refusal counts, next to the unguarded baseline. Small counts are reported as observed; no non-inferiority claim.
2. **Ship criterion.** Measurement on the development sets is exploratory and those sets are spent as tuning data. After that exploration, and before the sealed run, the implementation PR freezes the criterion: thresholds for how often N1, N2 and N3 co-occur with graded unsupported or wrong-fact answers and how rarely with graded supported answers, per notice code. This spec fixes no numbers because none can be chosen honestly before the first measurement. The frozen criterion is applied once to the sealed result; if it fails, the proposal is dropped, not widened and not re-tuned on the sealed set.
3. If the frozen criterion looks reachable: PR, review of the head, Nate's head-bound go, then one run on a fresh sealed fourth set authored blind to every earlier set. E1 counts do not substitute for it.

## Not in this spec

Withholding or replacing ungrounded text (Tier 2) needs the structured claim format above and its own proposal. Temperature, seed, `num_ctx` and `keep_alive` (W-154) are not dropped; E1 showed no effect in a small sample, which is not proof of none. The context budget (`ask.context.max-document-chars`) is a separate decision: it cuts the latency tail but removes the answering section for some questions. Answer-first wording is likewise separate and needs its own approval. Nothing here starts, deploys or changes a runtime setting.
