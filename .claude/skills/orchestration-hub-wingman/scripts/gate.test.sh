#!/usr/bin/env bash
# Offline test for the paged reads in lib.sh (w_pages, w_verdict), merge-gate.sh and ctx-audit.sh.
# A fake gh serves canned REST lists page by page. Like the cloud agent proxy it refuses --paginate once a
# list has a second page (the Link header would name a repositories/{id}/... URL), which is why the scripts
# must ask for ?page=N themselves. The real jq stands in for gh's built-in filter. A throwaway bare repo
# stands in for origin so merge-gate.sh can fetch pull/<n>/head. No network.
# Run: bash gate.test.sh   Exit 1 on any failure; prints SKIP and exits 0 when jq is missing.
set -u
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if ! command -v jq >/dev/null 2>&1; then echo "SKIP: gate tests need jq to stand in for gh's built-in filter"; exit 0; fi
t=$(mktemp -d); trap 'rm -rf "$t"' EXIT
pass=0; fail=0
ok() { if [ "$1" -eq 0 ]; then pass=$((pass + 1)); else fail=$((fail + 1)); echo "FAIL: $2"; fi; }

mkdir -p "$t/bin"
cat > "$t/bin/gh" <<'FAKE'
#!/usr/bin/env bash
# Fake gh: serves canned lists from $FAKE_DIR for repo test/repo. Understands only the calls the scripts make.
[ "${1:-}" = api ] || { echo "fake gh: unsupported: $*" >&2; exit 2; }
orig=$2; path=$2; shift 2
prog=.; pag=0
while [ $# -gt 0 ]; do case "$1" in --jq) prog=$2; shift 2 ;; --paginate) pag=1; shift ;; *) shift ;; esac; done
echo "$orig" >> "$FAKE_DIR/calls"
q=""; case "$path" in *\?*) q=${path#*\?}; path=${path%%\?*} ;; esac
page=1; per=100
IFS='&' read -ra kvs <<< "$q"
for kv in "${kvs[@]}"; do case "$kv" in page=*) page=${kv#page=} ;; per_page=*) per=${kv#per_page=} ;; esac; done
# serve <canned list> <failure key>: one page of a list, or the proxy's refusal after page 1 under --paginate
serve() {
  local f=$1 key=$2
  [ -f "$FAKE_DIR/fail_${key}_$page" ] && { echo "fake gh: injected failure ($key page $page)" >&2; exit 1; }
  if [ "$pag" = 1 ] && [ "$(jq length "$f")" -gt "$per" ]; then
    jq -c ".[0:$per]" "$f" | jq -r "$prog"
    echo 'gh: Numeric-ID repository paths (repositories/{id}/...) are not supported through this proxy. (HTTP 403)' >&2
    exit 1
  fi
  jq -c ".[$(( (page - 1) * per )):$(( page * per ))]" "$f" | jq -r "$prog"
}
case "$path" in
  repos/test/repo/issues/comments/*) jq -r "$prog" "$FAKE_DIR/board.json" ;;
  repos/test/repo/issues/*/comments) n=${path#repos/test/repo/issues/}; n=${n%%/*}; serve "$FAKE_DIR/comments_$n.json" "comments_$n" ;;
  repos/test/repo/pulls/*/files) n=${path#repos/test/repo/pulls/}; n=${n%%/*}; serve "$FAKE_DIR/files_$n.json" "files_$n" ;;
  repos/test/repo/pulls/*) n=${path##*/}; jq -r "$prog" "$FAKE_DIR/pull_$n.json" ;;
  repos/test/repo/pulls) jq -r "$prog" "$FAKE_DIR/pulls_open.json" ;;
  repos/test/repo/commits/*/check-runs) echo '{"check_runs":[]}' | jq -r "$prog" ;;
  repos/test/repo/actions/runs) echo '{"workflow_runs":[]}' | jq -r "$prog" ;;
  *) echo "fake gh: unsupported path $path" >&2; exit 2 ;;
esac
FAKE
chmod +x "$t/bin/gh"

HEAD40=1234567890abcdef1234567890abcdef12345678
export PATH="$t/bin:$PATH" FAKE_DIR="$t/fake" WINGMAN_REPO=test/repo WINGMAN_ISSUE=244 WINGMAN_BOARD_COMMENT=900 WINGMAN_NO_FETCH=1

# gen <file> <count> <jq object, . is the index>: a canned list of <count> objects
gen() { jq -n --argjson c "$2" "[range(\$c) | $3]" > "$1"; }
# patch <file> <jq filter>: edit a canned list in place
patch() { jq "$2" "$1" > "$t/tmp.json" && mv "$t/tmp.json" "$1"; }
# newcase: PR 299 open and clean with a short comment list, a short file list and a board that queues it
newcase() {
  rm -rf "$t/fake"; mkdir -p "$t/fake"; local f="$t/fake"
  jq -n --arg h "$HEAD40" '{state:"open",draft:false,base:{ref:"main"},head:{sha:$h},mergeable_state:"clean",title:"test pr",user:{login:"x"},body:"plain body",updated_at:"2026-10-28T15:00:00Z"}' > "$f/pull_299.json"
  echo '[{"number":299}]' > "$f/pulls_open.json"
  jq -n '{body:"| 1 | #299 test | abc | none | none | open |\n",updated_at:"2026-10-28T15:00:00Z"}' > "$f/board.json"
  gen "$f/comments_299.json" 5 '{id:(100+.),created_at:"2026-10-27T00:00:00Z",body:"comment \(.)"}'
  gen "$f/comments_244.json" 3 '{id:(200+.),created_at:"2026-10-27T00:00:00Z",body:"note \(.)"}'
  gen "$f/files_299.json" 3 '{filename:"docs/f\(.).md"}'
}

# ---- w_pages ---------------------------------------------------------------------------------------------
. "$here/lib.sh"
newcase
gen "$t/fake/comments_244.json" 250 '{id:(1000+.),created_at:"2026-10-27T00:00:00Z",body:"c\(.)"}'
out=$(w_pages "repos/test/repo/issues/244/comments" '.id'); rc=$?
[ "$rc" -eq 0 ] && [ "$(printf '%s\n' "$out" | wc -l | tr -d ' ')" -eq 250 ] && [ "$(printf '%s\n' "$out" | head -1)" = 1000 ] && [ "$(printf '%s\n' "$out" | tail -1)" = 1249 ]
ok $? "w_pages: 250 items over three pages come back whole and in order"

gen "$t/fake/comments_244.json" 100 '{id:(1000+.),created_at:"2026-10-27T00:00:00Z",body:"c\(.)"}'
out=$(w_pages "repos/test/repo/issues/244/comments" '.id'); rc=$?
[ "$rc" -eq 0 ] && [ "$(printf '%s\n' "$out" | wc -l | tr -d ' ')" -eq 100 ]; ok $? "w_pages: exactly 100 items (a full page, then an empty one) come back whole"

echo '[]' > "$t/fake/comments_244.json"
out=$(w_pages "repos/test/repo/issues/244/comments" '.id'); rc=$?
[ "$rc" -eq 0 ] && [ -z "$out" ]; ok $? "w_pages: an empty list is success with no output"

gen "$t/fake/comments_244.json" 250 '{id:(1000+.),created_at:"2026-10-27T00:00:00Z",body:"c\(.)"}'; echo 1 > "$t/fake/fail_comments_244_2"
out=$(w_pages "repos/test/repo/issues/244/comments" '.id'); rc=$?
[ "$rc" -eq 1 ] && [ -z "$out" ]; ok $? "w_pages: a failing second page returns 1 and prints nothing (all or nothing, never page 1 alone)"
rm -f "$t/fake/fail_comments_244_2"

out=$(w_pages "repos/test/repo/issues/comments/900" '.id'); rc=$?
[ "$rc" -eq 1 ] && [ -z "$out" ]; ok $? "w_pages: an answer that is not a list is a failure"

out=$(W_PAGES_MAX=2 w_pages "repos/test/repo/issues/244/comments" '.id'); rc=$?
[ "$rc" -eq 1 ] && [ -z "$out" ]; ok $? "w_pages: a list longer than W_PAGES_MAX pages is refused, not truncated"

: > "$t/fake/calls"
w_pages "repos/test/repo/issues/244/comments?since=2026-10-28T10:00:00Z" '.id' >/dev/null
grep -qx 'repos/test/repo/issues/244/comments?since=2026-10-28T10:00:00Z&per_page=100&page=1' "$t/fake/calls"; ok $? "w_pages: a path that already has a query gets &per_page and &page, not a second ?"

# ---- w_verdict -------------------------------------------------------------------------------------------
newcase; gen "$t/fake/comments_299.json" 130 '{id:(100+.),created_at:"2026-10-27T00:00:00Z",body:"comment \(.)"}'
patch "$t/fake/comments_299.json" ".[119].body = \"CLEAN $HEAD40 - reviewed\""
[ "$(w_verdict 299 $HEAD40)" = "CLEAN 1234567 match" ]; ok $? "w_verdict: a CLEAN on page 2 binds to the exact head"

patch "$t/fake/comments_299.json" ".[4].body = \"CLEAN $HEAD40 - first\" | .[125].body = \"HOLD $HEAD40 - changed my mind\""
[ "$(w_verdict 299 $HEAD40)" = "HOLD 1234567 match" ]; ok $? "w_verdict: a later HOLD on page 2 beats an earlier CLEAN on page 1"

newcase; gen "$t/fake/comments_299.json" 130 '{id:(100+.),created_at:"2026-10-27T00:00:00Z",body:"comment \(.)"}'
patch "$t/fake/comments_299.json" ".[125].body = \"CODEX 10:00 CDT: CLEAN $HEAD40\""
[ "$(w_verdict 299 $HEAD40)" = "CLEAN 1234567 match" ]; ok $? "w_verdict: a verdict on page 2 behind a CODEX header is read"

patch "$t/fake/comments_299.json" ".[4].body = \"CLEAN $HEAD40 - on page 1\""
echo 1 > "$t/fake/fail_comments_299_2"
[ "$(w_verdict 299 $HEAD40)" = "error - -" ]; ok $? "w_verdict: an unreadable page is 'error', never 'none' and never the stale CLEAN from page 1"
rm -f "$t/fake/fail_comments_299_2"

newcase
[ "$(w_verdict 299 $HEAD40)" = "none - -" ]; ok $? "w_verdict: no verdict comment is 'none'"

# ---- merge-gate.sh in a throwaway clone ------------------------------------------------------------------
git init -q --bare "$t/origin.git"
git clone -q "$t/origin.git" "$t/clone" 2>/dev/null
( cd "$t/clone" && git checkout -q -b main && echo base > a.txt && git add a.txt \
  && git -c user.name=t -c user.email=t@t commit -q -m base && git push -q origin main \
  && git checkout -q -b pr299 && echo change > b.txt && git add b.txt \
  && git -c user.name=t -c user.email=t@t commit -q -m change && git push -q origin HEAD:refs/pull/299/head \
  && git checkout -q main ) >/dev/null 2>&1
gate() { ( cd "$t/clone" && bash "$here/merge-gate.sh" 299 > "$t/out" 2>&1 ); echo $?; }

# gatecase <index of the CLEAN comment> <number of files> <index of a deploy-watched file, or none>:
# PR 299 with 130 comments (pages 1 and 2) and a file list of the given length
gatecase() {
  newcase
  gen "$t/fake/comments_299.json" 130 '{id:(100+.),created_at:"2026-10-27T00:00:00Z",body:"comment \(.)"}'
  patch "$t/fake/comments_299.json" ".[$1].body = \"CLEAN $HEAD40 - reviewed\""
  gen "$t/fake/files_299.json" "$2" '{filename:"docs/f\(.).md"}'
  if [ "$3" != none ]; then patch "$t/fake/files_299.json" ".[$3].filename = \"family-hub/page.html\""; fi
}

# G1: the CLEAN is on page 2 of the comments and a deploy-watched file is on page 2 of the files
gatecase 119 120 109
rc=$(gate)
[ "$rc" -eq 0 ]; ok $? "gate: PASS when the verdict and the deploy-watched file are both on page 2 (exit $rc)"
grep -q 'Codex CLEAN on this exact head' "$t/out"; ok $? "gate: reads the CLEAN from page 2"
grep -q '1 of 120 files are deploy-watched' "$t/out"; ok $? "gate: finds the deploy-watched file past the first 100"

# G1b: the safety case on its own. The CLEAN is on page 1, so only the file list decides: a deploy-watched file
# on page 2 must be seen, never reported as 'docs or skills only' (the false "no deploy" the old --paginate gave)
gatecase 4 120 109
rc=$(gate)
grep -q '1 of 120 files are deploy-watched' "$t/out" && ! grep -q 'docs or skills only' "$t/out"; ok $? "gate: a deploy-watched file past page 1 is never reported as 'docs or skills only'"

# G2: control with the CLEAN on page 1 and no deploy-watched file: every file is counted
gatecase 4 120 none
rc=$(gate)
[ "$rc" -eq 0 ] && grep -q 'docs or skills only (120 files)' "$t/out"; ok $? "gate: PASS and counts all 120 files when none is deploy-watched (exit $rc)"

# G3: the CLEAN is readable (page 1) but page 2 of the file list fails: HOLD, and no claim of 'no deploy'
gatecase 4 120 none; echo 1 > "$t/fake/fail_files_299_2"
rc=$(gate)
[ "$rc" -eq 1 ]; ok $? "gate: HOLD (exit 1) when the file list cannot be read in full (exit $rc)"
grep -q 'could not read the whole file list of #299' "$t/out"; ok $? "gate: says the file list is incomplete"
! grep -q 'docs or skills only' "$t/out"; ok $? "gate: does not claim 'no deploy' from a partial list"

# G4: the CLEAN is on page 1 but page 2 of the PR comments fails: HOLD on the verdict and on the named-condition scan
gatecase 4 120 none; echo 1 > "$t/fake/fail_comments_299_2"
rc=$(gate)
[ "$rc" -eq 1 ]; ok $? "gate: HOLD (exit 1) when the comments cannot be read in full, even with a CLEAN on page 1 (exit $rc)"
grep -q 'could not read every comment on #299, so the Codex verdict is unknown' "$t/out"; ok $? "gate: the verdict is unknown, not 'none'"
grep -q 'named-condition scan is incomplete' "$t/out"; ok $? "gate: the named-condition scan is reported incomplete"
! grep -q 'no .do not merge. or named-condition wording found' "$t/out"; ok $? "gate: does not report 'no named condition' from a partial read"

# G5: a named condition on page 2 of the comments is found (the gate still passes: it is a WARN to read, not a HOLD)
gatecase 4 120 none
patch "$t/fake/comments_299.json" '.[125].body = "Do not merge until Nate answers"'
rc=$(gate)
grep -q 'possible named condition' "$t/out"; ok $? "gate: finds 'do not merge' wording on page 2 of the comments"

# ---- ctx-audit.sh lane line ------------------------------------------------------------------------------
( cd "$t/clone" && git checkout -q -b audit ) >/dev/null 2>&1
audit() { ( cd "$t/clone" && bash "$here/ctx-audit.sh" > "$t/out" 2>&1 ); echo $?; }
newcase
gen "$t/fake/comments_244.json" 211 '{id:(1000+.),created_at:"2026-10-27T00:00:00Z",body:"other \(.)"}'
patch "$t/fake/comments_244.json" '.[100].created_at = "2026-10-28T09:06:00Z" | .[100].body = "CODE 04:06 CDT: host note"
  | .[150].created_at = "2026-10-28T14:54:00Z" | .[150].body = "DONE #307 review https://example.test/x - CLEAN abc"
  | .[170].created_at = "2026-10-28T14:52:00Z" | .[170].body = "Got it, ETA 10:15 CDT for #307 exact-head review and checks"
  | .[210].created_at = "2026-10-28T15:00:00Z" | .[210].body = "COWORK 10:00 CDT: newest"'
audit >/dev/null
grep -qE '^lanes, newest #244 comment: COWORK 10:00 CDT \(-?[0-9]+m ago\) \| CODE 04:06 CDT \(-?[0-9]+m ago\) \| CODEX 09:54 CDT' "$t/out"; ok $? "ctx-audit: lane times come from all three pages, and Codex's 'DONE #n review' and 'Got it, ETA ... review' headers count"
echo 1 > "$t/fake/fail_comments_244_2"
audit >/dev/null
grep -q 'lane times are unknown' "$t/out" && ! grep -q '^lanes, newest' "$t/out"; ok $? "ctx-audit: an unreadable page gives a warning, not a stale lane line"
rm -f "$t/fake/fail_comments_244_2"

# ---- no script may go back to --paginate -----------------------------------------------------------------
bad=$(grep -nE '^[^#]*--paginate' "$here"/lib.sh "$here"/merge-gate.sh "$here"/ctx-audit.sh "$here"/watch.sh "$here"/fed-check.sh "$here"/fed-regen.sh 2>/dev/null)
[ -z "$bad" ]; ok $? "no skill script uses gh --paginate (the cloud proxy stops it after page 1): $bad"

echo "gate tests: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
