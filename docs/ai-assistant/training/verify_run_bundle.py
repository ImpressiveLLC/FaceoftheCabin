#!/usr/bin/env python3
"""Verifies one sealed dev-sft-r1 run directory against the bundle hash posted on #244 (#543 section 2D).

Reads only the run directory and the repository's requirements.lock. Fails on the first mismatch and names it. Reports
INTEGRITY_OK together with the run's status: a failed or aborted run can be INTEGRITY_OK, and that is evidence of a
failure, never of successful training. Only a `completed` run must have every artifact, a clean tree and a passing lock
check.
"""
import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from run_capture import (ATTRIBUTION, BUNDLE, CAPTURE_ARTIFACTS, IMAGE_FIELDS, MANIFEST, REPO_FIELDS,  # noqa: E402
                         TRAINER_ARTIFACTS, fact_problems, lock_check, lock_syntax_problems)

STATUSES = ("completed", "failed", "aborted_preflight")
STAGES = ("preflight", "trainer_started", "trainer_exited", "post_capture")
FACTS = ("repo.json", "image-facts.json", "pip-freeze.txt", "lock-check.json")
# One artifact list shared with run_capture.py: a completed run must have produced every one of them.
REQUIRED_FOR_COMPLETED = TRAINER_ARTIFACTS + CAPTURE_ARTIFACTS
STAGE_FOR_STATUS = {"completed": ("post_capture",), "aborted_preflight": ("preflight",),
                    "failed": ("trainer_started", "trainer_exited")}
_LINE = re.compile(r"([0-9a-f]{64})  (\S(?:.*\S)?)")
_HEX64 = re.compile(r"[0-9a-f]{64}")


class IntegrityError(Exception):
    pass


def _sha(data):
    return hashlib.sha256(data).hexdigest()


def read_bundle(run_dir, posted):
    raw = (run_dir / BUNDLE).read_bytes() if (run_dir / BUNDLE).is_file() else None
    if raw is None:
        raise IntegrityError(f"{BUNDLE} is missing")
    if _sha(raw) != posted:
        raise IntegrityError(f"{BUNDLE} hash {_sha(raw)} does not equal the posted hash {posted}")
    text = raw.decode("utf-8")
    if "\r" in text or (text and not text.endswith("\n")):
        raise IntegrityError(f"{BUNDLE} is not LF-terminated lines")
    listed = {}
    for line in text.split("\n")[:-1]:
        match = _LINE.fullmatch(line)
        if not match:
            raise IntegrityError(f"{BUNDLE} line is malformed: {line!r}")
        digest, rel = match.groups()
        if rel.startswith("/") or ".." in Path(rel).parts or "\\" in rel or rel == BUNDLE:
            raise IntegrityError(f"{BUNDLE} lists an invalid path {rel!r}")
        if rel in listed:
            raise IntegrityError(f"{BUNDLE} lists {rel} twice")
        listed[rel] = digest
    if list(listed) != sorted(listed):
        raise IntegrityError(f"{BUNDLE} is not sorted by path")
    return listed


def check_files(run_dir, listed):
    for rel, digest in listed.items():
        path = run_dir / rel
        if not path.is_file():
            raise IntegrityError(f"listed file {rel} is missing")
        if _sha(path.read_bytes()) != digest:
            raise IntegrityError(f"listed file {rel} has changed")
    present = {p.relative_to(run_dir).as_posix() for p in run_dir.rglob("*") if p.is_file()}
    unlisted = sorted(present - set(listed) - {BUNDLE})
    if unlisted:
        raise IntegrityError(f"unlisted file(s) in the run: {', '.join(unlisted)}")


def check_artifact(name, entry, listed):
    if not isinstance(entry, dict):
        raise IntegrityError(f"artifact {name} has no entry")
    if entry.get("status") == "NOT_PRODUCED":
        if not isinstance(entry.get("reason"), str) or not entry["reason"].strip():
            raise IntegrityError(f"artifact {name} is NOT_PRODUCED without a reason")
        if name in listed or any(rel.startswith(name + "/") for rel in listed):
            raise IntegrityError(f"artifact {name} is marked NOT_PRODUCED but is present")
        return False
    if "sha256" in entry:
        digest = entry["sha256"]
        if not isinstance(digest, str) or not _HEX64.fullmatch(digest):
            raise IntegrityError(f"artifact {name} has a malformed sha256 {digest!r}")
        if name not in listed or listed[name] != digest:
            raise IntegrityError(f"artifact {name} hash does not match the bundle")
        return True
    if "files" in entry and isinstance(entry["files"], dict) and entry["files"]:
        if not all(isinstance(d, str) and _HEX64.fullmatch(d) for d in entry["files"].values()):
            raise IntegrityError(f"artifact {name} has a malformed file hash")
        inside = {rel: d for rel, d in listed.items() if rel.startswith(name + "/")}
        if inside != entry["files"]:
            raise IntegrityError(f"artifact {name} file hashes do not match the bundle")
        return True
    raise IntegrityError(f"artifact {name} is neither produced nor NOT_PRODUCED with a reason")


