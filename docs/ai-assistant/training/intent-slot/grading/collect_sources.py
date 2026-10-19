"""Fetch pinned public sources into a local, unapproved review queue. Never trains."""
import argparse
import hashlib
import json
import sys
import urllib.request
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

import yaml

ENFUSE = "39056ca057ae92d71fb7a272f7960567e55dca70"
HA = "a64026f9359dc705138314d7c363fef1f6d7bf5d"
SNIPS = "74b2893c91fc0bafc919a7e088ecb0b2bd611acf"


def ha_unit(data, row, path):
    """Pinned HA file supplies intent; allow only explicit light/fan/climate tests."""
    slots = row.get("slots", {})
    domains = {entity["name"].casefold(): entity["domain"] for entity in data.get("entities", [])}
    domain = slots.get("domain") or domains.get(str(slots.get("name", "")).casefold())
    if domain not in {"light", "fan", "climate"}:
        raise ValueError("HA lock/valve/cover or unknown domain requires governed manual mapping")
    return {"tests": [{"sentences": row["sentences"],
                       "intent": {"name": path.split("/")[2], "slots": slots}}]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--exclude", nargs="*", default=[], choices=["enfuse", "ha", "snips"],
                        help="sources to leave out entirely: nothing from them is downloaded or imported")
    args = parser.parse_args()
    excluded_sources = sorted(set(args.exclude))
    sys.path.insert(0, str(args.package.resolve()))
    from import_sources import adapt
    from contract import proposal, validate
    args.output.mkdir(parents=True, exist_ok=True)
    raw_dir = args.output / "upstream"
    raw_dir.mkdir(exist_ok=True)
    manifest, rows, excluded = [], [], []
    seen = {" ".join(json.loads(line)["text"].casefold().split()) for line in
            (args.package / "gold.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()}

    def fetch(name, url):
        request = urllib.request.Request(url, headers={"User-Agent": "FaceoftheCabin-review/1.0"})
        with urllib.request.urlopen(request, timeout=60) as response:
            raw = response.read()
        (raw_dir / name).write_bytes(raw)
        return raw

    sources = []
    if "enfuse" not in excluded_sources:
        fetch("enfuse-README.md", f"https://huggingface.co/datasets/enfuse/joint-intent-slot-smarthome/resolve/{ENFUSE}/README.md")
        sources.append(("enfuse", ENFUSE, "Apache-2.0", "train.jsonl",
                        f"https://huggingface.co/datasets/enfuse/joint-intent-slot-smarthome/resolve/{ENFUSE}/train.jsonl"))
    if "ha" not in excluded_sources:
        fetch("ha-LICENSE", f"https://raw.githubusercontent.com/OHF-Voice/intents/{HA}/LICENSE.md")
        for intent in ["HassTurnOn", "HassTurnOff"]:
            for name in ["name_only", "area_domain"]:
                path = f"tests/en/{intent}/{name}.yaml"
                sources.append(("ha", HA, "CC-BY-4.0", path,
                                f"https://raw.githubusercontent.com/OHF-Voice/intents/{HA}/{path}"))
    if "snips" not in excluded_sources:
        fetch("snips-LICENSE", f"https://raw.githubusercontent.com/snipsco/snips-nlu/{SNIPS}/LICENSE")
        sources.append(("snips", SNIPS, "Apache-2.0 (repository LICENSE; sample only)",
                        "sample_datasets/lights_dataset.json",
                        f"https://raw.githubusercontent.com/snipsco/snips-nlu/{SNIPS}/sample_datasets/lights_dataset.json"))
    for source, revision, license_name, path, url in sources:
        raw = fetch(source + "-" + path.replace("/", "-"), url)
        sha = hashlib.sha256(raw).hexdigest()
        data = ([json.loads(line) for line in raw.decode().splitlines() if line.strip()] if source == "enfuse"
                else yaml.safe_load(raw) if source == "ha" else json.loads(raw))
        units = ([([r], r) for r in data] if source == "enfuse" else
                 [({"tests": [r]}, r) for r in data["tests"]] if source == "ha" else
                 [({"intents": {name: {"utterances": [u]}}}, u)
                  for name, intent in data["intents"].items() for u in intent["utterances"]])
        before, rejected_before = len(rows), len(excluded)
        for index, (unit, original) in enumerate(units):
            note = ""
            # Existing adapter validates on construction. Clarify unitless temperature
            # before adaptation; never silently assume C or F from numeric values.
            if source == "enfuse":
                slots = {s["slot_type"]: s["slot_value"] for s in original["slots"]}
                if slots.get("attribute_type") == "temperature":
                    excluded.append({"source": source, "path": path, "record": index,
                                     "reason": "Temperature needs explicit unit review and label correction", "original": original})
                    continue
            try:
                if source == "ha":
                    unit = ha_unit(data, original, path)
                candidates = list(adapt(source, unit))
                for offset, (text, target) in enumerate(candidates):
                    if target["intent"] == "set_device_state" and target["slots"].get("state") not in {"on", "off"}:
                        excluded.append({"source": source, "path": path, "record": index,
                                         "reason": "State is not explicit on/off; numeric or compound labels require correction", "original": original})
                        continue
                    normalized = " ".join(text.casefold().split())
                    if normalized in seen:
                        excluded.append({"source": source, "path": path, "record": index,
                                         "reason": "Exact duplicate or overlap with held-out gold", "original": original})
                        continue
                    validate(target)
                    seen.add(normalized)
                    rows.append({"id": f"{source}-{sha[:12]}-{index}-{offset}", "text": text,
                                 "target": target, "critical": False, "group": f"{source}-{sha}",
                                 "provenance": {"source": source, "source_url": url,
                                    "revision": revision, "sha256": sha, "license": license_name,
                                    "record": index, "path": path, "review_status": "unreviewed",
                                    "purpose": "training-candidate", "mapping_note": note}})
            except (ValueError, KeyError, TypeError) as exc:
                excluded.append({"source": source, "path": path, "record": index,
                                 "reason": str(exc).splitlines()[0], "original": original})
            except Exception as exc:
                # Schema mismatches stay visible in quarantine, never silently dropped.
                excluded.append({"source": source, "path": path, "record": index,
                                 "reason": type(exc).__name__ + ": " + str(exc).splitlines()[0], "original": original})
        manifest.append({"source": source, "url": url, "revision": revision, "sha256": sha,
                         "license": license_name, "input_records": len(units),
                         "candidates": len(rows)-before, "quarantine_records": len(excluded)-rejected_before})
    for name, values in [("candidates.jsonl", rows), ("quarantine.jsonl", excluded)]:
        (args.output / name).write_text("".join(json.dumps(r, ensure_ascii=False)+"\n" for r in values), encoding="utf-8")
    report = {"created_at": datetime.now(timezone.utc).isoformat(), "sources": manifest,
              "candidate_count": len(rows), "quarantine_count": len(excluded),
              "candidate_sha256": hashlib.sha256((args.output / "candidates.jsonl").read_bytes()).hexdigest(),
              "by_source": dict(Counter(r["provenance"]["source"] for r in rows)),
              "excluded_sources": excluded_sources,
              "by_intent": dict(sorted(Counter(r["target"]["intent"] for r in rows).items())),
              "by_source_and_intent": {s: dict(sorted(Counter(r["target"]["intent"] for r in rows if r["provenance"]["source"] == s).items()))
                                       for s in sorted({r["provenance"]["source"] for r in rows})},
              "quarantine_by_reason": dict(Counter(x["reason"] for x in excluded).most_common(12)),
              "training_run": False, "human_approved": 0, "model_evaluated": False,
              "note": "Selected HA concrete on/off files only. Source-file groups unsplit. Quarantine retained. CASAS deferred: not an intent corpus."}
    (args.output / "manifest.json").write_text(json.dumps(report, indent=2)+"\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()

