#!/usr/bin/env python3
"""Container-side capture for one dev-sft-r1 training run (#543 section 2C). NOT_RUN on any host.

Creates data/runs/<run_id>/ exclusively, copies in the host's pre-run facts (repo.json, image-facts.json from
data/runs-incoming/<run_id>/), records host facts, pip freeze, a lock-to-freeze check and dpkg, runs train_sft.py with
its stdout and stderr captured byte for byte, then always writes run-manifest.json and bundle.sha256 and makes the
run read-only. A preflight problem (missing or malformed host facts, a dirty tree, an empty or mismatched lock, no apt
inventory, missing data, or any run on real data, which stays refused until the S7 train-manifest check exists) aborts
before training but still leaves a sealed bundle as evidence. Any later failure (trainer launch, capture error) is
finalized the same way and recorded as failed, never completed. Only an existing run directory
or a symlinked runs directory leaves nothing behind.

The printed bundle hash is the tamper anchor: post it on #244 at the time of the run. Read-only file modes stop
accidents, not a determined edit. verify_run_bundle.py (separate) recomputes everything from that hash.
"""
import argparse
import hashlib
import json
import os
import platform
import re
import shutil
import stat
import subprocess
import sys
import threading
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
ATTRIBUTION = "agent auditor(s) elected by Codex under owner amendment, not human audit"
MANIFEST = "run-manifest.json"
BUNDLE = "bundle.sha256"
HOST_SUPPLIED = ("repo.json", "image-facts.json")
TRAINER_ARTIFACTS = ("resolved-training-args.json", "loss.jsonl", "adapter")
CAPTURE_ARTIFACTS = ("train.stdout", "train.stderr", "events.jsonl", "host-facts.json", "pip-freeze.txt",
                     "lock-check.json", "dpkg.txt") + HOST_SUPPLIED
_LOCK_LINE = re.compile(r"^([A-Za-z0-9][A-Za-z0-9._-]*)==([^\s\\]+)")
# Every line shape a uv/pip-compile --generate-hashes lock may contain. Anything else is refused (Codex 7061467844).
_LOCK_SHAPES = (re.compile(r""), re.compile(r"#.*"), re.compile(r"    --hash=sha256:[0-9a-f]{64}( \\)?"),
                re.compile(r"    #( .*)?"), re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*==[^\s\\;]+( \\)?"),
                re.compile(r"--[a-z][a-z-]*(=| ).*"))


