#!/usr/bin/env python3
"""Synthetic CPU length-ladder qualification: rung planner, stop rules and parent runner (design: Codex 7096272556,
Cowork ruling 7091727691, start permission 7123248463).

Invented rows only. No real data, no export, no Colab. Each rung is one child process (ladder_rung.py) so that one rung's
memory high-water, OOM counter and failure cannot leak into the next. The parent stops at the FIRST failed rung and
writes every later rung as NOT_RUN; it never retries and never continues to a longer rung after a failure.

  python ladder.py plan   --budget-bytes N [--rungs 128,256,512,1024,2048]
      Prints the rung list and the fixed stop thresholds. Runs nothing.
  python ladder.py run    --budget-bytes N --out DIR [--model pinned|synthetic] [--steps 3] [--rung-timeout 1200]
      Runs the ladder. `pinned` loads the pinned 3B base model (fp32, CPU); `synthetic` uses a tiny random model and is
      only for proving the mechanics. The summary says which one ran; a synthetic summary is never qualification.

Stop rules (any one stops the ladder at that rung): OOM (child killed by the kernel or memory.events oom/oom_kill > 0),
nonfinite loss or gradient norm, failed adapter save or reload, memory above 90 percent of the effective budget (the
lower of --budget-bytes and the container memory limit), any swap use
or major-fault paging above the allowed floor, 20 minutes per rung, or a child that wrote no record. The budget is fixed
before the run (the lower of the usable training budget and the container ceiling); it is never recomputed from the
shrinking free RAM. Host (Windows) available memory is not visible inside the container, so the operator records it
before and after each rung with --host-available-file (one integer of bytes per line, appended by the operator), and the
parent applies the host reserve rules to the numbers it is given.

An OOM or any other stop is FAILURE EVIDENCE for that rung. It is never a pass and never a host qualification.
"""
import argparse
import json
import math
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
DEFAULT_RUNGS = (128, 256, 512, 1024, 2048)
CEILING_FRACTION = 0.90
STAGE2_PEAK_BYTES = round(14.21 * 1024 ** 3)   # measured Stage 2 peak; a real run must fit it under the ceiling
CEILING_POLICY = "90 percent of the lower of the fixed usable budget and the container memory limit (Codex 7096272556)"
CGROUP_MEMORY_MAX = Path("/sys/fs/cgroup/memory.max")
CGROUP_PAGE_SLACK_BYTES = 64 * 1024   # the kernel rounds a memory limit down to a whole page
RUNG_TIMEOUT_SECONDS = 20 * 60
HOST_START_MIN_BYTES = 6 * 1024 ** 3     # Windows available before the first rung
HOST_STOP_BELOW_BYTES = 4 * 1024 ** 3    # stop if Windows available falls below this
MAJOR_FAULT_DELTA_LIMIT = 200            # paging rule v1 (set by Code, never ruled): whole-rung process major faults, cold reads included
PAGING_RULES = ("v1", "v2")
TRACE_COLUMNS_V2 = ("epoch", "current", "rss", "anon", "file", "shmem", "metric", "phase", "majflt", "pgmajfault", "refault_file", "refault_anon")
PAGING_COUNTERS = ("majflt", "pgmajfault", "refault_file", "refault_anon")
PAGING_WINDOW_SECONDS = 5.0
LENGTH_TOLERANCE = 3                     # tokens a built row may be below the target (never above)
CEILING_METRICS = {
    "original": "A-original: ceiling on the larger of cgroup memory.current and process RSS (counts page cache)",
    "anon_shmem": "B-anon-shmem: ceiling on cgroup anon plus shmem (non-reclaimable). NEW labelled configuration, not a retry of A",
}
CONTAINER_CAPS = ("fixed", "budget")
HOST_OVERHEAD_MARGIN_BYTES = 2 * 1024 ** 3   # VM and host overhead kept out of the container cap
CONFIG_LABELS = {
    ("original", "fixed"): "A: first-run gate (ceiling on memory.current or RSS, container 22g)",
    ("anon_shmem", "fixed"): "B: ceiling on anon+shmem, container 22g (run 2 stopped by the host-floor watchdog)",
    ("anon_shmem", "budget"): "C: B plus a container memory cap taken from the fixed budget (page cache is reclaimed inside the "
                              "container, not left to starve Windows), a 1 s host watchdog and a per-second memory trace. NEW labelled configuration",
    ("original", "budget"): "D: A plus the budget-derived container cap. NEW labelled configuration",
}


