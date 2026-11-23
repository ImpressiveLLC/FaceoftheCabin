"""Require per-record review and, for agent labels, a passed audit by the elected auditor; keep paraphrase groups and gold out of training.

S7 G1 (#536 build spec): with S6 evidence (freeze record, ids-only family export, screen-train result) the train rows the
screen quarantined are dropped, every identity is checked against the freeze before any row is split, and a row whose
family is a frozen test or safety family but was not quarantined fails the run. Without S6 evidence the only way to run is
--dry-run-no-s6, which writes no training files and reports s6 and frozen_family_check as NOT_RUN. The tool never opens a
frozen set, the refusal pack, keyed hashes or the key; the family export is ids only. VALIDATED ONLY WHEN S6 AND
screen-train EXIST: until then this is tested on synthetic evidence only.

Two #536 gates are not met yet, so a frozen-aware run from the command line is REFUSED (Codex HOLD 7059474381):
- G8 (pair families bound to `group` before the split) has not landed; step 5 would check source groups, not families.
- The approved-input read boundary of #536 section 3.7 is only partly enforced: INPUT may not be an S6 file, and its
  bytes are hashed and checked against the freeze record's frozen-set hashes before they are parsed, but there is no
  approved-input list yet, so a path the caller passes is still opened. Cowork rules on whether that is enough.
Only --dry-run-no-s6 runs from the CLI until both are resolved; the frozen-aware path is exercised by synthetic tests.
"""
import argparse
import fnmatch
import hashlib
import json
import re
from dataclasses import dataclass
from pathlib import Path
from contract import ROOT, SYSTEM, read_jsonl, validate, write_jsonl, request_content
from vocabulary import field_errors, normalize_target


def normalize(text):
    return " ".join(text.casefold().split())


def check_agent_audit(row, provenance):
    """An agent label counts only after an audit of its exact cohort by the elected auditor (agent attribution under the owner amendment, #244 comment 6874199353, not a human audit) passed at 90 percent or better and is linked to the row.

    The link is the cohort hash and audit-sample hash recorded at promotion, the sample size the 10 percent rule requires, and the
    row's own hash of id, text and target at agreement time (so a row edited after the audit is refused). `audited_by` is attribution.
    """
    audit = provenance.get("audit_result") or {}
    hexes = all(isinstance(audit.get(k), str) and len(audit[k]) == 64 for k in ("cohort_sha256", "audit_ids_sha256"))
    cohort_n = audit.get("cohort_n", 0)
    required = max(1, round(cohort_n * 0.1)) if cohort_n else 0
    ok = (audit.get("audited_by") and hexes and audit.get("rate", 0) >= 0.9 and audit.get("track") == provenance.get("track")
          and cohort_n > 0 and audit.get("sample_n", 0) >= required)
    if not ok:
        raise ValueError(f"Agent label without a passed, linked audit by the elected auditor (owner amendment 6874199353): {row['id']}")
    bound = hashlib.sha256(json.dumps([row["id"], row["text"], row["target"]], sort_keys=True, ensure_ascii=False).encode()).hexdigest()
    if provenance.get("agreed_row_sha256") != bound:
        raise ValueError(f"Agent row changed after its audit: {row['id']}")


def rows_refused_by_training(splits):
    """Rows the training tokenizer (training/sft_tokenize.py, #444) will refuse: assistant content that starts with a
    newline merges with the "\n\n" that ends the assistant header, so segment-wise and whole-text tokenization differ
    and the trainer aborts the job. Reported here so a row is fixed or dropped before training, not found in the middle of it.
    json.dumps output starts with "{", so this is empty today; it guards a future change to the target format."""
    refused = []
    for split, rows in splits.items():
        for row in rows:
            for message in row["messages"]:
                if message["role"] == "assistant" and message["content"][:1] in ("\n", "\r"):
                    refused.append({"id": row["id"], "split": split, "reason": "assistant content starts with a newline; the training tokenizer refuses it"})
    return refused


