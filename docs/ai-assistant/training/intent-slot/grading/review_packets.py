"""S4/S5 review packets: hand reviewers blind text, import their labels, route disagreements, never approve a suspect label.

  make-packet  sample.jsonl -> packet-<track>.jsonl (id and text only, no source label) + packet.manifest.json
  import       labels from a first reviewer (and optionally a second) -> approved.jsonl, queue-nate.jsonl, audit.jsonl, report.json

Rows and labels are private (they carry candidate text); the committed record, if any, is report.json (counts only).
An agent label is recorded as an agent label. Nothing here trains, exports or edits gold.
"""
import argparse
import hashlib
import json
import random
import sys
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from contract import validate  # noqa: E402

AUDIT_SHARE = 0.10
PROVENANCE_KEYS = ("source", "revision", "sha256", "license")


def sha256_file(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def read_jsonl(path):
    return [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]


def unique_rows(rows, what):
    ids = [r["id"] for r in rows]
    dupes = sorted(i for i, n in Counter(ids).items() if n > 1)
    if dupes:
        raise ValueError(f"duplicate ids in {what}: {dupes[:5]}")
    return {r["id"]: r for r in rows}


def check_source_rows(rows):
    for row in rows:
        missing = [k for k in PROVENANCE_KEYS if not (row.get("provenance") or {}).get(k)]
        if missing:
            raise ValueError(f"{row['id']}: source provenance missing {missing}")
        if not row.get("group"):
            raise ValueError(f"{row['id']}: no paraphrase family (group)")


def label_key(target):
    """What two labels must agree on: intent and slots, case and spacing folded. Wording of a question is not compared."""
    slots = {k: " ".join(str(v).casefold().split()) for k, v in (target.get("slots") or {}).items()}
    return json.dumps([target["intent"], sorted(slots.items())])


def make_packet(sample_path, track, out_dir, size=50):
    rows = read_jsonl(sample_path)
    unique_rows(rows, "sample")
    check_source_rows(rows)
    out_dir = Path(out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    packet = [{"id": r["id"], "text": r["text"]} for r in sorted(rows, key=lambda r: r["id"])]
    files = []
    for n in range(0, len(packet), size):
        name = f"packet-{track}-{n // size + 1:02d}.jsonl"
        (out_dir / name).write_text("".join(json.dumps(p, sort_keys=True) + "\n" for p in packet[n:n + size]), encoding="utf-8")
        files.append(name)
    manifest = {"track": track, "sample_sha256": sha256_file(sample_path), "rows": len(packet),
                "ids_sha256": hashlib.sha256("\n".join(p["id"] for p in packet).encode()).hexdigest(),
                "files": files, "blind": True, "fields_given_to_reviewer": ["id", "text"]}
    (out_dir / "packet.manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return manifest


def load_labels(path, expected_ids, who):
    rows = read_jsonl(path)
    ids = [r["id"] for r in rows]
    dupes = sorted(i for i, n in Counter(ids).items() if n > 1)
    if dupes:
        raise ValueError(f"{who}: duplicate label ids {dupes[:5]}")
    unknown = sorted(set(ids) - expected_ids)
    if unknown:
        raise ValueError(f"{who}: labels for ids not in the packet {unknown[:5]}")
    missing = sorted(expected_ids - set(ids))
    if missing:
        raise ValueError(f"{who}: no label for {len(missing)} packet ids, e.g. {missing[:5]}")
    labels = {}
    for row in rows:
        try:
            labels[row["id"]] = validate(row["target"])
        except Exception as exc:  # contract violation or malformed target: refuse the whole import, not just the row
            raise ValueError(f"{who}: invalid label for {row['id']}: {str(exc).splitlines()[0]}") from exc
    return labels


def decide(row, first, second, track="A"):
    """Return (state, agreed_label). States: agreed, needs_second, nate.

    Tracks B and C (authored rows) need two blind reviewers who agree; anything else goes to Nate.
    A suspect source label never counts as agreement with the reviewer: it needs a second reviewer who matches the first.
    Otherwise the first reviewer matching the source approves; a mismatch needs a second reviewer, and a second reviewer who
    matches either the source or the first settles it. Anything else goes to Nate.
    """
    src, a = label_key(row["target"]), label_key(first)
    if track in ("B", "C"):
        # Authored rows: the author's label never approves anything; two blind reviewers must match each other.
        if second is None:
            return "needs_second", None
        return ("agreed", first) if label_key(second) == a else ("nate", None)
    if row.get("suspect_source_label"):
        if second is None:
            return "needs_second", None
        return ("agreed", first) if label_key(second) == a else ("nate", None)
    if a == src:
        return "agreed", row["target"]
    if second is None:
        return "needs_second", None
    b = label_key(second)
    if b == src:
        return "agreed", row["target"]
    if b == a:
        return "agreed", first
    return "nate", None


def import_labels(sample_path, packet_dir, labels_path, reviewer, reviewer_model, track,
                  second_path=None, second_reviewer=None, second_model=None, out_dir=None, seed="s5-audit"):
    packet_dir = Path(packet_dir)
    manifest = json.loads((packet_dir / "packet.manifest.json").read_text(encoding="utf-8"))
    if manifest["sample_sha256"] != sha256_file(sample_path):
        raise ValueError("stale packet: the sample changed after the packet was made; make a new packet")
    if manifest["track"] != track:
        raise ValueError(f"packet is track {manifest['track']}, import says {track}")
    original_hash = sha256_file(sample_path)
    rows = read_jsonl(sample_path)
    by_id = unique_rows(rows, "sample")
    check_source_rows(rows)
    ids = set(by_id)
    first = load_labels(labels_path, ids, reviewer)
    second = load_labels(second_path, ids, second_reviewer or "second reviewer") if second_path else None
    if second_path and not second_reviewer:
        raise ValueError("a second label file needs --second-reviewer")
    if second_reviewer and second_reviewer == reviewer:
        raise ValueError("the second reviewer must differ from the first")
    now = datetime.now(timezone.utc).isoformat()
    approved, queue_nate, pending = [], [], []
    for rid in sorted(ids):
        row = by_id[rid]
        state, label = decide(row, first[rid], second[rid] if second else None, track)
        if state == "agreed":
            out = json.loads(json.dumps(row))
            out["target"] = label
            out["provenance"].update(
                review_status="approved", reviewer=reviewer, reviewed_at=now, reviewer_kind="agent",
                reviewer_model=reviewer_model, second_reviewer=second_reviewer if second else None,
                agreement="agreed", track=track, purpose="training-candidate")
            out.pop("suspect_source_label", None)
            approved.append(out)
        elif state == "needs_second":
            pending.append(rid)
        else:
            q = json.loads(json.dumps(row))
            q["provenance"].update(review_status="unreviewed", purpose="training-candidate", track=track,
                                   review_notes=f"agent labels disagree; first {reviewer}: {label_key(first[rid])}; "
                                                f"second {second_reviewer}: {label_key(second[rid])}; source: {label_key(row['target'])}")
            queue_nate.append(q)
    rng = random.Random(seed)
    audit_n = max(1, round(len(approved) * AUDIT_SHARE)) if approved else 0
    audit = sorted(rng.sample(approved, audit_n), key=lambda r: r["id"]) if approved else []
    if sha256_file(sample_path) != original_hash:
        raise RuntimeError("the sample file changed during import")
    report = {"track": track, "reviewer": reviewer, "reviewer_kind": "agent", "second_reviewer": second_reviewer,
              "rows": len(ids), "approved_by_agreement": len(approved), "needs_second_reviewer": len(pending),
              "to_nate": len(queue_nate), "audit_sample": len(audit),
              "suspect_rows": sum(1 for r in rows if r.get("suspect_source_label")),
              "suspect_rows_approved_by_source_agreement": 0,
              "needs_second_ids": pending, "sample_sha256": original_hash}
    if out_dir:
        out_dir = Path(out_dir)
        out_dir.mkdir(parents=True, exist_ok=True)
        for name, items in (("approved.jsonl", approved), ("queue-nate.jsonl", queue_nate), ("audit.jsonl", audit)):
            (out_dir / name).write_text("".join(json.dumps(i, sort_keys=True) + "\n" for i in items), encoding="utf-8")
        (out_dir / "report.json").write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return report, approved, queue_nate, audit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="cmd", required=True)
    mk = sub.add_parser("make-packet")
    mk.add_argument("--sample", type=Path, required=True)
    mk.add_argument("--track", choices=["A", "B", "C"], required=True)
    mk.add_argument("--out", type=Path, required=True)
    mk.add_argument("--size", type=int, default=50)
    im = sub.add_parser("import")
    im.add_argument("--sample", type=Path, required=True)
    im.add_argument("--packet", type=Path, required=True)
    im.add_argument("--track", choices=["A", "B", "C"], required=True)
    im.add_argument("--labels", type=Path, required=True)
    im.add_argument("--reviewer", required=True)
    im.add_argument("--reviewer-model", required=True)
    im.add_argument("--second-labels", type=Path)
    im.add_argument("--second-reviewer")
    im.add_argument("--second-model")
    im.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    if args.cmd == "make-packet":
        print(json.dumps(make_packet(args.sample, args.track, args.out, args.size), indent=1))
    else:
        report = import_labels(args.sample, args.packet, args.labels, args.reviewer, args.reviewer_model, args.track,
                               args.second_labels, args.second_reviewer, args.second_model, args.out)[0]
        print(json.dumps({k: v for k, v in report.items() if k != "needs_second_ids"}, indent=1))


if __name__ == "__main__":
    main()
