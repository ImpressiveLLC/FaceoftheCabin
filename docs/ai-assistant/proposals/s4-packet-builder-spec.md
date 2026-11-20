# S4 blind packet builder: implementation specification

Status: proposed, docs only. Assigned by Cowork on #244 comment7033531858; prepared 2026-11-20. Base: ca783fe2a367c4096acafd8cabd11adfbf6132b0. No private source was read for this design; no packet, label or execution key is created by it.

## Scope and reuse

Extend the existing `grading/review_packets.py` pipeline rather than creating a second exporter. Keep `generate_synthetic.py -> bind_families.py -> make-packet` as the authored-row path. This specification implements the packet boundary proposed in the [S4 review plan](s4-authored-candidate-review-plan.md) and [S7 gap list](s7-prepare-sft-gap-list.md), especially G6/G7. It does not implement audit sampling, promotion, S6 or training. The existing id/text-only builder cannot be called compliant merely by changing a manifest field.

The builder accepts an explicitly admitted, family-bound cohort, a pinned rubric and contract, and screened reviewer assignments. It never decides admission, resolves a policy hold or elects an auditor. Track must be explicit; a source filename is not a track or authorization signal. Track B requires two independent labels for every admitted row and the later separate 20% audit. Other tracks retain their own governed rules; this proposal changes none of them.

## Preflight and custody

Before reading row content, require recorded authority for the specific source and output location. A read-only go is insufficient for writing/distributing derived packets. Only named, approved train/review inputs may be opened; sealed sets, refusal packs and screening keys are prohibited. Reject overlapping source/output paths, existing output destinations, symlinks or junction escapes, and paths outside approved roots. Never silently replace source files or prior evidence.

Require unique nonempty source IDs; valid text and trusted card shape; complete family bindings; pinned provenance/authorship and admission records; explicit hold/exclusion reconciliation; and reviewer eligibility decisions for every assigned family. A missing card becomes JSON null; a malformed card fails, rather than disappearing. The builder does not trim, normalize or rewrite text/card values. Machine validity and semantic approval remain separate. Ineligible reviewers receive no rows, and replacement assignments preserve required coverage.

Use distinct protected coordinator storage and reviewer-specific delivery directories outside Git. The reviewer directories are not siblings under a reviewer-readable parent containing coordinator records. Verify effective access for the actual recipient identity before delivery; a hidden filename or fresh chat is not isolation. If the available delivery mechanism cannot enforce that separation, stop before exposing a packet. Coordinator exposure to source targets disqualifies it from supplying independent blind labels for those rows.

## Opaque identity and projection

Allocate a random 128-bit opaque ID for each admitted source row and recipient assignment, encoded as 32 lowercase hexadecimal characters. Detect collisions before use. IDs must not encode source IDs, order, family, intent, track, provenance or targets. Each recipient receives a separately shuffled sequence. Keep ID allocation and shuffle seed/order in the protected run record; do not give recipients those records or seeds. Reusing the same frozen mapping reproduces the same packet; a new mapping is a new evidence version.

Each reviewer JSONL row has exactly `{id, text, card_target}`. The `id` is that recipient's opaque ID. Preserve source text and trusted card context exactly, with explicit null when absent. Do not include source targets, previous labels, suspect flags, groups, authorship, batch identifiers, rationales or source links. Do not pass label-bearing repository context to a reviewer as convenience material. The approved generic rubric must also be screened for row-specific leakage.

The protected crosswalk binds `(run_id, recipient_id, opaque_id)` to source ID, canonical family, source revision and source-row commitment. Require a bijection within each recipient's assigned cohort: no omissions, duplicates or invented rows. Reviewer A and B crosswalks resolve to the same admitted cohort without revealing their correspondence to either reviewer. Family-conflict replacements may partition delivery, but the protected coverage report must still show two eligible independent labels per Track B row.

## Serialization and commitments

Use one versioned canonical JSON helper shared by packet generation, import and later promotion verification. For version 1: sorted object keys by Unicode code point, UTF-8 without BOM, compact separators, no ASCII escaping, no NaN/Infinity, no Unicode normalization. Accept only contract-supported JSON types; reject duplicate keys while parsing. Every JSONL record ends in exactly one LF, including the last. SHA-256 file digests hash the actual stored bytes, including those line endings. Structured commitment digests hash the canonical JSON bytes with no terminal LF. Names explicitly distinguish these two digest types.

Keep the S7 row payload unchanged in meaning: `{version, id, text, card_target, target}`, where `id` is the stable source ID and `version` identifies this serialization/row schema. The source target stays protected. A later locked agreed target produces a separate agreement commitment; never overwrite the source commitment or pretend author agreement already exists.

