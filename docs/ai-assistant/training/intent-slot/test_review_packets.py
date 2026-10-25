import json
import tempfile
import unittest
from pathlib import Path

from grading import review_packets as rp


def target(intent="set_device_state", **slots):
    return {"intent": intent, "slots": slots, "requires_confirmation": intent != "get_device_state",
            "clarification": None, "automation": None}


def row(rid, text, tgt, suspect=False):
    return {"id": rid, "text": text, "target": tgt, "critical": False, "group": "fam-" + rid,
            "suspect_source_label": suspect,
            "provenance": {"source": "enfuse", "revision": "r" * 40, "sha256": "s" * 64, "license": "Apache-2.0",
                           "review_status": "unreviewed", "purpose": "training-candidate"}}


class Packets(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = Path(self.tmp.name)
        self.rows = [
            row("a", "turn on the porch light", target(device_name="porch light", state="on")),
            row("b", "turn off the den fan", target(device_name="den fan", state="off")),
            row("c", "set the lamp to warm", target(intent="set_device_attribute", device_name="lamp",
                                                     attribute_type="color", attribute_value="warm"), suspect=True),
            row("d", "turn on the hall light", target(device_name="hall light", state="on")),
        ]
        self.sample = self.dir / "sample.jsonl"
        self.write(self.sample, self.rows)
        self.packet = self.dir / "packet"
        rp.make_packet(self.sample, "A", self.packet)

    def tearDown(self):
        self.tmp.cleanup()

    @staticmethod
    def write(path, items):
        Path(path).write_text("".join(json.dumps(i) + "\n" for i in items), encoding="utf-8")

    def labels(self, name, overrides=None):
        out = {r["id"]: r["target"] for r in self.rows}
        out.update(overrides or {})
        path = self.dir / name
        self.write(path, [{"id": k, "target": v} for k, v in out.items()])
        return path

    def run_import(self, labels, track="A", **kw):
        return rp.import_labels(self.sample, self.packet, labels, "codex-proxy", "gpt-x", track, out_dir=self.dir / "out", **kw)

    def test_packet_is_blind_ids_and_text_only(self):
        for f in self.packet.glob("packet-A-*.jsonl"):
            for line in f.read_text(encoding="utf-8").splitlines():
                self.assertEqual(sorted(json.loads(line)), ["id", "text"])
        manifest = json.loads((self.packet / "packet.manifest.json").read_text(encoding="utf-8"))
        self.assertTrue(manifest["blind"])
        self.assertEqual(manifest["fields_given_to_reviewer"], ["id", "text"])

    def test_agreement_with_the_source_approves_but_a_suspect_row_never_does(self):
        report, approved, nate, _ = self.run_import(self.labels("l.jsonl"))
        self.assertEqual(sorted(r["id"] for r in approved), ["a", "b", "d"])
        self.assertEqual(report["needs_second_ids"], ["c"])
        self.assertEqual(report["suspect_rows_approved_by_source_agreement"], 0)

    def test_approved_rows_record_an_agent_not_a_person(self):
        _, approved, _, _ = self.run_import(self.labels("l.jsonl"))
        p = approved[0]["provenance"]
        self.assertEqual((p["reviewer_kind"], p["reviewer_model"], p["track"], p["agreement"], p["review_status"]),
                         ("agent", "gpt-x", "A", "agreed", "approved"))
        for key in ("reviewer", "reviewed_at", "source", "revision", "sha256", "license"):
            self.assertTrue(p[key], key)
        self.assertNotIn("suspect_source_label", approved[0])

    def test_disagreement_needs_a_second_reviewer_who_can_settle_it(self):
        first = self.labels("l1.jsonl", {"b": target(device_name="den fan", state="on")})
        report, _, _, _ = self.run_import(first)
        self.assertIn("b", report["needs_second_ids"])
        matches_source = self.labels("l2.jsonl")
        _, approved, _, _ = self.run_import(first, second_path=matches_source, second_reviewer="cowork-proxy", second_model="m")
        self.assertEqual(next(r for r in approved if r["id"] == "b")["target"], self.rows[1]["target"])
        three_way = self.labels("l3.jsonl", {"b": target(device_name="den fan", state="toggle")})
        _, _, nate, _ = self.run_import(first, second_path=three_way, second_reviewer="cowork-proxy", second_model="m")
        self.assertEqual([r["id"] for r in nate], ["b"])
        self.assertEqual(nate[0]["provenance"]["review_status"], "unreviewed")

    def test_suspect_row_needs_two_agents_who_match_each_other(self):
        relabel = target(intent="set_device_attribute", device_name="lamp", attribute_type="color", attribute_value="warm white")
        first = self.labels("l1.jsonl", {"c": relabel})
        same = self.labels("l2.jsonl", {"c": relabel})
        _, approved, _, _ = self.run_import(first, second_path=same, second_reviewer="cowork-proxy", second_model="m")
        self.assertEqual(next(r for r in approved if r["id"] == "c")["target"], relabel)
        other_label = target(intent="set_device_attribute", device_name="lamp", attribute_type="color", attribute_value="cool")
        other = self.labels("l3.jsonl", {"c": other_label})
        _, approved, nate, _ = self.run_import(first, second_path=other, second_reviewer="cowork-proxy", second_model="m")
        self.assertEqual([r["id"] for r in nate], ["c"])
        self.assertNotIn("c", [r["id"] for r in approved])

    def test_authored_tracks_need_two_blind_reviewers_and_ignore_the_author_label(self):
        rp.make_packet(self.sample, "B", self.dir / "packetB")
        self.packet = self.dir / "packetB"
        labels = self.labels("l.jsonl")
        report, approved, _, _ = self.run_import(labels, track="B")
        self.assertEqual((approved, report["needs_second_reviewer"]), ([], 4))
        _, approved, _, _ = self.run_import(labels, track="B", second_path=self.labels("l2.jsonl"),
                                            second_reviewer="cowork-proxy", second_model="m")
        self.assertEqual(len(approved), 4)

    def test_refusals(self):
        good = self.labels("good.jsonl")
        text = good.read_text(encoding="utf-8")
        extra = json.dumps({"id": "a", "target": target(device_name="x", state="on")}) + "\n"
        dup = self.dir / "dup.jsonl"
        dup.write_text(text + extra, encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "duplicate"):
            self.run_import(dup)
        unknown = self.dir / "unknown.jsonl"
        unknown.write_text(text + extra.replace('"a"', '"zzz"'), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "not in the packet"):
            self.run_import(unknown)
        short = self.dir / "short.jsonl"
        short.write_text("".join(text.splitlines(True)[:2]), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "no label for 2"):
            self.run_import(short)
        invalid = self.dir / "invalid.jsonl"
        bad = {"intent": "set_device_state", "slots": {}, "requires_confirmation": False, "clarification": None, "automation": None}
        self.write(invalid, [{"id": r["id"], "target": bad} for r in self.rows])
        with self.assertRaisesRegex(ValueError, "invalid label"):
            self.run_import(invalid)
        with self.assertRaisesRegex(ValueError, "second label file needs"):
            self.run_import(good, second_path=good)
        with self.assertRaisesRegex(ValueError, "must differ"):
            self.run_import(good, second_path=good, second_reviewer="codex-proxy", second_model="m")

    def test_stale_packet_is_refused_and_the_sample_is_never_modified(self):
        before = rp.sha256_file(self.sample)
        self.run_import(self.labels("l.jsonl"))
        self.assertEqual(rp.sha256_file(self.sample), before)
        self.write(self.sample, self.rows[:3])
        with self.assertRaisesRegex(ValueError, "stale packet"):
            self.run_import(self.labels("l2.jsonl"))

    def test_source_rows_without_provenance_or_family_are_refused(self):
        self.write(self.sample, [dict(self.rows[0], provenance={"source": "enfuse"})])
        with self.assertRaisesRegex(ValueError, "provenance missing"):
            rp.make_packet(self.sample, "A", self.dir / "p2")
        self.write(self.sample, [dict(self.rows[0], group="")])
        with self.assertRaisesRegex(ValueError, "no paraphrase family"):
            rp.make_packet(self.sample, "A", self.dir / "p3")

    def test_report_holds_counts_and_ids_not_text_and_audit_is_from_approved(self):
        report, approved, _, audit = self.run_import(self.labels("l.jsonl"))
        self.assertEqual(report["audit_sample"], len(audit))
        self.assertTrue({a["id"] for a in audit} <= {a["id"] for a in approved})
        text = json.dumps(report)
        for r in self.rows:
            self.assertNotIn(r["text"], text)


if __name__ == "__main__":
    unittest.main()
