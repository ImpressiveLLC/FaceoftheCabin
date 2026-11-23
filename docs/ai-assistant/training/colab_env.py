#!/usr/bin/env python3
"""Environment recorder for resumable chunks (Colab slice B; Codex design r1 section 5, Cowork 7104289870, Codex review
7106192144).

`record()` describes what is OBSERVED in the runtime: Python and platform, the installed version of every package the
lock pins, torch's CUDA and cuDNN builds, the GPU and the driver. `environment_id()` hashes those identity fields;
train_sft.py binds every chunk to it and re-records in the training process itself, so a stale record from another
runtime is refused.

The training policy (fp16, bf16 False, eager attention, deterministic algorithms, cuDNN benchmark off, a fixed cuBLAS
workspace) is REQUESTED here, applied by train_sft.py, and then observed in the training process by
`observed_runtime()`; requested and observed values are kept apart, and a mismatch refuses the chunk.

`check_lock()` compares the record with the lock's target and pins: Python version, platform, the torch CUDA build,
and the installed version of every pinned package. Wheel hashes are verified by `pip --require-hashes` at install time;
they are NOT re-measured here, and the record says so rather than claiming them.

No network, no account, no install. Reading `nvidia-smi` is optional and only reports what the driver says.
"""
import hashlib
import json
import os
import platform
import re
import subprocess
import sys
import uuid
from importlib import metadata
from pathlib import Path

DEFAULT_LOCK = Path(__file__).with_name("requirements-colab-cuda.lock")
KEY_PACKAGES = ("torch", "transformers", "peft", "accelerate", "tokenizers", "safetensors", "huggingface-hub", "numpy")
PACKAGES = KEY_PACKAGES  # kept for callers of slice B r1
VOLATILE = ("recorded_at", "gpu_memory_free_bytes", "lock_check", "record_path")
REQUESTED_POLICY = {"dtype": "torch.float16", "fp16": True, "bf16": False, "attn_implementation": "eager",
                    "deterministic_algorithms": True, "cudnn_benchmark": False, "cublas_workspace_config": ":4096:8"}
WHEEL_HASHES = ("NOT_MEASURED: versions come from installed metadata; wheel hashes were checked by pip "
                "--require-hashes at install time and are not re-measured here")


def _norm(name):
    return name.lower().replace("_", "-")


def _version(name):
    try:
        return metadata.version(name)
    except metadata.PackageNotFoundError:
        return None


def _nvidia_smi():
    try:
        out = subprocess.run(["nvidia-smi", "--query-gpu=driver_version,name,memory.total", "--format=csv,noheader"],
                             capture_output=True, text=True, timeout=20, check=True).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return None
    return out.splitlines()[0] if out else None


_PIN = re.compile(r"^([A-Za-z0-9_.\-]+)(\[[^\]]*\])?==([^\s;\\]+)")


def lock_pins(lock_path: Path) -> dict:
    pins = {}
    for line in Path(lock_path).read_text(encoding="utf-8").splitlines():
        match = _PIN.match(line.strip())
        if match:
            pins[_norm(match.group(1))] = match.group(3)
    return pins


def lock_target(lock_path: Path) -> dict:
    """The Python version, platform and torch CUDA build the lock was compiled for, read from the lock itself."""
    text = Path(lock_path).read_text(encoding="utf-8")
    python = re.search(r"--python-version (\d+\.\d+)", text)
    plat = re.search(r"--python-platform (\S+)", text)
    torch_pin = lock_pins(lock_path).get("torch", "")
    cuda = re.search(r"\+cu(\d+)(\d)$", torch_pin)
    return {"python": python.group(1) if python else None, "platform": plat.group(1) if plat else None,
            "torch_cuda_build": f"{cuda.group(1)}.{cuda.group(2)}" if cuda else None}


def record(torch=None, smi=_nvidia_smi, now=None, lock_path: Path = DEFAULT_LOCK, version=_version) -> dict:
    """What this runtime is. TORCH may be a stand-in in tests; by default the real module, if importable."""
    if torch is None:
        try:
            import torch  # noqa: F401
        except ImportError:
            torch = None
    gpu = None
    free = None
    if torch is not None and torch.cuda.is_available():
        props = torch.cuda.get_device_properties(0)
        free, _total = torch.cuda.mem_get_info(0)
        gpu = {"name": props.name, "total_memory_bytes": int(props.total_memory),
               "compute_capability": f"{props.major}.{props.minor}", "count": torch.cuda.device_count()}
    names = sorted(set(lock_pins(lock_path)) | set(KEY_PACKAGES)) if Path(lock_path).exists() else list(KEY_PACKAGES)
    rec = {
        "python": sys.version.split()[0],
        "implementation": platform.python_implementation(),
        "system": platform.system(),
        "machine": platform.machine(),
        "lock": Path(lock_path).name,
        "packages": {name: version(name) for name in names},
        "wheel_hashes": WHEEL_HASHES,
        "torch_cuda_build": getattr(getattr(torch, "version", None), "cuda", None) if torch else None,
        "cudnn": (torch.backends.cudnn.version() if torch is not None and torch.backends.cudnn.is_available() else None),
        "gpu": gpu,
        "nvidia_smi": smi() if smi else None,
        "requested_policy": dict(REQUESTED_POLICY),
        "recorded_at": now,
        "gpu_memory_free_bytes": int(free) if gpu else None,
    }
    rec["environment_id"] = environment_id(rec)
    return rec


