import json
import tempfile
import unittest
from pathlib import Path

from grading import bind_families as bf

HERE = Path(__file__).resolve().parent


def row(rid, group="one-legacy-group"):
    return {"id": rid, "text": "turn on " + rid, "group": group, "target": {"intent": "set_device_state"}}


class Bind(unittest.TestCase):
    def test_every_row_gets_its_own_canonical_family_and_keeps_the_source_group(self):
        rows = [row("a"), row("b"), row("c")]
        out = bf.bind(rows, {"fam-1": ["a", "b"], "fam-2": ["c"]})
        self.assertEqual([r["group"] for r in out], ["fam-1", "fam-1", "fam-2"])
        self.assertEqual({r["source_group"] for r in out}, {"one-legacy-group"})
        self.assertEqual([r["text"] for r in out], [r["text"] for r in rows])

    def test_input_rows_are_not_mutated(self):
        rows = [row("a")]
        bf.bind(rows, {"fam-1": ["a"]})
        self.assertEqual(rows[0]["group"], "one-legacy-group")
        self.assertNotIn("source_group", rows[0])

    def test_refusals(self):
        with self.assertRaisesRegex(ValueError, "not in the families map"):
            bf.bind([row("a"), row("zzz")], {"fam-1": ["a"]})
        with self.assertRaisesRegex(ValueError, "two families"):
            bf.bind([row("a")], {"fam-1": ["a"], "fam-2": ["a"]})
        with self.assertRaisesRegex(ValueError, "duplicate ids"):
            bf.bind([row("a"), row("a")], {"fam-1": ["a"]})

    def test_the_committed_sample_ids_map_to_distinct_canonical_families(self):
        manifest = json.loads((HERE / "sft" / "s1" / "s1-manifest.json").read_text(encoding="utf-8"))
        families = json.loads((HERE / "sft" / "s1" / "families.json").read_text(encoding="utf-8"))
        bound = bf.bind([row(i) for i in manifest["sample_ids"]], families)
        self.assertEqual(len(bound), manifest["sample_count"])
        self.assertEqual(len({r["group"] for r in bound}), manifest["sample_count"])  # one row per family, so no two rows share one
        self.assertEqual({r["source_group"] for r in bound}, {"one-legacy-group"})

    def test_cli_writes_a_derivative_and_leaves_the_original_untouched(self):
        import subprocess
        import sys
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            sample, fams, out = d / "sample.jsonl", d / "fam.json", d / "bound.jsonl"
            sample.write_text("".join(json.dumps(r) + "\n" for r in [row("a"), row("b")]), encoding="utf-8")
            fams.write_text(json.dumps({"fam-1": ["a"], "fam-2": ["b"]}), encoding="utf-8")
            before = bf.sha256_file(sample)
            done = subprocess.run([sys.executable, "-B", str(HERE / "grading" / "bind_families.py"), "--sample", str(sample),
                                   "--families", str(fams), "--out", str(out)], capture_output=True, text=True)
            self.assertEqual(done.returncode, 0, done.stderr)
            self.assertEqual(bf.sha256_file(sample), before)
            self.assertEqual({r["group"] for r in bf.read_jsonl(out)}, {"fam-1", "fam-2"})
            same = subprocess.run([sys.executable, "-B", str(HERE / "grading" / "bind_families.py"), "--sample", str(sample),
                                   "--families", str(fams), "--out", str(sample)], capture_output=True, text=True)
            self.assertNotEqual(same.returncode, 0)
            self.assertEqual(bf.sha256_file(sample), before)


if __name__ == "__main__":
    unittest.main()
