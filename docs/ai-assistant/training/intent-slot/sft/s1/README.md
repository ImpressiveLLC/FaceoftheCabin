# S1: coverage audit, paraphrase families and the review sample

Step S1 of the [fine-tuning data path](../../../../proposals/sft-data-path.md). Run 2026-10-19 by Code with `grading/s1_sample.py` over the private candidate file whose hash is in [`../../candidates/manifest.json`](../../candidates/manifest.json). **Nothing was reviewed, labelled by an agent, or trained.** What is committed here is ids, counts and hashes only; the sample's text stays in the private output folder.

| File | What it is |
|---|---|
| `s1-manifest.json` | the rules, the audit, the strata with their quotas, the 300 sampled candidate ids, and the hashes that tie it to the S0 candidates |
| `families.json` | every candidate id grouped into a paraphrase family (`fam-<hash>`), replacing the source-file `group` that S0 found |

## What the audit found

1. **The command pool is small.** The 941 Enfuse rows are only **305 distinct wordings** once word order, politeness and near duplicates are folded together. 211 of the 305 families have a single row; the biggest has 55 (a generic on/off wording). The 1,028-candidate headline overstates what there is to learn from.
2. **Holding OHF-Voice out costs more than 87 rows.** The OHF-Voice wordings (87 rows, 36 families, all `set_device_state`) are reserved for the frozen test. Six of those families also occur in Enfuse, so **103 Enfuse rows** (the most generic on/off commands) leave the training pool too, or the test would measure wording the model had seen. The pool is 838 rows in 305 families.
3. **The sample is almost the whole pool.** The plan's "about 300" is 300 of 305 families (one row per family), so the stratification only trims the thinnest cells and does not concentrate review anywhere. If review time matters more than coverage, `--target 200` makes the strata do real work; that is a decision for Nate, not made here.
4. **The pool is lights and the living room.** By text: light 659 rows, climate 160, fan 7, media 2, other 10; living room 506 of 838. Fans, locks, media and other rooms are close to absent, so the 100 authored commands in the plan should lean there.
5. **Source labels are noisy.** 51 pool rows (35 of the 300 sampled) carry a flag: an attribute command with no value (42), no slots at all (10), or a slot value that is not in the sentence, such as a truncated room (18). Track A's blind relabel will disagree on these, which is the point; the disagreement rate on flagged rows is a measure of the source label quality.

## How the families and sample are built

- **Family:** content words, order-free, with politeness and filler removed; two rows are one family when their words match exactly or overlap by 75% or more *and* agree on every meaning-changing word (on/off, open/close, lock/unlock) and number. Opposite commands and different values are never merged. Families are assigned over all rows first, so a held-out wording and a training wording cannot share one.
- **Sample:** one row per family (the lowest seeded hash). Strata are intent × device class × attribute class (27 cells here), read from the sentence, not the source label. Every cell gets up to 3, the rest in proportion to the square root of the cell size, and within a cell the next pick is the least represented room.
- Deterministic for a seed (`s1-2026-10-19`); `sample_sha256` and `families_sha256` pin the result.

## Not covered

- Device class and attribute class come from keyword lists; a sentence outside them lands in `other`/`none`.
- Near-duplicate detection is word overlap, not meaning, so two different phrasings of one command can sit in separate families. Those still stay together across the train/test split only if a later leakage check catches them; the plan's check (section 4, rule 5) runs at freeze.
- Nothing here selects hard classes. Those are authored (S2, S3).

## Regenerate

```sh
python grading/s1_sample.py --candidates <private>/candidates.jsonl --private-output <private folder> --committed-output sft/s1
```

The printed `sample_sha256` must equal the manifest's, and the run's input hash must equal the S0 `candidate_sha256`; if either differs, stop and find out why.
