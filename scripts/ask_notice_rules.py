#!/usr/bin/env python3
"""Offline reference implementation of the Ask source notice rules.

Specification: docs/ai-assistant/proposals/ask-no-source-guard.md (merged in #419).
Standard library only. Offline: reads recorded answers and fixture files, calls nothing,
changes nothing. It is not part of the runtime request path.

Everything below is byte-wise ASCII on purpose (keyword, whitespace, case folding), so the
later Java implementation can match it exactly. Non-ASCII look-alikes are never `source`,
never whitespace and never folded.
"""
import argparse
import hashlib
import json
import sys

WS = " \t\r\n"
STRIP = set("`'\"()<>.,;:") | {"…"}
BODY_CAP = 300  # characters of body text after the colon; 300 accepted, 301 rejected

N1_TEXT = "No documentation section was part of the material supplied for this answer."
N2_TEXT = "No documentation label in the expected form was found in this answer."
N3_TEXT = ("This answer names a source label that does not match any source "
           "supplied for this question.")
NOTICE_TEXT = {"N1": N1_TEXT, "N2": N2_TEXT, "N3": N3_TEXT}
CONTEXT_KINDS = ("EMPTY", "FALLBACK_ONLY", "NODES_ONLY", "WITH_DOCS")


def fold(text):
    """ASCII-only case folding: A-Z onto a-z, nothing else."""
    return "".join(chr(ord(c) + 32) if "A" <= c <= "Z" else c for c in text)


def extract_bodies(answer):
    """Single left-to-right scan; linear time; returns the body text of each citation bracket."""
    bodies = []
    n = len(answer)
    i = 0
    while i < n:
        if answer[i] != "[":
            i += 1
            continue
        j = i + 1
        while j < n and answer[j] in WS:
            j += 1
        if fold(answer[j:j + 6]) != "source":
            i += 1
            continue
        j += 6
        while j < n and answer[j] in WS:
            j += 1
        if j >= n or answer[j] != ":":
            i += 1
            continue
        start = j + 1
        k = start
        # find the first "]" or the next "[" (abandon), within the cap
        while k < n and answer[k] not in "[]":
            k += 1
        if k >= n:
            i += 1  # no closing bracket anywhere ahead: not a citation
            continue
        if answer[k] == "[":
            i = k  # abandoned; resume at the new "["
            continue
        body = answer[start:k]
        if len(body) > BODY_CAP:
            i += 1
            continue
        bodies.append(body)
        i = k + 1
    return bodies


def normalize(label):
    text = "".join(c for c in label if c not in WS)
    while True:
        if fold(text[:4]) == "doc:":
            text = text[4:]
        elif text and text[0] in STRIP:
            text = text[1:]
        elif text and text[-1] in STRIP:
            text = text[:-1]
        else:
            return fold(text)


def extract_labels(answer):
    """Normalized, non-empty labels in order of appearance (duplicates kept)."""
    labels = []
    for body in extract_bodies(answer):
        for part in body.replace(";", ",").split(","):
            norm = normalize(part)
            if norm:
                labels.append(norm)
    return labels


def matches(label, supplied_norm):
    if label in supplied_norm:
        return True
    return any(s.split("#", 1)[0] == label for s in supplied_norm)


def notice_code(answer, supplied_labels, context_kind, model_backed=True):
    """Returns None, "N1", "N2" or "N3" per the spec table."""
    if context_kind not in CONTEXT_KINDS:
        raise ValueError("unknown contextKind: %r" % (context_kind,))
    if not model_backed or context_kind == "EMPTY":
        return None
    if context_kind in ("FALLBACK_ONLY", "NODES_ONLY"):
        return "N1"
    supplied_norm = {normalize(s) for s in supplied_labels}
    supplied_norm.discard("")
    labels = extract_labels(answer)
    if any(not matches(label, supplied_norm) for label in labels):
        return "N3"
    if not labels:
        return "N2"
    return None


def file_sha256(path):
    with open(path, "rb") as handle:
        return hashlib.sha256(handle.read()).hexdigest()


def run_vectors(path):
    """Returns a list of (id, expected, got) mismatches over the shared vector file."""
    with open(path, "rb") as handle:
        data = json.loads(handle.read().decode("utf-8"))
    bad = []
    for vec in data["vectors"]:
        answer = vec["answer"]
        if "answerRepeat" in vec:
            rep = vec["answerRepeat"]
            answer = rep["prefix"] + rep["unit"] * rep["count"] + rep.get("suffix", "")
        got = {
            "labels": extract_labels(answer),
            "notice": notice_code(answer, vec["supplied"], vec["contextKind"],
                                  vec.get("modelBacked", True)),
        }
        want = {"labels": vec["expectedLabels"], "notice": vec["expectedNotice"]}
        if got != want:
            bad.append((vec["id"], want, got))
    return bad


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="cmd", required=True)
    vec = sub.add_parser("vectors", help="run the shared vector file; exit 1 on any mismatch")
    vec.add_argument("path")
    sha = sub.add_parser("sha256", help="print the sha256 of a file's raw bytes")
    sha.add_argument("path")
    args = parser.parse_args(argv)
    if args.cmd == "sha256":
        print(file_sha256(args.path))
        return 0
    bad = run_vectors(args.path)
    for vid, want, got in bad:
        print("MISMATCH %s want=%r got=%r" % (vid, want, got))
    print("vectors: %s" % ("FAIL" if bad else "ok"))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
