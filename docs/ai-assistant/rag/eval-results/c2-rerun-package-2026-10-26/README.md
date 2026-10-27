# C2 domain evaluation handoff — prepared, NOT_RUN

Codex prepares; Code executes the authorized host evaluation; Cowork ratifies. This package makes no model calls, deployment, ingestion, training or device changes. Prepared against source commit in run-spec.json; deployed identity remains unverified.

## Frozen experiment

Use run-spec.json: original 24 questions, three repeats, 72 requests in a new run. Keep both historical 72-trial runs (144 total) immutable. The corrected scoring oracle r3 has SHA256 `f584f7da1b07f7d609ae589b961a6ab8dc44f85286852f2cd0d1bf806240a52d`; rubric remains r2. Do not feed corrected expected answers to Ask. The runtime questions file stays the original r2 file; its obsolete Q05/Q07 expectations are superseded for grading only by oracle r3.

After each approved domain deployment, collect one uniquely named 72-trial run and compare to the previous retained 72-trial reference. Replaying the old before/after labels on current production cannot recreate the historical pair. A new controlled pair requires two independently attested states and a separate recorded plan; this handoff authorizes no rollback or alternate deployment. Q20 remains outside the pre-existing answer-only suite.

## Host command list for Code

Run only from the operator-confirmed M920q repository and authorized administrator session. An Ask evaluation creates model workload and ordinary request logs even though it has no device executor. The resident-session option reads an existing session through a read-only database transaction; never print it. No deployment/restart/scan command is included.

1. Precondition: #288 is merged and deployed before the first domain rerun. Record the SHA256 of the deployed classpath `rag/context-fixtures-r2.json`, verify it equals `docs/ai-assistant/rag/context-fixtures-r2.json`, and retain the per-category character table from `docs/governance/evidence/c2-context-map-wiring-2026-10-27/README.md` for that deployed revision. Unmapped sections cannot be measured: the #288 evidence currently lists 14 unreachable sections; retain that list with the attestation. Verify the intended domain PR is merged AND actually available through the mounted docs/context-map path. Record deployed backend image/checkout, exact model digest, docs hashes and context-map revision privately. A merge alone is insufficient. Record config and retrieval differences; unknown identities block causal improvement claims.
2. Choose a new private directory outside every Git checkout, owned by the operator. Set `PRIVATE_RUN_DIR` to that absolute path. Do not reuse a historical filename. Keep all raw answers, supplied sources and session/runtime details there; publish counts, trial IDs and hashes only.
3. Pin evaluation tools to the source_commit in run-spec.json, independently of the deployed application revision:

```sh
# From repository root. PRIVATE_RUN_DIR must already be an approved private folder.
: "${PRIVATE_RUN_DIR:?Set a fresh private absolute directory outside git}"
PACKAGE=docs/ai-assistant/rag/eval-results/c2-rerun-package-2026-10-26
EVAL_COMMIT=$(python3 -c 'import json; print(json.load(open("docs/ai-assistant/rag/eval-results/c2-rerun-package-2026-10-26/run-spec.json"))["source_commit"])')
# Refuse to overwrite staged inputs.
for item in questions oracle rubric harness grader; do
  test ! -e "$PRIVATE_RUN_DIR/$item" || exit 1
done
python3 - "$PACKAGE/run-spec.json" "$PRIVATE_RUN_DIR" <<'PY'
import hashlib,json,pathlib,subprocess,sys
spec=json.load(open(sys.argv[1])); folder=pathlib.Path(sys.argv[2]).resolve()
if any((p/'.git').exists() for p in (folder,*folder.parents)):
    raise SystemExit('Private output must be outside git')
for name,item in spec['inputs'].items():
    blob=subprocess.check_output(['git','show',spec['source_commit']+':'+item['path']])
    if hashlib.sha256(blob).hexdigest()!=item['sha256']:
        raise SystemExit('Frozen input hash mismatch: '+name)
    with (folder/name).open('xb') as f: f.write(blob)
print('Frozen input hashes verified; no model call made.')
PY
```

