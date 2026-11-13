"""Pinned, offline analysis for a paired two-arm Ask evaluation run.

Reads one run directory, verifies it against its frozen manifest, and returns a verdict. It performs no network
access and loads no model. The same code is exercised against an entirely synthetic run by
test_fixture_analysis.py, so its behavior is pinned before any real evidence exists.

Layout (all produced by the trusted shuffle step, not by this script):
  <run>/design.json          questions with their class, repeats, seed, draws, minimum gain
  <run>/gold.json            per question: question_text, expected_behavior, gold_facts, source_support (frozen)
  <run>/trials.jsonl         one row per trial: trial_id, question_id, repeat, status, latency_s, answer_text
  <run>/grader_pack.jsonl    what blinded graders see: blinded_id, question_id, question_text, answer_text, gold
  <run>/grades_a.json        blinded grader A: blinded_id -> grade record
  <run>/grades_b.json        blinded grader B, independent of A
  <run>/adjudication.json    blinded_id -> final grade record, for every A/B disagreement and nothing else
  <run>/manifest.json        sha256 of every file above and of the key
  <key>/key.json, key.copy.json  blinded_id -> trial_id and arm. THE ONLY FILES THAT NAME AN ARM. Default <run>/key.

Unmasking barrier: the key is not opened until every frozen file above, including both grade files and the
adjudication, is present and matches its manifest hash. A missing or changed hash fails closed.

Decision rule (Codex design return, issue 244 comment 6737081146, with the owner's gain choice): the paired
answerable PASS-fraction difference, averaging the repeats within each question; observed gain at least min_gain AND
the two-sided 95% paired question-block percentile bootstrap lower bound above 0. Technical-error trials count as
non-PASS and are never dropped. Incomplete or inconsistent evidence, or any failed A1 gate in either arm, is HOLD.
This is design consistency only: it is never an execution go and never a release decision.

Frozen bootstrap: numpy.random.Generator(numpy.random.PCG64(seed)); integers(0, n, size=(draws, n)) question
indices; the mean paired difference of each draw; numpy.quantile(means, [0.025, 0.975]) with the default linear
interpolation. The NumPy version is pinned in requirements.txt; a seed alone does not pin the analysis.

A1 gates, evaluated separately for the control arm and for the treatment arm (Codex HOLD 6804141788):
  flags = 0 over every retained trial; unsupported claims = 0 over probe trials; wrong facts at most
  10% of retained answerable trials; p95 latency strictly under 45 s over every retained trial (nearest-rank p95,
  provisional until the kit states the method).
"""
import hashlib
import json
import math
import sys
from pathlib import Path

import numpy as np

CONTROL, TREATMENT = "control", "treatment"
ARMS = (CONTROL, TREATMENT)
PASS = "PASS"
CLASSES = ("answerable", "partial", "probe")
# The kit's six expected_behavior values and the class that may carry each. A schema check only: it changes no
# grading rule, oracle or rubric.
PROBE_BEHAVIORS = ("clarify", "correct_false_premise", "decline", "state_inability_to_observe")
BEHAVIORS_BY_CLASS = {"answerable": ("answer",), "partial": ("answer_with_stated_limit",), "probe": PROBE_BEHAVIORS}
# Question ids are opaque: they must not name the class, taxonomy, arm or expected behavior.
FORBIDDEN_ID_TOKENS = ("answerable", "answ", "partial", "part", "probe", "unsupported", "unsu", "control", "treatment",
                       "arm", "answer", "clarify", "decline", "false", "premise", "inability", "observe", "limit",
                       "sent", "syn")
FROZEN = ("design.json", "gold.json", "trials.jsonl", "grader_pack.jsonl",
          "grades_a.json", "grades_b.json", "adjudication.json")
KEY_COPIES = ("key.json", "key.copy.json")
GRADE_FIELDS = ("grade", "wrong_fact", "unsupported_claim", "flag")
PACK_FIELDS = {"blinded_id", "question_id", "question_text", "answer_text", "gold"}
GOLD_FIELDS = {"question_text", "expected_behavior", "gold_facts", "source_support"}
MAX_WRONG_FACT_RATE = 0.10
LATENCY_P95_LIMIT_S = 45.0


class EvidenceError(Exception):
    """The evidence is altered, duplicated or inconsistent: nothing can be concluded from it."""


