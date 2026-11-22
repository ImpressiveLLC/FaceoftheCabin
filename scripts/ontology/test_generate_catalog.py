"""Tests for scripts/ontology/generate_catalog.py (S2). Pure Python; the Java parity test is a separate class.

Run from the repository root:  python -m unittest discover -s scripts/ontology -p 'test_*.py'
"""
import copy
import io
import json
import re
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))
import generate_catalog as gc  # noqa: E402

JAVA = gc.DEVICE_TYPE_JAVA.read_bytes().decode("utf-8").replace("\r\n", "\n")
ONTOLOGY = gc.ONTOLOGY.read_bytes()
SEED = gc.SEED.read_bytes()


def build(java=JAVA, ontology=ONTOLOGY, seed=SEED):
    return gc.build(java, ontology, seed)


def seed_with(change):
    data = yaml.safe_load(SEED)
    change(data)
    return yaml.safe_dump(data, sort_keys=True).encode("utf-8")


def ontology_with(change):
    data = yaml.safe_load(ONTOLOGY)
    change(data)
    return yaml.safe_dump(data, sort_keys=True).encode("utf-8")


def entry(catalog, type_name):
    return next(e for e in catalog["entries"] if e["id"] == type_name)


class CommittedCatalog(unittest.TestCase):
    def test_the_committed_catalog_is_exactly_what_the_generator_produces(self):
        self.assertEqual(gc.CATALOG.read_bytes().decode("utf-8"), gc.render(build()))

    def test_check_mode_passes_on_the_committed_file(self):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            self.assertEqual(gc.main(["--check"]), 0, err.getvalue())

    def test_generation_is_deterministic(self):
        self.assertEqual(gc.render(build()), gc.render(build()))

    def test_catalog_validates_against_its_schema(self):
        gc.validate_schema(build())

    def test_a_malformed_catalog_fails_the_schema(self):
        bad = build()
        bad["entries"][0]["stamp"]["seed_hash"] = "not-a-hash"
        with self.assertRaises(gc.CatalogError):
            gc.validate_schema(bad)
        bad = build()
        bad["entries"][0]["category"] = "NOT_A_CATEGORY"
        with self.assertRaises(gc.CatalogError):
            gc.validate_schema(bad)


class Gate(unittest.TestCase):
    def run_check(self, text):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "device-catalog.json"
            path.write_text(text, encoding="utf-8", newline="\n")
            original = gc.CATALOG
            gc.CATALOG = path
            out, err = io.StringIO(), io.StringIO()
            try:
                with redirect_stdout(out), redirect_stderr(err):
                    code = gc.main(["--check"])
            finally:
                gc.CATALOG = original
        return code, err.getvalue()

    def test_a_hand_edit_of_the_catalog_fails_with_the_fix_command(self):
        edited = gc.render(build()).replace('"category": "SAFETY"', '"category": "CLIMATE"', 1)
        code, err = self.run_check(edited)
        self.assertEqual(code, 1)
        self.assertIn(gc.FIX_COMMAND, err)

    def test_a_missing_catalog_fails(self):
        code, _ = self.run_check("")
        self.assertEqual(code, 1)

    def test_a_changed_input_makes_the_committed_catalog_stale(self):
        changed = build(ontology=ontology_with(lambda d: d["elements"][0].update({"notes": "changed"})))
        self.assertNotEqual(changed["generated_from"]["ontology_sha256"], build()["generated_from"]["ontology_sha256"])
        self.assertNotEqual(gc.render(changed), gc.CATALOG.read_bytes().decode("utf-8"))


