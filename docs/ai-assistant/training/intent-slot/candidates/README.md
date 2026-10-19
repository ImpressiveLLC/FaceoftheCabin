# External candidate sources: regeneration record (S0)

Step S0 of the [fine-tuning data path](../../../proposals/sft-data-path.md). **Only `manifest.json` lives in git.** The
candidate files, the quarantine file and the upstream downloads are private and stay out of the repository (the `.gitignore`
beside this file refuses them). Nothing here is reviewed, approved or trained on.

## Run record

| Field | Value |
|---|---|
| Run | 2026-10-19 (Code), approved by Nate in chat the same day |
| Sources | Enfuse smart-home JSONL (Apache-2.0) at `39056ca0…`; OHF-Voice intents, four concrete on/off test files (CC-BY-4.0) at `a64026f9…` |
| **Excluded on purpose** | **Snips** (12 rows before): nothing from it was downloaded, because its dataset terms are not verified. The collector gained `--exclude snips` for this, tested to never request the excluded source. |
| Tool | `grading/collect_sources.py --package . --output <private folder> --exclude snips` (this PR adds `--exclude` and the counts by intent) |
| Network | Seven requests: 2 files from Hugging Face (`README.md` 9 KB, `train.jsonl` 576 KB) and 5 from raw.githubusercontent.com (the OHF-Voice licence 19 KB and four YAML files of 1 to 2 KB) |
| Result | **1,028 candidates** (Enfuse 941, OHF-Voice 87) and 1,086 quarantined; every candidate is `unreviewed`, `training-candidate` |
| Candidate file hash | `6846f3a46c8b92655fbd34f97db61006fc32e14250b8a78b80813bb289538fce` (the private `candidates.jsonl`) |

## What the numbers say

- **Coverage matches the plan's finding.** Candidates by intent: set_device_state 538, set_device_attribute 303, get_device_state 187. There is **no** `clarify`, `unsupported`, `express_need`, `propose_automation` or `answer_question` row, which is why the data-path plan reviews a sample and authors the hard classes.
- **Quarantine, by reason (top):** exact duplicate within a source 549 (none equal a gold text), state not explicit on/off 259, temperature without a unit 139, duplicate slot 131 in all, lock/valve/cover or unknown domain 8. Enfuse had 2,016 input records and kept 941.
- **Different from the earlier count.** The 2026-10-16 note recorded 1,042 Enfuse and 86 OHF-Voice candidates (1,140 with Snips) and 988 quarantined. This run gives 941 and 87 with Snips left out, and 1,086 quarantined. The 101 fewer Enfuse rows are not explained by gold overlap (zero), and the cause is not established: the collector or its inputs may have changed since, and the earlier run was not reproduced. **Treat this manifest as the authoritative count, and do not mix the two.**
- **Groups are source files, not families.** All 1,028 rows carry only five `group` values (one Enfuse file, four OHF-Voice files). `prepare_sft.py` splits by group hash, so as it stands every Enfuse row would land in one split. Real paraphrase families must be assigned during review (plan step S1) before any split.
- **Unique wording.** All 1,028 texts are distinct after case and whitespace folding.

## Licences and attribution

OHF-Voice intents are CC-BY-4.0: any use needs attribution (source, revision `a64026f9…`, licence). Enfuse is Apache-2.0: keep its notice. The pinned URLs and file hashes are in `manifest.json`. A trained model's data card must carry both.

## Regenerate

```sh
python -m venv .venv && .venv/bin/pip install -r requirements.txt     # jsonschema, PyYAML, python-dateutil
.venv/bin/python grading/collect_sources.py --package . --output /private/path --exclude snips
```

Compare the printed `candidate_sha256` and each file's `sha256` with `manifest.json`. The upstream revisions are pinned commits, so the downloads are stable; if a hash differs, stop and find out why before reviewing anything.
