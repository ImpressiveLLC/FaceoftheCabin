"""Pinned, offline analysis for a paired two-arm Ask evaluation run.

Reads one run directory, verifies it against its frozen manifest, and returns a verdict. It performs no network
access, loads no model and never reads anything outside the run directory. The same code is exercised against an
entirely synthetic run by test_fixture_analysis.py, so its behavior is pinned before any real evidence exists.

Run directory contents (all produced by the trusted shuffle step, not by this script):
  design.json        questions with their class, repeats, seed, gain threshold, gate settings
  trials.jsonl       one row per trial: trial_id, question_id, repeat, status, latency_s, answer_text
  key.json           blinded_id -> trial_id and arm. THE ONLY FILE THAT NAMES AN ARM. key.copy.json is a second copy.
  grader_pack.jsonl  what blinded graders see: blinded_id, question_id, answer_text. No arm, no trial id.
  grades.json        blinded_id -> grade and flags, written by the blinded graders
  manifest.json      sha256 of every file above, and of the key

Decision rule (Codex design return, issue 244 comment 6737081146, with the owner's gain choice):
  the paired answerable PASS-fraction difference, averaging the repeats within each question, observed gain
  at least min_gain AND the two-sided 95% paired question-block percentile bootstrap lower bound above 0.
  Technical-error trials count as non-PASS and are never dropped. Incomplete or inconsistent evidence, or any
  failed gate, is HOLD. This is design consistency only: it is never an execution go and never a release decision.

The gate rules below are an operational reading with explicit settings in design.json. They must be replaced by
the values frozen in the sealed-set kit before any real run.
"""
import hashlib
import json
import math
import random
import sys
from pathlib import Path

CONTROL, TREATMENT = "control", "treatment"
PASS = "PASS"
FILES = ("design.json", "trials.jsonl", "grader_pack.jsonl", "grades.json")
KEY_COPIES = ("key.json", "key.copy.json")


class EvidenceError(Exception):
    """The evidence is altered, duplicated or inconsistent: nothing can be concluded from it."""


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def sha256_file(path):
    return sha256_bytes(Path(path).read_bytes())


def _read_jsonl(path):
    rows = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if line.strip():
            rows.append(json.loads(line))
    return rows


def freeze(run_dir):
    """Write manifest.json: the hashes everything else is checked against. Called once, by the trusted step."""
    run_dir = Path(run_dir)
    key_digest = sha256_file(run_dir / "key.json")
    files = {name: sha256_file(run_dir / name) for name in FILES}
    files["key"] = key_digest
    (run_dir / "manifest.json").write_text(json.dumps({"files": files}, indent=2, sort_keys=True), encoding="utf-8")
    return sha256_file(run_dir / "manifest.json")


def verify(run_dir):
    run_dir = Path(run_dir)
    try:
        manifest = json.loads((run_dir / "manifest.json").read_text(encoding="utf-8"))["files"]
    except (OSError, ValueError, KeyError) as error:
        raise EvidenceError(f"manifest.json unreadable: {error}")
    for name in FILES:
        path = run_dir / name
        if not path.is_file() or sha256_file(path) != manifest.get(name):
            raise EvidenceError(f"hash mismatch or missing file: {name}")
    return manifest


def load_key(run_dir, manifest):
    """Two-copy recovery: either intact copy will do; with neither intact there is no key and no analysis."""
    for name in KEY_COPIES:
        path = Path(run_dir) / name
        if path.is_file() and sha256_file(path) == manifest["key"]:
            return json.loads(path.read_text(encoding="utf-8"))
    raise EvidenceError("no intact copy of the key (both missing or altered)")


def leak_scan(run_dir, forbidden):
    """Return every (file, token) where a token that must stay hidden from graders appears in what they see."""
    findings = []
    for name in ("grader_pack.jsonl", "grades.json"):
        text = (Path(run_dir) / name).read_text(encoding="utf-8")
        for token in forbidden:
            if token and token in text:
                findings.append((name, token))
    return findings


def paired_bootstrap(diffs, draws, seed):
    """Question-block percentile bootstrap of the mean paired difference. Deterministic for a given seed."""
    rng = random.Random(seed)
    n = len(diffs)
    means = []
    for _ in range(draws):
        total = 0.0
        for _ in range(n):
            total += diffs[rng.randrange(n)]
        means.append(total / n)
    means.sort()
    return means[round(0.025 * (draws - 1))], means[round(0.975 * (draws - 1))]


