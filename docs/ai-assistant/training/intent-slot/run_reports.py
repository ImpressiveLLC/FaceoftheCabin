"""Report builders for the M920q intent/slot runs (HO-2026-10-16 steps 4 and 5). Standard library only.

  python run_reports.py baseline BASELINE.json [--peak-rss-mib N] [--digest SHA] [--preflight FILE]
  python run_reports.py shadow AUDIT.log [--since ISO8601]

`baseline` turns the file evaluate.py wrote into the evidence block that goes on the PR. `shadow` reads the
`assistant.proposal.audit` lines of the backend container log (one JSON object per propose call) and counts
what the shadow run saw. Neither judges quality: 22 fixtures measure the tooling, not the model, and whether a
resolved target was the right one is a human call, so the shadow report lists them for review instead.
"""
import argparse
import json
import math
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def percentile(values, p):
    """Nearest-rank percentile; empty input gives None."""
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(p / 100 * len(ordered)) - 1)]


def _fmt(value, unit="", digits=2):
    return "n/a" if value is None else f"{value:.{digits}f}{unit}"


def gold_intents(gold_path):
    return {row["id"]: row["target"]["intent"]
            for row in map(json.loads, filter(str.strip, Path(gold_path).read_text(encoding="utf-8").splitlines()))}


def baseline_evidence(report, gold_path=ROOT / "gold.jsonl", peak_rss_mib=None, digest=None, preflight=None):
    """Markdown evidence block from an evaluate.py report."""
    timings = report.get("timings_seconds", {})
    order = list(timings)
    warm = [timings[i] for i in order[1:]]
    errors = report.get("transport_errors", {})
    timeouts = sum(1 for e in errors.values() if "timeout" in str(e).lower())
    intents = gold_intents(gold_path)
    slices = defaultdict(lambda: [0, 0])
    for d in report.get("details", []):
        s = slices[intents.get(d["id"], "?")]
        s[1] += 1
        s[0] += bool(d["intent_correct"])
    lines = [
        f"## Prompt-only baseline: `{report.get('model')}`",
        "",
        f"**What this measures:** {report['count']} project-authored gold fixtures, labels still `pending-human-review`. It tests the "
        "tooling and gives a first look at the prompt-only model. It is **not** a quality result and not release evidence.",
        "",
        "| Gate (offline) | Result | Threshold | Pass |",
        "|---|---|---|---|",
        f"| Structured output rate | {report['valid_output_rate']:.1%} | 95% | {report['offline_gates']['structured_output']} |",
        f"| Intent accuracy | {report['intent_accuracy']:.1%} | 90% | {report['offline_gates']['intent_accuracy']} |",
        f"| Slot micro F1 | {report['slot_micro_f1']:.3f} | 0.90 | {report['offline_gates']['slot_f1']} |",
        f"| Critical fixture failures | {report['critical_fixture_failures']} | 0 | {report['offline_gates']['critical_fixture_failures']} |",
        "",
        f"Slot counts: {report['slot_counts']}. Fixtures: {report['count']}. Gold sha256: `{report.get('gold_sha256')}`.",
        "",
        "Intent accuracy by gold intent: " + ", ".join(f"{k} {a}/{n}" for k, (a, n) in sorted(slices.items())) + ".",
        "",
        "| Latency (s) | |",
        "|---|---|",
        f"| First request (includes model load) | {_fmt(timings[order[0]]) if order else 'n/a'} |",
        f"| p50 (excluding first) | {_fmt(percentile(warm, 50))} |",
        f"| p95 (excluding first) | {_fmt(percentile(warm, 95))} |",
        f"| max | {_fmt(max(timings.values()) if timings else None)} |",
        f"| Timeouts / transport errors | {timeouts} / {len(errors)} {dict(Counter(errors.values())) if errors else ''} |",
        "",
        f"Peak RSS during the run: {_fmt(peak_rss_mib, ' MiB', 0)}. Model digest: `{digest or 'not recorded'}`.",
        "",
        "Integration gates, still **NOT_MEASURED**: " + ", ".join(sorted(report.get("integration_gates", {}))) + ".",
        "`release_eligible` is " + str(report.get("release_eligible")).lower() + ". No SFT training ran in this step.",
    ]
    if preflight:
        lines += ["", "<details><summary>Host state before the run</summary>", "", "```", preflight.strip(), "```", "</details>"]
    return "\n".join(lines) + "\n"


