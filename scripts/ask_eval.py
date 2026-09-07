#!/usr/bin/env python3
"""Standard-library-only, sequential client for the existing Ask API.

No application configuration, knowledge, users or device state is changed.
Normal CabinSession authentication renews its expiry. Results need human review.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import time
from datetime import datetime, timezone
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler, ProxyHandler


class EvalError(Exception):
    pass


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def capture(argv, *, stdin=None):
    """Never relay subprocess stderr/stdout when a command fails."""
    result = subprocess.run(argv, input=stdin, capture_output=True, text=True,
                            timeout=30, check=False)
    if result.returncode:
        raise EvalError("Local runtime inspection failed; details suppressed.")
    return result.stdout.strip()


def resident_session():
    """Explicit operator opt-in; token crosses only local process pipes and HTTP."""
    emails = capture(["docker", "exec", "cabin-backend", "printenv", "ADMIN_EMAILS"])
    admins = [value.strip().lower() for value in emails.split(",") if value.strip()]
    if not admins:
        raise EvalError("No configured administrator; no authentication attempted.")
    # Escape SQL literals, pass query via stdin (no credential in argv).
    literals = ",".join("'" + value.replace("'", "''") + "'" for value in admins)
    query = ("BEGIN READ ONLY;\nSELECT token FROM cabin_sessions "
             "WHERE revoked_at IS NULL AND expires_at > now() "
             "AND lower(trim(google_email)) IN (" + literals + ") "
             "ORDER BY expires_at DESC, created_at DESC LIMIT 1;\nROLLBACK;\n")
    token = capture(["docker", "exec", "-i", "cabin-postgres", "psql", "-X", "-qAt",
                     "-U", "cabin", "-d", "cabin", "-v", "ON_ERROR_STOP=1"], stdin=query)
    if not re.fullmatch(r"[0-9a-fA-F-]{36}", token):
        raise EvalError("No active administrator CabinSession; sign in through the app.")
    return token


def endpoint(value):
    parsed = urlsplit(value)
    if (parsed.scheme != "http" or parsed.hostname != "127.0.0.1"
            or parsed.username or parsed.password or parsed.query or parsed.fragment
            or parsed.path.rstrip("/") or parsed.port is None):
        raise EvalError("Use http://127.0.0.1:PORT (M920q loopback or an SSH forward).")
    return value.rstrip("/") + "/api/helpdesk/ask"


def request_ask(url, question, token, timeout):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "CabinSession " + token
    request = Request(url, data=json.dumps({"question": question}).encode(),
                      headers=headers, method="POST")
    # Disable ambient proxies; credentials can only go to the checked loopback URL.
    opener = build_opener(ProxyHandler({}), NoRedirect())
    try:
        with opener.open(request, timeout=timeout) as response:
            data = response.read(262145)
            if len(data) > 262144:
                return response.status, None, "response_too_large"
            try:
                return response.status, json.loads(data), None
            except (ValueError, UnicodeError):
                return response.status, None, "invalid_json"
    except HTTPError as error:
        # Do not read error bodies: proxy/server diagnostics can contain secrets.
        error.close()
        return error.code, None, "http_error"
    except (URLError, TimeoutError, OSError):
        return None, None, "transport_error"


def sanitize(value, token):
    text = str(value).replace(token, "[REDACTED_SESSION]") if token else str(value)
    text = re.sub(r"(?i)(?:Bearer|CabinSession|ManagedSession)\s+\S+",
                  "[REDACTED_AUTH]", text)
    text = re.sub(r"[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}", "[REDACTED_EMAIL]", text)
    text = re.sub(r"\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b",
                  "[REDACTED_JWT]", text)
    text = re.sub(r"(?i)\b(?:password|api[_ -]?key|access[_ -]?token|client[_ -]?secret)"
                  r"\s*[:=]\s*[^\s,;]+", "[REDACTED_CREDENTIAL_ASSIGNMENT]", text)
    text = re.sub(r"https?://[^\s<>]+", scrub_url, text)
    return text


def scrub_url(match):
    value = match.group()
    if "?" in value or "@" in value:
        return "[REDACTED_URL_WITH_QUERY_OR_USERINFO]"
    return value


def project_response(body, token):
    if not isinstance(body, dict) or not isinstance(body.get("answer"), str):
        raise EvalError("invalid_response_contract")
    if not isinstance(body.get("sources"), list) or type(body.get("answeredByModel")) is not bool:
        raise EvalError("invalid_response_contract")
    sources = []
    for item in body["sources"]:
        if not isinstance(item, dict):
            raise EvalError("invalid_source_contract")
        if item.get("chunkType") == "CREDENTIAL_POINTER":
            sources.append({"chunkType": "CREDENTIAL_POINTER", "content": "[OMITTED]"})
            continue
        # Source content/topology are deliberately not retained. Source identity is
        # enough to detect irrelevant device citations; supporting docs are oracles.
        sources.append({key: sanitize(item[key], token)
                        for key in ("entityRef", "chunkType", "source", "generatedAt")
                        if key in item})
    return {"answer": sanitize(body["answer"], token), "sources": sources,
            "answeredByModel": body["answeredByModel"]}


def load_cases(path):
    raw = path.read_bytes()
    cases = json.loads(raw)
    trials = cases.get("questions", [])
    if not trials or len({q["id"] for q in trials}) != len(trials):
        raise EvalError("Question set empty or IDs duplicated.")
    for question in trials:
        if not re.fullmatch(r"Q\d{2}", question["id"]) or not question.get("question"):
            raise EvalError("Invalid question record.")
    return cases, hashlib.sha256(raw).hexdigest()


def runtime_metadata():
    return {
        "hostname": capture(["hostname"]),
        "checkout_sha": capture(["git", "-C", "/home/nate/FaceoftheCabin", "rev-parse", "HEAD"]),
        "backend_image_id": capture(["docker", "inspect", "--format", "{{.Image}}", "cabin-backend"]),
        "model_list": capture(["docker", "exec", "ollama", "ollama", "list"]),
    }


def write_line(handle, value):
    handle.write(json.dumps(value, ensure_ascii=False) + "\n")
    handle.flush()
    os.fsync(handle.fileno())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--questions", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True, help="Private JSONL file; must not exist")
    parser.add_argument("--url", default="http://127.0.0.1:8090")
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--timeout", type=int, default=90)
    parser.add_argument("--delay", type=float, default=1.0)
    auth = parser.add_mutually_exclusive_group(required=True)
    auth.add_argument("--resident-admin-session", action="store_true",
                      help="Use only with explicit operator approval on M920q")
    auth.add_argument("--token-file", type=Path, help="Operator-provided CabinSession, mode 0600")
    auth.add_argument("--denial-only", action="store_true", help="Check unauthenticated denial only")
    parser.add_argument("--record-runtime", action="store_true")
    args = parser.parse_args()
    url = endpoint(args.url)
    if not 1 <= args.repeats <= 3 or not 1 <= args.timeout <= 180 or not 0 <= args.delay <= 30:
        raise EvalError("Invalid repeat, timeout or delay bound.")
    cases, digest = load_cases(args.questions)
    # Never accidentally commit run data, even when invoked from a checkout.
    target = args.output.resolve()
    if any((parent / ".git").exists() for parent in (target.parent, *target.parents)):
        raise EvalError("Result file must be outside any Git checkout.")
    token = ""
    if args.resident_admin_session:
        token = resident_session()
    elif args.token_file:
        if os.name != "nt" and stat.S_IMODE(args.token_file.stat().st_mode) & 0o077:
            raise EvalError("Token file must be accessible only to its owner (0600).")
        token = args.token_file.read_text().strip()
        if not re.fullmatch(r"[0-9a-fA-F-]{36}", token):
            raise EvalError("Expected a CabinSession token; value suppressed.")
    metadata = runtime_metadata() if args.record_runtime else {}
    os.umask(0o077)
    # Refuse overwrites/symlinks and persist every completed request immediately.
    fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        write_line(handle, {"type": "run", "started_at": datetime.now(timezone.utc).isoformat(),
                            "suite": cases["suite"], "question_sha256": digest,
                            "harness_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                            "planned": 0 if args.denial_only else len(cases["questions"]) * args.repeats,
                            "repeats": args.repeats, "url": url, "runtime": metadata,
                            "role": "server-derived; administrator session selected" if args.resident_admin_session
                            else "not asserted by harness", "grading": "human review required"})
        status, _, error = request_ask(url, "Does choosing a profile authenticate me?", "", args.timeout)
        write_line(handle, {"type": "denial_check", "status": status, "passed": status in (401, 403)})
        if status not in (401, 403):
            raise EvalError("Expected unauthenticated denial; stopped before authenticated requests.")
        if args.denial_only:
            print("Unauthenticated Ask denial verified.", flush=True)
            return
        completed = model = fallback = 0
        # Round-robin question ordering reduces repeat-adjacency bias; no automatic
        # retries. A timed-out generation may continue on the server, so stop.
        for repeat in range(1, args.repeats + 1):
            for case in cases["questions"]:
                start = time.monotonic()
                status, body, error = request_ask(url, case["question"], token, args.timeout)
                row = {"type": "trial", "id": case["id"], "repeat": repeat,
                       "question": case["question"], "status": status,
                       "completed_at": datetime.now(timezone.utc).isoformat(),
                       "elapsed_seconds": round(time.monotonic() - start, 3), "grade": "UNREVIEWED"}
                if status == 200 and error is None:
                    try:
                        row.update(project_response(body, token))
                    except EvalError as exc:
                        error = str(exc)
                if error:
                    row["error"] = error
                write_line(handle, row)
                if error or status != 200:
                    raise EvalError("Trial failed; partial results preserved, no automatic retry.")
                completed += 1
                model += int(row["answeredByModel"])
                fallback += int(not row["answeredByModel"])
                print(f"{case['id']} repeat={repeat} HTTP=200 model={row['answeredByModel']} "
                      f"seconds={row['elapsed_seconds']}", flush=True)
                time.sleep(args.delay)
        write_line(handle, {"type": "completed", "executed": completed,
                            "model_backed": model, "fallback": fallback, "pass_rate": None})
        print(f"Completed {completed} trials; model={model}, fallback={fallback}; grading pending.", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (EvalError, OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        # Only our controlled EvalError strings are printable; never dump headers,
        # environment values, SQL data, response bodies or stack traces.
        print(str(error) if isinstance(error, EvalError) else "Harness error; details suppressed.", file=sys.stderr)
        sys.exit(2)
    except KeyboardInterrupt:
        print("Interrupted; completed trials preserved.", file=sys.stderr)
        sys.exit(130)