EMPTY_LIST_SHA256 = hashlib.sha256(b"").hexdigest()
REQUIRED_SCREEN_TOOLS = ("screen-train",)  # the screening tool identity a screen result must carry, nothing else
G8_BOUND_FAMILIES = False  # flips to True only in the PR that lands G8 (#536 section 6)
_HEX64 = re.compile(r"[0-9a-f]{64}")
FAMILY_EXPORT_KEYS = {"freeze_id", "test_family_ids", "safety_family_ids"}
_BAD_ID = re.compile(r"[\x00-\x1f\x7f\u0085\u2028\u2029]")


class S6Error(ValueError):
    """S6 evidence is missing, inconsistent or unsafe; nothing is split or written."""


def list_hash(ids, *, what, allow_empty=False):
    """sha256 of the ids sorted by code point, joined by one LF, no trailing newline, UTF-8 (#536 section 2.3)."""
    if not isinstance(ids, list):
        raise S6Error(f"{what} must be a list of ids")
    if not ids and not allow_empty:
        raise S6Error(f"{what} is empty; a freeze has non-empty sets")
    for value in ids:
        if not isinstance(value, str) or not value or _BAD_ID.search(value):
            raise S6Error(f"{what} holds an id that is not a plain non-empty string without control characters: {value!r}")
    if len(set(ids)) != len(ids):
        raise S6Error(f"{what} holds a duplicate id")
    return hashlib.sha256("\n".join(sorted(ids)).encode("utf-8")).hexdigest()


def file_sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def read_rows_snapshot(path):
    """Reads INPUT once, LF-only, and returns (rows, sha256 of exactly those bytes)."""
    raw = Path(path).read_bytes()
    rows = [json.loads(line) for line in raw.decode("utf-8").split("\n") if line.strip()]
    return rows, hashlib.sha256(raw).hexdigest()


def is_family_map(name):
    """The one predicate for a committed family map, used for discovery and for the reverse check: sft/s1/families.json
    or any direct child of sft/ matching *families*.json."""
    if name == "sft/s1/families.json":
        return True
    head, _, tail = name.partition("/")
    return head == "sft" and "/" not in tail and fnmatch.fnmatchcase(tail, "*families*.json")


def recompute_inputs(gold_path, root=None):
    """The committed files S6 must have hashed, keyed as the freeze record's `inputs` names them (relative to the package)."""
    root = Path(root) if root else ROOT
    files = {"gold.jsonl": Path(gold_path), "system.txt": root / "system.txt",
             "intent-slot.schema.json": root / "intent-slot.schema.json",
             "sft/s1/families.json": root / "sft" / "s1" / "families.json"}
    for path in sorted((root / "sft").iterdir()):
        if path.is_file() and is_family_map(f"sft/{path.name}"):
            files[f"sft/{path.name}"] = path
    return files


def _load_json(path, what):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except FileNotFoundError:
        raise S6Error(f"{what} {path} does not exist")
    except ValueError as exc:
        raise S6Error(f"{what} {path} is not valid JSON ({exc})")


@dataclass(frozen=True)
class Frozen:
    freeze_id: str
    exclusion: tuple
    quarantine_ids: frozenset
    quarantine_ids_sha256: str


