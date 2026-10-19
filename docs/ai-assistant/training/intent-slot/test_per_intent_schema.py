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


if __name__ == "__main__":
    unittest.main()
