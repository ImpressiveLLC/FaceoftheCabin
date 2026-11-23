#!/usr/bin/env python3
"""Durable chunk commits for resumable training (Colab slice A; Codex design r1 on #244, 7097565930, accepted by Cowork
7098353203). Pure Python: no torch, no network, no account. It moves bytes between two local directories and proves
what it moved; the notebook (slice B) decides what those directories are.

The protocol, per chunk:
  1. The trainer writes a full resumable checkpoint (adapter, optimizer, scheduler, RNG, trainer state, scaler) to a
     local staging directory.
  2. commit() copies every file into DURABLE/<chunk_id>/payload/, writes manifest.json (every file's bytes and SHA-256,
     bound to the run id, global step, parent chunk, base/code/lock/data hashes and environment id), re-reads and
     verifies every byte at the destination, and only then writes the COMMITTED marker, LAST.
  3. latest_verified() returns the newest chunk whose marker, manifest and payload all verify, checking lineage
     (parent hashes, strictly increasing steps) and the bindings. A chunk without a marker is a partial upload and is
     ignored. A marker whose chunk does not verify, a wrong lineage or a wrong binding is a FAILURE (LineageError),
     never a silent skip: corruption is not an interruption.

Never deletes a verified chunk to make room for an unverified one; prune() keeps the newest KEEP verified chunks and
runs only after a successful commit. One writer per run: acquire_lease() creates LEASE exclusively.
"""
import hashlib
import json
import os
import shutil
import uuid
from pathlib import Path

MANIFEST = "manifest.json"
MARKER = "COMMITTED"
PAYLOAD = "payload"
LEASE = "LEASE"
KEEP = 2
MANIFEST_VERSION = 1
# A full Trainer checkpoint of a PEFT model (design section 2). An adapter alone is not resumable, and neither is
# optimizer state without the adapter weights. scaler.pt is required too when the run uses fp16 (bindings["fp16"]).
REQUIRED_FILES = ("adapter_model.safetensors", "adapter_config.json", "trainer_state.json", "optimizer.pt",
                  "scheduler.pt", "rng_state.pth")
FP16_REQUIRED_FILES = ("scaler.pt",)
# Everything a chunk must share with the run it resumes. total_steps fixes the LR schedule horizon; seed fixes the
# sampler order; training_args_sha256 covers the other invariant TrainingArguments; fp16 decides the scaler. Chunk
# size is deliberately NOT bound: it may change between sessions.
BINDING_KEYS = ("base_revision", "code_sha256", "lock_sha256", "data_sha256", "environment_id", "total_steps", "seed",
                "training_args_sha256", "fp16")
FAULT_POINTS = ("before_copy", "mid_copy", "after_verify", "after_marker")


class LineageError(Exception):
    """A durable store that cannot be trusted: corruption, a broken lineage, or a binding mismatch."""


class CommitError(Exception):
    """A commit that was refused or could not complete; the previous verified chunk is untouched."""


class SimulatedInterruption(Exception):
    """Raised by a test fault hook to stand in for a killed process or a lost runtime."""


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def canonical(obj) -> bytes:
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("ascii")


def chunk_id(run_id: str, global_step: int) -> str:
    if not run_id or any(c in run_id for c in "/\\. ") or global_step < 0:
        raise CommitError(f"bad run id {run_id!r} or step {global_step}")
    return f"{run_id}-step{global_step:08d}"


def _files(root: Path) -> list[Path]:
    return sorted(p for p in root.rglob("*") if p.is_file())


