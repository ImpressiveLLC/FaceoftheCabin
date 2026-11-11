"""Builds an entirely synthetic evaluation run for testing analyze_run.py.

Every question, answer, grade and latency here is invented. Nothing is read from, or resembles, the sealed set,
the frozen suite or any corpus document. Arm labels appear only in key.json; the grader pack carries blinded ids.
"""
import json
import random
from pathlib import Path

from analyze_run import CONTROL, PASS, TREATMENT, freeze

CLASSES = ("answerable", "probe_unsupported", "probe_other")
TAG = {"answerable": "answ", "probe_unsupported": "unsu", "probe_other": "othr"}
DEFAULT_GATES = {"latency_p95_max_s": 45.0}


def _write_run(run_dir, design, trial_rows, arm_of, grades_by_trial, rng, drop=(), extra_trials=()):
    """trial_rows: list of dicts (trial_id, question_id, repeat, status, latency_s, answer_text)."""
    run_dir = Path(run_dir)
    run_dir.mkdir(parents=True, exist_ok=True)
    kept = [t for t in trial_rows if t["trial_id"] not in set(drop)]
    kept += list(extra_trials)
    order = list(kept)
    rng.shuffle(order)
    blinded = {t["trial_id"]: "B" + "".join(rng.choice("0123456789abcdef") for _ in range(12)) for t in order}
    key = {
        "canary": "KEYSENTINEL-" + "".join(rng.choice("0123456789abcdef") for _ in range(16)),
        "rows": [{"blinded_id": blinded[t["trial_id"]], "trial_id": t["trial_id"], "arm": arm_of[t["trial_id"]]}
                 for t in order],
    }
    (run_dir / "design.json").write_text(json.dumps(design, indent=2, sort_keys=True), encoding="utf-8")
    (run_dir / "trials.jsonl").write_text(
        "\n".join(json.dumps(t, sort_keys=True) for t in kept) + "\n", encoding="utf-8")
    key_text = json.dumps(key, indent=2, sort_keys=True)
    (run_dir / "key.json").write_text(key_text, encoding="utf-8")
    (run_dir / "key.copy.json").write_text(key_text, encoding="utf-8")
    (run_dir / "grader_pack.jsonl").write_text(
        "\n".join(json.dumps({"blinded_id": blinded[t["trial_id"]], "question_id": t["question_id"],
                              "answer_text": t["answer_text"]}, sort_keys=True) for t in order) + "\n",
        encoding="utf-8")
    grades = {blinded[t["trial_id"]]: grades_by_trial[t["trial_id"]]
              for t in order if t["trial_id"] in grades_by_trial}
    (run_dir / "grades.json").write_text(json.dumps(grades, indent=2, sort_keys=True), encoding="utf-8")
    return freeze(run_dir)


def build_run(run_dir, *, seed=7, per_class=None, repeats=3, control_pass=0.45, treatment_pass=0.80,
              min_gain=0.10, draws=10000, drop=(), extra_trials=(), gates=None):
    """A random synthetic run. Class sizes default to a small set; real sizes are passed in via per_class."""
    per_class = per_class or {"answerable": 12, "probe_unsupported": 4, "probe_other": 4}
    rng = random.Random(seed)
    questions = [{"id": f"SYN-{TAG[klass]}-{i:03d}", "class": klass}
                 for klass in CLASSES for i in range(per_class.get(klass, 0))]
    design = {"questions": questions, "repeats": repeats, "seed": 20261109, "bootstrap_draws": draws,
              "min_gain": min_gain, "gates": gates or dict(DEFAULT_GATES)}
    trials, arm_of, grades = [], {}, {}
    for q in questions:
        for arm in (CONTROL, TREATMENT):
            for rep in range(1, repeats + 1):
                tid = f"T-{q['id']}-{arm[0]}{rep}"
                trials.append({"trial_id": tid, "question_id": q["id"], "repeat": rep, "status": "ok",
                               "latency_s": round(rng.uniform(4, 20), 1),
                               "answer_text": "synthetic answer " + "".join(rng.choice("abcdef") for _ in range(10))})
                arm_of[tid] = arm
                p = treatment_pass if arm == TREATMENT else control_pass
                ok = rng.random() < p
                grades[tid] = {"grade": PASS if ok else "FAIL", "wrong_fact": False,
                               "unsupported_claim": False, "flag": False}
    return _write_run(run_dir, design, trials, arm_of, grades, rng, drop=drop, extra_trials=extra_trials), design


def build_sentinel(run_dir):
    """A tiny hand-written run with a known answer. Four answerable questions, per-question PASS counts out of 3:
    control 0,1,2,3 and treatment 3,3,3,3, so the paired differences are 1, 2/3, 1/3, 0 and the mean is exactly 0.5.
    Two probe questions with no flags in either arm; every latency is 10 seconds."""
    design = {"questions": [{"id": f"SENT-A{i}", "class": "answerable"} for i in range(1, 5)]
              + [{"id": "SENT-P1", "class": "probe_unsupported"}, {"id": "SENT-P2", "class": "probe_other"}],
              "repeats": 3, "seed": 20261109, "bootstrap_draws": 10000, "min_gain": 0.10,
              "gates": dict(DEFAULT_GATES)}
    control_passes = {"SENT-A1": 0, "SENT-A2": 1, "SENT-A3": 2, "SENT-A4": 3}
    trials, arm_of, grades = [], {}, {}
    for q in design["questions"]:
        for arm in (CONTROL, TREATMENT):
            for rep in range(1, 4):
                tid = f"T-{q['id']}-{arm[0]}{rep}"
                trials.append({"trial_id": tid, "question_id": q["id"], "repeat": rep, "status": "ok",
                               "latency_s": 10.0, "answer_text": f"synthetic sentinel answer {q['id']} {rep}"})
                arm_of[tid] = arm
                if q["class"] == "answerable":
                    ok = True if arm == TREATMENT else rep <= control_passes[q["id"]]
                else:
                    ok = True
                grades[tid] = {"grade": PASS if ok else "FAIL", "wrong_fact": False,
                               "unsupported_claim": False, "flag": False}
    return _write_run(run_dir, design, trials, arm_of, grades, random.Random(1)), design
