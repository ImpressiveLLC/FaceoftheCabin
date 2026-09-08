#!/usr/bin/env python3
"""
FaceoftheCabin Eval Pipeline — multi-agent edition
====================================================
Runs Ask endpoint trials against the question manifest, writes a JSONL result
file, then hands off to grade_pipeline.py for scoring.

QUICK START (on M920q):
  python3 eval_pipeline.py \\
    --agent-id codex \\
    --manifest /home/nate/FaceoftheCabin/docs/ai-assistant/rag/questions_manifest_r1.json \\
    --token-file /home/nate/.ha_token \\
    --output-dir /home/nate/eval-results

AGENT-SPECIFIC RUN (only common + questions you own):
  python3 eval_pipeline.py --agent-id codex --questions owned ...

SKIP SPECIFIC QUESTIONS (comment them out for this run):
  python3 eval_pipeline.py --skip Q01,Q02 ...

ADD EXTRA QUESTIONS (inline, not yet in manifest):
  python3 eval_pipeline.py --extra-questions extra_q.json ...

Dependencies: Python 3.9+ stdlib only.
"""
import argparse
import json
import os
import pathlib
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone


# ── Constants ─────────────────────────────────────────────────────────────────

DEFAULT_ENDPOINT  = "http://127.0.0.1:8090/api/helpdesk/ask"
DEFAULT_REPEATS   = 3
DEFAULT_OUTPUT    = pathlib.Path.home() / "eval-results"
PIPELINE_VERSION  = "r1"


# ── Argument parsing ───────────────────────────────────────────────────────────

def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="FaceoftheCabin multi-agent eval pipeline",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    p.add_argument("--agent-id", required=True,
        help="Your agent identifier (e.g. codex, cowork-claude, claude-code). "
             "Must match an entry in manifest agent_registry, or use 'guest' for ad-hoc runs.")
    p.add_argument("--manifest", required=True,
        help="Path to questions_manifest_r1.json")
    p.add_argument("--token-file",
        help="Path to file containing the bearer token for /api/helpdesk/ask. "
             "Token is read from first line of file. Omit for unauthenticated endpoints.")
    p.add_argument("--token",
        help="Bearer token value directly (prefer --token-file to keep tokens off the command line).")
    p.add_argument("--endpoint", default=DEFAULT_ENDPOINT,
        help=f"Backend URL (default: {DEFAULT_ENDPOINT})")
    p.add_argument("--repeats", type=int, default=DEFAULT_REPEATS,
        help=f"Trials per question (default: {DEFAULT_REPEATS})")
    p.add_argument("--output-dir", type=pathlib.Path, default=DEFAULT_OUTPUT,
        help=f"Root directory for eval results (default: {DEFAULT_OUTPUT})")
    p.add_argument("--round",
        help="Round label for this run (default: auto-incremented from existing results)")
    p.add_argument("--questions", default="owned",
        help="Which questions to run: 'common' (common only), 'owned' (common + yours), "
             "'all' (every question), or a comma-separated list of IDs (e.g. Q01,Q04,Q10). "
             "Default: owned")
    p.add_argument("--skip",
        help="Comma-separated question IDs to skip for this run, regardless of --questions filter. "
             "Use to comment out questions you don't want to answer in a shared round.")
    p.add_argument("--extra-questions",
        help="Path to a JSON file with additional questions not yet in the manifest. "
             "Format: [{\"id\": \"X01\", \"question\": \"...\", \"owner\": \"<agent-id>\"}]")
    p.add_argument("--dry-run", action="store_true",
        help="Print which questions would be run without calling the endpoint.")
    p.add_argument("--no-grade", action="store_true",
        help="Skip auto-launching grade_pipeline.py after the run completes.")
    p.add_argument("--role",
        help="HouseholdRole to pass in the request body (e.g. ADMINISTRATOR). "
             "Omit for default (non-admin) behavior.")
    p.add_argument("--timeout", type=int, default=60,
        help="HTTP timeout in seconds per request (default: 60)")
    return p


# ── Manifest helpers ───────────────────────────────────────────────────────────

def load_manifest(path: str) -> dict:
    with open(path) as f:
        return json.load(f)


