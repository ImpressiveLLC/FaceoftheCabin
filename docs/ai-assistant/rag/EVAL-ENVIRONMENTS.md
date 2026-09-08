# FaceoftheCabin Eval Pipeline — Environment Guide

This document is the onboarding reference for any agent joining the shared eval pipeline.
Read it before running eval_pipeline.py for the first time.

---

## 1. Production backend — M920q

The only environment where evals count. All ratification runs execute here.

| Property | Value |
|----------|-------|
| Hostname | `nates-Little-M920q` (Tailscale) |
| Tailscale IP | `100.77.44.113` |
| SSH user | `nate` |
| Backend port | `8090` |
| Ask endpoint | `http://127.0.0.1:8090/api/helpdesk/ask` |
| Ollama | `http://localhost:11434` |
| Compose files | `~/FaceoftheCabin/cabin-orchestration-platform/infra/docker-compose.yml` + `docker-compose.m920q.yml` |
| Service name | `cabin-backend` |

### Connecting

```bash
# From any machine with Tailscale
ssh nate@nates-Little-M920q

# Or by IP if MagicDNS is not resolving
ssh nate@100.77.44.113
```

### Running the backend

```bash
cd ~/FaceoftheCabin/cabin-orchestration-platform/infra
docker compose -f docker-compose.yml -f docker-compose.m920q.yml up -d --no-cache cabin-backend
```

Always use `--no-cache` for Java source changes. Without it, Maven layer cache prevents
new classes from appearing in the image.

### Health check

```bash
curl -s http://127.0.0.1:8090/actuator/health | python3 -m json.tool
```

Expect `"status": "UP"` at the top level before running any eval.

---

## 2. Claude Code CLI on M920q (preferred agent execution environment)

The Claude Code CLI installed directly on M920q has direct filesystem and git access.
This avoids cross-workspace confusion (Windows PowerShell ↔ cloud container ↔ Tailscale).

```bash
# Install (already installed as of 2026-09-07)
curl -fsSL https://claude.ai/install.sh | bash

# Launch (from M920q terminal or SSH session)
claude
```

When running evals from inside a Claude Code session on M920q, all paths are local.
Use the paths in Section 4 directly — no staging or SCP required.

---

## 3. GitHub — canonical source of truth

| Property | Value |
|----------|-------|
| Repo | `ImpressiveLLC/FaceoftheCabin` |
| Default branch | `main` |
| PR requirement | Every production artifact (code, docs, eval scripts) lands in a PR before it is real |
| Direct commit to main | Never |

### Agent workflow

1. Create a branch: `git checkout -b <agent-id>/<feature>`
2. Work on files
3. Open PR with title and body (see handover doc Deliverable Format section)
4. Cowork reviews PRs that touch ontology, security rules, or WSJF prioritization
5. Backend Code reviews PRs that touch `cabin-orchestration-platform/`

### Cloning

```bash
git clone https://github.com/ImpressiveLLC/FaceoftheCabin.git ~/FaceoftheCabin
```

---

## 4. File locations — M920q

These are the canonical paths. Use them verbatim in scripts and commands.

### Eval harness (frozen — do not rewrite)

```
/home/nate/.local/share/faceofthecabin-eval/1ea6e87/ask_eval.py
```

SHA-256: `e106cf75a6adabdb7826e00b7bbb30cf119ab432562b8a7a1244a42840125e54`

### Multi-agent eval pipeline (this system)

```
/home/nate/FaceoftheCabin/docs/ai-assistant/rag/scripts/eval_pipeline.py
/home/nate/FaceoftheCabin/docs/ai-assistant/rag/scripts/grade_pipeline.py
/home/nate/FaceoftheCabin/docs/ai-assistant/rag/questions_manifest_r1.json
```

### Question manifest

```
/home/nate/FaceoftheCabin/docs/ai-assistant/rag/questions_manifest_r1.json
```

This is the live file. Add questions here via PR before running them in production.

### Frozen baseline

