# S6: freeze command and ledger entry, design spec

Code, Thu 19 Nov 2026. Status: **proposal, design only**. Ordered on #244 (Cowork, 7026874976, free-capacity task). Docs only: no code, no data, no sealed file read, no host action, no training. This note does not freeze anything and does not authorize running anything.

Builds on [`sft-data-path.md`](sft-data-path.md) (section 4 rules 1 and 5, section 7 step S6, section 8 item 4), [`intent-sft-training-contract-v1.md`](intent-sft-training-contract-v1.md) (R4, R5), HO-2026-10-25 (sealed files, "no sealed rows in Git, an issue, a PR or chat") and the existing tools `grading/reserve_ohf.py`, `grading/s1_sample.py`, `prepare_sft.py` and `round_ledger.py`, all read on main at `40ceaf6a`.

## 1. What S6 has to deliver

From the data path: S6 is "Freeze: leakage checks pass, test and safety hashes recorded in the ledger" (owner Code), and the ledger needs "a freeze command that records the test and safety set hashes and counts every later opening" (section 8 item 4). Section 9 lists S6 done as a precondition of any training run.

So S6 produces one artifact that can be committed, and one rule that is enforced afterwards:

1. A **freeze record**: set-level counts and hashes for the frozen test (200) and the safety set (80), the result of every leakage check with counts, the hashes of every input and tool the checks used, and a freeze id.
2. An **opening log**: every later use of the frozen sets by a candidate model is a new ledger entry bound to that freeze id. The data path says the test is "opened once per candidate model"; the log is how that is made checkable.

Not in S6: authoring the sets (S3, Cowork), reviewing them, S5 audits, choosing a runtime, or any training, export, quantization or routing.

## 2. Today's tools and what is missing

| Tool | What it does now | Gap for S6 |
|---|---|---|
| `round_ledger.py` | `record` (one evaluation round vs dev gold) and `compare`. No freeze or opening subcommand. | New `freeze` and `open` subcommands (section 5). |
| `grading/s1_sample.py` | Family key (`core`, `near`, `must_agree`; near-duplicate threshold 0.75 on token overlap with on/off/open/close/lock/unlock and numbers required to agree). | Reused as the near-duplicate test; no new text-similarity method is invented. |
| `grading/reserve_ohf.py` | Reserves 30 OHF-Voice rows; has `--also-check` files compared by text and never copied. The committed `sft/s1/reserved-ohf.json` records "extra_files: NOT_RUN: sealed safety set and authored test are not in this checkout". | That NOT_RUN is exactly what S6 must close on the machine that has the files. |
| `prepare_sft.py` | `split_rows` excludes gold text and gold groups. It does **not** exclude test or safety families (data path section 8 item 2 is still open). It also assigns its own hash-bucket `test` split from training rows, a different thing from the frozen test. | Must read the freeze record's family list and exclude those families; the internal split should be renamed so "test" always means the frozen test. Not done here. |

## 3. Inputs

All inputs are private files on the freeze machine. Nothing below enters git, an issue, a PR or chat; the hub only ever sees counts, hashes and family-id list hashes (section 6).

| Input | Source | Used for |
|---|---|---|
| Safety set, 80 rows | held by Nate, placed on the freeze machine (HO-2026-10-25) | frozen, counted, hashed |
| Authored test, 170 rows | same | frozen, counted, hashed |
| Reserved OHF-Voice rows, 30 | `reserve_ohf.py` private output | with the authored 170 they make the 200-row frozen test (data path, "Frozen test composition") |
| Train pool | S5-promoted rows (with a passed linked audit) and `synthesis-templates.json` families | leakage and family disjointness against test and safety |
| Dev gold | `gold.jsonl` | leakage against the test and safety sets |
| Prompt text | `system.txt` and every few-shot example it carries | "prompt text against test text" (rule 5) |
| S1 families | `sft/s1/families.json` (committed) | family ids for the pool and the OHF reservation |
| Other repo sets | E3 contrast rows, stress sets, boundary candidates | screened for overlap so none of their text can be a test row |

