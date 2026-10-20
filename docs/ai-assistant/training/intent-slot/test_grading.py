import copy
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest
from unittest import mock
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
        data["target"]["slots"]["location"] = "loft"
        with self.assertRaises(ValueError):
            self.store.save_review(data)
        data["notes"] = "Reviewed correction"
        self.store.save_review(data)
        self.assertEqual(self.store.approved_rows()[0]["target"]["slots"]["location"], "loft")
        self.assertNotEqual(self.row["target"]["slots"]["location"], "loft")

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
        data["target"]["slots"]["location"] = "loft"
        data["notes"] = "Change reference answer"
        self.store.save_review(data)
        self.assertEqual(self.store.state["model_reviews"], {})

    def test_a_room_spelled_differently_is_normalized_and_is_not_a_correction(self):
        data = self.decision()
        data["target"]["slots"]["location"] = "  The Hallway "
        review = self.store.save_review(data)  # no notes needed: spelling is not a correction
        self.assertFalse(review["revised"])
        self.assertEqual(review["target"]["slots"]["location"], "hall")
        self.assertEqual(review["normalized"], [{"path": "slots.location", "from": "  The Hallway ", "to": "hall"}])
        self.assertEqual(self.store.approved_rows()[0]["target"]["slots"]["location"], "hall")

    def test_an_unknown_room_blocks_approval_but_not_rejection_or_save_for_later(self):
        data = self.decision()
        data["target"]["slots"]["location"] = "narnia"
        with self.assertRaises(ValueError) as raised:
            self.store.save_review(data)
        self.assertIn("slots.location", str(raised.exception))
        self.assertIn("Unknown room", str(raised.exception))
        self.assertEqual(self.store.approved_rows(), [])
        self.store.save_review({**data, "decision": "pending", "notes": "Which room is this?", "revision": 0})
        self.store.save_review({**data, "decision": "rejected", "notes": "Room does not exist", "revision": 1})
        self.assertEqual(self.store.approved_rows(), [])

    def test_a_human_need_cannot_ride_on_a_direct_command(self):
        data = self.decision()
        data["target"]["slots"]["concept"] = "light"
        with self.assertRaises(ValueError) as raised:
            self.store.save_review(data)
        self.assertIn("only to a need request", str(raised.exception))

    def test_every_need_in_the_vocabulary_can_be_approved_on_a_need_request(self):
        need = next(r for r in self.store.rows if r["target"]["intent"] == "express_need")
        for concept in ("light", "nap", "wake_up", "dark", "bedtime"):
            target = copy.deepcopy(need["target"])
            target["slots"]["concept"] = concept
            self.assertTrue(self.store.check_draft({"id": need["id"], "target": target})["ok"], concept)
        target["slots"]["concept"] = "bright"  # an alias of light, not a concept
        result = self.store.check_draft({"id": need["id"], "target": target})
        self.assertFalse(result["ok"])
        self.assertIn("Unknown need", result["fields"]["slots.concept"])

    def test_live_check_reports_fields_normalization_contract_and_supported_needs(self):
        target = copy.deepcopy(self.row["target"])
        clean = self.store.check_draft({"id": self.row["id"], "target": target})
        self.assertTrue(clean["ok"])
        self.assertEqual(clean["supports"], ["dark", "light"])  # "switch on the lights": context only
        target["slots"]["location"] = "Mechanical Room"
        fixed = self.store.check_draft({"id": self.row["id"], "target": target})
        self.assertTrue(fixed["ok"])
        self.assertEqual(fixed["normalized"]["slots"]["location"], "mech room")
        self.assertEqual(fixed["changes"][0]["to"], "mech room")
        target["slots"]["location"] = "x" * 50
        bad = self.store.check_draft({"id": self.row["id"], "target": target})
        self.assertFalse(bad["ok"])
        self.assertIn("longer than", bad["fields"]["slots.location"])
        target = copy.deepcopy(self.row["target"])
        target["requires_confirmation"] = False
        contract = self.store.check_draft({"id": self.row["id"], "target": target})
        self.assertFalse(contract["ok"])
        self.assertIn("confirmation", contract["contract"])
        with self.assertRaises(ValueError):
            self.store.check_draft({"id": self.row["id"], "target": {"intent": "clarify"}})
        self.assertEqual(self.store.state["audit"], [], "a live check never writes to the journal")

    def test_the_screen_is_given_the_vocabulary_and_the_journal_is_bound_to_it(self):
        vocab = self.store.snapshot()["vocabulary"]
        light = next(c for c in vocab["concepts"] if c["id"] == "light")
        self.assertIn("bright", light["aliases"])
        self.assertIn("mech room", [a["id"] for a in vocab["areas"]])
        self.assertEqual(vocab["sites"], ["cabin", "home"])
        self.assertEqual(self.store.contract_hash, ReviewStore(ROOT, self.path).contract_hash)
        self.store.save_review(self.decision())
        data = json.loads(self.path.read_text())
        data["contract_sha256"] = "from-before-the-vocabulary-changed"
        self.path.write_text(json.dumps(data))
        with self.assertRaises(ValueError):
            ReviewStore(ROOT, self.path)

    def test_validate_endpoint_needs_the_token_and_returns_the_same_answer(self):
        server = Server(("127.0.0.1", 0), self.store)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = f"http://127.0.0.1:{server.server_port}"
        try:
            payload = json.dumps({"id": self.row["id"], "target": self.row["target"]}).encode()
            with self.assertRaises(urllib.error.HTTPError) as raised:
                urllib.request.urlopen(urllib.request.Request(base + "/api/validate", data=payload))
            self.assertEqual(raised.exception.code, 403)
            with urllib.request.urlopen(base + "/api/state") as response:
                token = json.load(response)["token"]
            with urllib.request.urlopen(urllib.request.Request(base + "/api/validate", data=payload, headers={"X-Review-Token": token})) as response:
                self.assertTrue(json.load(response)["ok"])
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def run_collector(self, *extra):
        """Run collect_sources.main with every network call replaced by a tiny fixed answer; returns (urls requested, manifest)."""
        import io
        from contextlib import redirect_stdout
        from unittest.mock import patch
        from grading import collect_sources
        urls = []
        enfuse = json.dumps({"text": "turn on the kitchen light", "intent": "set_device_state", "slots": [
            {"slot_type": "device_name", "slot_value": "light"}, {"slot_type": "location", "slot_value": "kitchen"},
            {"slot_type": "state", "slot_value": "on"}]}).encode() + b"\n"

        class Response:
            def __init__(self, body):
                self.body = body
            def __enter__(self):
                return self
            def __exit__(self, *exc):
                return False
            def read(self):
                return self.body

        def fake(request, timeout=0):
            url = request.full_url
            urls.append(url)
            if url.endswith("train.jsonl"):
                return Response(enfuse)
            if url.endswith(".yaml"):
                return Response(b"tests: []\nentities: []\n")
            if url.endswith("lights_dataset.json"):
                return Response(b'{"intents": {}}')
            return Response(b"licence text")

        out = Path(self.temp.name) / "collected"
        with patch.object(collect_sources.urllib.request, "urlopen", fake),                 patch.object(sys, "argv", ["collect_sources.py", "--package", str(ROOT), "--output", str(out), *extra]),                 redirect_stdout(io.StringIO()):
            collect_sources.main()
        return urls, json.loads((out / "manifest.json").read_text(encoding="utf-8"))

    def test_an_excluded_source_is_never_requested_and_the_manifest_says_so(self):
        urls, manifest = self.run_collector("--exclude", "snips")
        self.assertFalse([u for u in urls if "snips" in u.lower()], urls)
        self.assertTrue(any("huggingface.co" in u for u in urls) and any("OHF-Voice" in u for u in urls))
        self.assertEqual(manifest["excluded_sources"], ["snips"])
        self.assertEqual(manifest["by_source"], {"enfuse": 1})
        self.assertEqual(manifest["by_intent"], {"set_device_state": 1})
        self.assertNotIn("snips", {s["source"] for s in manifest["sources"]})

    def test_excluding_enfuse_and_ha_requests_only_the_rest(self):
        urls, manifest = self.run_collector("--exclude", "enfuse", "ha")
        self.assertTrue(urls and all("snips" in u.lower() for u in urls), urls)
        self.assertEqual(manifest["excluded_sources"], ["enfuse", "ha"])
        self.assertEqual(manifest["by_source"], {})

    def test_with_no_exclusion_all_three_sources_are_requested(self):
        urls, manifest = self.run_collector()
        self.assertEqual(manifest["excluded_sources"], [])
        for needle in ("huggingface.co", "OHF-Voice", "snips"):
            self.assertTrue(any(needle in u for u in urls), needle)

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


