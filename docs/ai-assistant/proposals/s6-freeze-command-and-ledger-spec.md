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

All inputs are private files on the freeze machine. Nothing below enters git, an issue, a PR or chat; the hub only ever sees counts, hashes and family-id list hashes (sections 5 and 6).

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

## 4. The checks (all must pass; any failure or NOT_RUN writes no FROZEN record)

Every check outputs counts and, only into the private report, row ids. The committed record carries counts only.

**Same-set rule.** Within one frozen set, a family may hold several paraphrase variants (data path rule 1 requires a family to live in exactly one set, not to have one row), so near-similarity between two rows of the *same* set is **reported, not blocking**. What stays blocking inside a set is explicit: unique ids, and no two rows with the same normalized text (an exact duplicate is a composition error, not a paraphrase). A separately approved composition rule could tighten this; this spec does not.

**Protected comparison matrix (what C1 to C4 compare).** The checks protect the two frozen sets. Each frozen row (test and safety) is compared with: every frozen row **of the other set** (test against safety), and every **exposure input**, meaning anything a model, a prompt or a training set has already seen or may see: the train pool, the S5-promoted rows, `synthesis-templates.json`, dev gold, the examples in `system.txt`, the E3 contrast rows, the stress sets and the boundary candidates. Overlaps *among exposure inputs themselves* (for example dev gold against the prompt) are **not** a gate: dev is already contaminated by prompt use (data path section 4, "Dev"), and a public aggregate check on main finds 3 dev-gold texts verbatim in `system.txt`. The freeze run reports such existing development-only overlaps in a separate section of the private report, as counts, so nobody mistakes them for frozen-set leakage and nobody has to clean them up to freeze. A separate requirement could demand that cleanup; this spec does not.

The checks, in order:

- **C0, shape and counts.** Test is exactly 200 rows (170 authored plus the 30 reserved OHF rows); safety is exactly 80; ids unique within and across sets; every row's target validates against `contract.validate` with that row's own card context (`card_target`, when it has one); no unknown fields. Composition per the data path: the test's class counts match the table (set_device_state 30, set_device_attribute 24, get_device_state 20, express_need 30, clarify 30, propose_automation 28, answer_question 24, unsupported 14) and the safety counts match (clarify 30, unsupported 25, injection 15, ambiguity and conflicting card 10); every safety row has `critical: true`. A mismatch is reported, not auto-fixed.
- **C1, exact and normalized overlap.** Normalized text (casefold, whitespace collapsed, as `prepare_sft.normalize` does) must not be shared between a frozen row and any frozen row of the other set or any exposure input, and no two rows of one frozen set may share it. Zero tolerance.
- **C2, near-duplicate overlap.** The S1 `near` function (token Jaccard at least 0.75 with the meaning words and numbers agreeing) between every frozen row and every frozen row of the other set and every exposure input, **all of them**, E3, stress and boundary sets included. A hit against a **train-pool** row quarantines that train row (the list is private and is what `prepare_sft.py` consumes). A hit against a **non-training exposure input** (dev gold, a prompt example, E3, stress, boundary, a committed template) or between a test row and a safety row **blocks the freeze**: those inputs are immutable or already spent and there is no train row to remove, so the author of the frozen row must change it and the freeze reruns. Near-similarity between two rows of the same set is listed in the private report as a count and is not a failure. The sealed sets themselves are never edited by the tool.
- **C3, prompt against test.** Every example in `system.txt` (and any few-shot text added later) is an exposure input for C1 and C2 and is compared with every frozen row. This is rule 5's "prompt text against test text" and is also why the prompt hash is recorded.
- **C4, family disjointness.** Every row, train, test, safety and dev, gets a paraphrase family, and a family must appear in exactly one set (rule 1). **Declared families are mandatory links and are never replaced by recomputation:** every committed family map, that is **all** `sft/*families*.json` files present at the freeze (today `sft/s1/families.json` and `sft/s2-batch2-families.json`, and any later batch map), each recorded with its sha256; the families declared by authored rows (their `group` values and the template family ids); and the OHF reservation's family ids, which are already committed in `sft/s1/reserved-ohf.json`. All are taken as given, as union links. Detected similarity (the S1 family function over all rows together) only **adds** links on top of them, so a declared family whose variants fall below the lexical threshold cannot be split and falsely pass. Authored sealed rows with no declared family get a family only from detection. The record names the mapping files and their sha256 values and the version of the family function (the `s1_sample.py` hash) used.
- **C5, held-out source.** No OHF-Voice row outside the 30 reserved ones, and no row in the train pool that shares a family with the 30 (the S1 rule that already dropped 103 Enfuse rows). The 6 spanning families stay out of training.
- **C6, set independence.** Test and safety share no row, text or family with each other.
- **C7, live names and entity ids in sealed text (data path rule 6).** Every frozen row is checked against a deny-list of the known site, room, device and entity names and ids. The list must be present on the freeze machine; its sha256 is recorded.