Row shape: the gold shape (`id`, `group`, `text`, `target`, `critical`, `provenance`). The sealed files' actual format is not known to me; the first run on the freeze machine begins with a shape check that refuses unknown fields (section 4, check C0). That is an assumption to confirm, not a fact.

## 4. The checks (all must pass; any failure writes no freeze record)

Every check outputs counts and, only into the private report, row ids. The committed record carries counts only.

- **C0, shape and counts.** Test is exactly 200 rows (170 authored plus the 30 reserved OHF rows); safety is exactly 80; ids unique within and across sets; every row validates against `contract.validate`; no unknown fields. Composition per the data path: the test's class counts match the table (set_device_state 30, set_device_attribute 24, get_device_state 20, express_need 30, clarify 30, propose_automation 28, answer_question 24, unsupported 14) and the safety counts match (clarify 30, unsupported 25, injection 15, ambiguity and conflicting card 10); every safety row has `critical: true`. A mismatch is reported, not auto-fixed.
- **C1, exact and normalized overlap.** Normalized text (casefold, whitespace collapsed, as `prepare_sft.normalize` does) must not be shared between any two of: test, safety, train pool, dev gold, prompt text, the other repo sets. Zero tolerance.
- **C2, near-duplicate overlap.** The S1 `near` function (token Jaccard at least 0.75 with the meaning words and numbers agreeing) between every test or safety row and every train-pool, dev-gold and prompt-example row. Any hit quarantines the **train** row (not the sealed row) by default and is listed privately; a hit between test and safety is a failure of the freeze, not of training. The sealed sets themselves are never edited by the tool.
- **C3, prompt against test.** Every example in `system.txt` (and any few-shot text added later) is tested against every test and safety row with C1 and C2. This is rule 5's "prompt text against test text" and is also why the prompt hash is recorded.
- **C4, family disjointness.** Every row, train, test, safety and dev, gets a paraphrase family with the same family function S1 uses, over all sets together. A family must appear in exactly one set (rule 1). Authored sealed rows have no S1 family today, so this check **assigns** them; the assignment procedure is recorded in the freeze record by hash.
- **C5, held-out source.** No OHF-Voice row outside the 30 reserved ones, and no row in the train pool that shares a family with the 30 (the S1 rule that already dropped 103 Enfuse rows). The 6 spanning families stay out of training.
- **C6, set independence.** Test and safety share no row, text or family with each other.
- **NOT checked, and said in the record:** meaning-level overlap (paraphrases the word-overlap rule cannot see; the S1 README already lists this as not covered), live-system names or entity ids in sealed text (rule 6 is checked by a deny-list of the known room, device and entity names, a list the freeze machine must have; if it does not, the record says `NOT_RUN`), and reviewer quality (S4 and S5 are separate steps).

## 5. The freeze record and the ledger entries

`round_ledger.py freeze` (proposed, not built) writes one JSON entry. Fields:

