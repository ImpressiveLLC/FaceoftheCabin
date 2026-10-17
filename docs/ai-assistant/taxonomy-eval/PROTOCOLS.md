# Labeling and human-session protocols (W-89, plan sections 5.5 and 6)

Both protocols exist to make one thing cheap: giving an honest, asserted answer. They are instruments, so a protocol that
tires or confuses a grader is a defect in the protocol, never in the grader.

## 1. Blind labeling protocol (used by W-90, gate G2)

**Who labels.** Two independent labelers (Code and Cowork; a local open model may be a third). Neither sees the other's
labels or the proposed mapping before submitting. A labeler does not rule on disagreements; Nate does.

**What a labeler may use, and only this**
- `topics.json`: the six topics and two views, with their one-line descriptions.
- `edge-tests.draft.md` (later the D16 amendment): the discriminating test for each adjacent pair.
- The element's own record from `elements.json`: id, data class, layer, definition.

**What a labeler must not read**: `questions.json` (its expected topics are the mapping under test), the other labeler's file,
any earlier mapping, Cowork's or Code's notes, or the review discussion. A labeler who has seen any of these says so in the
file and the labels are excluded from agreement scoring.

**Rules**
1. Every element gets exactly one `topic`. One home per element; a related topic goes in `see_also` as a pointer and does not
   count toward agreement.
2. Use `none` when the element is not something a person asks about as a reading or a state: identity and config fields,
   secrets, infrastructure plumbing, family-hub content, UI composites. Give a `none_kind`: `identity`, `config_secret`,
   `infra`, `family_hub`, `ui` or `other`. `none` is a label like any other and is scored for agreement.
3. Views (`alert_history`, `occupancy`) own no readings. Label an element with a view only if it *is* the summary
   across time or topics.
4. Apply the edge tests literally, even when the result feels arbitrary. If two tests give different answers for one
   element, label it by the test whose pair is closest to the element's own measurement, and record `ambiguous: true`
   with a one-line reason. Do not skip it.
5. Spend no more than about a minute per element. A long deliberation is data about a weak edge: record `ambiguous`.

**Label file** (one per labeler, `labels/<labeler>.json`, generated files are not hand-edited after submission):

```json
{
  "labeler": "code",
  "kind": "agent",
  "model": "claude-sonnet-5-5",
  "taxonomy_version": "t1",
  "elements_sha256": "<sha256 from elements.json>",
  "saw_forbidden_material": false,
  "labels": {
    "humidity_percent": {"topic": "temp_humidity", "ambiguous": false, "see_also": [], "why": ""},
    "family_note": {"topic": "none", "none_kind": "family_hub", "ambiguous": false, "see_also": [], "why": ""}
  }
}
```

`kind` is `human`, `agent` or `local_model`. `elements_sha256` pins the element list the labels were made against.

**Population (R2, accepted by Nate on 2026-10-17).** The plan assumed the 154 raw, derived and composite elements all get a topic.
Reading the list, most of them are not readings a person asks about (chores, rewards, parenting schedule, tokens, MQTT
topic names, UI cards), so forcing a topic on each would measure nothing. This protocol therefore scores all 154 *and*
reports agreement twice: over the elements either labeler placed in a topic, and over the `none` / topic decision itself.
Nate accepted this at G1.

**Agreement report (G2).** Raw agreement, Cohen's kappa overall and per topic, a confusion matrix by topic pair, the
ambiguity list, and a 10 percent audit sample for Nate drawn with a fixed seed from the sha256 above, stratified by topic and
always including every disagreement the labelers could not resolve by a sharpened test.

## 2. Human session protocol (used by W-91, gates G3 and G4)

**Who.** Three to five graders (Nate and family). Each question is seen by at least three people.

**Shape.** Up to 12 questions per person, hard stop at 10 minutes. The grader sees the problem and the start context, opens
topics, and gives a one-tap verdict: *right here*, *wrong here* (then picks a reason from the fixed list), or *can't tell* (then
picks what was missing). A reason is a tap, never typing. A topic's description is visible; the edge tests are not.

**Nothing to deliberate.** Nobody is asked to explain where an edge is. "Can't tell this apart from X" is a one-tap wrong verdict,
so fuzziness is recorded as evidence rather than lost as abandonment.

**One closing question**: "Which question felt like arguing with the screen?" The answer is logged against the neighbor pair.

**Early stop.** A session ends early when decision time roughly doubles against the person's first three answers. Backtracks
between topics are logged as observations and not scored.

**Records.** Every tap produces one verdict record in the shape of `verdict.schema.json`, with `probe_version` set to
`human` and the grader's code in `run_id`. A tap that cannot produce a valid record is an instrument defect in the screen.
Decision time is recorded separately from the verdict. Individual graders are never named in a report; results are by question
and by neighbor pair.

## 3. What these protocols do not do

- They do not decide the topics. The D16 amendment (W-92) does, from the evidence.
- They do not rule. Rulings (R3, R4) are Nate's and apply at analysis time, never by editing a run log.
- They do not run anything: the probe runner (W-91) and the labeling tools (W-90) start only after G1 passes.
