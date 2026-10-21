"""Reserve the OHF-Voice set_device_state rows for the frozen test: one row per paraphrase family, none overlapping anything else.

Reads the private candidates and the committed S1 family map. Writes the reserved rows (private, they carry text) and a
committed record with ids, source revision, licence and hashes only. Optional --also-check files (the sealed safety set and
authored test, which only the freeze machine has) are compared by text and never copied or printed.
"""
import argparse
import hashlib
import json
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from s1_sample import core, near, rank  # noqa: E402

SEED = "ohf-reserve-2026-10-20"
EXPECTED = 30


def texts_of(path):
    return [json.loads(line)["text"] for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]


def overlaps(words, others):
    """Count of other texts that equal or nearly equal this wording (same on/off and numbers)."""
    return sum(1 for o in others if words == o or near(words, o))


def reserve(rows, family_map, others, seed=SEED):
    owner = {rid: fam for fam, ids in family_map.items() for rid in ids}
    held = [r for r in rows if r["provenance"]["source"] == "ha"]
    spans = {owner[r["id"]] for r in rows if r["provenance"]["source"] != "ha"}
    by_family = {}
    for row in held:
        by_family.setdefault(owner[row["id"]], []).append(row)
    chosen, skipped = [], Counter()
    other_cores = [core(t) for t in others]
    for fam in sorted(by_family):
        if fam in spans:
            skipped["shares a family with a training-source row"] += 1
            continue
        pick = min(by_family[fam], key=lambda r: rank(seed, r["id"]))
        if pick["target"]["intent"] != "set_device_state":
            skipped["not set_device_state"] += 1
            continue
        if overlaps(core(pick["text"]), other_cores):
            skipped["matches dev, a template or a checked file"] += 1
            continue
        chosen.append((fam, pick))
    return chosen, dict(skipped), len(by_family)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", type=Path, required=True)
    parser.add_argument("--candidates", type=Path, required=True)
    parser.add_argument("--families", type=Path, required=True)
    parser.add_argument("--private-output", type=Path, required=True)
    parser.add_argument("--committed-output", type=Path, required=True)
    parser.add_argument("--also-check", type=Path, nargs="*", default=[], help="JSONL files with a text field, e.g. the sealed sets")
    args = parser.parse_args()
    rows = [json.loads(line) for line in args.candidates.read_text(encoding="utf-8").splitlines() if line.strip()]
    family_map = json.loads(args.families.read_text(encoding="utf-8"))
    others = texts_of(args.package / "gold.jsonl")
    templates = json.loads((args.package / "synthesis-templates.json").read_text(encoding="utf-8"))
    others += [u for t in templates for u in t["utterances"]]
    checked_files = []
    for extra in args.also_check:
        texts = texts_of(extra)
        others += texts
        checked_files.append({"rows": len(texts), "sha256": hashlib.sha256(extra.read_bytes()).hexdigest()})
    chosen, skipped, family_count = reserve(rows, family_map, others)
    if len(chosen) != EXPECTED:
        sys.exit(f"Expected {EXPECTED} reserved rows, found {len(chosen)} (skipped: {skipped}). Do not freeze; decide first.")
    out = [dict(row, family_id=fam) for fam, row in sorted(chosen, key=lambda c: c[1]["id"])]
    blob = "".join(json.dumps(r, sort_keys=True) + "\n" for r in out)
    args.private_output.mkdir(parents=True, exist_ok=True)
    (args.private_output / "reserved-ohf-30.jsonl").write_text(blob, encoding="utf-8")
    record = {
        "step": "S1/S3 reserve", "seed": SEED, "rows": len(out), "families": family_count,
        "source": "OHF-Voice/intents", "licence": "CC-BY-4.0",
        "revisions": sorted({r["provenance"]["revision"] for r in out}),
        "by_file": dict(sorted(Counter(r["provenance"]["path"] for r in out).items())),
        "skipped_families": skipped,
        "checked_against": {"dev_gold": "gold.jsonl", "training_templates": "synthesis-templates.json",
                            "training_pool": "S1 families.json (no shared family)",
                            "extra_files": checked_files or "NOT_RUN: sealed safety set and authored test are not in this checkout"},
        "ids": [r["id"] for r in out], "family_ids": [r["family_id"] for r in out],
        "reserved_sha256": hashlib.sha256(blob.encode()).hexdigest(),
        "reviewed": 0, "training_run": False,
    }
    args.committed_output.mkdir(parents=True, exist_ok=True)
    (args.committed_output / "reserved-ohf.json").write_text(json.dumps(record, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps({k: record[k] for k in ("rows", "families", "revisions", "by_file", "skipped_families", "reserved_sha256")}, indent=1))


if __name__ == "__main__":
    main()