def environment_id(rec: dict) -> str:
    identity = {k: v for k, v in rec.items() if k not in VOLATILE and k != "environment_id"}
    return "env-" + hashlib.sha256(json.dumps(identity, sort_keys=True).encode()).hexdigest()[:32]


def check_lock(rec: dict, lock_path: Path) -> list[str]:
    """Every difference between the observed runtime and the lock's target and pins. Empty means it matches."""
    problems = []
    target = lock_target(lock_path)
    observed_python = ".".join(rec["python"].split(".")[:2])
    if target["python"] is None or observed_python != target["python"]:
        problems.append(f"Python {rec['python']}, lock targets {target['python']}")
    if target["platform"] != "x86_64-unknown-linux-gnu" or rec.get("system") != "Linux" or rec.get("machine") != "x86_64":
        problems.append(f"platform {rec.get('system')}/{rec.get('machine')}, lock targets {target['platform']}")
    if target["torch_cuda_build"] is None or rec.get("torch_cuda_build") != target["torch_cuda_build"]:
        problems.append(f"torch CUDA build {rec.get('torch_cuda_build')}, lock targets {target['torch_cuda_build']}")
    for name, pinned in sorted(lock_pins(lock_path).items()):
        installed = rec["packages"].get(name)
        if installed != pinned:
            problems.append(f"{name}: installed {installed}, lock pins {pinned}")
    for name in KEY_PACKAGES:
        if name not in lock_pins(lock_path):
            problems.append(f"{name}: not pinned in {Path(lock_path).name}")
    if rec.get("gpu") is None:
        problems.append("no CUDA GPU visible: this lock and the fp16 path need one")
    elif tuple(int(x) for x in rec["gpu"]["compute_capability"].split(".")) < (7, 0):
        problems.append(f"GPU compute capability {rec['gpu']['compute_capability']} is below 7.0 (fp16 tensor cores)")
    return problems


def apply_policy(torch) -> None:
    """Applies the requested determinism settings in the training process. CUBLAS_WORKSPACE_CONFIG must be set before
    the first CUDA call; train_sft.py sets it before importing torch."""
    os.environ.setdefault("CUBLAS_WORKSPACE_CONFIG", REQUESTED_POLICY["cublas_workspace_config"])
    torch.use_deterministic_algorithms(True)
    torch.backends.cudnn.benchmark = False


def observed_runtime(torch, model, training_args) -> dict:
    """The settings actually in force in the training process, read back rather than assumed."""
    param = next(iter(model.parameters()), None)
    base = getattr(model, "get_base_model", lambda: model)()
    return {"dtype": str(getattr(base, "dtype", None) or (param.dtype if param is not None else None)),
            "fp16": bool(training_args.fp16), "bf16": bool(training_args.bf16),
            "attn_implementation": getattr(getattr(base, "config", None), "_attn_implementation", None),
            "deterministic_algorithms": bool(torch.are_deterministic_algorithms_enabled()),
            "cudnn_benchmark": bool(torch.backends.cudnn.benchmark),
            "cublas_workspace_config": os.environ.get("CUBLAS_WORKSPACE_CONFIG")}


def policy_problems(observed: dict, requested: dict = REQUESTED_POLICY) -> list[str]:
    return [f"{key}: requested {requested[key]!r}, observed {observed.get(key)!r}"
            for key in requested if observed.get(key) != requested[key]]


def main(argv=None):
    import argparse
    import datetime
    parser = argparse.ArgumentParser(description="record this runtime and check it against the CUDA lock")
    parser.add_argument("--out-dir", type=Path, required=True,
                        help="directory for this session's record; each call writes a new, uniquely named file")
    parser.add_argument("--lock", type=Path, default=DEFAULT_LOCK)
    args = parser.parse_args(argv)
    rec = record(now=datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"), lock_path=args.lock)
    problems = check_lock(rec, args.lock)
    rec["lock_check"] = {"lock": args.lock.name, "problems": problems}
    args.out_dir.mkdir(parents=True, exist_ok=True)
    path = args.out_dir / f"env-{rec['environment_id']}-{uuid.uuid4().hex[:8]}.json"
    rec["record_path"] = path.name
    with open(path, "x", encoding="utf-8") as handle:
        json.dump(rec, handle, indent=2, sort_keys=True)
    print(f"environment_id {rec['environment_id']}\nrecord {path}")
    if problems:
        print("RUNTIME DOES NOT MATCH THE LOCK; do not start a lineage:\n  " + "\n  ".join(problems))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