def summarize_trace(path):
    """Peaks and last row of a rung's memory-trace.csv, so a rung killed before it could write its record still leaves
    evidence. None if there is no trace."""
    try:
        lines = Path(path).read_text(encoding="utf-8").splitlines()
    except OSError:
        return None
    cols = ("current", "rss", "anon", "file", "shmem", "metric")
    peaks = {c: None for c in cols}
    rows, last = 0, None
    for line in lines[1:]:
        parts = line.split(",")
        if len(parts) not in (7, 12):
            continue   # header, or a partly written last line
        rows += 1
        last = parts[0]
        for name, raw in zip(cols, parts[1:7]):
            try:
                value = int(raw)
            except ValueError:
                continue
            if peaks[name] is None or value > peaks[name]:
                peaks[name] = value
    return {"rows": rows, "last_epoch": last, **{f"{c}_peak_bytes": v for c, v in peaks.items()}} if rows else None


def paging_from_trace(path):
    """Per-phase paging counters from a rung's memory-trace.csv (12-column rows: load, train, save). All four counters are
    cumulative, so a phase's figure is last minus first inside it. `refault_file` is the cgroup's workingset_refault_file:
    file pages that were evicted and read again, which is real cache pressure; cold reads of the weights on a fresh VM show
    up in `majflt` and `pgmajfault` but not in `refault_*`. None if the trace has no 12-column rows."""
    try:
        lines = Path(path).read_text(encoding="utf-8").splitlines()
    except OSError:
        return None
    phases = {}
    for line in lines[1:]:
        parts = line.split(",")
        if len(parts) != 12:
            continue
        try:
            epoch = float(parts[0])
        except ValueError:
            continue
        values = {}
        for name, raw in zip(PAGING_COUNTERS, parts[8:12]):
            try:
                values[name] = int(raw)
            except ValueError:
                values[name] = None
        phases.setdefault(parts[7], []).append((epoch, values))
    if not phases:
        return None
    out = {}
    for phase, rows in phases.items():
        seconds = rows[-1][0] - rows[0][0]
        entry = {"rows": len(rows), "seconds": round(seconds, 1)}
        for name in PAGING_COUNTERS:
            first = next((v[name] for _, v in rows if v[name] is not None), None)
            last = next((v[name] for _, v in reversed(rows) if v[name] is not None), None)
            entry[name + "_delta"] = None if None in (first, last) else last - first
        # worst rate over any window of at least PAGING_WINDOW_SECONDS inside the phase
        best = None
        for i, (t0, v0) in enumerate(rows):
            if v0["refault_file"] is None:
                continue
            for t1, v1 in rows[i + 1:]:
                if t1 - t0 >= PAGING_WINDOW_SECONDS and v1["refault_file"] is not None:
                    rate = (v1["refault_file"] - v0["refault_file"]) / (t1 - t0)
                    best = rate if best is None or rate > best else best
                    break
        entry["max_refault_file_per_s"] = None if best is None else round(best, 2)
        out[phase] = entry
    return {"window_seconds": PAGING_WINDOW_SECONDS, "phases": out,
            "note": "counters are cumulative; the gap between phases (under one sample) is not attributed"}


FILLER = ["alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel"]


# ---------------------------------------------------------------- row synthesis
def filler_text(word_count: int) -> str:
    return " ".join(FILLER[i % len(FILLER)] for i in range(word_count))


