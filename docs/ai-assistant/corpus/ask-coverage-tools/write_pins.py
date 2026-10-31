"""Write the routing pins: every in-scope phrasing of the three 2026-10-31 proposals and the category the map sends it to.

  python write_pins.py ../../rag/context-fixtures-r2.json ../../../../cabin-orchestration-platform/backend/src/test/resources/rag/routing-pins.json

Refuses to write if any in-scope phrasing routes to no category or to a category the proposals do not accept
(apply_routing.EXPECTED), so a pin is always an agreed answer. AskRoutingCoverageTest asserts every pin against the real
AskContextBuilder, so changing a keyword that moves any of the 120 phrasings fails the build.
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import apply_routing as ar

fx = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
routed = ar.routes(fx)
pins, bad = [], []
for n, tid, q in ar.phrasings():
    if n not in ar.EXPECTED:
        continue
    got = routed[n]
    if got is None or got not in ar.EXPECTED[n]:
        bad.append((n, got))
    pins.append({"n": n, "id": tid, "question": q, "category": got})
if bad:
    raise SystemExit(f"not writing: {bad}")
out = Path(sys.argv[2])
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text(json.dumps(pins, indent=1, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")
print(f"wrote {len(pins)} pins to {out}")
