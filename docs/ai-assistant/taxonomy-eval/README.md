# Topic taxonomy evaluation harness (W-89, gate G1)

The instruments for deciding where each data element lives under a human-centered topic, and for testing that placement.
The plan is [`docs/governance/proposals/2026-10-17-topic-taxonomy-evaluation.md`](../../governance/proposals/2026-10-17-topic-taxonomy-evaluation.md);
it owns every threshold, schema and gate, and this file does not repeat them. Nothing here decides the topics, and
nothing here is ratified.

| File | What it is |
|---|---|
| `topics.json` | The taxonomy under test (`t1`): six topics, two computed views, one-line descriptions (all a probe or human sees) |
| `elements.json` | Snapshot of the ontology's element list with class counts and a version hash (gate G0). Regenerate with `extract_elements.py write`; `verify` reports drift |
| `questions.json` | The question set (`q1`): 29 problems phrased as a person's words, each with an expected home, an expected element or `no data yet`, and edge tags |
| `questions_check.py` | Enforces plan section 5.1 on the question set and prints coverage |
| `verdict.schema.json`, `verdicts.py` | The verdict record: schema, every cross-field rule, the harness-computed `found`/`hops`, the four-cell reading and the routing table |
| `edge-tests.draft.md` | Drafted edge tests for every listed edge, plus three candidate groupings. Draft home of the future D16 amendment text |
| `targets.json` | The metric targets, ratified as written by Nate on 2026-10-17 |
| `rulings.json` | R3 and R4 as ruled by Nate on 2026-10-17, with the questions each decides; folded into the D16 amendment (W-92) later |
| `PROTOCOLS.md` | The blind labeling protocol and the human session protocol |
| `fixtures/` | 10 dry-run verdict records that must all validate, and 14 records that must each be rejected for a named rule |
| `test_taxonomy_eval.py` | Tests for all of the above |

## Run it

```sh
cd docs/ai-assistant/taxonomy-eval
python -B questions_check.py                       # G1 question rules
python -B -m unittest discover -s . -p 'test_*.py' # everything, including the dry-run validation
python -B extract_elements.py verify               # is elements.json still the current ontology?
```

Standard library only. (`-B` rather than `-I`: isolated mode would hide the sibling modules the scripts import.)

## G1 exit criteria, status

| Criterion (plan section 6) | Status |
|---|---|
| 25 to 30 questions meeting the section 5.1 rules | Met: 29 questions, `questions_check.py` passes (every topic and view has at least 3; 21 target a hard edge, the rule is a third; 12 are unanswerable by design, the rule is 3) |
| Verdict schema validated on 10 dry-run records, none unparseable | Met: `fixtures/dry-run-verdicts.jsonl`; the 14 negative fixtures are each rejected for the rule they break |
| Edge tests drafted for every listed edge | Met: seven edges and three candidates in `edge-tests.draft.md`; every tag used by a question has a row |
| Metric targets ratified by Nate | **Met**: ratified as written by Nate on 2026-10-17 (R7), recorded in `targets.json`; R2 (the `none` label) accepted the same day |

All four G1 exit criteria are met. **G1 passes when this PR merges**; W-90 and W-91 may start from fresh `main` after that, and not before.

## Where this differs from the plan, and why

- **Location and format.** The plan proposes `eval/taxonomy/` and `questions.yaml`. This lives under `docs/ai-assistant/` with the repo's other
  evaluation tooling (the design constraints already allow Python there) and the question set is JSON, so the harness stays
  standard-library only (PyYAML is not installed on the maintainer's workstation; CI installs it only to cross-check the element reader).
- **Optional question fields.** `ruled` (R3, R4, see `rulings.json`) and `note` (what a question was drawn from) are additions to the plan's field list.
- **A `none` label.** Most of the 154 non-conceptual elements are not readings a person asks about (see `PROTOCOLS.md`, R2).
- **Edge tests are a draft file.** Their eventual single home is the D16 amendment; this file is deleted when W-92 moves them.
- **`views` get a `view-topic` edge** because the plan's own seed questions test it, though section 5.3 lists six edges.

## Open for Nate

R2, R3, R4 and R7 are settled (Nate, 2026-10-17; R3 and R4 are in `rulings.json`). Nothing is open for G4 from the plan's own list except what the evidence turns up.
