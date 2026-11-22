#!/usr/bin/env python3
"""Generate docs/ontology/device-catalog.json (S2, design #475; roadmap #449).

The catalog is a GENERATED file: nobody edits it by hand. Inputs:
  - docs/ontology.yaml                      the triggers that name each device type (applies_to_device_type)
  - DeviceType.java (backend source)        type ids, category, reported fields (telemetryFields)
  - docs/ontology/catalog-seed.yaml         match rules, check-in windows, ontology-only and safety allowlists
Each entry records what it was built from (per-entry hashes), and the file records the hash of every input. The hash
is the check; the ontology `version` string is only a label (S8 ruling Q4).

Usage:
  python scripts/ontology/generate_catalog.py            write the catalog
  python scripts/ontology/generate_catalog.py --check    fail if the committed catalog differs from a regeneration
  python scripts/ontology/generate_catalog.py --check --baseline OLD.json   also apply the safety guard against OLD

Needs PyYAML (and jsonschema for --check). Deterministic: sorted keys, LF, UTF-8.
"""
import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
ONTOLOGY = ROOT / "docs" / "ontology.yaml"
SEED = ROOT / "docs" / "ontology" / "catalog-seed.yaml"
SCHEMA = ROOT / "docs" / "ontology" / "schema" / "device-catalog.schema.json"
CATALOG = ROOT / "docs" / "ontology" / "device-catalog.json"
DEVICE_TYPE_JAVA = (ROOT / "cabin-orchestration-platform" / "backend" / "src" / "main" / "java" / "com" / "cabin"
                    / "orchestrator" / "devices" / "model" / "DeviceType.java")
GENERATOR_VERSION = 1
FIX_COMMAND = "python scripts/ontology/generate_catalog.py"


class CatalogError(Exception):
    """An input is inconsistent; nothing is written."""


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def canonical_hash(value):
    return sha256_bytes(canonical(value).encode("utf-8"))


def parse_device_types(java_text):
    """(constants {name: category}, fields {name: sorted list}, fragments {name: text}) from DeviceType.java."""
    constants = {}
    fragments = {}
    for line in java_text.split("\n"):
        match = re.match(r"^\s{4}([A-Z][A-Z0-9_]*)\(DeviceCategory\.([A-Z_]+)\)\s*[,;]", line)
        if match:
            constants[match.group(1)] = match.group(2)
            fragments[match.group(1)] = line.strip()
    if not constants:
        raise CatalogError("no DeviceType constants found; the source format changed")
    body = re.search(r"public Set<String> telemetryFields\(\) \{(.*?)\n    \}", java_text, re.S)
    if not body:
        raise CatalogError("telemetryFields() not found in DeviceType.java")
    fields = {name: [] for name in constants}
    for case in re.finditer(r"case ([A-Z0-9_,\s]+?) -> Set\.of\(([^)]*)\);", body.group(1)):
        values = sorted(re.findall(r'"([^"]+)"', case.group(2)))
        for name in [n.strip() for n in case.group(1).split(",")]:
            if name not in constants:
                raise CatalogError(f"telemetryFields() names {name}, which is not a DeviceType constant")
            fields[name] = values
            fragments[name] += "|" + case.group(0).strip()
    return constants, fields, fragments


def ontology_references(root):
    """{device type: sorted list of ontology element dicts that name it in applies_to_device_type}."""
    refs = {}
    for element in root.get("elements", []):
        applies = element.get("applies_to_device_type")
        if isinstance(applies, list):
            for type_name in applies:
                refs.setdefault(str(type_name), []).append(element)
    for type_name in refs:
        refs[type_name].sort(key=lambda e: str(e.get("id")))
    return refs


