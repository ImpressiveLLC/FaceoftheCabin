"""Verdict records for the taxonomy evaluation (W-89, plan section 5.2). Standard library only.

  build_record      the harness side: takes what a probe or a human returned, adds qid/run/version, computes `found` and `hops`.
  validate_record   schema shape plus every cross-field rule. A failing record is discarded and counted, never repaired.
  cell, route       the assert-and-eliminate reading (plan section 4): which cell a trial lands in and which object it updates.

The probe never reports `found` or `hops`: the harness computes them from the question's expected_topic and the path, so a
probe that tries is an instrument defect. Rulings (R3, R4) apply at analysis time and never edit a run log.
"""
import json
import re
from pathlib import Path

HERE = Path(__file__).resolve().parent

VERDICTS = ("right_here", "wrong_here", "cannot_tell")
WRONG_REASONS = ("missing_or_broken_here", "belongs_in", "duplicate_of", "cannot_tell_apart_from")
CANNOT_REASONS = ("missing_information", "missing_context", "invalid_flow")
NEEDS_ELEMENT_TARGET = ("duplicate_of", "cannot_tell_apart_from")
PROBE_FIELDS = ("path", "verdict", "reason", "target", "runner_up", "note")
HARNESS_FIELDS = ("qid", "run_id", "probe_version", "taxonomy_version", "hops", "found")
NO_DATA = "no data yet"

_PATH_ITEM = re.compile(r"^[a-z_]+(/[a-z0-9_-]+)?$")
_QID = re.compile(r"^Q[0-9]{3}$")


def load_topics(path=HERE / "topics.json"):
    return json.loads(Path(path).read_text(encoding="utf-8"))["topics"]


def load_element_ids(path=HERE / "elements.json"):
    return {e["id"] for e in json.loads(Path(path).read_text(encoding="utf-8"))["elements"]}


def topic_of(path_item):
    return path_item.split("/", 1)[0]


def build_record(probe_output, question, run_id, probe_version, taxonomy_version, topic_ids):
    """The harness's record, or raises ValueError (an instrument defect) if the probe overstepped.

    The probe may return only PROBE_FIELDS; any other key, including found/hops, is refused.
    """
    if not isinstance(probe_output, dict):
        raise ValueError("probe output is not an object")
    extra = sorted(set(probe_output) - set(PROBE_FIELDS))
    if extra:
        raise ValueError(f"probe returned fields it may not report: {extra}")
    missing = [k for k in PROBE_FIELDS if k not in probe_output]
    if missing:
        raise ValueError(f"probe output is missing {missing}")
    path = probe_output["path"]
    if not isinstance(path, list) or not path or not all(isinstance(p, str) for p in path):
        raise ValueError("path must be a non-empty list of strings")
    judged = topic_of(path[-1])
    return {"qid": question["id"], "run_id": run_id, "probe_version": probe_version, "taxonomy_version": taxonomy_version,
            "path": path, "hops": len(path), "found": judged == question["expected_topic"],
            **{k: probe_output[k] for k in PROBE_FIELDS if k != "path"}}


