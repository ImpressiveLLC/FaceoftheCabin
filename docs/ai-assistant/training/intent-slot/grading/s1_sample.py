"""S1 of the fine-tuning data path: assign paraphrase families, audit coverage, draw the stratified review sample.

Reads the private candidates file (never committed), writes the sample and its family map. The committed record is
the manifest and the family map, which hold ids, counts and hashes and no candidate text. Reviews nothing, trains nothing.
"""
import argparse
import hashlib
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

HOLD_OUT_SOURCE = "ha"
DEFAULT_SEED = "s1-2026-10-19"
DEFAULT_TARGET = 200
MIN_PER_CELL = 3
NEAR_DUPLICATE = 0.75

FILLER = {"please", "can", "could", "would", "will", "you", "the", "a", "an", "my", "me", "to", "in", "of", "up", "hey", "i",
          "it", "for", "is", "are", "be", "do", "just", "now", "and", "that", "this", "your", "our", "at", "your"}
NUMBER = re.compile(r"\d+(?:\.\d+)?")
DEVICE_WORDS = (("light", {"light", "lights", "lamp", "lamps", "bulb", "bulbs", "lighting"}),
                ("climate", {"thermostat", "heat", "heating", "temperature", "ac", "cooling", "heater"}),
                ("fan", {"fan", "fans"}),
                ("lock", {"lock", "door", "garage"}),
                ("media", {"tv", "speaker", "music", "radio"}))
ATTRIBUTE_WORDS = (("brightness", {"brightness", "dim", "dimmer", "brighter", "bright", "percent"}),
                   ("color", {"color", "colour", "warm", "cool", "red", "blue", "green"}),
                   ("temperature", {"degrees", "degree", "temperature"}))
ROOMS = ("living room", "kitchen", "bedroom", "hallway", "hall", "office", "garage", "dining room", "bathroom", "basement")


def tokens(text):
    return re.findall(r"[a-z0-9]+(?:\.\d+)?", text.casefold().replace("%", " percent"))


def core(text):
    """Order-free content tokens: same words in a different order, with or without politeness, are one wording."""
    return frozenset(t for t in tokens(text) if t not in FILLER)


def must_agree(words):
    """Tokens that change the meaning of a command: a pair that differs here is never a paraphrase."""
    return frozenset(t for t in words if t in {"on", "off", "open", "close", "lock", "unlock"} or NUMBER.fullmatch(t))


def near(a, b):
    if must_agree(a) != must_agree(b):
        return False
    union = a | b
    return bool(union) and len(a & b) / len(union) >= NEAR_DUPLICATE


def families(rows):
    """Union rows whose wording is the same or a near duplicate (same polarity and numbers). Returns row id -> family id."""
    cores = [core(r["text"]) for r in rows]
    parent = list(range(len(rows)))

    def find(i):
        while parent[i] != i:
            parent[i] = parent[parent[i]]
            i = parent[i]
        return i

    exact = {}
    for i, words in enumerate(cores):
        key = tuple(sorted(words))
        if key in exact:
            parent[find(i)] = find(exact[key])
        else:
            exact[key] = i
    for i in range(len(rows)):
        for j in range(i + 1, len(rows)):
            if find(i) != find(j) and near(cores[i], cores[j]):
                parent[find(j)] = find(i)
    members = defaultdict(list)
    for i in range(len(rows)):
        members[find(i)].append(i)
    out = {}
    for indexes in members.values():
        ids = sorted(rows[i]["id"] for i in indexes)
        family = "fam-" + hashlib.sha256("|".join(ids).encode()).hexdigest()[:12]
        for i in indexes:
            out[rows[i]["id"]] = family
    return out


def word_class(words, table, default):
    for name, vocabulary in table:
        if words & vocabulary:
            return name
    return default


def room_of(text):
    low = " ".join(tokens(text))
    return next((room for room in ROOMS if room in low), "none")


