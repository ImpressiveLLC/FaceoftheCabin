"""The need and room vocabulary: one source (concepts.json, areas.json), checked against every place that repeats it."""
import json
import re
import unittest
from pathlib import Path

import vocabulary as v

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[3]


def lines(name):
    return [json.loads(line) for line in (ROOT / name).read_text(encoding="utf-8").splitlines() if line.strip()]


def walk_targets(rows):
    for row in rows:
        yield from v._walk_targets(row["target"])


class ConceptVocabulary(unittest.TestCase):
    def test_ids_are_unique_and_match_the_schema_enum_exactly(self):
        self.assertEqual(len(v.CONCEPT_IDS), len(set(v.CONCEPT_IDS)))
        schema = json.loads((ROOT / "intent-slot.schema.json").read_text(encoding="utf-8"))
        self.assertEqual(schema["properties"]["slots"]["properties"]["concept"]["enum"], v.CONCEPT_IDS)

    def test_both_prompts_name_every_concept_and_nothing_else(self):
        for name in ("system.txt", "Modelfile.intent-poc2"):
            text = (ROOT / name).read_text(encoding="utf-8")
            listed = re.search(r"with a concept: ([a-z_, ]+?) \(provisional vocabulary\)", text)
            self.assertIsNotNone(listed, name)
            self.assertEqual(re.split(r", | or ", listed.group(1)), v.CONCEPT_IDS, name)

    def test_the_java_copy_of_the_schema_and_prompt_is_the_same_file(self):
        resources = REPO / "cabin-orchestration-platform" / "backend" / "src" / "main" / "resources" / "assistant"
        for name in ("intent-slot.schema.json", "system.txt"):
            copy = (resources / name).read_text(encoding="utf-8").replace("\r\n", "\n").strip()
            original = (ROOT / name).read_text(encoding="utf-8").replace("\r\n", "\n").strip()
            self.assertEqual(copy, original, f"backend resources/assistant/{name} drifted from docs; copy it over")

    def test_the_ontology_entity_lists_the_same_concepts(self):
        try:
            import yaml
        except ImportError:  # PyYAML is in requirements.txt; CI has it
            self.skipTest("PyYAML not installed")
        elements = yaml.safe_load((REPO / "docs" / "ontology.yaml").read_text(encoding="utf-8"))["elements"]
        entity = next(e for e in elements if e["id"] == "human_need_concept")
        self.assertEqual(entity["constraint"]["enum"], v.CONCEPT_IDS)
        by_id = {c["id"]: c for c in entity["concept_vocabulary"]}
        self.assertEqual(list(by_id), v.CONCEPT_IDS)
        for concept in v.CONCEPTS:
            self.assertEqual(by_id[concept["id"]]["aliases"], concept["aliases"], concept["id"])
            self.assertEqual(by_id[concept["id"]]["definition"].strip(), concept["definition"].strip(), concept["id"])

    def test_aliases_are_unique_and_never_another_concept(self):
        seen = {}
        for concept in v.CONCEPTS:
            for alias in concept["aliases"]:
                key = v.normalize(alias)
                self.assertNotIn(key, v.CONCEPT_IDS, f"alias {alias!r} is a concept id")
                self.assertNotIn(key.replace(" ", "_"), v.CONCEPT_IDS, f"alias {alias!r} is a concept id")
                self.assertNotIn(key, seen, f"alias {alias!r} is on both {seen.get(key)} and {concept['id']}")
                seen[key] = concept["id"]

    def test_bright_is_an_alias_of_light_not_a_second_concept(self):
        self.assertNotIn("bright", v.CONCEPT_IDS)
        light = next(c for c in v.CONCEPTS if c["id"] == "light")
        # Nate, 2026-10-16: light, bright and any derivation are one observational concept with different aliases.
        self.assertTrue({"bright", "brighter", "brightest", "brighten", "brightness", "lighten", "lighting"} <= set(light["aliases"]))
        for alias in light["aliases"]:
            self.assertEqual(v.normalize(alias), alias, f"{alias!r} should be stored normalized")

    def test_new_needs_exist_and_are_connected_to_their_neighbours(self):
        for needed in ("light", "nap", "wake_up", "bedtime", "dark"):
            self.assertIn(needed, v.CONCEPT_IDS)
        rel = {c["id"]: {(r["id"], r["relation"]) for r in c["relates_to"]} for c in v.CONCEPTS}
        self.assertIn(("light", "same_remedy_as"), rel["dark"])
        self.assertIn(("dark", "same_remedy_as"), rel["light"])
        self.assertIn(("wake_up", "inverse_of"), rel["bedtime"])
        self.assertIn(("bedtime", "inverse_of"), rel["wake_up"])
        self.assertIn(("bedtime", "contrasts_with"), rel["nap"])

    def test_relations_point_at_real_concepts_and_inverses_are_symmetric(self):
        rel = {c["id"]: c["relates_to"] for c in v.CONCEPTS}
        for cid, relations in rel.items():
            for r in relations:
                self.assertIn(r["id"], v.CONCEPT_IDS, f"{cid} relates to unknown {r['id']}")
                if r["relation"] in {"inverse_of", "same_remedy_as", "contrasts_with"}:
                    self.assertIn((cid, r["relation"]), {(x["id"], x["relation"]) for x in rel[r["id"]]},
                                  f"{cid} {r['relation']} {r['id']} is not mirrored")

    def test_every_concept_has_a_definition_and_an_example_and_asks_are_well_formed(self):
        for c in v.CONCEPTS:
            self.assertTrue(c["definition"].strip() and c["examples"], c["id"])
            for ask in c["asks"]:
                self.assertEqual(set(ask), {"text", "intent", "device_words", "state"}, c["id"])

    def test_every_concept_has_gold_and_training_templates_and_they_never_share_text(self):
        gold = lines("gold.jsonl")
        templates = json.loads((ROOT / "synthesis-templates.json").read_text(encoding="utf-8"))
        gold_needs = {g["target"]["slots"]["concept"] for g in gold if g["target"]["intent"] == "express_need"}
        template_needs = {t["target"]["slots"]["concept"] for t in templates if t["target"]["intent"] == "express_need"}
        self.assertEqual(gold_needs, set(v.CONCEPT_IDS))
        self.assertEqual(template_needs, set(v.CONCEPT_IDS))
        held_out = {v.normalize(g["text"]) for g in gold}
        for template in templates:
            for utterance in template["utterances"]:
                self.assertNotIn(v.normalize(utterance), held_out, f"{template['id']} reuses a gold text: {utterance!r}")

    def test_wake_me_at_a_time_is_a_schedule_not_a_need(self):
        gold = {g["text"]: g["target"] for g in lines("gold.jsonl")}
        self.assertEqual(gold["Wake me at 7 tomorrow"]["intent"], "clarify")
        self.assertEqual(gold["Time to wake up"]["slots"], {"concept": "wake_up"})
        self.assertEqual(gold["I'm going to take a nap"]["slots"], {"concept": "nap"})
        self.assertEqual(gold["More light, please"]["slots"], {"concept": "light"})
        self.assertEqual(gold["It needs to be brighter in here"]["slots"], {"concept": "light"})

    def test_an_explicit_command_stays_a_command_with_no_concept(self):
        target = next(g["target"] for g in lines("gold.jsonl") if g["text"] == "Turn on the hall lights, it is dark in here")
        self.assertEqual(target["intent"], "set_device_state")
        self.assertNotIn("concept", target["slots"])


