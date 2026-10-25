"""Offline proposal contract; never resolves entities or executes actions."""
import json
import re
from pathlib import Path
from datetime import datetime
from dateutil.rrule import rrulestr
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parent
SCHEMA = json.loads((ROOT / "intent-slot.schema.json").read_text(encoding="utf-8"))
VALIDATOR = Draft202012Validator(SCHEMA, format_checker=FormatChecker())
REQUEST_SCHEMA = json.loads((ROOT / "request.schema.json").read_text(encoding="utf-8"))
REQUEST_VALIDATOR = Draft202012Validator(REQUEST_SCHEMA)
SYSTEM = (ROOT / "system.txt").read_text(encoding="utf-8")


def validate(value, card_target=None):
    VALIDATOR.validate(value)
    intent = value["intent"]
    if intent in {"set_device_state", "set_device_attribute", "propose_automation", "express_need"}:
        if not value["requires_confirmation"]:
            raise ValueError("Mutations require confirmation")
    if intent == "clarify" and not value["clarification"]:
        raise ValueError("Clarification needs a question")
    if intent == "propose_automation":
        if value["automation"] is None:
            raise ValueError("Automation proposal needs a structured plan")
    elif value["automation"] is not None:
        raise ValueError("Only automation proposals carry a plan")
    if intent == "express_need" and "concept" not in value["slots"]:
        raise ValueError("A need requires a concept")
    if intent != "express_need" and "concept" in value["slots"]:
        raise ValueError("Concept belongs to express_need")
    if intent == "set_device_attribute" and value["slots"].get("attribute_type") == "temperature":
        if value["slots"].get("unit") not in {"C", "F"}:
            raise ValueError("Temperature requires explicit C or F; otherwise clarify")
    if value["automation"]:
        trigger = value["automation"]["trigger"]
        if trigger.get("kind") in {"time_once", "time_recurring"}:
            field = "at" if trigger["kind"] == "time_once" else "start_time"
            stamp = trigger[field]
            if "T" not in stamp.upper():
                raise ValueError("Schedule requires an ISO datetime with offset")
            parsed = datetime.fromisoformat(stamp.replace("Z", "+00:00"))
            if parsed.tzinfo is None or parsed.utcoffset() is None:
                raise ValueError("Schedule requires an explicit UTC offset")
        if trigger.get("kind") == "time_recurring":
            rule = trigger["rrule"]
            if "\n" in rule or "\r" in rule or not rule.startswith("FREQ=") or len(rule) > 500:
                raise ValueError("Expected one RFC 5545 RRULE value beginning FREQ=")
            parts = rule.split(";")
            keys = [part.split("=", 1)[0] for part in parts]
            if len(keys) != len(set(keys)) or any("=" not in part for part in parts):
                raise ValueError("RRULE parts must be unique key/value pairs")
            fields = dict(part.split("=", 1) for part in parts)
            if "COUNT" in fields and "UNTIL" in fields:
                raise ValueError("RRULE cannot combine COUNT and UNTIL")
            if "UNTIL" in fields and not re.fullmatch(r"\d{8}T\d{6}Z", fields["UNTIL"]):
                raise ValueError("RRULE UNTIL must be a UTC date-time YYYYMMDDTHHMMSSZ (DTSTART is a date-time), not an ISO datetime with an offset or a bare date")
            for key in ("COUNT", "INTERVAL"):
                if key in fields and (not fields[key].isdigit() or int(fields[key]) < 1):
                    raise ValueError("RRULE COUNT and INTERVAL must be positive integers")
            start = datetime.fromisoformat(trigger["start_time"].replace("Z", "+00:00"))
            rrulestr(rule, dtstart=start)  # Parse only; never expand a potentially unbounded rule.
    if card_target is not None:
        REQUEST_VALIDATOR.validate({"text": "validation", "card_target": card_target})
        for key, expected in card_target.items():
            if key in value["slots"] and value["slots"][key] != expected:
                raise ValueError("Model cannot override the selected card target")
        if value["automation"]:
            for action in value["automation"]["actions"]:
                if action["target"] != card_target:
                    raise ValueError("Automation action cannot override the selected card target")
    return value


def proposal(intent, slots=None, clarification=None, automation=None):
    return validate(dict(intent=intent, slots=slots or {}, clarification=clarification,
                         automation=automation, requires_confirmation=intent in {
                             "set_device_state", "set_device_attribute", "propose_automation", "express_need"}))


def request_content(text, card_target=None):
    request = {"text": text}
    if card_target is not None:
        request["card_target"] = card_target
    REQUEST_VALIDATOR.validate(request)
    return json.dumps(request, ensure_ascii=False)


def read_jsonl(path):
    return [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]


def write_jsonl(path, rows):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")
