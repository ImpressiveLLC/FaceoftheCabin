#!/usr/bin/env python3
"""W-169 step C1 prototype: a read-only capability coverage report for Zigbee2MQTT devices.

Design: docs/governance/proposals/2026-11-22-device-capability-coverage-design.md. For each device it compares three
layers and puts every capability the device OFFERS into exactly one status:

  shown                    the hub registers it and the device's catalog type reports it (charts, tiles)
  registered_not_shown     the hub reads it, but the device's catalog type does not report it
  offered_not_registered   the device offers it and the ontology has a row for it, but the hub does not read it
  offered_not_mappable     the device offers it and there is no ontology row for it yet (a W-168 candidate)
  setting_or_diagnostic    a configuration setting or diagnostic value; listed, not counted as a gap
  control                  something the hub could command (access bit 2); listed with whether the device has the
                           COMMAND capability, not counted as a reading gap

Inputs, all files, nothing live:
  --z2m-devices  a captured, REDACTED Zigbee2MQTT `bridge/devices` message (the list with definition.exposes)
  --registry     the hub's view: per device key, the assigned DeviceType and the fields the hub registered
  catalog        docs/ontology/device-catalog.json (reported fields per type)
  vocabulary     docs/ontology/schema/device-reporting-relationship.schema.json (the closed measurement-type enum,
                 which the backend's D7MeasurementTypes mirrors)

The report records the catalog hash and the sha256 of each input, so two runs can be compared (`--previous`): new
or removed devices and every capability whose status changed show as a diff.

Measure only. It reads files, writes one report, and changes no device, entity, topic or setting.
"""
import argparse
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "docs" / "ontology" / "device-catalog.json"
VOCABULARY = ROOT / "docs" / "ontology" / "schema" / "device-reporting-relationship.schema.json"
REPORT_VERSION = 1
STATUSES = ("shown", "registered_not_shown", "offered_not_registered", "offered_not_mappable",
            "setting_or_diagnostic", "control")
GAP_STATUSES = ("registered_not_shown", "offered_not_registered", "offered_not_mappable")
ACCESS_SET = 2
# Mirrors Zigbee2MqttAdapter.PRESENCE_FIELD_BY_DEVICE_TYPE: a binary expose counts as a reading only for this type.
PRESENCE_FIELD_BY_TYPE = {"MOTION_SENSOR": "occupancy", "CONTACT_SENSOR": "contact", "WATER_LEAK_SENSOR": "water_leak"}


class CoverageError(Exception):
    pass


def sha256_file(path):
    return hashlib.sha256(Path(path).read_bytes().replace(b"\r\n", b"\n")).hexdigest()


def load_json(path, what):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError) as exc:
        raise CoverageError(f"{what} {path} cannot be read as JSON: {exc}")


def measurement_vocabulary(schema):
    """The closed measurement-type enum (the first enum in the schema that names `temperature`)."""
    found = []

    def walk(node):
        if isinstance(node, dict):
            enum = node.get("enum")
            if isinstance(enum, list) and "temperature" in enum:
                found.append(enum)
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)

    walk(schema)
    if not found:
        raise CoverageError("no measurement-type enum found in the reporting-relationship schema")
    return set(found[0])


def camel(name):
    head, *rest = name.split("_")
    return head + "".join(part[:1].upper() + part[1:] for part in rest)


def flatten_exposes(exposes):
    """Leaf exposes with their property, type, category and access, composites and features expanded."""
    out = []

    def walk(items, parent_type=None):
        for item in items or []:
            if not isinstance(item, dict):
                continue
            features = item.get("features")
            if isinstance(features, list) and features:
                walk(features, item.get("type"))
                continue
            name = item.get("property") or item.get("name")
            if not name:
                continue
            out.append({"name": str(name), "type": str(item.get("type", "")), "category": item.get("category"),
                        "access": int(item.get("access", 1) or 0), "parent": parent_type})

    walk(exposes)
    return out


CONTROL_PARENTS = ("switch", "light", "lock", "climate", "fan", "cover")


def classify(leaf, assigned_type, registered, type_reports, vocabulary):
    """One status for one offered capability."""
    name, kind = leaf["name"], leaf["type"]
    if leaf["category"] in ("config", "diagnostic"):
        return "setting_or_diagnostic"
    writable = bool(leaf["access"] & ACCESS_SET)
    if writable and (kind != "numeric" or leaf["parent"] in CONTROL_PARENTS):
        return "control"
    is_reading_kind = kind == "numeric" or (kind == "binary" and PRESENCE_FIELD_BY_TYPE.get(assigned_type) == name)
    mappable = name in vocabulary
    if name in registered:
        return "shown" if camel(name) in type_reports or name in type_reports else "registered_not_shown"
    if not mappable:
        return "offered_not_mappable"
    if is_reading_kind or kind == "binary":
        return "offered_not_registered"
    return "offered_not_mappable"


