import json
import socket
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import analyze_run
from analyze_run import EvidenceError, analyze, freeze, leak_scan, paired_bootstrap
from synthetic_fixture import build_run, build_sentinel


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def write_json(path, data):
    Path(path).write_text(json.dumps(data, indent=2, sort_keys=True), encoding="utf-8")


class FixtureAnalysisTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self._tmp.name)

    def tearDown(self):
        self._tmp.cleanup()

    def blinded_for(self, trial_id):
        return next(r["blinded_id"] for r in read_json(self.dir / "key.json")["rows"] if r["trial_id"] == trial_id)

    # ---- sentinel mini-set: a known answer ----------------------------------------------------

    def test_sentinel_set_gives_the_known_difference(self):
        build_sentinel(self.dir)
        result = analyze(self.dir)
        self.assertAlmostEqual(result["delta"], 0.5, places=12)  # (1 + 2/3 + 1/3 + 0) / 4
        self.assertEqual(result["verdict"], "GAIN_MET")
        self.assertGreater(result["ci95"][0], 0)
        self.assertEqual((result["trials_seen"], result["trials_expected"]), (36, 36))
        self.assertEqual(result["answerable_questions"], 4)
        self.assertEqual(result["reasons"], [])

    def test_bootstrap_is_deterministic_for_a_seed_and_differs_across_seeds(self):
        spread = [i / 21 for i in range(22)]  # enough distinct values for a seed to show
        first = paired_bootstrap(spread, 10000, 20261109)
        self.assertEqual(first, paired_bootstrap(spread, 10000, 20261109))
        self.assertNotEqual(first, paired_bootstrap(spread, 10000, 20261110))
        build_sentinel(self.dir)
        sentinel = [1.0, 2 / 3, 1 / 3, 0.0]
        for got, want in zip(analyze(self.dir)["ci95"], paired_bootstrap(sentinel, 10000, 20261109)):
            self.assertAlmostEqual(got, want, places=9)  # same draws; only float rounding of the inputs can differ
        self.assertAlmostEqual(analyze(self.dir)["ci95"][0], 1 / 6, places=9)  # pinned for seed 20261109

    def test_whole_analysis_is_reproducible(self):
        build_sentinel(self.dir)
        self.assertEqual(analyze(self.dir), analyze(self.dir))

    # ---- two fake arms ------------------------------------------------------------------------

    def test_a_clearly_better_treatment_arm_meets_the_gain(self):
        build_run(self.dir, control_pass=0.2, treatment_pass=0.95, per_class={"answerable": 20, "probe_unsupported": 4, "probe_other": 4})
        result = analyze(self.dir)
        self.assertEqual(result["verdict"], "GAIN_MET")
        self.assertGreaterEqual(result["delta"], 0.10)

    def test_identical_arms_do_not_meet_the_gain(self):
        build_run(self.dir, control_pass=0.6, treatment_pass=0.6, seed=3)
        result = analyze(self.dir)
        self.assertNotEqual(result["verdict"], "GAIN_MET")

    # ---- mapping and key isolation ------------------------------------------------------------

    def test_a_swapped_arm_mapping_negates_the_difference_when_refrozen(self):
        build_sentinel(self.dir)
        before = analyze(self.dir)["delta"]
        key = read_json(self.dir / "key.json")
        for row in key["rows"]:
            row["arm"] = "control" if row["arm"] == "treatment" else "treatment"
        for name in ("key.json", "key.copy.json"):
            write_json(self.dir / name, key)
        freeze(self.dir)
        after = analyze(self.dir)
        self.assertAlmostEqual(after["delta"], -before, places=12)
        self.assertEqual(after["verdict"], "GAIN_NOT_MET")

    def test_a_swap_after_freezing_is_caught_by_the_key_hash(self):
        build_sentinel(self.dir)
        key = read_json(self.dir / "key.json")
        for row in key["rows"]:
            row["arm"] = "control" if row["arm"] == "treatment" else "treatment"
        for name in ("key.json", "key.copy.json"):
            write_json(self.dir / name, key)
        with self.assertRaisesRegex(EvidenceError, "no intact copy of the key"):
            analyze(self.dir)

    def test_the_grader_pack_holds_only_blinded_fields(self):
        build_sentinel(self.dir)
        for line in (self.dir / "grader_pack.jsonl").read_text(encoding="utf-8").splitlines():
            self.assertEqual(set(json.loads(line)), {"blinded_id", "question_id", "answer_text"})

    def test_leak_scan_is_clean_on_a_blinded_run_and_catches_an_injected_leak(self):
        build_sentinel(self.dir)
        key = read_json(self.dir / "key.json")
        forbidden = ["control", "treatment", key["canary"]] + [r["trial_id"] for r in key["rows"]]
        self.assertEqual(leak_scan(self.dir, forbidden), [])
        with (self.dir / "grader_pack.jsonl").open("a", encoding="utf-8") as handle:
            handle.write(json.dumps({"blinded_id": "X", "question_id": "SENT-A1",
                                     "answer_text": "arm=" + "treatment"}) + "\n")
            handle.write(json.dumps({"blinded_id": "Y", "question_id": "SENT-A1", "answer_text": key["canary"]}) + "\n")
        found = {token for _, token in leak_scan(self.dir, forbidden)}
        self.assertIn("treatment", found)
        self.assertIn(key["canary"], found)

    # ---- malformed, missing, duplicated, altered ----------------------------------------------

    def test_a_missing_trial_is_a_hold_not_a_silent_pass(self):
        build_run(self.dir, drop=("T-SYN-answ-000-t2",))
        result = analyze(self.dir)
        self.assertEqual(result["verdict"], "HOLD")
        self.assertTrue(any(r.startswith("missing trials") for r in result["reasons"]))
        self.assertEqual(result["trials_seen"], result["trials_expected"] - 1)

    def test_a_duplicate_trial_id_is_an_evidence_error(self):
        dup = {"trial_id": "T-SYN-answ-001-c1", "question_id": "SYN-answ-001", "repeat": 1, "status": "ok",
               "latency_s": 5.0, "answer_text": "synthetic answer duplicate"}
        build_run(self.dir, extra_trials=(dup,))
        with self.assertRaisesRegex(EvidenceError, "duplicate trial id"):
            analyze(self.dir)

    def test_an_altered_grades_file_fails_the_hash(self):
        build_sentinel(self.dir)
        grades = read_json(self.dir / "grades.json")
        some = next(iter(grades))
        grades[some]["grade"] = "PASS" if grades[some]["grade"] == "FAIL" else "FAIL"
        write_json(self.dir / "grades.json", grades)
        with self.assertRaisesRegex(EvidenceError, "hash mismatch or missing file: grades.json"):
            analyze(self.dir)

    def test_an_altered_trial_log_and_a_missing_manifest_are_both_refused(self):
        build_sentinel(self.dir)
        with (self.dir / "trials.jsonl").open("a", encoding="utf-8") as handle:
            handle.write("\n")
        with self.assertRaises(EvidenceError):
            analyze(self.dir)
        (self.dir / "manifest.json").unlink()
        with self.assertRaisesRegex(EvidenceError, "manifest.json unreadable"):
            analyze(self.dir)

    # ---- trials are never dropped; gates hold -------------------------------------------------

    def test_a_technical_error_is_non_pass_and_the_trial_is_retained(self):
        build_sentinel(self.dir)
        rows = [json.loads(line) for line in (self.dir / "trials.jsonl").read_text(encoding="utf-8").splitlines() if line]
        for row in rows:
            if row["trial_id"] == "T-SENT-A1-t1":
                row["status"] = "technical_error"  # graded PASS below, still counts as non-PASS
        (self.dir / "trials.jsonl").write_text("\n".join(json.dumps(r, sort_keys=True) for r in rows) + "\n", encoding="utf-8")
        freeze(self.dir)
        result = analyze(self.dir)
        self.assertEqual(result["trials_seen"], 36)
        self.assertAlmostEqual(result["delta"], (2 / 3 + 2 / 3 + 1 / 3 + 0) / 4, places=12)

    def test_a_failed_wrong_fact_gate_is_a_hold_even_with_a_large_gain(self):
        build_sentinel(self.dir)
        grades = read_json(self.dir / "grades.json")
        grades[self.blinded_for("T-SENT-A2-t1")]["wrong_fact"] = True
        write_json(self.dir / "grades.json", grades)
        freeze(self.dir)
        result = analyze(self.dir)
        self.assertEqual(result["verdict"], "HOLD")
        self.assertIn("gate failed: wrong_fact_not_up", result["reasons"])

    def test_a_failed_latency_gate_is_a_hold(self):
        build_sentinel(self.dir)
        rows = [json.loads(line) for line in (self.dir / "trials.jsonl").read_text(encoding="utf-8").splitlines() if line]
        for row in rows:
            if row["trial_id"].startswith("T-SENT-A1-t"):
                row["latency_s"] = 90.0
        (self.dir / "trials.jsonl").write_text("\n".join(json.dumps(r, sort_keys=True) for r in rows) + "\n", encoding="utf-8")
        freeze(self.dir)
        self.assertIn("gate failed: latency_p95_within_cap", analyze(self.dir)["reasons"])

    # ---- access, network boundary, two-copy recovery ------------------------------------------

    def test_either_key_copy_is_enough_and_neither_is_an_error(self):
        build_sentinel(self.dir)
        (self.dir / "key.json").unlink()
        self.assertEqual(analyze(self.dir)["verdict"], "GAIN_MET")
        (self.dir / "key.copy.json").write_text("corrupt", encoding="utf-8")
        with self.assertRaisesRegex(EvidenceError, "no intact copy of the key"):
            analyze(self.dir)

    def test_the_analysis_opens_no_network_connection(self):
        build_sentinel(self.dir)
        with mock.patch.object(socket, "socket", side_effect=AssertionError("network used")):
            self.assertEqual(analyze(self.dir)["verdict"], "GAIN_MET")

    def test_the_analysis_reads_only_the_run_directory(self):
        build_sentinel(self.dir)
        opened = []
        real = Path.read_bytes
        real_text = Path.read_text

        def spy_bytes(path, *a, **k):
            opened.append(Path(path).resolve())
            return real(path, *a, **k)

        def spy_text(path, *a, **k):
            opened.append(Path(path).resolve())
            return real_text(path, *a, **k)

        with mock.patch.object(Path, "read_bytes", spy_bytes), mock.patch.object(Path, "read_text", spy_text):
            analyze(self.dir)
        base = self.dir.resolve()
        self.assertTrue(opened)
        self.assertTrue(all(base in p.parents for p in opened), [str(p) for p in opened if base not in p.parents])

    def test_everything_synthetic_is_labelled_synthetic(self):
        build_run(self.dir)
        design = read_json(self.dir / "design.json")
        self.assertTrue(all(q["id"].startswith("SYN-") for q in design["questions"]))


if __name__ == "__main__":
    unittest.main()