def label_flags(row):
    """Cheap, honest signals that the source label may be wrong. They do not remove a row; they are counted and left for review."""
    slots = row["target"].get("slots") or {}
    low = " ".join(tokens(row["text"]))
    flags = []
    for key, value in slots.items():
        spoken = " ".join(tokens(value)) if isinstance(value, str) else ""
        if spoken and f" {spoken} " not in f" {low} ":
            flags.append("slot_value_not_in_text")
            break
    if row["target"]["intent"] == "set_device_attribute" and not slots.get("attribute_value"):
        flags.append("attribute_without_value")
    if not slots:
        flags.append("no_slots")
    if slots.get("location") and slots.get("device_name") and slots["location"].casefold() == slots["device_name"].casefold():
        flags.append("location_equals_device")
    return flags


def stratum(row):
    words = core(row["text"])
    return (row["target"]["intent"], word_class(words, DEVICE_WORDS, "other"), word_class(words, ATTRIBUTE_WORDS, "none"))


def rank(seed, key):
    return hashlib.sha256(f"{seed}|{key}".encode()).hexdigest()


def draw(pool, family_of, seed, target):
    """One row per family (the lowest seeded hash), then a quota per stratum: a floor for every cell, the rest by square root of size.

    Within a stratum the next pick is the family whose room is least represented so far, so rooms spread out too.
    """
    by_family = defaultdict(list)
    for row in pool:
        by_family[family_of[row["id"]]].append(row)
    reps = {fam: min(rs, key=lambda r: rank(seed, r["id"])) for fam, rs in by_family.items()}
    cells = defaultdict(list)
    for fam, row in reps.items():
        cells[stratum(row)].append(row)
    target = min(target, len(reps))
    quota = {cell: min(len(rs), MIN_PER_CELL) for cell, rs in cells.items()}
    while sum(quota.values()) > target:
        quota[max(quota, key=lambda c: (quota[c], c))] -= 1
    spare = target - sum(quota.values())
    weights = {cell: len(rs) ** 0.5 for cell, rs in cells.items()}
    while spare > 0:
        open_cells = [c for c in cells if quota[c] < len(cells[c])]
        if not open_cells:
            break
        total = sum(weights[c] for c in open_cells)
        best = max(open_cells, key=lambda c: (weights[c] / total * target - quota[c], c))
        quota[best] += 1
        spare -= 1
    chosen = []
    for cell in sorted(cells):
        rooms = Counter()
        left = sorted(cells[cell], key=lambda r: rank(seed, "pick|" + r["id"]))
        for _ in range(quota[cell]):
            row = min(left, key=lambda r: (rooms[room_of(r["text"])], rank(seed, "pick|" + r["id"])))
            left.remove(row)
            rooms[room_of(row["text"])] += 1
            chosen.append(row)
    return sorted(chosen, key=lambda r: r["id"]), reps, cells, quota


def sha256_of(lines):
    return hashlib.sha256("\n".join(lines).encode()).hexdigest()


def audit(rows, family_of):
    """Counts only: nothing here repeats a candidate's text."""
    fam_sizes = Counter(family_of[r["id"]] for r in rows)
    flags = Counter(flag for r in rows for flag in label_flags(r))
    return {
        "rows": len(rows),
        "families": len(fam_sizes),
        "family_size_histogram": dict(sorted(Counter(fam_sizes.values()).items())),
        "largest_family": max(fam_sizes.values()) if fam_sizes else 0,
        "by_intent": dict(sorted(Counter(r["target"]["intent"] for r in rows).items())),
        "by_device_class": dict(sorted(Counter(word_class(core(r["text"]), DEVICE_WORDS, "other") for r in rows).items())),
        "by_room": dict(sorted(Counter(room_of(r["text"]) for r in rows).items())),
        "label_flags": dict(sorted(flags.items())),
        "rows_with_a_flag": sum(1 for r in rows if label_flags(r)),
    }