def select_questions(manifest: dict, agent_id: str, filter_arg: str,
                     skip_ids: set, extra: list) -> list[dict]:
    """Return the ordered list of questions to run based on filter rules."""
    all_qs = list(manifest.get("questions", []))
    all_qs.extend(extra)

    if filter_arg == "common":
        selected = [q for q in all_qs if q.get("owner") == "common"]
    elif filter_arg == "all":
        selected = all_qs
    elif filter_arg == "owned":
        selected = [q for q in all_qs
                    if q.get("owner") in ("common", agent_id)]
    else:
        ids = {s.strip() for s in filter_arg.split(",")}
        selected = [q for q in all_qs if q["id"] in ids]

    return [q for q in selected if q["id"] not in skip_ids]


def load_extra(path: str) -> list:
    if not path:
        return []
    with open(path) as f:
        data = json.load(f)
    if isinstance(data, list):
        return data
    return data.get("questions", [])


# ── Round management ───────────────────────────────────────────────────────────

def resolve_round(output_dir: pathlib.Path, agent_id: str,
                  requested_round: str | None) -> str:
    if requested_round:
        return requested_round
    agent_dir = output_dir / agent_id
    if not agent_dir.exists():
        return "r1"
    existing = sorted(p.name for p in agent_dir.iterdir() if p.is_dir())
    if not existing:
        return "r1"
    # Increment last numeric round
    last = existing[-1]
    if last.startswith("r") and last[1:].isdigit():
        return f"r{int(last[1:]) + 1}"
    return f"r{len(existing) + 1}"


def make_run_dir(output_dir: pathlib.Path, agent_id: str, round_label: str) -> pathlib.Path:
    date_str = datetime.now(timezone.utc).strftime("%Y%m%d")
    run_dir = output_dir / agent_id / f"{date_str}-{round_label}"
    run_dir.mkdir(parents=True, exist_ok=True)
    return run_dir


# ── HTTP ───────────────────────────────────────────────────────────────────────

def load_token(token_file: str | None, token_direct: str | None) -> str | None:
    if token_direct:
        return token_direct.strip()
    if token_file:
        p = pathlib.Path(token_file)
        if not p.exists():
            raise FileNotFoundError(f"Token file not found: {token_file}")
        return p.read_text().strip().splitlines()[0]
    return None


