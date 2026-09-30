import json
import tempfile
import unittest
from pathlib import Path

from validate_questions_manifest import MANIFEST_PATH, REPO_ROOT, validate


def manifest(questions):
    return {"_schema": "fotc-eval-manifest/1", "questions": questions}


class ValidateQuestionsManifestTest(unittest.TestCase):
    def write(self, directory, data):
        path = Path(directory) / "manifest.json"
        path.write_text(json.dumps(data), encoding="utf-8")
        return path

    def test_written_status_with_missing_file_is_flagged(self):
        data = manifest([{"id": "Q01", "source_doc_status": "written",
                           "source_docs": ["docs/does/not/exist.md#anchor"]}])
        with tempfile.TemporaryDirectory() as d:
            problems = validate(self.write(d, data), repo_root=REPO_ROOT)
        self.assertEqual(len(problems), 1)
        self.assertIn("Q01", problems[0])
        self.assertIn("does not exist", problems[0])

    def test_not_yet_written_with_missing_file_is_not_flagged(self):
        data = manifest([{"id": "Q01", "source_doc_status": "not_yet_written",
                           "source_docs": ["docs/does/not/exist.md#anchor"]}])
        with tempfile.TemporaryDirectory() as d:
            problems = validate(self.write(d, data), repo_root=REPO_ROOT)
        self.assertEqual(problems, [])

    def test_live_data_required_needs_no_source_docs(self):
        data = manifest([{"id": "Q09", "source_doc_status": "live_data_required", "source_docs": []}])
        with tempfile.TemporaryDirectory() as d:
            problems = validate(self.write(d, data), repo_root=REPO_ROOT)
        self.assertEqual(problems, [])

    def test_written_status_with_no_source_docs_is_flagged(self):
        data = manifest([{"id": "Q01", "source_doc_status": "written", "source_docs": []}])
        with tempfile.TemporaryDirectory() as d:
            problems = validate(self.write(d, data), repo_root=REPO_ROOT)
        self.assertEqual(len(problems), 1)
        self.assertIn("empty", problems[0])

    def test_unknown_status_is_flagged(self):
        data = manifest([{"id": "Q01", "source_doc_status": "made_up_status", "source_docs": []}])
        with tempfile.TemporaryDirectory() as d:
            problems = validate(self.write(d, data), repo_root=REPO_ROOT)
        self.assertEqual(len(problems), 1)
        self.assertIn("unknown source_doc_status", problems[0])

    def test_duplicate_id_is_flagged(self):
        data = manifest([
            {"id": "Q01", "source_doc_status": "live_data_required", "source_docs": []},
            {"id": "Q01", "source_doc_status": "live_data_required", "source_docs": []},
        ])
        with tempfile.TemporaryDirectory() as d:
            problems = validate(self.write(d, data), repo_root=REPO_ROOT)
        self.assertEqual(len(problems), 1)
        self.assertIn("duplicate", problems[0])

    def test_external_url_source_doc_is_not_treated_as_a_repo_path(self):
        data = manifest([{"id": "Q01", "source_doc_status": "written",
                           "source_docs": ["https://example.com/not-a-repo-file"]}])
        with tempfile.TemporaryDirectory() as d:
            problems = validate(self.write(d, data), repo_root=REPO_ROOT)
        self.assertEqual(problems, [])

    def test_the_real_committed_manifest_is_currently_clean(self):
        # Regression guard: this is what actually caught the 2026-09-20 drift
        # (five nonexistent source_docs files, plus a stale handover link) --
        # keep this passing rather than re-introducing an unverifiable path.
        problems = validate(MANIFEST_PATH, repo_root=REPO_ROOT)
        self.assertEqual(problems, [])


if __name__ == "__main__":
    unittest.main()