def now():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def sha256_file(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def canonical_name(name):
    return re.sub(r"[-_.]+", "-", name).lower()


def lock_pins(lock_text):
    """name -> version for every top-level `name==version` line of a uv/pip-compile lock."""
    pins = {}
    for line in lock_text.split("\n"):
        match = _LOCK_LINE.match(line)
        if match:
            pins[canonical_name(match.group(1))] = match.group(2)
    return pins


def lock_syntax_problems(lock_text):
    """Problems that make a lock unusable as the pinned set: an unsupported line (an unpinned or ranged requirement, an
    environment marker, anything not in _LOCK_SHAPES), a package pinned twice to different versions, or no pins."""
    problems, seen = [], {}
    for number, line in enumerate(lock_text.split("\n"), start=1):
        if not any(shape.fullmatch(line) for shape in _LOCK_SHAPES):
            problems.append(f"lock line {number} is not a supported pinned-lock line: {line[:80]!r}")
            continue
        match = _LOCK_LINE.match(line)
        if match:
            name, version = canonical_name(match.group(1)), match.group(2)
            if name in seen and seen[name] != version:
                problems.append(f"lock pins {name} twice ({seen[name]} and {version})")
            seen[name] = version
    if not seen:
        problems.append("lock has no name==version pins")
    return problems


def lock_check(lock_text, freeze_text):
    """Lock to freeze only: every locked pin must be installed at that exact version. Extra freeze lines are listed and
    do not fail."""
    pins = lock_pins(lock_text)
    installed = {}
    for line in freeze_text.split("\n"):
        if "==" in line and not line.startswith("#"):
            name, _, version = line.strip().partition("==")
            installed[canonical_name(name)] = version
    mismatches = [{"package": name, "locked": version, "found": installed.get(name)}
                  for name, version in sorted(pins.items()) if installed.get(name) != version]
    return {"result": "pass" if not mismatches else "mismatch", "locked": len(pins), "mismatches": mismatches,
            "extra_installed": sorted(set(installed) - set(pins))}


def host_facts(use_gpu_hint=None):
    facts = {"python": sys.version, "platform": platform.platform(), "machine": platform.machine(),
             "cpu_count": os.cpu_count()}
    for name, key in (("/proc/cpuinfo", "model name"), ("/proc/meminfo", "MemTotal")):
        try:
            for line in Path(name).read_text(encoding="utf-8", errors="replace").split("\n"):
                if line.startswith(key):
                    facts[key.replace(" ", "_").lower()] = line.split(":", 1)[1].strip()
                    break
        except OSError as exc:
            facts[key.replace(" ", "_").lower()] = f"NOT_PRODUCED: {exc.__class__.__name__}"
    try:
        import torch  # noqa: PLC0415
    except ImportError as exc:
        facts["torch"] = f"NOT_PRODUCED: {exc}"
        facts["dtype_test"] = "NOT_PRODUCED: torch is not importable"
        return facts
    facts["torch"] = torch.__version__
    facts["cuda_available"] = bool(torch.cuda.is_available())
    tests = {}
    dtypes = ["float32"] + (["float16"] if facts["cuda_available"] else [])
    for dtype in dtypes:
        try:
            device = "cuda" if dtype == "float16" else "cpu"
            a = torch.ones((2, 2), dtype=getattr(torch, dtype), device=device)
            tests[dtype] = {"result": "pass", "value": float((a @ a).sum())}
        except Exception as exc:  # noqa: BLE001 - recorded as evidence, never swallowed silently
            tests[dtype] = {"result": "fail", "error": f"{exc.__class__.__name__}: {exc}"}
    facts["dtype_test"] = tests
    return facts


def run_text(cmd):
    """(stdout, None) or (None, reason)."""
    try:
        done = subprocess.run(cmd, capture_output=True, check=False)
    except OSError as exc:
        return None, f"{cmd[0]} not runnable: {exc}"
    if done.returncode != 0:
        return None, f"{' '.join(cmd)} exited {done.returncode}"
    return done.stdout.decode("utf-8", errors="replace"), None


class StreamCaptureError(RuntimeError):
    """A trainer output stream could not be captured; the run is failed, never completed."""


def _write_chunk(sink, chunk):
    sink.write(chunk)


def _pump(stream, sink, name, events, lock, failures, on_failure):
    offset = 0
    try:
        while True:
            chunk = stream.read1(65536) if hasattr(stream, "read1") else stream.read(65536)
            if not chunk:
                break
            _write_chunk(sink, chunk)
            with lock:
                events.append({"t": now(), "event": "chunk", "stream": name, "offset": offset, "length": len(chunk)})
            offset += len(chunk)
    except Exception as exc:  # noqa: BLE001 - handed to the capture finalizer, never swallowed
        with lock:
            failures.append(f"{name}: {exc.__class__.__name__}: {exc}")
            events.append({"t": now(), "event": "stream_error", "stream": name, "offset": offset, "error": str(exc)})
        # Stop the child so it cannot block forever on a pipe nobody is draining any more.
        on_failure()


def run_trainer(cmd, run_dir):
    """Runs CMD with stdout and stderr written unchanged; returns the exit code. Timestamps go only to events.jsonl.
    If either stream cannot be captured, the child is killed and reaped, the partial streams and events are kept, and
    StreamCaptureError reaches the capture finalizer."""
    events, lock, failures = [{"t": now(), "event": "start", "cmd": cmd}], threading.Lock(), []
    code = None
    with open(run_dir / "train.stdout", "xb") as out, open(run_dir / "train.stderr", "xb") as err:
        process = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, cwd=str(HERE))

        def stop():
            try:
                process.kill()
            except OSError:
                pass

        pumps = [threading.Thread(target=_pump, args=(process.stdout, out, "stdout", events, lock, failures, stop)),
                 threading.Thread(target=_pump, args=(process.stderr, err, "stderr", events, lock, failures, stop))]
        try:
            for pump in pumps:
                pump.start()
            for pump in pumps:
                pump.join()
        finally:
            for stream in (process.stdout, process.stderr):
                stream.close()
            code = process.wait()
    events.append({"t": now(), "event": "exit", "code": code})
    write_new(run_dir / "events.jsonl", "".join(json.dumps(e, sort_keys=True) + "\n" for e in events))
    if failures:
        raise StreamCaptureError("trainer output could not be captured: " + "; ".join(failures))
    return code


def write_new(path, text):
    with open(path, "x", encoding="utf-8", newline="\n") as handle:
        handle.write(text)


def write_json(path, value):
    write_new(path, json.dumps(value, sort_keys=True, indent=2) + "\n")


def artifact_entry(run_dir, name, reasons):
    path = run_dir / name
    if path.is_dir():
        files = sorted(p for p in path.rglob("*") if p.is_file())
        if files:
            return {"files": {p.relative_to(run_dir).as_posix(): sha256_file(p) for p in files}}
    elif path.is_file():
        return {"sha256": sha256_file(path)}
    return {"status": "NOT_PRODUCED", "reason": reasons.get(name, "not written by this run")}