def ask(endpoint: str, question: str, token: str | None,
        role: str | None, timeout: int) -> dict:
    """
    POST to the Ask endpoint and return the parsed response dict.
    Returns {"answer": str, "answeredByModel": bool, "sources": [], "error": str|None}
    """
    body: dict = {"question": question}
    if role:
        body["role"] = role

    data = json.dumps(body).encode()
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"

    req = urllib.request.Request(endpoint, data=data, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode()
            return json.loads(raw)
    except urllib.error.HTTPError as e:
        body_text = e.read().decode(errors="replace")
        return {"answer": "", "answeredByModel": False, "sources": [],
                "error": f"HTTP {e.code}: {body_text[:200]}"}
    except Exception as exc:
        return {"answer": "", "answeredByModel": False, "sources": [],
                "error": str(exc)}


# ── Trial runner ───────────────────────────────────────────────────────────────

def run_trial(endpoint: str, question_id: str, question_text: str,
              repeat: int, token: str | None, role: str | None,
              timeout: int) -> dict:
    result = ask(endpoint, question_text, token, role, timeout)
    return {
        "type": "trial",
        "id": question_id,
        "question": question_text,
        "repeat": repeat,
        "answer": result.get("answer", ""),
        "answered_by_model": result.get("answeredByModel", False),
        "sources": result.get("sources", []),
        "error": result.get("error"),
        "ts": datetime.now(timezone.utc).isoformat(),
    }


# ── Main ───────────────────────────────────────────────────────────────────────

def main() -> None:
    args = build_parser().parse_args()

    manifest = load_manifest(args.manifest)
    extra = load_extra(args.extra_questions)
    skip = {s.strip() for s in args.skip.split(",")} if args.skip else set()

    questions = select_questions(manifest, args.agent_id, args.questions, skip, extra)

    if not questions:
        print("No questions selected. Check --questions and --skip filters.", file=sys.stderr)
        sys.exit(1)

    round_label = resolve_round(args.output_dir, args.agent_id, args.round)
    run_dir     = make_run_dir(args.output_dir, args.agent_id, round_label)
    jsonl_path  = run_dir / f"eval-{args.agent_id}-{round_label}.jsonl"
    meta_path   = run_dir / "run-meta.json"

    token = None
    if not args.dry_run:
        token = load_token(args.token_file, args.token)

    # ── Dry run summary ────────────────────────────────────────────────────────
    if args.dry_run:
        print(f"DRY RUN — agent={args.agent_id} round={round_label}")
        print(f"  Questions: {len(questions)}, Repeats: {args.repeats}")
        print(f"  Total trials: {len(questions) * args.repeats}")
        print(f"  Endpoint: {args.endpoint}")
        print(f"  Output: {run_dir}")
        print()
        for q in questions:
            owner_tag = f"[{q.get('owner', '?')}]"
            print(f"  {q['id']:6s} {owner_tag:20s} {q['question']}")
        return

    # ── Write run metadata ─────────────────────────────────────────────────────
    run_meta = {
        "pipeline_version": PIPELINE_VERSION,
        "agent_id": args.agent_id,
        "round": round_label,
        "run_date": datetime.now(timezone.utc).isoformat(),
        "endpoint": args.endpoint,
        "repeats": args.repeats,
        "questions_filter": args.questions,
        "skipped": sorted(skip),
        "question_ids": [q["id"] for q in questions],
        "role": args.role,
        "manifest": str(args.manifest),
        "extra_questions_file": args.extra_questions,
        "jsonl": str(jsonl_path),
    }
    meta_path.write_text(json.dumps(run_meta, indent=2))

    # ── Trials ─────────────────────────────────────────────────────────────────
    total = len(questions) * args.repeats
    done  = 0
    model_count    = 0
    fallback_count = 0
    error_count    = 0

    print(f"[{args.agent_id} / {round_label}]  {len(questions)} questions × {args.repeats} repeats = {total} trials")
    print(f"  Endpoint : {args.endpoint}")
    print(f"  Output   : {jsonl_path}")
    print()

    with open(jsonl_path, "w") as out:
        # Write manifest header record
        out.write(json.dumps({"type": "meta", **run_meta}) + "\n")

        for q in questions:
            qid = q["id"]
            qtxt = q["question"]
            print(f"  {qid}: {qtxt}")
            for rep in range(args.repeats):
                trial = run_trial(
                    args.endpoint, qid, qtxt, rep,
                    token, args.role, args.timeout
                )
                out.write(json.dumps(trial) + "\n")
                out.flush()

                done += 1
                if trial.get("error"):
                    error_count += 1
                    tag = "ERROR"
                elif trial.get("answered_by_model"):
                    model_count += 1
                    tag = "MODEL"
                else:
                    fallback_count += 1
                    tag = "FALLBACK"

                ans_preview = (trial["answer"] or "")[:80].replace("\n", " ")
                print(f"    repeat={rep} [{tag:8s}] {ans_preview}")

                # Polite delay between requests
                if done < total:
                    time.sleep(0.3)

            print()

    print(f"Completed {done}/{total} trials  "
          f"model={model_count}  fallback={fallback_count}  errors={error_count}")
    print(f"Results written to: {jsonl_path}")

    # ── Auto-grade ─────────────────────────────────────────────────────────────
    if not args.no_grade:
        grade_script = pathlib.Path(__file__).parent / "grade_pipeline.py"
        if grade_script.exists():
            print(f"\nLaunching grade_pipeline.py ...")
            os.execv(sys.executable, [
                sys.executable, str(grade_script),
                "--agent-id", args.agent_id,
                "--manifest", args.manifest,
                "--jsonl", str(jsonl_path),
                "--output-dir", str(args.output_dir),
            ])
        else:
            print(f"\ngrade_pipeline.py not found at {grade_script}.")
            print(f"Grade manually: python3 grade_pipeline.py --jsonl {jsonl_path} ...")


if __name__ == "__main__":
    main()
