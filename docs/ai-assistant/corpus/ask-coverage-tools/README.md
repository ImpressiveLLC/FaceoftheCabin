# Ask routing replica (offline)

`route.py` is an offline copy of the keyword routing in `AskContextBuilder.select()` (same whole-word regex with an optional `s`/`es` plural, word-count scoring, boost only when a keyword matched, declaration order on ties). It reads the compiled map and a list of questions and prints, for each, the category Ask would choose or `NO CATEGORY`. It sends nothing to Ask, Ollama or any endpoint.

`phrasings.json` holds 150 plain-language questions written by the coverage-map analyst, keyed by the topic row in `../ask-coverage-map-2026-10-30.md` (A1, A2, ...). They are not drawn from usage logs or from any sealed or held-out set.

Reproduce the 2026-10-30 result (95 of 150 match no category) from the repository root:

```
python3 -I -c "import json;[print(f'{t}-{i}|{q}') for t,qs in json.load(open('docs/ai-assistant/corpus/ask-coverage-tools/phrasings.json')) for i,q in enumerate(qs,1)]" > /tmp/ask-phrasings.txt
python3 -I docs/ai-assistant/corpus/ask-coverage-tools/route.py cabin-orchestration-platform/backend/src/main/resources/rag/context-fixtures-r2.json /tmp/ask-phrasings.txt | grep -c "NO CATEGORY"
```

Keep it in step with `AskContextBuilder` if the matching rules change; the backend routing test, not this script, is the authority.
