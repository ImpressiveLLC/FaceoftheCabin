"""Synthetic JSONL fixtures only; network, collection and reservation are stopped."""
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

from grading import bind_families as bf, collect_sources as cs, reserve_ohf as ro, review_packets as rp
import import_sources


class Parsed(BaseException):
    """Stop a CLI after its input boundary, before operational work."""


def blob(rows, ending="\n", final=True):
    return (ending.join(json.dumps(r, ensure_ascii=False) for r in rows)
            + (ending if final else "")).encode("utf-8")


class JsonlBoundaries(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.rows = [{"id": "invented-a", "text": "lamp\u2028line\u2029paragraph\u0085next"},
                     {"id": "invented-b", "text": "literal\nnewline"}]

    def test_file_readers_preserve_text_with_lf_crlf_and_optional_terminal_lf(self):
        path = self.root / "invented.jsonl"
        for ending in ("\n", "\r\n"):
            for final in (True, False):
                raw = blob(self.rows, ending, final)
                path.write_bytes(raw)
                for read in (bf.read_jsonl, rp.read_jsonl):
                    with self.subTest(ending=ending, final=final, reader=read.__module__):
                        self.assertEqual(read(path), self.rows)
                self.assertEqual(ro.texts_of(path), [r["text"] for r in self.rows])
                self.assertEqual(path.read_bytes(), raw)

    def test_file_readers_still_ignore_blank_lines_and_reject_malformed_records(self):
        path = self.root / "invented.jsonl"
        for read in (bf.read_jsonl, rp.read_jsonl, ro.texts_of):
            path.write_bytes(b'\n \t\n{"id":"ok","text":"ok"}\n\n')
            self.assertEqual(len(read(path)), 1)
            path.write_bytes(b'{"text":"ok"}\n{"text":broken}\n')
            with self.assertRaises(json.JSONDecodeError):
                read(path)

    def test_reservation_candidate_boundary_preserves_unicode_without_reserving(self):
        candidates = self.root / "synthetic.jsonl"
        candidates.write_bytes(blob(self.rows))
        families = self.root / "families.json"
        families.write_text("{}", encoding="utf-8")
        (self.root / "gold.jsonl").write_bytes(blob(self.rows))
        (self.root / "synthesis-templates.json").write_text("[]", encoding="utf-8")
        argv = ["reserve", "--package", str(self.root), "--candidates", str(candidates),
                "--families", str(families), "--private-output", str(self.root / "unused-private"),
                "--committed-output", str(self.root / "unused-record")]
        with patch.object(sys, "argv", argv), patch.object(ro, "reserve", side_effect=Parsed) as reserve:
            with self.assertRaises(Parsed):
                ro.main()
        self.assertEqual(reserve.call_args.args[0], self.rows)
        self.assertFalse((self.root / "unused-private").exists())
        self.assertEqual(candidates.read_bytes(), blob(self.rows))

    def test_collector_boundaries_preserve_unicode_with_network_stubbed(self):
        (self.root / "gold.jsonl").write_bytes(blob(self.rows))
        upstream = [{"text": "synthetic upstream\u2028line\u2029paragraph", "slots": []}]
        raw = blob(upstream)
        argv = ["collect", "--package", str(self.root), "--output", str(self.root / "output"),
                "--exclude", "ha", "snips"]
        with patch.object(sys, "argv", argv), patch.object(sys, "path", list(sys.path)), \
                patch.object(cs.urllib.request, "urlopen", side_effect=[io.BytesIO(b"synthetic license"), io.BytesIO(raw)]), \
                patch.object(import_sources, "adapt", side_effect=Parsed) as adapt:
            with self.assertRaises(Parsed):
                cs.main()
        self.assertEqual(adapt.call_args.args, ("enfuse", upstream))
        self.assertEqual((self.root / "gold.jsonl").read_bytes(), blob(self.rows))
        self.assertFalse((self.root / "output" / "candidates.jsonl").exists())


if __name__ == "__main__":
    unittest.main()
