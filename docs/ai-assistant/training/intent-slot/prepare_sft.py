"""Require per-record human review; keep paraphrase groups and gold out of training."""
import argparse
import hashlib
import json
from pathlib import Path
from contract import ROOT, SYSTEM, read_jsonl, validate, write_jsonl, request_content
from vocabulary import field_errors, normalize_target


def normalize(text):
    return " ".join(text.casefold().split())


def check_agent_audit(row, provenance):
    """An agent label counts only after a human audit of its exact cohort passed at 90 percent or better and is linked to the row.

    The link is the cohort hash and audit-sample hash recorded at promotion, the sample size the 10 percent rule requires, and the
    row's own hash of id, text and target at agreement time (so a row edited after the audit is refused). `audited_by` is attribution.
    """
    audit = provenance.get("audit_result") or {}
    hexes = all(isinstance(audit.get(k), str) and len(audit[k]) == 64 for k in ("cohort_sha256", "audit_ids_sha256"))
    cohort_n = audit.get("cohort_n", 0)
    required = max(1, round(cohort_n * 0.1)) if cohort_n else 0
    ok = (audit.get("audited_by") and hexes and audit.get("rate", 0) >= 0.9 and audit.get("track") == provenance.get("track")
          and cohort_n > 0 and audit.get("sample_n", 0) >= required)
    if not ok:
        raise ValueError(f"Agent label without a passed, linked human audit: {row['id']}")
    bound = hashlib.sha256(json.dumps([row["id"], row["text"], row["target"]], sort_keys=True, ensure_ascii=False).encode()).hexdigest()
    if provenance.get("agreed_row_sha256") != bound:
        raise ValueError(f"Agent row changed after its audit: {row['id']}")


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
        if provenance.get("reviewer_kind") == "agent":
            check_agent_audit(row, provenance)
        text = normalize(row["text"])
        if not text or text in seen or text in forbidden_text or row["group"] in forbidden_group:
            raise ValueError(f"Empty, duplicate or held-out example: {row['id']}")
        if not row["group"]:
            raise ValueError("Missing paraphrase family")
        seen.add(text)
        validate(row["target"], row.get("card_target"))
        problems = field_errors(row["target"])
        if not problems and normalize_target(row["target"])[1]:
            problems = {"canonical": "a room or site is not in its canonical form; approve it in the grading screen, which normalizes it"}
        if problems:
            raise ValueError(f"Room, site or need not in the vocabulary ({row['id']}): {'; '.join(problems.values())}")
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
