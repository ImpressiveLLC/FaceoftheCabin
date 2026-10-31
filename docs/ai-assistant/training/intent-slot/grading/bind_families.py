"""Bind the canonical S1 paraphrase families into a preserved derivative of the private sample.

The collector gave every Enfuse row the same source-file `group`, so splitting on `group` would put the whole sample in one split.
The committed S1 families map (`sft/s1/families.json`) assigns each selected id its own canonical family. This writes a derivative
where `group` is that family id and the original value is kept as `source_group`. The input file is never modified.
"""
import argparse
import hashlib
import json
import sys
from collections import Counter
from pathlib import Path


def read_jsonl(path):
    return [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]


def sha256_file(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def bind(rows, family_map):
    owner = {}
    for family, ids in family_map.items():
        for rid in ids:
            if rid in owner:
                raise ValueError(f"id {rid} appears in two families: {owner[rid]} and {family}")
            owner[rid] = family
    ids = [r["id"] for r in rows]
    dupes = sorted(i for i, n in Counter(ids).items() if n > 1)
    if dupes:
        raise ValueError(f"duplicate ids in the sample: {dupes[:5]}")
    unknown = sorted(i for i in ids if i not in owner)
    if unknown:
        raise ValueError(f"{len(unknown)} sample ids are not in the families map, e.g. {unknown[:5]}")
    out = []
    for row in rows:
        bound = dict(row)
        bound["source_group"] = row.get("group")
        bound["group"] = owner[row["id"]]
        out.append(bound)
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sample", type=Path, required=True)
    parser.add_argument("--families", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    if args.out.resolve() == args.sample.resolve():
        sys.exit("--out must be a different file: the original sample is preserved")
    before = sha256_file(args.sample)
    rows = read_jsonl(args.sample)
    bound = bind(rows, json.loads(args.families.read_text(encoding="utf-8")))
    args.out.write_text("".join(json.dumps(r, sort_keys=True) + "\n" for r in bound), encoding="utf-8")
    if sha256_file(args.sample) != before:
        sys.exit("the original sample changed during binding")
    print(json.dumps({"rows": len(bound), "source_groups": len({r["source_group"] for r in bound}),
                      "canonical_families": len({r["group"] for r in bound}),
                      "original_sha256": before, "derivative_sha256": sha256_file(args.out)}, indent=1))


if __name__ == "__main__":
    main()
