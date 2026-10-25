"""Expand reviewed templates into synthetic candidates, never a training approval."""
import argparse
import hashlib
import json
from pathlib import Path
from contract import ROOT, validate, write_jsonl, request_content


def generate(path):
    raw = path.read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    rows = []
    for template in json.loads(raw):
        validate(template["target"], template.get("card_target"))
        for i, text in enumerate(template["utterances"]):
            request_content(text, template.get("card_target"))
            rows.append({"id": f"synthetic-{template['id']}-{i}", "group": template["id"],
                         "text": text, "target": template["target"],
                         "provenance": {"source": "synthetic", "revision": digest,
                                        "sha256": digest, "license": "project-authored",
                                        "record": i, "review_status": "unreviewed"}})
            if "card_target" in template:
                rows[-1]["card_target"] = template["card_target"]
    return rows


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("output", type=Path)
    p.add_argument("--templates", type=Path, default=ROOT / "synthesis-templates.json")
    args = p.parse_args()
    write_jsonl(args.output, generate(args.templates))