def load_frozen(freeze_record, family_export, screen_result, input_sha256, recompute):
    """Checks the three S6 files against each other, the INPUT bytes and the committed recompute inputs (#536 section 3,
    items 2 and 3). Every failure raises S6Error naming the mismatch; nothing is returned partially."""
    record = _load_json(freeze_record, "freeze record")
    export = _load_json(family_export, "family export")
    screen = _load_json(screen_result, "screen result")
    if not isinstance(record, dict) or record.get("kind") != "freeze":
        raise S6Error("freeze record kind is not 'freeze'")
    if record.get("result") != "FROZEN":
        raise S6Error(f"freeze result is {record.get('result')!r}, not FROZEN; FROZEN_WITH_GAPS does not satisfy S6")
    freeze_id = record.get("freeze_id")
    if not isinstance(freeze_id, str) or not freeze_id:
        raise S6Error("freeze record has no freeze_id")
    sets = record.get("sets") or {}
    expected = {name: (sets.get(name) or {}).get("family_ids_sha256") for name in ("test", "safety")}
    if not all(isinstance(v, str) and len(v) == 64 for v in expected.values()):
        raise S6Error("freeze record lacks a per-set family_ids_sha256 for both test and safety")

    if not isinstance(export, dict) or set(export) != FAMILY_EXPORT_KEYS:
        raise S6Error(f"family export must hold exactly {sorted(FAMILY_EXPORT_KEYS)}; refusing it as custody-unsafe")
    if export["freeze_id"] != freeze_id:
        raise S6Error("family export is for another freeze_id")
    test_ids, safety_ids = export["test_family_ids"], export["safety_family_ids"]
    for name, ids in (("test", test_ids), ("safety", safety_ids)):
        if list_hash(ids, what=f"{name}_family_ids") != expected[name]:
            raise S6Error(f"{name}_family_ids do not hash to the freeze record's family_ids_sha256")
    if set(test_ids) & set(safety_ids):
        raise S6Error("test and safety family ids overlap (S6 check C6)")

    if not isinstance(screen, dict) or screen.get("kind") != "screen-train":
        raise S6Error("screen result kind is not 'screen-train'")
    if screen.get("result") != "pass":
        raise S6Error(f"screen result is {screen.get('result')!r}, not pass")
    if screen.get("freeze_id") != freeze_id:
        raise S6Error("screen result is for another freeze_id")
    record_tool, screen_tool = record.get("tool"), screen.get("tool")
    if not isinstance(record_tool, dict) or not isinstance(screen_tool, dict):
        raise S6Error("freeze record and screen result must each carry a tool map")
    if set(screen_tool) != set(REQUIRED_SCREEN_TOOLS):
        raise S6Error(f"screen result tool keys {sorted(screen_tool)} are not exactly {list(REQUIRED_SCREEN_TOOLS)}")
    for key in REQUIRED_SCREEN_TOOLS:
        value, pinned = screen_tool[key], record_tool.get(key)
        if not isinstance(value, str) or not _HEX64.fullmatch(value):
            raise S6Error(f"screen result tool {key} is not a SHA-256 hex digest")
        if not isinstance(pinned, str) or not _HEX64.fullmatch(pinned):
            raise S6Error(f"freeze record does not pin a SHA-256 for tool {key}")
        if value != pinned:
            raise S6Error(f"screen result tool {key} is not the one the freeze record lists")
    if screen.get("train_file_sha256") != input_sha256:
        raise S6Error("screen result is stale: it was made for different INPUT bytes; run screen-train again")
    quarantine = screen.get("quarantine_ids")
    if list_hash(quarantine, what="quarantine_ids", allow_empty=True) != screen.get("quarantine_ids_sha256"):
        raise S6Error("quarantine_ids do not hash to quarantine_ids_sha256")

    inputs = record.get("inputs")
    if not isinstance(inputs, dict):
        raise S6Error("freeze record has no inputs map")
    for name, path in sorted(recompute.items()):
        if name not in inputs:
            raise S6Error(f"recompute input {name} is not in the freeze record's inputs")
        if not Path(path).is_file():
            raise S6Error(f"recompute input {name} ({path}) is not present here")
        if file_sha256(path) != inputs[name]:
            raise S6Error(f"recompute input {name} has changed since the freeze")
    for name in inputs:
        if (name in ("gold.jsonl", "system.txt", "intent-slot.schema.json") or is_family_map(name)) and name not in recompute:
            raise S6Error(f"freeze record lists {name} but it is not found here")
    for name in ("test", "safety"):
        if (sets.get(name) or {}).get("sha256") == input_sha256:
            raise S6Error(f"INPUT has the bytes of the frozen {name} set; refusing to read a frozen set as training input")
    return Frozen(freeze_id, tuple(sorted(set(test_ids) | set(safety_ids))), frozenset(quarantine),
                  screen["quarantine_ids_sha256"])


class FamilyBindingError(ValueError):
    """The family map does not bind every row, or would spread a minimal pair over families; nothing is split."""


