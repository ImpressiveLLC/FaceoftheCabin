# Topic taxonomy evaluation and rollout plan

| Field | Value |
|---|---|
| Status | **Proposed 2026-10-17 (Cowork). Nate concurred with the plan in chat the same day; nothing here is ratified.** |
| Use case | [UC-17](../use-cases.md) (a household member switches context by topic and acts in their own words) |
| Builds on | D16 Reporting Topics IA, D27 (ontology-driven context), [#174](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/174) (needs vocabulary, light) |
| Work | W-89 to W-92 in [`backlog.md`](../backlog.md) |
| Handover | [HO-2026-10-17-cowork-to-code-topic-taxonomy](../handovers/HO-2026-10-17-cowork-to-code-topic-taxonomy.md) (temporary; deleted at sign-off) |

This plan decides how placement of data elements under human-centered topics is decided and tested. It does not decide the topics. The D16 amendment that does comes from the evidence this plan produces (W-92).

## 1. Purpose and scope

The question: which human-centered topic should each data element live under, and what evidence shows the placement is right?

**In scope:** the six data-owning topics proposed in the code review of 2026-10-17 (Temp & Humidity, Air Quality, Security & Presence, Energy, Connectivity, Device Health) and the two computed views (Alert History, Occupancy).

**Out of scope:** Llama fine-tuning, new Connectivity data capture (item H), light as a measurement type (waits on #174), and any change to Ask's 18 context categories (open decision R5).

**Baseline verified on `main` 9a823f7, 2026-10-17:** `docs/ontology.yaml` carries 73 raw, 61 derived, 20 composite and 73 conceptual elements. A repo-wide search for "question modifier" finds nothing, so R1 stands. Ask's context fixtures live in `context-fixtures-r2.json` (two copies, `docs/ai-assistant/rag/` and the backend resources). D16 and the Ask category count are re-checked at G0.

## 2. Value statements

| Lens | Statement | How we know it was delivered |
|---|---|---|
| User value | A person with a real problem ("the internet is slow", "is the cabin too humid?") reaches the right answer in one move, and can say "that is wrong" in one tap instead of giving up. | First-click find rate of 85% or better, at most 2 hops, near-zero silent abandonment in human sessions. |
| System value | One versioned taxonomy shared by the ontology, the UI and Ask, with a question set that doubles as a regression suite, so topic changes are tested instead of argued. | A `topic` value on every non-conceptual element, a measured labeling agreement, and the regression run wired into CI. |
| Risk reduction | Every miss is routed to the object that is actually broken (taxonomy, implementation or test instrument), so we never re-cluster because a sensor is missing. | 100% of non-confirming verdicts carry a reason code and a routing decision. |
| QA validation | Placement claims are tested by a blind probe with planted controls, calibrated against short human sessions, and ruled on only when the evidence passes a gate. | Gate exit criteria G1 to G5, each a measurable check with a recorded result. |

## 3. Design principles

A proposal that breaks one of these needs a recorded exception.

1. **Assertion over silence.** Every trial ends in a verdict, and leaving is never cheaper than answering.
2. **One home per element.** Cross-references are pointers, never copies.
3. **Sharp edges over defensible descriptions.** Each adjacent pair gets a binary test that two graders answer the same way, even when the result feels arbitrary.
4. **Topic follows the measurement and its scope.** A household path is Connectivity; one device's own condition is Device Health.
5. **Life-safety signals stay alarm flags.** They never feed a comfort or air-quality badge.
6. **Rules evaluate, models propose, people ratify.** Derived states are defined in the ontology and computed by rules.
7. **Evidence updates the right object.** The taxonomy, the implementation and the test instrument are three separate things to fix.
8. **Grader effort is a budget.** Abrasion is a defect in the instrument, not in the grader.
9. **The probe is an instrument, not a user.** Its asserted negatives are strong evidence; its positives carry less weight than a human's.
10. **Everything is versioned.** The question set is the regression suite, and every result cites the taxonomy and probe version that produced it.

## 4. Method: assert and eliminate

Every trial records whether the target was found and whether the grader asserted a verdict. That pair decides which object is updated. A miss eliminates a placement only when it comes with an assertion.

| Found | Asserted | Reads as | Updates | Action |
|---|---|---|---|---|
| yes | yes | Found, and "this is where I expected it" | Placement | Promote. Strongest positive. |
| yes | no | Found, but wandered or never endorsed it | Label and path | Hold. Inspect the path for label friction, extra hops or a competing candidate. |
| no | yes | Not found, and the grader says it is wrong here | Taxonomy or implementation, by reason code | Route by reason (next table). |
| no | no | Abandoned or pivoted without any observation | Test instrument and edge cost | Never demote a placement. Fix the instrument and log it against the neighbor pair. |

No/no is a built-in bias, because walking away is cheaper than arguing about where an edge is. The design answer is to make an asserted verdict the cheapest action: "can't tell this apart from X" is a one-tap wrong verdict, so fuzziness is recorded as evidence instead of lost as abandonment.

| Reason code | Meaning | Object to fix | Action |
|---|---|---|---|
| `missing_or_broken_here` | Right home, but empty, absent or broken | Implementation | Open a defect. Placement unchanged. |
| `belongs_in:<topic>` | Wrong home | Taxonomy | Demote this placement, promote the named topic. |
| `duplicate_of:<element>` | Two homes for one thing | Taxonomy | Merge or re-home one. Placement on probation. |
| `cannot_tell_apart_from:<element>` | Edge not separable from a neighbor | Taxonomy edge | Sharpen the edge test. Placement on probation until it passes. |
| `missing_information`, `missing_context`, `invalid_flow` | Grader lacked what was needed to decide | Instrument | Fix question wording, starting context or flow, then re-run. |

The loop, with the taxonomy and question set fixed within a round:

1. Fix a taxonomy version and a question set version.
2. Run the blind probe (many repeats per question) and short human sessions.
3. Score each placement by cell. Demote on asserted wrong homes, promote runners-up, and sharpen the edge for every fuzzy pair.
4. Re-run until no placement changes rank across two consecutive rounds and the gate thresholds hold.
5. Rule, record and hand over.

Controls keep the probe honest:

- **Positive controls** (obvious placements) must be found.
- **Negative controls** (deliberately mis-homed elements) must draw a `wrong_here` verdict.
- **Unanswerable-by-design questions** (no data yet, such as Connectivity today) must draw `missing_or_broken_here`, never a taxonomy miss.
- **State-invariance controls** ask one question under different states (for example air quality Good, then Poor) and must return the same topic every time.

## 5. Instruments

All targets are proposals for Nate to ratify at G1.

### 5.1 Question set

- 25 to 30 questions, each phrased as a person's problem. A question never names a topic or an element.
- Fields: `id`, `problem`, `start_context`, `expected_topic`, `expected_element` (or `no data yet`), `edge_tags`, `source` (Ask log, alert history, family, synthetic).
- Each topic and each computed view has at least 3 questions, at least a third target a hard edge, and at least 3 are unanswerable by design.
- Wording is reviewed for leading phrases before any run.

Seed questions (hypotheses; Code extends them from real Ask and alert history):

| Problem, in a person's words | Expected home (hypothesis) | Edge it tests |
|---|---|---|
| "The internet is slow. Where do I look?" | Connectivity | Connectivity vs Device Health |
| "Why do six devices show offline?" | Connectivity | Connectivity vs Device Health |
| "Is it too humid in the cabin?" | Temp & Humidity | Temp & Humidity vs Air Quality |
| "Is the air in the bedroom stale?" | Air Quality | Temp & Humidity vs Air Quality |
| "Which sensors are about to die?" | Device Health | Device Health vs Energy |
| "Did the power go out?" | Energy | Device Health vs Energy |
| "Is anyone at the cabin right now?" | Security & Presence | Presence vs Connectivity |
| "Is my phone online?" | Connectivity | Presence vs Connectivity |
| "What alerts fired last night?" | Alert History (computed view) | View vs topic |
| "Is the CO level okay?" | Ruling pending (R4) | Life-safety placement |

Also add candidate questions for three proposed groupings so the probe decides whether they earn a place: lock and gate states (Access & Perimeter), "Can I run the dryer right now?" (Energy wording), and "Why did the lights just dim?" (a possible computed view, like Alert History).

### 5.2 Verdict schema

Every probe run and every human tap produces one record, and a trial cannot end without one.

```json
{
  "qid": "Q002",
  "run_id": "t1-p1-r03",
  "probe_version": "p1",
  "taxonomy_version": "t1",
  "path": ["connectivity", "household-paths"],
  "hops": 2,
  "found": false,
  "verdict": "wrong_here",
  "reason": "missing_or_broken_here",
  "target": null,
  "runner_up": "device-health",
  "note": "optional, one line"
}
```

- `verdict` is one of `right_here`, `wrong_here`, `cannot_tell`, and is never null.
- `wrong_here` requires a reason from the table in section 4. `belongs_in`, `duplicate_of` and `cannot_tell_apart_from` also require a `target`.
- `cannot_tell` requires `missing_information`, `missing_context` or `invalid_flow`, plus a one-line note of what was missing.
- `runner_up` is required whenever the verdict is not `right_here`.
- A record that fails validation is discarded and counted as an instrument defect. The full reason set must be accepted, and every cross-field rule is enforced.

**Harness rules:**

- The harness computes `found` from the expected topic, so the probe returns only its path, verdict and reason.
- The probe prompt carries no edge rules and no rulings. The discriminating words live in the topic descriptions, which humans see too. If a description cannot carry the distinction, the description is what is under test.
- A `belongs_in` verdict stays `wrong_here`. It is tagged as derived overlap when the named topic is one the expected element feeds.
- Rulings (R3, R4) are applied at analysis time and never edit a run log.
- Constrained decoding is allowed.

### 5.3 Edge tests

Each is a binary question that two graders answer the same way. They are draft hypotheses and become part of the D16 amendment (W-92), not a separate document.

| Edge | Discriminating test | Result |
|---|---|---|
| Temp & Humidity vs Air Quality | Does it describe how the space feels (heat, moisture) or what is in the air (gases, particles)? | Humidity stays in Temp & Humidity even though it feeds Stale Air. |
| Connectivity vs Device Health | If this fails, does more than one device or household function lose something? | Yes: Connectivity. No: Device Health. |
| Device Health vs Energy | Is the action to charge or replace a device, or to change how the building uses power? | Device: Device Health. Building: Energy. House or UPS battery is ruling R3. |
| Security & Presence vs Connectivity | Does it answer "is a person here" or "is the path up"? | Person: Presence. Path: Connectivity. |
| Life-safety signal (CO, smoke, leak) | Is it an alarm state that should interrupt someone? | Yes: stays a device alarm flag, surfaces through alerts, never feeds a comfort or air badge. Home topic is ruling R4. |
| Derived state | Which question does the state answer? | Its home is that question's topic, whatever its inputs. |

### 5.4 Sharpness metrics

| Metric | Definition | Proposed target |
|---|---|---|
| First-click find rate | Share of runs whose first topic matches the expected topic | 85% or better per question |
| Hops | Topics opened before the verdict | Median of 2, max of 3 |
| Top-two margin | Share choosing the top placement minus share choosing the second, per element | 0.30 or better, or a recorded ruling |
| Inter-labeler agreement | Cohen's kappa between independent labelers, per element | 0.80 or better |
| Control pass rates | Positive controls found; negative controls flagged `wrong_here` | 95% and 90% or better |
| Abandonment by neighbor pair | Human `cannot_tell` verdicts, grouped by the pair of topics involved | No pair above 5%; any cluster is an edge defect |
| Decision time | Seconds from question shown to verdict (humans) | Baseline first, then watch the trend |

### 5.5 Human session protocol

- Up to 12 questions per person, hard stop at 10 minutes, one-tap verdict with the reason picked from the fixed list.
- No deliberation prompts. Nobody is asked to explain where an edge is.
- One closing question: "Which question felt like arguing with the screen?" The answer is logged against the neighbor pair.
- 3 to 5 graders (Nate and family), each question seen by at least 3 people.
- A session ends early when decision time roughly doubles against the person's first three answers. Backtracks between topics are logged as observations, not scored.

## 6. Stages and gates

Nothing moves past a gate without a recorded pass. G0 to G5 run in order. Only G4 repeats by design, and after 3 rounds the open pairs go to Nate for a ruling.

| Gate | Exit criteria (all must hold) | If it fails | Decides |
|---|---|---|---|
| G0 Ground truth and IDs | Governance IDs allocated by this PR (W-89 to W-92). Element list extracted from `ontology.yaml` with a version hash and class counts matching 73/61/20/73, or corrected. D16 text and Ask's 18 categories snapshotted. R1 answered. | Stop. Correct the baseline before any labeling. | Nate; Code executes |
| G1 Instrument build | 25 to 30 questions meeting the section 5.1 rules. Verdict schema validated on 10 dry-run records with none unparseable. Edge tests drafted for every listed edge. Metric targets ratified. | Rewrite questions or tests. No labeling starts. | Cowork drafts, Code validates, Nate ratifies |
| G2 Blind labeling | Every non-conceptual element labeled by two independent labelers using only D16 and the edge tests. Kappa 0.80 or better after at most one sharpening round. Every remaining disagreement ruled. A 10% audit sample reviewed by Nate with 90% or better agreement with the ruled label. Zero unassigned. | Sharpen the edge tests for the pairs in the confusion matrix, then relabel only the affected elements, blind. | Nate rules; Code and Cowork label |
| G3 Probe calibration | Every run schema-valid. Positive controls 95% or better found. Negative controls 90% or better flagged `wrong_here`. Unanswerable questions draw `missing_or_broken_here` 90% or better. At least 3 humans completed a session and the probe's positive weight is recorded. State-change questions return the same topic in every state. | Revise the probe or question wording. The taxonomy is not touched at this gate. | Code builds, Cowork reviews, Nate signs |
| G4 Placement search and ruling | Per question: find rate 85% or better over at least 10 runs, or the shortfall is explained as an implementation or instrument defect. Median hops 2, max 3. Per element: top-two margin 0.30 or better, or a ruling. No duplicate homes. No neighbor pair above 5% abandonment. Rankings stable across two rounds. R3 and R4 ruled. | Another round. After 3 rounds without convergence, Nate rules on the open pairs. | Nate rules; evidence from Code and Cowork |
| G5 Implementation and sign-off | D16 amendment ratified in git. Measurement-to-topic map committed. Derived-state template and dependency-relation spec committed. Connectivity empty state designed. Regression run wired into CI. `topic` added to `concepts.json` once #174 settles. Sign-off recorded with UC and WSJF links. Temporary handover doc deleted. | Normal PR review loop; sign-off waits. | Nate signs off |

## 7. Work items

Refs A to L are plan-local labels. Only the first wave has backlog IDs; later items are allocated by the PR that introduces them, when they reach ready.

| Ref | Item | ID | Gate | Score note |
|---|---|---|---|---|
| A | Evaluation harness: question set, verdict schema, edge-test drafts, protocol | W-89 | G1 | Everything else reuses it |
| B | Blind double-labeling and agreement report | W-90 | G2 | |
| C | Blind probe, controls and human calibration | W-91 | G3, G4 | The elimination engine |
| D | D16 amendment, measurement-to-topic map, derived-state template, from the rulings | W-92 | G5 | Blocked until G4; rank is not start order |
| E | Device Health mapping | not allocated | after G5 | Data exists per the review (battery, `/api/signal-quality`, health monitor) |
| F | Temp & Humidity and Air Quality split mapping | not allocated | after G5 | Re-map of existing measurements; `topic` field waits on #174 |
| G | Dependency relation for alert grouping | not allocated | after G5 | Groups alerts; never hides a critical one |
| H | Connectivity capture decision | not allocated | after G5 | Nothing is captured today; first decide what to measure |
| I | Connectivity empty-state design | not allocated | after G5 | Required UX state for a topic with no data |
| J | Regression run wired into CI | not allocated | after G5 | Reuses the question set |
| K | LLM multi-agent taxonomy generation | not scheduled | none | Deferred |
| L | Llama fine-tuning on public datasets | not scheduled | none | Not recommended for a closed taxonomy this small |

Recommended execution order: A, then B and C in parallel, then D, then E and F, then I and J, then G and H. Scores for the first wave are in [`backlog.md`](../backlog.md).

## 8. Roles

| Role | Does | Does not |
|---|---|---|
| Nate (stakeholder, adjudicator) | Ratifies thresholds, edge tests and rulings. Supplies real questions. Audits 10% of labels. Takes short verdict sessions. Signs off gates. | Label all elements, or arbitrate every disagreement. Only unresolved pairs and the audit sample reach him. |
| Cowork | Drafts the instruments (A). Serves as the independent second labeler (B). Reviews probe design and analysis (C). Writes methodology and proposals. | Push to this repo from a cloud session, allocate IDs outside a PR, or ratify anything. |
| Code | Verifies the baseline at G0. Builds the probe and runner. Serves as the first blind labeler. Produces run logs and reports. Commits the amendment, map and CI regression. Opens PRs with the governance record. | See Cowork's labels or the proposed mapping before labeling, or change thresholds. |
| Human graders (family) | Give one-tap verdicts in short sessions. | Explain where an edge is, or deliberate. |

Code and Cowork likely share biases, so their agreement can overstate reliability. The 10% audit sample that Nate reviews is the check, and a local open model can serve as a third labeler. It labels only and never rules. Cowork's complement work (A and B, and the review role in C) is called out for negotiation through the agreed process; the CW ids come from the iteration plan, not from this proposal.

## 9. Item inputs and outputs

[`definition-of-done.md`](../definition-of-done.md) owns the definitions of done and the PR governance record, and the handover rules live in the [README](../README.md#handovers); this table adds only per-item inputs and outputs. Output paths are proposals; Code adapts them to repo conventions.

| Item | Inputs | Outputs | Accepted when |
|---|---|---|---|
| W-89 harness | `docs/ontology.yaml` (element list, version hash), D16 text, `context-fixtures-r2.json`, alert history, real Ask logs if any, Nate's answer to R1 | `eval/taxonomy/questions.yaml`, `verdict.schema.json`, labeling protocol. Edge tests live in the D16 amendment draft. | G1 |
| W-90 labeling | Non-conceptual element list; D16 rule text and edge tests only | Two label files, agreement report with confusion matrix by topic pair, ambiguity list, audit sample sheet | G2 |
| W-91 probe | Topic names and one-line descriptions, question set, verdict schema, control definitions | Probe runner, run logs (one JSON record per trial), per-question four-cell report, control results, human calibration weights | G3, then G4 |
| W-92 amendment | Ambiguity list, rulings, G4 rankings | D16 amendment including edge tests, measurement-to-topic map, derived-state template, dependency-relation spec | G5 criteria for these artifacts |

## 10. Documentation homes

Every fact gets one home.

| Artifact | Canonical home | Rule |
|---|---|---|
| This plan | `docs/governance/proposals/` | Durable until superseded by the D16 amendment. |
| D16 amendment, including edge tests | `docs/governance/decisions/ontology-decisions.md` | The only home for the rules. The harness reads edge tests from here. |
| Measurement-to-topic map | `topic` field in `concepts.json`, after #174 | Machine-readable. Docs link to it and never copy it. |
| Derived-state template | Ontology conventions (`docs/ontology.yaml`) | Rules compute, a person ratifies. |
| Dependency-relation spec | Ontology and decision record | Containment stays `parentDeviceId`. |
| Question set and verdict schema | `eval/taxonomy/` (proposed) | Versioned. Reused for Ask regression. |
| Run logs and reports | Generated artifacts | Not hand-edited. Cited by version, never pasted into docs. |
| Sign-off record | `docs/governance/signoffs/` | Cowork only, append-only. |

## 11. Open decisions and risks

R-labels are local to this proposal. They become Q-IDs inside the D16 amendment (W-92).

| Ref | Item | Why it matters | Owner | Needed by |
|---|---|---|---|---|
| R1 | **Answered (Nate, 2026-10-17, 03:21 CDT):** "Ask question modifiers" is not a repo object. It means the naming taxonomy and how it may change Ask question design and the meaning of Ask outputs. It does not block anything. Ask alignment is therefore a consequence of the ruled taxonomy and is handled with R5 after G4. This wording supersedes the earlier G0 note that read R1 as the 18 Ask context categories. | Ask alignment is a stated goal; it follows the taxonomy rather than constraining it. | Nate | closed |
| R2 | Labeling population. Assumption: the 154 raw, derived and composite elements get a topic, and the 73 conceptual elements link to the topic used to check them. | Sets the size and meaning of the agreement score. | Nate | G1 |
| R3 | Does a house or UPS battery belong in Energy or Device Health? | Decides the Device Health vs Energy edge. | Nate | G4 |
| R4 | Where do life-safety alarm flags (CO, smoke, leak) surface while staying alarm flags? | They must never feed a comfort or air badge, but they still need a home. | Nate | G4 |
| R5 | Should Ask's 18 context categories map to the topics? Kept out of this plan. | Two vocabularies with no stated relationship. | Nate | After G4 |
| R6 | Code and Cowork likely share biases as labelers. | Agreement could look better than it is. Mitigated by the 10% audit sample. | Code, Cowork | G2 |
| R7 | All thresholds are Cowork's estimates. | A target set too tight or loose distorts every gate. | Nate | G1 |
| R8 | #174 timing. The `topic` field and light both depend on it. | Blocks the committed map at G5. | Code | G5 |
| R9 | Repo facts. Class counts verified 2026-10-17 on `main` 9a823f7; the five D16 topics and 18 Ask categories are re-checked at G0. | The baseline could be off. | Code | G0 |
| R10 | Connectivity has no captured data. Its questions use a `no data yet` expected state. | Otherwise the taxonomy is blamed for a missing capture. | Cowork | G1 |
| R11 | Human grader supply (3 to 5 people). | Fewer graders weaken the probe calibration at G3. | Nate | G3 |

## 12. Traceability

| Plan element | Traces to |
|---|---|
| Six data-owning topics and two computed views | D16 Reporting Topics (five closed topics today). Alert History and Occupancy belong to no single service. |
| Topic follows the measurement | D16 rule that each measurement maps to exactly one topic. Also removes the voltage and current dual-use workaround D16 needed. |
| Raw, derived and composite tiers, progressive disclosure | `docs/ontology.yaml` data classes (73 raw, 61 derived, 20 composite, 73 conceptual), not surfaced in the UI today per the review. |
| Containment versus dependency | `parentDeviceId` is a single-level link today. A dependency relation does not exist yet. |
| Device Health data availability | Battery capture, `/api/signal-quality` and the health monitor, per the review. |
| `topic` field and light | [#174](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/174), W-66. |
| Assert-and-eliminate, four-cell model | Nate's stated method: elimination only on asserted verdicts, and no/no as a built-in bias to design against. |
| Sharp edges and grader budget | Nate's stated heuristic: a crisp "no, that is wrong" costs people less than exhausting deliberation over an edge. |
| Rules evaluate, people ratify; safety stays alarm flags | Review finding on derived states and life-safety signals. |
