"""Shared vocabulary for the intent/slot package: human-need concepts, rooms/areas and sites.

Standard library only, so the grading screen and prepare_sft.py can use it without the schema tooling.
`concepts.json` and `areas.json` are the single sources; the schema enum, system prompts, ontology entity
and Java copy are checked against them by tests, never edited to disagree.
"""
import difflib
import json
import re
from copy import deepcopy
from pathlib import Path

ROOT = Path(__file__).resolve().parent
MAX_AREA_LENGTH = 40
_AREA_SHAPE = re.compile(r"^[a-z][a-z0-9' -]*$")
_ARTICLES = ("the ", "a ", "an ", "my ", "our ")


def _load(name):
    return json.loads((ROOT / name).read_text(encoding="utf-8"))


CONCEPTS = _load("concepts.json")["concepts"]
CONCEPT_IDS = [c["id"] for c in CONCEPTS]
_AREAS = _load("areas.json")
SITES = list(_AREAS["sites"])
AREA_IDS = [a["id"] for a in _AREAS["areas"]]
AREA_ALIASES = {alias: a["id"] for a in _AREAS["areas"] for alias in a["aliases"]}


def normalize(text):
    """Lower-case, trim, turn underscores/hyphens/runs of space into single spaces, drop a leading article."""
    value = re.sub(r"[\s_]+", " ", str(text).strip().casefold()).strip(" .,;:")
    value = re.sub(r"\s*-\s*", " ", value)
    for article in _ARTICLES:
        if value.startswith(article):
            value = value[len(article):]
            break
    return value


def check_location(value):
    """(canonical area id, None) or (None, message) for a room/area string."""
    if not isinstance(value, str):
        return None, "Room / area must be text"
    text = normalize(value)
    if not text:
        return None, "Room / area is empty. Remove the field, or choose a room."
    if len(text) > MAX_AREA_LENGTH:
        return None, f"Room / area is longer than {MAX_AREA_LENGTH} characters"
    if not _AREA_SHAPE.match(text):
        return None, "Room / area may only use letters, digits, spaces, hyphens and apostrophes, and must start with a letter"
    if text in SITES:
        return None, f"'{text}' is a site, not a room. Use the Site field."
    if text in AREA_IDS:
        return text, None
    if text in AREA_ALIASES:
        return AREA_ALIASES[text], None
    near = difflib.get_close_matches(text, AREA_IDS + list(AREA_ALIASES), n=1, cutoff=0.75)
    hint = f" Did you mean '{AREA_ALIASES.get(near[0], near[0])}'?" if near else ""
    return None, f"Unknown room / area '{text}'.{hint} Choose from the list, or add the room to areas.json."


def check_site(value):
    if not isinstance(value, str):
        return None, "Site must be text"
    text = normalize(value)
    if text in SITES:
        return text, None
    return None, f"Unknown site '{text}'. Use one of: {', '.join(SITES)}."


def _walk_targets(target):
    """Yield (path, dict) for every object that can carry location/site: slots and automation targets."""
    yield "slots", target.get("slots", {})
    automation = target.get("automation")
    if automation:
        trigger = automation.get("trigger", {})
        if isinstance(trigger.get("target"), dict):
            yield "automation.trigger.target", trigger["target"]
        for i, condition in enumerate(automation.get("conditions", [])):
            if isinstance(condition.get("target"), dict):
                yield f"automation.conditions[{i}].target", condition["target"]
        for i, action in enumerate(automation.get("actions", [])):
            if isinstance(action.get("target"), dict):
                yield f"automation.actions[{i}].target", action["target"]


def normalize_target(target):
    """A copy of the expected answer with rooms and sites rewritten to their canonical form, plus the changes made.

    Invalid values are left exactly as given so the caller can report them; this never guesses.
    """
    result = deepcopy(target)
    changes = []
    for path, holder in _walk_targets(result):
        for key, check in (("location", check_location), ("site", check_site)):
            if key in holder:
                canonical, error = check(holder[key])
                if error is None and canonical != holder[key]:
                    changes.append({"path": f"{path}.{key}", "from": holder[key], "to": canonical})
                    holder[key] = canonical
    return result, changes


def field_errors(target):
    """{path: message} for every room, site and concept that is not allowed. Empty means the fields are clean."""
    errors = {}
    for path, holder in _walk_targets(target):
        if "location" in holder:
            _, error = check_location(holder["location"])
            if error:
                errors[f"{path}.location"] = error
        if "site" in holder:
            _, error = check_site(holder["site"])
            if error:
                errors[f"{path}.site"] = error
    slots = target.get("slots", {})
    if "concept" in slots:
        if slots["concept"] not in CONCEPT_IDS:
            errors["slots.concept"] = f"Unknown need '{slots['concept']}'. Choose one from the list."
        elif target.get("intent") != "express_need":
            errors["slots.concept"] = "A human need belongs only to a need request. Change the intent, or remove the need."
    return errors


def supported_needs(target):
    """Concept ids whose proposed asks this expected answer would satisfy (read-only context for a reviewer).

    Matches a direct device-state command against each concept's `asks`: same intent, same state, and a
    device name containing one of the ask's words. It never changes the answer and says nothing about authority.
    """
    if target.get("intent") != "set_device_state":
        return []
    slots = target.get("slots", {})
    name = normalize(slots.get("device_name", ""))
    state = normalize(slots.get("state", ""))
    found = []
    for concept in CONCEPTS:
        for ask in concept.get("asks", []):
            if ask["intent"] == target["intent"] and ask["state"] == state and any(w in name for w in ask["device_words"]):
                found.append(concept["id"])
                break
    return found


def public_vocabulary():
    """What the grading screen needs: concepts (with aliases and relations), areas with aliases, sites."""
    return {"concepts": deepcopy(CONCEPTS), "areas": [{"id": a["id"], "aliases": a["aliases"]} for a in _AREAS["areas"]],
            "sites": SITES}