class AreaVocabulary(unittest.TestCase):
    def test_ids_and_aliases_are_unique_and_disjoint(self):
        everything = v.AREA_IDS + list(v.AREA_ALIASES)
        self.assertEqual(len(everything), len(set(everything)))
        for text in everything:
            self.assertEqual(text, v.normalize(text), f"{text!r} is not stored in normalized form")
            self.assertIsNone(v.check_location(text)[1], text)
        for site in v.SITES:
            self.assertNotIn(site, everything)

    def test_existing_gold_and_templates_already_use_canonical_rooms_and_sites(self):
        rows = lines("gold.jsonl") + json.loads((ROOT / "synthesis-templates.json").read_text(encoding="utf-8"))
        for row in rows:
            self.assertEqual(v.field_errors(row["target"]), {}, row.get("id"))
            self.assertEqual(v.normalize_target(row["target"])[1], [], f"{row.get('id')} has a non-canonical room or site")

    def test_spelling_variants_collapse_to_one_canonical_room(self):
        for typed in ("mech room", "Mech Room", "  MECH   ROOM ", "mech-room", "mech_room", "the mech room", "Mechanical Room", "mechanical-room."):
            self.assertEqual(v.check_location(typed), ("mech room", None), typed)
        self.assertEqual(v.check_location("Hallway"), ("hall", None))
        self.assertEqual(v.check_location("the Hall"), ("hall", None))
        self.assertEqual(v.check_location("study"), ("study", None))
        self.assertEqual(v.check_location("home office"), ("office", None))

    def test_bad_rooms_are_refused_with_a_reason(self):
        cases = {"": "empty", "   ": "empty", "x" * 41: "longer than", "hall!": "may only use", "12": "may only use",
                 "kitchenette": "Unknown room", "cabin": "site", "home": "site", "garage; DROP": "may only use"}
        for typed, fragment in cases.items():
            canonical, error = v.check_location(typed)
            self.assertIsNone(canonical, typed)
            self.assertIn(fragment, error, typed)
        for not_text in (None, 5, ["hall"], {"room": "hall"}):
            self.assertIsNotNone(v.check_location(not_text)[1])

    def test_a_near_miss_suggests_the_room(self):
        self.assertIn("Did you mean 'kitchen'?", v.check_location("kitchn")[1])
        self.assertIn("Did you mean 'mech room'?", v.check_location("mech rom")[1])

    def test_sites(self):
        self.assertEqual(v.check_site("Cabin"), ("cabin", None))
        self.assertEqual(v.check_site(" HOME "), ("home", None))
        self.assertIn("Unknown site", v.check_site("elsewhere")[1])
        self.assertIsNotNone(v.check_site(3)[1])


