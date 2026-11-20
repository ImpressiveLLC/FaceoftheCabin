# S7 gap G1 (and the G2 rename): build specification for frozen-set exclusion in `prepare_sft.py`

Code, Thu 19 Nov 2026. Status: **build specification, docs only.** Ordered on #244 (Cowork, 7029753443: "S7 gap list item 1, docs-only, claim from #529 list"). I read "docs-only" as a specification, not code, because G1 cannot be validated until the S6 freeze and `screen-train` exist. No code, data, sealed read or host action; this note authorizes none, and builds nothing.

Builds on the merged [S7 gap list](s7-prepare-sft-gap-list.md) (gaps G1 and G2) and the merged [S6 freeze design](s6-freeze-command-and-ledger-spec.md) (sections 5 and 7: freeze record, refusal pack, `screen-train`). Read on main at `adbc9c300c8a79643781188b0293403e76fee3b9`; line numbers are for `prepare_sft.py` at that commit (`split_rows` is lines 47 to 81).

## 1. What changes, in one paragraph

`split_rows(rows, gold)` stops at dev gold. After this change `prepare_sft.py` also takes the S6 freeze record, the frozen-family ids and the `screen-train` result, **drops** the train rows that screen quarantined, **refuses to run** if the freeze or the screen cannot be matched to the input, and **hard-fails** on any row whose family is a frozen family but that the screen did not quarantine (meaning the screen was incomplete). It never reads a sealed row, never holds the screening key, and it renames its own hash-bucket split so `test` means only the frozen test (G2). G3 (near-duplicate gold overlap), G4, G5, G6, G8 and the manifests are separate changes in the gap list and are out of scope here.

## 2. Interface

### 2.1 Library

Keep `split_rows(rows, gold)` working for existing callers (`test_package.py`, `test_review_packets_cohort.py`) by adding one keyword-only parameter, `frozen=None`:

`split_rows(rows, gold, *, frozen=None)`

`frozen` is a small object built from the three private files in 2.2 (or `None`). With `frozen=None` the function behaves as today **except** for the G2 rename, and its return value says so (section 4, `s6` field), so a caller can never mistake a run without S6 for a frozen-aware run. The existing tests are updated for the rename only.

### 2.2 CLI

`python prepare_sft.py INPUT OUTPUT_DIR [--gold PATH] [--freeze-record PATH --refusal-pack PATH --screen-result PATH | --dry-run-no-s6]`

The three S6 flags are given together or not at all. `--dry-run-no-s6` is the only way to run without them; it writes no training files (only the report), prints `NOT_RUN: S6` in the first line of its output and in the report, and exits non-zero if the report is later passed to anything that needs `FROZEN`.

### 2.3 The three private input files (fields `prepare_sft.py` reads)

These formats are what the S6 build (separate work) must produce; this spec fixes only the fields this tool consumes, so the two can be built against each other.

- **Freeze record** (from `round_ledger.py freeze`): `kind: "freeze"`, `freeze_id`, `result` (`FROZEN` or `FROZEN_WITH_GAPS`), `inputs` (sha256 of the files the freeze checked), `tool`.
- **Refusal pack** (private, from the freeze machine): `freeze_id`, `frozen_family_ids` (ids only), `frozen_family_ids_sha256`. It may also hold the keyed hashes S6 uses, but **this tool does not read them and never holds the key**.
- **Screen result** (from `screen-train`): `freeze_id`, `train_file_sha256` (sha256 of the exact INPUT file), `quarantine_ids` (train row ids), `quarantine_ids_sha256`, `counts`, `result`, `tool`.

## 3. Behaviour