def validate_record(record, topic_ids, element_ids):
    """List of problems; empty means the record is valid."""
    problems = []
    if not isinstance(record, dict):
        return ["record is not an object"]
    keys = set(record)
    required = set(HARNESS_FIELDS) | set(PROBE_FIELDS)
    for k in sorted(required - keys):
        problems.append(f"missing field {k}")
    for k in sorted(keys - required):
        problems.append(f"unexpected field {k}")
    if problems:
        return problems
    if not isinstance(record["qid"], str) or not _QID.match(record["qid"]):
        problems.append("qid must look like Q001")
    for k in ("run_id", "probe_version", "taxonomy_version"):
        if not isinstance(record[k], str) or not record[k]:
            problems.append(f"{k} must be a non-empty string")
    path = record["path"]
    if not isinstance(path, list) or not 1 <= len(path) <= 8 or not all(isinstance(p, str) and _PATH_ITEM.match(p) for p in path):
        return problems + ["path must be 1 to 8 entries of the form topic or topic/subclass"]
    unknown = [p for p in path if topic_of(p) not in topic_ids]
    if unknown:
        problems.append(f"path names unknown topics {unknown}")
    if not isinstance(record["hops"], int) or isinstance(record["hops"], bool) or record["hops"] != len(path):
        problems.append("hops must equal len(path)")
    if not isinstance(record["found"], bool):
        problems.append("found must be a boolean")
    verdict, reason, target, runner_up, note = (record[k] for k in ("verdict", "reason", "target", "runner_up", "note"))
    if verdict not in VERDICTS:
        return problems + [f"verdict must be one of {list(VERDICTS)}, never null"]
    if reason not in (None,) + WRONG_REASONS + CANNOT_REASONS:
        problems.append(f"unknown reason {reason!r}")
    for k, v in (("target", target), ("runner_up", runner_up), ("note", note)):
        if v is not None and not isinstance(v, str):
            problems.append(f"{k} must be a string or null")
    if isinstance(note, str) and len(note) > 240:
        problems.append("note is longer than 240 characters")
    judged = topic_of(path[-1])
    if verdict == "right_here":
        if reason is not None:
            problems.append("right_here takes no reason")
        if target is not None:
            problems.append("right_here takes no target")
    elif verdict == "wrong_here":
        if reason not in WRONG_REASONS:
            problems.append(f"wrong_here requires a reason from {list(WRONG_REASONS)}")
        elif reason == "belongs_in":
            if target not in topic_ids:
                problems.append("belongs_in requires a target that is a topic or view id")
            elif target == judged:
                problems.append("belongs_in must name a different topic than the one judged")
        elif reason in NEEDS_ELEMENT_TARGET:
            if target not in element_ids:
                problems.append(f"{reason} requires a target that is an element id from elements.json")
        elif target is not None:
            problems.append("missing_or_broken_here takes no target")
    else:  # cannot_tell
        if reason not in CANNOT_REASONS:
            problems.append(f"cannot_tell requires a reason from {list(CANNOT_REASONS)}")
        if not (isinstance(note, str) and note.strip()):
            problems.append("cannot_tell requires a one-line note of what was missing")
        if target is not None:
            problems.append("cannot_tell takes no target")
    if verdict != "right_here" and runner_up not in topic_ids:
        problems.append("runner_up is required, and must be a topic or view id, unless the verdict is right_here")
    if runner_up is not None and runner_up not in topic_ids:
        problems.append("runner_up must be a topic or view id")
    return problems


def ingest(lines, topic_ids, element_ids):
    """(valid records, defects). Each defect is {line, problems}: an unparseable or invalid record is discarded and counted."""
    valid, defects = [], []
    for number, line in enumerate(lines, 1):
        if not line.strip():
            continue
        try:
            record = json.loads(line)
        except json.JSONDecodeError as exc:
            defects.append({"line": number, "problems": [f"not JSON: {exc.msg}"]})
            continue
        problems = validate_record(record, topic_ids, element_ids)
        (defects if problems else valid).append({"line": number, "problems": problems} if problems else record)
    return valid, defects


def asserted(record):
    """A grader asserted a verdict, as opposed to walking away with cannot_tell."""
    return record["verdict"] != "cannot_tell"


def cell(record):
    """The four-cell reading of plan section 4."""
    return {(True, True): "found_asserted", (True, False): "found_unasserted",
            (False, True): "missed_asserted", (False, False): "missed_unasserted"}[(record["found"], asserted(record))]


def route(record):
    """Which object a trial updates and what to do (plan section 4). Never demotes a placement on an unasserted miss."""
    verdict, reason = record["verdict"], record["reason"]
    if verdict == "right_here":
        return {"object": "placement", "action": "promote" if record["found"] else "hold: asserted right where it was not expected; inspect the path"}
    if verdict == "cannot_tell":
        return {"object": "instrument", "action": f"fix question wording, starting context or flow ({reason}), then re-run"}
    return {
        "missing_or_broken_here": {"object": "implementation", "action": "open a defect; placement unchanged"},
        "belongs_in": {"object": "taxonomy", "action": f"demote this placement, promote {record['target']}"},
        "duplicate_of": {"object": "taxonomy", "action": f"merge or re-home one of the pair ({record['target']}); placement on probation"},
        "cannot_tell_apart_from": {"object": "taxonomy edge", "action": f"sharpen the edge test against {record['target']}; placement on probation"},
    }[reason]