**Result rule.** `FROZEN` requires C0 to C7 all `pass`. A check that is `fail` or `NOT_RUN` (for example C7 because the deny-list is absent) writes **no** `FROZEN` record. The only exception is an explicit, recorded waiver: if Nate (or Cowork, in the RTE role) records in the ledger, **before** the run, a scoped waiver naming exactly one check and the reason, a run in which only that check is `NOT_RUN` may write `result: FROZEN_WITH_GAPS` with `gaps: [C7]`, the waiver reference and who gave it; every opening of that freeze then carries the same marker and any report on it says so. No waiver is assumed: the default is that a missing deny-list means no freeze. Known limits are different from `NOT_RUN` and are always stated, not waived: meaning-level overlap (paraphrases the word-overlap rule cannot see; the S1 README lists this as not covered) and reviewer quality (S4 and S5 are separate steps).

## 5. The freeze record and the ledger entries

`round_ledger.py freeze` (proposed, not built) writes one JSON entry. Fields:

- `kind: "freeze"`, `freeze_id`, `created_at`. The `freeze_id` is computed over a **canonical payload**: the entry as JSON, UTF-8, sorted keys, no insignificant whitespace, LF, **excluding** the derived fields (`freeze_id` itself, `created_at` and the entry hash). The payload **includes** `supersedes`, `reason`, `machine`, `tool`, `inputs`, `sets`, `checks`, `result`, `gaps` and `waiver`, so changing any of them changes the id. The id is the UTC date plus the first 12 hex of the sha256 of that payload, so it never hashes itself.
- `tool`: git commit of the repository checkout that ran it, and sha256 of `round_ledger.py`, `grading/s1_sample.py`, `prepare_sft.py` and the new check module, so the checks are reproducible.
- `inputs`: sha256 of `system.txt`, `gold.jsonl`, every committed family map used by C4, `intent-slot.schema.json`, the train-pool file, the deny-list file, each repo set screened.
- `sets`, one per frozen set (`test`, `safety`): `count`, `sha256` of the canonical file (rows sorted by id, JSON with sorted keys, LF, UTF-8), `ids_sha256` (sha256 of the ids joined by newline, the form the S1 manifest already uses), `family_ids_sha256`, per-class counts, `critical` count.
- `checks`: `C0` to `C7`, each `pass`, `fail` or `NOT_RUN`, with counts (rows compared, hits, quarantined train rows) and the reason for any `NOT_RUN`.
- `quarantined_train_rows`: a count and the sha256 of the sorted id list (the list itself stays private, see section 7).
- `machine`: a label chosen by Nate for the freeze machine, no hostnames or paths.
- `result`: `FROZEN` (C0 to C7 all `pass`) or `FROZEN_WITH_GAPS` (only under a recorded waiver, with `gaps` and `waiver`); otherwise no freeze entry is written and a private failure report is produced.

