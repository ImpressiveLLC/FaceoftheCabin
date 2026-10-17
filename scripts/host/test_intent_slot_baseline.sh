#!/usr/bin/env bash
# Tests for intent-slot-baseline with a fake docker, a fake pgrep and a scratch package. Touches nothing real.
#   bash scripts/host/test_intent_slot_baseline.sh
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
SCRIPT="$HERE/intent-slot-baseline"
W=$(mktemp -d)
trap 'rm -rf -- "${W:?}"' EXIT
PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "  ok   $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  FAIL $1"; }
check(){ if eval "$2"; then ok "$1"; else bad "$1  [$2]"; fi; }

# scratch package
SRC="$W/src"; mkdir -p "$SRC"
for f in contract.py evaluate.py run_reports.py gold.jsonl intent-slot.schema.json request.schema.json system.txt requirements.txt Modelfile.intent-r1; do echo "x" >"$SRC/$f"; done

# fake docker: logs every call; models come from $W/models; `run` of evaluate writes baseline.json
cat >"$W/docker" <<'EOF'
#!/usr/bin/env bash
echo "$*" >>"$FAKE_LOG"
case "$1" in
  ps) printf 'ollama\tUp 3 days\ncabin-backend\tUp 3 days\n' ;;
  stats) echo "2.5GiB / 31GiB" ;;
  cp) ;;
  exec)
    if [[ "$*" == *"ollama create"* ]]; then echo "cabin-intent-slot-prompt-r1:latest  feedc0ffee12  2.0 GB  now" >>"$FAKE_MODELS"; echo "success"
    elif [[ "$*" == *"ollama list"* ]]; then cat "$FAKE_MODELS"
    elif [[ "$*" == *"ollama ps"* ]]; then echo "NAME ID SIZE"
    fi ;;
  run)
    out=""; for a in "$@"; do [[ "$a" == *:/out || "$a" == *:/out:ro ]] && out="${a%%:/out*}"; done
    if [[ "$*" == *evaluate.py* ]]; then
      echo '{}' >"$out/baseline.json"; exit "${FAKE_EVAL_RC:-1}"
    elif [[ "$*" == *run_reports.py* ]]; then echo "## Prompt-only baseline: fake"; fi ;;
esac
EOF
chmod +x "$W/docker"
printf 'NAME ID SIZE MODIFIED\nllama3.2:3b  abc123  2.0 GB  3 weeks ago\n' >"$W/models.base"
printf '#!/usr/bin/env bash\nexit 1\n' >"$W/pgrep_idle"; chmod +x "$W/pgrep_idle"
printf '#!/usr/bin/env bash\necho "4242 python train_sft.py --data data/sft_dataset.jsonl"\n' >"$W/pgrep_busy"; chmod +x "$W/pgrep_busy"

run() {   # run [VAR=value ...]; fresh models + log + out each time unless overridden
  rm -f "$W/log" "$W/stdout" "$W/stderr"; cp "$W/models.base" "$W/models"
  env DOCKER="$W/docker" PGREP="$W/pgrep_idle" FAKE_LOG="$W/log" FAKE_MODELS="$W/models" \
      INTENT_SLOT_SRC="$SRC" INTENT_SLOT_OUT="$W/out" INTENT_SLOT_SAMPLE_SECONDS=0.1 "$@" \
      bash "$SCRIPT" >"$W/stdout" 2>"$W/stderr"
  echo $? >"$W/rc"
}
rc() { cat "$W/rc"; }
calls() { cat "$W/log" 2>/dev/null; }

echo "refusals before anything is touched"
rm -rf "$W/out"; run INTENT_SLOT_TAG=llama3.2:3b
check "a production or base tag is refused" "[ \$(rc) = 2 ] && grep -q 'never a production tag' $W/stderr"
check "and no docker call was made" "[ -z \"\$(calls)\" ]"
run INTENT_SLOT_TAG=cabin-helpdesk-r1
check "a tag outside cabin-intent-slot-* is refused" "[ \$(rc) = 2 ]"
run 'INTENT_SLOT_TAG=cabin-intent-slot-x;rm'
check "a tag with shell metacharacters is refused" "[ \$(rc) = 2 ] && grep -q 'may only use' $W/stderr"
rm -rf "$W/out"; run PGREP="$W/pgrep_busy"
check "an active training run stops it" "[ \$(rc) = 2 ] && grep -q 'training or another eval looks active' $W/stderr && grep -q train_sft $W/stderr"
check "before any docker call" "[ -z \"\$(calls)\" ]"
rm -rf "$W/out"; run INTENT_SLOT_SRC="$W/empty"
check "a missing package file stops it" "[ \$(rc) = 2 ] && grep -q 'missing' $W/stderr"

