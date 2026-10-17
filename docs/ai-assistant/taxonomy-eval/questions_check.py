"""Checks the question set against plan section 5.1 (gate G1). Standard library only.

  python questions_check.py          # prints a coverage report, exits 1 if any rule fails

Rules: 25 to 30 questions; every topic and view has at least 3; at least a third target a hard edge; at least 3 are
unanswerable by design (`no data yet`); a question never names a topic or an element and uses no leading phrase; every
expected_element is real; every edge tag has a drafted edge test.
"""
import json
import math
import re
import sys
from collections import Counter
from pathlib import Path

from verdicts import NO_DATA, load_element_ids, load_topics

HERE = Path(__file__).resolve().parent
SOURCES = ("ask_log", "alert_history", "family", "synthetic")
RULINGS = ("R3", "R4")
FIELDS = {"id", "problem", "start_context", "expected_topic", "expected_element", "edge_tags", "source", "note", "ruling_pending"}
REQUIRED = FIELDS - {"ruling_pending"}
# Words that name a topic or a view. A question that contains one is leading the grader to the answer.
TOPIC_WORDS = ("temp & humidity", "air quality", "security & presence", "connectivity", "device health", "alert history",
               "occupancy", "energy")
LEADING = (r"\bobviously\b", r"\bclearly\b", r"\bisn'?t it\b", r"\bright\?", r"\bshouldn'?t\b", r"\bdon'?t you think\b",
           r"\bi think\b", r"\bsurely\b", r"\bof course\b", r"\bin the .{0,30} (section|tab|topic|category)\b")


def load_questions(path=HERE / "questions.json"):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def edge_tags_drafted(path=HERE / "edge-tests.draft.md"):
    """Edge tags with a drafted test: the first backticked cell of each table row."""
    return set(re.findall(r"^\|\s*`([a-z][a-z0-9-]*)`\s*\|", Path(path).read_text(encoding="utf-8"), re.M))


def is_hard_edge(question):
    return any(not tag.startswith("candidate-") for tag in question["edge_tags"])


def check(data, topics, element_ids, drafted_tags):
    """(problems, coverage)."""
    problems, qs = [], data["questions"]
    topic_ids = {t["id"] for t in topics}
    if not 25 <= len(qs) <= 30:
        problems.append(f"need 25 to 30 questions, found {len(qs)}")
    ids = [q.get("id") for q in qs]
    if len(ids) != len(set(ids)):
        problems.append("duplicate question ids")
    names = sorted({w for t in topics for w in (t["label"].lower(), t["id"].replace("_", " "))} | set(TOPIC_WORDS))
    element_words = {e.lower() for e in element_ids}
    for q in qs:
        qid = q.get("id", "?")
        missing = REQUIRED - set(q)
        extra = set(q) - FIELDS
        if missing or extra:
            problems.append(f"{qid}: missing {sorted(missing)} unexpected {sorted(extra)}")
            continue
        if not re.match(r"^Q[0-9]{3}$", qid):
            problems.append(f"{qid}: id must look like Q001")
        if q["expected_topic"] not in topic_ids:
            problems.append(f"{qid}: unknown expected_topic {q['expected_topic']!r}")
        if q["expected_element"] != NO_DATA and q["expected_element"] not in element_ids:
            problems.append(f"{qid}: expected_element {q['expected_element']!r} is neither an ontology element nor {NO_DATA!r}")
        if q["source"] not in SOURCES:
            problems.append(f"{qid}: source must be one of {list(SOURCES)}")
        if not isinstance(q["edge_tags"], list):
            problems.append(f"{qid}: edge_tags must be a list")
        else:
            for tag in q["edge_tags"]:
                if tag not in drafted_tags:
                    problems.append(f"{qid}: edge tag {tag!r} has no drafted edge test")
        for r in q.get("ruling_pending", []):
            if r not in RULINGS:
                problems.append(f"{qid}: unknown ruling {r!r}")
        for field in ("problem", "start_context"):
            text = q[field]
            if not isinstance(text, str) or not text.strip():
                problems.append(f"{qid}: {field} is empty")
                continue
            low = text.lower()
            for name in names:
                if re.search(r"(?<![a-z])" + re.escape(name) + r"(?![a-z])", low):
                    problems.append(f"{qid}: {field} names a topic ({name!r}); a question describes a problem, not a place")
            for token in re.findall(r"[a-z0-9_]+", low):
                if token in element_words and "_" in token:
                    problems.append(f"{qid}: {field} contains the element id {token!r}")
            for pattern in LEADING:
                if re.search(pattern, low):
                    problems.append(f"{qid}: {field} uses a leading phrase ({pattern})")
        if len(q["start_context"]) > 240:
            problems.append(f"{qid}: start_context is longer than 240 characters")
        if not q["problem"].rstrip().endswith(("?", ".")):
            problems.append(f"{qid}: problem should read as a person's sentence")
    by_topic = Counter(q["expected_topic"] for q in qs if "expected_topic" in q)
    for t in topics:
        if by_topic[t["id"]] < 3:
            problems.append(f"{t['id']}: needs at least 3 questions, has {by_topic[t['id']]}")
    hard = sum(1 for q in qs if "edge_tags" in q and is_hard_edge(q))
    if hard < math.ceil(len(qs) / 3):
        problems.append(f"at least a third must target a hard edge: {hard} of {len(qs)}")
    unanswerable = sum(1 for q in qs if q.get("expected_element") == NO_DATA)
    if unanswerable < 3:
        problems.append(f"at least 3 questions must be unanswerable by design, found {unanswerable}")
    coverage = {"questions": len(qs), "by_topic": dict(sorted(by_topic.items())), "hard_edge": hard,
                "unanswerable_by_design": unanswerable, "ruling_pending": sum(1 for q in qs if q.get("ruling_pending")),
                "by_source": dict(sorted(Counter(q.get("source") for q in qs).items()))}
    return problems, coverage


def main():
    problems, coverage = check(load_questions(), load_topics(), load_element_ids(), edge_tags_drafted())
    print(json.dumps(coverage, indent=2))
    for p in problems:
        print("FAIL:", p)
    print("G1 question rules:", "FAIL" if problems else "pass")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
