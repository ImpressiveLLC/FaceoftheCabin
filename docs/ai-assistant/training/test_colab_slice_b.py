"""Colab slice B, offline: environment recorder and lock check, storage preflight, fp16 skipped-update accounting and
the Drive lock probe. Stand-ins only; no torch, GPU, Drive, account or network.

Run from docs/ai-assistant/training:  python -m pytest -q test_colab_slice_b.py
"""
import json
import multiprocessing as mp
from pathlib import Path
from types import SimpleNamespace

import pytest

import colab_env
import drive_lock_probe as probe
import resumable as rs
import train_sft


class FakeTorch:
    def __init__(self, cuda=True, capability=(7, 5), name="Tesla T4"):
        self._cuda, self._cap, self._name = cuda, capability, name
        self.version = SimpleNamespace(cuda="12.4" if cuda else None)
        self.backends = SimpleNamespace(cudnn=SimpleNamespace(is_available=lambda: cuda, version=lambda: 90100))
        outer = self
        self.cuda = SimpleNamespace(
            is_available=lambda: outer._cuda, device_count=lambda: 1, mem_get_info=lambda i: (14 << 30, 15 << 30),
            get_device_properties=lambda i: SimpleNamespace(name=outer._name, total_memory=15 << 30,
                                                            major=outer._cap[0], minor=outer._cap[1]))


def lock_for(rec, tmp_path, **override):
    pins = dict(rec["packages"], **override)
    path = tmp_path / "requirements-colab-cuda.lock"
    path.write_text("".join(f"{k}=={v} \\\n    --hash=sha256:{'0' * 64}\n" for k, v in pins.items() if v),
                    encoding="utf-8")
    return path


def test_record_describes_the_gpu_and_policy():
    rec = colab_env.record(torch=FakeTorch(), smi=lambda: "550.54, Tesla T4, 15360 MiB", now="t1")
    assert rec["gpu"] == {"name": "Tesla T4", "total_memory_bytes": 15 << 30, "compute_capability": "7.5", "count": 1}
    assert rec["policy"]["fp16"] is True and rec["policy"]["bf16"] is False
    assert rec["torch_cuda_build"] == "12.4" and rec["cudnn"] == 90100
    assert rec["environment_id"].startswith("env-")


def test_environment_id_ignores_volatile_fields_and_tracks_identity():
    a = colab_env.record(torch=FakeTorch(), smi=None, now="t1")
    b = colab_env.record(torch=FakeTorch(), smi=None, now="t2")
    assert a["environment_id"] == b["environment_id"]
    b["lock_check"] = {"problems": []}
    assert colab_env.environment_id(b) == a["environment_id"]
    c = colab_env.record(torch=FakeTorch(name="NVIDIA L4", capability=(8, 9)), smi=None, now="t1")
    assert c["environment_id"] != a["environment_id"]
    d = dict(a, packages=dict(a["packages"], torch="9.9.9"))
    assert colab_env.environment_id(d) != a["environment_id"]


def test_lock_check_lists_every_difference(tmp_path):
    rec = colab_env.record(torch=FakeTorch(), smi=None)
    rec["packages"] = {name: "1.0.0" for name in colab_env.PACKAGES}
    assert colab_env.check_lock(rec, lock_for(rec, tmp_path)) == []
    problems = colab_env.check_lock(rec, lock_for(rec, tmp_path, torch="2.7.0"))
    assert problems == ["torch: installed 1.0.0, lock pins 2.7.0"]
    rec_none = dict(rec, packages=dict(rec["packages"], peft=None))
    assert any("peft" in p for p in colab_env.check_lock(rec_none, lock_for(rec, tmp_path)))


@pytest.mark.parametrize("torch_stub,expected", [(FakeTorch(cuda=False), "no CUDA GPU"),
                                                  (FakeTorch(capability=(6, 1)), "below 7.0"),
                                                  (FakeTorch(capability=(10, 0)), None)])
def test_lock_check_requires_a_usable_gpu(tmp_path, torch_stub, expected):
    rec = colab_env.record(torch=torch_stub, smi=None)
    rec["packages"] = {name: "1.0.0" for name in colab_env.PACKAGES}
    problems = colab_env.check_lock(rec, lock_for(rec, tmp_path))
    assert (any(expected in p for p in problems)) if expected else problems == []


