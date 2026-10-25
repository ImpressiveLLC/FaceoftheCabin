import json
import unittest
from pathlib import Path

from grading import reserve_ohf as ro

HERE = Path(__file__).resolve().parent
RECORD = HERE / "sft" / "s1" / "reserved-ohf.json"


def row(rid, text, source="ha", intent="set_device_state"):
    return {"id": rid, "text": text, "target": {"intent": intent, "slots": {"state": "on"}}, "provenance": {"source": source}}


class Reserve(unittest.TestCase):
    def setUp(self):
        self.rows = [row("ha-1", "turn on the cellar fan"), row("ha-2", "turn off the attic lamp"),
                     row("ha-3", "turn on the porch light"), row("en-1", "turn on the porch light please", source="enfuse")]
        self.families = {"fam-a": ["ha-1"], "fam-b": ["ha-2"], "fam-c": ["ha-3", "en-1"]}

    def test_a_family_shared_with_a_training_source_is_never_reserved(self):
        chosen, skipped, count = ro.reserve(self.rows, self.families, [])
        self.assertEqual(sorted(r["id"] for _, r in chosen), ["ha-1", "ha-2"])
        self.assertEqual(skipped, {"shares a family with a training-source row": 1})
        self.assertEqual(count, 3)

    def test_a_row_matching_dev_or_a_checked_file_is_skipped_and_on_off_still_differs(self):
        chosen, skipped, _ = ro.reserve(self.rows, self.families, ["Turn the cellar fan on", "turn on the attic lamp"])
        self.assertEqual(sorted(r["id"] for _, r in chosen), ["ha-2"])
        self.assertEqual(skipped["matches dev, a template or a checked file"], 1)

    def test_only_set_device_state_is_reserved(self):
        rows = [row("ha-9", "is the cellar fan on", intent="get_device_state")]
        chosen, skipped, _ = ro.reserve(rows, {"fam-z": ["ha-9"]}, [])
        self.assertEqual((chosen, skipped), ([], {"not set_device_state": 1}))


class CommittedRecord(unittest.TestCase):
    def setUp(self):
        self.record = json.loads(RECORD.read_text(encoding="utf-8"))

    def test_thirty_rows_one_per_family_none_from_a_shared_family(self):
        r = self.record
        self.assertEqual((r["rows"], len(r["ids"]), len(set(r["family_ids"]))), (30, 30, 30))
        self.assertTrue(all(i.startswith("ha-") for i in r["ids"]))
        families = json.loads((HERE / "sft" / "s1" / "families.json").read_text(encoding="utf-8"))
        for fam in r["family_ids"]:
            self.assertTrue(all(i.startswith("ha-") for i in families[fam]), "a reserved family also holds a training-source row")

    def test_source_is_pinned_licensed_and_the_record_holds_no_text(self):
        r = self.record
        self.assertEqual((r["licence"], r["revisions"]), ("CC-BY-4.0", ["a64026f9359dc705138314d7c363fef1f6d7bf5d"]))
        self.assertRegex(r["reserved_sha256"], r"^[0-9a-f]{64}$")
        text = RECORD.read_text(encoding="utf-8")
        self.assertNotIn('"text"', text)
        self.assertEqual((r["reviewed"], r["training_run"]), (0, False))

    def test_the_sealed_files_check_is_honestly_not_run_here(self):
        self.assertTrue(str(self.record["checked_against"]["extra_files"]).startswith("NOT_RUN"))


if __name__ == "__main__":
    unittest.main()