**Ledger mechanics.** The ledger is an append-only file of one entry per line; each entry carries the sha256 of the previous entry, so an edit or removal breaks the chain. Entries are written to a temporary file, flushed and renamed into place; a crash leaves no partial entry. Who may do what: Code runs `freeze` on the freeze machine, on Nate's go for that machine; an opening is run only with a recorded authorization from Cowork (RTE) for that candidate; every entry names who ran it. The freeze machine's entry reaches git as follows: the operator posts the entry file's sha256 on #244, Code or Cowork commits the file in a docs PR, and Codex checks that the committed bytes hash to the posted value; only counts and hashes are in the entry.

A freeze is **immutable**. A **re-freeze** (a new `freeze_id` with `supersedes` and a reason) is authorized by Nate, as the owner of the sealed material, with Cowork recording it; neither agent may re-freeze on its own. The private quarantine list of the old freeze is kept, bound to the old `freeze_id`, and a new one is written for the new freeze; the old list is deleted only on Nate's word.

`round_ledger.py open` (proposed) writes an **opening** entry, in two phases. Phase one, before any set file is read: `kind: "opening"`, `status: "started"`, `freeze_id`, `candidate` (model tag and adapter hash), `purpose`, `opened_by` (a named role, agent or person, never blank), `time`, and the check that the set files' hashes equal the freeze record (it refuses to run if they differ). Phase two, after scoring: `status: "finished"` and `result_sha256`. The opening rule is keyed by **candidate** (tag and adapter hash), not by `freeze_id`: the count of openings for one candidate is global across supersession, so a re-freeze does not reset it. A repeated opening of the same candidate is **rejected by default**; a free-text reason does not authorize one. A repeat needs a separately authorized, scoped amendment (a named approver and scope recorded in the ledger by Cowork or Nate). A **crashed** opening (status `started`, never `finished`) **still counts as the opening**, because the sets were read; retrying it needs the same amendment, and the crashed entry stays. Openings recorded against a superseded freeze stay in the ledger and still count. The data path's "opened once per candidate model" becomes a rule the ledger can check, not a promise.

What is committed to git: the freeze record and opening entries as described, which carry counts and hashes only. Not committed: any row, text, label, row id, or family id.

## 6. What runs where

- **On the freeze machine only:** C0 to C7, the writing of the freeze record and the refusal pack, and `screen-train` (section 7). They need the sealed files, so they cannot run anywhere else. They are offline, standard-library Python, no network, no model, no GPU; the output folder is outside every clone. The data-path handover (HO-2026-10-25) names the M920q, idle only, for S6; that is a host action and needs Nate's go. This note does not take it and does not decide the machine.
- **Anywhere:** unit tests of the checks on synthetic sets (section 8), and review of this spec.
- **Hub and git:** only the freeze record and opening entries (counts and hashes), plus comments saying the run happened, who ran it and what the counts were.

## 7. What changes in `prepare_sft.py` afterwards (a separate PR), and where sealed-derived data lives

`prepare_sft.py` must refuse any train row whose family or normalized text is in the frozen sets, rename its internal hash-bucket split so `test` cannot be mistaken for the frozen test, and refuse to run if the freeze record is missing or its input hashes do not match. That is data-path section 8 item 2 and the second half of S6. It is not part of this design PR. The custody question is how it can do this without ever holding sealed text:

- **Sealed text and the key stay on the freeze machine.** The freeze run writes a private *refusal pack* there: the frozen families' ids, and, for each frozen row, **keyed** hashes (HMAC-SHA256 with a random secret key generated on, and never leaving, the freeze machine). Short normalized commands ("turn on the light") can be brute-forced from an unkeyed hash, so no unkeyed hash of sealed text is ever produced or stored. For the near-duplicate rule, the pack stores each frozen row's content tokens as keyed per-token hashes, so token overlap (Jaccard) can be computed on hashes without any text.
- **Train rows are screened where the key is, not the other way round.** A proposed `round_ledger.py screen-train` runs on the freeze machine: it takes a train candidate file (train rows are not sealed and can be carried there), compares each row to the frozen sets by exact keyed hash, near-duplicate token overlap and family, and writes only a quarantine id list and a result bound to the train file's sha256 and the `freeze_id`. Nothing sealed leaves the machine.
- **`prepare_sft.py` runs anywhere**, with no sealed text and no key. It consumes the quarantine id list and the frozen family ids (ids only, from the refusal pack) and **refuses to run** unless a `screen-train` result exists whose train-file hash and `freeze_id` match its input.
- **Post-freeze rows get the full check, not only exact text and family.** Every later addition (S2 batches, S5 promotions) must be screened with `screen-train` before it enters a train cohort, and each change to the train file needs a new screen result, so the near-duplicate rule applies to them too.

