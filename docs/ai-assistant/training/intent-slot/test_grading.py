import copy
import json
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path
from grading.server import ReviewStore, Server
from grading.collect_sources import ha_unit

ROOT = Path(__file__).resolve().parent


class GradingTests(unittest.TestCase):
    def test_ha_pinned_shape_and_guarded_domains(self):
        data = {"entities": [{"name": "Lamp", "domain": "light"}, {"name": "Door", "domain": "lock"}]}
        row = {"sentences": ["turn on Lamp"], "slots": {"name": "Lamp"}}
        mapped = ha_unit(data, row, "tests/en/HassTurnOn/name_only.yaml")
        self.assertEqual(mapped["tests"][0]["intent"]["name"], "HassTurnOn")
        row["slots"]["name"] = "Door"
        with self.assertRaises(ValueError):
            ha_unit(data, row, "tests/en/HassTurnOn/name_only.yaml")

    def test_candidate_export_cannot_be_reopened_as_gold(self):
        store = ReviewStore(ROOT, self.path, ROOT / "gold.jsonl", "training-candidate")
        store.save_review(self.decision())
        self.assertEqual(store.approved_rows()[0]["provenance"]["purpose"], "training-candidate")
        with self.assertRaises(ValueError):
            ReviewStore(ROOT, self.path)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "reviews.json"
        self.store = ReviewStore(ROOT, self.path)
        self.row = self.store.rows[0]

    def tearDown(self):
        self.temp.cleanup()

    def decision(self, **changes):
        return {"id": self.row["id"], "reviewer": "test reviewer", "decision": "approved",
                "target": copy.deepcopy(self.row["target"]), "revision": 0, **changes}

    def test_persistence_and_approved_export(self):
        self.assertEqual(self.store.approved_rows(), [])
        self.store.save_review(self.decision())
        restored = ReviewStore(ROOT, self.path)
        exported = restored.approved_rows()
        self.assertEqual(len(exported), 1)
        self.assertEqual(exported[0]["provenance"]["reviewer"], "test reviewer")
        self.assertEqual(exported[0]["provenance"]["purpose"], "evaluation-only")
        self.assertEqual(restored.rows[0], self.row)
        self.assertEqual(len(restored.state["audit"]), 1)

    def test_invalid_approval_and_rejection_reason(self):
        bad = self.decision()
        bad["target"]["requires_confirmation"] = False
        bad["notes"] = "invalid mutation"
        with self.assertRaises(Exception):
            self.store.save_review(bad)
        with self.assertRaises(ValueError):
            self.store.save_review(self.decision(decision="rejected"))
        self.store.save_review(self.decision(decision="rejected", notes="Unsuitable example"))
        self.assertEqual(self.store.approved_rows(), [])

    def test_correction_requires_reason_and_preserves_original(self):
        data = self.decision()
        data["target"]["slots"]["location"] = "corrected room"
        with self.assertRaises(ValueError):
            self.store.save_review(data)
        data["notes"] = "Reviewed correction"
        self.store.save_review(data)
        self.assertEqual(self.store.approved_rows()[0]["target"]["slots"]["location"], "corrected room")
        self.assertNotEqual(self.row["target"]["slots"]["location"], "corrected room")

    def test_stale_tab_cannot_overwrite(self):
        self.store.save_review(self.decision())
        with self.assertRaises(RuntimeError):
            self.store.save_review(self.decision(decision="pending"))

    def test_changed_dataset_rejects_journal(self):
        self.store.save_review(self.decision())
        data = json.loads(self.path.read_text())
        data["dataset_sha256"] = "stale"
        self.path.write_text(json.dumps(data))
        with self.assertRaises(ValueError):
            ReviewStore(ROOT, self.path)

    def test_report_hash_and_separate_model_grades(self):
        report = {"gold_sha256": "wrong", "predictions": {self.row["id"]: self.row["target"]}}
        with self.assertRaises(ValueError):
            self.store.import_report(report)
        report["gold_sha256"] = self.store.dataset_hash
        self.store.import_report(report)
        self.store.save_model_review({"id": self.row["id"], "decision": "pass", "reviewer": "tester",
                                      "report_sha256": self.store.state["report"]["sha256"], "gold_revision": 0})
        self.assertEqual(self.store.approved_rows(), [])
        data = self.decision()
        data["target"]["slots"]["location"] = "revised"
        data["notes"] = "Change reference answer"
        self.store.save_review(data)
        self.assertEqual(self.store.state["model_reviews"], {})

    def test_http_loopback_csrf_and_exports(self):
        server = Server(("127.0.0.1", 0), self.store)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = f"http://127.0.0.1:{server.server_port}"
        try:
            with urllib.request.urlopen(base + "/api/state") as response:
                token = json.load(response)["token"]
            payload = json.dumps(self.decision()).encode()
            with self.assertRaises(urllib.error.HTTPError) as raised:
                urllib.request.urlopen(urllib.request.Request(base + "/api/review", data=payload))
            self.assertEqual(raised.exception.code, 403)
            headers = {"X-Review-Token": token, "Origin": "https://example.com"}
            with self.assertRaises(urllib.error.HTTPError):
                urllib.request.urlopen(urllib.request.Request(base + "/api/review", data=payload, headers=headers))
            headers["Origin"] = base
            with urllib.request.urlopen(urllib.request.Request(base + "/api/review", data=payload, headers=headers)) as response:
                self.assertEqual(response.status, 200)
            with urllib.request.urlopen(base + "/api/export/gold") as response:
                self.assertEqual(json.loads(response.read())["id"], self.row["id"])
            with self.assertRaises(urllib.error.HTTPError):
                urllib.request.urlopen(base + "/../contract.py")
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == "__main__":
    unittest.main()
