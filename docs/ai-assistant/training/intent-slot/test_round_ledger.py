import unittest

import round_ledger as rl

GOLD = [
    {"id": "g1", "target": {"intent": "set_device_state"}},
    {"id": "g2", "target": {"intent": "clarify"}, "critical": True},
    {"id": "g3", "target": {"intent": "clarify"}},
]


def report(preds, gold_sha="G"):
    want = {g["id"]: g["target"]["intent"] for g in GOLD}
    return {"model": "m", "gold_sha256": gold_sha, "valid_output_rate": 1.0, "intent_accuracy": 0.5,
            "slot_micro_f1": 0.5, "critical_fixture_failures": 1,
            "predictions": {k: {"intent": v} for k, v in preds.items()},
            "details": [{"id": k, "valid": v != "INVALID", "intent_correct": v == want[k]} for k, v in preds.items()]}


class RoundLedgerTest(unittest.TestCase):
    def test_records_per_intent_confusions_and_failed_fixtures(self):
        e = rl.record(report({"g1": "get_device_state", "g2": "set_device_state", "g3": "clarify"}), GOLD, "r1", "P1")
        self.assertEqual(e["per_intent"]["clarify"], {"correct": 1, "total": 2})
        self.assertEqual(e["confusions"], {"set_device_state->get_device_state": 1, "clarify->set_device_state": 1})
        self.assertTrue(e["failed"]["g2"]["critical"])
        self.assertFalse(e["released"])

    def test_a_schema_invalid_output_is_wrong_even_when_its_label_matches(self):
        r = report({"g1": "set_device_state", "g2": "clarify", "g3": "clarify"})
        r["details"][0].update(valid=False, intent_correct=False)
        e = rl.record(r, GOLD, "r1", "P1")
        self.assertEqual(e["failed"]["g1"]["got"], "INVALID")
        self.assertEqual(e["per_intent"]["set_device_state"], {"correct": 0, "total": 1})

    def _pair(self, prev_preds, cur_preds, p2="P2", g2="G"):
        a = rl.record(report(prev_preds), GOLD, "r1", "P1")
        b = rl.record(report(cur_preds, g2), GOLD, "r2", p2)
        return rl.compare(a, b)

    def test_clean_improvement_has_no_warnings(self):
        _, w = self._pair({"g1": "x", "g2": "x", "g3": "x"}, {"g1": "set_device_state", "g2": "x", "g3": "clarify"})
        self.assertEqual(w, [])

    def test_regression_and_new_critical_failure_are_flagged(self):
        _, w = self._pair({"g1": "set_device_state", "g2": "clarify", "g3": "clarify"},
                          {"g1": "set_device_state", "g2": "set_device_state", "g3": "clarify"})
        self.assertTrue(any("REGRESSION" in x and "g2" in x for x in w))
        self.assertTrue(any("NEW CRITICAL" in x for x in w))

    def test_unchanged_prompt_and_changed_gold_are_flagged(self):
        _, w = self._pair({"g1": "x"}, {"g1": "x"}, p2="P1", g2="OTHER")
        self.assertTrue(any("PROMPT UNCHANGED" in x for x in w))
        self.assertTrue(any("GOLD CHANGED" in x for x in w))


    def test_a_metric_that_gets_worse_is_flagged_even_when_no_fixture_flips(self):
        a = rl.record(report({"g1": "set_device_state", "g2": "x", "g3": "x"}), GOLD, "r1", "P1")
        b = rl.record(report({"g1": "set_device_state", "g2": "x", "g3": "x"}), GOLD, "r2", "P2")
        b["valid_output_rate"] = a["valid_output_rate"] - 0.05
        b["critical_fixture_failures"] = a["critical_fixture_failures"] + 1
        _, w = rl.compare(a, b)
        self.assertTrue(any("METRIC DROP" in x and "valid_output_rate" in x for x in w))
        self.assertTrue(any("CRITICAL FAILURES ROSE" in x for x in w))


if __name__ == "__main__":
    unittest.main()
