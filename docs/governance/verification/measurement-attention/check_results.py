"""Validate the independent oracle catalog; optionally compare implementation evidence.

No production evaluator is imported. Missing evidence is NOT_RUN, never a pass.
Implementation adapters return [{"id": ..., "actual": {...}}]; expected keys
must match exactly, while additional diagnostic keys are permitted.
"""
import argparse
import json
from pathlib import Path


def validate(catalog):
    cases = catalog["cases"]
    ids = [c["id"] for c in cases + catalog["humanCases"]]
    if not cases or len(ids) != len(set(ids)):
        raise ValueError("Empty catalog or duplicate case IDs")
    for case in cases:
        if not case.get("contract") or not case.get("expected") or not isinstance(case.get("input"), dict):
            raise ValueError(f"Incomplete contract case: {case.get('id')}")
    for case in catalog["humanCases"]:
        if case.get("status") != "NOT_RUN" or not case.get("reason"):
            raise ValueError("Human evidence belongs in a separate observed trial report")
    return cases


def contains(actual, expected):
    if isinstance(expected, dict):
        return isinstance(actual, dict) and all(k in actual and contains(actual[k], v) for k, v in expected.items())
    if isinstance(actual, bool) or isinstance(expected, bool):
        return type(actual) is type(expected) and actual == expected
    return actual == expected


def compare(cases, results):
    by_id = {}
    allowed = {c["id"] for c in cases}
    for row in results:
        if row.get("id") not in allowed or row["id"] in by_id:
            raise ValueError("Unknown or duplicate implementation case ID")
        by_id[row["id"]] = row
    report = []
    for case in cases:
        row = by_id.get(case["id"])
        status = "NOT_RUN" if row is None else "PASS" if contains(row.get("actual"), case["expected"]) else "FAIL"
        report.append({"id": case["id"], "status": status})
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--results", type=Path, help="JSON implementation output; never the oracle itself")
    args = parser.parse_args()
    catalog = json.loads(Path(__file__).with_name("oracle-cases.json").read_text(encoding="utf8"))
    cases = validate(catalog)
    report = compare(cases, json.loads(args.results.read_text(encoding="utf8")) if args.results else [])
    counts = {s: sum(r["status"] == s for r in report) for s in ("PASS", "FAIL", "NOT_RUN")}
    print(json.dumps({"catalog": "VALID", "implementation": counts, "cases": report, "human": catalog["humanCases"]}, indent=2))
    raise SystemExit(1 if counts["FAIL"] else 2 if counts["NOT_RUN"] else 0)
