#!/usr/bin/env python3
"""Bridge script: turns an already-graded eval round into an SFT training
set. Reads the real, existing output of docs/ai-assistant/rag/scripts/
eval_pipeline.py and grade_pipeline.py -- does not invent a new data format.

VERIFIED 2026-09-14: run for real against claude-code/20260908-r3 on the
M920q, produced 9 examples across Q04/Q08/Q18. Reading that output
surfaced two real grading-data-quality issues (see KNOWN_GRADING_ISSUES
below) -- not a defect in this script, a defect in the source grading
that this script now works around explicitly and reversibly.

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
EXCLUDED_PATH = Path(__file__).parent / "data" / "sft_dataset.excluded.jsonl"

# Corrections found by actually reading the exported training data on the
# real M920q run (2026-09-14), not a hypothetical concern. Grading happens
# once per QUESTION, not per repeat/trial, so a single PASS verdict can
# pull in a repeat that doesn't deserve it. These are narrow, cited,
# reversible exclusions -- NOT an edit to the original grades.json/trial
# files (those stay untouched, append-only) -- and NOT a new subjective
# judgment call where one wasn't already made:
#
# - Q18 (all repeats, round claude-code/20260908-r3): the answer lists
#   "Driveway camera" and "Front door" as required inputs for a system
#   explicitly requested with no cameras or zone presence -- this is the
#   exact bug already documented as STILL FAILING in the
#   claude-code/20260908-r2-postfix grading notes ("All 3 repeats list
#   'driveway' and 'front_door' (camera entityRefs) as required inputs
#   for an explicitly no-camera install"), matching that question's own
#   documented fail_if criterion verbatim. r3 graded the same unfixed
#   behavior PASS -- two grading rounds directly disagree on the same
#   bug. This propagates the already-established r2-postfix finding
#   rather than making a fresh call.
# - Q04 repeat 1 (round claude-code/20260908-r3): a flat "I don't know."
#   -- not wrong, just a non-answer -- while the other two repeats of the
#   same PASS-graded question actually answer. Genuinely re-grading which
#   repeat(s) should count needs a human at grade_pipeline.py's
#   interactive prompt (it refuses to run non-interactively by design);
#   this is a narrow, mechanical exclusion of one clearly-non-answering
#   repeat, not a substitute for that real re-grade.
#
# Remove an entry here once the underlying question has been properly
# re-graded (Q18) or re-run with a consistent, reviewed answer (Q04) --
# excluded examples aren't deleted, they land in sft_dataset.excluded.jsonl
# instead, specifically so they're easy to pull back in later.
KNOWN_GRADING_ISSUES = {
    ("claude-code", "20260908-r3", "Q18", None): (
        "Answer violates its own documented fail_if (recommends camera "
        "entities for an explicitly no-camera install) -- matches the "
        "claude-code/20260908-r2-postfix finding for the same unfixed "
        "bug, which correctly graded this FAIL. r3's PASS verdict "
        "contradicts that finding."
    ),
    ("claude-code", "20260908-r3", "Q04", 1): (
        "This repeat is a flat 'I don't know.' non-answer, inconsistent "
        "with the other 2 repeats of the same PASS-graded question. "
        "Needs real per-repeat re-grading via grade_pipeline.py's "
        "interactive prompt, not a mechanical fix."
    ),
}


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
    excluded = []
    for qid in pass_ids:
        for repeat_idx, trial in enumerate(trials.get(qid, [])):
            if not trial.get("answer") or trial.get("error"):
                continue
            ex = {
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
            # Check both a whole-question exclusion (repeat=None) and a
            # specific-repeat exclusion for this exact trial.
            reason = KNOWN_GRADING_ISSUES.get(
                (args.agent, args.round, qid, None)
            ) or KNOWN_GRADING_ISSUES.get((args.agent, args.round, qid, repeat_idx))
            if reason:
                ex["excluded_reason"] = reason
                ex["repeat"] = repeat_idx
                excluded.append(ex)
            else:
                examples.append(ex)

    if not examples and not excluded:
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

    if excluded:
        with EXCLUDED_PATH.open("w") as f:
            for ex in excluded:
                f.write(json.dumps(ex) + "\n")
        print(f"Excluded {len(excluded)} known-bad example(s) to {EXCLUDED_PATH} (see KNOWN_GRADING_ISSUES):")
        for ex in excluded:
            print(f"  - {ex['question_id']} repeat {ex['repeat']}: {ex['excluded_reason']}")


if __name__ == "__main__":
    main()
