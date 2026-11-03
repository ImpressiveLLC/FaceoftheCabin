#!/usr/bin/env python3
"""Run the 92 question-versus-command boundary rows through the real POST /api/helpdesk/propose route and report what came back.

evaluate_boundary.py scores the model alone (local Ollama). This runs the whole route: the model, the question-shape guard (QuestionShape),
the politeness-wrapper handling, the resolver and the proposal step, as a signed-in session. The route is OFF by default (CABIN_ASK_INTENT_ENABLED)
and, while on, answers only an administrator; it has no executor, so nothing here can run anything.

Per row it keeps only the route's status, intent and code and the latency. It never keeps or prints answer text.

  python run_boundary_route.py --base http://127.0.0.1:8090 --resident-admin-session --output report.json   # on the hub
  python run_boundary_route.py --base http://100.x.x.x:8090 --token-file PATH --output report.json          # elsewhere

--resident-admin-session reads the newest active administrator CabinSession from the hub's database through the same read-only query
scripts/ask_eval.py uses (it needs docker access on the hub). --token-file holds one line, the full Authorization header value
("CabinSession ..."); keep it outside the repository and out of chats and command lines. The output file must not exist.

Verdicts: a question row is a FALSE ACTION when the route returned a PROPOSAL; it is fine when answered or clarified. A command row is a
MISSED COMMAND when the route answered it as a question (ANSWER); a PROPOSAL, CLARIFY, REFUSED or TARGET is fine. Any row the route did not
return a status for (HTTP error, timeout) makes the rates NOT_MEASURED rather than a reassuring zero.
"""
import argparse
import json
import statistics
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
CANDIDATES = HERE / "boundary-candidates.jsonl"
QUESTION_OK = {"ANSWER", "CLARIFY"}
COMMAND_OK = {"PROPOSAL", "CLARIFY", "REFUSED", "TARGET"}


def verdict(kind, status):
    """kind is "question" or "command"; status is the route's status string or None when no response came back."""
    if status is None:
        return "no_response"
    if kind == "question":
        if status == "PROPOSAL":
            return "false_action"
        return "ok" if status in QUESTION_OK else "other"
    if status == "ANSWER":
        return "missed_command"
    return "ok" if status in COMMAND_OK else "other"


def percentile(values, q):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, max(0, round(q * (len(ordered) - 1))))]


def score(rows, results):
    """rows: candidate dicts; results: id -> {"status", "intent", "code", "millis"} (missing or status None when the call failed)."""
    detail = []
    for r in rows:
        got = results.get(r["id"]) or {}
        detail.append({"id": r["id"], "kind": r["kind"], "bucket": r.get("bucket", "clear"), "area": r["area"],
                       "status": got.get("status"), "intent": got.get("intent"), "code": got.get("code"),
                       "millis": got.get("millis"), "verdict": verdict(r["kind"], got.get("status"))})
    complete = all(d["verdict"] != "no_response" for d in detail)
    questions = [d for d in detail if d["kind"] == "question"]
    commands = [d for d in detail if d["kind"] == "command"]
    false_actions = [d for d in questions if d["verdict"] == "false_action"]
    missed = [d for d in commands if d["verdict"] == "missed_command"]
    millis = [d["millis"] for d in detail if d["millis"] is not None]

    def rate(num, den):
        return "NOT_MEASURED" if not complete else (num / den if den else None)

    by_status = {}
    for d in detail:
        by_status[d["status"]] = by_status.get(d["status"], 0) + 1
    return {
        "complete": complete, "rows": len(detail), "questions": len(questions), "commands": len(commands),
        "question_to_action_count": len(false_actions), "question_to_action_rate": rate(len(false_actions), len(questions)),
        "missed_command_count": len(missed), "missed_command_rate": rate(len(missed), len(commands)),
        "no_response_count": sum(d["verdict"] == "no_response" for d in detail),
        "by_status": by_status,
        "latency_ms": {"p50": percentile(millis, 0.50), "p95": percentile(millis, 0.95), "mean": round(statistics.fmean(millis)) if millis else None},
        "false_action_ids": [d["id"] for d in false_actions], "missed_command_ids": [d["id"] for d in missed],
        "detail": detail, "release_eligible": False,
    }


def post(base, token, text, timeout):
    request = urllib.request.Request(base.rstrip("/") + "/api/helpdesk/propose", data=json.dumps({"text": text}).encode("utf-8"),
                                     headers={"Content-Type": "application/json", "Authorization": token}, method="POST")
    started = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = json.loads(response.read().decode("utf-8"))
    except (urllib.error.URLError, TimeoutError, ValueError, OSError):
        return {"status": None, "intent": None, "code": None, "millis": round((time.monotonic() - started) * 1000)}
    return {"status": body.get("status"), "intent": body.get("intent"), "code": body.get("code"),
            "millis": round((time.monotonic() - started) * 1000)}


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--base", required=True, help="backend origin, e.g. http://cabin-hub:8090")
    auth = p.add_mutually_exclusive_group(required=True)
    auth.add_argument("--token-file", type=Path)
    auth.add_argument("--resident-admin-session", action="store_true", help="read the administrator session on this hub (scripts/ask_eval.py)")
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--candidates", type=Path, default=CANDIDATES)
    p.add_argument("--timeout", type=float, default=120)
    args = p.parse_args(argv)
    if args.output.exists():
        print("refusing to overwrite an existing output file", file=sys.stderr)
        return 2
    if args.resident_admin_session:
        sys.path.insert(0, str(HERE.parents[4] / "scripts"))
        from ask_eval import resident_session  # same read-only lookup the Ask harness uses; prints nothing
        token = "CabinSession " + resident_session()
    else:
        token = args.token_file.read_text(encoding="utf-8").strip()
    rows = [json.loads(line) for line in args.candidates.read_text(encoding="utf-8").splitlines() if line.strip()]
    results = {r["id"]: post(args.base, token, r["text"], args.timeout) for r in rows}
    report = score(rows, results)
    args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    summary = {k: report[k] for k in ("complete", "rows", "questions", "commands", "question_to_action_count", "question_to_action_rate",
                                      "missed_command_count", "missed_command_rate", "no_response_count", "by_status", "latency_ms")}
    print(json.dumps(summary, indent=2))
    return 0 if report["complete"] else 1


if __name__ == "__main__":
    sys.exit(main())
