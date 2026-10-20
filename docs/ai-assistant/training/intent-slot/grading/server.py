"""Local human review of gold fixtures. No model calls, training or execution."""
import argparse
import copy
import hashlib
import importlib.util
import inspect
import json
import secrets
import subprocess
import sys
import threading
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse

HERE = Path(__file__).resolve().parent


def digest(data):
    return hashlib.sha256(data).hexdigest()


def semantic_vocabulary(package):
    """The parts of concepts.json and areas.json that decide whether an answer is valid: ids and aliases only."""
    parts = []
    concepts = package / "concepts.json"
    if concepts.exists():
        data = json.loads(concepts.read_text(encoding="utf-8"))
        parts.append(["concepts", sorted((c["id"], sorted(c.get("aliases", []))) for c in data["concepts"])])
    areas = package / "areas.json"
    if areas.exists():
        data = json.loads(areas.read_text(encoding="utf-8"))
        parts.append(["areas", sorted((a["id"], sorted(a.get("aliases", []))) for a in data["areas"]), sorted(data.get("sites", []))])
    return canonical(parts).encode()


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"))


def run_git(cwd, *args, timeout=20):
    """(returncode, stdout, stderr) of a git command; never raises, so a missing git or a slow network is a result, not a crash."""
    try:
        out = subprocess.run(["git", *args], cwd=str(cwd), capture_output=True, text=True, timeout=timeout)
        return out.returncode, (out.stdout or "").strip(), (out.stderr or "").strip()
    except (OSError, subprocess.TimeoutExpired) as exc:
        return 127, "", str(exc)


def default_journal(purpose, dataset_hash, contract_hash):
    """One journal per dataset and contract, so a changed vocabulary or gold starts a fresh journal on its own."""
    return Path.home() / ".fotc-grading" / f"journal-{purpose}-{dataset_hash[:8]}-{contract_hash[:8]}.json"