echo "base model and existing tag"
rm -rf "$W/out"; mkdir -p "$W"; : >"$W/nobase"
cat >"$W/docker_nobase" <<'EOF'
#!/usr/bin/env bash
echo "$*" >>"$FAKE_LOG"
[[ "$1" == exec && "$*" == *"ollama list"* ]] && { echo "NAME ID SIZE"; exit 0; }
exit 0
EOF
chmod +x "$W/docker_nobase"
run DOCKER="$W/docker_nobase"
check "a missing base model is reported, not pulled" "[ \$(rc) = 2 ] && grep -q 'not pulled' $W/stderr && ! grep -q 'pull' <(calls)"
rm -rf "$W/out"; rm -f "$W/log"; cp "$W/models.base" "$W/models"; echo "cabin-intent-slot-prompt-r1:latest abc 2 GB now" >>"$W/models"
env DOCKER="$W/docker" PGREP="$W/pgrep_idle" FAKE_LOG="$W/log" FAKE_MODELS="$W/models" INTENT_SLOT_SRC="$SRC" INTENT_SLOT_OUT="$W/out" bash "$SCRIPT" >/dev/null 2>"$W/stderr"; echo $? >"$W/rc"
check "an existing candidate tag is not silently recreated" "[ \$(rc) = 2 ] && grep -q 'REPLACE=1' $W/stderr && ! grep -q 'ollama create' $W/log"

echo "a full run"
rm -rf "$W/out"; run
check "an offline-gate failure (exit 1) is a result, not an error" "[ \$(rc) = 0 ] && grep -q 'gates exit code: 1' $W/stdout"
check "the candidate tag was created in the ollama container" "grep -q 'exec ollama ollama create cabin-intent-slot-prompt-r1 -f /tmp/Modelfile.intent-r1' $W/log"
check "evaluate ran in a throwaway container on the ollama network" "grep -q 'run --rm --network container:ollama' $W/log"
check "the package copy and its hashes are kept" "[ -f $W/out/package/evaluate.py ] && [ \$(wc -l <$W/out/package.sha256) = 8 ]"
check "the host state was recorded first" "grep -q '^## memory' $W/out/preflight.txt && grep -q '^## ollama models' $W/out/preflight.txt"
check "peak memory comes from the samples (2.5 GiB = 2560 MiB)" "grep -q 'peak container memory 2560 MiB' $W/stdout"
check "the model id is reported" "grep -q 'feedc0ffee12' $W/stdout"
check "evidence.md is written for the PR" "grep -q 'Prompt-only baseline' $W/out/evidence.md"
check "the report call carries peak memory and digest" "grep -q 'run_reports.py baseline /out/baseline.json' $W/log && grep -q -- '--peak-rss-mib 2560' $W/log && grep -q -- '--digest feedc0ffee12' $W/log"
check "the script says how to remove the tag but never does" "grep -q 'ollama rm' $W/stdout && ! grep -q 'ollama rm' $W/log"

echo "evidence and failure handling"
run
check "a second run against the same output directory will not overwrite evidence" "[ \$(rc) = 2 ] && grep -q 'not overwriting evidence' $W/stderr"
rm -rf "$W/out"; run FAKE_EVAL_RC=7
check "an evaluation that could not run fails loudly" "[ \$(rc) = 2 ] && grep -q 'evaluation did not run (exit 7)' $W/stderr"

echo "safety by inspection"
check "no deletion, stop, compose or training command anywhere in the script" "! grep -nE '(^|[^a-z])(rm -r|rm -f|docker rm|docker stop|docker kill|compose|ollama rm|train_sft.py [^|]*--|git push)' <(grep -v '^#' $SCRIPT | grep -v 'echo \"to remove')"
check "bash syntax is valid" "bash -n $SCRIPT"

echo
echo "passed $PASS, failed $FAIL"
[ "$FAIL" = 0 ]