def build_manifest(stage_dir: Path, *, run_id: str, global_step: int, total_steps: int, optimizer_updates: int,
                   skipped_updates: int, parent: str | None, bindings: dict, cursor: dict,
                   evidence: dict | None = None) -> dict:
    absent = [key for key in BINDING_KEYS if bindings.get(key) is None or bindings.get(key) == ""]
    if absent:
        raise CommitError(f"bindings missing {absent}")
    if total_steps != bindings["total_steps"]:
        raise CommitError(f"total_steps {total_steps} differs from the run's bound budget {bindings['total_steps']}")
    required = REQUIRED_FILES + (FP16_REQUIRED_FILES if bindings["fp16"] else ())
    missing = [name for name in required if not (stage_dir / name).is_file()]
    if missing:
        raise CommitError(f"checkpoint at step {global_step} lacks {missing}; it is not a complete resumable checkpoint")
    try:
        saved_step = json.loads((stage_dir / "trainer_state.json").read_text(encoding="utf-8"))["global_step"]
    except (OSError, ValueError, KeyError, TypeError) as exc:
        raise CommitError(f"trainer_state.json at step {global_step} is unreadable ({exc})") from exc
    if saved_step != global_step:
        raise CommitError(f"trainer_state.json says step {saved_step}, the chunk is step {global_step}")
    return {
        "manifest_version": MANIFEST_VERSION,
        "run_id": run_id,
        "chunk_id": chunk_id(run_id, global_step),
        "global_step": global_step,
        "total_steps": total_steps,
        "run_complete": global_step == total_steps,
        "accumulation_boundary": 0,  # a chunk only ever ends after a completed optimizer update
        "optimizer_updates": optimizer_updates,
        "skipped_updates": skipped_updates,
        "parent_manifest_sha256": parent,
        "bindings": {key: bindings[key] for key in BINDING_KEYS},
        "cursor": cursor,
        "evidence": evidence or {},
        "files": [{"path": p.relative_to(stage_dir).as_posix(), "bytes": p.stat().st_size, "sha256": sha256_file(p)}
                  for p in _files(stage_dir)],
    }


def _fault(hook, point):
    if hook is not None:
        hook(point)


def commit(stage_dir: Path, durable_root: Path, *, run_id: str, global_step: int, total_steps: int,
           optimizer_updates: int, bindings: dict, cursor: dict, skipped_updates: int = 0, fault=None,
           evidence: dict | None = None) -> dict:
    """Commits STAGE_DIR as the chunk at GLOBAL_STEP. Returns the manifest. Raises CommitError on refusal; an
    interruption anywhere before the marker leaves a partial chunk that latest_verified() ignores."""
    durable_root.mkdir(parents=True, exist_ok=True)
    previous = latest_verified(durable_root, run_id, bindings)
    parent = previous["manifest_sha256"] if previous else None
    if previous and global_step <= previous["manifest"]["global_step"]:
        raise CommitError(f"step {global_step} is not after the last verified step {previous['manifest']['global_step']}")
    manifest = build_manifest(stage_dir, run_id=run_id, global_step=global_step, total_steps=total_steps,
                              optimizer_updates=optimizer_updates, skipped_updates=skipped_updates, parent=parent,
                              bindings=bindings, cursor=cursor, evidence=evidence)
    dest = durable_root / manifest["chunk_id"]
    if (dest / MARKER).exists():
        raise CommitError(f"{dest.name} is already committed; refusing a second writer for the same step")
    if dest.exists():
        shutil.rmtree(dest)  # an earlier partial upload of this same chunk; it never had a marker
    payload = dest / PAYLOAD
    payload.mkdir(parents=True)
    _fault(fault, "before_copy")
    try:
        for index, entry in enumerate(manifest["files"]):
            target = payload / entry["path"]
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(stage_dir / entry["path"], target)
            if index == 0:
                _fault(fault, "mid_copy")
        manifest_bytes = canonical(manifest)
        (dest / MANIFEST).write_bytes(manifest_bytes)
        problems = _verify_payload(dest, manifest)
        if problems:
            raise CommitError(f"destination did not verify after copy: {problems[:3]}")
    except OSError as exc:  # disk full, quota, permission: the chunk stays partial and unmarked
        raise CommitError(f"copy to {dest} failed ({exc}); previous verified chunk retained") from exc
    _fault(fault, "after_verify")
    marker = {"chunk_id": manifest["chunk_id"], "manifest_sha256": hashlib.sha256(manifest_bytes).hexdigest()}
    tmp = dest / (MARKER + ".tmp")
    tmp.write_bytes(canonical(marker))
    os.replace(tmp, dest / MARKER)
    _fault(fault, "after_marker")
    prune(durable_root, run_id, bindings)
    return manifest


STORAGE_MARGIN_BYTES = 256 * 1024 * 1024
STORAGE_CHECKPOINTS = KEEP + 1  # the retained verified chunks plus the one being written


def checkpoint_bytes(record: dict) -> int:
    """The measured size of a committed chunk: the sum of its manifest's file sizes."""
    return sum(entry["bytes"] for entry in record["manifest"]["files"])


NOT_MEASURED = "NOT_MEASURED"


