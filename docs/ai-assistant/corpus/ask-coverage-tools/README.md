# Ask coverage tools

`phrasings.json` holds 150 plain-language questions written by the coverage-map analyst, keyed by the topic row in `../ask-coverage-map-2026-10-30.md` (A1, A2, ...). They are not drawn from usage logs or from any sealed or held-out set.

## Measuring routing and delivery: use the C1-S1 harness

`route.py`, the offline Python replica of the routing, was **retired on 2026-11-18**. This follows Cowork's C1 ruling on #244 (6979177326, point 4). The replica predated the W-151 matcher (wider word endings; tied categories all kept), so every report that used it needed a caveat.

The authority is now the backend's own matcher, through [`AskDeliveryHarnessTest`](../../../../cabin-orchestration-platform/backend/src/test/java/com/cabin/orchestrator/helpdesk/AskDeliveryHarnessTest.java). From `cabin-orchestration-platform/backend`:

```
mvn -Dtest=AskDeliveryHarnessTest test
```

It writes `target/c1-s1-delivery-report.json`. That report has two parts:
- **The development set:** for each row and audience, the first category, selected set, delivered sections, whole-section delivery, budget, leaks and known gaps. The frozen baseline is in [`../../rag/eval-results/2026-11-18-c1-s1-baseline.md`](../../rag/eval-results/2026-11-18-c1-s1-baseline.md).
- **`coverageMapPhrasings`:** these 150 phrasings run through the real matcher, with the "no category" count and ids. This replaces `route.py`'s count. At retirement both gave 16 of 150. The figure is reported, not gated.

The 120 pinned phrasings are gated separately by `AskRoutingCoverageTest` (`src/test/resources/rag/routing-pins.json`).

## Older tools kept as records

`apply_routing.py` and `write_pins.py` applied the 2026-10-31 routing proposals (PR #365) and wrote `routing-pins.json`. They carry their own copy of the matcher as it was then (plural-only endings, first winner on ties), so rerunning them reproduces that change, not today's routing. Do not use them to measure the current map. Any new pin must be checked with the backend tests.

Evidence files dated before 2026-11-18 quote `route.py` commands. They are dated records and are left as written.