def sha256_file(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def _json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def _read_jsonl(path):
    return [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]


def freeze(run_dir, key_dir=None):
    """Write manifest.json: the hashes everything else is checked against. Called once, by the trusted step."""
    run_dir = Path(run_dir)
    key_dir = Path(key_dir) if key_dir else run_dir / "key"
    files = {name: sha256_file(run_dir / name) for name in FROZEN}
    files["key"] = sha256_file(key_dir / "key.json")
    (run_dir / "manifest.json").write_text(json.dumps({"files": files}, indent=2, sort_keys=True), encoding="utf-8")
    return sha256_file(run_dir / "manifest.json")


def verify(run_dir):
    """The barrier: every frozen file present and matching BEFORE the key is touched."""
    run_dir = Path(run_dir)
    try:
        manifest = _json(run_dir / "manifest.json")["files"]
    except (OSError, ValueError, KeyError) as error:
        raise EvidenceError(f"manifest.json unreadable: {error}")
    for name in FROZEN:
        path = run_dir / name
        if not path.is_file():
            raise EvidenceError(f"missing frozen file: {name}")
        if sha256_file(path) != manifest.get(name):
            raise EvidenceError(f"hash mismatch: {name}")
    if "key" not in manifest:
        raise EvidenceError("manifest has no key hash")
    return manifest


def load_key(key_dir, manifest):
    """Two-copy recovery: either intact copy will do; with neither intact there is no key and no analysis."""
    for name in KEY_COPIES:
        path = Path(key_dir) / name
        if path.is_file() and sha256_file(path) == manifest["key"]:
            return _json(path)
    raise EvidenceError("no intact copy of the key (both missing or altered)")


def leak_scan(run_dir, forbidden):
    """Return every (file, token) where a token that must stay hidden from graders appears in what they see."""
    findings = []
    for name in ("grader_pack.jsonl", "grades_a.json", "grades_b.json", "adjudication.json"):
        text = (Path(run_dir) / name).read_text(encoding="utf-8")
        for token in forbidden:
            if token and token in text:
                findings.append((name, token))
    return findings


def bootstrap_ci(diffs, draws, seed):
    """Question-block percentile bootstrap of the mean paired difference, per the frozen algorithm above."""
    values = np.asarray(diffs, dtype=float)
    n = len(values)
    rng = np.random.Generator(np.random.PCG64(seed))
    means = values[rng.integers(0, n, size=(draws, n))].mean(axis=1)
    lower, upper = np.quantile(means, [0.025, 0.975])
    return float(lower), float(upper)


def p95_nearest_rank(values):
    ordered = sorted(values)
    return ordered[max(0, math.ceil(0.95 * len(ordered)) - 1)] if ordered else 0.0


def _record(grade):
    return tuple(grade.get(field) for field in GRADE_FIELDS)


def _validate_grade(record, where):
    """Exact fields, a closed grade vocabulary and real booleans. A missing or null measurement is never 'false'."""
    if not isinstance(record, dict) or set(record) != set(GRADE_FIELDS):
        raise EvidenceError(f"grade record has the wrong fields in {where}: "
                            f"{sorted(record) if isinstance(record, dict) else type(record).__name__}")
    if record["grade"] not in (PASS, "FAIL"):
        raise EvidenceError(f"invalid grade value in {where}: {record['grade']!r}")
    for name in GRADE_FIELDS[1:]:
        if type(record[name]) is not bool:
            raise EvidenceError(f"{name} must be true or false in {where}: {record[name]!r}")


def _resolve_grades(pack, trials, gold, grades_a, grades_b, adjudication):
    """Everything about grading that can be checked WITHOUT the key, so incomplete grading never reaches it.
    The pack lists every blinded id; both graders must have graded exactly those; the adjudication must hold exactly
    the disagreements. Returns the final grade per blinded id and the number of adjudicated disagreements."""
    if not pack:
        raise EvidenceError("grader pack is empty")
    ids = []
    for record in pack:
        if set(record) != PACK_FIELDS:
            raise EvidenceError(f"grader pack record has the wrong fields: {sorted(record)}")
        ids.append(record["blinded_id"])
        entry = gold.get(record["question_id"])
        if entry is None or record["question_text"] != entry["question_text"] or record["gold"] != {
                "expected_behavior": entry["expected_behavior"], "gold_facts": entry["gold_facts"],
                "source_support": entry["source_support"]}:
            raise EvidenceError(f"grader pack record does not match the frozen gold: {record['blinded_id']}")
    if len(set(ids)) != len(ids):
        raise EvidenceError("grader pack lists a blinded id twice")
    if len(pack) != len(trials):
        raise EvidenceError(f"grader pack has {len(pack)} records for {len(trials)} trials")
    if sorted((r["question_id"], r["answer_text"]) for r in pack) != sorted(
            (t["question_id"], t["answer_text"]) for t in trials):
        raise EvidenceError("grader pack content does not match the trial log")
    wanted = set(ids)
    for label, grades in (("grades_a", grades_a), ("grades_b", grades_b)):
        if set(grades) != wanted:
            raise EvidenceError(f"{label} does not cover exactly the grader pack: "
                                f"{len(wanted - set(grades))} missing, {len(set(grades) - wanted)} extra")
        for blinded, record in grades.items():
            _validate_grade(record, f"{label}[{blinded}]")
    disagreements = {b for b in wanted if _record(grades_a[b]) != _record(grades_b[b])}
    if set(adjudication) != disagreements:
        raise EvidenceError(f"adjudication must hold exactly the disagreements: {len(disagreements - set(adjudication))} "
                            f"unresolved, {len(set(adjudication) - disagreements)} not in disagreement")
    for blinded, record in adjudication.items():
        _validate_grade(record, f"adjudication[{blinded}]")
    return {b: (adjudication[b] if b in disagreements else grades_a[b]) for b in wanted}, len(disagreements)


def _check_pack(pack, trials_by_id, key_rows, gold, questions):
    """Exact one-to-one membership and content: an empty or partial pack is a failure, not a smaller sample."""
    if not pack:
        raise EvidenceError("grader pack is empty")
    expected = {r["blinded_id"]: r["trial_id"] for r in key_rows}
    seen = {}
    for record in pack:
        if set(record) != PACK_FIELDS:
            raise EvidenceError(f"grader pack record has the wrong fields: {sorted(record)}")
        blinded = record["blinded_id"]
        if blinded in seen:
            raise EvidenceError(f"grader pack lists a blinded id twice: {blinded}")
        seen[blinded] = record
        if blinded not in expected:
            raise EvidenceError(f"grader pack names a blinded id that is not in the key: {blinded}")
        trial = trials_by_id[expected[blinded]]
        if record["question_id"] != trial["question_id"] or record["answer_text"] != trial["answer_text"]:
            raise EvidenceError(f"grader pack record does not match its trial: {blinded}")
        entry = gold[record["question_id"]]
        if record["question_text"] != entry["question_text"] or record["gold"] != {
                "expected_behavior": entry["expected_behavior"], "gold_facts": entry["gold_facts"],
                "source_support": entry["source_support"]}:
            raise EvidenceError(f"grader pack record does not match the frozen gold: {blinded}")
    missing = set(expected) - set(seen)
    if missing:
        raise EvidenceError(f"grader pack is missing {len(missing)} trial(s)")


def opaque_id_problems(question_ids):
    """Ways the ids give away class, taxonomy, arm or behavior by name. Grouping by sort order is a fixture test."""
    problems = []
    for qid in question_ids:
        hits = [token for token in FORBIDDEN_ID_TOKENS if token in str(qid).lower()]
        if hits:
            problems.append(f"{qid} contains {hits[0]!r}")
    return problems


def analyze(run_dir, key_dir=None):
    run_dir = Path(run_dir)
    key_dir = Path(key_dir) if key_dir else run_dir / "key"
    manifest = verify(run_dir)  # the barrier, before the key
    design = _json(run_dir / "design.json")
    gold = _json(run_dir / "gold.json")
    trials = _read_jsonl(run_dir / "trials.jsonl")
    pack = _read_jsonl(run_dir / "grader_pack.jsonl")
    grades_a = _json(run_dir / "grades_a.json")
    grades_b = _json(run_dir / "grades_b.json")
    adjudication = _json(run_dir / "adjudication.json")
    classes = {q["id"]: q["class"] for q in design["questions"]}
    if not set(classes.values()) <= set(CLASSES) or not classes:
        raise EvidenceError(f"design classes must be drawn from {CLASSES}")
    for qid in classes:
        if qid not in gold or set(gold[qid]) != GOLD_FIELDS:
            raise EvidenceError(f"gold entry missing or malformed for {qid}")
        if gold[qid]["expected_behavior"] not in BEHAVIORS_BY_CLASS[classes[qid]]:
            raise EvidenceError(f"expected_behavior for a {classes[qid]} question must be one of "
                                f"{BEHAVIORS_BY_CLASS[classes[qid]]}")
    problems = opaque_id_problems(classes)
    if problems:
        raise EvidenceError("question ids are not opaque: " + "; ".join(problems))

    trial_ids = [t["trial_id"] for t in trials]
    if len(set(trial_ids)) != len(trial_ids):
        raise EvidenceError("duplicate trial id in trials.jsonl")
    # Grading must be complete and well-formed before the key is touched (Codex HOLD 6813304626).
    final, adjudicated = _resolve_grades(pack, trials, gold, grades_a, grades_b, adjudication)

    key = load_key(key_dir, manifest)
    rows = key["rows"]
    reasons = []

    blinded_ids = [r["blinded_id"] for r in rows]
    if len(set(blinded_ids)) != len(blinded_ids):
        raise EvidenceError("duplicate blinded id in the key")
    trials_by_id = {t["trial_id"]: t for t in trials}
    arm_of, blinded_of = {}, {}
    for row in rows:
        if row["trial_id"] not in trials_by_id:
            raise EvidenceError(f"key names a trial that is not in the log: {row['trial_id']}")
        if row["arm"] not in ARMS:
            raise EvidenceError(f"unknown arm in the key: {row['arm']!r}")
        if row["trial_id"] in arm_of:
            raise EvidenceError(f"trial keyed twice: {row['trial_id']}")
        arm_of[row["trial_id"]] = row["arm"]
        blinded_of[row["trial_id"]] = row["blinded_id"]
    _check_pack(pack, trials_by_id, rows, gold, classes)

    repeats = design["repeats"]
    expected = {(q, arm, rep) for q in classes for arm in ARMS for rep in range(1, repeats + 1)}
    seen = {}
    for trial in trials:
        if trial["trial_id"] not in arm_of:
            reasons.append(f"trial not in the key: {trial['trial_id']}")
            continue
        slot = (trial["question_id"], arm_of[trial["trial_id"]], trial["repeat"])
        if slot not in expected:
            raise EvidenceError(f"trial outside the design: {slot}")
        if slot in seen:
            raise EvidenceError(f"duplicate (question, arm, repeat): {slot}")
        seen[slot] = trial
    missing = sorted(expected - set(seen))
    if missing:
        reasons.append(f"missing trials: {len(missing)} (first: {missing[0]})")

    # Final grades were resolved and validated before the key was opened; a trial without one is an error.
    def final_of(trial):
        try:
            return final[blinded_of[trial["trial_id"]]]
        except KeyError:
            raise EvidenceError(f"no final grade for trial {trial['trial_id']}")

    def passed(trial):
        return trial.get("status") == "ok" and final_of(trial)["grade"] == PASS

    def flagged(trial, name):
        return final_of(trial)[name]

    answerable = sorted(q for q, c in classes.items() if c == "answerable")
    fractions = {arm: {} for arm in ARMS}
    for q in answerable:
        for arm in ARMS:
            runs = [seen[(q, arm, rep)] for rep in range(1, repeats + 1) if (q, arm, rep) in seen]
            fractions[arm][q] = sum(passed(t) for t in runs) / repeats
    diffs = [fractions[TREATMENT][q] - fractions[CONTROL][q] for q in answerable]
    delta = sum(diffs) / len(diffs) if diffs else 0.0
    lower, upper = bootstrap_ci(diffs, design["bootstrap_draws"], design["seed"]) if diffs else (0.0, 0.0)

    gates = {}
    for arm in ARMS:
        mine = [t for (q, a, r), t in seen.items() if a == arm]
        answerable_trials = [t for t in mine if classes[t["question_id"]] == "answerable"]
        probe_trials = [t for t in mine if classes[t["question_id"]] == "probe"]
        wrong = sum(flagged(t, "wrong_fact") for t in answerable_trials)
        gates[arm] = {
            "flags_zero": sum(flagged(t, "flag") for t in mine) == 0,
            "unsupported_claims_zero": sum(flagged(t, "unsupported_claim") for t in probe_trials) == 0,
            "wrong_fact_rate_within_limit": (wrong / len(answerable_trials) if answerable_trials else 0.0)
            <= MAX_WRONG_FACT_RATE,
            "p95_latency_under_limit": p95_nearest_rank([t["latency_s"] for t in mine]) < LATENCY_P95_LIMIT_S,
        }
        for name, ok in gates[arm].items():
            if not ok:
                reasons.append(f"gate failed ({arm}): {name}")

    if reasons:
        verdict = "HOLD"
    elif delta >= design["min_gain"] and lower > 0:
        verdict = "GAIN_MET"
    else:
        verdict = "GAIN_NOT_MET"
    return {
        "verdict": verdict,
        "reasons": reasons,
        "delta": delta,
        "ci95": [lower, upper],
        "answerable_questions": len(answerable),
        "gates": gates,
        "adjudicated": adjudicated,
        "trials_seen": len(seen),
        "trials_expected": len(expected),
        "manifest_sha256": sha256_file(run_dir / "manifest.json"),
    }


if __name__ == "__main__":
    try:
        print(json.dumps(analyze(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None), indent=2, sort_keys=True))
    except EvidenceError as error:
        print(f"EVIDENCE ERROR: {error}")
        sys.exit(2)
