import json
import re
import unittest
from pathlib import Path

from grading import s1_sample as s1

HERE = Path(__file__).resolve().parent
COMMITTED = HERE / "sft" / "s1"


def row(rid, text, intent="set_device_state", source="enfuse", slots=None):
    return {"id": rid, "text": text, "target": {"intent": intent, "slots": slots if slots is not None else {"state": "on"}},
            "provenance": {"source": source}}


class Families(unittest.TestCase):
    def fam(self, *texts):
        rows = [row(f"r{i}", t) for i, t in enumerate(texts)]
        out = s1.families(rows)
        return [out[r["id"]] for r in rows]

    def test_reordered_and_polite_wording_is_one_family(self):
        a, b, c = self.fam("turn on the lights in the kitchen", "please turn the kitchen lights on", "Can you turn on kitchen lights?")
        self.assertEqual(a, b)
        self.assertEqual(b, c)

    def test_opposite_polarity_is_never_merged(self):
        a, b = self.fam("turn on the kitchen lights", "turn off the kitchen lights")
        self.assertNotEqual(a, b)

    def test_different_numbers_are_never_merged(self):
        a, b = self.fam("set the living room lights to 50 percent brightness", "set the living room lights to 60 percent brightness")
        self.assertNotEqual(a, b)

    def test_different_rooms_are_separate_families(self):
        a, b = self.fam("turn on the kitchen lights", "turn on the garage fan")
        self.assertNotEqual(a, b)

    def test_family_ids_do_not_depend_on_row_order(self):
        rows = [row("a", "turn on the kitchen lights"), row("b", "kitchen lights turn on"), row("c", "turn off the hall fan")]
        self.assertEqual(s1.families(rows), s1.families(list(reversed(rows))))


class Sampling(unittest.TestCase):
    def corpus(self):
        rooms = ["kitchen", "bedroom", "garage", "office", "hallway", "dining room"]
        rows = []
        for i in range(60):
            rows.append(row(f"enfuse-{i}", f"set the {rooms[i % 6]} lamp {i} to {i + 1} percent brightness", "set_device_attribute",
                            slots={"attribute_type": "brightness", "attribute_value": f"{i + 1} percent"}))
        for i in range(12):
            rows.append(row(f"enfuse-g{i}", f"is the {rooms[i % 6]} fan number {i} running", "get_device_state", slots={"device_name": "fan"}))
        rows.append(row("ha-1", "turn on the cellar light", source="ha"))
        rows.append(row("enfuse-leak", "turn on the cellar light please"))
        return rows

    def test_held_out_source_never_reaches_the_sample_and_its_families_are_dropped(self):
        manifest, sample, _ = s1.build(self.corpus(), target=40)
        self.assertEqual(manifest["held_out_rows"], 1)
        self.assertNotIn("ha-1", manifest["sample_ids"])
        self.assertNotIn("enfuse-leak", manifest["sample_ids"])
        self.assertEqual(manifest["pool_rows_dropped_for_sharing_a_held_out_family"], 1)
        self.assertEqual(manifest["families_spanning_held_out_and_pool"], 1)
        self.assertTrue(all(r["provenance"]["source"] != "ha" for r in sample))

    def test_one_row_per_family_and_no_family_twice(self):
        rows = self.corpus() + [row("enfuse-dup", "set the kitchen lamp 0 to 1 percent brightness please", "set_device_attribute",
                                    slots={"attribute_type": "brightness", "attribute_value": "1 percent"})]
        manifest, sample, family_map = s1.build(rows, target=80)
        owner = {rid: fam for fam, ids in family_map.items() for rid in ids}
        families = [owner[r["id"]] for r in sample]
        self.assertEqual(len(families), len(set(families)))

    def test_deterministic_for_a_seed_and_different_for_another(self):
        a = s1.build(self.corpus(), seed="x", target=30)[0]["sample_sha256"]
        self.assertEqual(a, s1.build(self.corpus(), seed="x", target=30)[0]["sample_sha256"])
        self.assertNotEqual(a, s1.build(self.corpus(), seed="y", target=30)[0]["sample_sha256"])

    def test_every_stratum_is_represented_when_the_target_allows(self):
        manifest, sample, _ = s1.build(self.corpus(), target=30)
        self.assertTrue(all(cell["sampled"] >= 1 for cell in manifest["strata"]))
        self.assertEqual(manifest["sample_count"], len(sample))
        self.assertEqual(sum(cell["sampled"] for cell in manifest["strata"]), len(sample))

    def test_target_larger_than_the_pool_returns_every_family_once(self):
        manifest, _, _ = s1.build(self.corpus(), target=10000)
        self.assertEqual(manifest["sample_count"], manifest["pool_family_count"])

    def test_rooms_spread_across_a_stratum(self):
        manifest, _, _ = s1.build(self.corpus(), target=24)
        counts = manifest["sample_by_room"]
        self.assertGreaterEqual(len(counts), 5)
        self.assertLessEqual(max(counts.values()) - min(counts.values()), 4)

    def test_label_flags_catch_a_truncated_or_missing_value(self):
        self.assertIn("slot_value_not_in_text", s1.label_flags(row("a", "set the garage thermostat to 72 degrees", slots={"location": "gar"})))
        self.assertIn("attribute_without_value", s1.label_flags(row("b", "dim the lights", "set_device_attribute", slots={"device_name": "lights"})))
        self.assertIn("no_slots", s1.label_flags(row("c", "set the hallway thermostat to 68", slots={})))
        self.assertEqual(s1.label_flags(row("d", "turn on the kitchen lights", slots={"location": "kitchen", "state": "on"})), [])

    def test_the_manifest_carries_no_candidate_text(self):
        manifest, _, family_map = s1.build(self.corpus(), target=30)
        blob = json.dumps(manifest) + json.dumps(family_map)
        self.assertNotIn("lamp", blob)
        self.assertNotIn("cellar", blob)


