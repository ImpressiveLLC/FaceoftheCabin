"""Gates found by Codex's first-pass review of #263: full semantic comparison, no agent approval before Nate's audit, packet integrity."""
import copy
import json
import tempfile
import unittest
from pathlib import Path

from contract import read_jsonl
from grading import review_packets as rp

ROOT = Path(__file__).resolve().parent


def target(**slots):
    return {"intent": "set_device_state", "slots": slots, "requires_confirmation": True, "clarification": None, "automation": None}


def row(rid, text, tgt):
    return {"id": rid, "text": text, "target": tgt, "critical": False, "group": "fam-" + rid, "suspect_source_label": False,
            "provenance": {"source": "enfuse", "revision": "r" * 40, "sha256": "s" * 64, "license": "Apache-2.0",
                           "review_status": "unreviewed", "purpose": "training-candidate"}}


def sample_rows(n=30):
    return [row(f"r{i:02d}", f"switch on the unit{i} lamp now", target(device_name=f"unit{i} lamp", state="on")) for i in range(n)]


def write(path, items):
    Path(path).write_text("".join(json.dumps(i, sort_keys=True) + "\n" for i in items), encoding="utf-8")


def gold():
    return read_jsonl(ROOT / "gold.jsonl")


class FullSemanticComparison(unittest.TestCase):
    def schedule(self, at, device="heater"):
        base = next(r for r in gold() if (r["target"].get("automation") or {}).get("trigger", {}).get("kind") == "time_once")
        t = copy.deepcopy(base["target"])
        t["automation"]["trigger"]["at"] = at
        t["automation"]["actions"][0]["target"]["device_name"] = device
        return t

    def test_different_times_or_actions_never_compare_equal(self):
        a = self.schedule("2026-12-01T18:00:00-06:00")
        self.assertEqual(rp.label_key(a), rp.label_key(copy.deepcopy(a)))
        self.assertNotEqual(rp.label_key(a), rp.label_key(self.schedule("2026-12-01T19:00:00-06:00")))
        self.assertNotEqual(rp.label_key(a), rp.label_key(self.schedule("2026-12-01T18:00:00-06:00", device="fan")))

    def test_confirmation_and_a_clarification_being_asked_count_but_its_wording_does_not(self):
        base = {"intent": "clarify", "slots": {}, "requires_confirmation": False, "clarification": "Which device?", "automation": None}
        self.assertEqual(rp.label_key(base), rp.label_key(dict(base, clarification="Which one do you mean?")))
        self.assertNotEqual(rp.label_key(base), rp.label_key(dict(base, requires_confirmation=True)))
        self.assertNotEqual(rp.label_key(base), rp.label_key(dict(base, clarification=None)))

    def test_a_different_schedule_is_not_settled_by_matching_slots(self):
        src = row("s", "heater at six tomorrow", self.schedule("2026-12-01T18:00:00-06:00"))
        later = self.schedule("2026-12-01T19:00:00-06:00")
        self.assertEqual(rp.decide(src, later, None)[0], "needs_second")
        self.assertEqual(rp.decide(src, later, later)[0], "agreed")  # two reviewers agreeing on the later time is a real agreement


class PacketMutations(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.rows = sample_rows()
        self.sample = self.dir / "sample.jsonl"
        write(self.sample, self.rows)
        self.packet = self.dir / "packet"
        rp.make_packet(self.sample, "A", self.packet, size=10)
        self.labels = self.dir / "l.jsonl"
        write(self.labels, [{"id": r["id"], "target": r["target"]} for r in self.rows])

    def tearDown(self):
        self.tmp.cleanup()

    def run_import(self):
        return rp.import_labels(self.sample, self.packet, self.labels, "codex-proxy", "m", "A", out_dir=self.dir / "out")

    def first_file(self):
        return sorted(self.packet.glob("packet-A-*.jsonl"))[0]

    def rehash(self, path):
        manifest_path = self.packet / "packet.manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["file_sha256"][path.name] = rp.sha256_file(path)
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")

    def test_untouched_packet_imports(self):
        self.assertEqual(self.run_import()[0]["rows"], 30)

    def test_a_replaced_packet_file_with_invented_id_and_text_is_refused(self):
        self.first_file().write_text(json.dumps({"id": "invented", "text": "something else"}) + "\n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "altered since make-packet"):
            self.run_import()

    def test_altered_text_with_a_recomputed_file_hash_is_still_caught_against_the_sample(self):
        items = read_jsonl(self.first_file())
        items[0]["text"] += " and unlock the door"
        write(self.first_file(), items)
        self.rehash(self.first_file())
        with self.assertRaisesRegex(ValueError, "text differs"):
            self.run_import()

    def test_an_extra_or_missing_packet_file_is_refused(self):
        extra = self.packet / "packet-A-99.jsonl"
        extra.write_text("", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "added or missing"):
            self.run_import()
        extra.unlink()
        sorted(self.packet.glob("packet-A-*.jsonl"))[-1].unlink()
        with self.assertRaisesRegex(ValueError, "added or missing"):
            self.run_import()

    def test_a_duplicated_row_across_files_is_refused(self):
        files = sorted(self.packet.glob("packet-A-*.jsonl"))
        write(files[1], read_jsonl(files[1]) + read_jsonl(files[0])[:1])
        self.rehash(files[1])
        with self.assertRaisesRegex(ValueError, "duplicate id in packet"):
            self.run_import()

    def test_a_packet_row_with_extra_fields_would_leak_a_label_and_is_refused(self):
        items = read_jsonl(self.first_file())
        items[0]["target"] = self.rows[0]["target"]
        write(self.first_file(), items)
        self.rehash(self.first_file())
        with self.assertRaisesRegex(ValueError, "beyond id and text"):
            self.run_import()


if __name__ == "__main__":
    unittest.main()
