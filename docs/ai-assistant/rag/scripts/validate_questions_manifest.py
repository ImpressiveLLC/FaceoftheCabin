#!/usr/bin/env python3
"""Validate docs/ai-assistant/rag/questions_manifest_r1.json against the
real filesystem.

This is a structural check, not a content/factual review: it confirms every
source_docs path a question claims as written/available/partially_available
actually exists on disk, and that source_doc_status is one of the manifest's
own declared values. It does not evaluate whether the target section still
answers the question -- that is a human/PR review, same as any other corpus
content gate.

Written after the 2026-09-20 status-reconciliation entry in
docs/ai-assistant/wsjf-backlog.md found that questions_manifest_r1.json
named source_docs files that never existed (quick-start.md, tokens-security.md,
credential-management.md, user-onboarding.md, integrations.md) while the
manifest's own status field claimed some of them were written -- exactly the
kind of drift this script exists to catch before it recurs.
"""
import argparse
import json
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[4]
MANIFEST_PATH = REPO_ROOT / "docs/ai-assistant/rag/questions_manifest_r1.json"

# A question's source_doc_status is allowed to say a doc is missing --
# only these three statuses assert the file must actually exist and answer
# the question. "not_yet_written" and "live_data_required" are the manifest's
# own designed escape hatches (see its "_doc" field and adding_questions
# instructions) and never require a real file.
STATUSES_REQUIRING_FILES = {"written", "available", "partially_available"}
KNOWN_STATUSES = STATUSES_REQUIRING_FILES | {"not_yet_written", "live_data_required"}


def validate(manifest_path=MANIFEST_PATH, repo_root=REPO_ROOT):
    """Returns a list of problem strings; empty means the manifest is clean."""
    manifest = json.loads(Path(manifest_path).read_text(encoding="utf-8"))
    problems = []
    seen_ids = set()
    for entry in manifest.get("questions", []):
        qid = entry.get("id", "<missing id>")
        if qid in seen_ids:
            problems.append(f"{qid}: duplicate question id")
        seen_ids.add(qid)

        status = entry.get("source_doc_status")
        if status not in KNOWN_STATUSES:
            problems.append(f"{qid}: unknown source_doc_status {status!r}")
            continue

        source_docs = entry.get("source_docs", [])
        if status in STATUSES_REQUIRING_FILES and not source_docs:
            problems.append(f"{qid}: status {status!r} but source_docs is empty")

        if status not in STATUSES_REQUIRING_FILES:
            continue  # not_yet_written / live_data_required: files may not exist yet

        for doc in source_docs:
            path_part = doc.split("#", 1)[0]
            if not path_part:
                problems.append(f"{qid}: empty path in source_docs entry {doc!r}")
                continue
            if path_part.startswith(("http://", "https://")):
                continue  # external reference, not a repo-relative path
            if not (Path(repo_root) / path_part).is_file():
                problems.append(f"{qid}: source_docs path does not exist: {path_part}")
    return problems


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", default=str(MANIFEST_PATH))
    args = parser.parse_args()
    problems = validate(Path(args.manifest))
    print(json.dumps({"problems": problems, "clean": not problems}, indent=2))
    if problems:
        sys.exit(1)


if __name__ == "__main__":
    main()
