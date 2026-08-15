#!/usr/bin/env bash
# Reviewable fixture for deploy-production-stack.yml's dirty-worktree
# preflight (2026-08-15). Exercises the exact check the workflow runs
# against a real temporary git repo, in isolation from GitHub Actions and
# from any live host state -- no network, no Docker, nothing touched
# outside a throwaway directory under $TMPDIR.
#
# Run directly: bash .github/workflows/scripts/test-production-stack-dirty-worktree-guard.sh
set -Eeuo pipefail

# Mirrors deploy-production-stack.yml's own preflight exactly -- kept as a
# literal copy rather than sourced from the workflow file, since the
# workflow's `run:` block isn't independently invocable outside Actions.
# If that block's guard logic changes, update this copy in the same PR.
preflight() {
  local dirty
  dirty=$(git status --porcelain --untracked-files=all)
  if [ -n "$dirty" ]; then
    echo "::error::Deploy clone has uncommitted changes -- refusing to fetch/reset/deploy."
    return 1
  fi
  return 0
}

WORKDIR=$(mktemp -d)
trap 'rm -rf "$WORKDIR"' EXIT

cd "$WORKDIR"
git init --quiet
git config user.email "test@example.invalid"
git config user.name "Test"
echo "seed" > seed.txt
git add seed.txt
git commit --quiet -m "seed"

FAILURES=0

echo "no output" > tracked-modified.txt
git add tracked-modified.txt
git commit --quiet -m "add tracked-modified.txt"
echo "changed" > tracked-modified.txt   # uncommitted tracked-file edit

echo "-- test 1: dirty worktree (uncommitted tracked-file edit) must fail --"
if preflight; then
  echo "FAIL: preflight passed on a dirty worktree (tracked-file edit)"
  FAILURES=$((FAILURES + 1))
else
  echo "PASS: preflight correctly refused a dirty worktree (tracked-file edit)"
fi

git checkout -- tracked-modified.txt   # restore clean state

echo "untracked" > untracked-file.txt  # untracked file, no add/commit
echo "-- test 2: dirty worktree (untracked file) must fail --"
if preflight; then
  echo "FAIL: preflight passed on a dirty worktree (untracked file)"
  FAILURES=$((FAILURES + 1))
else
  echo "PASS: preflight correctly refused a dirty worktree (untracked file)"
fi

rm -f untracked-file.txt

echo "-- test 3: clean worktree must pass --"
if preflight; then
  echo "PASS: preflight correctly allowed a clean worktree"
else
  echo "FAIL: preflight refused a clean worktree"
  FAILURES=$((FAILURES + 1))
fi

if [ "$FAILURES" -ne 0 ]; then
  echo "$FAILURES check(s) failed"
  exit 1
fi
echo "All dirty-worktree guard checks passed"