class Stamps(unittest.TestCase):
    def test_an_ontology_change_stales_only_the_entries_that_read_that_fragment(self):
        base = build()

        def add_trigger(data):
            camera_trigger = next(e for e in data["elements"] if e.get("applies_to_device_type") == ["CAMERA"])
            camera_trigger["notes"] = "edited"

        changed = build(ontology=ontology_with(add_trigger))
        for e in base["entries"]:
            after = entry(changed, e["id"])
            if e["id"] == "CAMERA":
                self.assertNotEqual(e["stamp"]["ontology_content_hash"], after["stamp"]["ontology_content_hash"])
            else:
                self.assertEqual(e["stamp"], after["stamp"], e["id"])

    def test_the_version_label_does_not_decide_staleness(self):
        base = build()
        relabelled = build(ontology=ontology_with(lambda d: d.update({"version": "9.9.9"})))
        self.assertNotEqual(base["generated_from"]["ontology_sha256"], relabelled["generated_from"]["ontology_sha256"])
        self.assertEqual(relabelled["generated_from"]["ontology_version_label"], "9.9.9")
        self.assertEqual([e["stamp"]["ontology_content_hash"] for e in base["entries"]],
                         [e["stamp"]["ontology_content_hash"] for e in relabelled["entries"]])

    def test_a_seed_change_stales_the_seed_stamp_of_that_type_only(self):
        base = build()
        changed = build(seed=seed_with(lambda d: d["checkin_defaults"]["non_zigbee_by_type"].update({"CAMERA": 7})))
        self.assertNotEqual(entry(base, "CAMERA")["stamp"]["seed_hash"], entry(changed, "CAMERA")["stamp"]["seed_hash"])
        self.assertEqual(entry(base, "LOCK")["stamp"]["seed_hash"], entry(changed, "LOCK")["stamp"]["seed_hash"])


class InputConsistency(unittest.TestCase):
    def test_an_ontology_type_with_no_enum_constant_and_no_allowlist_entry_fails(self):
        def point_at_unknown(data):
            next(e for e in data["elements"] if e.get("applies_to_device_type") == ["CAMERA"])["applies_to_device_type"] = ["NOT_A_TYPE"]
        with self.assertRaises(gc.CatalogError) as ctx:
            build(ontology=ontology_with(point_at_unknown))
        self.assertIn("NOT_A_TYPE", str(ctx.exception))

    def test_an_ontology_only_entry_that_became_an_enum_constant_fails(self):
        java = JAVA.replace("    DASHBOARD(DeviceCategory.PLATFORM);", "    DASHBOARD(DeviceCategory.PLATFORM),\n    RF_TRIPWIRE(DeviceCategory.SECURITY);")
        with self.assertRaises(gc.CatalogError) as ctx:
            build(java=java)
        self.assertIn("RF_TRIPWIRE", str(ctx.exception))

    def test_a_stale_ontology_only_entry_fails(self):
        with self.assertRaises(gc.CatalogError):
            build(ontology=ontology_with(lambda d: [e.update({"applies_to_device_type": []}) for e in d["elements"]
                                                    if e.get("applies_to_device_type") == ["RF_TRIPWIRE"]]))

    def test_a_rule_that_returns_an_unknown_type_or_repeats_an_id_fails(self):
        with self.assertRaises(gc.CatalogError):
            build(seed=seed_with(lambda d: d["match_rules"][0].update({"returns": "NOPE"})))
        with self.assertRaises(gc.CatalogError):
            build(seed=seed_with(lambda d: d["match_rules"][1].update({"id": d["match_rules"][0]["id"]})))

    def test_an_ontology_only_entry_needs_a_reason(self):
        with self.assertRaises(gc.CatalogError):
            build(seed=seed_with(lambda d: d["ontology_only"]["RF_TRIPWIRE"].update({"reason": ""})))

    def test_the_real_source_parses_to_the_expected_shape(self):
        constants, fields, _ = gc.parse_device_types(JAVA)
        self.assertEqual(len(constants), 24)
        self.assertEqual(constants["WATER_VALVE"], "SAFETY")
        self.assertEqual(fields["CO2_SENSOR"], ["co2"])
        self.assertEqual(fields["POWER_METER"], ["current", "energy", "power", "voltage"])
        self.assertEqual(fields["TEMPERATURE_SENSOR"], ["humidity", "temperature"])
        self.assertEqual(fields["LOCK"], [])

    def test_every_type_gets_exactly_one_entry_and_protocols_come_from_the_rules(self):
        catalog = build()
        ids = [e["id"] for e in catalog["entries"]]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertEqual(entry(catalog, "WATER_PRESSURE_SENSOR")["protocols"], ["mqtt_bridge"])
        self.assertEqual(entry(catalog, "DISHWASHER")["protocols"], [])
        self.assertEqual(entry(catalog, "TEMPERATURE_SENSOR")["protocols"], ["home_assistant", "mqtt_bridge", "zigbee2mqtt"])
        self.assertEqual(entry(catalog, "CAMERA")["checkin_minutes"], {"non_zigbee": 5, "zigbee_battery": 1560, "zigbee_mains": 10})
        self.assertEqual(entry(catalog, "ROUTER")["checkin_minutes"]["non_zigbee"], 30)
        self.assertIsNone(entry(catalog, "RF_TRIPWIRE")["category"])