def test_train_sft_refuses_a_record_whose_id_is_not_the_bound_one(tmp_path):
    rec = colab_env.record(torch=FakeTorch(), smi=None)
    path = tmp_path / "env.json"
    path.write_text(json.dumps(rec), encoding="utf-8")
    assert train_sft.load_environment_record(path, rec["environment_id"]) == rec
    with pytest.raises(SystemExit, match="hashes to"):
        train_sft.load_environment_record(path, "env-other")
    assert train_sft.load_environment_record(None, "anything") is None


def test_storage_preflight_uses_the_measured_size_when_there_is_one(tmp_path):
    rec = rs.storage_preflight(tmp_path, measured_bytes=1000, estimate_bytes=10, free_bytes=10 ** 12)
    assert rec["basis"] == "measured" and rec["checkpoint_bytes"] == 1000
    assert rec["needed_bytes"] == 3 * 1000 + rs.STORAGE_MARGIN_BYTES
    first = rs.storage_preflight(tmp_path, measured_bytes=None, estimate_bytes=10, free_bytes=10 ** 12)
    assert first["basis"] == "lower_bound_estimate"
    assert "not verified to be the real quota" in first["free_bytes_source"]


def test_storage_preflight_refuses_without_room_for_three_checkpoints(tmp_path):
    size = 500 * 1024 * 1024
    needed = 3 * size + rs.STORAGE_MARGIN_BYTES
    rs.storage_preflight(tmp_path, measured_bytes=size, estimate_bytes=0, free_bytes=needed)
    with pytest.raises(rs.CommitError, match="nothing was trained"):
        rs.storage_preflight(tmp_path, measured_bytes=size, estimate_bytes=0, free_bytes=needed - 1)


def test_checkpoint_bytes_is_the_manifest_total(tmp_path):
    record = {"manifest": {"files": [{"bytes": 3}, {"bytes": 4}]}}
    assert rs.checkpoint_bytes(record) == 7


def test_amp_counter_counts_skipped_updates():
    counter = train_sft.amp_skip_counter(object)
    control = object()
    for skipped in (False, True, False, True, True):
        counter.on_optimizer_step(None, None, control, optimizer=SimpleNamespace(step_was_skipped=skipped))
    counter.on_optimizer_step(None, None, control, optimizer=None)
    assert (counter.observed, counter.skipped) == (6, 3)


def test_lower_bound_checkpoint_bytes_counts_only_trainable_parameters():
    params = [SimpleNamespace(requires_grad=True, numel=lambda: 10), SimpleNamespace(requires_grad=False, numel=lambda: 99)]
    assert train_sft.lower_bound_checkpoint_bytes(SimpleNamespace(parameters=lambda: params)) == 120


def write_intervals(store, rows):
    (store / "probe").mkdir(parents=True, exist_ok=True)
    for label in {r[0] for r in rows}:
        with open(store / "probe" / f"{label}.jsonl", "w", encoding="utf-8") as out:
            for lab, start, end in rows:
                if lab == label:
                    out.write(json.dumps({"label": lab, "token": "t", "start": start, "end": end, "released": True})
                              + "\n")


def test_probe_report_flags_overlap_and_needs_two_runtimes(tmp_path):
    write_intervals(tmp_path / "a", [("a", 0, 1), ("b", 1, 2), ("a", 2, 3)])
    assert probe.report(tmp_path / "a")["verdict"].startswith("NO OVERLAP OBSERVED")
    write_intervals(tmp_path / "b", [("a", 0, 2), ("b", 1, 3)])
    rep = probe.report(tmp_path / "b")
    assert rep["verdict"].startswith("NOT QUALIFIED") and rep["overlaps"][0]["overlap_seconds"] == 1
    write_intervals(tmp_path / "c", [("a", 0, 1), ("a", 1, 2)])
    assert probe.report(tmp_path / "c")["verdict"].startswith(probe.STATUS_UNQUALIFIED)


def _probe_worker(store, label, seconds):
    import drive_lock_probe as p
    p.run(Path(store), label, seconds, hold=0.05)


def test_two_local_processes_never_overlap(tmp_path):
    """The local filesystem baseline. It says nothing about Drive, which stays UNQUALIFIED until measured there."""
    ctx = mp.get_context("spawn")
    procs = [ctx.Process(target=_probe_worker, args=(str(tmp_path), label, 2.0)) for label in ("a", "b")]
    for proc in procs:
        proc.start()
    for proc in procs:
        proc.join(60)
    rep = probe.report(tmp_path)
    assert rep["labels"] == ["a", "b"] and rep["intervals"] > 4
    assert rep["overlaps"] == [] and rep["unreleased"] == 0


