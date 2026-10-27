#!/usr/bin/env bash
# Wingman merge-gate: read-only. Decides PASS or HOLD for merging ONE PR and prints the
# pinned merge command. It never merges, comments or pushes. Run it again right before
# merging: anything that landed in between changes the answer.
# Usage: merge-gate.sh <pr-number>
# Env: WINGMAN_REPO, WINGMAN_ISSUE (244), WINGMAN_BOARD_COMMENT (6449313109)
# Needs: gh, git >= 2.38 (merge-tree --write-tree) inside a clone with network access.
# Exit 0 = PASS, 1 = HOLD, 2 = cannot run.
set -u
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
. "$here/lib.sh"
PR="${1:-}"
case "$PR" in ''|*[!0-9]*) echo "usage: merge-gate.sh <pr-number>" >&2; exit 2 ;; esac
BOARD="${WINGMAN_BOARD_COMMENT:-6449313109}"
ISSUE="${WINGMAN_ISSUE:-244}"

w_need gh
w_need git
root=$(git rev-parse --show-toplevel 2>/dev/null) || { say FAIL "not inside a git clone"; exit 2; }
cd "$root" || exit 2
trap 'git for-each-ref --format="%(refname)" refs/wingman 2>/dev/null | while read -r r; do git update-ref -d "$r"; done' EXIT

echo "== PR #$PR"
row=$(gh api "repos/$W_REPO/pulls/$PR" --jq \
  '[.state,.draft,.base.ref,.head.sha,(.mergeable_state // "unknown"),.title[0:70],.user.login]|@tsv' 2>/dev/null) \
  || { say FAIL "cannot read PR #$PR (number, auth or network)"; exit 2; }
state=$(printf '%s' "$row" | cut -f1); draft=$(printf '%s' "$row" | cut -f2)
base=$(printf '%s' "$row" | cut -f3); head=$(printf '%s' "$row" | cut -f4)
mstate=$(printf '%s' "$row" | cut -f5); title=$(printf '%s' "$row" | cut -f6)
echo "$title | head ${head:0:7} | base $base | mergeable_state $mstate"

[ "$state" = open ] || say HOLD "PR is $state"
[ "$draft" = false ] || say HOLD "draft (REST cannot undraft: ask the owner to mark ready)"
[ "$base" = main ] || say HOLD "base is $base, not main"
case "$mstate" in
  clean) say OK "GitHub reports clean" ;;
  unknown) say HOLD "mergeable_state still computing: wait 10 s and re-run (not a pass)" ;;
  dirty) say HOLD "merge conflicts: owner merges origin/main in (peer-collaboration skill section 1)" ;;
  *) say HOLD "mergeable_state is $mstate" ;;
esac

echo "== checks and verdict"
chk=$(gh api "repos/$W_REPO/commits/$head/check-runs" --jq \
  '[.check_runs[]|(.conclusion // .status)]|group_by(.)|map("\(.[0]):\(length)")|join(",")' 2>/dev/null)
case "$chk" in
  '') say NOTE "no checks reported. Rely on the Codex verdict and local test evidence; absence is not a pass" ;;
  *failure*|*cancelled*|*timed_out*|*action_required*) say HOLD "failing checks: $chk" ;;
  *in_progress*|*queued*|*pending*) say HOLD "checks still running: $chk" ;;
  *) say OK "checks: $chk" ;;
esac
set -- $(w_verdict "$PR" "$head")
case "$1:$3" in
  CLEAN:match) say OK "Codex CLEAN on this exact head ($2)" ;;
  CLEAN:older) say HOLD "Codex CLEAN was for an older head ($2); head moved: ask for a re-verdict" ;;
  HOLD:*) say HOLD "latest verdict is HOLD ($2): read it, owner fixes, Codex re-verifies" ;;
  none:*) say HOLD "no Codex verdict yet (agent-authored PRs need CLEAN on the head)" ;;
  *) say HOLD "verdict present but not bound to a head sha: ask Codex to restate it with the sha" ;;
esac

echo "== named conditions and order"
conds=$( { gh api "repos/$W_REPO/pulls/$PR" --jq '.body // ""' 2>/dev/null
           gh api "repos/$W_REPO/issues/$PR/comments?per_page=100" --paginate --jq '.[].body' 2>/dev/null; } \
         | grep -inE 'do not merge|nate-only|needs nate|named condition|hold until' | head -3 | cut -c1-140)
if [ -n "$conds" ]; then say WARN "possible named condition (read before merging):"; echo "$conds"; else say OK "no 'do not merge' or named-condition wording found"; fi
boardbody=$(gh api "repos/$W_REPO/issues/comments/$BOARD" --jq .body 2>/dev/null)
queue=$(printf '%s\n' "$boardbody" | awk -F'|' \
  '/^\| *[0-9]+ *\| *#[0-9]+/{o=$2+0; if (match($3,/#[0-9]+/)) print o, substr($3,RSTART+1,RLENGTH-1)}')