def copy_package(destination):
    """A throwaway copy of the intent-slot package, so finishing a session can rewrite its gold.jsonl safely."""
    shutil.copytree(ROOT, destination, ignore=shutil.ignore_patterns("__pycache__", "candidates", "rounds", "sft", ".venv", "*.tmp"))
    return Path(destination)


def git(cwd, *args):
    return subprocess.run(["git", *args], cwd=str(cwd), capture_output=True, text=True, check=True).stdout.strip()


class SessionFlowTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.tmp = Path(self.temp.name)
        self.pkg = copy_package(self.tmp / "pkg")
        self.store = ReviewStore(self.pkg, self.tmp / "journal.json")
        self.store.check_command = [sys.executable, "-c", "pass"]
        self.row = self.store.rows[0]

    def tearDown(self):
        self.temp.cleanup()

    def approve(self, store=None, index=0, notes="ok"):
        store = store or self.store
        row = store.rows[index]
        store.save_review({"id": row["id"], "reviewer": "Nate", "decision": "approved", "notes": notes,
                           "target": copy.deepcopy(row["target"]), "revision": store.state["reviews"].get(row["id"], {}).get("revision", 0)})

    def test_default_journal_is_per_dataset_and_contract(self):
        with mock.patch.object(Path, "home", return_value=self.tmp):
            store = ReviewStore(self.pkg, None)
        self.assertEqual(store.path.parent, (self.tmp / ".fotc-grading").resolve())
        self.assertIn(store.dataset_hash[:8], store.path.name)
        self.assertIn(store.contract_hash[:8], store.path.name)

    def test_preflight_outside_git_warns_but_does_not_block(self):
        report = self.store.preflight()
        self.assertEqual(report["blockers"], 0)
        self.assertIn("git", {c["id"] for c in report["checks"] if c["status"] == "warn"})

    def test_a_changed_file_means_restart(self):
        (self.pkg / "concepts.json").write_text((self.pkg / "concepts.json").read_text() + "\n")
        checks = {c["id"]: c for c in self.store.preflight()["checks"]}
        self.assertEqual(checks["restart"]["status"], "fail")
        self.assertIn("start it again", checks["restart"]["fix"])

    def test_done_moves_only_approved_rows_and_keeps_the_rest_byte_identical(self):
        before = [l for l in (self.pkg / "gold.jsonl").read_text(encoding="utf-8").splitlines()]
        self.approve(notes="Nate approved this one")
        result = self.store.finish()
        self.assertEqual((result["mode"], result["count"], result["tests"]), ("gold", 1, "passed"))
        after = (self.pkg / "gold.jsonl").read_text(encoding="utf-8").splitlines()
        self.assertEqual(len(after), len(before))
        self.assertEqual(after[1:], before[1:])
        moved = json.loads(after[0])
        self.assertEqual(moved["provenance"]["review_status"], "approved")
        self.assertEqual(moved["provenance"]["reviewer"], "Nate")
        self.assertEqual(moved["provenance"]["review_notes"], "Nate approved this one")

    def test_done_with_nothing_approved_changes_nothing(self):
        before = (self.pkg / "gold.jsonl").read_bytes()
        with self.assertRaises(ValueError):
            self.store.finish()
        self.assertEqual((self.pkg / "gold.jsonl").read_bytes(), before)

    def test_failing_package_checks_roll_the_file_back(self):
        before = (self.pkg / "gold.jsonl").read_bytes()
        self.approve(notes="changed so the bytes differ")
        self.store.check_command = [sys.executable, "-c", "import sys; print('drift'); sys.exit(1)"]
        with self.assertRaises(ValueError) as raised:
            self.store.finish()
        self.assertIn("nothing was changed", str(raised.exception))
        self.assertEqual((self.pkg / "gold.jsonl").read_bytes(), before)

    def test_candidate_sessions_never_write_gold(self):
        gold = (self.pkg / "gold.jsonl").read_bytes()
        cand = self.tmp / "candidates.jsonl"
        shutil.copy(self.pkg / "gold.jsonl", cand)
        store = ReviewStore(self.pkg, self.tmp / "cand-journal.json", cand, "training-candidate")
        self.approve(store)
        result = store.finish()
        self.assertEqual(result["mode"], "candidates")
        self.assertTrue(Path(result["path"]).exists())
        self.assertEqual((self.pkg / "gold.jsonl").read_bytes(), gold)

    def test_endpoints_need_the_token_and_report_state(self):
        server = Server(("127.0.0.1", 0), self.store)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = f"http://127.0.0.1:{server.server_port}"
        try:
            with urllib.request.urlopen(base + "/api/preflight") as response:
                report = json.load(response)
            self.assertIn("checks", report)
            with urllib.request.urlopen(base + "/session.js") as response:
                self.assertIn("Done, move to gold", response.read().decode())
            with self.assertRaises(urllib.error.HTTPError) as raised:
                urllib.request.urlopen(urllib.request.Request(base + "/api/finish", data=b"{}"))
            self.assertEqual(raised.exception.code, 403)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


