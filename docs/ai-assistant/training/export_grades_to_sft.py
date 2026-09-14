#!/usr/bin/env python3
"""Bridge script: turns an already-graded eval round into an SFT training
set. Reads the real, existing output of docs/ai-assistant/rag/scripts/
eval_pipeline.py and grade_pipeline.py -- does not invent a new data format.

UNVERIFIED as of this write (2026-09-14): written against the real trial/
grade JSON schemas confirmed live on the M920q
(~/eval-results/claude-code/20260908-r3/*.jsonl and *-grades.json), but
this script itself has not been run. Run it for real before trusting its
output.

Usage:
    python export_grades_to_sft.py --agent claude-code --round 20260908-r3
    python export_grades_to_sft.py --agent claude-code --round 20260908-r2-postfix
"""
import argparse
import json
import sys
from pathlib import Path

EVAL_RESULTS_ROOT = Path.home() / "eval-results"
OUTPUT_PATH = Path(__file__).parent / "data" / "sft_dataset.jsonl"


def load_trials(jsonl_path: Path) -> dict[str, list[dict]]:
    """Returns {question_id: [trial_record, ...]} -- a question can have
    more than one repeat. Skips the leading {"type": "meta", ...} line."""
    trials: dict[str, list[dict]] = {}
    with jsonl_path.open() as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            record = json.loads(line)
            if record.get("type") != "trial":
                continue
            trials.setdefault(record["id"], []).append(record)
    return trials


def load_pass_ids(grades_path: Path) -> set[str] | None:
    """Returns the set of question ids graded PASS, or None if this
    grades file doesn't have a flat {id: verdict} map at all (e.g. the
    r2-postfix format, which is narrative/qualitative -- key_finding +
    other_notes, no per-question grades dict). Callers must handle None
    by skipping the round with a clear message, not by guessing."""
    with grades_path.open() as f:
        data = json.load(f)
    grades = data.get("grades")
    if not isinstance(grades, dict):
        return None
    return {qid for qid, verdict in grades.items() if verdict == "PASS"}, data.get("graded_at")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--agent", required=True, help="e.g. claude-code")
    parser.add_argument("--round", required=True, help="e.g. 20260908-r3")
    parser.add_argument(
        "--eval-results-root",
        type=Path,
        default=EVAL_RESULTS_ROOT,
        help="Override if eval-results doesn't live under $HOME on this machine.",
    )
    args = parser.parse_args()

    round_dir = args.eval_results_root / args.agent / args.round
    if not round_dir.is_dir():
        sys.exit(f"No such round directory: {round_dir}")

    jsonl_candidates = list(round_dir.glob(f"eval-{args.agent}-*.jsonl"))
    grades_candidates = list(round_dir.glob("*-grades.json"))
    if len(jsonl_candidates) != 1:
        sys.exit(f"Expected exactly one trial jsonl in {round_dir}, found {len(jsonl_candidates)}")
    if len(grades_candidates) != 1:
        sys.exit(
            f"Expected exactly one *-grades.json in {round_dir}, found {len(grades_candidates)} "
            "-- see grading-runbook.md's own gotcha about stray backup grade files."
        )

    trials = load_trials(jsonl_candidates[0])
    result = load_pass_ids(grades_candidates[0])
    if result is None:
        sys.exit(
            f"{grades_candidates[0].name} has no flat {{id: verdict}} grades map "
            "(this is the narrative/qualitative format, e.g. r2-postfix) -- "
            "nothing machine-gradeable to export from this round. Pick a round "
            "with a real grades dict instead."
        )
    pass_ids, graded_at = result

    examples = []
    for qid in pass_ids:
        for trial in trials.get(qid, []):
            if not trial.get("answer") or trial.get("error"):
                continue
            examples.append(
                {
                    "messages": [
                        {"role": "user", "content": trial["question"]},
                        {"role": "assistant", "content": trial["answer"]},
                    ],
                    "source": "human-graded",
                    "agent_id": args.agent,
                    "round": args.round,
                    "graded_at": graded_at,
                    "question_id": qid,
                }
            )

    if not examples:
        sys.exit(
            f"Zero PASS-graded, non-error trials found for {args.agent}/{args.round} -- "
            "nothing to write. This is a real, honest outcome, not a bug: check "
            "the grades file directly if this is unexpected."
        )

    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    with OUTPUT_PATH.open("w") as f:
        for ex in examples:
            f.write(json.dumps(ex) + "\n")

    print(f"Wrote {len(examples)} example(s) to {OUTPUT_PATH}")
    print(f"Question ids included: {sorted({e['question_id'] for e in examples})}")


if __name__ == "__main__":
    main()
