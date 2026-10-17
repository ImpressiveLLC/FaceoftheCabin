"""Extract the element list from docs/ontology.yaml with a version hash (gate G0, W-89). Standard library only.

  python extract_elements.py write            # regenerate elements.json from the ontology next to the repo
  python extract_elements.py verify           # exit 1 if elements.json no longer matches the ontology

The ontology is regular enough to read without a YAML library: each element is a "  - id:" item under `elements:` and its
scalar keys sit at four spaces. test_taxonomy_eval.py cross-checks this reader against PyYAML when PyYAML is installed (CI),
so a format change that fools the reader fails there. elements.json is a snapshot pinned to a commit; `verify` reports drift
instead of failing a unit test, because the ontology changes often and a snapshot is replaced on purpose.
"""
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
ONTOLOGY = REPO / "docs" / "ontology.yaml"
OUTPUT = HERE / "elements.json"
KEYS = ("entity_type", "layer", "primitive", "data_class", "lifecycle_status", "ui_display_name")
FOLDED = {">", "|", ">-", "|-"}


def parse(text):
    """Elements in file order: {id, entity_type, layer, primitive, data_class, lifecycle_status, ui_display_name, definition}."""
    text = "\n" + text.replace("\r\n", "\n")
    if "\nelements:\n" not in text:
        raise ValueError("no top-level `elements:` list in the ontology")
    elements, current, folded = [], None, None
    for line in text.split("\nelements:\n", 1)[1].split("\n"):
        item = re.match(r"^  - id: (\S+)\s*$", line)
        if item:
            current = {"id": item.group(1)}
            elements.append(current)
            folded = None
            continue
        if current is None:
            continue
        key = re.match(r"^    ([a-z_]+):\s*(.*)$", line)
        if key:
            name, value = key.group(1), key.group(2).strip()
            folded = name if value in FOLDED else None
            if name in KEYS:
                current[name] = value.strip("\"'")
            elif name == "definition":
                current["definition"] = "" if folded else value.strip("\"'")
            continue
        if folded == "definition" and line.startswith("      "):
            current["definition"] = (current.get("definition", "") + " " + line.strip()).strip()
        elif line.strip() and not line.startswith("      "):
            folded = None
    return elements


def snapshot(text, commit=None):
    elements = parse(text)
    ids = [e["id"] for e in elements]
    if len(ids) != len(set(ids)):
        raise ValueError("duplicate element ids in the ontology")
    counts = {}
    for e in elements:
        counts[e.get("data_class", "unclassified")] = counts.get(e.get("data_class", "unclassified"), 0) + 1
    raw = text.replace("\r\n", "\n").encode("utf-8")
    return {
        "source": "docs/ontology.yaml",
        "commit": commit,
        "git_blob": hashlib.sha1(b"blob %d\0" % len(raw) + raw).hexdigest(),
        "sha256": hashlib.sha256(raw).hexdigest(),
        "counts": dict(sorted(counts.items())),
        "total": len(elements),
        "elements": [{"id": e["id"], "data_class": e.get("data_class", "unclassified"), "layer": e.get("layer", ""),
                      "primitive": e.get("primitive", ""), "lifecycle_status": e.get("lifecycle_status", ""),
                      "ui_display_name": e.get("ui_display_name", ""),
                      "definition": (e.get("definition", "")[:240]).strip()} for e in elements],
    }


def head_commit():
    try:
        return subprocess.run(["git", "-C", str(REPO), "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return None


def main(argv=None):
    mode = (argv or sys.argv[1:] or ["verify"])[0]
    current = snapshot(ONTOLOGY.read_text(encoding="utf-8"), head_commit())
    if mode == "write":
        OUTPUT.write_text(json.dumps(current, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")
        print(f"wrote {OUTPUT.name}: {current['total']} elements {current['counts']}, sha256 {current['sha256'][:12]}")
        return 0
    saved = json.loads(OUTPUT.read_text(encoding="utf-8"))
    if saved["sha256"] == current["sha256"]:
        print("elements.json matches the ontology")
        return 0
    old, new = {e["id"] for e in saved["elements"]}, {e["id"] for e in current["elements"]}
    print(f"elements.json is out of date: ontology changed since {str(saved.get('commit'))[:7]}; "
          f"added {sorted(new - old)}, removed {sorted(old - new)}, counts {saved['counts']} -> {current['counts']}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
