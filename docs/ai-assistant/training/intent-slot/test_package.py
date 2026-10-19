import copy
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch, MagicMock
from jsonschema import Draft202012Validator, ValidationError
from contract import ROOT, SCHEMA, proposal, read_jsonl, validate, request_content
from evaluate import score, query
from generate_synthetic import generate
from import_sources import adapt
from prepare_sft import split_rows
from vocabulary import CONCEPT_IDS


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

    def test_poc2_needs_and_coverage(self):
        needs = {r["target"]["slots"]["concept"] for r in self.gold
                 if r["target"]["intent"] == "express_need"}
        self.assertEqual(needs, set(CONCEPT_IDS))
        self.assertTrue(any(r["text"] == "I'm cold" for r in self.gold))
        for slots in ({}, {"concept": "unknown"}):
            with self.assertRaises((ValueError, ValidationError)):
                proposal("express_need", slots)
        with self.assertRaises(ValidationError):
            proposal("answer_question", {"concept": "cold"})

    def automation_case(self, kind):
        return copy.deepcopy(next(r for r in self.gold
            if r["target"]["automation"] and
            r["target"]["automation"]["trigger"].get("kind") == kind))

    def test_schedule_and_presence_validation(self):
        for kind, field in (("time_once", "at"), ("time_recurring", "start_time")):
            row = self.automation_case(kind)
            row["target"]["automation"]["trigger"][field] = "2027-01-01T12:00:00"
            with self.assertRaises((ValidationError, ValueError)):
                validate(row["target"])
        row = self.automation_case("state_changes_to")
        trigger = row["target"]["automation"]["trigger"]
        del trigger["for_seconds"]
        validate(row["target"])
        for invalid in (-1, 0.5, "60"):
            trigger["for_seconds"] = invalid
            with self.assertRaises((ValidationError, ValueError)):
                validate(row["target"])

    def test_invalid_recurrences_fail_without_expansion(self):
        row = self.automation_case("time_recurring")
        for rule in ("FREQ=NOPE", "FREQ=DAILY;COUNT=0", "FREQ=DAILY;INTERVAL=0",
                     "FREQ=DAILY;FREQ=WEEKLY", "FREQ=DAILY;COUNT=2;UNTIL=20270101T000000Z",
                     "FREQ=DAILY\nEXDATE:20270101T000000Z",
                     # r4 candidate (gold-32, gold-47): an ISO datetime with an offset, or a bare date, is not a valid UNTIL.
                     "FREQ=DAILY;UNTIL=2026-12-31T23:59:59-06:00", "FREQ=DAILY;UNTIL=2027-01-01",
                     "FREQ=DAILY;UNTIL=20271231", "FREQ=DAILY;UNTIL=20270101T000000", "FREQ=DAILY;UNTIL=20270101T000000+0100"):
            row["target"]["automation"]["trigger"]["rrule"] = rule
            # The schema (pattern) now rejects some of these before contract.py's semantic checks run.
            with self.subTest(rule=rule), self.assertRaises((ValueError, ValidationError)):
                validate(row["target"])

    def test_well_formed_until_is_accepted(self):
        row = self.automation_case("time_recurring")
        for rule in ("FREQ=DAILY;UNTIL=20271231T235959Z",):
            row["target"]["automation"]["trigger"]["rrule"] = rule
            with self.subTest(rule=rule):
                validate(row["target"])

    def test_trusted_card_cannot_be_overridden(self):
        row = self.automation_case("time_once")
        card = row["card_target"]
        output = proposal("set_device_state", {"state": "on"})
        validate(output, card)  # Caller retains the target even when omitted.
        output["slots"]["site"] = "elsewhere"
        with self.assertRaises(ValueError):
            validate(output, card)
        row["target"]["automation"]["actions"][0]["target"]["device_name"] = "lock"
        with self.assertRaises(ValueError):
            validate(row["target"], card)
        with self.assertRaises(ValidationError):
            request_content("on", {"device_name": "lamp"})
        self.assertEqual(json.loads(request_content("on", card))["card_target"], card)

    def test_generated_triggers_and_export_retain_card(self):
        generated = generate(ROOT / "synthesis-templates.json")
        triggers = {r["target"]["automation"]["trigger"].get("kind") for r in generated
                    if r["target"]["automation"]}
        self.assertTrue({"time_once", "time_recurring", "state_changes_to"} <= triggers)
        rows = self.reviewed()
        sample = next(r for r in generated if r.get("card_target"))
        rows[0].update(text=sample["text"], target=sample["target"], card_target=sample["card_target"])
        result = split_rows(rows, self.gold)
        exported = next(r for split in result.values() for r in split if r["id"] == rows[0]["id"])
        self.assertEqual(json.loads(exported["messages"][1]["content"])["card_target"], sample["card_target"])
        self.assertTrue(all(r["provenance"]["review_status"] == "unreviewed" for r in generated))
        self.assertNotIn('"entity_id"', json.dumps(generated))

    def test_ollama_request_preserves_trusted_card(self):
        card = {"device_name": "lamp", "location": "study", "site": "home"}
        expected = proposal("set_device_state", {"state": "on"})
        response = MagicMock()
        response.__enter__.return_value.read.return_value = json.dumps(
            {"message": {"content": json.dumps(expected)}}).encode()
        with patch("evaluate.urllib.request.urlopen", return_value=response) as transport:
            self.assertEqual(query("on", "test", "http://localhost:11434/api/chat", 1, card), expected)
        body = json.loads(transport.call_args.args[0].data)
        self.assertEqual(json.loads(body["messages"][1]["content"]), {"text": "on", "card_target": card})

    def test_card_conflict_is_invalid_and_critical(self):
        row = self.automation_case("time_once")
        self.predictions[row["id"]]["automation"]["actions"][0]["target"]["site"] = "wrong"
        report = score(self.gold, self.predictions)
        self.assertFalse(report["offline_pass"])
        self.assertEqual(report["critical_fixture_failures"], 1)

    def test_oracle_is_not_release_evidence(self):
        report = score(self.gold, self.predictions)
        self.assertTrue(report["offline_pass"])
        self.assertFalse(report["release_eligible"])
        self.assertEqual(report["integration_gates"]["accepted_invented_entities"], "NOT_MEASURED")

    def test_malformed_and_missing_predictions_count(self):
        self.predictions[self.gold[0]["id"]] = "not JSON"
        del self.predictions[self.gold[1]["id"]]
        del self.predictions[self.gold[2]["id"]]  # Three of 42 exceed the unchanged 5% gate.
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
