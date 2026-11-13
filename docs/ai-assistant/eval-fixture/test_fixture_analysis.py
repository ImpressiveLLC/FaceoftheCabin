import json
import re
import socket
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import numpy as np

from analyze_run import (BEHAVIORS_BY_CLASS, EvidenceError, analyze, bootstrap_ci, freeze, leak_scan, opaque_id_problems,
                         p95_nearest_rank)
from synthetic_fixture import FALLBACK, SENT, build_run, build_sentinel, grade, syn_id

HERE = Path(__file__).parent


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write_json(path, data):
    Path(path).write_text(json.dumps(data, indent=2, sort_keys=True), encoding="utf-8")


def read_rows(path):
    return [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]


def write_rows(path, rows):
    Path(path).write_text("\n".join(json.dumps(r, sort_keys=True) for r in rows) + "\n", encoding="utf-8")


def reference_quantile(sorted_values, q):
    """Independent linear-interpolation quantile (the declared method), not NumPy's."""
    position = q * (len(sorted_values) - 1)
    low = int(position)
    high = min(low + 1, len(sorted_values) - 1)
    return sorted_values[low] + (position - low) * (sorted_values[high] - sorted_values[low])


class FixtureAnalysisTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def blinded_for(self, trial_id):
        return next(r["blinded_id"] for r in read_json(self.dir / "key" / "key.json")["rows"] if r["trial_id"] == trial_id)

    def edit_trials(self, edit):
        rows = read_rows(self.dir / "trials.jsonl")
        edit(rows)
        write_rows(self.dir / "trials.jsonl", rows)

    def edit_grades(self, name, edit):
        data = read_json(self.dir / name)
        edit(data)
        write_json(self.dir / name, data)

    def set_final_grade(self, trial_id, **fields):
        """Change one trial's final grade consistently in both grader files, then refreeze."""
        blinded = self.blinded_for(trial_id)
        for name in ("grades_a.json", "grades_b.json"):
            self.edit_grades(name, lambda data: data[blinded].update(fields))
        self.edit_grades("adjudication.json", lambda data: data[blinded].update(fields) if blinded in data else None)
        freeze(self.dir)

    # ---- sentinel mini-set: a known answer ----------------------------------------------------

    def test_sentinel_set_gives_the_known_difference(self):
        build_sentinel(self.dir)
        result = analyze(self.dir)
        self.assertAlmostEqual(result["delta"], 0.5, places=12)  # (1 + 2/3 + 1/3 + 0) / 4
        self.assertEqual(result["verdict"], "GAIN_MET")
        self.assertGreater(result["ci95"][0], 0)
        self.assertEqual((result["trials_seen"], result["trials_expected"]), (36, 36))
        self.assertEqual((result["answerable_questions"], result["adjudicated"]), (4, 1))
        self.assertEqual(result["reasons"], [])

    def test_fallbacks_are_ordinary_grades_not_wrong_facts(self):
        build_sentinel(self.dir)
        trials = read_rows(self.dir / "trials.jsonl")
        self.assertTrue(any(t["answer_text"] == FALLBACK and t["question_id"] == SENT["A1"] for t in trials))
        self.assertTrue(any(t["answer_text"] == FALLBACK and t["question_id"] == SENT["P1"] for t in trials))
        self.assertTrue(all(g["wrong_fact"] is False for g in read_json(self.dir / "grades_a.json").values()))
        self.assertEqual(analyze(self.dir)["verdict"], "GAIN_MET")

    def test_the_sentinel_has_all_three_classes(self):
        build_sentinel(self.dir)
        self.assertEqual({q["class"] for q in read_json(self.dir / "design.json")["questions"]},
                         {"answerable", "partial", "probe"})

    # ---- the frozen bootstrap -----------------------------------------------------------------

    def test_the_numpy_version_is_the_pinned_one(self):
        pinned = re.search(r"numpy==([0-9.]+)", (HERE / "requirements.txt").read_text(encoding="utf-8")).group(1)
        self.assertEqual(np.__version__, pinned)

    def test_prescribed_generator_first_draws_are_pinned(self):
        # Recorded from numpy 2.2.6 (see requirements.txt); a different NumPy is a different analysis.
        rng = np.random.Generator(np.random.PCG64(20261109))
        self.assertEqual(rng.integers(0, 72, size=8).tolist(), [52, 22, 34, 54, 51, 71, 30, 27])

    def test_bootstrap_matches_analytic_cases_that_need_no_generator(self):
        lower, upper = bootstrap_ci([0.3] * 5, 10000, 20261109)
        self.assertAlmostEqual(lower, 0.3, places=12)
        self.assertAlmostEqual(upper, 0.3, places=12)
        # two paired differences 0 and 1: each end of the range is hit by a quarter of the draws, far above the 2.5%
        # tail, so the interval is exactly [0, 1] for any sound generator.
        self.assertEqual(bootstrap_ci([0.0, 1.0], 10000, 20261109), (0.0, 1.0))

    def test_quantile_is_linear_interpolation_checked_against_an_independent_reference(self):
        values = sorted(float(v) for v in np.random.Generator(np.random.PCG64(5)).random(1001))
        got = np.quantile(values, [0.025, 0.975])
        self.assertAlmostEqual(got[0], reference_quantile(values, 0.025), places=12)
        self.assertAlmostEqual(got[1], reference_quantile(values, 0.975), places=12)
        # an interpolating quantile differs from the rounded order statistic the first version used
        short = [0.0, 1.0, 2.0, 3.0, 4.0, 5.0]
        self.assertAlmostEqual(reference_quantile(short, 0.025), 0.125, places=12)
        self.assertAlmostEqual(float(np.quantile(short, 0.025)), 0.125, places=12)

    def test_bootstrap_is_deterministic_for_a_seed_and_differs_across_seeds(self):
        spread = [i / 21 for i in range(22)]
        first = bootstrap_ci(spread, 10000, 20261109)
        self.assertEqual(first, bootstrap_ci(spread, 10000, 20261109))
        self.assertNotEqual(first, bootstrap_ci(spread, 10000, 20261110))

    def test_sentinel_interval_is_pinned_for_the_prescribed_seed(self):
        build_sentinel(self.dir)
        result = analyze(self.dir)
        want = bootstrap_ci([1.0, 2 / 3, 1 / 3, 0.0], 10000, 20261109)
        for got, expected in zip(result["ci95"], want):
            self.assertAlmostEqual(got, expected, places=9)
        self.assertAlmostEqual(result["ci95"][0], 1 / 6, places=9)
        self.assertAlmostEqual(result["ci95"][1], 5 / 6, places=9)

    def test_whole_analysis_is_reproducible(self):
        build_sentinel(self.dir)
        self.assertEqual(analyze(self.dir), analyze(self.dir))

    # ---- two fake arms ------------------------------------------------------------------------

    def test_a_clearly_better_treatment_arm_meets_the_gain(self):
        build_run(self.dir, control_pass=0.2, treatment_pass=0.95,
                  per_class={"answerable": 20, "partial": 4, "probe": 4})
        result = analyze(self.dir)
        self.assertEqual(result["verdict"], "GAIN_MET")
        self.assertGreaterEqual(result["delta"], 0.10)

    def test_identical_arms_do_not_meet_the_gain(self):
        build_run(self.dir, control_pass=0.6, treatment_pass=0.6, seed=3)
        self.assertNotEqual(analyze(self.dir)["verdict"], "GAIN_MET")

    # ---- A1 gates: absolute, per arm ----------------------------------------------------------

    def test_flags_in_both_arms_hold_even_though_they_did_not_increase(self):
        build_sentinel(self.dir)
        for name in ("grades_a.json", "grades_b.json", "adjudication.json"):
            self.edit_grades(name, lambda data: [g.update(flag=True) for g in data.values()])
        freeze(self.dir)
        result = analyze(self.dir)
        self.assertEqual(result["verdict"], "HOLD")
        self.assertIn("gate failed (control): flags_zero", result["reasons"])
        self.assertIn("gate failed (treatment): flags_zero", result["reasons"])

    def test_a_single_flag_in_one_arm_holds(self):
        build_sentinel(self.dir)
        self.set_final_grade(f"T-{SENT['PA1']}-c2", flag=True)
        result = analyze(self.dir)
        self.assertEqual(result["verdict"], "HOLD")
        self.assertEqual([r for r in result["reasons"] if r.startswith("gate failed")],
                         ["gate failed (control): flags_zero"])

    def test_unsupported_claims_in_both_arms_hold(self):
        build_sentinel(self.dir)
        for arm in "ct":
            self.set_final_grade(f"T-{SENT['P1']}-{arm}1", unsupported_claim=True)
        result = analyze(self.dir)
        self.assertEqual(result["verdict"], "HOLD")
        self.assertIn("gate failed (control): unsupported_claims_zero", result["reasons"])
        self.assertIn("gate failed (treatment): unsupported_claims_zero", result["reasons"])

    def test_wrong_facts_are_limited_to_ten_percent_of_answerable_trials_in_each_arm(self):
        build_sentinel(self.dir)  # 12 answerable trials per arm: one wrong fact is 8.3%, two are 16.7%
        self.set_final_grade(f"T-{SENT['A1']}-c1", wrong_fact=True)
        self.assertEqual(analyze(self.dir)["verdict"], "GAIN_MET")
        self.set_final_grade(f"T-{SENT['A1']}-c2", wrong_fact=True)
        result = analyze(self.dir)
        self.assertEqual(result["verdict"], "HOLD")
        self.assertIn("gate failed (control): wrong_fact_rate_within_limit", result["reasons"])
        self.assertNotIn("gate failed (treatment): wrong_fact_rate_within_limit", result["reasons"])

    def test_wrong_facts_in_both_arms_above_the_limit_hold_even_with_equal_counts(self):
        build_sentinel(self.dir)
        for arm in "ct":
            for rep in (1, 2):
                self.set_final_grade(f"T-{SENT['A3']}-{arm}{rep}", wrong_fact=True)
        self.assertEqual(analyze(self.dir)["verdict"], "HOLD")

    def test_p95_latency_must_be_strictly_under_45_seconds_in_each_arm(self):
        self.assertEqual(p95_nearest_rank([10.0] * 19 + [45.0]), 10.0)  # one slow trial in 20 does not move p95
        build_sentinel(self.dir)

        def set_latency(arm_prefix, value):
            self.edit_trials(lambda rows: [r.update(latency_s=value) for r in rows if arm_prefix in r["trial_id"]])
            freeze(self.dir)

        set_latency("-c", 45.0)  # exactly 45 s in the control arm only: fails the strict limit
        result = analyze(self.dir)
        self.assertEqual(result["verdict"], "HOLD")
        self.assertIn("gate failed (control): p95_latency_under_limit", result["reasons"])
        self.assertNotIn("gate failed (treatment): p95_latency_under_limit", result["reasons"])
        set_latency("-c", 44.9)
        self.assertEqual(analyze(self.dir)["verdict"], "GAIN_MET")
        set_latency("-t", 45.0)
        self.assertIn("gate failed (treatment): p95_latency_under_limit", analyze(self.dir)["reasons"])

    def test_a_technical_error_is_non_pass_and_the_trial_is_retained(self):
        build_sentinel(self.dir)
        self.edit_trials(lambda rows: [r.update(status="technical_error") for r in rows if r["trial_id"] == f"T-{SENT['A1']}-t1"])
        freeze(self.dir)
        result = analyze(self.dir)
        self.assertEqual(result["trials_seen"], 36)
        self.assertAlmostEqual(result["delta"], (2 / 3 + 2 / 3 + 1 / 3 + 0) / 4, places=12)

    # ---- the unmasking barrier: two frozen grade files plus adjudication ----------------------

    def key_paths_read(self, call):
        opened = []
        real_bytes, real_text = Path.read_bytes, Path.read_text

        def spy_bytes(path, *a, **k):
            opened.append(Path(path).resolve())
            return real_bytes(path, *a, **k)

        def spy_text(path, *a, **k):
            opened.append(Path(path).resolve())
            return real_text(path, *a, **k)

        with mock.patch.object(Path, "read_bytes", spy_bytes), mock.patch.object(Path, "read_text", spy_text):
            try:
                call()
            except EvidenceError:
                pass
        key_dir = (self.dir / "key").resolve()
        return [p for p in opened if key_dir in p.parents]

    def test_a_missing_second_grade_file_fails_closed_and_the_key_is_never_opened(self):
        build_sentinel(self.dir)
        (self.dir / "grades_b.json").unlink()
        with self.assertRaisesRegex(EvidenceError, "missing frozen file: grades_b.json"):
            analyze(self.dir)
        self.assertEqual(self.key_paths_read(lambda: analyze(self.dir)), [])

    def test_a_missing_adjudication_fails_closed_before_the_key(self):
        build_sentinel(self.dir)
        (self.dir / "adjudication.json").unlink()
        with self.assertRaisesRegex(EvidenceError, "missing frozen file: adjudication.json"):
            analyze(self.dir)
        self.assertEqual(self.key_paths_read(lambda: analyze(self.dir)), [])

    def test_a_changed_grade_file_or_adjudication_fails_the_hash_before_the_key(self):
        for name in ("grades_a.json", "grades_b.json", "adjudication.json"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as tmp:
                self.dir = Path(tmp)
                build_sentinel(self.dir)
                data = read_json(self.dir / name)
                first = next(iter(data))
                data[first] = grade(data[first]["grade"] != "PASS")
                write_json(self.dir / name, data)
                with self.assertRaisesRegex(EvidenceError, f"hash mismatch: {name}"):
                    analyze(self.dir)
                self.assertEqual(self.key_paths_read(lambda: analyze(self.dir)), [])

    def test_the_key_is_opened_only_after_the_barrier_passes(self):
        build_sentinel(self.dir)
        self.assertTrue(self.key_paths_read(lambda: analyze(self.dir)))

    def test_an_unresolved_disagreement_fails_closed_and_the_key_is_never_opened(self):
        build_sentinel(self.dir)
        blinded = self.blinded_for(f"T-{SENT['A4']}-t1")
        self.edit_grades("grades_b.json", lambda data: data[blinded].update(grade="FAIL"))
        freeze(self.dir)
        with self.assertRaisesRegex(EvidenceError, "1 unresolved"):
            analyze(self.dir)
        self.assertEqual(self.key_paths_read(lambda: analyze(self.dir)), [])

    def test_adjudication_for_a_trial_the_graders_agreed_on_is_refused(self):
        build_sentinel(self.dir)
        blinded = self.blinded_for(f"T-{SENT['A4']}-t2")
        self.edit_grades("adjudication.json", lambda data: data.update({blinded: grade(False)}))
        freeze(self.dir)
        with self.assertRaisesRegex(EvidenceError, "not in disagreement"):
            analyze(self.dir)

    def test_both_graders_must_have_graded_every_trial_and_the_key_stays_closed(self):
        build_sentinel(self.dir)
        blinded = self.blinded_for(f"T-{SENT['A1']}-t3")
        self.edit_grades("grades_a.json", lambda data: data.pop(blinded))
        freeze(self.dir)
        with self.assertRaisesRegex(EvidenceError, "grades_a does not cover exactly the grader pack: 1 missing"):
            analyze(self.dir)
        self.assertEqual(self.key_paths_read(lambda: analyze(self.dir)), [])

    def test_refrozen_empty_or_partial_grading_never_reaches_the_key(self):
        for label, edit in (("empty", lambda d: {}), ("partial", lambda d: dict(list(d.items())[:-3]))):
            for names in (("grades_a.json",), ("grades_b.json",), ("grades_a.json", "grades_b.json", "adjudication.json")):
                with self.subTest(label=label, names=names), tempfile.TemporaryDirectory() as tmp:
                    self.dir = Path(tmp)
                    build_sentinel(self.dir)
                    for name in names:
                        write_json(self.dir / name, edit(read_json(self.dir / name)))
                    freeze(self.dir)
                    with self.assertRaises(EvidenceError):
                        analyze(self.dir)
                    self.assertEqual(self.key_paths_read(lambda: analyze(self.dir)), [])

    # ---- grade records are validated: a missing measurement is never "false" ------------------

    def refrozen_grades(self, edit):
        for name in ("grades_a.json", "grades_b.json", "adjudication.json"):
            data = read_json(self.dir / name)
            edit(data)
            write_json(self.dir / name, data)
        freeze(self.dir)

    def test_grade_records_without_the_gate_fields_fail_closed(self):
        build_sentinel(self.dir)

        def strip(data):
            for record in data.values():
                for field in ("wrong_fact", "unsupported_claim", "flag"):
                    record.pop(field, None)

        self.refrozen_grades(strip)  # Codex's reproduction: the intact sentinel, gate fields removed everywhere
        with self.assertRaisesRegex(EvidenceError, "wrong fields"):
            analyze(self.dir)
        self.assertEqual(self.key_paths_read(lambda: analyze(self.dir)), [])

    def test_null_non_boolean_and_unknown_grade_values_fail_closed(self):
        cases = (("flag", None, "must be true or false"), ("wrong_fact", "yes", "must be true or false"),
                 ("unsupported_claim", 0, "must be true or false"), ("grade", "MAYBE", "invalid grade value"),
                 ("grade", None, "invalid grade value"))
        for field, value, message in cases:
            for name in ("grades_a.json", "grades_b.json", "adjudication.json"):
                with self.subTest(field=field, value=value, name=name), tempfile.TemporaryDirectory() as tmp:
                    self.dir = Path(tmp)
                    build_sentinel(self.dir)
                    data = read_json(self.dir / name)
                    next(iter(data.values()))[field] = value
                    write_json(self.dir / name, data)
                    freeze(self.dir)
                    with self.assertRaisesRegex(EvidenceError, message):
                        analyze(self.dir)
                    self.assertEqual(self.key_paths_read(lambda: analyze(self.dir)), [])

    def test_an_extra_field_in_a_grade_record_fails_closed(self):
        build_sentinel(self.dir)
        self.refrozen_grades(lambda data: next(iter(data.values())).update(note="extra"))
        with self.assertRaisesRegex(EvidenceError, "wrong fields"):
            analyze(self.dir)

    # ---- the grader pack contract -------------------------------------------------------------

    def refrozen_pack(self, edit):
        rows = read_rows(self.dir / "grader_pack.jsonl")
        rows = edit(rows)
        write_rows(self.dir / "grader_pack.jsonl", rows)
        freeze(self.dir)

    def test_an_empty_pack_fails_closed_even_when_refrozen(self):
        build_sentinel(self.dir)
        (self.dir / "grader_pack.jsonl").write_text("", encoding="utf-8")
        freeze(self.dir)
        with self.assertRaisesRegex(EvidenceError, "grader pack is empty"):
            analyze(self.dir)

    def test_a_pack_missing_a_record_fails_closed(self):
        build_sentinel(self.dir)
        self.refrozen_pack(lambda rows: rows[:-1])
        with self.assertRaisesRegex(EvidenceError, "35 records for 36 trials"):
            analyze(self.dir)

    def test_a_pack_with_a_duplicate_or_foreign_record_fails_closed(self):
        build_sentinel(self.dir)
        self.refrozen_pack(lambda rows: rows + [rows[0]])
        with self.assertRaisesRegex(EvidenceError, "twice"):
            analyze(self.dir)
        build_sentinel(self.dir)  # a fresh pack: the duplicate above was refrozen into the previous one
        self.refrozen_pack(lambda rows: rows[:-1] + [dict(rows[0], blinded_id="Bffffffffffff")])
        with self.assertRaisesRegex(EvidenceError, "does not match the trial log"):
            analyze(self.dir)

    def test_a_pack_record_that_differs_from_its_trial_or_the_gold_fails_closed(self):
        for field, value, message in (("answer_text", "tampered", "does not match the trial log"),
                                      ("question_text", "tampered", "does not match the frozen gold"),
                                      ("gold", {"expected_behavior": "x", "gold_facts": [], "source_support": []},
                                       "does not match the frozen gold")):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as tmp:
                self.dir = Path(tmp)
                build_sentinel(self.dir)
                self.refrozen_pack(lambda rows: [dict(rows[0], **{field: value})] + rows[1:])
                with self.assertRaisesRegex(EvidenceError, message):
                    analyze(self.dir)

    def test_swapped_answers_between_two_pack_records_are_caught_once_the_key_is_open(self):
        build_sentinel(self.dir)
        rows = read_rows(self.dir / "grader_pack.jsonl")
        # SENT-A1 has fallback answers in one arm and ordinary answers in the other: same question, different text
        pair = [r for r in rows if r["question_id"] == SENT["A1"]]
        first = pair[0]
        second = next(r for r in pair if r["answer_text"] != first["answer_text"])
        first["answer_text"], second["answer_text"] = second["answer_text"], first["answer_text"]
        write_rows(self.dir / "grader_pack.jsonl", rows)
        freeze(self.dir)
        with self.assertRaisesRegex(EvidenceError, "does not match its trial"):
            analyze(self.dir)

    def test_the_grader_pack_has_exactly_the_contract_fields_and_names_no_arm(self):
        build_sentinel(self.dir)
        for row in read_rows(self.dir / "grader_pack.jsonl"):
            self.assertEqual(set(row), {"blinded_id", "question_id", "question_text", "answer_text", "gold"})
            self.assertEqual(set(row["gold"]), {"expected_behavior", "gold_facts", "source_support"})
        self.refrozen_pack(lambda rows: [dict(rows[0], arm="treatment")] + rows[1:])
        with self.assertRaisesRegex(EvidenceError, "wrong fields"):
            analyze(self.dir)

    def test_a_gold_entry_missing_for_a_question_fails_closed(self):
        build_sentinel(self.dir)
        self.edit_grades("gold.json", lambda data: data.pop(SENT["PA1"]))
        freeze(self.dir)
        with self.assertRaisesRegex(EvidenceError, "gold entry missing"):
            analyze(self.dir)

    # ---- mapping and key isolation ------------------------------------------------------------

    def test_a_swapped_arm_mapping_negates_the_difference_when_refrozen(self):
        build_sentinel(self.dir)
        before = analyze(self.dir)["delta"]
        key = read_json(self.dir / "key" / "key.json")
        for row in key["rows"]:
            row["arm"] = "control" if row["arm"] == "treatment" else "treatment"
        for name in ("key.json", "key.copy.json"):
            write_json(self.dir / "key" / name, key)
        freeze(self.dir)
        after = analyze(self.dir)
        self.assertAlmostEqual(after["delta"], -before, places=12)
        self.assertEqual(after["verdict"], "GAIN_NOT_MET")

    def test_a_swap_after_freezing_is_caught_by_the_key_hash(self):
        build_sentinel(self.dir)
        key = read_json(self.dir / "key" / "key.json")
        for row in key["rows"]:
            row["arm"] = "control" if row["arm"] == "treatment" else "treatment"
        for name in ("key.json", "key.copy.json"):
            write_json(self.dir / "key" / name, key)
        with self.assertRaisesRegex(EvidenceError, "no intact copy of the key"):
            analyze(self.dir)

    def test_the_key_can_live_outside_the_run_directory(self):
        build_sentinel(self.dir)
        with tempfile.TemporaryDirectory() as elsewhere:
            for name in ("key.json", "key.copy.json"):
                (Path(elsewhere) / name).write_bytes((self.dir / "key" / name).read_bytes())
                (self.dir / "key" / name).unlink()
            self.assertEqual(analyze(self.dir, elsewhere)["verdict"], "GAIN_MET")

    def test_leak_scan_is_clean_on_a_blinded_run_and_catches_an_injected_leak(self):
        build_sentinel(self.dir)
        key = read_json(self.dir / "key" / "key.json")
        forbidden = ["control", "treatment", key["canary"]] + [r["trial_id"] for r in key["rows"]]
        self.assertEqual(leak_scan(self.dir, forbidden), [])
        with (self.dir / "grader_pack.jsonl").open("a", encoding="utf-8") as handle:
            handle.write(json.dumps({"blinded_id": "X", "answer_text": "arm=treatment"}) + "\n")
            handle.write(json.dumps({"blinded_id": "Y", "answer_text": key["canary"]}) + "\n")
        found = {token for _, token in leak_scan(self.dir, forbidden)}
        self.assertIn("treatment", found)
        self.assertIn(key["canary"], found)

    # ---- missing, duplicated, altered ---------------------------------------------------------

    def test_a_missing_trial_is_a_hold_not_a_silent_pass(self):
        build_run(self.dir, drop=(f"T-{syn_id('answerable', 0)}-t2",))
        result = analyze(self.dir)
        self.assertEqual(result["verdict"], "HOLD")
        self.assertTrue(any(r.startswith("missing trials") for r in result["reasons"]))
        self.assertEqual(result["trials_seen"], result["trials_expected"] - 1)

    def test_a_duplicate_trial_id_is_an_evidence_error(self):
        qid = syn_id("answerable", 1)
        dup = {"trial_id": f"T-{qid}-c1", "question_id": qid, "repeat": 1, "status": "ok",
               "latency_s": 5.0, "answer_text": "synthetic answer duplicate"}
        build_run(self.dir, extra_trials=(dup,))
        with self.assertRaisesRegex(EvidenceError, "duplicate trial id"):
            analyze(self.dir)

    def test_an_altered_trial_log_and_a_missing_manifest_are_both_refused(self):
        build_sentinel(self.dir)
        with (self.dir / "trials.jsonl").open("a", encoding="utf-8") as handle:
            handle.write("\n")
        with self.assertRaisesRegex(EvidenceError, "hash mismatch: trials.jsonl"):
            analyze(self.dir)
        (self.dir / "manifest.json").unlink()
        with self.assertRaisesRegex(EvidenceError, "manifest.json unreadable"):
            analyze(self.dir)

    # ---- access, network boundary, two-copy recovery ------------------------------------------

    def test_either_key_copy_is_enough_and_neither_is_an_error(self):
        build_sentinel(self.dir)
        (self.dir / "key" / "key.json").unlink()
        self.assertEqual(analyze(self.dir)["verdict"], "GAIN_MET")
        (self.dir / "key" / "key.copy.json").write_text("corrupt", encoding="utf-8")
        with self.assertRaisesRegex(EvidenceError, "no intact copy of the key"):
            analyze(self.dir)

    def test_the_analysis_opens_no_python_level_network_connection(self):
        # A unit-level check only. It does not prove host isolation, egress control or log and cache hygiene,
        # which are separate required evidence from the custody environment.
        build_sentinel(self.dir)
        with mock.patch.object(socket, "socket", side_effect=AssertionError("network used")):
            self.assertEqual(analyze(self.dir)["verdict"], "GAIN_MET")

    def test_everything_synthetic_is_labelled_synthetic(self):
        build_run(self.dir)
        self.assertTrue(all(row["answer_text"].startswith("synthetic ") for row in read_rows(self.dir / "trials.jsonl")))
        self.assertTrue(all(row["question_text"].startswith("Synthetic ")
                            for row in read_rows(self.dir / "grader_pack.jsonl")))

    # ---- schema alignment: class value, the kit's six behaviors, opaque question ids ------------------

    def test_the_class_value_is_probe_and_the_old_label_is_rejected(self):
        build_sentinel(self.dir)
        self.assertEqual({q["class"] for q in read_json(self.dir / "design.json")["questions"]},
                         {"answerable", "partial", "probe"})
        for source in ("analyze_run.py", "synthetic_fixture.py"):
            self.assertNotIn("probe_unsupported", (HERE / source).read_text(encoding="utf-8"), source)
        for bad in ("probe_unsupported", "Probe", "unsupported", ""):
            with self.subTest(bad=bad):
                build_sentinel(self.dir)
                design = read_json(self.dir / "design.json")
                design["questions"][-1]["class"] = bad
                write_json(self.dir / "design.json", design)
                freeze(self.dir)
                with self.assertRaisesRegex(EvidenceError, "design classes must be drawn from"):
                    analyze(self.dir)
                self.assertEqual(self.key_paths_read(lambda: analyze(self.dir)), [])

    def test_the_unsupported_claim_gate_still_covers_exactly_the_probe_class(self):
        build_sentinel(self.dir)
        self.set_final_grade(f"T-{SENT['PA1']}-c1", unsupported_claim=True)  # a partial row is outside the denominator
        self.assertEqual(analyze(self.dir)["verdict"], "GAIN_MET")
        self.set_final_grade(f"T-{SENT['P1']}-c1", unsupported_claim=True)
        result = analyze(self.dir)
        self.assertEqual(result["verdict"], "HOLD")
        self.assertFalse(result["gates"]["control"]["unsupported_claims_zero"])
        self.assertTrue(result["gates"]["treatment"]["unsupported_claims_zero"])

    def test_synthetic_expected_behaviors_are_the_kits_six_values_by_class(self):
        build_run(self.dir, per_class={"answerable": 3, "partial": 3, "probe": 8})
        design = {q["id"]: q["class"] for q in read_json(self.dir / "design.json")["questions"]}
        gold = read_json(self.dir / "gold.json")
        for qid, klass in design.items():
            self.assertIn(gold[qid]["expected_behavior"], BEHAVIORS_BY_CLASS[klass])
        self.assertEqual({gold[q]["expected_behavior"] for q, k in design.items() if k == "probe"},
                         {"clarify", "correct_false_premise", "decline", "state_inability_to_observe"})
        self.assertEqual({gold[q]["expected_behavior"] for q, k in design.items() if k == "partial"},
                         {"answer_with_stated_limit"})
        self.assertEqual({b for values in BEHAVIORS_BY_CLASS.values() for b in values},
                         {"answer", "answer_with_stated_limit", "clarify", "correct_false_premise", "decline",
                          "state_inability_to_observe"})

    def test_an_expected_behavior_outside_the_six_or_wrong_for_its_class_fails_closed(self):
        for qclass, bad in (("answerable", "decline"), ("partial", "answer"), ("probe", "answer_supported_part"),
                            ("probe", "decline_or_no_context"), ("probe", "answer_with_stated_limit")):
            with self.subTest(qclass=qclass, bad=bad), tempfile.TemporaryDirectory() as tmp:
                self.dir = Path(tmp)
                build_sentinel(self.dir)
                design = {q["id"]: q["class"] for q in read_json(self.dir / "design.json")["questions"]}
                target = next(q for q, k in design.items() if k == qclass)
                gold = read_json(self.dir / "gold.json")
                gold[target]["expected_behavior"] = bad
                write_json(self.dir / "gold.json", gold)
                rows = read_rows(self.dir / "grader_pack.jsonl")
                for row in rows:
                    if row["question_id"] == target:
                        row["gold"]["expected_behavior"] = bad
                write_rows(self.dir / "grader_pack.jsonl", rows)
                freeze(self.dir)
                with self.assertRaisesRegex(EvidenceError, "expected_behavior for a"):
                    analyze(self.dir)
                self.assertEqual(self.key_paths_read(lambda: analyze(self.dir)), [])

    def test_synthetic_question_ids_are_opaque(self):
        for builder in (build_sentinel, lambda d: build_run(d, per_class={"answerable": 12, "partial": 6, "probe": 6})):
            with tempfile.TemporaryDirectory() as tmp:
                builder(Path(tmp))
                design = read_json(Path(tmp) / "design.json")["questions"]
                ids = [q["id"] for q in design]
                self.assertEqual(opaque_id_problems(ids), [])
                self.assertTrue(all(re.fullmatch(r"Q-[0-9a-f]{8}", i) for i in ids))
                self.assertEqual(len(set(ids)), len(ids))
                classes = {q["id"]: q["class"] for q in design}
                self.assertNotEqual([classes[i] for i in sorted(ids)], sorted(classes.values()),
                                    "sorted ids must not be grouped by class")
                self.assertNotEqual([q["class"] for q in design], sorted(q["class"] for q in design),
                                    "design order must not follow the class")

    def test_ids_that_name_the_class_taxonomy_arm_or_behavior_are_rejected_before_the_key(self):
        for bad in ("SYN-answ-000", "Q-probe-01", "unsupported-3", "Q-control-1", "Q-treatment", "Q-decline-2",
                    "SENT-A1", "Q-clarify", "Q-partial-4"):
            with self.subTest(bad=bad):
                self.assertTrue(opaque_id_problems([bad]), bad)
        for bad in ("SYN-answ-000", "Q-UNDERSPECIFIED-01", "Q-FALSE-PRESUPPOSITION-2", "Q-NONSENSICAL-3",
                    "Q-modality-limited", "Q-live-data-1", "Q-SAFETY-CONCERNED-4", "Q-out-of-database-5",
                    "Q-single-hop-6", "Q-multi-hop-abstract-7", "Q-specific-8", "Q-ragas-9", "Q-UAEval4RAG-10"):
            with self.subTest(bad=bad), tempfile.TemporaryDirectory() as tmp:
                self.dir = Path(tmp)
                build_sentinel(self.dir)
                for name in ("design.json", "gold.json", "trials.jsonl", "grader_pack.jsonl", "grades_a.json",
                             "grades_b.json", "adjudication.json"):
                    path = self.dir / name
                    path.write_text(path.read_text(encoding="utf-8").replace(SENT["A1"], bad), encoding="utf-8")
                freeze(self.dir)  # a consistent, refrozen run: only the id is revealing
                with self.assertRaisesRegex(EvidenceError, "question ids are not opaque"):
                    analyze(self.dir)
                self.assertEqual(self.key_paths_read(lambda: analyze(self.dir)), [])

    def test_the_denylist_covers_every_taxonomy_group_of_the_kit_and_no_hash_id_trips_it(self):
        for label in ("underspecified", "false presupposition", "nonsensical", "unsupported modality",
                      "unavailable live data", "safety-concerned", "out-of-database", "single-hop", "multi-hop",
                      "abstract", "specific"):
            self.assertTrue(opaque_id_problems([f"Q-{label}"]), label)
        generated = [syn_id("answerable", i) for i in range(20000)]  # hex ids cannot spell a taxonomy word
        self.assertEqual(opaque_id_problems(generated), [])

    def test_the_grader_pack_ids_are_opaque_and_carry_no_class_field(self):
        build_run(self.dir)
        for row in read_rows(self.dir / "grader_pack.jsonl"):
            self.assertEqual(opaque_id_problems([row["question_id"]]), [])
            self.assertNotIn("class", row)
            self.assertNotIn("class", row["gold"])


if __name__ == "__main__":
    unittest.main()