def build(rows, seed=DEFAULT_SEED, target=DEFAULT_TARGET):
    sources = Counter(r["provenance"]["source"] for r in rows)
    held_out = [r for r in rows if r["provenance"]["source"] == HOLD_OUT_SOURCE]
    # Families are assigned over every row; a training row that shares a family with a held-out row is itself held out.
    family_of = families(rows)
    held_families = {family_of[r["id"]] for r in held_out}
    cross = held_families & {family_of[r["id"]] for r in rows if r["provenance"]["source"] != HOLD_OUT_SOURCE}
    pool = [r for r in rows if r["provenance"]["source"] != HOLD_OUT_SOURCE and family_of[r["id"]] not in held_families]
    sample, reps, cells, quota = draw(pool, family_of, seed, target)
    sample_ids = [r["id"] for r in sample]
    family_map = defaultdict(list)
    for row in rows:
        family_map[family_of[row["id"]]].append(row["id"])
    manifest = {
        "step": "S1",
        "seed": seed,
        "target": target,
        "rules": {"hold_out_source": HOLD_OUT_SOURCE, "rows_per_family": 1, "min_per_cell": MIN_PER_CELL,
                  "near_duplicate_jaccard": NEAR_DUPLICATE, "family_key": "content tokens, order-free, filler removed, on/off/open/close/lock/unlock and numbers must agree"},
        "input_rows": len(rows),
        "by_source": dict(sorted(sources.items())),
        "pool_rows": len(pool),
        "held_out_rows": len(held_out),
        "families_spanning_held_out_and_pool": len(cross),
        "pool_rows_dropped_for_sharing_a_held_out_family": sum(
            1 for r in rows if r["provenance"]["source"] != HOLD_OUT_SOURCE and family_of[r["id"]] in held_families),
        "audit_pool": audit(pool, family_of),
        "audit_held_out": audit(held_out, family_of) if held_out else None,
        "pool_family_count": len(reps),
        "strata": [{"intent": c[0], "device_class": c[1], "attribute_class": c[2], "families": len(cells[c]), "sampled": quota[c]}
                   for c in sorted(cells)],
        "sample_count": len(sample),
        "sample_by_intent": dict(sorted(Counter(r["target"]["intent"] for r in sample).items())),
        "sample_by_room": dict(sorted(Counter(room_of(r["text"]) for r in sample).items())),
        "sample_with_label_flag": sum(1 for r in sample if label_flags(r)),
        "suspect_source_label_ids": [r["id"] for r in sample if label_flags(r)],
        "spanning_family_ids": sorted(cross),
        "sample_ids": sample_ids,
        "sample_sha256": sha256_of(sample_ids),
        "families_sha256": sha256_of(sorted(f"{fam}:{','.join(sorted(ids))}" for fam, ids in family_map.items())),
        "reviewed": 0,
        "training_run": False,
    }
    return manifest, sample, {fam: sorted(ids) for fam, ids in sorted(family_map.items())}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidates", type=Path, required=True, help="private candidates.jsonl from collect_sources.py")
    parser.add_argument("--private-output", type=Path, required=True, help="folder for sample.jsonl (has candidate text; never commit)")
    parser.add_argument("--committed-output", type=Path, required=True, help="folder for s1-manifest.json and families.json (ids and counts only)")
    parser.add_argument("--seed", default=DEFAULT_SEED)
    parser.add_argument("--target", type=int, default=DEFAULT_TARGET)
    args = parser.parse_args()
    rows = [json.loads(line) for line in args.candidates.read_text(encoding="utf-8").splitlines() if line.strip()]
    manifest, sample, family_map = build(rows, args.seed, args.target)
    manifest["candidate_sha256"] = hashlib.sha256(args.candidates.read_bytes()).hexdigest()
    args.private_output.mkdir(parents=True, exist_ok=True)
    args.committed_output.mkdir(parents=True, exist_ok=True)
    suspect = set(manifest["suspect_source_label_ids"])
    (args.private_output / "sample.jsonl").write_text(
        "".join(json.dumps(dict(r, suspect_source_label=r["id"] in suspect), sort_keys=True) + chr(10) for r in sample),
        encoding="utf-8")
    (args.committed_output / "s1-manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    (args.committed_output / "families.json").write_text(json.dumps(family_map, indent=1, sort_keys=True) + "\n", encoding="utf-8")
    json.dump({k: manifest[k] for k in ("input_rows", "pool_rows", "held_out_rows", "pool_family_count", "sample_count",
                                         "sample_by_intent", "sample_with_label_flag", "sample_sha256")}, sys.stdout, indent=1)
    print()


if __name__ == "__main__":
    main()