4. Only when Code is authorized to perform this host run, execute the native endpoint without `--use-context`. The harness records local runtime metadata and verifies unauthenticated denial. Confirm its private run metadata says server-derived administrator. Do not fall back to a different role, endpoint, model or probe flag if a prerequisite fails.

```sh
umask 077
python3 "$PRIVATE_RUN_DIR/harness" \
  --questions "$PRIVATE_RUN_DIR/questions" \
  --output "$PRIVATE_RUN_DIR/domain-run.jsonl" \
  --url http://127.0.0.1:8090 --repeats 3 \
  --resident-admin-session --record-runtime
python3 "$PRIVATE_RUN_DIR/grader" init \
  "$PRIVATE_RUN_DIR/domain-run.jsonl" "$PRIVATE_RUN_DIR/domain-grades.json"
sha256sum "$PRIVATE_RUN_DIR/domain-run.jsonl"
```

If a run stops, preserve it as incomplete; do not append a replacement trial or cherry-pick repeats. Give any replacement run its own identity and retain the failure record. No output file may overwrite an existing artifact.

## Grading sheet and comparison gate

The native grader initializer creates the importer-compatible r2 sidecar from actual transport records. Use review-notes-template.json as supplemental review notes, NOT as a fabricated transport sidecar. Copy it privately; bind it to the new raw-run hash. It has all 72 unique question/repeat keys, with no approvals or scores prefilled.

For every trial inspect the actual answer, all required assertions under oracle r3, and the supplied versioned sources. Record a second source check or a concrete counter-case, reviewer, confidence and disagreements. Score factual/usefulness/citations independently; record both violation flags. PASS requires valid contract, all three scores 2, both flags false and reviewer. Agent review is never human ratification; uncertain/disputed rows go to Cowork/Nathan. Confidence is subjective until calibrated. Do not publish answer text.

Before comparison verify BOTH sidecars have the same corrected oracle hash, all 24 question IDs x three repeats exactly once, unchanged question/rubric/role/endpoint/repeat manifests and complete review. Keep the oracle binding and raw hashes in a separate run-attestation record: the existing r2 grader does not enforce oracle hashes itself. Hash or manifest mismatch is HOLD, never an automatic normalization. The frozen reference is the private after-codex-oracle-r3-grades.json beside the original C1 package, not the old draft 13-pass file.

```sh
python3 "$PRIVATE_RUN_DIR/grader" report "$PRIVATE_RUN_DIR/domain-grades.json"
# Set PRIVATE_REFERENCE_GRADES to the confirmed private r3 reference sidecar.
: "${PRIVATE_REFERENCE_GRADES:?Set the reviewed r3 reference sidecar}"
python3 "$PRIVATE_RUN_DIR/grader" compare \
  "$PRIVATE_REFERENCE_GRADES" "$PRIVATE_RUN_DIR/domain-grades.json"
```

Report execution and grading coverage, PASS/72, all 24 question repeat counts, paired transitions, refusal/source-available failures and safety/governance flags separately. Compare to 11/72 after (0/72 historical before), preserving Q12/1's defect and both existing flagged FAILs. A higher count alone does not mean accepted/improved. No automatic release: any new or unresolved risk flag, incomplete review or incompatible provenance blocks acceptance. C1b remains unauthorized. S5 review and sealed-file placement stay parked behind corpus work.

## Validation and handoff

Preparation checks only: JSON parses, exact 24 x 3 unique keys, all review cells unreviewed, frozen Git-blob hashes, oracle hash, relative links and diff whitespace. Host/model execution, fresh accuracy, deployment and human ratification are NOT_RUN. After execution Code posts private result location plus counts/hashes on #244; Codex grades/reviews, Cowork ratifies.