- `kind: "freeze"`, `freeze_id` (UTC date plus the first 12 hex of the entry's own content hash), `created_at`.
- `tool`: git commit of the repository checkout that ran it, and sha256 of `round_ledger.py`, `grading/s1_sample.py`, `prepare_sft.py` and the new check module, so the checks are reproducible.
- `inputs`: sha256 of `system.txt`, `gold.jsonl`, `sft/s1/families.json`, `intent-slot.schema.json`, the train-pool file, the deny-list file, each repo set screened.
- `sets`, one per frozen set (`test`, `safety`): `count`, `sha256` of the canonical file (rows sorted by id, JSON with sorted keys, LF, UTF-8), `ids_sha256` (sha256 of the ids joined by newline, the form the S1 manifest already uses), `family_ids_sha256`, per-class counts, `critical` count.
- `checks`: `C0` to `C6`, each `pass` or `fail`, with counts (rows compared, hits, quarantined train rows) and `NOT_RUN` where something could not be checked, with the reason.
- `quarantined_train_rows`: a count and the sha256 of the sorted id list (the list itself stays private and is what `prepare_sft.py` consumes).
- `machine`: a label chosen by Nate for the freeze machine, no hostnames or paths.
- `result`: `FROZEN` only if C0 to C6 all pass; otherwise no entry is written and a private failure report is produced.

A freeze is **immutable**. A refreeze is a new `freeze_id` with a `supersedes` link and a reason; earlier openings stay bound to the freeze they used.

`round_ledger.py open` (proposed) writes an **opening** entry before any scoring: `kind: "opening"`, `freeze_id`, `candidate` (model tag and adapter hash), `purpose`, `opened_by` (a named role, agent or person, never blank), `time`, and later `result_sha256`. The command refuses to run if the set files' hashes differ from the freeze record, refuses a second opening for the same candidate unless the entry says why, and records that the sets were read. The data path's "opened once per candidate model" becomes a rule the ledger can check, not a promise.

What is committed to git: the freeze record and opening entries as described, which carry counts and hashes only. Not committed: any row, text, label, row id, or family id.

## 6. What runs where

- **On the freeze machine only:** C0 to C6 and the writing of the freeze record. They need the sealed files, so they cannot run anywhere else. They are offline, standard-library Python, no network, no model, no GPU; the output folder is outside every clone. The data-path handover (HO-2026-10-25) names the M920q, idle only, for S6; that is a host action and needs Nate's go. This note does not take it and does not decide the machine.
- **Anywhere:** unit tests of the checks on synthetic sets (section 8), and review of this spec.
- **Hub and git:** only the freeze record and opening entries (counts and hashes), plus comments saying the run happened, who ran it and what the counts were.

## 7. What changes in `prepare_sft.py` afterwards (a separate PR)

Read the private quarantine list and the frozen test and safety family ids; refuse any train row whose family or normalized text is in them; rename the internal hash-bucket split so `test` cannot be mistaken for the frozen test; refuse to run if the freeze record is missing or its input hashes do not match. This is data-path section 8 item 2 and is the second half of S6; it is not part of this design PR.

## 8. Tests the build will need (synthetic data only)

A shared normalized text between each pair of sets fails C1; a near-duplicate (one word changed, and one on/off flip that must not count) behaves as the S1 rules say; a prompt example copied into the test fails C3; one family in two sets fails C4; a wrong count, a duplicate id or an unknown field fails C0; an edited row after freezing makes `open` refuse; two openings of the same candidate need a reason; the committed record contains no text and no ids (the same style of test `test_s1_sample.py` has for the S1 manifest); the failure path writes no record.

## 9. Risks and open decisions

1. **The sealed files' real format is unknown to me.** C0 may need adjusting on first contact; it must fail closed.
2. **Near-duplicate quarantine direction.** I propose quarantining train rows, never sealed rows. Cowork or Nate may prefer the opposite for a specific hit.
3. **Authored rows have no S1 family.** C4 assigns them; whether the assignment should be reviewed by someone other than Code before freezing is for Cowork.
4. **Deny-list for live names.** It must exist on the freeze machine; if it does not, the check is `NOT_RUN` and the freeze record says so, which Cowork may treat as a blocker.
5. **Which machine.** M920q per the handover, idle only, needs Nate's go; Nate's runtime and sealed-file placement decisions come first.
6. **Not a substitute for the audit.** A passed freeze says the sets are separate and counted; it says nothing about label quality.

## 10. Not claimed

No sealed row was read, no file was written outside this note, no check was run, no host was touched. Every statement about the sealed files is about their expected shape and is marked as an assumption. Nothing here authorizes training, export, quantization, routing, purchase, or the placement or release of sealed files. A passed S6 would satisfy only the S6 line of the section 9 prerequisites, not the rest.