class SafetyGuard(unittest.TestCase):
    def setUp(self):
        self.old = build()

    def test_removing_a_safety_entry_is_refused_unless_allowed_with_a_reason(self):
        new = copy.deepcopy(self.old)
        new["entries"] = [e for e in new["entries"] if e["id"] != "WATER_VALVE"]
        self.assertIn("WATER_VALVE: entry removed or renamed", gc.safety_violations(self.old, new))
        new["allowed_changes"] = [{"type": "WATER_VALVE", "change": "removed", "reason": "synthetic test"}]
        self.assertEqual(gc.safety_violations(self.old, new), [])
        new["allowed_changes"] = [{"type": "WATER_VALVE", "change": "removed", "reason": ""}]
        self.assertIn("WATER_VALVE: entry removed or renamed", gc.safety_violations(self.old, new))

    def test_deleting_the_enum_constant_a_rule_returns_stops_the_generator_first(self):
        java = JAVA.replace("    WATER_VALVE(DeviceCategory.SAFETY),\n", "")
        with self.assertRaises(gc.CatalogError) as ctx:
            build(java=java)
        self.assertIn("WATER_VALVE", str(ctx.exception))

    def test_a_category_downgrade_of_a_safety_type_is_refused(self):
        java = JAVA.replace("    CO_ALARM(DeviceCategory.SAFETY),", "    CO_ALARM(DeviceCategory.CLIMATE),")
        problems = gc.safety_violations(self.old, build(java=java))
        self.assertIn("CO_ALARM: category SAFETY -> CLIMATE", problems)

    def test_a_checkin_change_of_a_safety_type_is_refused(self):
        new = build(seed=seed_with(lambda d: d["checkin_defaults"]["non_zigbee_by_type"].update({"LOCK": 60})))
        self.assertIn("LOCK: check-in window changed", gc.safety_violations(self.old, new))

    def test_a_reason_is_required_for_an_allowed_change(self):
        new = build(seed=seed_with(lambda d: (d["checkin_defaults"]["non_zigbee_by_type"].update({"LOCK": 60}),
                                              d.update({"allowed_changes": [{"type": "LOCK", "change": "checkin", "reason": ""}]}))))
        self.assertIn("LOCK: check-in window changed", gc.safety_violations(self.old, new))

    def test_changes_to_a_non_safety_type_are_not_blocked(self):
        new = build(seed=seed_with(lambda d: d["checkin_defaults"]["non_zigbee_by_type"].update({"CAMERA": 9})))
        self.assertEqual(gc.safety_violations(self.old, new), [])

    def test_the_baseline_option_applies_the_guard_in_main(self):
        with tempfile.TemporaryDirectory() as tmp:
            baseline = Path(tmp) / "old.json"
            old = copy.deepcopy(self.old)
            old["entries"] = [e for e in old["entries"]]
            next(e for e in old["entries"] if e["id"] == "LOCK")["category"] = "PLATFORM"  # the baseline differs
            baseline.write_text(json.dumps(old), encoding="utf-8")
            out, err = io.StringIO(), io.StringIO()
            with redirect_stdout(out), redirect_stderr(err):
                code = gc.main(["--check", "--baseline", str(baseline)])
            self.assertEqual(code, 1)
            self.assertIn("safety guard", err.getvalue())


class SeedRules(unittest.TestCase):
    def test_every_inferType_site_is_present_with_ordered_unique_rule_ids(self):
        rules = yaml.safe_load(SEED)["match_rules"]
        by_site = {}
        for rule in rules:
            by_site.setdefault(rule["site"], []).append(rule["id"])
        self.assertEqual(sorted(by_site), ["home_assistant", "mqtt_bridge", "zigbee2mqtt"])
        self.assertEqual([len(v) for _, v in sorted(by_site.items())], [16, 6, 17])
        for site, ids in by_site.items():
            numbers = [int(re.match(r"^[a-z]{2}-([0-9]{2})-", i).group(1)) for i in ids]
            self.assertEqual(numbers, list(range(1, len(ids) + 1)), site)


if __name__ == "__main__":
    unittest.main()
