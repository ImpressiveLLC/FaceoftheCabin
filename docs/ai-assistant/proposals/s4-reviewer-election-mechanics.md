# S4 reviewer appointments and separate auditor elections

Status: proposed mechanics only, 2026-11-20. Assigned by Cowork on #244 comment7035251285. No person or agent is appointed by this document, no access is granted and no row or label is read. Base: c9dec2bb79546a6cf65d938a86e2c3f035a9ef29.

## Authority and roles

[Training contract section 5](intent-sft-training-contract-v1.md#5-owner-amendment-codex-elects-the-auditors-6874199353) delegates the approved TRAIN-data audit appointment to Codex under its adopted terms. It changes who may audit, not thresholds, access authority, custody or execution gates. The earlier auditor A election remains conditional on exposure screening and a pinned approved cohort; this document neither silently replaces it nor treats public-process intake as data eligibility. Confirm the prior appointment's scope and eligibility before reusing it.

[S4's authored-candidate plan](s4-authored-candidate-review-plan.md) separately requires two independent blind labels for every admitted Track B row. Codex coordinates these labeler appointments under the explicit hub assignment, then separately records the auditor election under the owner amendment. A labeler appointment is not an auditor election. Nate retains dispute authority and the right to inspect or revoke the audit amendment.

| Role | May do | Must not do |
|---|---|---|
| Codex coordinator | Pin admission/provenance, screen candidates, record scoped labeler appointments and auditor elections, verify protected commitments | Supply independent labels or audits on its own or exposed families; self-certify blind eligibility |
| Labeler A and labeler B | Independently produce complete targets and dispositions for their assigned projections | See source/prior labels, coach each other, retrieve label-bearing source context or adjudicate their own disagreement |
| Elected auditor | Independently label the precommitted audit sample before comparison | Audit any family it authored, labeled or trained on; replace hard cases or change denominators |
| Code reciprocal checker | Check permitted counts, hashes and record completeness within its own authorization | Treat metadata verification as semantic labeling or audit completion |
| Nate | Resolve disputes/policy holds, inspect evidence within custody rules, revoke the amendment | No delegated actor may substitute silence for Nate's decision |

Protected Track C, sealed sets, keys, host work and training remain outside these appointments. Source access and packet creation/distribution need their applicable authorization independently of role election.

## Eligibility before access

1. Pin the train-only admitted manifest and content hash, source revision, complete canonical family mapping, provenance/authorship register, labeler history, rubric and intended role. Unresolved admission or family mapping stays held; missing authorship is not evidence of independence.
2. Screen each proposed agent before it receives any row: stable agent/session identity, parent/coauthor lineage, model/provider/version, inherited context, prior source/target/label access, authorship, labeling, training use and tool access. Candidate attestation and coordinator evidence must both be recorded. A fresh session or different agent name is insufficient.
3. Evaluate eligibility per family and role. Any prior target/label exposure prevents blind labeling/auditing of the affected family. Auditors additionally cannot audit families they authored, labeled or trained on. Authors and their coauthoring parent sessions cannot label their own families independently. Uncertain exposure is `UNKNOWN_HOLD`, not eligible.
4. Check actual delivery isolation using the [packet-builder specification](s4-packet-builder-spec.md): reviewer access excludes protected crosswalk/source-target records, other reviewers' submissions and label-bearing source material. A candidate with unrestricted access to the same records is not made blind by an instruction to ignore them. If isolation cannot be demonstrated, hold delivery and propose a bounded alternative.
5. Reassign conflicts to an eligible reviewer/auditor without dropping the conflicted families from the obligation. For Track B every admitted row retains two distinct eligible independent labelers. Audit conflicts require another elected auditor over the affected sampled families; no convenient redraw or family exclusion.
6. Record appointment/election and recipient acknowledgement before any row access. Recheck when identity, inherited context, cohort, family mapping or exposure changes. Any discovered exposure suspends the affected assignment and preserves its prior evidence for disposition.

Shared model/provider bias is a recorded limitation even when session/context isolation passes. Different models are not automatic independence, and identical models are not automatic evidence of a shared conversation. Describe actual independence controls rather than claiming human equivalence.

## Versioned protected record

Keep the authoritative record outside Git with the custody artifacts. Public hub entries expose only permitted role/status references and counts/hashes, never private source text, labels, semantic family names or access credentials. Hashes do not grant access to the payload. Use the packet specification's canonical serialization and explicit version; publish only hashes authorized for that destination.

| Record field | Required content |
|---|---|
| `record_version`, `record_id`, `supersedes`, `created_at` | Versioned immutable identity and UTC time; previous record reference for amendments |
| `role`, `appointing_actor`, `authority_ref` | `labeler_a`, `labeler_b` or `auditor`; Codex coordinator; exact assignment or audit-amendment reference |
| `scope` | Train-only track, pinned cohort/manifest hash, family-map hash, rubric version, assigned opaque delivery IDs and protected source linkage |
| `candidate` | Stable agent/session and parent lineage, model/provider/version, planned delivery identity |
| `exposure_evidence` | Candidate declaration plus coordinator evidence references for authorship, labeling, training and inherited/source-label exposure |
| `family_decisions` | Per-family `ELIGIBLE`, `CONFLICT` or `UNKNOWN_HOLD`, reason, evidence reference and replacement assignment where needed |
| `delivery_gate` | Separate source/output authorization refs, isolation check evidence, acknowledged limits and status before access |
| `audit_precommit` | Auditor only: locked cohort denominator, selection-rule version/seed, sample and projection hashes, assigned subset and conflict reassignment refs |
| `status`, `acknowledgement`, `revocation` | Proposed/held/appointed/acknowledged/suspended/revoked/completed; timestamped identity acknowledgement and append-only changes |
| `attribution` | Auditor records/results use the exact string below |

`agent auditor(s) elected by Codex under owner amendment, not human audit`

Records lacking required screening are held, not partially approved. No invented candidate identity or blanket eligibility entry is allowed. The coordinator signs its own decision as coordination evidence; it is not an independent semantic verdict. An election does not change a row's review status.

## Ordering and evidence

Before labeling, pin the appointment records, rubric and audit-selection algorithm/coverage rules. Labelers independently lock their submissions before comparison. Disagreement, abstention, invalid labels and source-label conflicts enter the existing governed hold queue; author preference and majority are not adjudication. After agreement/admission locks, precommit the exact audit cohort and sample before auditor access/scoring.

For Track B use `ceil(0.20 * M)` sampled rows from the admitted agreed cohort M, preserving any precommitted additional coverage requirements. An empty cohort cannot pass. Allocation among several elected auditors partitions the one precommitted sample; their union equals that sample, without silent omissions or double counting. Do not reduce M to remove conflicts. Auditor replacements retain the selected rows and renewed eligibility records.

Audit agreement is accepted matches divided by all sampled rows; missing/abstained/invalid/unresolved results remain non-matches. Require at least 90%, preserve every disagreement for Nate's disposition and do not pool away a separately commissioned sample's failure. For example, M=495 would require at least99 sampled rows and90 accepted matches; 495 is a planning ceiling, not an admitted cohort claim. Preserve failed results; remediation is a versioned cohort and separately precommitted follow-up, never retroactive relabeling to manufacture a pass.

Completion requires scoped coverage, immutable submissions, an independent blinded audit and required dispute dispositions. Report appointment, access, label coverage and audit outcomes separately. `APPOINTED` or a hash check is not `AUDIT_PASS`; no status here means training-ready.

## Review and acceptance checklist for implementation

Synthetic records must reject missing/unknown identity or family provenance, conflicted/self-review appointments, auditor-labeler overlap, inherited-label exposure and absent delivery authorization. They must accept a conflict reassignment only if two eligible labelers per Track B row and the full precommitted audit sample remain covered. A revoked appointment must stop new access without deleting earlier submissions. Changes to cohort or policy must invalidate stale acknowledgements. Missing audit labels cannot shrink the denominator; exact attribution must survive every report/export allowed by custody rules.

These are required future checks, not executed tests. This proposal elects nobody, creates no private record, builds no packet and performs no S4/S5 audit. Any actual appointment must identify the candidate and satisfy the above gates on a separate recorded execution handoff.