def write_bundle(run_dir):
    lines = []
    for path in sorted(p for p in run_dir.rglob("*") if p.is_file()):
        rel = path.relative_to(run_dir).as_posix()
        if rel != BUNDLE:
            lines.append(f"{sha256_file(path)}  {rel}\n")
    write_new(run_dir / BUNDLE, "".join(lines))
    return sha256_file(run_dir / BUNDLE)


def seal(run_dir):
    for path in sorted(run_dir.rglob("*"), reverse=True):
        mode = path.stat().st_mode
        path.chmod(mode & ~(stat.S_IWUSR | stat.S_IWGRP | stat.S_IWOTH))
    run_dir.chmod(run_dir.stat().st_mode & ~(stat.S_IWUSR | stat.S_IWGRP | stat.S_IWOTH))


REPO_FIELDS = {"commit": "nonempty", "status_porcelain": "str", "plan_id": "nonempty", "plan_sha256": "sha256"}
IMAGE_FIELDS = {"image_id": "nonempty", "base_digest": "nonempty"}
_HEX64 = re.compile(r"[0-9a-f]{64}")


def fact_problems(name, value, fields):
    """Problems with one host-supplied fact file against the shared capture contract (also used by the verifier)."""
    if not isinstance(value, dict):
        return [f"{name} is not a JSON object"]
    problems = []
    for field, kind in fields.items():
        item = value.get(field)
        if not isinstance(item, str):
            problems.append(f"{name} field {field} is missing or not a string")
        elif kind == "nonempty" and not item.strip():
            problems.append(f"{name} field {field} is empty")
        elif kind == "sha256" and not _HEX64.fullmatch(item):
            problems.append(f"{name} field {field} is not a SHA-256 hex digest")
    return problems