_PAIR_TOKEN = re.compile(r"-pair(?=-|$)")


def pair_base(group):
    """The raw group a minimal-pair counterpart belongs with: 'x-pair' -> 'x', 'x-pair-3' -> 'x-3'."""
    return _PAIR_TOKEN.sub("", group)


def pair_relations(rows):
    """(base, pair) raw-group pairs that are both present: the halves of a minimal pair (S7 G8, #529).

    The generator gives every template its own raw `group`, and an opposite-label minimal pair is two templates, so
    splitting on the raw group can put the two halves in different splits. These are the halves that must stay together.
    """
    groups = {row["group"] for row in rows}
    return [(pair_base(g), g) for g in sorted(groups) if _PAIR_TOKEN.search(g) and pair_base(g) in groups]


def compose_family_maps(*maps):
    """Apply family maps in order, replacing an entry that repeats a canonical key with the newer, complete list.

    An overlay that replaces a family must retain every earlier member, and no id may end up in two families
    (concatenating the lists of two maps would duplicate ownership). Raises FamilyBindingError otherwise.
    """
    composed = {}
    for index, family_map in enumerate(maps):
        for family, ids in family_map.items():
            ids = list(ids)
            if len(set(ids)) != len(ids):
                raise FamilyBindingError(f"map {index}: family {family} lists an id twice")
            if family in composed:
                dropped = set(composed[family]) - set(ids)
                if dropped:
                    raise FamilyBindingError(
                        f"map {index} replaces family {family} but drops {len(dropped)} earlier member(s); "
                        "an overlay must retain every prior member")
            composed[family] = ids
    owner = {}
    for family, ids in composed.items():
        for rid in ids:
            if rid in owner and owner[rid] != family:
                raise FamilyBindingError(f"id {rid} is in two families: {owner[rid]} and {family}")
            owner[rid] = family
    return composed


def bind_families(rows, family_map):
    """Rows with `group` replaced by their canonical family id (the raw value is kept as `source_group`).

    Every row must be in the map, ids must be unique, and the two halves of a minimal pair must share one family, so a
    pair can never be split by the hash-bucket rule. The input rows are not modified.
    """
    owner = {}
    for family, ids in sorted(family_map.items()):
        for rid in ids:
            if rid in owner and owner[rid] != family:
                raise FamilyBindingError(f"id {rid} is in two families: {owner[rid]} and {family}")
            owner[rid] = family
    ids = [row["id"] for row in rows]
    duplicated = sorted({i for i in ids if ids.count(i) > 1})
    if duplicated:
        raise FamilyBindingError(f"duplicate row ids: {', '.join(duplicated[:5])}")
    unbound = sorted(i for i in ids if i not in owner)
    if unbound:
        raise FamilyBindingError(f"{len(unbound)} row(s) are not in the family map, e.g. {', '.join(unbound[:5])}; "
                                 "an unbound row, and so an unbound pair half, is refused")
    bound = [dict(row, source_group=row.get("group"), group=owner[row["id"]]) for row in rows]
    families_of = {}
    for row in bound:
        families_of.setdefault(row["source_group"], set()).add(row["group"])
    for base, pair in pair_relations(rows):
        spread = families_of[base] | families_of[pair]
        if len(spread) != 1:
            raise FamilyBindingError(f"minimal pair {base} / {pair} is spread over {len(spread)} families; "
                                     "its halves could land in different splits")
    return bound


def split_rows(rows, gold, *, frozen=None, families=None):
    """The train/validation/holdout splits. With frozen=None and families=None this is the gate as before (plus the G2
    rename). `families` is a composed family map (S7 G8): rows are bound to their canonical family before anything else."""
    return split_rows_with_report(rows, gold, frozen=frozen, families=families)[0]