def storage_preflight(durable_root: Path, *, measured_bytes: int | None, sizing: dict | None = None,
                      sizing_run: bool = False, free_bytes: int | None = None,
                      remote_quota: dict | None = None) -> dict:
    """Refuses to start a chunk unless the store has room for KEEP verified chunks plus the in-flight one plus a margin,
    sized from a MEASURED checkpoint (Codex 7105613707): the newest committed chunk of this run, or else a sizing record
    from the separately approved sizing run on the same environment and model. With neither, the size is NOT_MEASURED
    and the run is refused; no estimate, and no tiny CPU checkpoint, is ever substituted. The sizing run itself
    (SIZING_RUN) is the one run allowed without a size, because it produces the measurement; it still needs the margin.

    Free space is recorded twice and separately: what the local mount reports, and the account's remote quota as
    entered from the provider (REMOTE_QUOTA), or NOT_MEASURED. The smaller known figure is used."""
    local_free = shutil.disk_usage(durable_root).free if free_bytes is None else free_bytes
    remote_free = remote_quota.get("free_bytes") if remote_quota else None
    free = min(local_free, remote_free) if remote_free is not None else local_free
    if measured_bytes:
        basis, size = "measured_this_run", measured_bytes
    elif sizing:
        basis, size = "sizing_run_measurement", int(sizing["checkpoint_bytes"])
    elif sizing_run:
        basis, size = NOT_MEASURED + " (this is the sizing run)", 0
    else:
        raise CommitError("checkpoint size is NOT_MEASURED: run the separately approved sizing run first and pass its "
                          "record; no estimate is substituted. Nothing was trained.")
    needed = STORAGE_CHECKPOINTS * size + STORAGE_MARGIN_BYTES
    record = {"basis": basis, "checkpoint_bytes": size or None, "checkpoints_reserved": STORAGE_CHECKPOINTS,
              "margin_bytes": STORAGE_MARGIN_BYTES, "needed_bytes": needed,
              "local_free_bytes": local_free,
              "local_free_source": "shutil.disk_usage of the store's mount; not the account quota on a Drive mount",
              "remote_quota": remote_quota or NOT_MEASURED, "free_bytes_used": free,
              "sizing_source": sizing.get("chunk_id") if sizing else None}
    if free < needed:
        raise CommitError(f"store has {free} bytes free, needs {needed} ({STORAGE_CHECKPOINTS} x {size} {basis} + "
                          f"{STORAGE_MARGIN_BYTES} margin); nothing was trained")
    return record


def sizing_record(durable_root: Path, run_id: str, bindings: dict) -> dict:
    """The measured size of the newest committed chunk of a sizing run, bound to its environment and model, for use by
    other runs' preflights."""
    latest = latest_verified(durable_root, run_id, bindings)
    if latest is None:
        raise CommitError(f"no committed sizing chunk for {run_id}")
    return {"checkpoint_bytes": checkpoint_bytes(latest), "chunk_id": latest["manifest"]["chunk_id"],
            "manifest_sha256": latest["manifest_sha256"],
            "binds": {k: bindings[k] for k in ("base_revision", "code_sha256", "lock_sha256", "environment_id")}}


def check_sizing(sizing: dict, bindings: dict) -> None:
    """A size measured on a different model, code, lock or environment is not this run's size."""
    for key, value in sizing.get("binds", {}).items():
        if bindings.get(key) != value:
            raise CommitError(f"sizing record was measured with {key}={value!r}, this run has {bindings.get(key)!r}")
    if set(sizing.get("binds", {})) != {"base_revision", "code_sha256", "lock_sha256", "environment_id"}:
        raise CommitError("sizing record does not say what it was measured with")


def _verify_payload(dest: Path, manifest: dict) -> list[str]:
    problems = []
    listed = set()
    for entry in manifest["files"]:
        path = dest / PAYLOAD / entry["path"]
        listed.add(entry["path"])
        if not path.is_file():
            problems.append(f"missing {entry['path']}")
        elif path.stat().st_size != entry["bytes"] or sha256_file(path) != entry["sha256"]:
            problems.append(f"changed {entry['path']}")
    extra = [p.relative_to(dest / PAYLOAD).as_posix() for p in _files(dest / PAYLOAD)
             if p.relative_to(dest / PAYLOAD).as_posix() not in listed]
    problems.extend(f"unlisted {name}" for name in extra)
    return problems