class CommittedRecord(unittest.TestCase):
    def setUp(self):
        self.manifest = json.loads((COMMITTED / "s1-manifest.json").read_text(encoding="utf-8"))
        self.families = json.loads((COMMITTED / "families.json").read_text(encoding="utf-8"))

    def test_counts_agree_with_each_other(self):
        m = self.manifest
        self.assertEqual(m["sample_count"], len(m["sample_ids"]))
        self.assertEqual(len(set(m["sample_ids"])), m["sample_count"])
        self.assertEqual(sum(m["sample_by_intent"].values()), m["sample_count"])
        self.assertEqual(sum(c["sampled"] for c in m["strata"]), m["sample_count"])
        self.assertEqual(sum(len(ids) for ids in self.families.values()), m["input_rows"])
        self.assertEqual(m["pool_rows"] + m["held_out_rows"] + m["pool_rows_dropped_for_sharing_a_held_out_family"], m["input_rows"])

    def test_hashes_recompute_from_the_committed_lists(self):
        m = self.manifest
        self.assertEqual(s1.sha256_of(m["sample_ids"]), m["sample_sha256"])
        self.assertEqual(s1.sha256_of(sorted(f"{f}:{','.join(ids)}" for f, ids in self.families.items())), m["families_sha256"])
        self.assertRegex(m["candidate_sha256"], r"^[0-9a-f]{64}$")

    def test_candidates_hash_matches_the_s0_manifest(self):
        s0 = json.loads((HERE / "candidates" / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(self.manifest["candidate_sha256"], s0["candidate_sha256"])
        self.assertEqual(self.manifest["input_rows"], s0["candidate_count"])

    def test_no_held_out_row_is_sampled_and_nothing_was_reviewed_or_trained(self):
        m = self.manifest
        self.assertTrue(all(not i.startswith("ha-") for i in m["sample_ids"]))
        self.assertEqual((m["reviewed"], m["training_run"]), (0, False))
        owner = {rid: fam for fam, ids in self.families.items() for rid in ids}
        held = {owner[i] for ids in self.families.values() for i in ids if i.startswith("ha-")}
        self.assertFalse(held & {owner[i] for i in m["sample_ids"]})

    def test_only_ids_counts_and_hashes_are_committed(self):
        text = (COMMITTED / "s1-manifest.json").read_text(encoding="utf-8") + (COMMITTED / "families.json").read_text(encoding="utf-8")
        self.assertLess(len(text), 120000)
        self.assertIsNone(re.search(r'"(text|original|utterance|sentences)"', text))
        self.assertEqual(sorted(p.name for p in COMMITTED.iterdir()), ["README.md", "families.json", "reserved-ohf.json", "s1-manifest.json"])


if __name__ == "__main__":
    unittest.main()
