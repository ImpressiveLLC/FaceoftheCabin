"""Record one evaluation round and compare it with the previous one.

  python round_ledger.py record REPORT.json --round r1 --out rounds/r1.json
  python round_ledger.py compare rounds/r1.json rounds/r2.json

A ledger entry keeps the pathway: which prompt and gold produced which per-intent
results, which fixtures failed and how they were confused. `compare` flags the ways
a round can quietly get out of sync with its goal (see docs/ai-assistant/proposals/sme-loop.md, "Self-healing").
"""
import argparse
import collections
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def record(report, gold_rows, round_id, prompt_sha):
    gold = {g["id"]: g for g in gold_rows}
    preds = report["predictions"]
    # The evaluator is the authority: an output that fails the schema is wrong even if its label matches.
    details = {d["id"]: d for d in report["details"]}
    per_intent = collections.defaultdict(lambda: [0, 0])
    confusions = collections.Counter()
    failed = {}
    for gid, g in gold.items():
        want = g["target"]["intent"]
        d = details.get(gid, {})
        got = (preds.get(gid) or {}).get("intent", "NONE") if d.get("valid") else "INVALID"
        per_intent[want][1] += 1
        if d.get("intent_correct"):
            per_intent[want][0] += 1
        else:
            confusions[f"{want}->{got}"] += 1
            failed[gid] = {"want": want, "got": got, "critical": bool(g.get("critical"))}
    return {
        "round": round_id, "model": report.get("model"), "prompt_sha256": prompt_sha,
        "gold_sha256": report.get("gold_sha256"), "fixtures": len(gold),
        "valid_output_rate": report.get("valid_output_rate"),
        "intent_accuracy": report.get("intent_accuracy"),
        "slot_micro_f1": report.get("slot_micro_f1"),
        "critical_fixture_failures": report.get("critical_fixture_failures"),
        "per_intent": {k: {"correct": v[0], "total": v[1]} for k, v in sorted(per_intent.items())},
        "confusions": dict(confusions.most_common()), "failed": failed,
        "released": False,
    }


def compare(prev, cur):
    """Return (lines, warnings). Warnings are the self-healing triggers."""
    warnings = []
    if prev["gold_sha256"] != cur["gold_sha256"]:
        warnings.append("GOLD CHANGED between rounds: scores are not comparable; re-run the previous prompt on this gold first.")
    if prev["prompt_sha256"] == cur["prompt_sha256"]:
        warnings.append("PROMPT UNCHANGED: this round tests nothing new (check the host used the new system.txt).")
    regressed = sorted(set(cur["failed"]) - set(prev["failed"]))
    if regressed:
        warnings.append(f"REGRESSION: previously passing fixtures now fail: {', '.join(regressed)}")
    for key in ("valid_output_rate", "intent_accuracy", "slot_micro_f1"):
        if cur[key] is not None and prev[key] is not None and cur[key] < prev[key] - 0.01:
            warnings.append(f"METRIC DROP: {key} fell from {prev[key]:.3f} to {cur[key]:.3f}; a change that helps one metric and hurts another is not an improvement.")
    if cur["critical_fixture_failures"] is not None and prev["critical_fixture_failures"] is not None             and cur["critical_fixture_failures"] > prev["critical_fixture_failures"]:
        warnings.append(f"CRITICAL FAILURES ROSE: {prev['critical_fixture_failures']} -> {cur['critical_fixture_failures']}.")
    new_critical = [i for i in regressed if cur["failed"][i]["critical"]]
    if new_critical:
        warnings.append(f"NEW CRITICAL FAILURES: {', '.join(new_critical)}; stop and revert the prompt change before iterating.")
    lines = []
    for key in ("valid_output_rate", "intent_accuracy", "slot_micro_f1", "critical_fixture_failures"):
        lines.append(f"{key}: {prev[key]} -> {cur[key]}")
    for intent in sorted(set(prev["per_intent"]) | set(cur["per_intent"])):
        a, b = prev["per_intent"].get(intent, {}), cur["per_intent"].get(intent, {})
        lines.append(f"{intent}: {a.get('correct', 0)}/{a.get('total', 0)} -> {b.get('correct', 0)}/{b.get('total', 0)}")
    return lines, warnings


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("record")
    r.add_argument("report", type=Path)
    r.add_argument("--round", required=True)
    r.add_argument("--out", type=Path, required=True)
    r.add_argument("--gold", type=Path, default=ROOT / "gold.jsonl")
    r.add_argument("--prompt", type=Path, default=ROOT / "system.txt")
    c = sub.add_parser("compare")
    c.add_argument("previous", type=Path)
    c.add_argument("current", type=Path)
    a = p.parse_args()
    if a.cmd == "record":
        gold = [json.loads(x) for x in a.gold.read_text(encoding="utf-8").splitlines() if x.strip()]
        report = json.loads(a.report.read_text(encoding="utf-8"))
        entry = record(report, gold, a.round, sha(a.prompt))
        if entry["gold_sha256"] != sha(a.gold):
            raise SystemExit("report gold hash differs from --gold bytes; grade the exact fixtures the model saw")
        a.out.parent.mkdir(parents=True, exist_ok=True)
        a.out.write_text(json.dumps(entry, indent=2) + "\n", encoding="utf-8")
        print(f"recorded {a.round}: {entry['intent_accuracy']} intent accuracy, {len(entry['failed'])} failed fixtures")
    else:
        lines, warnings = compare(json.loads(a.previous.read_text()), json.loads(a.current.read_text()))
        print("\n".join(lines))
        print("\n".join("WARNING: " + w for w in warnings) or "no drift warnings")
        raise SystemExit(1 if warnings else 0)


if __name__ == "__main__":
    main()