def build_row(row_index: int, target_tokens: int, encode, bos_id: int, build_example):
    """An invented conversation whose tokenized length is within LENGTH_TOLERANCE below target_tokens (never above it, so
    the 2048 cap never truncates it). `build_example(messages, encode, bos_id, max_length)` is sft_tokenize's, passed in so
    tests can use a stand-in. Returns (messages, actual_length). Fails loudly if the target cannot be hit."""
    def make(words):
        return [
            {"role": "system", "content": "You classify synthetic requests. This is an invented qualification row."},
            {"role": "user", "content": f"Synthetic request {row_index}: " + filler_text(words)},
            {"role": "assistant", "content": json.dumps({"intent": "synthetic_test", "row": row_index})},
        ]

    def length(words):
        return len(build_example(make(words), encode, bos_id, target_tokens + 4096)["input_ids"])

    if length(0) > target_tokens:
        raise ValueError(f"target {target_tokens} is shorter than the empty row ({length(0)})")
    low, high = 0, 1
    while length(high) <= target_tokens:
        low, high = high, high * 2
        if high > 1_000_000:
            raise ValueError("filler grows without reaching the target; check the encoder")
    # largest word count whose length does not exceed the target
    while high - low > 1:
        mid = (low + high) // 2
        if length(mid) <= target_tokens:
            low = mid
        else:
            high = mid
    actual = length(low)
    if target_tokens - actual > LENGTH_TOLERANCE:
        raise ValueError(f"cannot reach target {target_tokens}: closest row is {actual} tokens")
    return make(low), actual


# ---------------------------------------------------------------- stop rules
def effective_budget(*limits):
    """The one ceiling denominator: the lowest of the limits that are known (usable budget, declared container limit, actual
    cgroup memory.max). Startup admission, the child watcher and the parent verdict all use this."""
    known = [v for v in limits if v]
    return min(known) if known else 0


def ceiling_bytes(effective):
    return effective * 9 // 10


def read_cgroup_max(path=CGROUP_MEMORY_MAX):
    """The cgroup's memory limit in bytes, or None when it is unreadable or unlimited ("max")."""
    try:
        return int(Path(path).read_text().strip())
    except (OSError, ValueError):
        return None


def cap_gate(budget_bytes, container_memory_bytes, cgroup_memory_max) -> list:
    """Admission for a real run: the container limit is declared, matches what the kernel enforces, and the ceiling under
    the effective budget still holds the measured Stage 2 peak."""
    reasons = []
    if not container_memory_bytes:
        reasons.append("container memory limit not declared (--container-memory-bytes)")
    if cgroup_memory_max is None:
        reasons.append("cgroup memory.max not readable or unlimited; the container limit cannot be shown to apply")
    elif container_memory_bytes and not 0 <= container_memory_bytes - cgroup_memory_max < CGROUP_PAGE_SLACK_BYTES:
        reasons.append(f"cgroup memory.max {cgroup_memory_max} does not match the declared container limit {container_memory_bytes}")
    effective = effective_budget(budget_bytes, container_memory_bytes, cgroup_memory_max)
    if ceiling_bytes(effective) < STAGE2_PEAK_BYTES:
        reasons.append(f"ceiling {ceiling_bytes(effective)} under effective budget {effective} is below the Stage 2 peak {STAGE2_PEAK_BYTES}")
    return reasons


