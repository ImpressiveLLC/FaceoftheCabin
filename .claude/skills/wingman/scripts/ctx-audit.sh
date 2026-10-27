#!/usr/bin/env bash
# Wingman ctx-audit: read-only, one screen. Clone freshness, open PRs with gates,
# board age, new #244 comments, running and recent deploys.
# Env: WINGMAN_REPO (ImpressiveLLC/FaceoftheCabin), WINGMAN_ISSUE (244),
#      WINGMAN_BOARD_COMMENT (6449313109), WINGMAN_NO_FETCH=1 to skip `git fetch`.
# Exit 0 when nothing FAILed, 1 otherwise, 2 when not in a clone or gh is missing.
set -u
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
. "$here/lib.sh"
ISSUE="${WINGMAN_ISSUE:-244}"
BOARD="${WINGMAN_BOARD_COMMENT:-6449313109}"

w_need gh
root=$(git rev-parse --show-toplevel 2>/dev/null) || { say FAIL "not inside a git clone"; exit 2; }
cd "$root" || exit 2

echo "== time"
printf 'CDT %s | UTC %s | stamp comments from the clock tool if this disagrees\n' \
  "$(TZ=America/Chicago date '+%a %Y-%m-%d %H:%M')" "$(date -u '+%H:%M:%SZ')"

echo "== clone ($root)"
if [ "${WINGMAN_NO_FETCH:-0}" != 1 ]; then
  git fetch origin --quiet --prune 2>/dev/null || say WARN "git fetch failed (offline or proxy); numbers below may be stale"
fi
br=$(git rev-parse --abbrev-ref HEAD)
dirty=$(git status --porcelain | wc -l | tr -d ' ')
behind=$(git rev-list --count HEAD..origin/main 2>/dev/null || echo '?')
echo "branch $br | dirty $dirty | behind origin/main $behind | origin/main $(git rev-parse --short origin/main 2>/dev/null || echo none)"
if [ "$dirty" != 0 ]; then
  say WARN "dirty tree ($dirty paths). If not yours: stop, report, use a clean clone (routing skill)"
fi
if git rev-parse --verify -q main >/dev/null; then
  set -- $(git rev-list --left-right --count main...origin/main 2>/dev/null || echo '0 0')
  if [ "${1:-0}" != 0 ]; then
    say FAIL "local main has $1 commit(s) not on origin/main (diverged). Reconcile before work"
  elif [ "${2:-0}" != 0 ]; then
    say WARN "local main is $2 behind origin/main (git fetch origin main:main, or pull --ff-only)"
  else
    say OK "local main equals origin/main"
  fi
fi

echo "== open PRs (REST)"
nums=$(gh api "repos/$W_REPO/pulls?state=open&per_page=50" --jq '.[].number' 2>/dev/null)
if [ -z "$nums" ]; then
  say NOTE "no open PRs (or gh is not authenticated: run gh auth status)"
fi
for n in $nums; do
  row=$(gh api "repos/$W_REPO/pulls/$n" --jq \
    '[.draft,.head.sha,(.mergeable_state // "unknown"),.updated_at,.title[0:56]]|@tsv' 2>/dev/null)
  draft=$(printf '%s' "$row" | cut -f1); head=$(printf '%s' "$row" | cut -f2)
  mstate=$(printf '%s' "$row" | cut -f3); upd=$(printf '%s' "$row" | cut -f4)
  title=$(printf '%s' "$row" | cut -f5)
  checks=$(gh api "repos/$W_REPO/commits/$head/check-runs" --jq \
    '[.check_runs[]|(.conclusion // .status)]|group_by(.)|map("\(.[0]):\(length)")|join(",")' 2>/dev/null)
  verdict=$(w_verdict "$n" "$head")
  printf '#%s %s head %s | %s | draft=%s | checks %s | codex %s | upd %sZ\n' \
    "$n" "$title" "${head:0:7}" "$mstate" "$draft" "${checks:-none}" "$verdict" "${upd:11:5}"
done

echo "== board #$ISSUE (comment $BOARD)"
bts=$(gh api "repos/$W_REPO/issues/comments/$BOARD" --jq .updated_at 2>/dev/null)
if [ -n "$bts" ]; then
  age=$(w_age_min "$bts")
  echo "board edited ${age:-?} min ago ($(w_cdt "$bts") CDT)"
  if [ -n "$nums" ] && [ -n "${age:-}" ] && [ "$age" -gt 40 ]; then
    say WARN "board older than 40 min while PRs are open: refresh it"
  fi
  echo "-- #$ISSUE comments after the board edit (newest last)"
  gh api "repos/$W_REPO/issues/$ISSUE/comments?since=$bts&per_page=100" --jq \
    "map(select(.id!=$BOARD))|.[]|\"  \\(.id) \\(.created_at[11:16])Z \\(.body[0:110]|gsub(\"\\n\";\" \"))\"" 2>/dev/null | tail -8
  # Lane silence: newest #244 comment per lane in the last 24 h (lane read from the speaker header; heuristic).
  since24=$(date -u -d '24 hours ago' '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null)
  lanes=$(gh api "repos/$W_REPO/issues/$ISSUE/comments?since=$since24&per_page=100" --paginate --jq \
    '.[]|[.created_at,(.body[0:60]|if test("^COWORK") then "COWORK" elif test("^CODEX|^Codex|Got it, Codex|^CLEAN|^HOLD|^DONE review") then "CODEX" elif test("^CODE( |:|$)") then "CODE" else "OTHER" end)]|@tsv' 2>/dev/null \
    | sort | awk -F'\t' '$2!="OTHER"{last[$2]=$1} END{for(l in last) print l, last[l]}')
  line=""
  for l in COWORK CODE CODEX; do
    t=$(printf '%s\n' "$lanes" | awk -v l="$l" '$1==l{print $2}')
    if [ -n "$t" ]; then line="$line$l $(w_cdt "$t") CDT ($(w_age_min "$t")m ago) | "; else line="$line$l none in 24h | "; fi
  done
  echo "lanes, newest #$ISSUE comment: ${line% | }"
else
  say WARN "could not read the board comment (id, auth or network)"
fi

echo "== deploys"
gh api "repos/$W_REPO/actions/runs?per_page=20" --jq \
  '.workflow_runs[]|select(.status!="completed")|"RUNNING \(.name) \(.status) \(.head_sha[0:7])"' 2>/dev/null
gh api "repos/$W_REPO/actions/runs?branch=main&per_page=4" --jq \
  '.workflow_runs[]|"main \(.name) \(.conclusion // .status) \(.head_sha[0:7]) \(.created_at[11:16])Z"' 2>/dev/null

[ "$w_fails" -eq 0 ]