def split_rows_with_report(rows, gold, *, frozen=None, families=None):
    rows_in = len(rows)
    pairs_bound = 0
    if families is not None:
        pairs_bound = len(pair_relations(rows))  # counted on the raw groups, before binding replaces them
        rows = bind_families(rows, families)
    quarantined_ids = []
    if frozen is not None:
        quarantined_ids = sorted(r["id"] for r in rows if r["id"] in frozen.quarantine_ids)
        rows = [r for r in rows if r["id"] not in frozen.quarantine_ids]
        exclusion = set(frozen.exclusion)
        leaked = sorted(r["id"] for r in rows if r["group"] in exclusion)
        if leaked:
            raise S6Error(f"{len(leaked)} row(s) in a frozen test or safety family were not quarantined by the screen: "
                          + ", ".join(leaked))
    result = _split_rows(rows, gold)
    if frozen is not None:
        # #536 section 3.8: frozen-aware output does not depend on input order.
        result = {k: sorted(v, key=lambda row: row["id"]) for k, v in result.items()}
    report = {
        "s6": "FROZEN" if frozen is not None else "NOT_RUN",
        "freeze_id": frozen.freeze_id if frozen is not None else None,
        "rows_in": rows_in,
        "quarantined": {"count": len(quarantined_ids),
                        "quarantine_ids_sha256": frozen.quarantine_ids_sha256 if frozen is not None else None},
        "dropped_gold": 0,
        "rows_out": sum(len(v) for v in result.values()),
        "splits": {k: len(v) for k, v in sorted(result.items())},
        "families": {k: len({r["group"] for r in v}) for k, v in sorted(result.items())},
        "frozen_family_check": "pass" if frozen is not None else "NOT_RUN",
    }
    if families is not None:
        report["families_bound"] = True
        report["pair_relations_bound"] = pairs_bound
    return result, report, quarantined_ids


def _split_rows(rows, gold):
    if not rows:
        raise ValueError("Empty dataset")
    forbidden_text = {normalize(r["text"]) for r in gold}
    forbidden_group = {r["group"] for r in gold}
    result = {"train": [], "validation": [], "holdout": []}
    seen = set()
    for row in rows:
        provenance = row["provenance"]
        if (provenance.get("review_status") != "approved" or
                not all(provenance.get(k) for k in ("reviewer", "reviewed_at", "source", "revision", "sha256", "license"))):
            raise ValueError(f"Missing human approval/provenance: {row['id']}")
        if provenance.get("reviewer_kind") == "agent":
            check_agent_audit(row, provenance)
        text = normalize(row["text"])
        # Codex HOLD 7094064757: binding renames `group`, so the gold-family exclusion checks the original group
        # (`source_group`) as well as the canonical one; renaming a gold family must not admit its rows.
        groups = {row["group"], row.get("source_group", row["group"])}
        if not text or text in seen or text in forbidden_text or groups & forbidden_group:
            raise ValueError(f"Empty, duplicate or held-out example: {row['id']}")
        if not row["group"]:
            raise ValueError("Missing paraphrase family")
        seen.add(text)
        validate(row["target"], row.get("card_target"))
        problems = field_errors(row["target"])
        if not problems and normalize_target(row["target"])[1]:
            problems = {"canonical": "a room or site is not in its canonical form; approve it in the grading screen, which normalizes it"}
        if problems:
            raise ValueError(f"Room, site or need not in the vocabulary ({row['id']}): {'; '.join(problems.values())}")
        # S7 G2: the internal 10 percent hash-bucket split is "holdout", not "test"; "test" means only the S6 frozen test.
        bucket = int(hashlib.sha256(row["group"].encode()).hexdigest(), 16) % 10
        split = "holdout" if bucket == 0 else "validation" if bucket == 1 else "train"
        result[split].append({"messages": [{"role": "system", "content": SYSTEM},
                                            {"role": "user", "content": request_content(row["text"], row.get("card_target"))},
                                            {"role": "assistant", "content": json.dumps(row["target"])}],
                              "id": row["id"], "group": row["group"], "provenance": provenance})
    if any(not result[k] for k in result):
        raise ValueError("Need enough independent reviewed families for nonempty train/validation/holdout")
    return result


