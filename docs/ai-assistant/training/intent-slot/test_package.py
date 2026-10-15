import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from jsonschema import Draft202012Validator, ValidationError
from contract import ROOT, SCHEMA, proposal, read_jsonl, validate
from evaluate import score
from generate_synthetic import generate
from import_sources import adapt
from prepare_sft import split_rows


class PackageTests(unittest.TestCase):
    def setUp(self):
        self.gold = read_jsonl(ROOT / "gold.jsonl")
        self.predictions = {r["id"]: copy.deepcopy(r["target"]) for r in self.gold}

    def test_schemas_and_fixtures(self):
        Draft202012Validator.check_schema(SCHEMA)
        automation = json.loads((ROOT / "automation.schema.json").read_text())
        Draft202012Validator.check_schema(automation)
        for row in self.gold:
            validate(row["target"])
            if row["target"]["automation"]:
                Draft202012Validator(automation).validate(row["target"]["automation"])

    def test_oracle_is_not_release_evidence(self):
        report = score(self.gold, self.predictions)
        self.assertTrue(report["offline_pass"])
        self.assertFalse(report["release_eligible"])
        self.assertEqual(report["integration_gates"]["accepted_invented_entities"], "NOT_MEASURED")

    def test_malformed_and_missing_predictions_count(self):
        self.predictions[self.gold[0]["id"]] = "not JSON"
        del self.predictions[self.gold[1]["id"]]
        report = score(self.gold, self.predictions)
        self.assertFalse(report["offline_pass"])
        self.assertLess(report["slot_micro_f1"], 1)

    def test_invented_id_and_confirmation_rejected(self):
        output = proposal("set_device_state", {"device_name": "lights", "state": "on"})
        output["slots"]["entity_id"] = "light.imaginary"
        with self.assertRaises(ValidationError):
            validate(output)
        del output["slots"]["entity_id"]
        output["requires_confirmation"] = False
        with self.assertRaises(ValueError):
            validate(output)

    def test_critical_failure_overrides_high_aggregate(self):
        row = next(r for r in self.gold if r["target"]["intent"] == "unsupported")
        self.predictions[row["id"]] = proposal("answer_question")
        self.assertFalse(score(self.gold, self.predictions)["offline_pass"])

    def test_automation_semantics_are_graded(self):
        row = next(r for r in self.gold if r["target"]["automation"])
        self.predictions[row["id"]]["automation"]["actions"][1]["delay_seconds"] = 3
        self.assertEqual(score(self.gold, self.predictions)["critical_fixture_failures"], 1)

    def test_extra_slots_reduce_precision(self):
        self.predictions[self.gold[0]["id"]]["slots"]["site"] = "invented"
        self.assertLess(score(self.gold, self.predictions)["slot_micro_f1"], 1)

    def test_adapters(self):
        enfuse = [{"text": "lamp on", "intent": "set_device_state", "slots": [
            {"slot_type": "device_name", "slot_value": "lamp"},
            {"slot_type": "state", "slot_value": "on"}]}]
        self.assertEqual(list(adapt("enfuse", enfuse))[0][1]["slots"]["state"], "on")
        ha = {"tests": [{"sentences": ["activate den lights"], "intent": {
            "name": "HassTurnOn", "slots": {"domain": "light", "area": "den"}}}]}
        self.assertEqual(list(adapt("ha", ha))[0][1]["slots"]["location"], "den")
        snips = {"intents": {"turnLightOff": {"utterances": [{"data": [
            {"text": "darken "}, {"text": "loft", "slot_name": "room"}]}]}}}
        self.assertEqual(list(adapt("snips", snips))[0][0], "darken loft")

    def test_unknown_and_duplicate_source_labels_fail(self):
        with self.assertRaises(ValueError):
            list(adapt("snips", {"intents": {"unlock": {}}}))
        with self.assertRaises(ValueError):
            list(adapt("enfuse", [{"text": "x", "intent": "get_device_state", "slots": [
                {"slot_type": "state", "slot_value": "on"},
                {"slot_type": "state", "slot_value": "off"}]}]))

    def test_cli_roundtrip_and_failure_exit(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            predictions = path / "predictions.jsonl"
            predictions.write_text("".join(json.dumps({"id": k, "output": v}) + "\n"
                                            for k, v in self.predictions.items()), encoding="utf-8")
            command = [sys.executable, "-B", str(ROOT / "evaluate.py"), "--predictions",
                       str(predictions), "--output", str(path / "report.json")]
            good = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(good.returncode, 0, good.stderr)
            self.assertFalse(json.loads((path / "report.json").read_text())["release_eligible"])
            predictions.write_text("", encoding="utf-8")
            bad = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(bad.returncode, 1, bad.stderr)
            self.assertEqual(json.loads((path / "report.json").read_text())["valid_output_rate"], 0)

    def test_review_required(self):
        with self.assertRaises(ValueError):
            split_rows(generate(ROOT / "synthesis-templates.json"), self.gold)

    def reviewed(self):
        rows = []
        for i in range(100):
            for j in range(2):
                rows.append({"id": f"{i}-{j}", "text": f"example {i} version {j}",
                             "group": f"family-{i}", "target": proposal("answer_question"),
                             "provenance": {"review_status": "approved", "reviewer": "test-only",
                                            "reviewed_at": "2026-10-14", "source": "test",
                                            "revision": "test", "sha256": "test", "license": "test"}})
        return rows

    def test_group_split_and_holdout_leakage(self):
        rows = self.reviewed()
        result = split_rows(rows, self.gold)
        groups = {key: {r["group"] for r in values} for key, values in result.items()}
        self.assertFalse(groups["train"] & groups["test"])
        self.assertFalse(groups["train"] & groups["validation"])
        reversed_result = split_rows(list(reversed(rows)), self.gold)
        self.assertEqual(groups, {k: {r["group"] for r in v} for k, v in reversed_result.items()})
        rows[0]["text"] = self.gold[0]["text"]
        with self.assertRaises(ValueError):
            split_rows(rows, self.gold)


if __name__ == "__main__":
    unittest.main()
