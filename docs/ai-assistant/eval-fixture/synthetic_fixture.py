"""Builds an entirely synthetic evaluation run for testing analyze_run.py.

Every question, answer, gold fact, grade and latency here is invented. Nothing is read from, or resembles, the
sealed set, the frozen suite or any corpus document. Arm labels appear only under <run>/key/. The grader pack
carries blinded ids, the question text and the frozen gold, and nothing that names an arm or a trial.
"""
import json
import random
from pathlib import Path

from analyze_run import CONTROL, PASS, TREATMENT, freeze

CLASSES = ("answerable", "partial", "probe_unsupported")
TAG = {"answerable": "answ", "partial": "part", "probe_unsupported": "unsu"}
BEHAVIOR = {"answerable": "answer", "partial": "answer_supported_part_and_flag_the_rest",
            "probe_unsupported": "decline_or_no_context"}
FALLBACK = "I do not have any information about that yet."
FAIL = "FAIL"


def grade(ok, wrong_fact=False, unsupported_claim=False, flag=False):
    return {"grade": PASS if ok else FAIL, "wrong_fact": wrong_fact,
            "unsupported_claim": unsupported_claim, "flag": flag}


def gold_for(questions):
    return {q["id"]: {"question_text": f"Synthetic question {q['id']}?",
                      "expected_behavior": BEHAVIOR[q["class"]],
                      "gold_facts": [f"synthetic fact {q['id']}-1", f"synthetic fact {q['id']}-2"],
                      "source_support": [f"synthetic-source-{q['id']}"]} for q in questions}


def write_run(run_dir, design, trials, arm_of, grades_a, grades_b, adjudication, rng, *, drop=(), extra_trials=()):
    """trials: dicts (trial_id, question_id, repeat, status, latency_s, answer_text). grades_*: trial_id -> grade."""
    run_dir = Path(run_dir)
    (run_dir / "key").mkdir(parents=True, exist_ok=True)
    gold = gold_for(design["questions"])
    kept = [t for t in trials if t["trial_id"] not in set(drop)] + list(extra_trials)
    order = list(kept)
    rng.shuffle(order)
    blinded = {t["trial_id"]: "B" + "".join(rng.choice("0123456789abcdef") for _ in range(12)) for t in order}

    def by_blinded(table):
        return {blinded[tid]: g for tid, g in table.items() if tid in blinded}

    key = {"canary": "KEYSENTINEL-" + "".join(rng.choice("0123456789abcdef") for _ in range(16)),
           "rows": [{"blinded_id": blinded[t["trial_id"]], "trial_id": t["trial_id"], "arm": arm_of[t["trial_id"]]}
                    for t in order]}
    dump = lambda data: json.dumps(data, indent=2, sort_keys=True)  # noqa: E731
    (run_dir / "design.json").write_text(dump(design), encoding="utf-8")
    (run_dir / "gold.json").write_text(dump(gold), encoding="utf-8")
    (run_dir / "trials.jsonl").write_text("\n".join(json.dumps(t, sort_keys=True) for t in kept) + "\n", encoding="utf-8")
    (run_dir / "key" / "key.json").write_text(dump(key), encoding="utf-8")
    (run_dir / "key" / "key.copy.json").write_text(dump(key), encoding="utf-8")
    pack = []
    for t in order:
        entry = gold[t["question_id"]]
        pack.append({"blinded_id": blinded[t["trial_id"]], "question_id": t["question_id"],
                     "question_text": entry["question_text"], "answer_text": t["answer_text"],
                     "gold": {"expected_behavior": entry["expected_behavior"], "gold_facts": entry["gold_facts"],
                              "source_support": entry["source_support"]}})
    (run_dir / "grader_pack.jsonl").write_text("\n".join(json.dumps(r, sort_keys=True) for r in pack) + "\n",
                                               encoding="utf-8")
    (run_dir / "grades_a.json").write_text(dump(by_blinded(grades_a)), encoding="utf-8")
    (run_dir / "grades_b.json").write_text(dump(by_blinded(grades_b)), encoding="utf-8")
    (run_dir / "adjudication.json").write_text(dump(by_blinded(adjudication)), encoding="utf-8")
    return freeze(run_dir)