class ReviewStore:
    def __init__(self, package, state_file, dataset=None, purpose="evaluation-only"):
        self.package = Path(package).resolve()
        self.path = Path(state_file).resolve() if state_file else None
        self.lock = threading.RLock()
        self.purpose = purpose
        self.check_command = None  # tests substitute a stub; None runs the package's own unit tests
        raw = Path(dataset or self.package / "gold.jsonl").read_bytes()
        self.dataset_hash = digest(raw)
        self.rows = [json.loads(line) for line in raw.decode("utf-8-sig").splitlines() if line.strip()]
        self.by_id = {row["id"]: row for row in self.rows}
        if not self.rows or len(self.rows) != len(self.by_id):
            raise ValueError("Gold must contain unique IDs and at least one example")
        # Load the selected package's real validator rather than duplicating its rules.
        spec = importlib.util.spec_from_file_location("grading_contract", self.package / "contract.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        self.validator = module.validate
        # The room/site/need vocabulary lives next to the contract; an older package without it is graded as before.
        vocab_path = self.package / "vocabulary.py"
        self.vocabulary = None
        if vocab_path.exists():
            vspec = importlib.util.spec_from_file_location("grading_vocabulary", vocab_path)
            self.vocabulary = importlib.util.module_from_spec(vspec)
            vspec.loader.exec_module(self.vocabulary)
        self.has_card = "card_target" in inspect.signature(self.validator).parameters
        self.schema = module.SCHEMA
        # What decides whether an approval is still valid: the contract, the schemas, and the vocabulary's ids and aliases.
        # Concept definitions, examples, relations and proposed asks do not change what a correct answer is, so editing
        # them must not throw away graded work.
        raw_files = [f for f in ("contract.py", "vocabulary.py") if (self.package / f).exists()] + sorted(p.name for p in self.package.glob("*.schema.json"))
        self.contract_hash = digest(b"".join((self.package / f).read_bytes() for f in raw_files) + semantic_vocabulary(self.package))
        if self.path is None:
            self.path = default_journal(purpose, self.dataset_hash, self.contract_hash).resolve()
        self.started_fingerprint = self.code_fingerprint(dataset)
        self.dataset_file = Path(dataset or self.package / "gold.jsonl").resolve()
        self.state = {"version": 1, "purpose": purpose, "dataset_sha256": self.dataset_hash,
                      "contract_sha256": self.contract_hash, "reviews": {}, "audit": [],
                      "report": None, "model_reviews": {}}
        if self.path.exists():
            state = json.loads(self.path.read_text(encoding="utf-8"))
            if (state.get("version") != 1 or state.get("purpose", "evaluation-only") != purpose or state.get("dataset_sha256") != self.dataset_hash
                    or state.get("contract_sha256") != self.contract_hash):
                raise ValueError("Saved reviews belong to a different dataset/contract. Choose a new state file; never reuse stale approvals.")
            self.state = state
        elif purpose == "evaluation-only":
            self.seed_from_gold()

    def seed_from_gold(self):
        """A new journal starts with the examples gold already records as approved, so a session only asks about new work.
        An example is carried over only if it still passes the contract and its rooms and needs are already in canonical form."""
        seeded = 0
        for row in self.rows:
            prov = row.get("provenance", {})
            if prov.get("review_status") != "approved":
                continue
            try:
                self.validate(row["target"], row)
                if self.normalized(row["target"])[0] != row["target"]:
                    continue
                if self.vocabulary and self.vocabulary.field_errors(row["target"]):
                    continue
            except Exception:
                continue
            self.state["reviews"][row["id"]] = {"decision": "approved", "target": row["target"], "reviewer": prov.get("reviewer", ""),
                                               "notes": prov.get("review_notes", ""), "reviewed_at": prov.get("reviewed_at", ""),
                                               "revision": 1, "revised": False, "seeded": True}
            seeded += 1
        if seeded:
            self.state["audit"].append({"type": "seeded_from_gold", "count": seeded, "at": datetime.now(timezone.utc).isoformat()})

    def validate(self, target, row):
        if self.has_card:
            self.validator(target, card_target=row.get("card_target"))
        else:
            if row.get("card_target"):
                raise ValueError("Selected validator does not support card targets")
            self.validator(target)

    def persist(self, state):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temp = self.path.with_name(self.path.name + ".tmp")
        temp.write_text(json.dumps(state, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        temp.replace(self.path)
        self.state = state

    def snapshot(self):
        with self.lock:
            extra = {"vocabulary": self.vocabulary.public_vocabulary()} if self.vocabulary else {}
            return copy.deepcopy({**self.state, "rows": self.rows, "schema": self.schema,
                                  "package": str(self.package), "state_file": str(self.path), "purpose": self.purpose, **extra})

    def normalized(self, target):
        """The answer with rooms and sites in canonical form, and the changes that took."""
        if not self.vocabulary:
            return target, []
        return self.vocabulary.normalize_target(target)

    def check_draft(self, data):
        """Live, side-effect-free check of an answer being edited: field errors, contract error, what normalization
        would change, and the needs a direct command supports. The same rules save_review enforces on approval."""
        with self.lock:
            row = self.by_id[data["id"]]
            target = data["target"]
            if not isinstance(target, dict) or not isinstance(target.get("slots"), dict):
                raise ValueError("Expected an answer object with slots")
            normalized, changes = self.normalized(target)
            fields = self.vocabulary.field_errors(normalized) if self.vocabulary else {}
            contract = None
            try:
                self.validate(normalized, row)
            except Exception as exc:  # includes jsonschema.ValidationError
                contract = str(exc).splitlines()[0]
            supports = self.vocabulary.supported_needs(normalized) if self.vocabulary else []
            return {"fields": fields, "contract": contract, "normalized": normalized, "changes": changes,
                    "supports": supports, "ok": not fields and contract is None}

    def save_review(self, data):
        with self.lock:
            row = self.by_id[data["id"]]
            reviewer = str(data.get("reviewer", "")).strip()
            notes = str(data.get("notes", "")).strip()
            decision = data.get("decision")
            if not reviewer or len(reviewer) > 120:
                raise ValueError("Enter your reviewer name (up to 120 characters)")
            if decision not in {"approved", "rejected", "pending"}:
                raise ValueError("Choose approve, reject or pending")
            if len(notes) > 10000:
                raise ValueError("Notes are too long")
            target = data.get("target", row["target"])
            normalized, changes = self.normalized(target)
            if decision == "approved":
                # Approval stores the canonical form; a room that is only spelled differently is not a correction.
                target = normalized
                fields = self.vocabulary.field_errors(target) if self.vocabulary else {}
                if fields:
                    raise ValueError("Fix before approving: " + "; ".join(f"{path}: {message}" for path, message in fields.items()))
                self.validate(target, row)
            revised = target != (self.normalized(row["target"])[0] if decision == "approved" else row["target"])
            if (decision == "rejected" or revised) and not notes:
                raise ValueError("Add a reason for a rejection or corrected answer")
            previous = self.state["reviews"].get(row["id"], {})
            if data.get("revision", 0) != previous.get("revision", 0):
                raise RuntimeError("This example changed in another tab. Reload before saving.")
            review = {"decision": decision, "target": target, "reviewer": reviewer,
                      "notes": notes, "reviewed_at": datetime.now(timezone.utc).isoformat(),
                      "revision": previous.get("revision", 0) + 1, "revised": revised}
            if decision == "approved" and changes:
                review["normalized"] = changes
            next_state = copy.deepcopy(self.state)
            next_state["reviews"][row["id"]] = review
            next_state["audit"].append({"type": "gold_review", "id": row["id"], **review})
            # Changing an expected answer invalidates a model judgment against it.
            if previous.get("target", row["target"]) != target:
                next_state["model_reviews"].pop(row["id"], None)
            self.persist(next_state)
            return review

    def import_report(self, report):
        with self.lock:
            if report.get("gold_sha256") != self.dataset_hash:
                raise ValueError("Report gold hash does not match this dataset. Run evaluation against these exact fixtures first.")
            predictions = report.get("predictions")
            if not isinstance(predictions, dict) or not predictions or set(predictions) - set(self.by_id):
                raise ValueError("Report needs predictions keyed by known fixture IDs")
            report_hash = digest(canonical(report).encode())
            next_state = copy.deepcopy(self.state)
            if (self.state.get("report") or {}).get("sha256") != report_hash:
                next_state["model_reviews"] = {}
            next_state["report"] = {"sha256": report_hash, "data": report}
            next_state["audit"].append({"type": "report_import", "sha256": report_hash,
                                        "at": datetime.now(timezone.utc).isoformat()})
            self.persist(next_state)
            return {"model": report.get("model"), "count": len(predictions)}

    def save_model_review(self, data):
        with self.lock:
            row = self.by_id[data["id"]]
            report = self.state.get("report")
            if not report or row["id"] not in report["data"]["predictions"]:
                raise ValueError("No model response is loaded for this example")
            if data.get("report_sha256") != report["sha256"]:
                raise RuntimeError("The loaded report changed. Reload before grading.")
            review = self.state["reviews"].get(row["id"], {})
            target = review.get("target", row["target"])
            if data.get("gold_revision", 0) != review.get("revision", 0):
                raise RuntimeError("The expected answer changed. Reload before grading.")
            if data.get("decision") not in {"pass", "fail", "unsure"} or not str(data.get("reviewer", "")).strip():
                raise ValueError("Enter a reviewer and select pass, fail or unsure")
            if data["decision"] != "pass" and not str(data.get("notes", "")).strip():
                raise ValueError("Add a reason for fail or unsure")
            entry = {"decision": data["decision"], "reviewer": str(data["reviewer"]).strip(),
                     "notes": str(data.get("notes", "")), "reviewed_at": datetime.now(timezone.utc).isoformat(),
                     "report_sha256": report["sha256"], "target_sha256": digest(canonical(target).encode())}
            next_state = copy.deepcopy(self.state)
            next_state["model_reviews"][row["id"]] = entry
            next_state["audit"].append({"type": "model_review", "id": row["id"], **entry})
            self.persist(next_state)
            return entry

    def code_fingerprint(self, dataset=None):
        """Hash of every file this session depends on: the tool, the contract, the vocabulary and the examples. If it changes
        while the tool runs (a git pull, a finished session), the tool is out of date and must be restarted."""
        names = sorted(p for p in HERE.glob("*") if p.suffix in {".py", ".js", ".html", ".css"})
        names += [self.package / f for f in ("contract.py", "vocabulary.py", "concepts.json", "areas.json", "system.txt")
                  if (self.package / f).exists()]
        names += sorted(self.package.glob("*.schema.json"))
        names.append(Path(dataset or self.package / "gold.jsonl"))
        return digest(b"".join(Path(n).read_bytes() for n in names if Path(n).exists()))

    def counts(self):
        counts = {"approved": 0, "rejected": 0, "later": 0, "pending": 0, "new": 0}
        for row in self.rows:
            review = self.state["reviews"].get(row["id"], {})
            decision = review.get("decision")
            if decision == "approved" and not review.get("seeded"):
                counts["new"] += 1
            key = {"approved": "approved", "rejected": "rejected"}.get(decision, "later" if decision else "pending")
            counts[key] += 1
        return counts

    def preflight(self, fetch=True):
        """The checklist shown above the examples. Each check is ok, warn (does not block) or fail (blocks Done)."""
        checks = []

        def add(cid, status, label, detail="", fix=""):
            checks.append({"id": cid, "status": status, "label": label, "detail": detail, "fix": fix})

        counts = self.counts()
        add("work", "ok", f"{counts['approved']} approved ({counts['new']} new this session), {counts['pending'] + counts['later']} still to review, {counts['rejected']} rejected",
            "Only examples approved or corrected this session are written when you press Done.")
        if self.code_fingerprint(self.dataset_file) != self.started_fingerprint:
            add("restart", "fail", "This tool is out of date", "Files changed on disk since it started (a git pull, or a finished session).",
                "Run start.ps1 again (it stops the old tool for you), or stop the tool (Ctrl+C in its terminal) and start it again.")
        else:
            add("restart", "ok", "This tool is running the current files")
        code, top, _ = run_git(self.package, "rev-parse", "--show-toplevel")
        if code != 0:
            add("git", "warn", "Not inside a git checkout", "Done will still save gold.jsonl here, but it cannot make a branch for you.",
                "Run the tool from a clone of the repository.")
            return {"checks": checks, "blockers": sum(c["status"] == "fail" for c in checks), "repo": None}
        _, branch, _ = run_git(top, "rev-parse", "--abbrev-ref", "HEAD")
        if branch != "main":
            add("branch", "fail", f"You are on branch {branch}, not main", "A grading session must start from the latest main.",
                "Run start.ps1 from a folder that is on main (git switch main), then reload this page.")
        else:
            add("branch", "ok", "On main")
        if fetch:
            fcode, _, ferr = run_git(top, "fetch", "origin", "main", timeout=20)
        else:
            fcode, ferr = 0, ""
        if fcode != 0:
            add("fresh", "warn", "Could not reach GitHub to check for updates", ferr.splitlines()[0] if ferr else "",
                "Run: git pull   before you start, then run start.ps1 again.")
        else:
            _, counted, _ = run_git(top, "rev-list", "--left-right", "--count", "HEAD...origin/main")
            try:
                ahead, behind = (int(x) for x in counted.split())
            except ValueError:
                ahead = behind = 0
            if behind:
                add("fresh", "fail", f"Your copy is {behind} change{'s' if behind != 1 else ''} behind main",
                    "Someone merged work you do not have yet.", "Run start.ps1 again: it does git pull and restarts this tool.")
            elif ahead and branch == "main":
                add("fresh", "warn", f"Your main has {ahead} commit{'s' if ahead != 1 else ''} that GitHub does not", "", "Tell Code before you continue.")
            else:
                add("fresh", "ok", "Up to date with GitHub main")
        rel = str(self.dataset_file.relative_to(Path(top).resolve())) if self.dataset_file.is_relative_to(Path(top).resolve()) else None
        _, dirty, _ = run_git(top, "status", "--porcelain", "--", rel) if rel else (0, "", "")
        if dirty:
            add("clean", "fail", "gold.jsonl has changes that are not saved in git", dirty,
                "Ask Code to commit or discard them first.")
        else:
            add("clean", "ok", "No unsaved changes to gold")
        return {"checks": checks, "blockers": sum(c["status"] == "fail" for c in checks), "repo": top, "branch": branch}

    def finish(self, data=None):
        """Done, move to gold: write the approved examples into gold.jsonl, prove the package still passes its own tests, then
        put them on a new branch and push it for review. Never touches main."""
        with self.lock:
            rows = self.approved_rows(only_new=self.purpose == "evaluation-only")
            if not rows:
                raise ValueError("Nothing new to move. Approve or correct at least one example this session, then press Done.")
            if self.purpose != "evaluation-only":
                out = self.path.with_name(self.path.stem + ".approved.jsonl")
                out.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")
                return {"mode": "candidates", "count": len(rows), "path": str(out),
                        "message": "Approved candidates saved next to your journal. Candidates are never added to gold."}
            report = self.preflight(fetch=True)
            blockers = [c for c in report["checks"] if c["status"] == "fail"]
            if blockers:
                raise RuntimeError("Not ready: " + "; ".join(f"{c['label']}. {c['fix']}" for c in blockers))
            gold = self.dataset_file
            original = gold.read_bytes()
            if digest(original) != self.dataset_hash:
                raise RuntimeError("gold.jsonl changed since this tool started. Stop the tool and start it again.")
            by_id = {r["id"]: r for r in rows}
            merged = [by_id.get(r["id"], r) for r in self.rows]
            temp = gold.with_name(gold.name + ".tmp")
            temp.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in merged), encoding="utf-8")
            temp.replace(gold)
            try:
                command = self.check_command or [sys.executable, "-B", "-m", "unittest", "discover", "-s", str(self.package), "-p", "test_*.py"]
                result = subprocess.run(command, cwd=str(self.package), capture_output=True, text=True, timeout=180)
                tests_ok, tail = result.returncode == 0, (result.stderr or result.stdout).strip().splitlines()[-6:]
            except (OSError, subprocess.TimeoutExpired) as exc:
                tests_ok, tail = False, [str(exc)]
            if not tests_ok:
                gold.write_bytes(original)
                raise ValueError("The package's own checks failed with these examples, so nothing was changed. " + " | ".join(tail))
            reply = {"mode": "gold", "count": len(rows), "tests": "passed", "branch": None, "pushed": False, "pr_url": None,
                     "commands": []}
            top = report.get("repo")
            if top:
                branch = "grading/gold-" + datetime.now().strftime("%Y%m%d-%H%M")
                rel = str(gold.relative_to(Path(top).resolve()))
                steps = [("checkout", "-b", branch), ("add", "--", rel),
                         ("commit", "-m", f"gold: apply grading session ({len(rows)} approved)")]
                for step in steps:
                    code, _, err = run_git(top, *step)
                    if code != 0:
                        reply["commands"] = [f"git {' '.join(x)}" for x in steps[steps.index(step):]] + [f"git push -u origin {branch}"]
                        reply["message"] = f"Gold is updated on disk, but git said: {err.splitlines()[0] if err else 'failed'}"
                        break
                else:
                    reply["branch"] = branch
                    code, _, err = run_git(top, "push", "-u", "origin", branch, timeout=90)
                    reply["pushed"] = code == 0
                    if code == 0:
                        _, remote, _ = run_git(top, "config", "--get", "remote.origin.url")
                        slug = remote.removesuffix(".git").split("github.com")[-1].lstrip(":/")
                        reply["pr_url"] = f"https://github.com/{slug}/compare/main...{branch}?expand=1" if "github.com" in remote else None
                        run_git(top, "checkout", "main")
                        reply["message"] = "Moved to gold on a new branch and pushed it. Open the pull request, then tell Code."
                    else:
                        reply["commands"] = [f"git push -u origin {branch}"]
                        reply["message"] = f"Committed on branch {branch}, but the push failed: {err.splitlines()[0] if err else 'unknown'}"
            else:
                reply["message"] = "Gold is updated in this folder. Tell Code to commit it."
            state = copy.deepcopy(self.state)
            state["audit"].append({"type": "gold_applied", "count": len(rows), "at": datetime.now(timezone.utc).isoformat(),
                                   "branch": reply["branch"]})
            self.persist(state)
            return reply

    def approved_rows(self, only_new=False):
        with self.lock:
            result = []
            for original in self.rows:
                review = self.state["reviews"].get(original["id"], {})
                if review.get("decision") != "approved":
                    continue
                if only_new and review.get("seeded"):
                    continue  # already approved in gold: leave its bytes alone
                self.validate(review["target"], original)
                row = copy.deepcopy(original)
                row["target"] = review["target"]
                row["provenance"].update(review_status="approved", reviewer=review["reviewer"],
                                         reviewed_at=review["reviewed_at"], review_notes=review["notes"],
                                         original_dataset_sha256=self.dataset_hash,
                                         contract_sha256=self.contract_hash, purpose=self.purpose)
                result.append(row)
            return result


class Server(ThreadingHTTPServer):
    def __init__(self, address, store):
        super().__init__(address, Handler)
        self.store = store
        self.token = secrets.token_urlsafe(32)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def send(self, code, content, content_type="application/json; charset=utf-8", filename=None):
        if not isinstance(content, bytes):
            content = json.dumps(content, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(content)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'")
        if filename:
            self.send_header("Content-Disposition", f'attachment; filename="{filename}"')
        self.end_headers()
        self.wfile.write(content)

    def allowed(self, write=False):
        port = self.server.server_port
        hosts = {f"127.0.0.1:{port}", f"localhost:{port}"}
        if self.headers.get("Host") not in hosts:
            self.send(403, {"error": "Local host only"})
            return False
        origin = self.headers.get("Origin")
        if origin and origin not in {f"http://{h}" for h in hosts}:
            self.send(403, {"error": "Local origin only"})
            return False
        if write and self.headers.get("X-Review-Token") != self.server.token:
            self.send(403, {"error": "Reload the grading page before saving"})
            return False
        return True

    def do_GET(self):
        if not self.allowed():
            return
        route = urlparse(self.path).path
        if route == "/api/state":
            self.send(200, {**self.server.store.snapshot(), "token": self.server.token})
        elif route == "/api/preflight":
            self.send(200, self.server.store.preflight())
        elif route == "/api/export/gold":
            rows = self.server.store.approved_rows()
            if not rows:
                self.send(400, {"error": "Approve at least one expected answer before exporting"})
                return
            self.send(200, "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows).encode(),
                      "application/x-ndjson", f"approved-{self.server.store.purpose}-{len(rows)}.jsonl")
        elif route == "/api/export/reviews":
            data = self.server.store.snapshot()
            data.pop("schema")
            data["release_eligible"] = False
            self.send(200, data, filename="poc2-human-reviews.json")
        elif route in {"/", "/app.js", "/session.js", "/style.css"}:
            name = {"/": "index.html", "/app.js": "app.js", "/session.js": "session.js", "/style.css": "style.css"}[route]
            mime = {"/": "text/html; charset=utf-8", "/app.js": "text/javascript; charset=utf-8", "/session.js": "text/javascript; charset=utf-8", "/style.css": "text/css; charset=utf-8"}[route]
            self.send(200, (HERE / name).read_bytes(), mime)
        else:
            self.send(404, {"error": "Not found"})

    def do_POST(self):
        if not self.allowed(write=True):
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 < length <= 4_000_000:
                raise ValueError("Request must be between 1 byte and 4 MB")
            data = json.loads(self.rfile.read(length))
            methods = {"/api/review": self.server.store.save_review,
                       "/api/validate": self.server.store.check_draft,
                       "/api/report": self.server.store.import_report,
                       "/api/model-review": self.server.store.save_model_review,
                       "/api/finish": self.server.store.finish}
            if self.path not in methods:
                self.send(404, {"error": "Not found"})
                return
            self.send(200, methods[self.path](data))
        except RuntimeError as exc:
            self.send(409, {"error": str(exc)})
        except (ValueError, KeyError, TypeError) as exc:
            self.send(400, {"error": str(exc)})
        except Exception as exc:
            # Includes jsonschema.ValidationError; no request content or stack traces in logs.
            self.send(400, {"error": str(exc).splitlines()[0]})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", type=Path, default=HERE.parent,
                        help="Folder containing gold.jsonl and its matching contract.py/schema")
    parser.add_argument("--state", type=Path, help="Private local review journal (default: a per-dataset file under ~/.fotc-grading, so a changed gold or vocabulary starts a fresh one)")
    parser.add_argument("--port", type=int, default=8766)
    parser.add_argument("--dataset", type=Path, help="Explicit candidate JSONL; defaults to package gold")
    parser.add_argument("--purpose", choices=["evaluation-only", "training-candidate"], default="evaluation-only")
    args = parser.parse_args()
    if args.dataset and args.purpose != "training-candidate":
        parser.error("External datasets must remain training-candidate until a separate holdout is curated")
    server = Server(("127.0.0.1", args.port), ReviewStore(args.package, args.state, args.dataset, args.purpose))
    print(f"Grading screen: http://127.0.0.1:{server.server_port}/", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
