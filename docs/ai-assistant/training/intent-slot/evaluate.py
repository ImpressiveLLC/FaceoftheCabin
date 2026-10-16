"""Offline-file or local Ollama evaluation. No HA/MQTT or executor access."""
import argparse
import hashlib
import json
import time
import urllib.request
from pathlib import Path
from contract import ROOT, SCHEMA, SYSTEM, read_jsonl, validate


def slot_pairs(value):
    return {(k, str(v).casefold().strip()) for k, v in value.get("slots", {}).items()}


def score(gold, predictions):
    ids = [r["id"] for r in gold]
    if not ids or len(ids) != len(set(ids)):
        raise ValueError("Gold must be nonempty with unique IDs")
    if set(predictions) - set(ids):
        raise ValueError("Unexpected prediction IDs")
    valid = correct = tp = fp = fn = critical = 0
    details = []
    for row in gold:
        validate(row["target"])
        expected = row["target"]
        actual = predictions.get(row["id"])
        error = None
        try:
            validate(actual)
        except Exception as exc:
            error = str(exc).splitlines()[0]
        passed = error is None
        valid += passed
        predicted_slots = slot_pairs(actual) if passed else set()
        expected_slots = slot_pairs(expected)
        tp += len(predicted_slots & expected_slots)
        fp += len(predicted_slots - expected_slots)
        fn += len(expected_slots - predicted_slots)
        intent_ok = passed and actual["intent"] == expected["intent"]
        correct += intent_ok
        semantic_ok = (intent_ok and predicted_slots == expected_slots and
                       actual["requires_confirmation"] == expected["requires_confirmation"] and
                       actual["automation"] == expected["automation"])
        # Clarification wording can differ; contract requires a nonempty question.
        if row.get("critical") and not semantic_ok:
            critical += 1
        details.append({"id": row["id"], "valid": passed, "intent_correct": intent_ok,
                        "semantic_match": semantic_ok, "error": error})
    n = len(gold)
    f1 = 2 * tp / (2 * tp + fp + fn) if (2 * tp + fp + fn) else 1.0
    gates = {"structured_output": valid / n >= .95,
             "intent_accuracy": correct / n >= .90, "slot_f1": f1 >= .90,
             "critical_fixture_failures": critical == 0}
    return {"count": n, "valid_output_rate": valid / n, "intent_accuracy": correct / n,
            "slot_micro_f1": f1, "slot_counts": {"tp": tp, "fp": fp, "fn": fn},
            "critical_fixture_failures": critical, "offline_gates": gates,
            "offline_pass": all(gates.values()),
            "integration_gates": {"accepted_invented_entities": "NOT_MEASURED",
                                  "execution_without_confirmation": "NOT_MEASURED",
                                  "critical_runtime_safety_failures": "NOT_MEASURED",
                                  "rag_regression": "NOT_MEASURED"},
            "release_eligible": False, "details": details}


def query(text, model, endpoint, timeout):
    body = {"model": model, "stream": False, "format": SCHEMA,
            "options": {"temperature": 0}, "messages": [
                {"role": "system", "content": SYSTEM}, {"role": "user", "content": text}]}
    request = urllib.request.Request(endpoint, data=json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(json.loads(response.read())["message"]["content"])


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--gold", type=Path, default=ROOT / "gold.jsonl")
    mode = p.add_mutually_exclusive_group(required=True)
    mode.add_argument("--predictions", type=Path, help="JSONL {id, output} records")
    mode.add_argument("--model", help="Explicit local candidate or baseline tag")
    p.add_argument("--endpoint", default="http://127.0.0.1:11434/api/chat")
    p.add_argument("--timeout", type=float, default=180)
    p.add_argument("--output", required=True, type=Path)
    args = p.parse_args()
    gold = read_jsonl(args.gold)
    predictions, timings, errors = {}, {}, {}
    if args.predictions:
        for row in read_jsonl(args.predictions):
            if row["id"] in predictions:
                raise ValueError("Duplicate prediction ID")
            predictions[row["id"]] = row["output"]
    else:
        if args.endpoint not in {"http://127.0.0.1:11434/api/chat", "http://localhost:11434/api/chat"}:
            raise ValueError("This harness only contacts local Ollama")
        for row in gold:
            start = time.monotonic()
            try:
                predictions[row["id"]] = query(row["text"], args.model, args.endpoint, args.timeout)
            except Exception as exc:
                errors[row["id"]] = type(exc).__name__
                predictions[row["id"]] = None
            timings[row["id"]] = time.monotonic() - start
    report = score(gold, predictions)
    report.update(model=args.model, gold_sha256=hashlib.sha256(args.gold.read_bytes()).hexdigest(),
                  timings_seconds=timings, transport_errors=errors, predictions=predictions,
                  fixture_review_status="pending-human-review")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k not in {"details", "predictions", "timings_seconds"}}))
    raise SystemExit(0 if report["offline_pass"] else 1)


if __name__ == "__main__":
    main()