def build(java_text, ontology_bytes, seed_bytes):
    constants, fields, fragments = parse_device_types(java_text)
    ontology = yaml.safe_load(ontology_bytes)
    seed = yaml.safe_load(seed_bytes)
    refs = ontology_references(ontology)
    rules = seed.get("match_rules", [])
    ids = [r["id"] for r in rules]
    if len(set(ids)) != len(ids):
        raise CatalogError("duplicate match rule ids in the seed")
    for rule in rules:
        if rule["returns"] not in constants:
            raise CatalogError(f"match rule {rule['id']} returns {rule['returns']}, which is not a DeviceType constant")
    defaults = seed["checkin_defaults"]
    for type_name in defaults["non_zigbee_by_type"]:
        if type_name not in constants:
            raise CatalogError(f"check-in default names {type_name}, which is not a DeviceType constant")
    ontology_only = seed.get("ontology_only", {})
    for type_name, info in ontology_only.items():
        if type_name in constants:
            raise CatalogError(f"{type_name} is now a DeviceType constant; remove it from ontology_only in the seed")
        if type_name not in refs:
            raise CatalogError(f"{type_name} is no longer named in docs/ontology.yaml; remove it from ontology_only")
        if not info.get("reason"):
            raise CatalogError(f"ontology_only {type_name} needs a reason")
    unexplained = sorted(set(refs) - set(constants) - set(ontology_only))
    if unexplained:
        raise CatalogError("docs/ontology.yaml names device types that are neither DeviceType constants nor listed in "
                           f"ontology_only: {', '.join(unexplained)}")
    for type_name in seed.get("safety_types", []):
        if type_name not in constants:
            raise CatalogError(f"safety type {type_name} is not a DeviceType constant")

    entries = []
    for type_name in sorted(constants):
        sites = sorted({r["site"] for r in rules if r["returns"] == type_name})
        rule_ids = [r["id"] for r in rules if r["returns"] == type_name]
        non_zigbee = defaults["non_zigbee_by_type"].get(type_name, defaults["non_zigbee_default"])
        seed_fragment = {"checkin": {"non_zigbee": non_zigbee, "zigbee_mains": defaults["zigbee_mains"],
                                     "zigbee_battery": defaults["zigbee_battery"]},
                         "rules": [r for r in rules if r["returns"] == type_name]}
        entries.append({
            "id": type_name,
            "status": "enum",
            "category": constants[type_name],
            "reported_fields": fields[type_name],
            "triggers_naming_type": [str(e["id"]) for e in refs.get(type_name, [])],
            "protocols": sites,
            "match_rule_ids": rule_ids,
            "checkin_minutes": seed_fragment["checkin"],
            "reason": None,
            "field_sources": {
                "id": "DeviceType.java", "category": "DeviceType.java", "reported_fields": "DeviceType.java",
                "triggers_naming_type": "docs/ontology.yaml", "protocols": "seed match rules",
                "match_rule_ids": "seed", "checkin_minutes": "seed"},
            "stamp": {
                "ontology_content_hash": canonical_hash(refs.get(type_name, [])),
                "seed_hash": canonical_hash(seed_fragment),
                "source_hash": sha256_bytes(fragments[type_name].encode("utf-8"))},
        })
    for type_name in sorted(ontology_only):
        entries.append({
            "id": type_name, "status": "ontology_only", "category": None, "reported_fields": [],
            "triggers_naming_type": [str(e["id"]) for e in refs.get(type_name, [])],
            "protocols": [], "match_rule_ids": [], "checkin_minutes": None,
            "reason": ontology_only[type_name]["reason"],
            "field_sources": {"id": "docs/ontology.yaml", "triggers_naming_type": "docs/ontology.yaml",
                              "reason": "seed ontology_only"},
            "stamp": {"ontology_content_hash": canonical_hash(refs.get(type_name, [])),
                      "seed_hash": canonical_hash(ontology_only[type_name]), "source_hash": None},
        })
    entries.sort(key=lambda e: e["id"])
    catalog = {
        "schema_version": seed["schema_version"],
        "generated_from": {
            "ontology_sha256": sha256_bytes(ontology_bytes),
            "ontology_version_label": str(ontology.get("version")),
            "device_type_source_sha256": sha256_bytes(java_text.encode("utf-8")),
            "seed_sha256": sha256_bytes(seed_bytes),
            "generator_version": GENERATOR_VERSION},
        "safety_types": sorted(seed.get("safety_types", [])),
        "allowed_changes": seed.get("allowed_changes", []),
        "match_rules": rules,
        "entries": entries,
    }
    catalog["catalog_hash"] = canonical_hash(catalog)
    return catalog