1. **Missing or partial S6 inputs.** Without all three files and without `--dry-run-no-s6`, exit with an error naming what is missing. No partial mode.
2. **`FROZEN_WITH_GAPS` is refused.** The freeze record's `result` must be exactly `FROZEN` (S6 design, section 4). `FROZEN_WITH_GAPS` does not satisfy S6 and enables nothing; a run against it exits with that reason, even with a waiver on record, until an owner amendment exists (none does).
3. **Identity checks, all of which must pass before any row is read for splitting:** the refusal pack's `freeze_id` and the screen result's `freeze_id` equal the freeze record's; the screen result's `train_file_sha256` equals the sha256 of the INPUT bytes; `sha256(sorted frozen_family_ids joined by newline)` equals the pack's `frozen_family_ids_sha256`, and that value equals the one the freeze record carries (the S6 build defines how the record combines the test and safety family hashes); `sha256(sorted quarantine_ids joined by newline)` equals `quarantine_ids_sha256`; and the freeze record's `inputs` hashes for the files this tool can recompute here (`gold.jsonl`, `system.txt`, the committed family maps) equal the current bytes. Any mismatch exits with the mismatch named. A stale screen (the train file changed after it) is therefore refused, which is how post-freeze rows (S2 batches, S5 promotions) are forced through `screen-train` again.
4. **Quarantined rows are dropped, not trained and not silently lost.** A row whose id is in `quarantine_ids` is removed before splitting and counted under `quarantined` with its id written to the private report. It does not raise.
5. **A frozen family that the screen did not quarantine is a hard failure.** If any remaining row's family (the bound family `group` used for splitting, see gap G8) is in `frozen_family_ids`, exit with an error listing the row ids and the count. That can only mean the screen was incomplete or ran on a different family map, and training on it would break the one-set-per-family rule.
6. **Gold exclusion is unchanged** (exact normalized text and group: the sets are built at lines 50 to 51 and applied at line 62). G3 later strengthens it.
7. **The tool reads no sealed text.** It never opens the frozen sets, the keyed hashes or the key, and it refuses a path that is not one of the three named files. A test asserts that the tool's source contains no read of any frozen-set path.
8. **Determinism.** Output order, split assignment and the report are independent of input order for a fixed input (the existing hash-bucket rule), and the report is byte-identical across two runs on the same input.
9. **G2 rename.** The internal hash-bucket split `bucket == 0` is renamed from `test` to `holdout` everywhere: the returned dict key, the output file name (`holdout.jsonl`), the printed counts and the data card. The word "test" then means only the frozen test. Whether the 10 percent holdout is wanted at all with small cohorts is a separate decision (the gap list, G2); this spec only renames it. The "nonempty splits" check keeps its meaning for train, validation and holdout.

## 4. Report fields (private, counts only to the hub)

`s6`: `FROZEN` with the `freeze_id`, or `NOT_RUN` (dry run). `rows_in`, `quarantined` (count and `quarantine_ids_sha256`), `dropped_gold` (always raises today, so 0), `rows_out`, per split counts and family counts, `frozen_family_check: pass`. It does not contain row text or ids except in the private report file. The G9 manifests later reuse these fields.

## 5. Tests the build will need (synthetic data only)

A run with `frozen=None` behaves as today apart from the `holdout` rename and reports `s6: NOT_RUN`; a missing S6 file, a partial set of the three flags, and `FROZEN_WITH_GAPS` each exit with the right reason; each identity mismatch exits (a screen result for another `freeze_id`, a changed INPUT file, an edited quarantine list, an edited frozen-family list, a changed `gold.jsonl`); a quarantined row is dropped and counted and does not appear in any split; a row in a frozen family that is not quarantined fails the run; a stale screen after an appended row is refused; two runs on shuffled input produce identical splits and an identical report; the source contains no read of a frozen-set path; the existing `test_package.py` and `test_review_packets_cohort.py` pass with only the rename applied.

## 6. Order of work and dependencies

1. The G2 rename with its test updates, safe to do now (touches `prepare_sft.py`, `test_package.py` line 249 and the review-packets cohort test's use of the splits; no S6 dependency).
2. The G1 wiring (sections 2 to 4), which can be written and unit-tested against synthetic freeze, refusal-pack and screen files now, but is **validated only when S6 and `screen-train` exist**, so it must not be reported as done earlier.
3. G8 (pair families bound before the split) must land first or with it, because step 5 of section 3 depends on the bound family being the `group`.
Each step is its own small PR reviewed by Codex and Cowork.

## 7. Not claimed

No code was written or run. No sealed file, refusal pack, screen result or freeze record exists or was read; the formats in 2.3 are the ones this tool needs, to be matched by the S6 build. Nothing here authorizes training, export, quantization, routing, purchase, host work or sealed-file access.