class SessionGitFlowTests(unittest.TestCase):
    """The real path: a clone of main, a remote, a pull request branch, and the repo left on main."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.tmp = Path(self.temp.name)
        self.remote = self.tmp / "remote.git"
        git(self.tmp, "init", "--bare", "-b", "main", str(self.remote))
        self.work = self.tmp / "work"
        self.work.mkdir()
        git(self.work, "init", "-b", "main")
        git(self.work, "config", "user.email", "nate@example.com")
        git(self.work, "config", "user.name", "Nate")
        self.pkg = copy_package(self.work / "pkg")
        git(self.work, "add", "-A")
        git(self.work, "commit", "-m", "base")
        git(self.work, "remote", "add", "origin", "https://github.com/ImpressiveLLC/FaceoftheCabin.git")
        git(self.work, "config", "url." + str(self.remote) + ".insteadOf", "https://github.com/ImpressiveLLC/FaceoftheCabin.git")
        git(self.work, "push", "-u", "origin", "main")
        self.store = ReviewStore(self.pkg, self.tmp / "journal.json")
        self.store.check_command = [sys.executable, "-c", "pass"]

    def tearDown(self):
        self.temp.cleanup()

    def approve(self):
        row = self.store.rows[0]
        self.store.save_review({"id": row["id"], "reviewer": "Nate", "decision": "approved", "notes": "git flow",
                                "target": copy.deepcopy(row["target"]), "revision": 0})

    def test_a_fresh_clone_on_main_is_green(self):
        report = self.store.preflight()
        self.assertEqual(report["blockers"], 0, report)
        self.assertEqual({c["id"]: c["status"] for c in report["checks"]}["fresh"], "ok")

    def test_behind_main_blocks_with_the_pull_instruction(self):
        other = self.tmp / "other"
        git(self.tmp, "clone", str(self.remote), str(other))
        git(other, "config", "user.email", "x@example.com")
        git(other, "config", "user.name", "X")
        (other / "NOTE.txt").write_text("a merge landed\n")
        git(other, "add", "-A")
        git(other, "commit", "-m", "someone merged")
        git(other, "push", "origin", "main")
        checks = {c["id"]: c for c in self.store.preflight()["checks"]}
        self.assertEqual(checks["fresh"]["status"], "fail")
        self.assertIn("git pull", checks["fresh"]["fix"])
        self.approve()
        with self.assertRaises(RuntimeError):
            self.store.finish()

    def test_done_branches_pushes_and_leaves_the_repo_on_main(self):
        self.approve()
        result = self.store.finish()
        self.assertTrue(result["pushed"], result)
        self.assertTrue(result["branch"].startswith("grading/gold-"))
        self.assertIn("compare/main..." + result["branch"], result["pr_url"])
        self.assertEqual(git(self.work, "rev-parse", "--abbrev-ref", "HEAD"), "main")
        self.assertIn(result["branch"], git(self.remote, "branch", "--list"))
        on_branch = git(self.remote, "show", f"{result['branch']}:pkg/gold.jsonl").splitlines()[0]
        self.assertEqual(json.loads(on_branch)["provenance"]["review_notes"], "git flow")
        self.assertNotIn("git flow", (self.pkg / "gold.jsonl").read_text(encoding="utf-8"))


class SessionInstructions(unittest.TestCase):
    """The start-up steps shown on the page must work in Windows PowerShell 5.1, which has no `&&`."""

    def test_page_and_messages_point_at_the_start_script_and_avoid_and_and(self):
        grading = Path(__file__).resolve().parent / "grading"
        self.assertTrue((grading / "start.ps1").is_file())
        page = (grading / "index.html").read_text(encoding="utf-8")
        self.assertIn("start.ps1", page)
        self.assertNotIn("&&", page)
        self.assertNotIn("&amp;&amp;", page)
        self.assertNotIn("&&", (grading / "server.py").read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