myorder=$(printf '%s\n' "$queue" | awk -v p="$PR" '$2==p{print $1; exit}')
openlist=$(gh api "repos/$W_REPO/pulls?state=open&per_page=50" --jq '.[].number' 2>/dev/null)
if [ -n "$myorder" ]; then
  ahead=""
  while read -r o p; do
    [ -z "$o" ] && continue
    if [ "$o" -lt "$myorder" ] && printf '%s\n' "$openlist" | grep -qx "$p"; then ahead="$ahead #$p"; fi
  done <<EOF
$queue
EOF
  if [ -n "$ahead" ]; then
    say HOLD "behind$ahead in the board's agreed merge order (a newer or later PR never jumps the queue)"
  else
    say OK "next in the board's merge queue (position $myorder)"
  fi
else
  say NOTE "#$PR is not in the board's merge queue: confirm order from a merge-order note or the iteration plan"
fi

echo "== deploy exposure"
files=$(gh api "repos/$W_REPO/pulls/$PR/files?per_page=100" --paginate --jq '.[].filename' 2>/dev/null)
dep=0; total=0
while IFS= read -r f; do
  [ -z "$f" ] && continue
  total=$((total + 1)); w_is_deploy_path "$f" && dep=$((dep + 1))
done <<EOF
$files
EOF
# Only runs that can be a deploy count: on main, or a Deploy/rotate workflow. A pull-request check on someone else's branch is not a deploy (it caused a false HOLD on 2026-10-27).
runs=$(gh api "repos/$W_REPO/actions/runs?per_page=30" --jq '[.workflow_runs[]|select(.status!="completed" and (.head_branch=="main" or (.name|test("^(Deploy|Rotate)";"i"))))]|length' 2>/dev/null)
if [ "$dep" -gt 0 ]; then
  say NOTE "$dep of $total files are deploy-watched: merge triggers a deploy. The permission classifier asks for Nate's explicit in-session go ([Production Deploy]); Code watches the health gate"
  [ "${runs:-0}" -gt 0 ] && say HOLD "$runs deploy run(s) still in progress on main: wait for the previous deploy to finish"
else
  say OK "docs or skills only ($total files): no deploy"
fi

echo "== overlap with main and with every other open PR"
git fetch origin main --quiet 2>/dev/null || say WARN "fetch of main failed; overlap result may be stale"
git fetch origin "pull/$PR/head:refs/wingman/pr$PR" --quiet --force 2>/dev/null \
  || { say FAIL "cannot fetch pull/$PR/head"; exit 2; }
if ! out=$(git merge-tree --write-tree --name-only origin/main "refs/wingman/pr$PR" 2>&1); then
  say HOLD "conflicts against main: $(printf '%s\n' "$out" | sed -n '2,6p' | tr '\n' ' ')"
else
  tree=$(printf '%s\n' "$out" | head -1)
  sim=$(GIT_AUTHOR_NAME=wingman GIT_AUTHOR_EMAIL=wingman@localhost GIT_COMMITTER_NAME=wingman \
        GIT_COMMITTER_EMAIL=wingman@localhost git commit-tree "$tree" -p origin/main -p "refs/wingman/pr$PR" -m sim)
  say OK "clean against main"
  for m in $(gh api "repos/$W_REPO/pulls?state=open&per_page=50" --jq '.[].number' 2>/dev/null); do
    [ "$m" = "$PR" ] && continue
    git fetch origin "pull/$m/head:refs/wingman/pr$m" --quiet --force 2>/dev/null || { say WARN "cannot fetch #$m"; continue; }
    if git merge-tree --write-tree --name-only "refs/wingman/pr$m" origin/main >/dev/null 2>&1; then before=clean; else before=conflicting; fi
    if git merge-tree --write-tree --name-only "refs/wingman/pr$m" "$sim" >/dev/null 2>&1; then after=clean; else after=conflicting; fi
    if [ "$before" = clean ] && [ "$after" = conflicting ]; then
      say WARN "#$m would NEWLY conflict after this merge: say so on #$m or merge theirs first (a peer's PR is not yours to repair)"
    else
      say OK "#$m unaffected ($before before, $after after)"
    fi
  done
fi

echo "== result"
if [ "$w_fails" -gt 0 ]; then echo "GATE ERROR #$PR"; exit 2; fi
if [ "$w_holds" -gt 0 ]; then echo "GATE HOLD #$PR ($w_holds reason(s) above). Do not merge."; exit 1; fi
echo "GATE PASS #$PR head ${head:0:7}. Mechanical checks only. You still confirm: the PR is yours or assigned to you, next in the agreed order, no named condition, Nate's go if it deploys."
echo "Merge (one PR at a time, pinned to the head you just checked):"
echo "  gh api -X PUT repos/$W_REPO/pulls/$PR/merge -f sha=$head -f merge_method=merge"
echo "Then: comment MERGED with ids only, git fetch, re-run this gate for the next PR."
exit 0
