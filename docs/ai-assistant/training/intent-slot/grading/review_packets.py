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
    """What two labels must agree on: the whole semantic target. Case and spacing are folded in strings; numbers, dates, triggers,
    conditions and actions are compared as they are. Only the wording of a clarification question is not compared, just that
    one is asked."""
    def fold(value):
        if isinstance(value, str):
            return " ".join(value.casefold().split())
        if isinstance(value, dict):
            return {k: fold(v) for k, v in sorted(value.items())}
        if isinstance(value, list):
            return [fold(v) for v in value]
        return value
    semantic = {k: fold(v) for k, v in target.items() if k != "clarification"}
    semantic["clarification_asked"] = bool(target.get("clarification"))
    return json.dumps(semantic, sort_keys=True)


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False)


def agreed_row_sha(row):
    """Binds an agreed row's id, text and target. Recomputed downstream, so editing a row after the audit is detected."""
    return hashlib.sha256(canonical([row["id"], row["text"], row["target"]]).encode()).hexdigest()


def cohort_sha(agreed_rows):
    """The exact set of agent-agreed rows an audit is about (ids, text, targets, track)."""
    tracks = sorted({r["provenance"]["track"] for r in agreed_rows})
    return hashlib.sha256(canonical([tracks, sorted(agreed_row_sha(r) for r in agreed_rows)]).encode()).hexdigest()


def audit_size(cohort_n):
    return max(1, round(cohort_n * AUDIT_SHARE)) if cohort_n else 0