```
/home/nate/FaceoftheCabin/docs/ai-assistant/rag/cli-baseline-2026-09-07.md
```

Do not modify. Reference only.

### Context fixtures (C1a)

```
/home/nate/FaceoftheCabin/docs/ai-assistant/rag/context-fixtures-r1.json
```

The deterministic keyword-to-KnowledgeNode mapping. Edit to improve C1a retrieval.
Must be placed at `src/main/resources/rag/context-fixtures-r1.json` in the
backend classpath (synced via PR → CI deploy).

### Eval results (per-agent, per-round)

```
/home/nate/eval-results/
  <agent-id>/
    <YYYYMMDD>-<round>/
      run-meta.json
      eval-<agent-id>-<round>.jsonl
      eval-<agent-id>-<round>-grades.json
  round-<round>-summary.json          ← written by grade_pipeline.py summary command
```

Example:
```
/home/nate/eval-results/codex/20260907-r2/eval-codex-r2.jsonl
/home/nate/eval-results/cowork-claude/20260907-r2/eval-cowork-claude-r2.jsonl
/home/nate/eval-results/round-r2-summary.json
```

### Token file

```
/home/nate/.ha_token
```

First line is the bearer token. Pass with `--token-file /home/nate/.ha_token`.
Never paste the token value on the command line or in a script.

---

## 5. Corpus — what must exist for questions to pass

The questions manifest's `source_docs` field lists what files must contain the answer
for each question. Files with `source_doc_status: not_yet_written` will produce
"I don't have any information" answers until they are written and ingested.

### Current corpus status

| Location | Status | Used for |
|----------|--------|----------|
| `docs/user-guide/user-onboarding.md` | ❌ not written | Q01, Q11 |
| `docs/user-guide/tokens-security.md` | ❌ not written | Q01, Q03, Q10, Q12, Q13 |
| `docs/user-guide/device-onboarding.md` | ❌ not written | Q04, Q05, Q06, Q07, Q08, Q18 |
| `docs/user-guide/quick-start.md` | ❌ not written | Q06, Q12, Q14, Q15, Q16, Q19, Q21 |
| `docs/user-guide/maintenance.md` | ❌ not written | Q08, Q14, Q15, Q16, Q17 |
| `docs/user-guide/integrations.md` | ❌ not written | Q03, Q08, Q18 |
| `docs/user-guide/configuration.md` | ❌ not written | Q12, Q13, Q15 |
| `docs/user-guide/credential-management.md` | ❌ not written | Q11 |
| `docs/user-guide/ask-and-helpdesk.md` | ❌ not written | Q21, Q22, Q23 |
| `handover/codex-handover-2026-09-07-r1.md` | ✅ written (project doc) | Q22, Q23, Q24, Q25 |
| `docs/ai-assistant/wsjf-backlog.md` | ✅ written (partial) | Q25 |
| `docs/ai-assistant/rag/context-fixtures-r1.json` | ✅ written | C1a deterministic injection |

### Writing corpus docs

Every corpus file is a markdown document authored by Codex and shipped via PR.
Before writing one, read the A3 section of the handover doc for required sections
and rules for authorship. Each doc must:

- Contain factual information derived from the ontology artifact, code handover,
  and codebase — never invented
- Be referenced by at least one question's `source_docs` field in the manifest
- Be ingested by `ingest.py` before it affects Ask answers (C1b only;
  C1a uses `context-fixtures-r1.json` for deterministic lookup)

### Adding a corpus doc to context-fixtures-r1.json (C1a path)

For a question to benefit from C1a injection before C1b ships:

1. Write the corpus doc
2. Identify the keyword(s) in the question that identify its category
3. Add a category entry to `context-fixtures-r1.json` pointing to the relevant KnowledgeNodes
4. Redeploy the backend (`--no-cache`) and re-run the eval to verify

---

## 6. Running the pipeline — step by step

### First-time agent setup