def judge_rung(record: dict, *, budget_bytes: int, host_before=None, host_after=None, host_min_seen=None,
               paging_rule="v1", paging_limit=None, container_memory_bytes=None) -> dict:
    """Applies the stop rules to one rung's record. Returns {"status": "PASS"|"FAILURE", "reasons": [...]}.
    Missing evidence is a failure, never a pass. The record keys are those ladder_rung.py writes."""
    reasons = []
    if not isinstance(record, dict) or not record:
        return {"status": "FAILURE", "reasons": ["no rung record was written"]}
    if record.get("completed") is not True:
        reasons.append(f"rung did not complete ({record.get('failure') or 'no reason recorded'})")
    if record.get("oom_events", None) is None:
        reasons.append("OOM counter not recorded")
    elif record["oom_events"] > 0:
        reasons.append(f"memory.events oom/oom_kill count {record['oom_events']}")
    for key in ("losses", "grad_norms"):
        values = record.get(key)
        if not values:
            reasons.append(f"{key} missing")
        elif not all(isinstance(v, (int, float)) and math.isfinite(v) for v in values):
            reasons.append(f"nonfinite value in {key}")
    if record.get("adapter_saved") is not True:
        reasons.append("adapter save not confirmed")
    if record.get("adapter_reload_equal") is not True:
        reasons.append("adapter reload did not reproduce the saved tensors")
    # The ceiling is judged on the metric the rung ran under; records without one are the original configuration.
    metric = record.get("ceiling_metric") or "original"
    peak = record.get("ceiling_metric_peak_bytes") if record.get("ceiling_metric_peak_bytes") is not None else record.get("memory_peak_bytes")
    if peak is None:
        reasons.append("memory high-water not recorded")
    effective = effective_budget(budget_bytes, container_memory_bytes, record.get("cgroup_memory_max"))
    if peak is not None and peak > ceiling_bytes(effective):
        reasons.append(f"memory high-water {peak} ({metric}) above {int(CEILING_FRACTION * 100)} percent of the effective budget {effective}")
    if record.get("effective_budget_bytes") not in (None, effective):
        reasons.append(f"the rung ran under effective budget {record['effective_budget_bytes']}, the verdict uses {effective}")
    if (record.get("swap_peak_bytes") or 0) > 0:
        reasons.append(f"swap used ({record['swap_peak_bytes']} bytes); no swap-dependent qualification")
    if paging_rule == "v2":
        # v2 judges file-page refaults in the TRAINING phase only (cold reads while loading are not paging pressure). The
        # limit is explicit: nobody has ruled a number, so there is no default.
        if paging_limit is None:
            reasons.append("paging rule v2 needs an explicit --paging-max-train-refaults-per-s")
        else:
            train = ((record.get("paging") or {}).get("phases") or {}).get("train")
            if not train:
                reasons.append("paging evidence missing: no training-phase counters in the rung record")
            else:
                rate = train.get("max_refault_file_per_s")
                if rate is None:
                    reasons.append("paging evidence missing: no refault_file counter in the training phase")
                elif rate > paging_limit:
                    reasons.append(f"sustained paging (v2): {rate} file-page refaults per second in training, over {paging_limit}")
                if (train.get("refault_anon_delta") or 0) > 0:
                    reasons.append(f"sustained paging (v2): {train['refault_anon_delta']} anonymous-page refaults in training (swap-ins)")
    elif (record.get("major_fault_delta") or 0) > MAJOR_FAULT_DELTA_LIMIT:
        reasons.append(f"sustained paging: {record['major_fault_delta']} major faults")
    if record.get("wall_seconds") is not None and record["wall_seconds"] > RUNG_TIMEOUT_SECONDS:
        reasons.append(f"rung took {record['wall_seconds']:.0f}s, over {RUNG_TIMEOUT_SECONDS}s")
    seen = [v for v in (host_before, host_after, host_min_seen) if v is not None]
    if seen and min(seen) < HOST_STOP_BELOW_BYTES:
        reasons.append(f"Windows available fell to {min(seen)} bytes, below the {HOST_STOP_BELOW_BYTES} stop floor")
    return {"status": "FAILURE" if reasons else "PASS", "reasons": reasons}


def host_start_gate(host_available_bytes) -> list:
    if host_available_bytes is None:
        return ["Windows available memory not provided; the start gate cannot be shown to pass"]
    if host_available_bytes < HOST_START_MIN_BYTES:
        return [f"Windows available {host_available_bytes} bytes is below the {HOST_START_MIN_BYTES} start minimum"]
    return []


