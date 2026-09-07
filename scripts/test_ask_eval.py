"""Meaningful local transport/privacy checks; no cabin services are contacted."""
import json
from http.server import BaseHTTPRequestHandler, HTTPServer
import tempfile
import threading
import unittest
from unittest.mock import patch
from pathlib import Path

import ask_eval as harness


class HarnessTests(unittest.TestCase):
    def test_authentication_cannot_be_sent_to_nonloopback_or_redirect(self):
        for url in ("http://evil.example:80", "http://127.0.0.1:80@evil.example",
                    "http://127.0.0.1:80/?token=x", "https://127.0.0.1:80",
                    "http://localhost:8090", "http://127.0.0.1:80/other"):
            with self.assertRaises(harness.EvalError):
                harness.endpoint(url)
        hits = []

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                hits.append(self.path)
                self.send_response(302)
                self.send_header("Location", "/unexpected")
                self.end_headers()

            def log_message(self, *args):
                pass

        server = HTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            status, body, error = harness.request_ask(
                harness.endpoint(f"http://127.0.0.1:{server.server_port}"), "test", "synthetic", 2)
            self.assertEqual((status, body, error), (302, None, "http_error"))
            self.assertEqual(hits, ["/api/helpdesk/ask"])
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_success_uses_normal_ask_contract(self):
        seen = []

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                seen.append((self.headers.get("Authorization"),
                             json.loads(self.rfile.read(int(self.headers["Content-Length"])))))
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b'{"answer":"fixture","sources":[],"answeredByModel":false}')

            def log_message(self, *args):
                pass

        server = HTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            status, body, error = harness.request_ask(
                harness.endpoint(f"http://127.0.0.1:{server.server_port}"), "question", "synthetic", 2)
            self.assertEqual(status, 200)
            self.assertIsNone(error)
            self.assertFalse(harness.project_response(body, "synthetic")["answeredByModel"])
            self.assertEqual(seen, [("CabinSession synthetic", {"question": "question"})])
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_results_omit_token_pointers_raw_sources_and_private_urls(self):
        body = {"answer": "synthetic-token user@example.test https://a.test/?token=other "
                          "password=synthetic-value",
                "answeredByModel": True, "sources": [
                    {"entityRef": "sensitive-pointer", "chunkType": "CREDENTIAL_POINTER",
                     "content": "vault-entry-synthetic", "source": "private"},
                    {"entityRef": "fixture", "chunkType": "DESCRIPTION",
                     "content": "private-household-data", "source": "manually_curated"}]}
        output = json.dumps(harness.project_response(body, "synthetic-token"))
        for hidden in ("synthetic-token", "user@example.test", "token=other", "synthetic-value",
                       "sensitive-pointer", "vault-entry-synthetic", "private-household-data"):
            self.assertNotIn(hidden, output)
        self.assertIn("manually_curated", output)

    def test_contract_failure_and_subprocess_error_do_not_relay_sensitive_data(self):
        for body in ({}, {"answer": "x", "sources": [], "answeredByModel": "true"}):
            with self.assertRaises(harness.EvalError):
                harness.project_response(body, "")
        with patch("ask_eval.subprocess.run") as run:
            run.return_value.returncode = 1
            run.return_value.stderr = "sensitive diagnostic"
            with self.assertRaisesRegex(harness.EvalError, "details suppressed"):
                harness.capture(["synthetic-command"])

    def test_resident_session_only_selects_active_admin_and_never_mints_identity(self):
        token = "12345678-1234-1234-1234-123456789012"
        with patch("ask_eval.capture", side_effect=["admin@example.test", token]) as capture:
            self.assertEqual(harness.resident_session(), token)
            query = capture.call_args.kwargs["stdin"]
            self.assertIn("BEGIN READ ONLY", query)
            self.assertIn("revoked_at IS NULL AND expires_at > now()", query)
            self.assertIn("admin@example.test", query)
            self.assertNotIn("INSERT", query)
            self.assertNotIn(token, str(capture.call_args))
        with patch("ask_eval.capture", side_effect=["admin@example.test", ""]):
            with self.assertRaises(harness.EvalError):
                harness.resident_session()


if __name__ == "__main__":
    unittest.main()