Separately hash the protected crosswalk and metadata (provenance, admission, family bindings and eligibility references). The cohort commitment hashes a versioned array of `{source_id, row_sha256}` sorted by source ID. A protected run manifest binds all source blob hashes, cohort hash, mapping hashes, metadata hashes, schema/rubric/tool revisions and recipient packet file hashes. Derived hash fields are outside the payload they hash; do not create a self-referential manifest hash.

Each recipient gets a minimal delivery manifest containing only format version, opaque run/delivery identifiers, assigned row count, numbered packet filenames and their byte SHA-256 digests, projection field list and approved generic rubric version. No target-bearing commitments, source paths, protected crosswalk hashes, family/intent counts or hidden metadata are included. The reviewer verifies the delivered projection files; only the coordinator checks source/target commitments. Cryptographic hashes of small label spaces are not a substitute for keeping hidden material private.

An edit to text, card, source target, mapping, admission or policy version invalidates the affected commitment chain and requires a new immutable run. Old submissions remain evidence for the old run only. A change in delivery order changes byte hashes but does not change source identity; both are recorded explicitly.

## Build and submission lifecycle

1. Validate authorization, roots, recipients and source/admission dependencies; read approved inputs once and verify their pinned hashes.
2. Create an exclusively owned new staging directory under the approved output root. Construct protected commitments and separately projected delivery files. No logs include source text or labels.
3. Verify every delivery against its protected crosswalk and exact projection, complete coverage and field allowlist. Rehash source inputs before finalization to reject a concurrent change.
4. Finalize by atomic rename into a previously absent run directory on the same volume. A failure leaves an explicitly incomplete coordinator-only staging record, never a deliverable run. Fail closed if safe finalization cannot be provided.
5. Record immutable delivery hashes and recipient eligibility before granting access. A rerun with an existing destination refuses rather than merging or overwriting. Authorized cleanup is a separate operation.
6. Import submissions against the pinned delivery run. Reject unknown/missing/duplicate IDs, extra fields, altered packet bytes, wrong run/reviewer, partial coverage or malformed targets. Validate targets with each row's trusted card. Semantic holds remain holds despite schema validity.
7. Lock each submission's original bytes, hash, reviewer identity/model and receipt record before comparison. Revisions append a new record with supersession links; they never overwrite prior evidence. No A/B or author-target disclosure before both independent submissions lock.
8. Compare through the protected crosswalk. Full target differences, abstentions, source-label conflicts and policy holds go to the governed disagreement queue; neither majority nor author preference approves a row. This builder does not promote rows.

The implementation must distinguish `BUILT_NOT_DELIVERED`, `DELIVERED`, `SUBMISSION_INCOMPLETE` and `SUBMISSIONS_LOCKED` from semantic agreement/audit statuses. Building a packet is not completing S4 or S5. The later audit builder must use the same projection/commitment helpers, independently screened auditor assignments and its precommitted sample; no unchanged 10% constant may stand in for Track B's 20% rule. Auditor attribution remains `agent auditor(s) elected by Codex under owner amendment, not human audit`.

## Synthetic acceptance evidence required before use

- A target-bearing source fixture with card-dependent rows produces only the three allowed reviewer fields and a minimal manifest. Neither reviewer can open coordinator records or the other reviewer's delivery/submissions using the intended delivery identity.
- Semantic source IDs and flags never appear in delivery IDs, filenames or metadata; unique opaque mappings cover each assigned row exactly once. Shuffling one recipient does not reuse the other's ordering seed.
- Missing cards yield explicit null; changed cards/text alter projection and hidden row commitments. A changed target changes hidden commitments without changing the blind projection. Changed provenance/family bindings change protected metadata commitments.
- Canonical fixtures cover Unicode, nested card/target keys, explicit null, duplicate-key rejection, forbidden nonfinite values and exact LF/UTF-8 bytes. Reusing a frozen mapping reproduces bytes across supported runtimes.
- Unknown/missing/duplicate IDs, extra reviewer fields, stale source/policy hashes, tampered delivery, invalid card/target combinations, incomplete labels and reviewer conflicts fail closed. No schema pass erases a semantic hold.
- A preexisting destination, unauthorized path, symlink/junction escape, interrupted build and concurrent source mutation produce no deliverable run or overwritten evidence. Source files remain byte-identical.
- Two locked disagreeing submissions preserve both originals and enter the disagreement queue. A source-target disagreement is not silently corrected. An auditor cannot be assigned a family it authored or labeled.

Implementation acceptance requires these tests plus review of the actual delivery isolation. This document supplies neither test execution nor access approval. All implementation and S4/S5 execution claims remain NOT_RUN.
