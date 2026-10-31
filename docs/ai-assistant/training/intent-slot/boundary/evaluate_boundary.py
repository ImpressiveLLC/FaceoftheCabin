"""Question-versus-command boundary: the false-action rate of the existing Ask intent route (W-117, boundary item).

Offline scoring, no HA/MQTT/executor access. Two ways to get predictions, both through the package's own `evaluate.query`
(the same strict-schema call the route makes):

  python evaluate_boundary.py --model TAG --output report.json       # local Ollama only, one query per row
  python evaluate_boundary.py --predictions preds.jsonl --output report.json    # JSONL {id, output}

False action  = a QUESTION-shaped row whose predicted intent is a command or proposal intent (set_device_state,
                set_device_attribute, propose_automation, express_need). The number that matters; it should be zero.
Missed command = a COMMAND row predicted as answer_question (the other direction of the boundary).
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


def intent_of(output):
    return output.get("intent") if isinstance(output, dict) else None


def score(rows, predictions):
    """Rows are the candidates; predictions maps id to the model's output dict (or None when it failed)."""
    questions = [r for r in rows if r["kind"] == "question"]
    commands = [r for r in rows if r["kind"] == "command"]
    by_area = {}
    false_actions, missed, wrong_question, no_output = [], [], [], []
    for r in rows:
        area = by_area.setdefault(r["area"], {"questions": 0, "false_actions": 0, "commands": 0, "missed_commands": 0})
        got = intent_of(predictions.get(r["id"]))
        if got is None:
            no_output.append(r["id"])
        if r["kind"] == "question":
            area["questions"] += 1
            if got in ACTION_INTENTS:
                area["false_actions"] += 1
                false_actions.append({"id": r["id"], "text": r["text"], "area": r["area"], "predicted": got})
            if got is not None and got != "answer_question":
                wrong_question.append({"id": r["id"], "text": r["text"], "predicted": got})
        else:
            area["commands"] += 1
            if got == "answer_question":
                area["missed_commands"] += 1
                missed.append({"id": r["id"], "text": r["text"], "area": r["area"], "predicted": got})
    nq, nc = len(questions), len(commands)
    # A rate over rows the model never answered is not a measurement: with every query failed the "false-action rate" would read
    # a reassuring 0.0. So a run with ANY row missing an output reports NOT_MEASURED for the rates and is marked incomplete.
    complete = not no_output
    return {
        "complete": complete,
        "questions": nq, "commands": nc,
        "false_action_count": len(false_actions),
        "false_action_rate": ((len(false_actions) / nq) if nq else None) if complete else "NOT_MEASURED",
        "missed_command_count": len(missed),
        "missed_command_rate": ((len(missed) / nc) if nc else None) if complete else "NOT_MEASURED",
        "questions_not_answered_as_question": len(wrong_question),
        "rows_without_output": sorted(no_output),
        "by_area": by_area,
        "false_action_cases": false_actions,
        "missed_command_cases": missed,
        "questions_not_answered_as_question_cases": wrong_question,
        "review_status": "UNREVIEWED candidates: evidence, not a gate",
    }


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--candidates", type=Path, default=CANDIDATES)
    mode = p.add_mutually_exclusive_group(required=True)
    mode.add_argument("--predictions", type=Path)
    mode.add_argument("--model", help="Explicit local candidate or baseline tag")
    p.add_argument("--endpoint", default="http://127.0.0.1:11434/api/chat")
    p.add_argument("--timeout", type=float, default=180)
    p.add_argument("--output", required=True, type=Path)
    args = p.parse_args()
    rows = read_rows(args.candidates)
    predictions, timings, errors = {}, {}, {}
    if args.predictions:
        for row in read_rows(args.predictions):
            if row["id"] in predictions:
                raise ValueError("Duplicate prediction ID")
            predictions[row["id"]] = row["output"]
    else:
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
    report = score(rows, predictions)
    report.update(model=args.model,
                  candidates_sha256=hashlib.sha256(args.candidates.read_bytes()).hexdigest(),
                  prompt_sha256=hashlib.sha256((HERE.parent / "system.txt").read_bytes()).hexdigest(),
                  schema_sha256=hashlib.sha256((HERE.parent / "intent-slot.schema.json").read_bytes()).hexdigest(),
                  timings_seconds=timings, transport_errors=errors, predictions=predictions)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({k: report[k] for k in ("complete", "questions", "commands", "false_action_count", "false_action_rate",
                                             "missed_command_count", "missed_command_rate",
                                             "questions_not_answered_as_question")}, indent=2))
    if not report["complete"]:
        print(f"INCOMPLETE: {len(report['rows_without_output'])} of {len(rows)} rows have no output "
              f"(transport errors: {len(errors)}); the rates above are NOT_MEASURED", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
