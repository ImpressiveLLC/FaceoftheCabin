# Shared knowledge contributions

## Doc 27 environmental content

[Environmental user guide](user-guide/environmental-nodes.md) is prepared within the
reviewed-document allowlist. Adding it does not select it in AskContextBuilder.
The [handoff](../governance/handovers/HO-2026-10-16-doc27-poc2.md) assigns category/ref
wiring and answer/role tests to implementation. Preserve ordered/commissioned status,
measurement provenance and the distinction between a topic relationship and a command.
Do not ingest frozen acceptance answers as retrieval source content.

This is a repository procedure usable now by authorized human operators/installers/maintainers, Claude collaborators and Codex collaborators. It is **not a claim that the future in-app review/refresh workflow exists**. GitHub remains the canonical artifact/change record. No model is the sole keeper of reusable facts.

## Small, auditable changes

**Short answer.** An agent's proposal does not settle a disputed correction, and one agent's confidence is not authority. The correction is a shared, evidence-bearing change submitted in a PR or linked issue that keeps each actor's assertions, sources and dissent visible and explains the conflict. It is reviewed by the relevant maintainer or domain reviewer, and a security, ontology or physical-safety change needs the accountable review. Do not claim a merged document has reached Ask unless the deployed mounted docs and the reviewed context map actually include it; a merge alone does not prove that.

1. Identify the user task, affected capability and existing document/section. Read source evidence before rewriting its behavior.
2. Supply a minimal change with supporting source paths/sections and commit. Distinguish current implementation, approved policy, planned work, runtime observation and inference.
3. Update coverage/gaps and expected-answer evals. A corrected procedure needs a failure/recovery case as well as a successful example.
4. Preserve other actors' assertions, provenance and dissent where evidence conflicts. Explain the conflict rather than silently deleting it or treating one agent's confidence as authority.
5. Submit a PR with scope, tests/source checks, live verification status and any affected permissions or operations. Existing Cowork Ratification fields in the WSJF proposal remain owned by that review process; do not self-ratify them.
6. After review/release, record the accepted source version. Then verify separately that the deployed mounted docs include the change and that the reviewed context map lists its section, and record the eval outcome. No automated indexing exists today; if one is added, verify its version and eval outcome as well.

Routine documentation can proceed from verified sources without waiting for unrelated decisions. A change to security, ontology or physical safety must get the relevant accountable review. The controlling human operator decides authorized deployment and physical actions; the assistant does not gain that authority from document text.

## Contribution record

Include these fields in the PR or linked issue rather than creating a private parallel queue:

| Field | Meaning |
|---|---|
| Capability / question | What the user is trying to accomplish |
| Source and applicability | Commit, document/section, role and instance/version |
| Evidence and uncertainty | What was inspected/tested; what remains unverified |
| Proposed change | Docs/code/config/evals affected, with references |
| Review authority | Relevant maintainer/domain reviewer, unassigned if not yet known |
| Validation | Source checks, local tests and actual live/clean-room results separately |
| Release / knowledge state | PR/commit and index version if one exists; never infer completion |

Keep credentials, private household data, raw logs and unreviewed transcript dumps out of reusable knowledge. Synthetic examples must not silently become actual instance facts. The corpus and eval files are portable Markdown/JSON; vendor-specific prompts may reference them but must not replace them as the only source.

## Future in-app workflow

The desired contribution flow is raise → develop evidence → review → publish/verify → refresh/reuse. It needs role-aware UI, canonical PR/decision linkage, visible freshness, retry and rollback. That implementation is a separate enhancement described in [PR #34](https://github.com/ImpressiveLLC/FaceoftheCabin/pull/34); do not document those controls as available in today's Ask panel.
