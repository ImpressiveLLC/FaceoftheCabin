#!/usr/bin/env python3
"""Synthetic GPU qualification for resumable chunks (Colab slice B; Codex design r1 section 4).

Two commands, both offline and both on invented data:

  invented-rows --out FILE --rows N
      Writes N invented conversations in the sft_dataset.jsonl shape. Every row is plainly synthetic (a fixed
      template with a counter); nothing is read from real data, and no data is discovered automatically.

  sizing-record --store DIR --run-id sizing-ID --out FILE
      After the separately approved one-step sizing run: writes the MEASURED checkpoint size of its committed chunk,
      bound to the model, code, lock and environment it was measured with. Runs that follow pass it as
      --checkpoint-size-record; without it they refuse (NOT_MEASURED).

  compare --whole-store DIR --whole-run ID --chunk-store DIR --chunk-run ID
      Reads ONLY the durable stores, so a lost runtime loses nothing it needs. Verifies every retained chunk and the
      lineage of both runs, then applies the gate fixed before results. PASS needs all of: both runs complete at the
      same bound budget and identical bound configuration; the chunked run really resumed; equal optimizer and
      skipped-update counts; the same trained-input trace (batches, order, labels, masks); complete loss histories
      1..T, finite, allclose (atol 1e-4, rtol 1e-3) with identical learning rates; final adapter relative L2 <= 1e-4
      (absolute <= 1e-6 if the reference norm is 0). Missing evidence fails; it is never a PASS. A failure is
      investigated, never met by widening a tolerance after the fact.

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


def history_losses(log_history: list) -> dict:
    out = {}
    for entry in log_history:
        if "loss" in entry:
            out[int(entry["step"])] = (float(entry["loss"]), entry.get("learning_rate"))
    return out


def compare_losses(whole: dict, chunked: dict, total_steps: int) -> list[str]:
    """Both histories must cover exactly steps 1..TOTAL_STEPS (empty or truncated ones fail), with finite values."""
    problems = []
    expected = list(range(1, total_steps + 1))
    for name, history in (("whole", whole), ("chunked", chunked)):
        if sorted(history) != expected:
            problems.append(f"{name} loss history covers steps {sorted(history)[:3]}..{sorted(history)[-3:]} "
                            f"({len(history)} of {total_steps}); a complete history 1..{total_steps} is required")
    for step in sorted(set(whole) & set(chunked)):
        (a, lr_a), (b, lr_b) = whole[step], chunked[step]
        if not (math.isfinite(a) and math.isfinite(b)):
            problems.append(f"step {step}: non-finite loss ({a}, {b})")
        elif not math.isclose(b, a, rel_tol=RTOL, abs_tol=ATOL):
            problems.append(f"step {step}: loss {b} vs {a} outside atol {ATOL} / rtol {RTOL}")
        if lr_a is None or lr_b is None or not math.isfinite(lr_a) or lr_a != lr_b:
            problems.append(f"step {step}: learning rate {lr_b} vs {lr_a}")
    return problems


def verified_final(store: Path, run_id: str):
    """The newest chunk of RUN_ID after verifying every retained chunk and the lineage between them (resumable.py),
    using the bindings the run recorded. Raises on an invalid marker, corrupt payload or broken lineage."""
    import resumable as rs
    marked = [p for p in sorted(Path(store).glob(f"{run_id}-step*")) if (p / rs.MARKER).exists()]
    if not marked:
        raise rs.LineageError(f"no committed chunk for {run_id} in {store}")
    try:
        bindings = json.loads((marked[-1] / rs.MANIFEST).read_bytes())["bindings"]
    except (OSError, ValueError, KeyError) as exc:
        raise rs.LineageError(f"{marked[-1].name}: unreadable manifest ({exc})") from exc
    chain = rs.verified_chain(Path(store), run_id, bindings)
    return chain[-1], chain


def evidence_problems(whole: dict, chunked: dict, chunked_chain: list) -> list[str]:
    """Completion, budgets, configuration, update accounting and the trained-input trace. Missing evidence fails."""
    problems = []
    a, b = whole["manifest"], chunked["manifest"]
    for name, m in (("whole", a), ("chunked", b)):
        if not m.get("run_complete") or m.get("global_step") != m.get("total_steps") \
                or m.get("total_steps") != m.get("bindings", {}).get("total_steps"):
            problems.append(f"{name} run is not complete at its bound budget (step {m.get('global_step')}, total "
                            f"{m.get('total_steps')}, bound {m.get('bindings', {}).get('total_steps')})")
    if a.get("bindings") != b.get("bindings"):
        differing = sorted(k for k in set(a.get("bindings", {})) | set(b.get("bindings", {}))
                           if a.get("bindings", {}).get(k) != b.get("bindings", {}).get(k))
        problems.append(f"the two runs were bound to different configuration: {differing}")
    if b.get("cursor", {}).get("resumed_from_step", 0) <= 0:
        problems.append("the chunked run's final chunk did not resume from an earlier chunk; nothing was qualified")
    for key in ("optimizer_updates", "skipped_updates"):
        if a.get(key) is None or b.get(key) is None:
            problems.append(f"{key} is NOT_MEASURED in a run")
        elif a[key] != b[key]:
            problems.append(f"{key}: whole {a[key]}, chunked {b[key]}")
    ta, tb = a.get("evidence", {}).get("input_trace"), b.get("evidence", {}).get("input_trace")
    if not ta or not tb or not ta.get("sha256_chain") or not tb.get("sha256_chain"):
        problems.append("trained-input trace is NOT_MEASURED in a run")
    elif (ta["sha256_chain"], ta["micro_batches_total"]) != (tb["sha256_chain"], tb["micro_batches_total"]):
        problems.append(f"trained inputs differ: {ta['micro_batches_total']} vs {tb['micro_batches_total']} micro-batches, "
                        "chain mismatch (different batches, order or masks)")
    return problems


def compare(whole_store: Path, whole_run: str, chunk_store: Path, chunk_run: str, adapter_diff=None) -> dict:
    """The gate, from the durable stores alone: nothing in a lost /content is needed."""
    import resumable as rs
    problems = []
    try:
        whole, _ = verified_final(whole_store, whole_run)
        chunked, chunked_chain = verified_final(chunk_store, chunk_run)
    except rs.LineageError as exc:
        return {"result": "FAIL", "problems": [f"store did not verify: {exc}"]}
    problems += evidence_problems(whole, chunked, chunked_chain)

    def history(record):
        state = json.loads((record["dir"] / rs.PAYLOAD / "trainer_state.json").read_text(encoding="utf-8"))
        return history_losses(state.get("log_history", []))

    problems += compare_losses(history(whole), history(chunked), whole["manifest"].get("total_steps") or 0)
    diff = norm = None
    files = [r["dir"] / rs.PAYLOAD / "adapter_model.safetensors" for r in (whole, chunked)]
    if not all(f.is_file() for f in files):
        problems.append("final adapter missing from a verified chunk")
    else:
        diff, norm = (adapter_diff or _adapter_diff_files)(*files)
        problems += adapter_problems(diff, norm)
    return {"gate": {"atol": ATOL, "rtol": RTOL, "adapter_rel_l2": ADAPTER_REL_L2},
            "whole": whole["manifest"]["chunk_id"], "chunked": chunked["manifest"]["chunk_id"],
            "adapter_l2_diff": diff, "adapter_l2_norm": norm, "problems": problems,
            "result": "PASS" if not problems else "FAIL"}


def _adapter_diff_files(a: Path, b: Path):
    from safetensors.torch import load_file
    return adapter_difference(load_file(str(a)), load_file(str(b)))


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
    size = sub.add_parser("sizing-record")
    size.add_argument("--store", type=Path, required=True)
    size.add_argument("--run-id", required=True)
    size.add_argument("--out", type=Path, required=True)
    cmp = sub.add_parser("compare")
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
    if args.cmd == "sizing-record":
        import resumable as rs
        marked = [p for p in sorted(args.store.glob(f"{args.run_id}-step*")) if (p / rs.MARKER).exists()]
        if not args.run_id.startswith("sizing-") or not marked:
            raise SystemExit(f"no committed sizing chunk for {args.run_id} in {args.store}")
        bindings = json.loads((marked[-1] / rs.MANIFEST).read_bytes())["bindings"]
        record = rs.sizing_record(args.store, args.run_id, bindings)  # verifies the chain with its own bindings
        with open(args.out, "x", encoding="utf-8") as out:
            json.dump(record, out, indent=2, sort_keys=True)
        print(f"measured checkpoint size {record['checkpoint_bytes']} bytes from {record['chunk_id']}")
        return 0
    verdict = compare(args.whole_store, args.whole_run, args.chunk_store, args.chunk_run)
    print(json.dumps(verdict, indent=2))
    return 0 if verdict["result"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
