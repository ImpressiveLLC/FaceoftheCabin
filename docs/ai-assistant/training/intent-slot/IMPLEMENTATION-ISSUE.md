# Implement hybrid Ask intent/slot proposals and live entity resolution

Prepared issue body; this file does not claim an issue has been opened or work ratified.
Capability: map household language into safe, reviewable semantic proposals while
retaining grounded Ask/Tiny Helpdesk answers. Nate requested the package and PR;
runtime deployment and physical actions are separate decisions.

Source: [r1 plan and gates](README.md), baseline `409e1804311b79eafcfa15f24aeac91b588b353c`;
prior conversation `6a78fe15-e978-83ea-8986-0c3a3ddcd045`. Maintainer/domain reviewer
unassigned; no changes to existing Cowork Ratification fields.

## Implementation sequence

- [ ] Review schema, intent taxonomy, gold labels, source licenses and training
  provenance. Expand datasets and freeze independent template/entity/site holdouts.
- [ ] Export live non-secret HA/Zigbee/MQTT inventory with ontology identity,
  site/area/aliases, capabilities, availability, observation time and version.
  Filter by caller permission before model context. Never export credentials.
- [ ] Implement Java EntityResolver over that inventory: exactly-one candidate,
  capability and value/unit checks, freshness and location isolation; ambiguous,
  missing, offline or stale cases ask for clarification or reject. Log reasons.
- [ ] Add a feature-gated intent proposal route using the canonical contract;
  preserve existing question retrieval/citations and role filtering. Malformed
  or low-confidence results fail closed; model confidence is not authorization.
- [ ] Apply existing policy controls and confirm the concrete resolved plan.
  Bind approval to user, target, action parameters, plan hash and expiry;
  invalidate on inventory/plan changes and prevent replay. Never create arbitrary
  service/topic passthrough. Reuse the platform workflow engine/executor and its HA/MQTT adapters; preserve
  ownership of existing HA automations without treating HA as the sole engine.
- [ ] Add backend tests with a fake executor proving zero invocations on blocked
  requests. Cover forged approvals, prompt injection, unknown entity, wrong site,
  changed capability, unavailable devices, stale inventory, ambiguous aliases,
  unsafe ranges, negative requests and unit conversions.
- [ ] Compare prompt-only vs reviewed SFT with the existing isolated 3B pipeline.
  Capture training/export provenance and M920q resource measurements.
- [ ] Run all [evaluation gates](README.md#evaluation-gates), existing RAG
  regressions and accountable safety review before enabling any runtime route.
- [ ] Consider deterministic behavioral suggestion mining only after this path
  works; require explicit telemetry scope/retention and human-reviewed proposals.

## Acceptance and rollout

At least 95% valid output, 90% intent accuracy, 90% slot micro F1; zero accepted
invented entities, critical safety failures or execution without required
confirmation. Publish counts, per-class slices, raw outputs and failure analysis.
No model quality claim from training loss or a successful GGUF export alone.

Start shadow/proposal-only with executor disabled, then a reviewed canary for a
narrow capability set. Keep old tag/route available, document rollback and verify
actual runtime behavior separately from merged code. Refresh the corpus only
from the approved revision; record index/version evidence before claiming Ask
knows this plan. Decompose and score runtime work in the AI backlog before that
implementation PR; this starter does not allocate or self-ratify backlog items.
