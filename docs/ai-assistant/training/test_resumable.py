"""resumable.py: durable chunk commits, lineage and fault handling. Synthetic files only; no torch, model or network.

Run from docs/ai-assistant/training:  python -m pytest -q test_resumable.py
"""
import json
from pathlib import Path

import pytest

import resumable as rs

BINDINGS = {"base_revision": "rev", "code_sha256": "c" * 64, "lock_sha256": "l" * 64, "data_sha256": "d" * 64,
            "environment_id": "env-synthetic"}
RUN = "synthrun"


_STAGES = iter(range(1_000_000))


def stage(tmp: Path, step: int, extra: bytes = b"") -> Path:
    d = tmp / f"stage-{step}-{next(_STAGES)}"
    (d / "adapter").mkdir(parents=True)
    (d / "adapter" / "adapter_model.safetensors").write_bytes(b"adapter" + bytes([step % 256]) + extra)
    (d / "trainer_state.json").write_text(json.dumps({"global_step": step}))
    for name in ("optimizer.pt", "scheduler.pt", "rng_state.pth"):
        (d / name).write_bytes(f"{name}-{step}".encode())
    return d


def do_commit(tmp, store, step, total=10, fault=None, bindings=BINDINGS, run=RUN):
    return rs.commit(stage(tmp, step), store, run_id=run, global_step=step, total_steps=total,
                     optimizer_updates=step, bindings=bindings, cursor={"epoch": 0, "next_row": step}, fault=fault)


def fault_at(point):
    def hook(name):
        if name == point:
            raise rs.SimulatedInterruption(name)
    return hook


def test_commit_then_latest_verified_and_lineage(tmp_path):
    store = tmp_path / "store"
    first = do_commit(tmp_path, store, 2)
    second = do_commit(tmp_path, store, 4)
    latest = rs.latest_verified(store, RUN, BINDINGS)
    assert latest["manifest"]["global_step"] == 4
    assert first["parent_manifest_sha256"] is None
    chain = rs.verified_chain(store, RUN, BINDINGS)
    assert second["parent_manifest_sha256"] == chain[0]["manifest_sha256"]
    assert latest["manifest"]["accumulation_boundary"] == 0
    assert latest["manifest"]["run_complete"] is False


def test_final_chunk_is_marked_complete_and_partial_ones_are_not(tmp_path):
    store = tmp_path / "store"
    assert do_commit(tmp_path, store, 5, total=10)["run_complete"] is False
    assert do_commit(tmp_path, store, 10, total=10)["run_complete"] is True


@pytest.mark.parametrize("point", ["before_copy", "mid_copy", "after_verify"])
def test_interruption_before_the_marker_leaves_the_previous_chunk_as_latest(tmp_path, point):
    store = tmp_path / "store"
    do_commit(tmp_path, store, 2)
    with pytest.raises(rs.SimulatedInterruption):
        do_commit(tmp_path, store, 4, fault=fault_at(point))
    assert rs.latest_verified(store, RUN, BINDINGS)["manifest"]["global_step"] == 2
    # resuming replays the lost work and commits the same step cleanly over the partial directory
    assert do_commit(tmp_path, store, 4)["global_step"] == 4
    assert rs.latest_verified(store, RUN, BINDINGS)["manifest"]["global_step"] == 4


def test_interruption_after_the_marker_is_a_committed_chunk_and_a_second_writer_is_refused(tmp_path):
    store = tmp_path / "store"
    with pytest.raises(rs.SimulatedInterruption):
        do_commit(tmp_path, store, 3, fault=fault_at("after_marker"))
    assert rs.latest_verified(store, RUN, BINDINGS)["manifest"]["global_step"] == 3
    with pytest.raises(rs.CommitError, match="not after the last verified step"):
        do_commit(tmp_path, store, 3)  # the lost acknowledgement must not produce a duplicate step


def test_disk_full_stops_explicitly_and_keeps_the_previous_chunk(tmp_path, monkeypatch):
    store = tmp_path / "store"
    do_commit(tmp_path, store, 2)
    real = rs.shutil.copyfile

    def full(src, dst, *a, **k):
        if "step00000004" in str(dst):
            raise OSError(28, "No space left on device")
        return real(src, dst, *a, **k)

    monkeypatch.setattr(rs.shutil, "copyfile", full)
    with pytest.raises(rs.CommitError, match="previous verified chunk retained"):
        do_commit(tmp_path, store, 4)
    assert rs.latest_verified(store, RUN, BINDINGS)["manifest"]["global_step"] == 2


def test_a_corrupted_committed_chunk_is_a_failure_not_a_skip(tmp_path):
    store = tmp_path / "store"
    do_commit(tmp_path, store, 2)
    do_commit(tmp_path, store, 4)
    victim = store / rs.chunk_id(RUN, 4) / rs.PAYLOAD / "optimizer.pt"
    victim.write_bytes(b"flipped")
    with pytest.raises(rs.LineageError, match="corrupt"):
        rs.latest_verified(store, RUN, BINDINGS)