def _read_committed(dest: Path) -> dict:
    """The verified record of one marked chunk, or LineageError if a marked chunk does not verify."""
    try:
        marker = json.loads((dest / MARKER).read_bytes())
        manifest_bytes = (dest / MANIFEST).read_bytes()
        manifest = json.loads(manifest_bytes)
    except (OSError, ValueError) as exc:
        raise LineageError(f"{dest.name}: marked but unreadable ({exc})") from exc
    manifest_sha = hashlib.sha256(manifest_bytes).hexdigest()
    if marker.get("manifest_sha256") != manifest_sha or marker.get("chunk_id") != dest.name \
            or manifest.get("chunk_id") != dest.name:
        raise LineageError(f"{dest.name}: marker does not match its manifest")
    problems = _verify_payload(dest, manifest)
    if problems:
        raise LineageError(f"{dest.name}: committed chunk is corrupt: {problems[:3]}")
    return {"dir": dest, "manifest": manifest, "manifest_sha256": manifest_sha}


def verified_chain(durable_root: Path, run_id: str, bindings: dict) -> list[dict]:
    """Every committed chunk of RUN_ID, oldest first, after checking each one and the lineage between them."""
    if not durable_root.is_dir():
        return []
    chain = []
    for dest in sorted(durable_root.glob(f"{run_id}-step*")):
        if not dest.is_dir() or not (dest / MARKER).exists():
            continue  # partial upload or an orphan directory: not part of the lineage
        chain.append(_read_committed(dest))
    chain.sort(key=lambda c: c["manifest"]["global_step"])
    for index, record in enumerate(chain):
        manifest = record["manifest"]
        if manifest.get("run_id") != run_id:
            raise LineageError(f"{record['dir'].name}: run id {manifest.get('run_id')!r} is not {run_id!r}")
        if manifest.get("bindings") != {key: bindings.get(key) for key in BINDING_KEYS}:
            raise LineageError(f"{record['dir'].name}: bindings differ from this run's (base, code, lock, data or "
                               "environment); an incompatible session starts a new lineage, it does not resume")
        if index == 0:
            continue  # its parent may be a pruned chunk; lineage is checked between retained neighbours

        previous = chain[index - 1]
        if manifest["global_step"] <= previous["manifest"]["global_step"]:
            raise LineageError(f"{record['dir'].name}: duplicate or non-increasing global step")
        if manifest.get("parent_manifest_sha256") != previous["manifest_sha256"]:
            raise LineageError(f"{record['dir'].name}: parent hash does not match {previous['dir'].name}")
    return chain


def latest_verified(durable_root: Path, run_id: str, bindings: dict):
    chain = verified_chain(durable_root, run_id, bindings)
    return chain[-1] if chain else None


def prune(durable_root: Path, run_id: str, bindings: dict, keep: int = KEEP) -> list[str]:
    """Removes verified chunks older than the newest KEEP, and unmarked partial directories older than the newest
    verified chunk. Never removes the newest verified chunk."""
    chain = verified_chain(durable_root, run_id, bindings)
    removed = []
    for record in chain[:-keep] if len(chain) > keep else []:
        shutil.rmtree(record["dir"])
        removed.append(record["dir"].name)
    if chain:
        newest = chain[-1]["dir"].name
        for dest in durable_root.glob(f"{run_id}-step*"):
            if dest.is_dir() and not (dest / MARKER).exists() and dest.name < newest:
                shutil.rmtree(dest)
                removed.append(dest.name)
    return removed


def restore(record: dict, checkpoint_dir: Path) -> Path:
    """Copies a verified chunk's payload to CHECKPOINT_DIR (which must not exist) and re-verifies the copy."""
    if checkpoint_dir.exists():
        raise CommitError(f"{checkpoint_dir} already exists; restore into a fresh directory")
    shutil.copytree(record["dir"] / PAYLOAD, checkpoint_dir)
    for entry in record["manifest"]["files"]:
        if sha256_file(checkpoint_dir / entry["path"]) != entry["sha256"]:
            raise LineageError(f"restored {entry['path']} does not match the manifest")
    return checkpoint_dir


def _lease_path(durable_root: Path, run_id: str) -> Path:
    return durable_root / f"{run_id}.{LEASE}"


def _guard_path(durable_root: Path, run_id: str) -> Path:
    return durable_root / f"{run_id}.{LEASE}.guard"


