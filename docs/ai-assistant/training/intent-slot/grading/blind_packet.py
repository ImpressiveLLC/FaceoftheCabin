"""Synthetic S4 in-memory core; no delivery, authorization, import or approval.

Only ``packet_bytes`` may be projected to a reviewer after external access checks.
All other output is coordinator-only, including the frozen mapping. Callers must
retain it privately and immutably: hashes establish consistency, not authenticity.
"""
from dataclasses import dataclass
import hashlib
import json
import math
import re
import secrets

from contract import request_content, validate
from .review_packets import check_source_rows

VERSION = 1


def _json_types(value):
    if value is None or type(value) in (str, bool, int):
        return
    if type(value) is float and math.isfinite(value):
        return
    if type(value) is list:
        for item in value:
            _json_types(item)
        return
    if type(value) is dict and all(type(k) is str for k in value):
        for item in value.values():
            _json_types(item)
        return
    raise ValueError("unsupported JSON type or nonfinite number")


def canonical_bytes(value, *, version=VERSION):
    """V1: Unicode key order, compact UTF-8, no normalization or terminal LF.

    Numbers use Python JSON encoding; this is not RFC 8785/JCS.
    """
    if type(version) is not int or version != VERSION:
        raise ValueError("unsupported canonical version")
    _json_types(value)
    return json.dumps(value, sort_keys=True, ensure_ascii=False,
                      separators=(",", ":"), allow_nan=False).encode("utf-8")


def strict_loads(raw):
    """Parse UTF-8 JSON without duplicate keys, BOMs or nonfinite numbers."""
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError("duplicate JSON key")
            result[key] = value
        return result

    def nonfinite(_):
        raise ValueError("nonfinite JSON number")

    if type(raw) is bytes:
        raw = raw.decode("utf-8")
    if type(raw) is not str:
        raise ValueError("expected JSON text or UTF-8 bytes")
    value = json.loads(raw, object_pairs_hook=pairs, parse_constant=nonfinite)
    canonical_bytes(value)  # Also rejects overflow (1e999) and lone surrogates.
    return value


def _sha(value):
    return hashlib.sha256(canonical_bytes(value)).hexdigest()


def _name(value):
    return type(value) is str and bool(value.strip())


def _sources(rows, family_map):
    # Round-trip both validates JSON types and takes a detached snapshot.
    rows = strict_loads(canonical_bytes(rows))
    family_map = strict_loads(canonical_bytes(family_map))
    if type(rows) is not list or not rows or type(family_map) is not dict:
        raise ValueError("nonempty source list and family map required")
    by_id = {}
    for row in rows:
        if type(row) is not dict or not _name(row.get("id")):
            raise ValueError("nonempty source ID required")
        if row["id"] in by_id:
            raise ValueError("duplicate source ID")
        if not _name(row.get("group")) or type(row.get("provenance")) is not dict:
            raise ValueError("family and provenance required")
        check_source_rows([row])
        if not _name(row["provenance"]["revision"]):
            raise ValueError("source revision required")
        if not _name(row.get("text")):
            raise ValueError("nonblank source text required")
        request_content(row["text"], row.get("card_target"))
        validate(row.get("target"), card_target=row.get("card_target"))
        by_id[row["id"]] = row
    owners = {}
    for family, ids in family_map.items():
        if not _name(family) or type(ids) is not list or not ids:
            raise ValueError("invalid family binding")
        for rid in ids:
            if not _name(rid) or rid in owners:
                raise ValueError("duplicate or invalid family source ID")
            owners[rid] = family
    if set(owners) != set(by_id):
        raise ValueError("family map must cover exactly the assigned cohort")
    if any(row["group"] != owners[rid] for rid, row in by_id.items()):
        raise ValueError("source family differs from canonical binding")
    return by_id, family_map


@dataclass(frozen=True)
class Packet:
    """Immutable bytes; protected_bytes and frozen_mapping MUST stay private."""
    packet_bytes: bytes
    protected_bytes: bytes
    frozen_mapping: bytes