def capture(*, run_id, runs_dir, incoming_dir, data, seed, lock_path, trainer_cmd=None, max_steps=None,
            data_manifest=None, invented_data=False, freeze_cmd=None, dpkg_cmd=None, facts_fn=host_facts):
    """Captures one run and returns (run_dir, bundle_sha256, manifest). Raises SystemExit, writing nothing, only when
    the run directory cannot be created exclusively. After that, every failure is recorded and finalized: the manifest
    and bundle are always written, and nothing incomplete is labelled completed."""
    # Resolve every filesystem argument at entry, before anything (the trainer subprocess included) changes directory.
    runs_dir, incoming_dir = Path(runs_dir).absolute(), Path(incoming_dir).absolute()
    data, lock_path = Path(data).absolute(), Path(lock_path).absolute()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", run_id or ""):
        raise SystemExit(f"run id {run_id!r} is not a plain name; nothing was written")
    if runs_dir.is_symlink():
        raise SystemExit(f"{runs_dir} is a symlink; nothing was written")
    runs_dir.mkdir(parents=True, exist_ok=True)
    run_dir = runs_dir / run_id
    try:
        os.mkdir(run_dir)
    except FileExistsError:
        raise SystemExit(f"{run_dir} already exists; earlier evidence is never reopened and nothing was written")

    started = now()
    reasons, problems = {}, []
    state = {"stage": "preflight", "exit_code": None, "status": None, "capture_error": None}
    repo = None
    data_sha = None
    try:
        for name, fields in (("repo.json", REPO_FIELDS), ("image-facts.json", IMAGE_FIELDS)):
            source = incoming_dir / name
            if not (source.is_file() and not source.is_symlink()):
                reasons[name] = f"host step did not supply {name} in {incoming_dir}"
                problems.append(reasons[name])
                continue
            shutil.copyfile(source, run_dir / name)
            try:
                value = json.loads((run_dir / name).read_text(encoding="utf-8"))
            except ValueError as exc:
                problems.append(f"{name} is not valid JSON ({exc})")
                continue
            problems.extend(fact_problems(name, value, fields))
            if name == "repo.json" and isinstance(value, dict):
                repo = value
                if isinstance(value.get("status_porcelain"), str) and value["status_porcelain"]:
                    problems.append("repo.json shows a dirty tree; a real run needs a clean, committed checkout")

        write_json(run_dir / "host-facts.json", facts_fn())
        freeze_text, why = run_text(freeze_cmd or [sys.executable, "-m", "pip", "freeze", "--all"])
        if freeze_text is None:
            reasons["pip-freeze.txt"] = reasons["lock-check.json"] = why
            problems.append(why)
        else:
            write_new(run_dir / "pip-freeze.txt", freeze_text)
            if not lock_path.is_file():
                reasons["lock-check.json"] = f"lock file {lock_path} is missing"
                problems.append(reasons["lock-check.json"])
            else:
                lock_text = lock_path.read_text(encoding="utf-8")
                lock_problems = lock_syntax_problems(lock_text)
                if lock_problems:
                    reasons["lock-check.json"] = f"lock file {lock_path.name} is not usable: " + "; ".join(lock_problems)
                    problems.append(reasons["lock-check.json"])
                else:
                    check = lock_check(lock_text, freeze_text)
                    write_json(run_dir / "lock-check.json", check)
                    if check["result"] != "pass":
                        problems.append(f"{len(check['mismatches'])} locked package(s) differ from the installed set")
        dpkg_text, why = run_text(dpkg_cmd or ["dpkg-query", "-W"])
        if dpkg_text is None:
            reasons["dpkg.txt"] = why
            problems.append(f"no apt inventory: {why}")
        else:
            write_new(run_dir / "dpkg.txt", dpkg_text)

        if not invented_data:
            # The S7 train-manifest schema is not implemented yet, so no path is accepted as S7 readiness.
            problems.append("a run on real data is refused until the S7 train-manifest check exists; "
                            "only --invented-data dry runs are supported")
        if data.is_file():
            data_sha = sha256_file(data)
        else:
            problems.append(f"training data {data} does not exist")

        if not problems:
            cmd = (trainer_cmd or [sys.executable, str(HERE / "train_sft.py")]) + [
                "--run-dir", str(run_dir), "--seed", str(seed), "--data", str(data)]
            if max_steps is not None:
                cmd += ["--max-steps", str(max_steps)]
            state["stage"] = "trainer_started"
            state["exit_code"] = run_trainer(cmd, run_dir)
            state["stage"] = "trainer_exited"
    except Exception as exc:  # noqa: BLE001 - recorded as evidence and finalized, never reported as success
        state["capture_error"] = f"{exc.__class__.__name__}: {exc}"
        problems.append(f"capture error at stage {state['stage']}: {state['capture_error']}")

    if state["stage"] == "preflight":
        status = "aborted_preflight"
        for name in TRAINER_ARTIFACTS + ("train.stdout", "train.stderr", "events.jsonl"):
            reasons.setdefault(name, "preflight refused the run: " + "; ".join(problems))
    elif state["capture_error"] is None and state["exit_code"] == 0:
        status = "completed"
    else:
        status = "failed"
        for name in TRAINER_ARTIFACTS + ("train.stdout", "train.stderr", "events.jsonl"):
            reasons.setdefault(name, state["capture_error"] or f"trainer exited {state['exit_code']} before writing it")
    for name in CAPTURE_ARTIFACTS:
        reasons.setdefault(name, "not written: " + ("; ".join(problems) or "capture stopped"))

    manifest = {
        "attribution": ATTRIBUTION,
        "run_id": run_id,
        "status": status,
        "stage": "post_capture" if status == "completed" else state["stage"],
        "problems": problems,
        "capture_error": state["capture_error"],
        "exit_code": state["exit_code"],
        "started_at": started,
        "ended_at": now(),
        "plan": {"id": (repo or {}).get("plan_id"), "sha256": (repo or {}).get("plan_sha256")},
        "commit": (repo or {}).get("commit"),
        "seed": seed,
        "max_steps": max_steps,
        "run_kind": "invented_data_dry_run" if invented_data else "refused_real_data",
        "data": {"name": data.name, "sha256": data_sha, "manifest": None},
        "artifacts": {name: artifact_entry(run_dir, name, reasons) for name in TRAINER_ARTIFACTS + CAPTURE_ARTIFACTS},
    }
    write_json(run_dir / MANIFEST, manifest)
    bundle = write_bundle(run_dir)
    seal(run_dir)
    return run_dir, bundle, manifest


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    p.add_argument("--run-id", required=True)
    p.add_argument("--runs-dir", type=Path, default=HERE / "data" / "runs")
    p.add_argument("--incoming-dir", type=Path, help="default: data/runs-incoming/<run-id>")
    p.add_argument("--data", type=Path, required=True)
    p.add_argument("--seed", type=int, default=20261114)
    p.add_argument("--max-steps", type=int)
    p.add_argument("--lock", type=Path, default=HERE / "requirements.lock")
    p.add_argument("--invented-data", action="store_true", required=True,
                   help="required: only dry runs on invented rows are supported until the S7 train-manifest check exists")
    args = p.parse_args(argv)
    incoming = args.incoming_dir or (args.runs_dir.parent / "runs-incoming" / args.run_id)
    run_dir, bundle, manifest = capture(run_id=args.run_id, runs_dir=args.runs_dir, incoming_dir=incoming,
                                        data=args.data, seed=args.seed, lock_path=args.lock, max_steps=args.max_steps,
                                        invented_data=args.invented_data)
    print(f"{manifest['status']} ({manifest['stage']}) {run_dir}")
    print(f"bundle sha256 {bundle}  <- post this on #244 now")
    return 0 if manifest["status"] == "completed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