def build_run(run_dir, *, seed=7, per_class=None, repeats=3, control_pass=0.45, treatment_pass=0.80,
              min_gain=0.10, draws=10000, drop=(), extra_trials=(), disagree=0.1):
    """A random synthetic run. Class sizes default to a small set; real sizes are the real design's business."""
    per_class = per_class or {"answerable": 12, "partial": 4, "probe_unsupported": 4}
    rng = random.Random(seed)
    questions = [{"id": f"SYN-{TAG[klass]}-{i:03d}", "class": klass}
                 for klass in CLASSES for i in range(per_class.get(klass, 0))]
    design = {"questions": questions, "repeats": repeats, "seed": 20261109, "bootstrap_draws": draws,
              "min_gain": min_gain}
    trials, arm_of, grades_a, grades_b, adjudication = [], {}, {}, {}, {}
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
                grades_a[tid] = grade(ok)
                if rng.random() < disagree:  # a second, independent grader sometimes disagrees; adjudication decides
                    grades_b[tid] = grade(not ok)
                    adjudication[tid] = grade(ok)
                else:
                    grades_b[tid] = grade(ok)
    return write_run(run_dir, design, trials, arm_of, grades_a, grades_b, adjudication, rng,
                     drop=drop, extra_trials=extra_trials), design


def build_sentinel(run_dir):
    """A tiny hand-written run with a known answer. Four answerable questions, per-question PASS counts out of 3:
    control 0, 1, 2, 3 and treatment 3, 3, 3, 3, so the paired differences are 1, 2/3, 1/3, 0 and the mean is
    exactly 0.5. One partial and one unsupported-probe question, no flags in either arm, every latency 10 s.
    Includes: fallback answers on answerable trials (graded FAIL, not wrong facts), valid no-context fallbacks on
    the probe (graded PASS), and one grader disagreement resolved by the adjudication."""
    questions = ([{"id": f"SENT-A{i}", "class": "answerable"} for i in range(1, 5)]
                 + [{"id": "SENT-PA1", "class": "partial"}, {"id": "SENT-P1", "class": "probe_unsupported"}])
    design = {"questions": questions, "repeats": 3, "seed": 20261109, "bootstrap_draws": 10000, "min_gain": 0.10}
    control_passes = {"SENT-A1": 0, "SENT-A2": 1, "SENT-A3": 2, "SENT-A4": 3}
    trials, arm_of, grades_a, grades_b, adjudication = [], {}, {}, {}, {}
    for q in questions:
        for arm in (CONTROL, TREATMENT):
            for rep in range(1, 4):
                tid = f"T-{q['id']}-{arm[0]}{rep}"
                answer = f"synthetic sentinel answer {q['id']} {rep}"
                if q["class"] == "answerable":
                    ok = True if arm == TREATMENT else rep <= control_passes[q["id"]]
                    if not ok and q["id"] == "SENT-A1":
                        answer = FALLBACK  # a fallback on an answerable question: non-PASS, not a wrong fact
                else:
                    ok = True
                    if q["class"] == "probe_unsupported":
                        answer = FALLBACK  # a valid no-context response to a probe
                trials.append({"trial_id": tid, "question_id": q["id"], "repeat": rep, "status": "ok",
                               "latency_s": 10.0, "answer_text": answer})
                arm_of[tid] = arm
                grades_a[tid] = grade(ok)
                grades_b[tid] = grade(ok)
    # one disagreement: grader A says PASS, grader B says FAIL; the adjudication's PASS is the final grade
    disputed = "T-SENT-A2-c1"
    assert grades_a[disputed]["grade"] == PASS
    grades_b[disputed] = grade(False)
    adjudication[disputed] = grade(True)
    return write_run(run_dir, design, trials, arm_of, grades_a, grades_b, adjudication, random.Random(1)), design
