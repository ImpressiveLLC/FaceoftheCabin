"""Offline tests for export_telemetry.py against a throwaway local HTTP server (no real backend, no real data)."""
import gzip
import hashlib
import http.server
import os
import tempfile
import threading
import unittest
from urllib.parse import parse_qs, urlparse

import export_telemetry as ex

GOOD_TOKEN = "test-token-not-real"


def gz_lines(n):
    return gzip.compress(b"".join(b'{"eventId":"e%d"}\n' % i for i in range(n)))


class Handler(http.server.BaseHTTPRequestHandler):
    log = []
    truncate_first = set()  # months whose first request is cut off mid-body
    seen = {}

    def log_message(self, *a):
        pass

    def do_GET(self):
        url = urlparse(self.path)
        q = parse_qs(url.query)
        Handler.log.append((self.headers.get("Authorization"), q))
        if self.headers.get("Authorization") != "CabinSession " + GOOD_TOKEN:
            self.send_response(401)
            self.end_headers()
            return
        month = q["from"][0][:7]
        body = gz_lines(3)
        Handler.seen[month] = Handler.seen.get(month, 0) + 1
        self.send_response(200)
        self.send_header("Content-Type", "application/gzip")
        if month in Handler.truncate_first and Handler.seen[month] == 1:
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body[: len(body) // 2])  # promise more than is sent: the client sees a short read
            self.wfile.flush()
            self.close_connection = True
            return
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class ExportTelemetryTest(unittest.TestCase):
    def setUp(self):
        Handler.log, Handler.truncate_first, Handler.seen = [], set(), {}
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.base = "http://127.0.0.1:%d" % self.server.server_port
        self.tmp = tempfile.TemporaryDirectory()
        os.environ["CABIN_SESSION_TOKEN"] = GOOD_TOKEN

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.tmp.cleanup()

    def run_export(self, *extra):
        return ex.main(["--base-url", self.base, "--out-dir", self.tmp.name, "--from", "2026-08-15", "--to", "2026-10-02",
                        "--retries", "2"] + list(extra))

    def test_month_chunks_cover_the_range_exactly_once(self):
        chunks = ex.month_chunks(ex.parse_day("2026-08-15"), ex.parse_day("2026-10-02"))
        self.assertEqual([c[0] for c in chunks], ["2026-08", "2026-09", "2026-10"])
        self.assertEqual(chunks[0][1].isoformat(), "2026-08-15T00:00:00+00:00")
        self.assertEqual(chunks[0][2], chunks[1][1])  # no gap, no overlap
        self.assertEqual(chunks[2][2].isoformat(), "2026-10-02T00:00:00+00:00")

    def test_downloads_one_file_per_month_with_a_manifest_and_sends_the_token_in_the_header_only(self):
        self.assertEqual(self.run_export(), 0)
        files = sorted(f for f in os.listdir(self.tmp.name) if f.startswith("telemetry-"))
        self.assertEqual(files, ["telemetry-2026-08.jsonl.gz", "telemetry-2026-09.jsonl.gz", "telemetry-2026-10.jsonl.gz"])
        manifest = open(os.path.join(self.tmp.name, "manifest.tsv")).read().splitlines()
        self.assertEqual(len(manifest), 4)
        month, rows, size, sha, name = manifest[1].split("\t")
        self.assertEqual((month, rows), ("2026-08", "3"))
        with open(os.path.join(self.tmp.name, name), "rb") as f:
            self.assertEqual(hashlib.sha256(f.read()).hexdigest(), sha)
        for auth, q in Handler.log:
            self.assertNotIn(GOOD_TOKEN, str(q), "the token must never be in the URL")

    def test_a_second_run_skips_finished_past_months_and_leaves_no_part_files(self):
        self.assertEqual(self.run_export(), 0)
        for month in list(Handler.seen):
            Handler.seen[month] = 0
        # make the files look as if they were written long after those months ended
        for f in os.listdir(self.tmp.name):
            if f.startswith("telemetry-"):
                os.utime(os.path.join(self.tmp.name, f), (4102444800, 4102444800))
        self.assertEqual(self.run_export(), 0)
        self.assertEqual(sum(Handler.seen.values()), 0, "no request on resume when every file is complete")
        self.assertEqual([f for f in os.listdir(self.tmp.name) if f.endswith(".part")], [])

    def test_a_cut_off_download_is_retried_and_never_saved_as_complete(self):
        Handler.truncate_first = {"2026-09"}
        self.assertEqual(self.run_export(), 0)
        self.assertEqual(Handler.seen["2026-09"], 2, "the cut-off response was fetched again")
        with gzip.open(os.path.join(self.tmp.name, "telemetry-2026-09.jsonl.gz"), "rb") as f:
            self.assertEqual(len(f.readlines()), 3)

    def test_a_refused_credential_stops_at_once_with_no_files(self):
        os.environ["CABIN_SESSION_TOKEN"] = "wrong"
        self.assertEqual(self.run_export(), 1)
        self.assertEqual(len(Handler.log), 1, "no retry on 401")
        self.assertEqual([f for f in os.listdir(self.tmp.name) if f.startswith("telemetry-")], [])

    def test_no_token_is_an_error_before_any_request(self):
        del os.environ["CABIN_SESSION_TOKEN"]
        self.assertEqual(self.run_export(), 2)
        self.assertEqual(Handler.log, [])

    def test_filters_are_sent_as_query_parameters(self):
        self.assertEqual(self.run_export("--device-id", "d1,d2", "--field", "temperature"), 0)
        q = Handler.log[0][1]
        self.assertEqual((q["deviceId"], q["field"], q["format"]), (["d1,d2"], ["temperature"], ["jsonl.gz"]))


if __name__ == "__main__":
    unittest.main()
