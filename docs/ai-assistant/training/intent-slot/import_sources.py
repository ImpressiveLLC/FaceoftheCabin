"""Explicit adapters; unknown mappings fail rather than silently relabel data."""
import argparse
import hashlib
import json
from pathlib import Path
import yaml
from contract import proposal, read_jsonl, write_jsonl


def pairs(items):
    result = {}
    for key, value in items:
        if key in result:
            raise ValueError(f"Duplicate slot: {key}")
        result[key] = str(value)
    return result


def adapt(kind, data):
    if kind == "enfuse":
        for row in data:
            if row["intent"] not in {"set_device_state", "set_device_attribute", "get_device_state"}:
                raise ValueError(f"Unknown Enfuse intent: {row['intent']}")
            yield row["text"], proposal(row["intent"], pairs(
                (s["slot_type"], s["slot_value"]) for s in row["slots"]))
    elif kind == "ha":
        # Home Assistant concrete test YAML; sentence templates are NOT literal utterances.
        mapping = {"HassTurnOn": "on", "HassTurnOff": "off"}
        keys = {"name": "device_name", "area": "location", "domain": "device_name"}
        for row in data["tests"]:
            if "intent" not in row:
                raise ValueError("Unmatched HA tests need manual unsupported/clarify labels")
            intent = row["intent"]
            if intent["name"] not in mapping:
                raise ValueError(f"Unsupported HA intent: {intent['name']}")
            raw = intent.get("slots", {})
            if set(raw) - set(keys):
                raise ValueError("Unmapped HA slot; review mapping before import")
            slots = {"state": mapping[intent["name"]]}
            for key, value in raw.items():
                if key == "domain" and "name" in raw:
                    continue
                slots[keys[key]] = str(value)
            for sentence in row["sentences"]:
                yield sentence, proposal("set_device_state", slots.copy())
    elif kind == "snips":
        for name, intent in data["intents"].items():
            if name not in {"turnLightOn", "turnLightOff", "setLightColor"}:
                raise ValueError(f"Unknown Snips sample intent: {name}")
            for utterance in intent["utterances"]:
                chunks = utterance["data"]
                slots = pairs((c["slot_name"], c["text"]) for c in chunks if "slot_name" in c)
                if set(slots) - {"room", "color"}:
                    raise ValueError("Unmapped Snips slot")
                target = {"device_name": "lights"}
                if "room" in slots:
                    target["location"] = slots["room"]
                if name == "setLightColor":
                    target.update(attribute_type="color", attribute_value=slots["color"])
                    label = "set_device_attribute"
                else:
                    target["state"] = "on" if name == "turnLightOn" else "off"
                    label = "set_device_state"
                yield "".join(c["text"] for c in chunks), proposal(label, target)
    else:
        raise ValueError(f"Unknown source: {kind}")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("source", choices=["ha", "enfuse", "snips"])
    p.add_argument("input", type=Path)
    p.add_argument("output", type=Path)
    p.add_argument("--revision", required=True, help="Immutable upstream commit/dataset revision")
    p.add_argument("--license", required=True, help="Reviewed license identifier")
    args = p.parse_args()
    raw = args.input.read_bytes()
    data = (read_jsonl(args.input) if args.source == "enfuse" else
            yaml.safe_load(raw) if args.source == "ha" else json.loads(raw))
    digest = hashlib.sha256(raw).hexdigest()
    rows = []
    for i, (text, target) in enumerate(adapt(args.source, data)):
        rows.append({"id": f"{args.source}-{digest[:12]}-{i}", "text": text,
                     "target": target, "group": f"{args.source}-{digest}",
                     "provenance": {"source": args.source, "revision": args.revision,
                                    "sha256": digest, "license": args.license,
                                    "record": i, "review_status": "unreviewed"}})
    if not rows:
        raise ValueError("Empty import")
    write_jsonl(args.output, rows)


if __name__ == "__main__":
    main()
