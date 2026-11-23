#!/usr/bin/env python3
"""Synthetic GPU qualification for resumable chunks (Colab slice B; Codex design r1 section 4).

Two commands, both offline and both on invented data:

  invented-rows --out FILE --rows N
      Writes N invented conversations in the sft_dataset.jsonl shape. Every row is plainly synthetic (a fixed
      template with a counter); nothing is read from real data, and no data is discovered automatically.

  compare --whole RUN_DIR --chunks RUN_DIR [RUN_DIR ...] --whole-store DIR --chunk-store DIR --whole-run ID --chunk-run ID
      Applies the gate fixed before results: every loss finite; matched-step loss allclose (atol 1e-4, rtol 1e-3);
      identical learning rate at every step; the same final step; final adapter relative L2 difference <= 1e-4
      (absolute <= 1e-6 if the reference norm is 0). Prints one JSON verdict. A failure is investigated, never met by
      widening a tolerance after the fact.

This is the comparison Nate's first person-run session would use. It is not a run and authorizes none.
"""
import argparse
import json
import math
import sys
from pathlib import Path

ATOL, RTOL, ADAPTER_REL_L2, ADAPTER_ABS_L2 = 1e-4, 1e-3, 1e-4, 1e-6
INVENTED_NOTE = "invented qualification row"


def invented_rows(count: int) -> list[dict]:
    if count < 1:
        raise ValueError("count must be at least 1")
    rows = []
    for i in range(count):
        rows.append({"id": f"invented-{i:04d}", "messages": [
            {"role": "system", "content": f"You classify synthetic requests. This is an {INVENTED_NOTE}."},
            {"role": "user", "content": f"Synthetic request number {i}: please turn on test lamp {i % 7}."},
            {"role": "assistant", "content": json.dumps({"intent": "synthetic_test", "lamp": i % 7, "row": i})},
        ]})
    return rows


def losses(run_dir: Path) -> dict:
    out = {}
    for line in (Path(run_dir) / "loss.jsonl").read_text(encoding="utf-8").splitlines():
        entry = json.loads(line) if line.strip() else {}
        if "loss" in entry:
            out[int(entry["step"])] = (float(entry["loss"]), entry.get("learning_rate"))
    return out


def compare_losses(whole: dict, chunked: dict) -> list[str]:
    problems = []
    if sorted(whole) != sorted(chunked):
        problems.append(f"logged steps differ: whole {sorted(whole)} vs chunked {sorted(chunked)}")
    for step in sorted(set(whole) & set(chunked)):
        (a, lr_a), (b, lr_b) = whole[step], chunked[step]
        if not (math.isfinite(a) and math.isfinite(b)):
            problems.append(f"step {step}: non-finite loss ({a}, {b})")
        elif not math.isclose(b, a, rel_tol=RTOL, abs_tol=ATOL):
            problems.append(f"step {step}: loss {b} vs {a} outside atol {ATOL} / rtol {RTOL}")
        if lr_a != lr_b:
            problems.append(f"step {step}: learning rate {lr_b} vs {lr_a}")
    return problems


def final_adapter_file(store: Path, run_id: str) -> Path:
    import resumable as rs
    finals = [p for p in sorted(Path(store).glob(f"{run_id}-step*")) if (p / rs.MARKER).exists()]
    if not finals:
        raise SystemExit(f"no committed chunk for {run_id} in {store}")
    manifest = json.loads((finals[-1] / rs.MANIFEST).read_bytes())
    if not manifest["run_complete"]:
        raise SystemExit(f"{finals[-1].name} is a partial chunk, not a completed run")
    return finals[-1] / rs.PAYLOAD / "adapter_model.safetensors"


def adapter_difference(a: dict, b: dict) -> tuple[float, float]:
    """(L2 of the difference, L2 of the reference) over all tensors; keys must match."""
    if a.keys() != b.keys():
        raise SystemExit(f"adapter tensors differ: {sorted(set(a) ^ set(b))[:5]}")
    import torch
    diff = math.sqrt(sum(float(torch.sum((a[k].double() - b[k].double()) ** 2)) for k in a))
    norm = math.sqrt(sum(float(torch.sum(a[k].double() ** 2)) for k in a))
    return diff, norm


def adapter_problems(diff: float, norm: float) -> list[str]:
    if norm == 0:
        return [] if diff <= ADAPTER_ABS_L2 else [f"adapter absolute L2 {diff} > {ADAPTER_ABS_L2} (zero reference)"]
    return [] if diff / norm <= ADAPTER_REL_L2 else [f"adapter relative L2 {diff / norm} > {ADAPTER_REL_L2}"]


def main(argv=None):
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="cmd", required=True)
    rows = sub.add_parser("invented-rows")
    rows.add_argument("--out", type=Path, required=True)
    rows.add_argument("--rows", type=int, default=64)
    cmp = sub.add_parser("compare")
    cmp.add_argument("--whole", type=Path, required=True)
    cmp.add_argument("--chunks", type=Path, nargs="+", required=True)
    cmp.add_argument("--whole-store", type=Path, required=True)
    cmp.add_argument("--chunk-store", type=Path, required=True)
    cmp.add_argument("--whole-run", required=True)
    cmp.add_argument("--chunk-run", required=True)
    args = parser.parse_args(argv)
    if args.cmd == "invented-rows":
        with open(args.out, "x", encoding="utf-8", newline="\n") as out:
            for row in invented_rows(args.rows):
                out.write(json.dumps(row, sort_keys=True) + "\n")
        print(f"wrote {args.rows} invented rows to {args.out}")
        return 0
    chunked = {}
    for run_dir in args.chunks:
        chunked.update(losses(run_dir))
    problems = compare_losses(losses(args.whole), chunked)
    from safetensors.torch import load_file
    diff, norm = adapter_difference(load_file(str(final_adapter_file(args.whole_store, args.whole_run))),
                                    load_file(str(final_adapter_file(args.chunk_store, args.chunk_run))))
    problems += adapter_problems(diff, norm)
    verdict = {"gate": {"atol": ATOL, "rtol": RTOL, "adapter_rel_l2": ADAPTER_REL_L2},
               "adapter_l2_diff": diff, "adapter_l2_norm": norm, "problems": problems,
               "result": "PASS" if not problems else "FAIL"}
    print(json.dumps(verdict, indent=2))
    return 0 if not problems else 1


if __name__ == "__main__":
    sys.exit(main())
