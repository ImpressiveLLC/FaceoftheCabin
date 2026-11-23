"""Uninterrupted versus chunked-and-resumed training on the synthetic harness (Colab slice A, design r1 section 4).

Needs torch, transformers and peft (the training image); SKIPPED elsewhere, and a skip is not a pass. Each chunk runs
in a fresh Python process, so nothing survives in memory between chunks. Tiny random model, invented rows, CPU.

Gate, fixed before results (design r1 section 4): every loss finite; matched-step loss allclose atol 1e-4 / rtol 1e-3;
final adapter relative L2 difference <= 1e-4; identical trained-batch trace, learning-rate schedule and step count.

Run inside the image from docs/ai-assistant/training:  python -m pytest -q test_resume_equivalence.py
"""
import json
import math
import os
import subprocess
import sys
from pathlib import Path

import pytest

pytest.importorskip("torch")
pytest.importorskip("transformers")
pytest.importorskip("peft")

HERE = Path(__file__).resolve().parent
TOTAL, FIRST = 6, 2  # 12 rows, batch 1, accumulation 4: 3 updates per epoch, so the cut is mid-epoch


def chunk(store, run_id, run_dir, total, steps, *extra, check=True):
    env = dict(os.environ, HF_HUB_OFFLINE="1", TRANSFORMERS_OFFLINE="1", PYTHONHASHSEED="0")
    done = subprocess.run([sys.executable, str(HERE / "resume_harness.py"), "--store", str(store), "--run-id", run_id,
                           "--run-dir", str(run_dir), "--total-steps", str(total), "--chunk-steps", str(steps),
                           *extra], check=check, cwd=HERE, env=env, capture_output=not check, text=True)
    if not check:
        return done
    return json.loads((run_dir / "trace.json").read_text(encoding="utf-8"))


def losses(log_history):
    return {e["step"]: (e["loss"], e["learning_rate"]) for e in log_history if "loss" in e}


def final_adapter(store, run_id):
    from safetensors.torch import load_file
    import resumable as rs
    finals = sorted(p for p in Path(store).glob(f"{run_id}-step*") if (p / rs.MARKER).exists())
    files = list((finals[-1] / rs.PAYLOAD).rglob("adapter_model.safetensors"))
    assert len(files) == 1, files
    return load_file(str(files[0]))


@pytest.fixture(scope="module")
def runs(tmp_path_factory):
    tmp = tmp_path_factory.mktemp("resume")
    whole = chunk(tmp / "store-whole", "whole", tmp / "whole-1", TOTAL, TOTAL)
    first = chunk(tmp / "store-chunked", "chunked", tmp / "chunked-1", TOTAL, FIRST)
    second = chunk(tmp / "store-chunked", "chunked", tmp / "chunked-2", TOTAL, TOTAL - FIRST)
    return tmp, whole, first, second


def test_chunk_boundaries_and_completion(runs):
    _, whole, first, second = runs
    assert whole["manifest"]["global_step"] == TOTAL and whole["manifest"]["run_complete"] is True
    assert first["manifest"]["global_step"] == FIRST and first["manifest"]["run_complete"] is False
    assert second["manifest"]["global_step"] == TOTAL and second["manifest"]["run_complete"] is True
    assert second["manifest"]["cursor"]["resumed_from_step"] == FIRST
    assert second["manifest"]["optimizer_updates"] == TOTAL


def test_the_resumed_run_trains_exactly_the_same_batches_in_the_same_order(runs):
    _, whole, first, second = runs
    assert [t["ids_sha256"] for t in first["trace"] + second["trace"]] == [t["ids_sha256"] for t in whole["trace"]]


def test_losses_and_learning_rates_match_step_for_step(runs):
    _, whole, first, second = runs
    expected = losses(whole["log_history"])
    got = losses(first["log_history"]) | losses(second["log_history"])
    assert sorted(got) == sorted(expected) == list(range(1, TOTAL + 1))
    for step, (loss, lr) in expected.items():
        assert math.isfinite(loss) and math.isfinite(got[step][0])
        assert math.isclose(got[step][0], loss, rel_tol=1e-3, abs_tol=1e-4), (step, got[step][0], loss)
        assert got[step][1] == lr, f"learning-rate schedule differs at step {step}"