class TargetChecks(unittest.TestCase):
    def need(self, **slots):
        return {"intent": "express_need", "slots": slots, "requires_confirmation": True, "clarification": None, "automation": None}

    def test_normalize_rewrites_only_what_is_valid_and_reports_it(self):
        target = {"intent": "set_device_state", "slots": {"device_name": "lights", "location": "Hallway", "site": "CABIN", "state": "on"},
                  "requires_confirmation": True, "clarification": None, "automation": None}
        fixed, changes = v.normalize_target(target)
        self.assertEqual(fixed["slots"]["location"], "hall")
        self.assertEqual(fixed["slots"]["site"], "cabin")
        self.assertEqual({c["path"] for c in changes}, {"slots.location", "slots.site"})
        self.assertEqual(target["slots"]["location"], "Hallway", "the input is never mutated")
        target["slots"]["location"] = "kitchenette"
        fixed, changes = v.normalize_target(target)
        self.assertEqual(fixed["slots"]["location"], "kitchenette", "an invalid room is left for the reviewer, never guessed")

    def test_field_errors_cover_slots_and_automation_targets(self):
        target = {"intent": "propose_automation", "slots": {}, "requires_confirmation": True, "clarification": None,
                  "automation": {"trigger": {"kind": "state_changes_to", "target": {"device_name": "presence", "location": "narnia", "site": "cabin"}},
                                 "conditions": [],
                                 "actions": [{"target": {"device_name": "heater", "location": "mech room", "site": "mars"}}]}}
        errors = v.field_errors(target)
        self.assertEqual(set(errors), {"automation.trigger.target.location", "automation.actions[0].target.site"})

    def test_a_need_is_only_allowed_on_a_need_request(self):
        self.assertEqual(v.field_errors(self.need(concept="light")), {})
        self.assertIn("slots.concept", v.field_errors(self.need(concept="bright")))
        self.assertIn("Unknown need", v.field_errors(self.need(concept="bright"))["slots.concept"])
        command = {"intent": "set_device_state", "slots": {"device_name": "lights", "state": "on", "concept": "dark"}}
        self.assertIn("only to a need request", v.field_errors(command)["slots.concept"])

    def test_a_direct_command_reports_the_needs_it_would_satisfy(self):
        command = lambda name, state: {"intent": "set_device_state", "slots": {"device_name": name, "state": state}}
        self.assertEqual(v.supported_needs(command("lights", "on")), ["dark", "light"])
        self.assertEqual(v.supported_needs(command("hall lamp", "On")), ["dark", "light"])
        self.assertEqual(v.supported_needs(command("fan", "on")), ["hot", "stuffy"])
        self.assertEqual(v.supported_needs(command("heater", "on")), ["cold"])
        self.assertEqual(v.supported_needs(command("lights", "off")), [])
        self.assertEqual(v.supported_needs(self.need(concept="light")), [])
        self.assertEqual(v.supported_needs({"intent": "get_device_state", "slots": {"device_name": "lights"}}), [])

    def test_public_vocabulary_is_what_the_screen_needs(self):
        public = v.public_vocabulary()
        self.assertEqual([c["id"] for c in public["concepts"]], v.CONCEPT_IDS)
        self.assertIn("mech room", [a["id"] for a in public["areas"]])
        self.assertEqual(public["sites"], ["cabin", "home"])


if __name__ == "__main__":
    unittest.main()
