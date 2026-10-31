"""Question-versus-command boundary: the false-action rate of the existing Ask intent route (W-117, boundary item).

Offline scoring, no HA/MQTT/executor access. Two ways to get predictions, both through the package's own `evaluate.query`
(the same strict-schema call the route makes):

  python evaluate_boundary.py --model TAG --output report.json                  # local Ollama only, one query per row
  python evaluate_boundary.py --predictions preds.jsonl --output report.json    # JSONL {id, output}
  python evaluate_boundary.py --model TAG --queries-only preds.jsonl            # query only (no scoring, no contract import):
                                                                                # for a host without the package requirements;
                                                                                # score the file elsewhere with --predictions

Every output must pass the package's own contract validation (`contract.validate`). An output that is missing, is not a dict, or
fails validation is NOT a valid answer: the run is marked incomplete, the rates read NOT_MEASURED and the exit status is 1. A
prediction for an id that is not in the candidates is an error, as in `evaluate.py`.

False action  = a QUESTION row whose predicted intent is a command or proposal intent (set_device_state, set_device_attribute,
                propose_automation, express_need). The headline number is over the CLEAR questions; the ambiguous bucket ("Can I
                ...?" phrasings that are literally about what the app can do but may be read as indirect requests) is reported
                beside it, and the headline is also given with the ambiguous rows included.
Missed command = a COMMAND row predicted as answer_question.
Command outside its expected list = a command routed to a command or proposal intent that its row does not accept.
Rows are UNREVIEWED candidates until a human promotes them to gold; this report is evidence, not a release gate.
"""
import argparse
import hashlib
import json
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

ACTION_INTENTS = {"set_device_state", "set_device_attribute", "propose_automation", "express_need"}
CANDIDATES = HERE / "boundary-candidates.jsonl"


def read_rows(path):
    return [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]


def contract_validate():
    try:
        from contract import validate
    except ImportError as exc:  # jsonschema / python-dateutil missing: refuse rather than score unvalidated outputs
        raise SystemExit(f"cannot import the package contract ({exc}); install requirements.txt: scoring needs real validation")
    return validate


def check_output(output, validate):
    """Return (intent, None) for a valid output, or (None, reason) for a missing or invalid one."""
    if output is None:
        return None, "no output"
    if not isinstance(output, dict):
        return None, "output is not an object"
    try:
        validate(output)
    except Exception as exc:  # jsonschema.ValidationError, ValueError, KeyError: all mean not a valid intent
        return None, "fails contract validation: " + str(exc).splitlines()[0][:120]
    return output["intent"], None