def _p95(values):
    ordered = sorted(values)
    return ordered[max(0, math.ceil(0.95 * len(ordered)) - 1)] if ordered else 0.0


def analyze(run_dir):
    run_dir = Path(run_dir)
    manifest = verify(run_dir)
    design = json.loads((run_dir / "design.json").read_text(encoding="utf-8"))
    trials = _read_jsonl(run_dir / "trials.jsonl")
    grades = json.loads((run_dir / "grades.json").read_text(encoding="utf-8"))
    key = load_key(run_dir, manifest)
    rows = key["rows"]
    reasons = []

    trial_ids = [t["trial_id"] for t in trials]
    if len(set(trial_ids)) != len(trial_ids):
        raise EvidenceError("duplicate trial id in trials.jsonl")
    blinded = [r["blinded_id"] for r in rows]
    if len(set(blinded)) != len(blinded):
        raise EvidenceError("duplicate blinded id in the key")
    by_trial = {t["trial_id"]: t for t in trials}
    arm_of = {}
    for row in rows:
        if row["trial_id"] not in by_trial:
            raise EvidenceError(f"key names a trial that is not in the log: {row['trial_id']}")
        if row["arm"] not in (CONTROL, TREATMENT):
            raise EvidenceError(f"unknown arm in the key: {row['arm']!r}")
        if row["trial_id"] in arm_of:
            raise EvidenceError(f"trial keyed twice: {row['trial_id']}")
        arm_of[row["trial_id"]] = row["arm"]
    blinded_of = {r["trial_id"]: r["blinded_id"] for r in rows}

    classes = {q["id"]: q["class"] for q in design["questions"]}
    repeats = design["repeats"]
    expected = {(q, arm, rep) for q in classes for arm in (CONTROL, TREATMENT) for rep in range(1, repeats + 1)}
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
    ungraded = [t for t in seen.values() if blinded_of[t["trial_id"]] not in grades]
    if ungraded:
        reasons.append(f"ungraded trials: {len(ungraded)}")

    def passed(trial):
        grade = grades.get(blinded_of[trial["trial_id"]], {})
        return trial.get("status") == "ok" and grade.get("grade") == PASS

    def flag(trial, name):
        return bool(grades.get(blinded_of[trial["trial_id"]], {}).get(name))

    answerable = sorted(q for q, c in classes.items() if c == "answerable")
    fractions = {CONTROL: {}, TREATMENT: {}}
    for q in answerable:
        for arm in (CONTROL, TREATMENT):
            runs = [seen[(q, arm, rep)] for rep in range(1, repeats + 1) if (q, arm, rep) in seen]
            fractions[arm][q] = sum(passed(t) for t in runs) / repeats
    diffs = [fractions[TREATMENT][q] - fractions[CONTROL][q] for q in answerable]
    delta = sum(diffs) / len(diffs) if diffs else 0.0
    lower, upper = paired_bootstrap(diffs, design["bootstrap_draws"], design["seed"]) if diffs else (0.0, 0.0)

    gate = design["gates"]
    arm_trials = {arm: [t for (q, a, r), t in seen.items() if a == arm] for arm in (CONTROL, TREATMENT)}

    def count(arm, klass, name):
        return sum(flag(t, name) for t in arm_trials[arm] if classes[t["question_id"]] == klass)

    gates = {
        "wrong_fact_not_up": count(TREATMENT, "answerable", "wrong_fact") <= count(CONTROL, "answerable", "wrong_fact"),
        "unsupported_claim_not_up": count(TREATMENT, "probe_unsupported", "unsupported_claim")
        <= count(CONTROL, "probe_unsupported", "unsupported_claim"),
        "flags_not_up": sum(flag(t, "flag") for t in arm_trials[TREATMENT])
        <= sum(flag(t, "flag") for t in arm_trials[CONTROL]),
        "latency_p95_within_cap": _p95([t["latency_s"] for t in arm_trials[TREATMENT]]) <= gate["latency_p95_max_s"],
    }
    for name, ok in gates.items():
        if not ok:
            reasons.append(f"gate failed: {name}")

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
        "trials_seen": len(seen),
        "trials_expected": len(expected),
        "manifest_sha256": sha256_file(run_dir / "manifest.json"),
    }


if __name__ == "__main__":
    try:
        print(json.dumps(analyze(sys.argv[1]), indent=2, sort_keys=True))
    except EvidenceError as error:
        print(f"EVIDENCE ERROR: {error}")
        sys.exit(2)