def main(argv=None, *, _synthetic_test_only=False):
    p = argparse.ArgumentParser()
    p.add_argument("input", type=Path)
    p.add_argument("output_dir", type=Path)
    p.add_argument("--gold", type=Path, default=ROOT / "gold.jsonl")
    p.add_argument("--freeze-record", type=Path)
    p.add_argument("--family-export", type=Path)
    p.add_argument("--screen-result", type=Path)
    p.add_argument("--family-map", type=Path, action="append", default=[],
                   help="a family map; repeat to stack overlays, applied in order and replacing by canonical key")
    p.add_argument("--dry-run-no-s6", action="store_true",
                   help="run the gates without S6 evidence; writes only report.json, never training files")
    args = p.parse_args(argv)
    s6_paths = {"--freeze-record": args.freeze_record, "--family-export": args.family_export,
                "--screen-result": args.screen_result}
    given = [flag for flag, value in s6_paths.items() if value is not None]
    if args.dry_run_no_s6 and given:
        p.error("--dry-run-no-s6 cannot be combined with S6 evidence")
    if not args.dry_run_no_s6 and len(given) != 3:
        missing = [flag for flag, value in s6_paths.items() if value is None]
        p.error("S6 evidence is required: give --freeze-record, --family-export and --screen-result together, or "
                f"--dry-run-no-s6 (missing: {', '.join(missing)})")
    paths = ([args.input.resolve()] + [Path(v).resolve() for v in s6_paths.values() if v is not None]
             + [Path(v).resolve() for v in args.family_map])
    if len(set(paths)) != len(paths):
        p.error("INPUT and the S6 evidence files must be different files")

    if not args.dry_run_no_s6 and not _synthetic_test_only and not G8_BOUND_FAMILIES:
        raise SystemExit("frozen-aware runs are refused until S7 G8 (bound families) lands and Cowork rules on the "
                         "#536 read boundary; use --dry-run-no-s6. Nothing was read or written")
    raw = args.input.read_bytes()
    input_sha256 = hashlib.sha256(raw).hexdigest()
    frozen = None
    if not args.dry_run_no_s6:
        try:
            # The frozen-set bytes check runs here, before INPUT is parsed.
            frozen = load_frozen(args.freeze_record, args.family_export, args.screen_result, input_sha256,
                                 recompute_inputs(args.gold))
        except S6Error as exc:
            raise SystemExit(f"S6 check failed: {exc}; nothing was split or written")
    rows = [json.loads(line) for line in raw.decode("utf-8").split("\n") if line.strip()]
    families = None
    try:
        if args.family_map:
            try:
                loaded = [_load_json(path, "family map") for path in args.family_map]
            except S6Error as exc:
                raise FamilyBindingError(str(exc))
            families = compose_family_maps(*loaded)
        splits, report, quarantined_ids = split_rows_with_report(rows, read_jsonl(args.gold), frozen=frozen,
                                                                 families=families)
    except FamilyBindingError as exc:
        raise SystemExit(f"family binding failed: {exc}; nothing was split or written")
    except S6Error as exc:
        raise SystemExit(f"S6 check failed: {exc}; nothing was split or written")
    if args.dry_run_no_s6:
        print("NOT_RUN: S6 (dry run; no training files written)")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / "report.json").write_text(json.dumps(report, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    if not args.dry_run_no_s6:
        for split, split_rows_out in splits.items():
            write_jsonl(args.output_dir / f"{split}.jsonl", split_rows_out)
        # Private: ids of dropped rows, never posted to the hub (counts only there).
        (args.output_dir / "quarantined.private.json").write_text(
            json.dumps({"freeze_id": frozen.freeze_id, "quarantined_ids": quarantined_ids}, indent=2) + "\n",
            encoding="utf-8")
    print(json.dumps(report, sort_keys=True))
    refused = rows_refused_by_training(splits)
    print(json.dumps({"refused_by_training_leading_newline": len(refused), "rows": refused}))
    if refused:
        raise SystemExit(f"{len(refused)} row(s) would be refused by the training tokenizer; fix or drop them: " + ", ".join(r["id"] for r in refused))
    return report


if __name__ == "__main__":
    main()