def test_an_extra_file_in_a_committed_payload_is_corruption(tmp_path):
    store = tmp_path / "store"
    do_commit(tmp_path, store, 2)
    (store / rs.chunk_id(RUN, 2) / rs.PAYLOAD / "planted.bin").write_bytes(b"x")
    with pytest.raises(rs.LineageError, match="unlisted"):
        rs.latest_verified(store, RUN, BINDINGS)


def test_a_tampered_manifest_does_not_match_its_marker(tmp_path):
    store = tmp_path / "store"
    do_commit(tmp_path, store, 2)
    path = store / rs.chunk_id(RUN, 2) / rs.MANIFEST
    manifest = json.loads(path.read_bytes())
    manifest["global_step"] = 9
    path.write_bytes(rs.canonical(manifest))
    with pytest.raises(rs.LineageError, match="marker does not match"):
        rs.latest_verified(store, RUN, BINDINGS)


@pytest.mark.parametrize("key", rs.BINDING_KEYS)
def test_a_wrong_binding_refuses_to_resume(tmp_path, key):
    store = tmp_path / "store"
    do_commit(tmp_path, store, 2)
    wrong = dict(BINDINGS, **{key: "different"})
    with pytest.raises(rs.LineageError, match="bindings differ"):
        rs.latest_verified(store, RUN, wrong)


@pytest.mark.parametrize("name", rs.REQUIRED_FILES)
def test_missing_optimizer_scheduler_rng_or_state_refuses_the_commit(tmp_path, name):
    d = stage(tmp_path, 2)
    (d / name).unlink()
    with pytest.raises(rs.CommitError, match="not resumable state"):
        rs.commit(d, tmp_path / "store", run_id=RUN, global_step=2, total_steps=10, optimizer_updates=2,
                  bindings=BINDINGS, cursor={})


def test_a_broken_parent_link_is_a_lineage_failure(tmp_path):
    store = tmp_path / "store"
    do_commit(tmp_path, store, 2)
    do_commit(tmp_path, store, 4)
    path = store / rs.chunk_id(RUN, 4) / rs.MANIFEST
    manifest = json.loads(path.read_bytes())
    manifest["parent_manifest_sha256"] = "0" * 64
    data = rs.canonical(manifest)
    path.write_bytes(data)
    (store / rs.chunk_id(RUN, 4) / rs.MARKER).write_bytes(rs.canonical(
        {"chunk_id": rs.chunk_id(RUN, 4), "manifest_sha256": rs.hashlib.sha256(data).hexdigest()}))
    with pytest.raises(rs.LineageError, match="parent hash"):
        rs.latest_verified(store, RUN, BINDINGS)


def test_prune_keeps_two_verified_and_drops_old_partials(tmp_path):
    store = tmp_path / "store"
    for step in (1, 2, 3):
        do_commit(tmp_path, store, step)
    with pytest.raises(rs.SimulatedInterruption):
        do_commit(tmp_path, store, 4, fault=fault_at("mid_copy"))
    do_commit(tmp_path, store, 5)
    names = sorted(p.name for p in store.iterdir() if p.is_dir())
    assert names == [rs.chunk_id(RUN, 3), rs.chunk_id(RUN, 5)]
    assert rs.latest_verified(store, RUN, BINDINGS)["manifest"]["global_step"] == 5


def test_restore_copies_and_reverifies(tmp_path):
    store = tmp_path / "store"
    do_commit(tmp_path, store, 2)
    record = rs.latest_verified(store, RUN, BINDINGS)
    out = rs.restore(record, tmp_path / "checkpoint-2")
    assert (out / "optimizer.pt").read_bytes() == b"optimizer.pt-2"
    with pytest.raises(rs.CommitError):
        rs.restore(record, out)


def test_lease_allows_one_owner(tmp_path):
    store = tmp_path / "store"
    rs.acquire_lease(store, RUN, "session-a")
    rs.acquire_lease(store, RUN, "session-a")  # the same owner after an interruption
    with pytest.raises(rs.CommitError, match="leased"):
        rs.acquire_lease(store, RUN, "session-b")
    rs.release_lease(store, RUN, "session-a")
    rs.acquire_lease(store, RUN, "session-b")


def test_a_stale_lease_is_taken_over_only_by_naming_its_owner(tmp_path):
    store = tmp_path / "store"
    rs.acquire_lease(store, RUN, "killed-session")
    with pytest.raises(rs.CommitError, match="leased"):
        rs.acquire_lease(store, RUN, "new-session", take_over_from="some-other-session")
    rs.acquire_lease(store, RUN, "new-session", take_over_from="killed-session")
    with pytest.raises(rs.CommitError, match="leased"):
        rs.acquire_lease(store, RUN, "third-session")


def test_other_runs_are_not_part_of_this_lineage(tmp_path):
    store = tmp_path / "store"
    do_commit(tmp_path, store, 2, run="otherrun")
    assert rs.latest_verified(store, RUN, BINDINGS) is None
