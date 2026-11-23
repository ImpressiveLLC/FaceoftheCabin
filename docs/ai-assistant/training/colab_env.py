#!/usr/bin/env python3
"""Environment recorder for resumable chunks (Colab slice B; Codex design r1 section 5, Cowork 7104289870).

`record()` describes the runtime a chunk runs in: Python, the pinned training packages, torch's CUDA and cuDNN builds,
the GPU (model, memory, compute capability), the driver, the dtype policy, the attention implementation and the
determinism settings. `environment_id()` hashes the identity fields of that record; train_sft.py binds every chunk to
it, so a session on a different runtime starts a new lineage instead of resuming silently across environments.

`check_lock()` compares the installed versions with a pip lock file (name==version lines) and lists every difference;
a non-empty list means the runtime is not the locked one and the notebook stops.

No network, no account, no install. Reading `nvidia-smi` is optional and only reports what the driver says.
"""
import hashlib
import json
import platform
import re
import subprocess
import sys
from importlib import metadata
from pathlib import Path

PACKAGES = ("torch", "transformers", "peft", "accelerate", "tokenizers", "safetensors", "huggingface-hub", "numpy")
# Fields that change between two sessions on the same runtime are recorded but not hashed.
VOLATILE = ("recorded_at", "gpu_memory_free_bytes", "lock_check")
POLICY = {"dtype": "float16", "fp16": True, "bf16": False, "attn_implementation": "eager",
          "deterministic_algorithms": True, "cudnn_benchmark": False, "cublas_workspace_config": ":4096:8"}


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


def record(torch=None, smi=_nvidia_smi, now=None) -> dict:
    """The environment record. TORCH may be a stand-in in tests; by default the real module, if importable."""
    if torch is None:
        try:
            import torch  # noqa: F401
        except ImportError:
            torch = None
    gpu = None
    if torch is not None and torch.cuda.is_available():
        props = torch.cuda.get_device_properties(0)
        free, _total = torch.cuda.mem_get_info(0)
        gpu = {"name": props.name, "total_memory_bytes": int(props.total_memory),
               "compute_capability": f"{props.major}.{props.minor}", "count": torch.cuda.device_count()}
    rec = {
        "python": sys.version.split()[0],
        "implementation": platform.python_implementation(),
        "machine": platform.machine(),
        "packages": {name: _version(name) for name in PACKAGES},
        "torch_cuda_build": getattr(getattr(torch, "version", None), "cuda", None) if torch else None,
        "cudnn": (torch.backends.cudnn.version() if torch is not None and torch.backends.cudnn.is_available() else None),
        "gpu": gpu,
        "nvidia_smi": smi() if smi else None,
        "policy": dict(POLICY),
        "recorded_at": now,
        "gpu_memory_free_bytes": int(free) if gpu else None,
    }
    rec["environment_id"] = environment_id(rec)
    return rec


def environment_id(rec: dict) -> str:
    identity = {k: v for k, v in rec.items() if k not in VOLATILE and k != "environment_id"}
    return "env-" + hashlib.sha256(json.dumps(identity, sort_keys=True).encode()).hexdigest()[:32]


_PIN = re.compile(r"^([A-Za-z0-9_.\-]+)(\[[^\]]*\])?==([^\s;\\]+)")


def lock_pins(lock_path: Path) -> dict:
    pins = {}
    for line in Path(lock_path).read_text(encoding="utf-8").splitlines():
        match = _PIN.match(line.strip())
        if match:
            pins[match.group(1).lower().replace("_", "-")] = match.group(3)
    return pins


def check_lock(rec: dict, lock_path: Path) -> list[str]:
    """Every difference between the recorded package versions and the lock. Empty means the runtime matches."""
    pins = lock_pins(lock_path)
    problems = []
    for name, installed in rec["packages"].items():
        pinned = pins.get(name)
        if pinned is None:
            problems.append(f"{name}: not pinned in {Path(lock_path).name}")
        elif installed != pinned:
            problems.append(f"{name}: installed {installed}, lock pins {pinned}")
    if rec.get("gpu") is None:
        problems.append("no CUDA GPU visible: this lock and the fp16 path need one")
    elif tuple(int(x) for x in rec["gpu"]["compute_capability"].split(".")) < (7, 0):
        problems.append(f"GPU compute capability {rec['gpu']['compute_capability']} is below 7.0 (fp16 tensor cores)")
    return problems


def main(argv=None):
    import argparse
    parser = argparse.ArgumentParser(description="record this runtime and check it against the CUDA lock")
    parser.add_argument("--out", type=Path, required=True, help="where to write the record (must not exist)")
    parser.add_argument("--lock", type=Path, default=Path(__file__).with_name("requirements-colab-cuda.lock"))
    args = parser.parse_args(argv)
    import datetime
    rec = record(now=datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds"))
    problems = check_lock(rec, args.lock)
    rec["lock_check"] = {"lock": args.lock.name, "problems": problems}
    with open(args.out, "x", encoding="utf-8") as handle:
        json.dump(rec, handle, indent=2, sort_keys=True)
    print(f"environment_id {rec['environment_id']}")
    if problems:
        print("RUNTIME DOES NOT MATCH THE LOCK; do not start a lineage:\n  " + "\n  ".join(problems))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
