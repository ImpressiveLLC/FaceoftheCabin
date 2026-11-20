# S7: what has to change before a `prepare_sft.py` dry run, and the train-manifest hash format

Code, Thu 19 Nov 2026. Status: **proposal, gap list only**. Ordered on #244 (Cowork, 7027949404). Docs only: no code, no data, no sealed or private-sample access, no host, no training. This note does not run a dry run and does not authorize one.

Evidence basis: `prepare_sft.py`, `grading/review_packets.py`, `generate_synthetic.py`, `sft/s1/*`, `sft/s2-batch2*.{json,md}`, the training contract v1 and the S6 design ([`s6-freeze-command-and-ledger-spec.md`](s6-freeze-command-and-ledger-spec.md), #527), all read on main at `2a642850`. Nothing was executed except reading and counting; line numbers are for that commit.

## 1. What a dry run is for

The path in HO-2026-10-25 is: S6 freeze, then an S7 dry run of `prepare_sft.py` that produces splits and a data card with nothing exported or trained. A dry run is useful only if it can fail for the right reasons: a leaked row, an unreviewed row, a row that cannot be labeled. Today it would pass or fail for several wrong ones (section 3).

## 2. What the tool does today

`split_rows(rows, gold)` takes reviewed rows and dev gold. It refuses a row unless `provenance.review_status == "approved"` with reviewer, reviewed_at, source, revision, sha256 and license present, and for an agent label it checks `audit_result` (`check_agent_audit`, a passed audit by 90 percent or better, a linked cohort and audit-sample hash, the row hash unchanged since the audit, audited sample size at least 10 percent of the cohort). It drops nothing silently: an empty, duplicate, gold-text or gold-group row raises. It validates the target against the contract (with `card_target`) and vocabulary, then assigns a split by `sha256(group) % 10` (0 is "test", 1 is "validation", rest "train") and raises if any split is empty. It prints only counts.

## 3. The gaps

"Blocks the dry run" means a dry run would give a false pass or a wrong refusal without it.

| # | Gap | Evidence | Needed change | Blocks the dry run |
|---|---|---|---|---|
| G1 | **Frozen test and safety families are not excluded.** Only dev gold is excluded, by exact normalized text and by group. | `split_rows` builds `forbidden_text` and `forbidden_group` from gold only (lines 50 to 51); data path section 8 item 2 is open. | Read the S6 outputs (the quarantine list and the frozen test and safety family ids, privately) and refuse any row whose family or normalized text is in them; refuse to run if the freeze record is missing or its hashes differ. | Yes, once S6 exists; until then the dry run must say `NOT_RUN: S6` in its output, not pass silently. |
| G2 | **The internal split is also called `test`.** 10 percent of reviewed families go to a split named `test`, while "the frozen test" is a different, sealed set. | `bucket == 0` gives `"test"` (lines 73 to 74). | Rename it (for example `holdout`) so "test" means only the frozen test; decide whether the 10 percent holdout is wanted at all with a small cohort. | Yes (a misleading name in the data card). |
| G3 | **Gold overlap is exact-only.** Near-duplicates of dev gold pass. | Exact text and group only; the #528 review found 4 S2 candidates at 0.75 token overlap with gold-34 that this check would let through. | Apply the S1 `near` rule (token Jaccard at least 0.75, meaning words and numbers agree) between each row and gold, with the same exclusion and report. | Yes (dev leakage). |
| G4 | **Track B audit share is 10 percent in code; the adopted terms say 20 percent.** | `AUDIT_SHARE = 0.10` is one constant for every track (`review_packets.py` line 21); `check_agent_audit` also computes 10 percent for every row. Contract section 5 item 3: Track A 10 percent, Track B 20 percent. | A per-track share (A 10, B 20, C as the amendment says, C is outside the elected auditor's appointment) in `audit_size`, in the audit manifest and in `check_agent_audit`. | Yes for any authored (Track B) row: it would be accepted with half the required audit. |
| G5 | **The tool still says an audit must be a person; the amendment elects agent auditors.** `record_audit` refuses `audited_by` that starts with codex, cowork, claude or agent and says "must be recorded by a person (Nate)"; an agent with another name passes. The result carries `attribution_only: true` and no auditor kind or "not human audit" label. | `review_packets.py` lines 302 to 303 and 311. | Add an explicit `auditor_kind` (`agent` or `human`) with a required label ("agent auditor(s) elected by Codex under owner amendment, not human audit"), the election reference, and an independence check: the auditor must not be the author, first reviewer, second reviewer or family author of any row in the cohort. Carry the fields into `audit_result` and have `check_agent_audit` verify them. | Yes (the audit trail would be mislabeled or refused). |
| G6 | **Card-dependent rows cannot be labeled or validated blind.** The reviewer packet carries only `id` and `text`; a label is validated with `validate(row["target"])` and no card. Wrong-card and schedule rows (S2 batch 2) have targets that depend on `card_target`. | `make_packet` (`fields_given_to_reviewer: ["id","text"]`); `load_labels` line 157; compare `prepare_sft` line 67 which does pass `card_target`. | Include `card_target` in the packet for rows that have one (it is request input, not a label), validate labels against it, and say in the packet manifest which rows carry one. | Yes for those 50 candidates. |
| G7 | **Authored templates do not reach the review path.** `generate_synthetic.generate` makes rows with `review_status: unreviewed` and `group` set to the template id; nothing exports them to a private sample file for Track B, and the packet needs `source`, `revision`, `sha256`, `license` and `record`, which `generate` does provide. | `generate_synthetic.py` lines 9 to 23. | A small private exporter from `synthesis-templates.json` (with the family binding) to a Track B sample; its output hash goes in the manifest. | Yes (no path from S2 to an approved row today). |
| G8 | **Minimal-pair halves can fall in different splits.** The raw generated `group` is the template id, and an opposite-label pair is two templates, so `sha256(group) % 10` can put a clarify and its resolved counterpart in different splits. The S2 batch 2 note says not to split raw generated output. | `s2-batch2.md`, "Capability blockers and family handling"; `s2-batch2-families.json` (5 families of 30); batch 1's pair families are not bound the same way. | Apply `grading/bind_families.py` (or its generalization) so the pair family, not the template id, is the `group` before any split. | Yes. |
| G9 | **No train manifest or data card.** The tool prints counts. | `prepare_sft.py` `__main__`. | Write the private manifest in section 4 and a counts-only data card. | Yes (the auditor needs the manifest). |
| G10 | **No live-name or entity-id check.** The data-path rule 6 (no live site, room, device name or entity id in training) is not enforced here. | No such check in `prepare_sft.py`. | A deny-list check with the same list S6 uses; `NOT_RUN` if the list is absent. | Should (a safety rule). |
| G11 | **Small-cohort behavior.** The tool raises if train, validation or holdout is empty. | `split_rows` last check. | Keep refusing but report the reason with counts; for a dry run on a small cohort, allow an explicit `--dry-run` that reports empty splits instead of raising, without writing training files. | No; usability. |

Not a gap, said so no one re-checks it: the tokenizer and loss-mask side is covered (`sft_tokenize.py`, `test_sft_tokenize.py`, now in CI by #525).

## 4. Train-manifest hash format for the auditor

Codex's preparation checklist (7025518467) needs a train-only manifest and hash, provenance, author and labeler identities, and a precommitted reproducible sample with its denominators before any scoring. The format below reuses the field names the importer already writes (`audit.manifest.json`, `audit_result`) so nothing new has to be learned. It is **private**; only counts and hashes go to the hub.

`train-manifest.json` (proposed):

- `manifest_version`, `built_at`, `manifest_id` (the first 12 hex of the sha256 of the canonical payload without derived fields, so it never hashes itself).
- `tool`: git commit and sha256 of `prepare_sft.py`, `grading/review_packets.py`, `grading/s1_sample.py`, `grading/bind_families.py`, `contract.py`, `vocabulary.py`.
- `inputs`: sha256 of each promoted or agreed file per track, of `sft/s1/families.json`, of the S2 family maps, of `gold.jsonl`, and the `freeze_id` from S6 (or `NOT_RUN: S6`).
- `counts`: rows and families in train, validation and holdout; by intent; by track (A, B, C); by source; `quarantined`, `refused_by_training` (leading-newline rule).
- `hashes`: `train_ids_sha256` (sorted ids joined by newline, the S1 manifest form), `train_content_sha256` (canonical sorted `[id, text, target]`, the form `audit_content_sha` uses), `families_sha256` (the S1 formula), `quarantine_ids_sha256`, `frozen_family_ids_sha256`.
- `tracks[]`, one per track: `track`, `cohort_sha256`, `cohort_n`, `audit_share` (0.10 or 0.20), `audit_n`, `seed`, `audit_ids_sha256`, `audit_content_sha256`, and the `sample_selection` rule as text (precommitted before labels exist).
- `people_and_agents`: for each track the author ids, first and second labeler ids and models, and the family authors; and the `auditor` block: id, `auditor_kind: agent`, the election reference (6874239053), the label "agent auditor(s) elected by Codex under owner amendment, not human audit", and `conflict_exclusion_sha256` (hash of the sorted family ids the auditor authored, labeled or trained on, which are excluded from its audit).
- `result`: `READY_FOR_AUDIT` only if every input hash matches and no row failed a check; otherwise no manifest.

The auditor can then check independently that its sample is the precommitted one (`seed`, `audit_n`, the ids hash), that the content it is shown hashes to `audit_content_sha256`, and that none of its own families is in the audit cohort.

## 5. Order and size

The smallest dry-run-ready change, in order: G6 and G8 (so authored rows can be labeled and keep pairs together), G4 and G5 (so audits are the right size and labeled correctly), G7 (the exporter), G3 and G2 (leakage and naming), G9 (manifest), G1 after S6 exists, then G10 and G11. These are four or five small code PRs plus tests, each reviewed by Codex and Cowork; none is started by this note.

## 6. Not claimed

No dry run was run, no data was read, nothing was changed. The gap evidence is from reading the code at the commit named above and from the #528 review. Whether G4's 20 percent for Track B also applies to Track C is not decided here (the amendment keeps Track C outside the elected auditor's appointment). Nothing here authorizes training, export, quantization, routing, host work, purchase, or sealed or private-data access.
