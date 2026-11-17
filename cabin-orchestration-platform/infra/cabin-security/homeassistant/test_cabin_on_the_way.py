"""Parity and safety tests for cabin_on_the_way.yaml (the "On the Way" preheat package).

Run from this directory (needs PyYAML):
    python -m unittest test_cabin_on_the_way -v

These tests tie the package to the three other places that name the same
entities: the dashboard button (App.jsx), the backend allowlist
(HaServiceController.java) and the heater knowledge entry (heater-control.md).
"""

import pathlib
import re
import unittest

import yaml

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[3]
BOOLEAN = "input_boolean.on_the_way_to_cabin"
HEATER = "switch.heater_mech_room"
SENSOR = "sensor.temp_mech_room_temperature"
YAML_TEXT = (HERE / "cabin_on_the_way.yaml").read_text(encoding="utf-8")
PKG = yaml.safe_load(YAML_TEXT)
AUTO = PKG["automation"][0]


def read(rel):
    return (ROOT / rel).read_text(encoding="utf-8")


class OnTheWayPackage(unittest.TestCase):
    def test_helper_matches_dashboard_button(self):
        app = read("cabin-orchestration-platform/ui/src/App.jsx")
        self.assertIn('"' + BOOLEAN + '"', app, "App.jsx must call the same helper this package defines")
        self.assertEqual("input_boolean." + next(iter(PKG["input_boolean"])), BOOLEAN)

    def test_backend_allows_exactly_the_call_the_button_makes(self):
        java = read("cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/api/HaServiceController.java")
        self.assertRegex(java, r'ALLOWED_DOMAINS\s*=\s*Set\.of\([^)]*"input_boolean"')
        self.assertRegex(java, r'ALLOWED_SERVICES\s*=\s*Set\.of\([^)]*"turn_on"')

    def test_entities_match_knowledge_entry(self):
        doc = read("docs/ai-assistant/knowledge/heater-control.md")
        for entity in (BOOLEAN, HEATER, SENSOR):
            self.assertIn(entity, doc)
        self.assertIn("38", doc)

    def test_triggers_on_the_helper_turning_on(self):
        triggers = AUTO["triggers"]
        self.assertEqual(len(triggers), 1)
        self.assertEqual(triggers[0]["trigger"], "state")
        self.assertEqual(triggers[0]["entity_id"], BOOLEAN)
        self.assertEqual(triggers[0]["to"], "on")

    def test_heater_starts_only_when_temp_known_above_38_and_heater_off(self):
        choose = AUTO["actions"][0]["choose"]
        self.assertEqual(len(choose), 1)
        conds = choose[0]["conditions"]
        numeric = [c for c in conds if c["condition"] == "numeric_state"]
        state = [c for c in conds if c["condition"] == "state"]
        self.assertEqual(numeric[0]["entity_id"], SENSOR)
        self.assertEqual(numeric[0]["above"], 38)
        self.assertNotIn("below", numeric[0])
        self.assertEqual((state[0]["entity_id"], state[0]["state"]), (HEATER, "off"))
        turn_ons = [s for s in choose[0]["sequence"] if s["action"] == "switch.turn_on"]
        self.assertEqual(len(turn_ons), 1)
        self.assertEqual(turn_ons[0]["target"]["entity_id"], HEATER)

    def test_heater_is_never_started_outside_the_guarded_branch(self):
        self.assertEqual(len(re.findall(r"switch\.turn_on", YAML_TEXT)), 1)
        self.assertEqual(len(re.findall(r"switch\.turn_off", YAML_TEXT)), 0, "package must not turn the heater off")
        default_actions = [a["action"] for a in AUTO["actions"][0]["default"]]
        self.assertEqual(default_actions, ["mqtt.publish"])

    def test_every_press_publishes_one_info_event_and_resets_the_helper(self):
        step = AUTO["actions"][0]
        started = [s for s in step["choose"][0]["sequence"] if s["action"] == "mqtt.publish"]
        skipped = step["default"]
        for branch in (started, skipped):
            self.assertEqual(len(branch), 1)
            self.assertEqual(branch[0]["data"]["topic"], "cabin/event/INFO")
        self.assertIn("HEATER_PREHEAT_STARTED", started[0]["data"]["payload"])
        self.assertIn("HEATER_PREHEAT_SKIPPED", skipped[0]["data"]["payload"])
        reset = AUTO["actions"][-1]
        self.assertEqual(reset["action"], "input_boolean.turn_off")
        self.assertEqual(reset["target"]["entity_id"], BOOLEAN)

    def test_mode_is_single(self):
        self.assertEqual(AUTO["mode"], "single")


if __name__ == "__main__":
    unittest.main()