def score(rows, predictions, validate=None):
    """Rows are the candidates; predictions maps id to the model's output dict (or None when it failed)."""
    validate = validate or contract_validate()
    ids = {r["id"] for r in rows}
    unknown = sorted(set(predictions) - ids)
    if unknown:
        raise ValueError(f"Unexpected prediction IDs: {unknown[:5]}")
    detail, invalid = [], {}
    for r in rows:
        got, why = check_output(predictions.get(r["id"]), validate)
        if why:
            invalid[r["id"]] = why
        kind, bucket = r["kind"], r.get("bucket", "clear")
        if got is None:
            verdict = "invalid" if r["id"] in predictions and predictions[r["id"]] is not None else "no_output"
        elif kind == "question":
            verdict = "false_action" if got in ACTION_INTENTS else ("ok" if got in r["expected_intents"] else "not_answered_as_question")
        else:
            if got == "answer_question":
                verdict = "missed_command"
            elif got in ACTION_INTENTS and got not in r["expected_intents"]:
                verdict = "command_outside_expected"
            else:
                verdict = "ok"
        detail.append({"id": r["id"], "kind": kind, "bucket": bucket, "area": r["area"], "text": r["text"],
                       "expected_intents": r["expected_intents"], "predicted_intent": got, "valid": why is None,
                       "invalid_reason": why, "verdict": verdict, "reason": r.get("reason")})
    complete = not invalid
    by = {}
    for d in detail:
        a = by.setdefault(d["area"], {"questions": 0, "false_actions": 0, "commands": 0, "missed_commands": 0})
        if d["kind"] == "question":
            a["questions"] += 1
            a["false_actions"] += d["verdict"] == "false_action"
        else:
            a["commands"] += 1
            a["missed_commands"] += d["verdict"] == "missed_command"
    clear_q = [d for d in detail if d["kind"] == "question" and d["bucket"] == "clear"]
    amb_q = [d for d in detail if d["kind"] == "question" and d["bucket"] != "clear"]
    cmds = [d for d in detail if d["kind"] == "command"]
    fa_clear = [d for d in clear_q if d["verdict"] == "false_action"]
    fa_amb = [d for d in amb_q if d["verdict"] == "false_action"]
    missed = [d for d in cmds if d["verdict"] == "missed_command"]
    outside = [d for d in cmds if d["verdict"] == "command_outside_expected"]
    not_q = [d for d in clear_q + amb_q if d["verdict"] == "not_answered_as_question"]

    def rate(num, den):
        return "NOT_MEASURED" if not complete else ((num / den) if den else None)

    return {
        "complete": complete,
        "questions": len(clear_q) + len(amb_q), "clear_questions": len(clear_q), "ambiguous_questions": len(amb_q),
        "commands": len(cmds),
        "false_action_count": len(fa_clear),
        "false_action_rate": rate(len(fa_clear), len(clear_q)),
        "ambiguous_routed_to_action_count": len(fa_amb),
        "false_action_rate_including_ambiguous": rate(len(fa_clear) + len(fa_amb), len(clear_q) + len(amb_q)),
        "missed_command_count": len(missed), "missed_command_rate": rate(len(missed), len(cmds)),
        "commands_outside_expected_count": len(outside),
        "questions_not_answered_as_question": len(not_q),
        "invalid_or_missing_outputs": invalid,
        "by_area": by,
        "false_action_cases": [{"id": d["id"], "text": d["text"], "predicted": d["predicted_intent"]} for d in fa_clear],
        "ambiguous_routed_to_action_cases": [{"id": d["id"], "text": d["text"], "predicted": d["predicted_intent"]} for d in fa_amb],
        "missed_command_cases": [{"id": d["id"], "text": d["text"], "predicted": d["predicted_intent"]} for d in missed],
        "commands_outside_expected_cases": [{"id": d["id"], "text": d["text"], "predicted": d["predicted_intent"]} for d in outside],
        "questions_not_answered_as_question_cases": [{"id": d["id"], "text": d["text"], "predicted": d["predicted_intent"]} for d in not_q],
        "rows": detail,
        "review_status": "UNREVIEWED candidates: evidence, not a gate",
        "release_eligible": False,
    }


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--candidates", type=Path, default=CANDIDATES)
    mode = p.add_mutually_exclusive_group(required=True)
    mode.add_argument("--predictions", type=Path)
    mode.add_argument("--model", help="Explicit local candidate or baseline tag")
    p.add_argument("--endpoint", default="http://127.0.0.1:11434/api/chat")
    p.add_argument("--timeout", type=float, default=180)
    p.add_argument("--output", type=Path)
    p.add_argument("--queries-only", type=Path, help="with --model: write the predictions JSONL and stop (no scoring); --output is not needed")
    p.add_argument("--model-tag", help="label for the report when scoring a --predictions file made earlier with this model")
    p.add_argument("--run-report", type=Path, help="also write the compact per-row report (no raw model output) to commit")
    args = p.parse_args()
    rows = read_rows(args.candidates)
    predictions, timings, errors = {}, {}, {}
    if args.predictions:
        for row in read_rows(args.predictions):
            if row["id"] in predictions:
                raise ValueError("Duplicate prediction ID")
            predictions[row["id"]] = row["output"]
            if "seconds" in row:
                timings[row["id"]] = row["seconds"]
            if row.get("transport_error"):
                errors[row["id"]] = row["transport_error"]
        sha = hashlib.sha256(args.predictions.read_bytes()).hexdigest()
    else:
        sha = None
        if args.endpoint not in {"http://127.0.0.1:11434/api/chat", "http://localhost:11434/api/chat"}:
            raise ValueError("This harness only contacts local Ollama")
        from evaluate import query
        for row in rows:
            start = time.monotonic()
            try:
                predictions[row["id"]] = query(row["text"], args.model, args.endpoint, args.timeout)
            except Exception as exc:
                errors[row["id"]] = type(exc).__name__
                predictions[row["id"]] = None
            timings[row["id"]] = time.monotonic() - start
    if args.queries_only:
        if not args.model:
            raise ValueError("--queries-only needs --model")
        args.queries_only.parent.mkdir(parents=True, exist_ok=True)
        with args.queries_only.open("w", encoding="utf-8") as handle:
            for row in rows:
                handle.write(json.dumps({"id": row["id"], "output": predictions[row["id"]], "seconds": round(timings[row["id"]], 2),
                                         "transport_error": errors.get(row["id"])}) + "\n")
        print(f"wrote {len(rows)} predictions ({len(errors)} transport errors, {round(sum(timings.values()))} s) to {args.queries_only}")
        return
    if not args.output:
        raise ValueError("--output is required unless --queries-only is used")
    report = score(rows, predictions)
    hashes = dict(candidates_sha256=hashlib.sha256(args.candidates.read_bytes()).hexdigest(),
                  prompt_sha256=hashlib.sha256((HERE.parent / "system.txt").read_bytes()).hexdigest(),
                  schema_sha256=hashlib.sha256((HERE.parent / "intent-slot.schema.json").read_bytes()).hexdigest(),
                  contract_sha256=hashlib.sha256((HERE.parent / "contract.py").read_bytes()).hexdigest())
    report.update(model=args.model or args.model_tag, predictions_file_sha256=sha, timings_seconds=timings,
                  transport_errors=errors, predictions=predictions, **hashes)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    if args.run_report:
        compact = {k: v for k, v in report.items() if k not in ("predictions", "timings_seconds")}
        compact["total_seconds"] = round(sum(timings.values()), 1)
        args.run_report.parent.mkdir(parents=True, exist_ok=True)
        args.run_report.write_text(json.dumps(compact, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    keys = ("complete", "clear_questions", "ambiguous_questions", "commands", "false_action_count", "false_action_rate",
            "ambiguous_routed_to_action_count", "false_action_rate_including_ambiguous", "missed_command_count",
            "missed_command_rate", "commands_outside_expected_count", "questions_not_answered_as_question")
    print(json.dumps({k: report[k] for k in keys}, indent=2))
    if not report["complete"]:
        print(f"INCOMPLETE: {len(report['invalid_or_missing_outputs'])} of {len(rows)} rows have no valid output "
              f"(transport errors: {len(errors)}); the rates above are NOT_MEASURED", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