# ---------------------------------------------------------------- parent runner
def run_ladder(*, rungs, budget_bytes, out_dir: Path, model="pinned", steps=3, rung_timeout=RUNG_TIMEOUT_SECONDS,
               host_available_start=None, host_available_end_of_rung=None, host_min_reader=None, host_clock_offset=0.0, ceiling_metric="original", container_cap="fixed", container_memory_bytes=None, paging_rule="v1", paging_limit=None, cgroup_memory_max=None, require_cgroup_evidence=False,
               child_command=None) -> dict:
    """Runs rungs in order in separate processes. `child_command(rung, rung_dir)` -> argv list (tests inject a stub).
    `host_available_end_of_rung(rung)` -> bytes or None, supplied by the operator's host reader.
    `host_min_reader(start_epoch, end_epoch)` -> the lowest Windows-available reading taken during the rung, or None if there
    was none; when a reader is given, no reading during a rung is a failure (missing evidence), never a pass.
    `host_clock_offset` is Windows epoch minus this process's clock, in seconds: the WSL clock can run ahead of or behind
    Windows (seen: 65 s ahead), so rung times are shifted onto the Windows clock before the sampler file is read."""
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    effective = effective_budget(budget_bytes, container_memory_bytes, cgroup_memory_max)
    summary = {"kind": "length_ladder", "model": model, "synthetic_only": True, "qualification": model == "pinned",
               "budget_bytes": budget_bytes, "effective_budget_bytes": effective, "ceiling_bytes": ceiling_bytes(effective),
               "ceiling_policy": CEILING_POLICY, "cgroup_memory_max_bytes": cgroup_memory_max, "steps": steps,
               "configuration": {"ceiling_metric": ceiling_metric, "container_cap": container_cap,
                                 "paging_rule": paging_rule, "paging_max_train_refaults_per_s": paging_limit,
                                 "container_memory_bytes": container_memory_bytes,
                                 "label": CONFIG_LABELS[(ceiling_metric, container_cap)], "metric_label": CEILING_METRICS[ceiling_metric]},
               "rungs": [], "stopped_at": None, "host_clock_offset_seconds": host_clock_offset, "host_start_gate": host_start_gate(host_available_start),
               "host_available_start": host_available_start}
    if require_cgroup_evidence:
        summary["host_start_gate"] += cap_gate(budget_bytes, container_memory_bytes, cgroup_memory_max)
    if model != "pinned":
        summary["qualification"] = False
        summary["note"] = "synthetic tiny model: mechanics only, never host qualification"
    stopped = bool(summary["host_start_gate"])
    if stopped:
        summary["stopped_at"] = "start gate"
    for rung in rungs:
        entry = {"rung": rung}
        if stopped:
            entry.update(status="NOT_RUN", reasons=["an earlier gate or rung failed; no longer rung is attempted"])
            summary["rungs"].append(entry)
            continue
        rung_dir = out_dir / f"rung-{rung}"
        rung_dir.mkdir(parents=True, exist_ok=True)
        argv = (child_command or (lambda r, d: default_child_command(r, d, model, steps, budget_bytes, ceiling_metric, effective)))(rung, rung_dir)
        started = time.monotonic()
        started_epoch = time.time()
        reasons = []
        try:
            proc = subprocess.run(argv, cwd=str(HERE), timeout=rung_timeout, capture_output=True, text=True)
            entry["returncode"] = proc.returncode
            (rung_dir / "child.log").write_text((proc.stdout or "") + "\n--- stderr ---\n" + (proc.stderr or ""), encoding="utf-8")
            if proc.returncode in (-9, 137):
                reasons.append("child killed (SIGKILL; treated as OOM)")
            elif proc.returncode != 0:
                reasons.append(f"child exited {proc.returncode}")
        except subprocess.TimeoutExpired:
            reasons.append(f"rung exceeded {rung_timeout}s and was killed")
        wall = time.monotonic() - started
        ended_epoch = time.time()
        record = {}
        record_path = rung_dir / "rung.json"
        if record_path.exists():
            try:
                record = json.loads(record_path.read_text(encoding="utf-8"))
            except ValueError:
                reasons.append("rung.json is not valid JSON")
        record.setdefault("wall_seconds", wall)
        host_after = host_available_end_of_rung(rung) if host_available_end_of_rung else None
        host_min = host_min_reader(started_epoch + host_clock_offset, ended_epoch + host_clock_offset) if host_min_reader else None
        verdict = judge_rung(record, budget_bytes=budget_bytes, host_after=host_after, host_min_seen=host_min,
                             paging_rule=paging_rule, paging_limit=paging_limit,
                             container_memory_bytes=effective_budget(container_memory_bytes, cgroup_memory_max) or None)
        if require_cgroup_evidence and record.get("cgroup_memory_max") is None:
            verdict["reasons"].append("the rung record has no cgroup memory.max reading")
        if host_min_reader and host_min is None:
            verdict["reasons"].append("no Windows-available reading was taken during the rung")
        verdict["reasons"] = reasons + verdict["reasons"]
        if verdict["reasons"]:
            verdict["status"] = "FAILURE"
        entry.update(status=verdict["status"], reasons=verdict["reasons"], host_available_after=host_after, host_available_min_during=host_min,
                     actual_token_lengths=record.get("actual_token_lengths"), memory_peak_bytes=record.get("memory_peak_bytes"),
                     wall_seconds=round(record.get("wall_seconds", wall), 1),
                     memory_trace=summarize_trace(rung_dir / "memory-trace.csv"),
                     paging=record.get("paging") or paging_from_trace(rung_dir / "memory-trace.csv"))
        summary["rungs"].append(entry)
        if verdict["status"] != "PASS":
            summary["stopped_at"] = rung
            stopped = True
    summary["complete"] = all(r["status"] == "PASS" for r in summary["rungs"])
    (out_dir / "summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return summary


def clock_offset(host_epoch, now=None):
    """Windows epoch (seconds, taken by the launcher just before the container started) minus this clock now; 0 when none
    was given. The few seconds the container takes to start make it read slightly low; read_host_min's slack covers that."""
    if not host_epoch or host_epoch <= 0:
        return 0.0
    return float(host_epoch) - (time.time() if now is None else now)


def read_host_min(csv_path, start_epoch, end_epoch, slack=10):
    """Lowest `bytes` among `epoch,bytes` lines of the launcher's sampler file taken within [start-slack, end+slack]."""
    try:
        lines = Path(csv_path).read_text(encoding="utf-8").splitlines()
    except OSError:
        return None
    best = None
    for line in lines:
        try:
            epoch, value = line.split(",")[:2]
            epoch, value = float(epoch), int(value)
        except ValueError:
            continue  # header or a partly written last line
        if start_epoch - slack <= epoch <= end_epoch + slack and (best is None or value < best):
            best = value
    return best


def default_child_command(rung, rung_dir, model="pinned", steps=3, budget_bytes=0, ceiling_metric="original",
                          effective_budget_bytes=0):
    return [sys.executable, str(HERE / "ladder_rung.py"), "--rung", str(rung), "--out", str(rung_dir),
            "--model", model, "--steps", str(steps), "--budget-bytes", str(budget_bytes),
            "--effective-budget-bytes", str(effective_budget_bytes or budget_bytes), "--ceiling-metric", ceiling_metric]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("plan", "run"):
        p = sub.add_parser(name)
        p.add_argument("--budget-bytes", type=int, required=True, help="fixed pre-run usable training budget, bytes")
        p.add_argument("--rungs", default=",".join(str(r) for r in DEFAULT_RUNGS))
        p.add_argument("--paging-rule", choices=PAGING_RULES, default="v1",
                       help="v1 = whole-rung process major faults (as run 3); v2 = training-phase file-page refaults per second")
        p.add_argument("--paging-max-train-refaults-per-s", type=float, default=None,
                       help="the limit for paging rule v2; there is no default because none has been ruled")
        p.add_argument("--container-cap", choices=CONTAINER_CAPS, default="fixed",
                       help="how the container memory limit was set (the launcher sets it); budget = from the fixed budget")
        p.add_argument("--container-memory-bytes", type=int, default=None, help="the container memory limit actually used, bytes; lowers the ceiling when below the budget")
        p.add_argument("--ceiling-metric", choices=sorted(CEILING_METRICS), default="original",
                       help="what the 90 percent ceiling is measured on; anon_shmem is a new labelled configuration")
    run = sub.choices["run"]
    run.add_argument("--out", type=Path, required=True)
    run.add_argument("--model", choices=("pinned", "synthetic"), default="pinned")
    run.add_argument("--steps", type=int, default=3)
    run.add_argument("--rung-timeout", type=int, default=RUNG_TIMEOUT_SECONDS)
    run.add_argument("--host-available-start", type=int, default=None, help="Windows available bytes, measured just before")
    run.add_argument("--host-epoch-at-start", type=int, default=0,
                     help="Windows epoch seconds taken by the launcher just before the container started (corrects WSL clock skew)")
    run.add_argument("--host-csv", type=Path, default=None,
                     help="sampler file of `epoch_seconds,windows_available_bytes` lines; each rung is judged on its lowest reading")
    run.add_argument("--host-available-file", type=Path, default=None,
                     help="file the operator appends Windows available bytes to after each rung (one integer per line)")
    args = parser.parse_args(argv)
    if args.command == "run" and args.paging_rule == "v2" and args.paging_max_train_refaults_per_s is None:
        parser.error("--paging-rule v2 needs --paging-max-train-refaults-per-s (no limit has been ruled, so there is no default)")
    rungs = [int(r) for r in args.rungs.split(",")]
    if args.command == "plan":
        effective = effective_budget(args.budget_bytes, args.container_memory_bytes)
        print(json.dumps({"rungs": rungs, "budget_bytes": args.budget_bytes, "effective_budget_bytes": effective,
                          "ceiling_bytes": ceiling_bytes(effective),
                          "host_start_min_bytes": HOST_START_MIN_BYTES, "host_stop_below_bytes": HOST_STOP_BELOW_BYTES,
                          "rung_timeout_seconds": RUNG_TIMEOUT_SECONDS}, indent=2))
        return 0

    def host_reader(rung):
        if not args.host_available_file or not args.host_available_file.exists():
            return None
        lines = [l.strip() for l in args.host_available_file.read_text().splitlines() if l.strip()]
        return int(lines[-1]) if lines else None

    summary = run_ladder(
        rungs=rungs, budget_bytes=args.budget_bytes, out_dir=args.out, model=args.model, steps=args.steps,
        rung_timeout=args.rung_timeout, host_available_start=args.host_available_start,
        host_available_end_of_rung=host_reader,
        host_min_reader=(lambda a, b: read_host_min(args.host_csv, a, b)) if args.host_csv else None,
        host_clock_offset=clock_offset(args.host_epoch_at_start), ceiling_metric=args.ceiling_metric,
        container_cap=args.container_cap, container_memory_bytes=args.container_memory_bytes,
        paging_rule=args.paging_rule, paging_limit=args.paging_max_train_refaults_per_s,
        cgroup_memory_max=read_cgroup_max(), require_cgroup_evidence=args.model == "pinned")
    print(json.dumps({k: summary[k] for k in ("model", "qualification", "configuration", "stopped_at", "complete")}, indent=2))
    return 0 if summary["complete"] else 1


if __name__ == "__main__":
    sys.exit(main())