def _read_lease(path: Path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        return None


GUARD_WAIT_SECONDS = 5.0

# Test hook: called inside the guard after the lease is read, so a test can attempt a competing transition at the
# exact point a check-then-act race would open. Never set outside tests.
_in_guard_hook = None


class _TransitionGuard:
    """One exclusive guard shared by acquire, takeover and release (Codex 7102640266). Every ownership read and
    mutation happens while holding it, so there is no check-then-act window. It is created exclusively; if it already
    exists, another transition is in progress or a killed process stranded it, and the caller fails closed. A stranded
    guard is cleared only by clear_stranded_guard(), naming its nonce, after the person confirms no session is running.
    This is a local-filesystem guarantee; whether a Drive mount honours exclusive create across runtimes is a slice B
    qualification question, not claimed here."""

    def __init__(self, durable_root: Path, run_id: str):
        self.path = _guard_path(durable_root, run_id)
        self.nonce = uuid.uuid4().hex

    def __enter__(self):
        # A transition holds the guard for milliseconds, so a busy guard is retried for a bounded time; only a guard
        # that stays held (another transition stuck, or a killed process) fails closed. Windows reports a guard that is
        # being created or deleted by another process as PermissionError rather than FileExistsError.
        import time
        deadline = time.monotonic() + GUARD_WAIT_SECONDS
        while True:
            try:
                with open(self.path, "x", encoding="utf-8") as handle:
                    handle.write(self.nonce)
                return self
            except (FileExistsError, PermissionError):
                if time.monotonic() >= deadline:
                    break
                time.sleep(0.01)
        try:
            held = self.path.read_text(encoding="utf-8")
        except OSError:
            held = "?"
        raise CommitError(f"lease transition guard {self.path.name} is held (nonce {held}): another acquire, "
                          "takeover or release is in progress, or a killed process left it. Nothing was changed. "
                          "If no session is running, clear it with clear_stranded_guard naming that nonce.")

    def __exit__(self, *exc):
        # Only this holder can have created the guard while it exists, so it is removed without re-reading it (a read
        # would hold a handle that blocks another process's delete on Windows). A delete blocked by another process's
        # momentary read is retried.
        import time
        deadline = time.monotonic() + GUARD_WAIT_SECONDS
        while True:
            try:
                self.path.unlink()
                return False
            except FileNotFoundError:
                return False
            except PermissionError:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(0.01)


def clear_stranded_guard(durable_root: Path, run_id: str, nonce: str) -> None:
    """Explicit, quiescent recovery of a guard left by a killed process. Refuses unless NONCE is the guard's."""
    path = _guard_path(durable_root, run_id)
    if not path.exists() or path.read_text(encoding="utf-8") != nonce:
        raise CommitError(f"no stranded guard with nonce {nonce!r} for run {run_id}")
    path.unlink()


def acquire_lease(durable_root: Path, run_id: str, label: str, take_over_from: str | None = None) -> str:
    """One active invocation per run. Returns a fresh, invocation-unique token. Any existing lease is refused, even one
    with the same label, because two runtimes can share a directory name. A lease left by a killed session is taken
    over only when the caller names its exact token (TAKE_OVER_FROM), a deliberate act after checking the session is
    gone; never automatic. Both paths run entirely inside the transition guard."""
    durable_root.mkdir(parents=True, exist_ok=True)
    path = _lease_path(durable_root, run_id)
    token = uuid.uuid4().hex
    record = json.dumps({"run_id": run_id, "token": token, "label": label})
    with _TransitionGuard(durable_root, run_id):
        held = _read_lease(path)
        if _in_guard_hook:
            _in_guard_hook("acquire" if take_over_from is None else "takeover")
        if take_over_from is None:
            if held is not None:
                raise CommitError(f"run {run_id} is leased by token {held.get('token')!r} (label "
                                  f"{held.get('label')!r}); refusing a second writer. If that session is gone, take "
                                  "over by naming that token.")
        elif held is None or held.get("token") != take_over_from:
            raise CommitError(f"run {run_id}: the current lease is not token {take_over_from!r} "
                              f"(current: {held and held.get('token')!r}); nothing taken over")
        tmp = path.with_name(f"{path.name}.{token}.tmp")
        tmp.write_text(record, encoding="utf-8")
        os.replace(tmp, path)
    return token


def release_lease(durable_root: Path, run_id: str, token: str) -> bool:
    """Removes the lease only if it is still this invocation's, checked and removed inside the transition guard."""
    path = _lease_path(durable_root, run_id)
    with _TransitionGuard(durable_root, run_id):
        held = _read_lease(path)
        if _in_guard_hook:
            _in_guard_hook("release")
        if held is None or held.get("token") != token:
            return False
        path.unlink()
        return True