def parse_audit_lines(lines):
    """Audit entries from raw log text; any line without a JSON object, or without a requestId, is skipped."""
    entries = []
    for line in lines:
        start = line.find("{")
        if start < 0:
            continue
        try:
            entry = json.loads(line[start:])
        except json.JSONDecodeError:
            continue
        if isinstance(entry, dict) and "requestId" in entry and "status" in entry:
            entries.append(entry)
    return entries


def shadow_report(entries, since=None):
    if since:
        entries = [e for e in entries if str(e.get("at", "")) >= since]
    by_intent = Counter(e.get("intent") or "(none)" for e in entries)
    by_status = Counter(e["status"] for e in entries)
    by_code = Counter(e.get("code") or "-" for e in entries)
    outcomes = Counter(d["outcome"] for e in entries for d in e.get("decisions", []))
    model = [e["modelLatencyMillis"] / 1000 for e in entries if e.get("modelLatencyMillis")]
    total = [e["totalLatencyMillis"] / 1000 for e in entries if e.get("totalLatencyMillis") is not None]
    refused = [e for e in entries if any(d["outcome"] not in ("RESOLVED",) for d in e.get("decisions", []))]
    resolved = [e for e in entries if e["status"] in ("PROPOSAL", "TARGET")]

    def table(counter, title):
        return [f"| {title} | Count |", "|---|---|"] + [f"| {k} | {v} |" for k, v in sorted(counter.items(), key=lambda kv: -kv[1])] + [""]

    lines = [f"## Shadow report: {len(entries)} propose call(s)", ""]
    if entries:
        lines += [f"From {min(str(e.get('at')) for e in entries)} to {max(str(e.get('at')) for e in entries)}. "
                  f"Models seen: {sorted({e.get('model') for e in entries})}. Executor: none exists; nothing ran.", ""]
    lines += table(by_intent, "Intent") + table(by_status, "Status") + table(by_code, "Refusal code")
    lines += table(outcomes, "Resolver outcome (per target)")
    lines += ["| Latency (s) | p50 | p95 | max |", "|---|---|---|---|",
              f"| Model | {_fmt(percentile(model, 50))} | {_fmt(percentile(model, 95))} | {_fmt(max(model) if model else None)} |",
              f"| Whole request | {_fmt(percentile(total, 50))} | {_fmt(percentile(total, 95))} | {_fmt(max(total) if total else None)} |", ""]
    lines += [f"### Every target that resolved ({len(resolved)}): a person marks each right or wrong", "",
              "| When | Request | Resolved to | Plan |", "|---|---|---|---|"]
    for e in resolved:
        targets = ", ".join(f"{d['role']}:{d.get('entityId')}" for d in e.get("decisions", []) if d["outcome"] == "RESOLVED")
        lines.append(f"| {e.get('at')} | {str(e.get('input'))[:120]} | {targets} | {str(e.get('planHash') or '')[:12]} |")
    lines += ["", f"### Every refused or ambiguous target ({len(refused)})", "", "| When | Request | Outcome | Why |", "|---|---|---|---|"]
    for e in refused:
        for d in e.get("decisions", []):
            if d["outcome"] != "RESOLVED":
                lines.append(f"| {e.get('at')} | {str(e.get('input'))[:120]} | {d['outcome']} | {'; '.join(d.get('reasons', []))[:160]} |")
    bad_model = [e for e in entries if e.get("code") in ("MALFORMED_MODEL_OUTPUT", "MODEL_UNAVAILABLE")]
    lines += ["", f"### Model failures ({len(bad_model)})", ""]
    for e in bad_model:
        lines.append(f"- {e.get('at')} {e['code']}: {str(e.get('validation'))[:160]}")
    return "\n".join(lines) + "\n"


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    b = sub.add_parser("baseline")
    b.add_argument("report", type=Path)
    b.add_argument("--gold", type=Path, default=ROOT / "gold.jsonl")
    b.add_argument("--peak-rss-mib", type=float)
    b.add_argument("--digest")
    b.add_argument("--preflight", type=Path)
    s = sub.add_parser("shadow")
    s.add_argument("log", type=Path)
    s.add_argument("--since")
    args = p.parse_args(argv)
    if args.cmd == "baseline":
        report = json.loads(args.report.read_text(encoding="utf-8"))
        pre = args.preflight.read_text(encoding="utf-8") if args.preflight else None
        sys.stdout.write(baseline_evidence(report, args.gold, args.peak_rss_mib, args.digest, pre))
    else:
        entries = parse_audit_lines(args.log.read_text(encoding="utf-8", errors="replace").splitlines())
        sys.stdout.write(shadow_report(entries, args.since))


if __name__ == "__main__":
    main()
