"""Offline proposal contract; never resolves entities or executes actions."""
import json
from pathlib import Path
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parent
SCHEMA = json.loads((ROOT / "intent-slot.schema.json").read_text(encoding="utf-8"))
VALIDATOR = Draft202012Validator(SCHEMA)
SYSTEM = (ROOT / "system.txt").read_text(encoding="utf-8")


def validate(value):
    VALIDATOR.validate(value)
    intent = value["intent"]
    if intent in {"set_device_state", "set_device_attribute", "propose_automation"}:
        if not value["requires_confirmation"]:
            raise ValueError("Mutations require confirmation")
    if intent == "clarify" and not value["clarification"]:
        raise ValueError("Clarification needs a question")
    if intent == "propose_automation":
        if value["automation"] is None:
            raise ValueError("Automation proposal needs a structured plan")
    elif value["automation"] is not None:
        raise ValueError("Only automation proposals carry a plan")
    return value


def proposal(intent, slots=None, clarification=None, automation=None):
    return validate(dict(intent=intent, slots=slots or {}, clarification=clarification,
                         automation=automation, requires_confirmation=intent in {
                             "set_device_state", "set_device_attribute", "propose_automation"}))


def read_jsonl(path):
    return [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]


def write_jsonl(path, rows):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")
