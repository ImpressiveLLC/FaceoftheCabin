import copy
import json
from pathlib import Path
import unittest
from check_results import compare, contains, validate


class OracleEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.catalog = json.loads(Path(__file__).with_name("oracle-cases.json").read_text())
        self.cases = validate(self.catalog)

    def test_catalog_is_complete_and_has_boundary_and_vision_cases(self):
        ids = {c["id"] for c in self.cases}
        self.assertTrue({"rh-at30", "rh-at50", "rh-at60", "rh-at100", "unit-zero-watts", "reporting-retained", "composition-historical-alert"} <= ids)
        self.assertEqual(6, len(self.catalog["humanCases"]))

    def test_no_evidence_is_not_run(self):
        self.assertTrue(all(r["status"] == "NOT_RUN" for r in compare(self.cases, [])))

    def test_wrong_boundary_fails_and_other_cases_remain_not_run(self):
        report = {r["id"]: r["status"] for r in compare(self.cases, [{"id": "rh-at60", "actual": {"attention": "Normal", "current": True}}])}
        self.assertEqual("FAIL", report["rh-at60"])
        self.assertEqual("NOT_RUN", report["rh-at50"])

    def test_extra_diagnostics_are_allowed_but_missing_claims_fail(self):
        self.assertTrue(contains({"value": 0, "source": "meter"}, {"value": 0}))
        self.assertFalse(contains({}, {"value": 0}))
        self.assertFalse(contains({"eligible": 0}, {"eligible": False}))

    def test_duplicate_or_unknown_results_rejected(self):
        with self.assertRaises(ValueError):
            compare(self.cases, [{"id": "invented", "actual": {}}])
        row = {"id": self.cases[0]["id"], "actual": {}}
        with self.assertRaises(ValueError):
            compare(self.cases, [row, row])

    def test_duplicate_catalog_and_fabricated_human_pass_rejected(self):
        duplicate = copy.deepcopy(self.catalog)
        duplicate["cases"].append(duplicate["cases"][0])
        with self.assertRaises(ValueError):
            validate(duplicate)
        self.catalog["humanCases"][0]["status"] = "PASS"
        with self.assertRaises(ValueError):
            validate(self.catalog)


if __name__ == "__main__":
    unittest.main()
