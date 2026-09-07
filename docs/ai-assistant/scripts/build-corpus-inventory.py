#!/usr/bin/env python3
"""Offline source inventory, not an ingester or a runtime/API client.

Reads allowlisted files from an explicit Git revision, never the working .env,
vaults, sessions or live configuration. Outputs names/references, not values.
Regex extraction is an audit starting point, not an authorization/API schema.
"""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
OUTPUT = ROOT / "docs/ai-assistant/corpus"
BASELINE = "cd145620bcbe7deaa30e236779ec7e1134db7880"


def git(*args):
    return subprocess.check_output(["git", "-C", str(ROOT), *args])


def uncomment(text):
    # Keep string literals and offsets; comments cannot create fake routes.
    pattern = r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/'
    return re.sub(pattern, lambda m: re.sub(r"[^\n]", " ", m[0])
                  if m[0].startswith(("//", "/*")) else m[0], text)


def line_at(text, offset):
    return text.count("\n", 0, offset) + 1


def source_link(path, line, revision):
    return f"https://github.com/ImpressiveLLC/FaceoftheCabin/blob/{revision}/{path}#L{line}"


def build(revision):
    revision = git("rev-parse", "--verify", revision + "^{commit}").decode().strip()
    paths = git("ls-tree", "-r", "--name-only", revision).decode().splitlines()
    selected = []
    for path in paths:
        p = Path(path)
        if any(x in p.parts for x in ("test", "tests", "test-fixtures", "node_modules", ".git")):
            continue
        if p.name.startswith(".env") or "vault" in p.name.lower() or "/group_vars/" in path:
            continue
        if path.startswith(("cabin-orchestration-platform/", "family-hub/", "ansible/roles/", ".github/workflows/")):
            if p.suffix in (".java", ".py", ".js", ".jsx", ".ts", ".tsx", ".yml", ".yaml", ".j2") or p.name == "Dockerfile":
                selected.append(path)
    sources, routes, config, services, gaps = [], [], {}, [], []
    for path in selected:
        raw = git("cat-file", "blob", f"{revision}:{path}")
        text = raw.decode("utf-8-sig")
        sources.append({"path": path, "sha256": hashlib.sha256(raw).hexdigest()})
        clean = uncomment(text) if Path(path).suffix in (".java", ".js", ".jsx", ".ts", ".tsx") else text
        if path.endswith(".java") and re.search(r"@(?:RestController|Controller)\b", clean):
            class_match = re.search(r"\bclass\s+(\w+)", clean)
            if not class_match:
                gaps.append({"path": path, "reason": "controller class not parsed"})
                continue
            prefix_part = clean[:class_match.start()]
            base_match = re.search(r"@RequestMapping\s*\((.*?)\)", prefix_part, re.S)
            bases = re.findall(r'"([^"\n]*)"', base_match[1]) if base_match else [""]
            if not bases:
                gaps.append({"path": path, "reason": "nonliteral class mapping"})
                bases = ["[UNRESOLVED]"]
            for m in re.finditer(r"@(Get|Post|Put|Patch|Delete|Request)Mapping\b(?:[ \t]*\((.*?)\))?", clean[class_match.end():], re.S):
                offset = class_match.end() + m.start()
                args = m[2] or ""
                path_args = args.split("produces")[0].split("consumes")[0]
                suffixes = re.findall(r'"([^"\n]*)"', path_args) or [""]
                methods = re.findall(r"RequestMethod\.(\w+)", args) if m[1] == "Request" else [m[1].upper()]
                for base in bases:
                    for suffix in suffixes:
                        for method in methods or ["ANY"]:
                            routes.append({"kind": "spring", "method": method,
                                           "route": base.rstrip("/") + suffix,
                                           "path": path, "line": line_at(clean, offset),
                                           "review": "source mapping only; permissions and side effects require review"})
        if path.endswith(".py"):
            for m in re.finditer(r'@(app|router)\.(get|post|put|patch|delete)\(\s*["\']([^"\']+)', text):
                routes.append({"kind": "python-decorator", "method": m[2].upper(), "route": m[3],
                               "path": path, "line": line_at(text, m.start()),
                               "review": "decorator path; router prefixes and permissions require review"})
        patterns = [r"\$\{([A-Za-z_][A-Za-z0-9_.-]*)", r"(?:process\.env\.|import\.meta\.env\.)([A-Z][A-Z0-9_]*)",
                    r"(?:getenv|environ\.get)\(\s*['\"]([A-Z][A-Z0-9_]*)", r"environ\[\s*['\"]([A-Z][A-Z0-9_]*)"]
        if Path(path).name == "Dockerfile":
            patterns.append(r"(?m)^\s*(?:ARG|ENV)\s+([A-Z][A-Z0-9_]*)")
        for pattern in patterns:
            for m in re.finditer(pattern, clean):
                name = m[1]
                if not (name.isupper() or path.endswith(".java")):
                    continue
                config.setdefault(name, set()).add((path, line_at(clean, m.start())))
        if "docker-compose" in Path(path).name:
            in_services = False
            for number, line in enumerate(text.splitlines(), 1):
                if line == "services:":
                    in_services = True
                elif re.match(r"^[A-Za-z]", line):
                    in_services = False
                match = re.match(r"^  ([A-Za-z0-9_-]+):\s*(?:#.*)?$", line)
                if in_services and match:
                    services.append({"name": match[1], "path": path, "line": number,
                                     "review": "declaration only; overlay/profile may disable or modify service"})
    result = {"schema_version": 1, "source_revision": revision,
              "scope": "tracked source declarations; not semantic completeness or deployed state",
              "exclusions": ["raw values/defaults", ".env files", "vault filenames", "group_vars", "tests/fixtures", "live state"],
              "sources": sources, "routes": routes, "services": services,
              "configuration_references": [{"name": name, "references": [{"path": p, "line": n} for p, n in sorted(refs)]}
                                            for name, refs in sorted(config.items())],
              "extraction_gaps": gaps}
    md = ["# Source inventory", "", f"Source revision: `{revision}`.", "",
          "Generated offline from allowlisted tracked source. Names and source locations only; no secret values/defaults.",
          "This is a structural audit index, not a complete API contract, permission map or runtime inventory.",
          "Review the [coverage manifest](coverage.md) for semantic coverage and remaining gaps.", "",
          f"Scanned {len(sources)} source files; found {len(routes)} route declarations, {len(services)} compose service declarations and {len(config)} configuration names.",
          "Repeated services represent overlays/locations, not additional running containers.", "",
          "## Routes", "", "GET does not prove read-only: the platform-import proposals route contacts a provider and upserts records.", "",
          "| Method | Declared path | Source |", "|---|---|---|"]
    for r in routes:
        md.append(f"| {r['method']} | `{r['route']}` | [{Path(r['path']).name}:{r['line']}]({source_link(r['path'], r['line'], revision)}) |")
    md += ["", "## Compose service declarations", "", "Profiles, overlays and external networks require source review before installation.", "",
           "| Service | Source |", "|---|---|"]
    for s in services:
        md.append(f"| `{s['name']}` | [{s['path']}:{s['line']}]({source_link(s['path'], s['line'], revision)}) |")
    md += ["", "## Configuration references", "", "Requiredness, defaults and purpose are not inferred from names. See [configuration review](../user-guide/configuration.md).", "",
           "| Name | Source references |", "|---|---|"]
    for c in result["configuration_references"]:
        refs = "; ".join(f"[{Path(r['path']).name}:{r['line']}]({source_link(r['path'], r['line'], revision)})" for r in c["references"])
        md.append(f"| `{c['name']}` | {refs} |")
    md += ["", "## Extraction limits", "", "Regex extraction does not evaluate Spring conditions, inherited mappings, gateway prefixes, Compose interpolation/merging, environment precedence or authorization. YAML keys without an environment reference and dynamic settings may not appear. Review source hashes and coverage gaps before treating a capability as documented.", "",
           f"Explicit parser gaps: {len(gaps)}. Zero parser gaps is not proof of semantic completeness.", ""]
    return {"source-inventory.json": json.dumps(result, indent=2, ensure_ascii=False) + "\n",
            "source-inventory.md": "\n".join(md)}, result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--revision", default=BASELINE)
    parser.add_argument("--check", action="store_true", help="compare without changing generated files")
    args = parser.parse_args()
    files, result = build(args.revision)
    for name, content in files.items():
        target = OUTPUT / name
        if args.check:
            if not target.exists() or target.read_text(encoding="utf-8") != content:
                raise SystemExit(f"Inventory differs: {name}; regenerate/review explicitly")
        else:
            OUTPUT.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8", newline="\n")
    print(json.dumps({"checked": args.check, "revision": result["source_revision"],
                      "files": len(result["sources"]), "routes": len(result["routes"]),
                      "service_declarations": len(result["services"]),
                      "configuration_names": len(result["configuration_references"]),
                      "parser_gaps": len(result["extraction_gaps"])}))


if __name__ == "__main__":
    main()