def build_report(z2m_devices, registry, catalog, vocabulary, inputs, now=None):
    if not isinstance(z2m_devices, list):
        raise CoverageError("the Zigbee2MQTT device list must be a JSON list (the bridge/devices message)")
    reports_by_type = {e["id"]: set(e.get("reported_fields", [])) for e in catalog.get("entries", [])}
    hub = registry.get("devices", {}) if isinstance(registry, dict) else {}
    devices = []
    for device in sorted(z2m_devices, key=lambda d: str(d.get("ieee_address") or d.get("friendly_name") or "")):
        if device.get("type") == "Coordinator":
            continue
        key = str(device.get("ieee_address") or device.get("friendly_name") or "")
        if not key:
            raise CoverageError("a device has neither ieee_address nor friendly_name")
        definition = device.get("definition") or {}
        entry = hub.get(key, {})
        assigned = entry.get("assigned_type")
        registered = set(entry.get("registered_fields", []))
        has_command = bool(entry.get("has_command", False))
        type_reports = reports_by_type.get(assigned, set()) if assigned else set()
        capabilities = []
        seen = set()
        for leaf in flatten_exposes(definition.get("exposes")):
            if leaf["name"] in seen:
                continue
            seen.add(leaf["name"])
            status = classify(leaf, assigned, registered, type_reports, vocabulary)
            item = {"name": leaf["name"], "expose_type": leaf["type"], "status": status}
            if status == "control":
                item["hub_can_command"] = has_command
            capabilities.append(item)
        counts = {s: sum(1 for c in capabilities if c["status"] == s) for s in STATUSES}
        devices.append({
            "key": key,
            "model": definition.get("model"),
            "vendor": definition.get("vendor"),
            "assigned_type": assigned,
            "in_hub": key in hub,
            "catalog_type_known": assigned in reports_by_type if assigned else False,
            "capabilities": capabilities,
            "counts": counts,
            "gaps": sum(counts[s] for s in GAP_STATUSES),
        })
    devices.sort(key=lambda d: (-d["gaps"], d["key"]))
    return {
        "report": "capability-coverage",
        "report_version": REPORT_VERSION,
        "site": "zigbee2mqtt",
        "generated_at": now or datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "catalog_hash": catalog.get("catalog_hash"),
        "inputs": inputs,
        "summary": {
            "devices": len(devices),
            "not_in_hub": sum(1 for d in devices if not d["in_hub"]),
            "by_status": {s: sum(d["counts"][s] for d in devices) for s in STATUSES},
            "devices_with_gaps": sum(1 for d in devices if d["gaps"]),
        },
        "devices": devices,
    }


def diff_reports(previous, current):
    """New and removed devices, and each capability whose status changed, between two runs."""
    before = {d["key"]: d for d in previous.get("devices", [])}
    after = {d["key"]: d for d in current.get("devices", [])}
    changes = []
    for key in sorted(set(before) & set(after)):
        old = {c["name"]: c["status"] for c in before[key]["capabilities"]}
        new = {c["name"]: c["status"] for c in after[key]["capabilities"]}
        for name in sorted(set(old) | set(new)):
            if old.get(name) != new.get(name):
                changes.append({"key": key, "capability": name, "before": old.get(name), "after": new.get(name)})
    return {"catalog_hash_before": previous.get("catalog_hash"), "catalog_hash_after": current.get("catalog_hash"),
            "devices_added": sorted(set(after) - set(before)), "devices_removed": sorted(set(before) - set(after)),
            "capability_changes": changes}


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    p.add_argument("--z2m-devices", type=Path, required=True)
    p.add_argument("--registry", type=Path, required=True)
    p.add_argument("--catalog", type=Path, default=CATALOG)
    p.add_argument("--vocabulary", type=Path, default=VOCABULARY)
    p.add_argument("--previous", type=Path, help="an earlier report; adds a diff section")
    p.add_argument("--out", type=Path, required=True, help="report path; refused if it exists")
    args = p.parse_args(argv)
    if args.out.exists():
        raise SystemExit(f"{args.out} exists; reports are never overwritten")
    try:
        inputs = {name: sha256_file(path) for name, path in (("z2m_devices", args.z2m_devices),
                                                             ("registry", args.registry), ("catalog", args.catalog),
                                                             ("vocabulary", args.vocabulary))}
        report = build_report(load_json(args.z2m_devices, "device list"), load_json(args.registry, "registry"),
                              load_json(args.catalog, "catalog"),
                              measurement_vocabulary(load_json(args.vocabulary, "vocabulary schema")), inputs)
        if args.previous:
            report["diff"] = diff_reports(load_json(args.previous, "previous report"), report)
    except CoverageError as exc:
        raise SystemExit(f"coverage report not written: {exc}")
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with open(args.out, "x", encoding="utf-8", newline="\n") as handle:
        handle.write(json.dumps(report, sort_keys=True, indent=1, ensure_ascii=False) + "\n")
    s = report["summary"]
    print(f"{s['devices']} devices, {s['devices_with_gaps']} with gaps, by status {s['by_status']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
