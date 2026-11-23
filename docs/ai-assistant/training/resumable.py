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
from pathlib import Path

MANIFEST = "manifest.json"
MARKER = "COMMITTED"
PAYLOAD = "payload"
LEASE = "LEASE"
KEEP = 2
MANIFEST_VERSION = 1
# A model.save_pretrained adapter alone is not resumable state (design section 2). These must be in every chunk.
REQUIRED_FILES = ("trainer_state.json", "optimizer.pt", "scheduler.pt", "rng_state.pth")
BINDING_KEYS = ("base_revision", "code_sha256", "lock_sha256", "data_sha256", "environment_id")
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
                   skipped_updates: int, parent: str | None, bindings: dict, cursor: dict) -> dict:
    missing = [name for name in REQUIRED_FILES if not (stage_dir / name).is_file()]
    if missing:
        raise CommitError(f"checkpoint at step {global_step} lacks {missing}; an adapter alone is not resumable state")
    absent = [key for key in BINDING_KEYS if not bindings.get(key)]
    if absent:
        raise CommitError(f"bindings missing {absent}")
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
        "files": [{"path": p.relative_to(stage_dir).as_posix(), "bytes": p.stat().st_size, "sha256": sha256_file(p)}
                  for p in _files(stage_dir)],
    }


def _fault(hook, point):
    if hook is not None:
        hook(point)


def commit(stage_dir: Path, durable_root: Path, *, run_id: str, global_step: int, total_steps: int,
           optimizer_updates: int, bindings: dict, cursor: dict, skipped_updates: int = 0, fault=None) -> dict:
    """Commits STAGE_DIR as the chunk at GLOBAL_STEP. Returns the manifest. Raises CommitError on refusal; an
    interruption anywhere before the marker leaves a partial chunk that latest_verified() ignores."""
    durable_root.mkdir(parents=True, exist_ok=True)
    previous = latest_verified(durable_root, run_id, bindings)
    parent = previous["manifest_sha256"] if previous else None
    if previous and global_step <= previous["manifest"]["global_step"]:
        raise CommitError(f"step {global_step} is not after the last verified step {previous['manifest']['global_step']}")
    manifest = build_manifest(stage_dir, run_id=run_id, global_step=global_step, total_steps=total_steps,
                              optimizer_updates=optimizer_updates, skipped_updates=skipped_updates, parent=parent,
                              bindings=bindings, cursor=cursor)
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


def acquire_lease(durable_root: Path, run_id: str, owner: str, take_over_from: str | None = None) -> Path:
    """One writer per run while a chunk is active. Creates LEASE exclusively and is released when the chunk's process
    ends. A lease left behind by a killed session is taken over only when the caller names that exact owner
    (TAKE_OVER_FROM), a deliberate act by the person running it after checking that session is gone, never automatic."""
    durable_root.mkdir(parents=True, exist_ok=True)
    path = durable_root / f"{run_id}.{LEASE}"
    try:
        with open(path, "x", encoding="utf-8") as handle:
            handle.write(json.dumps({"run_id": run_id, "owner": owner}))
    except FileExistsError:
        held = json.loads(path.read_text(encoding="utf-8")).get("owner")
        if held == owner:
            return path
        if take_over_from is None or take_over_from != held:
            raise CommitError(f"run {run_id} is leased by {held!r}; refusing a second writer (if that session is "
                              f"gone, take over by naming it)")
        path.write_text(json.dumps({"run_id": run_id, "owner": owner, "took_over_from": held}), encoding="utf-8")
    return path


def release_lease(durable_root: Path, run_id: str, owner: str) -> None:
    path = durable_root / f"{run_id}.{LEASE}"
    if path.exists() and json.loads(path.read_text(encoding="utf-8")).get("owner") == owner:
        path.unlink()
