import unittest
import json
import tempfile
from pathlib import Path
from unittest.mock import patch

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
    def test_record_cli_requires_hashes_from_the_evaluated_package(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            gold, prompt, schema = [root / name for name in ("gold", "prompt", "schema")]
            gold.write_text("\n".join(json.dumps(g) for g in GOLD), encoding="utf-8")
            prompt.write_text("unchanged prompt", encoding="utf-8")
            schema.write_text("{}", encoding="utf-8")
            value = report({"g1": "x"}, rl.sha(gold))
            result, output = root / "report.json", root / "round.json"
            args = ["round_ledger.py", "record", str(result), "--round", "r4", "--out", str(output),
                    "--gold", str(gold), "--prompt", str(prompt), "--schema", str(schema)]
            result.write_text(json.dumps(value), encoding="utf-8")
            with patch("sys.argv", args), self.assertRaises(SystemExit):
                rl.main()
            value.update(prompt_sha256=rl.sha(prompt), schema_sha256=rl.sha(schema))
            result.write_text(json.dumps(value), encoding="utf-8")
            with patch("sys.argv", args):
                rl.main()
            self.assertEqual(json.loads(output.read_text())["schema_sha256"], rl.sha(schema))
            schema.write_text('{"type":"object"}', encoding="utf-8")
            with patch("sys.argv", args), self.assertRaises(SystemExit):
                rl.main()

    def test_schema_only_round_is_attributed_without_unchanged_prompt_warning(self):
        a = rl.record(report({"g1": "x"}), GOLD, "r3", "P", "S1")
        b = rl.record(report({"g1": "x"}), GOLD, "r4", "P", "S2")
        self.assertEqual(rl.compare(a, b)[1], [])
        b["prompt_sha256"] = "P2"
        self.assertTrue(any("MULTIPLE CHANGES" in w for w in rl.compare(a, b)[1]))
        a.pop("schema_sha256")
        self.assertTrue(any("SCHEMA HASH UNKNOWN" in w for w in rl.compare(a, b)[1]))

    def test_records_per_intent_confusions_and_failed_fixtures(self):
        e = rl.record(report({"g1": "get_device_state", "g2": "set_device_state", "g3": "clarify"}), GOLD, "r1", "P1", "S1")
        self.assertEqual(e["per_intent"]["clarify"], {"correct": 1, "total": 2})
        self.assertEqual(e["confusions"], {"set_device_state->get_device_state": 1, "clarify->set_device_state": 1})
        self.assertTrue(e["failed"]["g2"]["critical"])
        self.assertFalse(e["released"])

    def test_a_schema_invalid_output_is_wrong_even_when_its_label_matches(self):
        r = report({"g1": "set_device_state", "g2": "clarify", "g3": "clarify"})
        r["details"][0].update(valid=False, intent_correct=False)
        e = rl.record(r, GOLD, "r1", "P1", "S1")
        self.assertEqual(e["failed"]["g1"]["got"], "INVALID")
        self.assertEqual(e["per_intent"]["set_device_state"], {"correct": 0, "total": 1})

    def _pair(self, prev_preds, cur_preds, p2="P2", g2="G"):
        a = rl.record(report(prev_preds), GOLD, "r1", "P1", "S1")
        b = rl.record(report(cur_preds, g2), GOLD, "r2", p2, "S1")
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
        a = rl.record(report({"g1": "set_device_state", "g2": "x", "g3": "x"}), GOLD, "r1", "P1", "S1")
        b = rl.record(report({"g1": "set_device_state", "g2": "x", "g3": "x"}), GOLD, "r2", "P2", "S1")
        b["valid_output_rate"] = a["valid_output_rate"] - 0.05
        b["critical_fixture_failures"] = a["critical_fixture_failures"] + 1
        _, w = rl.compare(a, b)
        self.assertTrue(any("METRIC DROP" in x and "valid_output_rate" in x for x in w))
        self.assertTrue(any("CRITICAL FAILURES ROSE" in x for x in w))


if __name__ == "__main__":
    unittest.main()
