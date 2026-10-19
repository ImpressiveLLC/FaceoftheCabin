import json
import subprocess
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
CANDIDATES = HERE / "candidates"


class CandidateManifest(unittest.TestCase):
    def setUp(self):
        self.manifest = json.loads((CANDIDATES / "manifest.json").read_text(encoding="utf-8"))

    def test_counts_are_consistent(self):
        m = self.manifest
        self.assertEqual(sum(m["by_source"].values()), m["candidate_count"])
        self.assertEqual(sum(m["by_intent"].values()), m["candidate_count"])
        self.assertEqual(sum(s["candidates"] for s in m["sources"]), m["candidate_count"])
        self.assertEqual(sum(s["quarantine_records"] for s in m["sources"]), m["quarantine_count"])
        for source, by_intent in m["by_source_and_intent"].items():
            self.assertEqual(sum(by_intent.values()), m["by_source"][source])

    def test_snips_was_excluded_and_never_appears(self):
        m = self.manifest
        self.assertEqual(m["excluded_sources"], ["snips"])
        self.assertNotIn("snips", m["by_source"])
        self.assertTrue(all(s["source"] != "snips" and "snips" not in s["url"].lower() for s in m["sources"]))

    def test_sources_are_pinned_hashed_and_licensed(self):
        for s in self.manifest["sources"]:
            self.assertRegex(s["revision"], r"^[0-9a-f]{40}$")
            self.assertRegex(s["sha256"], r"^[0-9a-f]{64}$")
            self.assertIn(s["revision"], s["url"], "the URL must be the pinned revision, not a branch")
            self.assertTrue(s["license"])
        self.assertRegex(self.manifest["candidate_sha256"], r"^[0-9a-f]{64}$")

    def test_nothing_was_approved_trained_or_evaluated(self):
        m = self.manifest
        self.assertEqual((m["training_run"], m["human_approved"], m["model_evaluated"]), (False, 0, False))

    def test_the_external_sources_supply_no_hard_class(self):
        # The data-path plan rests on this: if it ever stops being true, the plan needs rereading.
        self.assertEqual(set(self.manifest["by_intent"]), {"get_device_state", "set_device_attribute", "set_device_state"})

    def test_the_manifest_holds_counts_hashes_and_urls_and_no_dataset_text(self):
        text = (CANDIDATES / "manifest.json").read_text(encoding="utf-8")
        self.assertLess(len(text), 20000)
        for key in ("text", "original", "utterance", "sentences"):
            self.assertNotIn(f'"{key}"', text)

    def test_no_candidate_or_download_file_is_committed(self):
        tracked = subprocess.run(["git", "ls-files", "--", str(CANDIDATES)], capture_output=True, text=True, cwd=HERE)
        if tracked.returncode != 0:
            self.skipTest("not a git checkout")
        names = sorted(Path(p).name for p in tracked.stdout.split())
        self.assertEqual(names, [".gitignore", "README.md", "manifest.json"])


if __name__ == "__main__":
    unittest.main()