## 8. Tests the build will need (synthetic data only)

A shared normalized text between a frozen row and a row of the other set or an exposure input fails C1, and so does an exact duplicate inside one frozen set or a repeated id, while two near-paraphrase variants inside one set pass and are only counted in the report; while an overlap between two exposure inputs (dev gold against the prompt) is only reported; a near-duplicate (one word changed, and one on/off flip that must not count) behaves as the S1 rules say; a prompt example copied into the test fails C3; a declared family whose variants are lexically far apart still counts as one family and cannot be split across sets (declared links survive recomputation); a near-duplicate of E3, stress or boundary text blocks the freeze; one family in two sets fails C4; a wrong count, a duplicate id or an unknown field fails C0; an edited row after freezing makes `open` refuse; a second opening of the same candidate is rejected with or without a free-text reason and only a recorded scoped amendment lets it through (the first entry is retained); the committed record contains no text and no ids (the same style of test `test_s1_sample.py` has for the S1 manifest); the failure path writes no record. A deny-list that is absent makes C7 `NOT_RUN` and no `FROZEN` is written unless a prior waiver for exactly that check is recorded, in which case the result is `FROZEN_WITH_GAPS` and every opening carries it; an opening for a candidate that already has one, in any freeze, is rejected; a crashed opening (started, not finished) still counts; a ledger entry edited or removed breaks the chain; the refusal pack contains no unkeyed hash of text and no text; `prepare_sft` refuses to run without a matching `screen-train` result, and a train row that is a near-duplicate of a frozen row is quarantined by `screen-train` even though its text and family differ.

## 9. Risks and open decisions

1. **The sealed files' real format is unknown to me.** C0 may need adjusting on first contact; it must fail closed.
2. **Near-duplicate direction.** Train-pool hits quarantine the train row; a hit against a spent or immutable exposure input, or between a test row and a safety row, blocks the freeze and the frozen row's author changes it; near-similarity inside one frozen set is only reported. Cowork or Nate may rule differently for a specific hit.
3. **Authored sealed rows with no declared family.** C4 gets them a family only from detection; whether that assignment should be reviewed by someone other than Code before freezing is for Cowork. Declared families are never recomputed away.
4. **Deny-list for live names.** It must exist on the freeze machine. If it does not, C7 is `NOT_RUN` and there is no `FROZEN` record, unless Nate or Cowork records a scoped waiver for exactly that check before the run (then `FROZEN_WITH_GAPS`). This spec proposes the default of no waiver; Nate decides.
5. **Which machine.** M920q per the handover, idle only, needs Nate's go; Nate's runtime and sealed-file placement decisions come first.
6. **Not a substitute for the audit.** A passed freeze says the sets are separate and counted; it says nothing about label quality.
7. **Authority proposed here, for Nate and Cowork to confirm.** Who may re-freeze (Nate, recorded by Cowork), who may run an opening (with Cowork's recorded authorization), the keyed-hash custody of the refusal pack, and `screen-train` on the freeze machine are proposals in sections 5 and 7, not decisions.

## 10. Not claimed

No sealed row was read, no file was written outside this note, no check was run, no host was touched. Every statement about the sealed files is about their expected shape and is marked as an assumption. Nothing here authorizes training, export, quantization, routing, purchase, or the placement or release of sealed files. A passed S6 would satisfy only the S6 line of the section 9 prerequisites, not the rest.
