"""Decoder constraints must reject concept leaks before semantic validation."""
import copy
import unittest

from contract import SCHEMA, VALIDATOR, read_jsonl, ROOT


class PerIntentSchemaTest(unittest.TestCase):
    def test_every_non_need_intent_rejects_every_concept_at_schema_layer(self):
        ordinary, need = SCHEMA["oneOf"]
        concepts = need["properties"]["slots"]["properties"]["concept"]["enum"]
        for intent in ordinary["properties"]["intent"]["enum"]:
            value = dict(intent=intent, slots={}, requires_confirmation=True,
                         clarification=None, automation=None)
            self.assertTrue(VALIDATOR.is_valid(value), intent)
            for concept in concepts:
                value["slots"] = {"concept": concept}
                self.assertFalse(VALIDATOR.is_valid(value), (intent, concept))

    def test_gold_and_unknown_fields(self):
        for row in read_jsonl(ROOT / "gold.jsonl"):
            value = copy.deepcopy(row["target"])
            self.assertTrue(VALIDATOR.is_valid(value), row["id"])
            value["slots"]["invented"] = "unsafe"
            self.assertFalse(VALIDATOR.is_valid(value), row["id"])

    def test_all_need_concepts_remain_allowed(self):
        for concept in SCHEMA["oneOf"][1]["properties"]["slots"]["properties"]["concept"]["enum"]:
            value = dict(intent="express_need", slots={"concept": concept},
                         requires_confirmation=True, clarification=None, automation=None)
            self.assertTrue(VALIDATOR.is_valid(value), concept)

    def test_unit_is_a_closed_list(self):
        for unit in ("C", "F", "K", "percent", "lux", "seconds", "minutes", "hours"):
            value = dict(intent="set_device_attribute", slots={"unit": unit},
                         requires_confirmation=True, clarification=None, automation=None)
            self.assertTrue(VALIDATOR.is_valid(value), unit)
        # Observed in r4 (gold-08, gold-40): spelled-out and decorated units.
        for unit in ("Fahrenheit", "°F", "none", "None", "unknown", "null"):
            value = dict(intent="set_device_attribute", slots={"unit": unit},
                         requires_confirmation=True, clarification=None, automation=None)
            self.assertFalse(VALIDATOR.is_valid(value), unit)

    def test_an_offset_until_cannot_be_generated(self):
        row = next(r for r in read_jsonl(ROOT / "gold.jsonl")
                   if (r["target"].get("automation") or {}).get("trigger", {}).get("kind") == "time_recurring")
        for rule, ok in (("FREQ=DAILY;UNTIL=20271231T235959Z", True), ("FREQ=WEEKLY;BYDAY=MO,-1FR;INTERVAL=2", True),
                         ("FREQ=DAILY;UNTIL=2026-12-31T23:59:59-06:00", False), ("FREQ=DAILY;UNTIL=2026-12-31T23H59M-06", False),
                         ("FREQ=DAILY;FOO=1", False), ("FREQ=DAILY;UNTIL=20269999T235959Z", False), ("DAILY", False)):
            value = copy.deepcopy(row["target"])
            value["automation"]["trigger"]["rrule"] = rule
            self.assertEqual(VALIDATOR.is_valid(value), ok, rule)


if __name__ == "__main__":
    unittest.main()