def render(catalog):
    return json.dumps(catalog, sort_keys=True, indent=1, ensure_ascii=False) + "\n"


def generate(root=ROOT):
    java_text = DEVICE_TYPE_JAVA.read_bytes().decode("utf-8").replace("\r\n", "\n")
    return build(java_text, ONTOLOGY.read_bytes(), SEED.read_bytes())


def safety_violations(old, new):
    """Changes to a safety type that the seed's allowed_changes do not list (design #475 section 3, rule d)."""
    allowed = {(a["type"], a["change"]) for a in new.get("allowed_changes", []) if a.get("reason")}
    old_by_id = {e["id"]: e for e in old["entries"]}
    new_by_id = {e["id"]: e for e in new["entries"]}
    problems = []
    for type_name in sorted(set(old.get("safety_types", [])) | set(new.get("safety_types", []))):
        before, after = old_by_id.get(type_name), new_by_id.get(type_name)
        if before is None and after is None:
            continue
        if after is None:
            if (type_name, "removed") not in allowed:
                problems.append(f"{type_name}: entry removed or renamed")
            continue
        if before is None:
            continue
        if before["category"] != after["category"] and (type_name, "category") not in allowed:
            problems.append(f"{type_name}: category {before['category']} -> {after['category']}")
        if before["checkin_minutes"] != after["checkin_minutes"] and (type_name, "checkin") not in allowed:
            problems.append(f"{type_name}: check-in window changed")
    return problems


def validate_schema(catalog):
    try:
        import jsonschema
    except ImportError as exc:
        raise CatalogError("jsonschema is not installed; pip install jsonschema to validate the catalog") from exc
    schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
    errors = sorted(jsonschema.Draft202012Validator(schema).iter_errors(catalog), key=lambda e: list(e.path))
    if errors:
        raise CatalogError("catalog fails its schema: " + "; ".join(
            f"{'/'.join(str(p) for p in e.path) or '<root>'}: {e.message}" for e in errors[:5]))


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--check", action="store_true", help="fail if the committed catalog differs from a regeneration")
    parser.add_argument("--baseline", type=Path, help="an older catalog; apply the safety guard against it")
    parser.add_argument("--no-schema", action="store_true", help="skip schema validation (jsonschema not installed)")
    args = parser.parse_args(argv)
    try:
        catalog = generate()
        if not args.no_schema:
            validate_schema(catalog)
        text = render(catalog)
        if args.baseline:
            problems = safety_violations(json.loads(args.baseline.read_text(encoding="utf-8")), catalog)
            if problems:
                raise CatalogError("safety guard: " + "; ".join(problems)
                                   + ". List each in allowed_changes in docs/ontology/catalog-seed.yaml with a reason.")
        if args.check:
            current = CATALOG.read_bytes().decode("utf-8") if CATALOG.exists() else ""
            if current != text:
                raise CatalogError("docs/ontology/device-catalog.json is not what the generator produces from the current "
                                   f"inputs (a hand edit, or an input changed). Run: {FIX_COMMAND}")
            print(f"catalog OK: {len(catalog['entries'])} entries, hash {catalog['catalog_hash'][:12]}")
            return 0
        CATALOG.write_bytes(text.encode("utf-8"))
        print(f"wrote {CATALOG.relative_to(ROOT)}: {len(catalog['entries'])} entries, hash {catalog['catalog_hash'][:12]}")
        return 0
    except CatalogError as exc:
        print(f"CATALOG ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
