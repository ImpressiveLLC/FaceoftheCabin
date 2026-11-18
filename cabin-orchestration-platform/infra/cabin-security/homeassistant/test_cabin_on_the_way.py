"""Parity and safety tests for cabin_on_the_way.yaml (the "On the Way" preheat package).

Run from this directory (needs PyYAML):
    python -m unittest test_cabin_on_the_way -v

These tests tie the package to the three other places that name the same
entities: the dashboard button (App.jsx), the backend allowlist
(HaServiceController.java) and the heater knowledge entry (heater-control.md).
They are structural: they read the YAML, they do not run Home Assistant.
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


def choose_step():
    return [a for a in AUTO["actions"] if "choose" in a][0]


def all_publishes(node, found=None):
    found = [] if found is None else found
    if isinstance(node, dict):
        if node.get("action") == "mqtt.publish":
            found.append(node)
        for v in node.values():
            all_publishes(v, found)
    elif isinstance(node, list):
        for v in node:
            all_publishes(v, found)
    return found


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
        choose = choose_step()["choose"]
        self.assertEqual(len(choose), 1)
        conds = choose[0]["conditions"]
        numeric = [c for c in conds if c["condition"] == "numeric_state"]
        state = [c for c in conds if c["condition"] == "state"]
        self.assertEqual(numeric[0]["entity_id"], SENSOR)
        self.assertEqual(numeric[0]["above"], 38)
        self.assertNotIn("below", numeric[0])
        self.assertEqual((state[0]["entity_id"], state[0]["state"]), (HEATER, "off"))
        turn_ons = [s for s in choose[0]["sequence"] if s.get("action") == "switch.turn_on"]
        self.assertEqual(len(turn_ons), 1)
        self.assertEqual(turn_ons[0]["target"]["entity_id"], HEATER)

    def test_heater_is_never_started_outside_the_guarded_branch(self):
        self.assertEqual(len(re.findall(r"switch\.turn_on", YAML_TEXT)), 1)
        self.assertEqual(len(re.findall(r"switch\.turn_off", YAML_TEXT)), 0, "package must not turn the heater off")
        default_actions = [a["action"] for a in choose_step()["default"]]
        self.assertEqual(default_actions, ["mqtt.publish"])

    def test_reset_is_first_and_last(self):
        first, last = AUTO["actions"][0], AUTO["actions"][-1]
        for step in (first, last):
            self.assertEqual(step["action"], "input_boolean.turn_off")
            self.assertEqual(step["target"]["entity_id"], BOOLEAN)
        resets = [a for a in AUTO["actions"] if a.get("action") == "input_boolean.turn_off"]
        self.assertEqual(len(resets), 2)

    def test_wait_guard_is_an_exact_bounded_state_wait(self):
        seq = choose_step()["choose"][0]["sequence"]
        wait = seq[1]
        self.assertEqual(wait["wait_template"], "{{ is_state('switch.heater_mech_room', 'on') }}")
        self.assertEqual(wait["timeout"], "00:00:15")
        self.assertIs(wait["continue_on_timeout"], True)
        self.assertEqual(len(re.findall("wait_template", YAML_TEXT)), 1)

    def test_every_step_between_the_resets_tolerates_its_own_failure(self):
        # The final reset is only always reached if no step before it can abort the run.
        def walk(node):
            if isinstance(node, dict):
                if "action" in node and node is not AUTO["actions"][0] and node is not AUTO["actions"][-1]:
                    self.assertIs(node.get("continue_on_error"), True, node["action"])
                if "wait_template" in node:
                    self.assertIs(node.get("continue_on_timeout"), True)
                    self.assertIn("timeout", node)
                for v in node.values():
                    walk(v)
            elif isinstance(node, list):
                for v in node:
                    walk(v)

        walk(AUTO["actions"])

    def test_repeated_tap_never_strands_the_helper_on(self):
        """Run the action list in a tiny model of HA's single mode: a second tap arrives while the run waits (the
        helper turns on, the trigger is ignored). Whatever fails, the helper must end off so a later tap can trigger."""
        class Abort(Exception):
            pass

        def run(env):
            state = {"helper": "on", "heater": env["heater"], "published": []}

            def conds_ok(conds):
                for c in conds:
                    if c["condition"] == "numeric_state":
                        t = env["temp"]
                        if t is None or not t > c["above"]:
                            return False
                    elif c["condition"] == "state":
                        if state["heater"] != c["state"]:
                            return False
                return True

            def exec_list(steps):
                for st in steps:
                    exec_step(st)

            def exec_step(st):
                if "choose" in st:
                    for branch in st["choose"]:
                        if conds_ok(branch["conditions"]):
                            return exec_list(branch["sequence"])
                    return exec_list(st.get("default", []))
                if "wait_template" in st:
                    state["helper"] = "on"  # the second tap, during the wait; its trigger is ignored in single mode
                    if env["heater_comes_on"]:
                        state["heater"] = "on"
                    elif "timeout" not in st or not st.get("continue_on_timeout"):
                        raise Abort()
                    return
                act = st["action"]
                if act == "input_boolean.turn_off":
                    state["helper"] = "off"
                elif act == "switch.turn_on":
                    if env["heater_call_fails"]:
                        if not st.get("continue_on_error"):
                            raise Abort()
                elif act == "mqtt.publish":
                    if env["mqtt_fails"]:
                        if not st.get("continue_on_error"):
                            raise Abort()
                    else:
                        state["published"].append(st["data"]["payload"])

            try:
                exec_list(AUTO["actions"])
            except Abort:
                pass
            return state

        for heater_call_fails in (False, True):
            for heater_comes_on in (False, True):
                for mqtt_fails in (False, True):
                    for temp, heater in ((70, "off"), (30, "off"), (None, "off"), (70, "on")):
                        env = dict(temp=temp, heater=heater, heater_call_fails=heater_call_fails,
                                   heater_comes_on=heater_comes_on, mqtt_fails=mqtt_fails)
                        end = run(env)
                        self.assertEqual(end["helper"], "off", env)
                        if not mqtt_fails:
                            joined = " ".join(end["published"])
                            if "HEATER_PREHEAT_STARTED" in joined:
                                self.assertTrue(heater_comes_on, "STARTED published although the heater never reported on")

    def test_failed_heater_call_does_not_abort_and_is_not_reported_as_started(self):
        seq = choose_step()["choose"][0]["sequence"]
        self.assertEqual(seq[0]["action"], "switch.turn_on")
        self.assertTrue(seq[0]["continue_on_error"], "a failed heater call must not stop the run")
        self.assertIn(HEATER, seq[1]["wait_template"])
        self.assertTrue(seq[1]["continue_on_timeout"])
        inner = seq[2]
        cond = inner["choose"][0]["conditions"][0]
        self.assertEqual((cond["condition"], cond["entity_id"], cond["state"]), ("state", HEATER, "on"))
        self.assertIn("HEATER_PREHEAT_STARTED", inner["choose"][0]["sequence"][0]["data"]["payload"])
        self.assertEqual(len(re.findall("HEATER_PREHEAT_STARTED", YAML_TEXT)), 1)
        failed = inner["default"][0]["data"]["payload"]
        self.assertIn("HEATER_PREHEAT_FAILED", failed)
        self.assertNotIn("HEATER_PREHEAT_STARTED", failed)

    def test_every_press_records_one_info_event_and_publish_failures_do_not_abort(self):
        publishes = all_publishes(AUTO["actions"])
        self.assertEqual(len(publishes), 3)
        for pub in publishes:
            self.assertEqual(pub["data"]["topic"], "cabin/event/INFO")
            self.assertTrue(pub["continue_on_error"])
        events = " ".join(pub["data"]["payload"] for pub in publishes)
        for name in ("HEATER_PREHEAT_STARTED", "HEATER_PREHEAT_FAILED", "HEATER_PREHEAT_SKIPPED"):
            self.assertIn(name, events)

    def test_mode_is_single(self):
        self.assertEqual(AUTO["mode"], "single")


if __name__ == "__main__":
    unittest.main()
