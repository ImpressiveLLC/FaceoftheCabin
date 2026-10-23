"""Require per-record human review; keep paraphrase groups and gold out of training."""
import argparse
import hashlib
import json
from pathlib import Path
from contract import ROOT, SYSTEM, read_jsonl, validate, write_jsonl, request_content


def normalize(text):
    return " ".join(text.casefold().split())


def split_rows(rows, gold):
    if not rows:
        raise ValueError("Empty dataset")
    forbidden_text = {normalize(r["text"]) for r in gold}
    forbidden_group = {r["group"] for r in gold}
    result = {"train": [], "validation": [], "test": []}
    seen = set()
    for row in rows:
        provenance = row["provenance"]
        if (provenance.get("review_status") != "approved" or
                not all(provenance.get(k) for k in ("reviewer", "reviewed_at", "source", "revision", "sha256", "license"))):
            raise ValueError(f"Missing human approval/provenance: {row['id']}")
        text = normalize(row["text"])
        if not text or text in seen or text in forbidden_text or row["group"] in forbidden_group:
            raise ValueError(f"Empty, duplicate or held-out example: {row['id']}")
        if not row["group"]:
            raise ValueError("Missing paraphrase family")
        seen.add(text)
        validate(row["target"], row.get("card_target"))
        bucket = int(hashlib.sha256(row["group"].encode()).hexdigest(), 16) % 10
        split = "test" if bucket == 0 else "validation" if bucket == 1 else "train"
        result[split].append({"messages": [{"role": "system", "content": SYSTEM},
                                            {"role": "user", "content": request_content(row["text"], row.get("card_target"))},
                                            {"role": "assistant", "content": json.dumps(row["target"])}],
                              "id": row["id"], "group": row["group"], "provenance": provenance})
    if any(not result[k] for k in result):
        raise ValueError("Need enough independent reviewed families for nonempty train/validation/test")
    return result


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("input", type=Path)
    p.add_argument("output_dir", type=Path)
    p.add_argument("--gold", type=Path, default=ROOT / "gold.jsonl")
    args = p.parse_args()
    splits = split_rows(read_jsonl(args.input), read_jsonl(args.gold))
    for split, rows in splits.items():
        write_jsonl(args.output_dir / f"{split}.jsonl", rows)
    print(json.dumps({k: len(v) for k, v in splits.items()}))