```bash
# 1. Register your agent-id in the manifest
#    Edit questions_manifest_r1.json → agent_registry → add your entry
#    Open a PR with just this change before running

# 2. Confirm the backend is healthy
curl -s http://127.0.0.1:8090/actuator/health

# 3. Confirm your token file exists
head -c 10 /home/nate/.ha_token && echo " [truncated]"

# 4. Dry run to see which questions you'd run
python3 /home/nate/FaceoftheCabin/docs/ai-assistant/rag/scripts/eval_pipeline.py \
  --agent-id <your-id> \
  --manifest /home/nate/FaceoftheCabin/docs/ai-assistant/rag/questions_manifest_r1.json \
  --token-file /home/nate/.ha_token \
  --output-dir /home/nate/eval-results \
  --dry-run
```

### Running your eval

```bash
python3 /home/nate/FaceoftheCabin/docs/ai-assistant/rag/scripts/eval_pipeline.py \
  --agent-id <your-id> \
  --manifest /home/nate/FaceoftheCabin/docs/ai-assistant/rag/questions_manifest_r1.json \
  --token-file /home/nate/.ha_token \
  --output-dir /home/nate/eval-results \
  --questions owned \
  --round r2
```

This runs common + owned questions, writes results, then auto-launches the grader.

### Skipping questions you don't want to own in a shared round

```bash
  --skip Q18,Q24,Q25
```

Skipped questions are noted in run-meta.json and excluded from your grades file.
Other agents in the round can still run them.

### Grading manually (if auto-grade was skipped)

```bash
python3 /home/nate/FaceoftheCabin/docs/ai-assistant/rag/scripts/grade_pipeline.py grade \
  --agent-id <your-id> \
  --manifest /home/nate/FaceoftheCabin/docs/ai-assistant/rag/questions_manifest_r1.json \
  --jsonl /home/nate/eval-results/<agent-id>/<date>-<round>/eval-<agent-id>-<round>.jsonl \
  --output-dir /home/nate/eval-results \
  --criteria
```

`--criteria` shows the manifest's required and fail_if criteria alongside each answer.

### Generating the round summary (after all agents have graded)

```bash
python3 /home/nate/FaceoftheCabin/docs/ai-assistant/rag/scripts/grade_pipeline.py summary \
  --manifest /home/nate/FaceoftheCabin/docs/ai-assistant/rag/questions_manifest_r1.json \
  --output-dir /home/nate/eval-results \
  --round r2
```

This finds all `*-grades.json` files under `eval-results/*/` matching the round and
produces a comparison table plus `round-r2-summary.json`.

---

## 7. Adding new questions to the shared pipeline

1. Edit `questions_manifest_r1.json` — follow the `adding_questions` instructions in the file
2. Set `owner` to your agent-id if it's agent-specific, or `common` if all agents should run it
3. Identify and write (or reference) the `source_docs` that should contain the answer
4. Open a PR with the manifest update before running the question in production
5. All agents in the next round automatically pick up `common` questions;
   agent-specific questions only run for the owning agent unless `--questions all` is passed

---

## 8. Ratification gate

| Gate | Requirement |
|------|-------------|
| C1b authorized | Server-side (no --use-context flag) pass count strictly > 2/24 |
| No safety regression | Q03, Q10, Q18, Q24, Q25 must not have content violations (refusal fails are flagged, not blocking) |
| Cowork ratification | Cowork reviews the round summary and writes ratification to the handover doc |

Current status: **C1b NOT authorized** (C1a ratification FAIL, 2026-09-07).

---

## 9. Security rules — mandatory for all agents

- Never `cat`, print, log, or surface a secret value in any output, eval result, or PR
- Compare secrets by presence or prefix only
- CREDENTIAL_POINTER knowledge nodes contain vault key names only — never raw credentials
- Eval JSONL files must not contain raw token values; token is sent over HTTP only
- `WEBUI_AUTH=false` is intentional — Tailscale is the access layer, not HTTP auth
- Safety-critical KnowledgeNodes (leak, valve, freeze) = `manually_curated` only
- If an eval answer contains what appears to be a raw credential, treat it as a bug and report immediately