def test_the_committed_cuda_lock_pins_every_recorded_package():
    pins = colab_env.lock_pins(Path(__file__).with_name("requirements-colab-cuda.lock"))
    assert set(colab_env.PACKAGES) <= set(pins)
    assert pins["torch"] == "2.7.0+cu126"
    cpu = colab_env.lock_pins(Path(__file__).with_name("requirements.lock"))
    for name in colab_env.PACKAGES:
        if name != "torch":
            assert pins[name] == cpu[name], f"{name}: the CUDA lock must match the CPU lock except for torch"
    text = Path(__file__).with_name("requirements-colab-cuda.lock").read_text(encoding="utf-8")
    assert "UNVERIFIED" in text and "--hash=sha256:" in text


def test_invented_rows_are_synthetic_and_render():
    import qualification as q
    import sft_tokenize as st
    rows = q.invented_rows(5)
    assert [r["id"] for r in rows] == [f"invented-{i:04d}" for i in range(5)]
    for row in rows:
        assert q.INVENTED_NOTE in row["messages"][0]["content"]
        segments = st.render_segments(row["messages"])
        assert any(trainable for _, trainable in segments)
    with pytest.raises(ValueError):
        q.invented_rows(0)


def test_invented_rows_cli_never_overwrites(tmp_path):
    import qualification as q
    out = tmp_path / "rows.jsonl"
    assert q.main(["invented-rows", "--out", str(out), "--rows", "3"]) == 0
    assert len(out.read_text(encoding="utf-8").splitlines()) == 3
    with pytest.raises(FileExistsError):
        q.main(["invented-rows", "--out", str(out), "--rows", "3"])


def test_the_loss_gate(tmp_path):
    import qualification as q
    whole = {1: (2.0, 1e-4), 2: (1.5, 5e-5)}
    assert q.compare_losses(whole, dict(whole)) == []
    assert q.compare_losses(whole, {1: (2.0 + 5e-5, 1e-4), 2: (1.5, 5e-5)}) == []
    assert any("outside" in p for p in q.compare_losses(whole, {1: (2.01, 1e-4), 2: (1.5, 5e-5)}))
    assert any("learning rate" in p for p in q.compare_losses(whole, {1: (2.0, 2e-4), 2: (1.5, 5e-5)}))
    assert any("non-finite" in p for p in q.compare_losses(whole, {1: (float("nan"), 1e-4), 2: (1.5, 5e-5)}))
    assert any("steps differ" in p for p in q.compare_losses(whole, {1: (2.0, 1e-4)}))
    run = tmp_path / "r"
    run.mkdir()
    (run / "loss.jsonl").write_text('{"step": 1, "loss": 2.0, "learning_rate": 0.0001}\n{"train_runtime": 3}\n',
                                    encoding="utf-8")
    assert q.losses(run) == {1: (2.0, 0.0001)}


def test_the_adapter_gate():
    import qualification as q
    assert q.adapter_problems(1e-5, 1.0) == []
    assert q.adapter_problems(2e-4, 1.0) != []
    assert q.adapter_problems(0.0, 0.0) == [] and q.adapter_problems(1e-3, 0.0) != []


def test_the_notebook_is_a_thin_person_run_driver():
    nb = json.loads(Path(__file__).with_name("colab_qualification.ipynb").read_text(encoding="utf-8"))
    code = "\n".join("".join(c["source"]) for c in nb["cells"] if c["cell_type"] == "code")
    text = "\n".join("".join(c["source"]) for c in nb["cells"])
    assert all(c.get("outputs") == [] for c in nb["cells"] if c["cell_type"] == "code"), "no stored outputs"
    for forbidden in ("userdata", "getpass", "token", "api_key", "password", "HF_TOKEN", "glob(", "os.walk",
                      "Trainer(", "get_peft_model", "sft_dataset.jsonl", "auth.authenticate_user"):
        assert forbidden not in code, f"notebook code must not contain {forbidden!r}"
    assert "UNQUALIFIED" in text and "bf16=False" in text and "invented" in text
    assert "--require-hashes" in code and "requirements-colab-cuda.lock" in code
    assert code.count("train_sft.py --data /content/invented.jsonl") == 3