def select_audit(agreed_rows, seed):
    ordered = sorted(agreed_rows, key=lambda r: r["id"])
    return sorted(random.Random(seed).sample(ordered, audit_size(len(ordered))), key=lambda r: r["id"]) if ordered else []


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
                "files": files, "file_sha256": {f: sha256_file(out_dir / f) for f in files},
                "blind": True, "fields_given_to_reviewer": ["id", "text"]}
    (out_dir / "packet.manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return manifest


def check_packet(packet_dir, manifest, by_id):
    """The packet on disk must be exactly what make-packet wrote for this sample: same files, same bytes, same id and text."""
    packet_dir = Path(packet_dir)
    if sorted(manifest.get("files", [])) != sorted(p.name for p in packet_dir.glob("packet-*.jsonl")):
        raise ValueError("packet files differ from the manifest (added or missing file)")
    seen = {}
    for name in manifest["files"]:
        path = packet_dir / name
        if manifest.get("file_sha256", {}).get(name) != sha256_file(path):
            raise ValueError(f"packet file altered since make-packet: {name}")
        for item in read_jsonl(path):
            if sorted(item) != ["id", "text"]:
                raise ValueError(f"packet row has fields beyond id and text: {item.get('id')}")
            if item["id"] in seen:
                raise ValueError(f"duplicate id in packet: {item['id']}")
            seen[item["id"]] = item["text"]
    if set(seen) != set(by_id):
        raise ValueError("packet ids differ from the sample (missing or invented rows)")
    altered = sorted(i for i, t in seen.items() if t != by_id[i]["text"])
    if altered:
        raise ValueError(f"packet text differs from the sample for {len(altered)} rows, e.g. {altered[:5]}")
    ids_hash = hashlib.sha256("\n".join(sorted(seen)).encode()).hexdigest()
    if ids_hash != manifest["ids_sha256"]:
        raise ValueError("packet ids hash does not match the manifest")


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
    check_packet(packet_dir, manifest, by_id)
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
                review_status="agent-agreed", reviewer=reviewer, reviewed_at=now, reviewer_kind="agent",
                reviewer_model=reviewer_model, second_reviewer=second_reviewer if second else None,
                agreement="agreed", track=track, purpose="training-candidate")
            out.pop("suspect_source_label", None)
            out["provenance"]["agreed_row_sha256"] = agreed_row_sha(out)
            approved.append(out)
        elif state == "needs_second":
            pending.append(rid)
        else:
            q = json.loads(json.dumps(row))
            q["provenance"].update(review_status="unreviewed", purpose="training-candidate", track=track,
                                   review_notes=f"agent labels disagree; first {reviewer}: {label_key(first[rid])}; "
                                                f"second {second_reviewer}: {label_key(second[rid])}; source: {label_key(row['target'])}")
            queue_nate.append(q)
    audit = select_audit(approved, seed)
    cohort = cohort_sha(approved) if approved else None
    audit_manifest = {"track": track, "cohort_sha256": cohort, "cohort_n": len(approved), "audit_n": len(audit), "seed": seed,
                      "audit_ids": [r["id"] for r in audit],
                      "audit_ids_sha256": hashlib.sha256("\n".join(r["id"] for r in audit).encode()).hexdigest()}
    if sha256_file(sample_path) != original_hash:
        raise RuntimeError("the sample file changed during import")
    report = {"track": track, "reviewer": reviewer, "reviewer_kind": "agent", "second_reviewer": second_reviewer,
              "rows": len(ids), "approved_by_agreement": len(approved), "needs_second_reviewer": len(pending),
              "to_nate": len(queue_nate), "audit_sample": len(audit),
              "suspect_rows": sum(1 for r in rows if r.get("suspect_source_label")),
              "suspect_rows_approved_by_source_agreement": 0,
              "needs_second_ids": pending, "sample_sha256": original_hash, "cohort_sha256": cohort}
    if out_dir:
        out_dir = Path(out_dir)
        out_dir.mkdir(parents=True, exist_ok=True)
        for name, items in (("agreed.jsonl", approved), ("queue-nate.jsonl", queue_nate), ("audit.jsonl", audit)):
            (out_dir / name).write_text("".join(json.dumps(i, sort_keys=True) + "\n" for i in items), encoding="utf-8")
        (out_dir / "audit.manifest.json").write_text(json.dumps(audit_manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        (out_dir / "report.json").write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return report, approved, queue_nate, audit


AUDIT_PASS_RATE = 0.9


def record_audit(agreed_path, audit_path, decisions_path, audited_by, out_path, manifest_path=None):
    """Nate's audit of one exact cohort of agent-agreed rows. The audit sample must be the one the importer drew from that
    cohort (same ids, same size, same cohort hash); decisions must cover exactly it. At 90 percent or better the cohort's rows become
    `approved` with the audit identities linked; a row marked wrong is withheld; below 90 percent nothing is promoted.
    `audited_by` is attribution, not authentication: the decisions themselves must come from Nate."""
    agreed = read_jsonl(agreed_path)
    by_id = unique_rows(agreed, "agreed rows")
    if not agreed:
        raise ValueError("no agreed rows to audit")
    manifest_path = Path(manifest_path) if manifest_path else Path(audit_path).with_name("audit.manifest.json")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    for r in agreed:
        if r["provenance"].get("review_status") != "agent-agreed" or r["provenance"].get("agreed_row_sha256") != agreed_row_sha(r):
            raise ValueError(f"agreed row changed since import or not agent-agreed: {r['id']}")
    cohort = cohort_sha(agreed)
    if manifest["cohort_sha256"] != cohort:
        raise ValueError("stale or foreign audit: it was drawn from a different cohort of agreed rows")
    audit_rows = read_jsonl(audit_path)
    audit_ids = [r["id"] for r in audit_rows]
    if len(set(audit_ids)) != len(audit_ids):
        raise ValueError("duplicate ids in the audit sample")
    if not set(audit_ids) <= set(by_id):
        raise ValueError("audit sample contains ids that are not in the agreed cohort")
    if len(audit_ids) != audit_size(len(agreed)) or len(audit_ids) != manifest["audit_n"]:
        raise ValueError(f"audit sample has {len(audit_ids)} rows; {audit_size(len(agreed))} are required for {len(agreed)} agreed rows")
    expected = [r["id"] for r in select_audit(agreed, manifest["seed"])]
    if sorted(audit_ids) != expected or manifest["audit_ids"] != expected:
        raise ValueError("audit sample is not the sample the importer drew from this cohort")
    decisions = read_jsonl(decisions_path)
    dec = unique_rows(decisions, "audit decisions")
    if sorted(dec) != sorted(audit_ids):
        raise ValueError("audit decisions must cover exactly the audit sample, no more and no fewer")
    if not audited_by or audited_by.lower().startswith(("codex", "cowork", "claude", "agent")):
        raise ValueError("an audit must be recorded by a person (Nate), not an agent")
    if any(not isinstance(d.get("agree"), bool) for d in decisions):
        raise ValueError("every audit decision needs agree: true or false")
    agreed_n = sum(1 for d in decisions if d["agree"])
    rate = agreed_n / len(decisions)
    if rate < AUDIT_PASS_RATE:
        raise ValueError(f"audit agreement {rate:.2f} is below {AUDIT_PASS_RATE}; nothing promoted")
    wrong = {d["id"] for d in decisions if not d["agree"]}
    result = {"audited_by": audited_by, "attribution_only": True, "audited_at": datetime.now(timezone.utc).isoformat(),
              "sample_n": len(decisions), "agreed_n": agreed_n, "rate": rate, "cohort_sha256": cohort, "cohort_n": len(agreed),
              "track": manifest["track"], "audit_ids_sha256": manifest["audit_ids_sha256"]}
    promoted = []
    for rid in sorted(by_id):
        if rid in wrong:
            continue
        row = json.loads(json.dumps(by_id[rid]))
        row["provenance"].update(review_status="approved", audit_result=result)
        promoted.append(row)
    Path(out_path).write_text("".join(json.dumps(r, sort_keys=True) + "\n" for r in promoted), encoding="utf-8")
    return {**result, "promoted": len(promoted), "withheld": sorted(wrong)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="cmd", required=True)
    au = sub.add_parser("record-audit")
    au.add_argument("--agreed", type=Path, required=True)
    au.add_argument("--audit-sample", type=Path, required=True)
    au.add_argument("--audit-manifest", type=Path, help="default: audit.manifest.json beside the audit sample")
    au.add_argument("--decisions", type=Path, required=True, help="JSONL {id, agree} for exactly the audit sample")
    au.add_argument("--audited-by", required=True)
    au.add_argument("--out", type=Path, required=True)
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
    elif args.cmd == "record-audit":
        print(json.dumps(record_audit(args.agreed, args.audit_sample, args.decisions, args.audited_by, args.out, args.audit_manifest), indent=1))
    else:
        report = import_labels(args.sample, args.packet, args.labels, args.reviewer, args.reviewer_model, args.track,
                               args.second_labels, args.second_reviewer, args.second_model, args.out)[0]
        print(json.dumps({k: v for k, v in report.items() if k != "needs_second_ids"}, indent=1))


if __name__ == "__main__":
    main()