def test_final_adapters_match(runs):
    import torch
    tmp = runs[0]
    a = final_adapter(tmp / "store-whole", "whole")
    b = final_adapter(tmp / "store-chunked", "chunked")
    assert a.keys() == b.keys()
    diff = math.sqrt(sum(float(torch.sum((a[k].float() - b[k].float()) ** 2)) for k in a))
    norm = math.sqrt(sum(float(torch.sum(a[k].float() ** 2)) for k in a))
    if norm == 0:
        assert diff <= 1e-6
    else:
        assert diff / norm <= 1e-4, (diff, norm)
    print(f"final adapter relative L2 difference: {diff / norm if norm else diff:.3e}")


def test_checkpoint_sizes_are_measured_not_assumed(runs):
    import resumable as rs
    tmp = runs[0]
    sizes = {}
    for chunk_dir in sorted((tmp / "store-chunked").glob("chunked-step*")):
        manifest = json.loads((chunk_dir / rs.MANIFEST).read_bytes())
        sizes[chunk_dir.name] = sum(f["bytes"] for f in manifest["files"])
    assert sizes and all(size > 0 for size in sizes.values())
    print("measured synthetic checkpoint bytes:", sizes)


def relative_l2(a, b):
    import torch
    diff = math.sqrt(sum(float(torch.sum((a[k].float() - b[k].float()) ** 2)) for k in a))
    norm = math.sqrt(sum(float(torch.sum(a[k].float() ** 2)) for k in a))
    return diff / norm


def test_rng_state_is_restored_when_the_run_depends_on_it(tmp_path):
    """With LoRA dropout the losses depend on the RNG stream, so equality after a resume shows the RNG was restored;
    the same resume with rng_state.pth removed must diverge (Codex 7101764001 note)."""
    whole = chunk(tmp_path / "sw", "w", tmp_path / "w1", TOTAL, TOTAL, "--dropout", "0.3")
    chunk(tmp_path / "sc", "c", tmp_path / "c1", TOTAL, FIRST, "--dropout", "0.3")
    resumed = chunk(tmp_path / "sc", "c", tmp_path / "c2", TOTAL, TOTAL - FIRST, "--dropout", "0.3")
    chunk(tmp_path / "sm", "m", tmp_path / "m1", TOTAL, FIRST, "--dropout", "0.3")
    lost = chunk(tmp_path / "sm", "m", tmp_path / "m2", TOTAL, TOTAL - FIRST, "--dropout", "0.3",
                 "--drop-rng-on-restore")
    after = lambda history: {k: v for k, v in losses(history).items() if k > FIRST}  # noqa: E731
    assert after(resumed["log_history"]) == after(whole["log_history"])
    assert sorted(after(whole["log_history"])) == list(range(FIRST + 1, TOTAL + 1))
    assert relative_l2(final_adapter(tmp_path / "sw", "w"), final_adapter(tmp_path / "sc", "c")) <= 1e-4
    assert relative_l2(final_adapter(tmp_path / "sw", "w"), final_adapter(tmp_path / "sm", "m")) > 1e-4,         "losing the RNG state did not change the result, so this test could not detect it"


@pytest.mark.parametrize("extra", [("--total-steps-override",), ("--seed", "7")])
def test_a_changed_budget_or_seed_is_refused_before_training(tmp_path, extra):
    chunk(tmp_path / "s", "r", tmp_path / "r1", TOTAL, FIRST)
    if extra == ("--total-steps-override",):
        done = chunk(tmp_path / "s", "r", tmp_path / "r2", TOTAL * 2, FIRST, check=False)
    else:
        done = chunk(tmp_path / "s", "r", tmp_path / "r2", TOTAL, FIRST, *extra, check=False)
    assert done.returncode != 0
    assert "bindings differ" in done.stderr
    assert not (tmp_path / "r2" / "trainer" / f"checkpoint-{FIRST * 2}").exists(), "it trained before refusing"