def build_packet(rows, *, recipient_id, family_map, frozen_mapping=None,
                 expected_mapping_sha256=None):
    """Build one recipient's exact cohort, or reproduce a pinned mapping.

    ``family_map`` is {canonical_family: [source_id, ...]} for exactly this
    recipient's cohort. ``frozen_mapping`` is the private bytes from a prior
    result. Rebuilds require its SHA-256 pinned separately by the caller in
    ``expected_mapping_sha256``; a digest supplied with an untrusted mapping is
    not an authenticity anchor. New calls allocate random 128-bit IDs and shuffle;
    rebuilds consume no randomness and require unchanged sources/metadata.
    Admission, reviewer eligibility and policy pinning belong to the caller.
    Malformed JSON/bindings raise ValueError; schema failures retain the existing
    contract ValidationError. Source IDs are nonblank strings, not path names.
    """
    if frozen_mapping is None:
        if expected_mapping_sha256 is not None:
            raise ValueError("mapping digest requires a frozen mapping")
    else:
        if type(frozen_mapping) is not bytes:
            raise ValueError("frozen mapping must be immutable bytes")
        if (type(expected_mapping_sha256) is not str
                or not re.fullmatch(r"[0-9a-f]{64}", expected_mapping_sha256)):
            raise ValueError("rebuild requires a pinned mapping SHA-256")
        if hashlib.sha256(frozen_mapping).hexdigest() != expected_mapping_sha256:
            raise ValueError("frozen mapping SHA-256 differs from pin")
    if not _name(recipient_id):
        raise ValueError("recipient ID required")
    by_id, families = _sources(rows, family_map)
    commitments = []
    metadata = []
    for rid, row in sorted(by_id.items()):
        payload = {"version": VERSION, "id": rid, "text": row["text"],
                   "card_target": row.get("card_target"), "target": row["target"]}
        commitments.append({"source_id": rid, "row_sha256": _sha(payload)})
        metadata.append({"source_id": rid, "metadata": {
            k: v for k, v in row.items() if k not in ("id", "text", "card_target", "target")}})
    binding = {"version": VERSION, "recipient_id": recipient_id,
               "cohort_sha256": _sha({"version": VERSION, "rows": commitments}),
               "metadata_sha256": _sha({"version": VERSION, "rows": metadata,
                                          "family_map": families})}
    if frozen_mapping is None:
        entries = []
        allocated = set()
        for item in commitments:
            opaque = secrets.token_hex(16)
            if opaque in allocated:
                raise ValueError("opaque ID collision")
            allocated.add(opaque)
            row = by_id[item["source_id"]]
            entries.append({**item, "opaque_id": opaque, "family": row["group"],
                            "source_revision": row["provenance"]["revision"]})
        secrets.SystemRandom().shuffle(entries)
        mapping = {**binding, "run_id": secrets.token_hex(16), "entries": entries}
    else:
        mapping = strict_loads(frozen_mapping)
    _check_mapping(mapping, binding, by_id, commitments)
    frozen = canonical_bytes(mapping)
    packet = b"".join(canonical_bytes({"id": entry["opaque_id"],
                       "text": by_id[entry["source_id"]]["text"],
                       "card_target": by_id[entry["source_id"]].get("card_target")}) + b"\n"
                      for entry in mapping["entries"])
    protected = {**binding, "run_id": mapping["run_id"], "status": "BUILT_NOT_DELIVERED",
                 "source_commitments": commitments, "metadata": metadata,
                 "family_map": families, "crosswalk": mapping["entries"],
                 "mapping_sha256": _sha(mapping),
                 "packet_bytes_sha256": hashlib.sha256(packet).hexdigest()}
    return Packet(packet, canonical_bytes(protected), frozen)


def _check_mapping(mapping, binding, by_id, commitments):
    if type(mapping) is not dict or set(mapping) != set(binding) | {"run_id", "entries"}:
        raise ValueError("invalid frozen mapping fields")
    if any(canonical_bytes(mapping[k]) != canonical_bytes(v) for k, v in binding.items()):
        raise ValueError("stale source, metadata, recipient or version")
    if type(mapping["run_id"]) is not str or not re.fullmatch(r"[0-9a-f]{32}", mapping["run_id"]):
        raise ValueError("invalid opaque run ID")
    entries = mapping["entries"]
    if type(entries) is not list or len(entries) != len(by_id):
        raise ValueError("mapping must cover the cohort")
    hashes = {item["source_id"]: item["row_sha256"] for item in commitments}
    seen_source, seen_opaque = set(), set()
    for entry in entries:
        if type(entry) is not dict or set(entry) != {
                "source_id", "row_sha256", "opaque_id", "family", "source_revision"}:
            raise ValueError("invalid crosswalk fields")
        rid, opaque = entry["source_id"], entry["opaque_id"]
        if not _name(rid) or rid not in by_id or rid in seen_source:
            raise ValueError("unknown or duplicate mapping source ID")
        if type(opaque) is not str or not re.fullmatch(r"[0-9a-f]{32}", opaque) or opaque in seen_opaque:
            raise ValueError("invalid or duplicate opaque ID")
        row = by_id[rid]
        if (entry["family"] != row["group"] or entry["source_revision"] != row["provenance"]["revision"]
                or entry["row_sha256"] != hashes[rid]):
            raise ValueError("changed crosswalk binding")
        seen_source.add(rid)
        seen_opaque.add(opaque)