def load_json(run_dir, name):
    try:
        return json.loads((run_dir / name).read_text(encoding="utf-8"))
    except ValueError as exc:
        raise IntegrityError(f"{name} is not valid JSON ({exc})")


def verify(run_dir, posted, lock_path=HERE / "requirements.lock"):
    """Returns the run's status if its integrity holds; raises IntegrityError naming the first mismatch."""
    run_dir = Path(run_dir)
    listed = read_bundle(run_dir, posted)
    check_files(run_dir, listed)
    if MANIFEST not in listed:
        raise IntegrityError(f"{MANIFEST} is not in the bundle")
    manifest = load_json(run_dir, MANIFEST)
    if not isinstance(manifest, dict):
        raise IntegrityError(f"{MANIFEST} is not a JSON object")
    status, stage = manifest.get("status"), manifest.get("stage")
    if status not in STATUSES or stage not in STAGES:
        raise IntegrityError(f"manifest status {status!r} or stage {stage!r} is not a known value")
    if stage not in STAGE_FOR_STATUS[status]:
        raise IntegrityError(f"manifest stage {stage!r} is inconsistent with status {status!r}")
    if manifest.get("attribution") != ATTRIBUTION:
        raise IntegrityError("manifest attribution is not the exact section 5 string")
    for field in ("started_at", "ended_at", "run_id"):
        if not isinstance(manifest.get(field), str) or not manifest[field].strip():
            raise IntegrityError(f"manifest field {field} is empty or not a string")
    artifacts = manifest.get("artifacts")
    if not isinstance(artifacts, dict):
        raise IntegrityError("manifest has no artifacts map")
    produced = {name: check_artifact(name, entry, listed) for name, entry in artifacts.items()}
    for name in FACTS:
        if name not in artifacts:
            raise IntegrityError(f"artifact {name} is not accounted for (produced or NOT_PRODUCED)")

    if produced.get("lock-check.json"):
        # A produced check is always recomputed and must agree, whatever the run's status.
        if not produced.get("pip-freeze.txt"):
            raise IntegrityError("lock-check.json is present but pip-freeze.txt is not")
        lock_path = Path(lock_path)
        if not lock_path.is_file():
            raise IntegrityError(f"cannot recompute the lock check: {lock_path} is missing")
        lock_text = lock_path.read_text(encoding="utf-8")
        if lock_syntax_problems(lock_text):
            raise IntegrityError(f"cannot recompute the lock check: {lock_path.name} is not a usable pinned lock")
        recomputed = lock_check(lock_text, (run_dir / "pip-freeze.txt").read_text(encoding="utf-8"))
        recorded = load_json(run_dir, "lock-check.json")
        if recorded != recomputed:
            raise IntegrityError("lock-check.json disagrees with the recomputed lock-to-freeze check")
    elif status == "completed":
        raise IntegrityError("a completed run has no lock check")
    # Otherwise the lock check was recorded as NOT_PRODUCED with a reason (checked above): failure evidence only.

    if status == "completed":
        missing = [name for name in REQUIRED_FOR_COMPLETED if not produced.get(name)]
        if missing:
            raise IntegrityError(f"a completed run lacks {', '.join(missing)}")
        repo = load_json(run_dir, "repo.json")
        problems = fact_problems("repo.json", repo, REPO_FIELDS) + fact_problems(
            "image-facts.json", load_json(run_dir, "image-facts.json"), IMAGE_FIELDS)
        if problems:
            raise IntegrityError("a completed run's host facts break the capture contract: " + "; ".join(problems))
        if repo["status_porcelain"] != "":
            raise IntegrityError("a completed run's repo.json shows a dirty tree")
        check = load_json(run_dir, "lock-check.json")
        if not isinstance(check, dict) or check.get("result") != "pass":
            raise IntegrityError("a completed run's lock check is not a pass")
        if manifest.get("exit_code") != 0:
            raise IntegrityError("a completed run's trainer exit code is not 0")
    return status


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    p.add_argument("run_dir", type=Path)
    p.add_argument("posted_bundle_sha256", help="the bundle hash posted on #244 at the time of the run")
    p.add_argument("--lock", type=Path, default=HERE / "requirements.lock")
    args = p.parse_args(argv)
    try:
        status = verify(args.run_dir, args.posted_bundle_sha256, args.lock)
    except (IntegrityError, ValueError, OSError) as exc:
        print(f"INTEGRITY_FAIL: {exc}")
        return 1
    note = "" if status == "completed" else " (integrity of failure evidence only; not successful training)"
    print(f"INTEGRITY_OK status={status}{note}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
