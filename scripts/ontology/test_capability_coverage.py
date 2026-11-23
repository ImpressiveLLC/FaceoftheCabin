"""Tests for scripts/ontology/capability_coverage.py (W-169 C1). Synthetic fixtures only; no live host.

Run from the repository root:  python -m unittest discover -s scripts/ontology -p 'test_*.py'
"""
import copy
import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import capability_coverage as cc  # noqa: E402

HERE = Path(__file__).resolve().parent
DEVICES = json.loads((HERE / "fixtures" / "z2m-bridge-devices.synthetic.json").read_text(encoding="utf-8"))
REGISTRY = json.loads((HERE / "fixtures" / "hub-registry.synthetic.json").read_text(encoding="utf-8"))
CATALOG = json.loads(cc.CATALOG.read_text(encoding="utf-8"))
VOCAB = cc.measurement_vocabulary(json.loads(cc.VOCABULARY.read_text(encoding="utf-8")))


def report(devices=DEVICES, registry=REGISTRY):
    return cc.build_report(devices, registry, CATALOG, VOCAB, {"test": "synthetic"}, now="2026-11-23T00:00:00+00:00")


def statuses(rep, key):
    device = next(d for d in rep["devices"] if d["key"] == key)
    return {c["name"]: c["status"] for c in device["capabilities"]}


class CapabilityCoverageTests(unittest.TestCase):
    def test_vocabulary_is_the_closed_measurement_enum(self):
        self.assertIn("temperature", VOCAB)
        self.assertIn("water_leak", VOCAB)
        self.assertNotIn("illuminance", VOCAB)

    def test_temperature_sensor_statuses(self):
        self.assertEqual(statuses(report(), "0x00000000000000a1"), {
            "temperature": "shown", "humidity": "shown",
            "battery": "registered_not_shown",          # the hub reads it; TEMPERATURE_SENSOR does not report it
            "voltage": "offered_not_registered",        # in the vocabulary, not read by the hub
            "temperature_calibration": "setting_or_diagnostic",
            "linkquality": "setting_or_diagnostic"})

    def test_plug_switch_is_a_control_and_metering_is_shown(self):
        rep = report()
        self.assertEqual(statuses(rep, "0x00000000000000b2"), {
            "state": "control", "power": "shown", "energy": "shown", "current": "shown", "voltage": "shown",
            "power_on_behavior": "setting_or_diagnostic"})
        plug = next(d for d in rep["devices"] if d["key"] == "0x00000000000000b2")
        self.assertTrue(next(c for c in plug["capabilities"] if c["name"] == "state")["hub_can_command"])

    def test_motion_sensor_illuminance_is_not_mappable(self):
        self.assertEqual(statuses(report(), "0x00000000000000c3"), {
            "occupancy": "registered_not_shown",        # MOTION_SENSOR reports no fields in the catalog today
            "illuminance": "offered_not_mappable",      # no ontology row: a W-168 candidate
            "battery": "registered_not_shown"})

    def test_device_not_in_the_hub(self):
        rep = report()
        device = next(d for d in rep["devices"] if d["key"] == "0x00000000000000d4")
        self.assertFalse(device["in_hub"])
        self.assertIsNone(device["assigned_type"])
        self.assertEqual(statuses(rep, "0x00000000000000d4"),
                         {"pressure": "offered_not_registered", "soil_moisture": "offered_not_mappable"})
        self.assertEqual(rep["summary"]["not_in_hub"], 1)

    def test_coordinator_skipped_and_summary_counts(self):
        rep = report()
        self.assertEqual(rep["summary"]["devices"], 4)
        self.assertNotIn("0x0000000000000000", [d["key"] for d in rep["devices"]])
        by = rep["summary"]["by_status"]
        self.assertEqual(sum(by.values()), sum(len(d["capabilities"]) for d in rep["devices"]))
        self.assertEqual(by["shown"], 6)
        self.assertEqual(by["offered_not_mappable"], 2)
        self.assertEqual(rep["summary"]["devices_with_gaps"], 3)
        self.assertEqual(rep["catalog_hash"], CATALOG["catalog_hash"])

    def test_devices_ranked_by_gaps(self):
        gaps = [d["gaps"] for d in report()["devices"]]
        self.assertEqual(gaps, sorted(gaps, reverse=True))

    def test_deterministic_regardless_of_input_order(self):
        a = json.dumps(report(), sort_keys=True)
        b = json.dumps(report(list(reversed(DEVICES))), sort_keys=True)
        self.assertEqual(a, b)

    def test_diff_shows_new_devices_and_status_changes(self):
        before = report()
        registry = copy.deepcopy(REGISTRY)
        registry["devices"]["0x00000000000000a1"]["registered_fields"].append("voltage")
        devices = copy.deepcopy(DEVICES)
        devices.append({"ieee_address": "0x00000000000000e5", "type": "EndDevice",
                        "definition": {"exposes": [{"type": "numeric", "name": "co2", "access": 1}]}})
        diff = cc.diff_reports(before, report(devices, registry))
        self.assertEqual(diff["devices_added"], ["0x00000000000000e5"])
        self.assertEqual(diff["devices_removed"], [])
        self.assertEqual(diff["capability_changes"], [{"key": "0x00000000000000a1", "capability": "voltage",
                                                       "before": "offered_not_registered",
                                                       "after": "registered_not_shown"}])

    def test_bad_inputs_fail_without_a_report(self):
        with self.assertRaises(cc.CoverageError):
            report(devices={"not": "a list"})
        with self.assertRaises(cc.CoverageError):
            report(devices=[{"definition": {"exposes": []}}])
        with self.assertRaises(cc.CoverageError):
            cc.measurement_vocabulary({"no": "enum"})

    def test_cli_writes_once_and_never_overwrites(self):
        with tempfile.TemporaryDirectory() as tmp:
            out = Path(tmp) / "report.json"
            args = ["--z2m-devices", str(HERE / "fixtures" / "z2m-bridge-devices.synthetic.json"),
                    "--registry", str(HERE / "fixtures" / "hub-registry.synthetic.json"), "--out", str(out)]
            with redirect_stdout(io.StringIO()):
                self.assertEqual(cc.main(args), 0)
            written = json.loads(out.read_text(encoding="utf-8"))
            self.assertEqual(set(written["inputs"]), {"z2m_devices", "registry", "catalog", "vocabulary"})
            self.assertTrue(all(len(v) == 64 for v in written["inputs"].values()))
            with self.assertRaises(SystemExit):
                cc.main(args)
            with redirect_stdout(io.StringIO()):
                self.assertEqual(cc.main(args[:-1] + [str(Path(tmp) / "r2.json"), "--previous", str(out)]), 0)
            self.assertEqual(json.loads((Path(tmp) / "r2.json").read_text(encoding="utf-8"))["diff"]["capability_changes"], [])


if __name__ == "__main__":
    unittest.main()
