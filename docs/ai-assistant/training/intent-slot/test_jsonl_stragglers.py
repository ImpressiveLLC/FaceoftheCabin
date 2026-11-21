"""Synthetic Unicode intake regressions; no private input, model or network."""
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import contract
import round_ledger
import run_reports
from grading import s1_sample, server
from boundary import evaluate_boundary, run_boundary_route, test_boundary

ROOT = Path(__file__).resolve().parent


class Parsed(BaseException):
    """Stop operational CLI work immediately after parsing synthetic fixtures."""


class JsonlStragglers(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.rows = [{"id": "synthetic-a", "text": "lamp\u2028line\u2029paragraph\u0085next",
                      "target": {"intent": "propose_automation"}},
                     {"id": "synthetic-b", "text": "other\ntext", "target": {"intent": "clarify"}}]
        self.path = self.root / "gold.jsonl"
        self.raw = ("\n".join(json.dumps(r, ensure_ascii=False) for r in self.rows) + "\n").encode("utf-8")
        self.path.write_bytes(self.raw)

    def test_contract_and_boundary_readers_preserve_unicode_and_record_rules(self):
        for reader in (contract.read_jsonl, evaluate_boundary.read_rows):
            for ending in (b"\n", b"\r\n"):
                for final in (True, False):
                    raw = self.raw.replace(b"\n", ending)
                    if not final:
                        raw = raw.removesuffix(ending)
                    self.path.write_bytes(raw)
                    with self.subTest(reader=reader.__module__, ending=ending, final=final):
                        self.assertEqual(reader(self.path), self.rows)
                        self.assertEqual(self.path.read_bytes(), raw)
            self.path.write_bytes(b'\n \n{"text":"ok"}\n')
            self.assertEqual(reader(self.path), [{"text": "ok"}])
            self.path.write_bytes(b'{"text":broken}\n')
            with self.assertRaises(json.JSONDecodeError):
                reader(self.path)

    def test_report_intents_preserve_unicode_records(self):
        self.assertEqual(run_reports.gold_intents(self.path),
                         {r["id"]: r["target"]["intent"] for r in self.rows})
        self.assertEqual(self.path.read_bytes(), self.raw)

    def test_review_store_preserves_unicode_and_bom_support(self):
        # Real committed validator; only the candidate input is synthetic.
        for bom in (b"", b"\xef\xbb\xbf"):
            self.path.write_bytes(bom + self.raw)
            store = server.ReviewStore(ROOT, self.root / "unused-journal.json", self.path,
                                       purpose="training-candidate")
            self.assertEqual(store.rows, self.rows)
            self.assertEqual(store.dataset_hash, hashlib.sha256(bom + self.raw).hexdigest())
            self.assertFalse((self.root / "unused-journal.json").exists())

    def test_s1_candidate_boundary_stops_before_building_a_sample(self):
        argv = ["sample", "--candidates", str(self.path), "--private-output", str(self.root / "unused-private"),
                "--committed-output", str(self.root / "unused-public")]
        with patch.object(sys, "argv", argv), patch.object(s1_sample, "build", side_effect=Parsed) as build:
            with self.assertRaises(Parsed):
                s1_sample.main()
        self.assertEqual(build.call_args.args[0], self.rows)
        self.assertEqual(self.path.read_bytes(), self.raw)
        self.assertFalse((self.root / "unused-private").exists())
        self.assertFalse((self.root / "unused-public").exists())

    def test_ledger_cli_stops_before_recording_synthetic_rows(self):
        report = self.root / "report.json"
        prompt, schema = self.root / "prompt.txt", self.root / "schema.json"
        prompt.write_bytes(b"invented prompt")
        schema.write_bytes(b"{}")
        report.write_text(json.dumps({"prompt_sha256": round_ledger.sha(prompt),
                                     "schema_sha256": round_ledger.sha(schema)}), encoding="utf-8")
        argv = ["ledger", "record", str(report), "--round", "synthetic", "--out", str(self.root / "unused.json"),
                "--gold", str(self.path), "--prompt", str(prompt), "--schema", str(schema)]
        with patch.object(sys, "argv", argv), patch.object(round_ledger, "record", side_effect=Parsed) as record:
            with self.assertRaises(Parsed):
                round_ledger.main()
        self.assertEqual(record.call_args.args[1], self.rows)
        self.assertFalse((self.root / "unused.json").exists())

    def test_route_cli_preserves_text_without_http_or_real_credentials(self):
        token = self.root / "synthetic-token.txt"
        token.write_text("invented-not-a-credential", encoding="utf-8")
        argv = ["--base", "http://synthetic.invalid", "--token-file", str(token),
                "--candidates", str(self.path), "--output", str(self.root / "unused.json")]
        with patch.object(run_boundary_route, "post", return_value={}) as post, \
                patch.object(run_boundary_route, "score", side_effect=Parsed) as score:
            with self.assertRaises(Parsed):
                run_boundary_route.main(argv)
        self.assertEqual([call.args[2] for call in post.call_args_list], [r["text"] for r in self.rows])
        self.assertEqual(score.call_args.args[0], self.rows)
        self.assertFalse((self.root / "unused.json").exists())

    def test_boundary_fixture_helper_reads_synthetic_unicode_gold(self):
        with patch.object(test_boundary, "HERE", self.root / "boundary"):
            self.assertEqual(test_boundary.out("propose_automation"), self.rows[0]["target"])
        self.assertEqual(self.path.read_bytes(), self.raw)
        self.path.write_text(json.dumps(self.rows[1]) + "\n\n", encoding="utf-8")
        with patch.object(test_boundary, "HERE", self.root / "boundary"), self.assertRaises(StopIteration):
            test_boundary.out("propose_automation")


if __name__ == "__main__":
    unittest.main()
