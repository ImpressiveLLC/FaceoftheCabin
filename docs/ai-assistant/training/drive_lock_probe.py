#!/usr/bin/env python3
"""Measures whether resumable.py's lease guard holds on a given store (Colab slice B; Codex 7104080408 condition 1).

The guard is an exclusive file create. On a local filesystem that is atomic, and slice A's tests prove it. On a
Google Drive mount shared by two Colab runtimes it is UNQUALIFIED: Drive syncs files between runtimes asynchronously,
and nothing here assumes an exclusive create on one runtime is visible to the other in time. This probe measures it.

Run the same command at the same time in two runtimes (a person-run step; Nate's gate), each with its own --label:
  python drive_lock_probe.py run --store /content/drive/MyDrive/fotc-probe --label runtime-a --seconds 300
then, from either one:
  python drive_lock_probe.py report --store /content/drive/MyDrive/fotc-probe

Each runtime repeatedly acquires the lease, records the interval it held it, and releases it. `report` merges the
intervals: any overlap between two labels means two writers held the lease at once on that store, and the store is
NOT QUALIFIED for unattended resume. No overlap over a run is evidence, not proof; the report says how much it saw.
Uses invented data only: the lease file and the probe's own interval logs.
"""
import argparse
import json
import sys
import time
from pathlib import Path

import resumable as rs

PROBE_RUN = "lockprobe"
STATUS_UNQUALIFIED = "UNQUALIFIED"


def run(store: Path, label: str, seconds: float, hold: float = 0.5, clock=time.time, sleep=time.sleep,
        min_holds: int = 0, max_seconds: float | None = None, gap: float | None = None) -> dict:
    """Acquire, hold, release, repeatedly, for SECONDS, and until this runtime has held the lease at least MIN_HOLDS
    times (bounded by MAX_SECONDS). After each release it waits GAP (default: HOLD) before trying again, a handoff
    that lets the other runtime in: mutual exclusion does not promise fairness, so without it one runtime can keep
    reacquiring while the other is only refused, and the run would show one label and prove nothing."""
    log = store / "probe" / f"{label}.jsonl"
    log.parent.mkdir(parents=True, exist_ok=True)
    held = refused = 0
    gap = hold if gap is None else gap
    start = clock()
    deadline = start + seconds
    hard = start + (max_seconds if max_seconds is not None else max(seconds, 1.0) * 30)
    with open(log, "a", encoding="utf-8") as out:
        while (clock() < deadline or held < min_holds) and clock() < hard:
            try:
                token = rs.acquire_lease(store, PROBE_RUN, label)
            except rs.CommitError:
                refused += 1
                sleep(hold / 5)
                continue
            begin = clock()
            sleep(hold)
            end = clock()
            released = None
            while released is None:  # a release that cannot get the guard in time is retried, never skipped
                try:
                    released = rs.release_lease(store, PROBE_RUN, token)
                except rs.CommitError:
                    sleep(hold / 5)
            out.write(json.dumps({"label": label, "token": token, "start": begin, "end": end,
                                  "released": released}) + "\n")
            out.flush()
            held += 1
            sleep(gap)
    return {"label": label, "held": held, "refused": refused, "met_min_holds": held >= min_holds}


def report(store: Path) -> dict:
    intervals = []
    for log in sorted((store / "probe").glob("*.jsonl")):
        for line in log.read_text(encoding="utf-8").splitlines():
            if line.strip():
                intervals.append(json.loads(line))
    intervals.sort(key=lambda i: i["start"])
    overlaps = []
    for index, first in enumerate(intervals):
        for second in intervals[index + 1:]:
            if second["start"] >= first["end"]:
                break
            if second["label"] != first["label"]:
                overlaps.append({"a": first["label"], "b": second["label"],
                                 "overlap_seconds": min(first["end"], second["end"]) - second["start"]})
    labels = sorted({i["label"] for i in intervals})
    unreleased = sum(1 for i in intervals if not i["released"])
    if len(labels) < 2:
        verdict = STATUS_UNQUALIFIED + " (fewer than two runtimes ran the probe)"
    elif overlaps or unreleased:
        verdict = "NOT QUALIFIED: two writers held the lease at once" if overlaps else \
            "NOT QUALIFIED: a release did not find its own lease"
    else:
        verdict = "NO OVERLAP OBSERVED (evidence for this store and run length only; reviewer decides qualification)"
    return {"labels": labels, "intervals": len(intervals), "overlaps": overlaps, "unreleased": unreleased,
            "observed_seconds": (intervals[-1]["end"] - intervals[0]["start"]) if intervals else 0,
            "verdict": verdict}


def main(argv=None):
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("--store", type=Path, required=True)
    r.add_argument("--label", required=True)
    r.add_argument("--seconds", type=float, default=300)
    p = sub.add_parser("report")
    p.add_argument("--store", type=Path, required=True)
    args = parser.parse_args(argv)
    out = run(args.store, args.label, args.seconds) if args.cmd == "run" else report(args.store)
    print(json.dumps(out, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
