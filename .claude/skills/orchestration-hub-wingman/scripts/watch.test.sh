#!/usr/bin/env bash
# Offline test for watch.sh: a fake gh serves canned data (and can fail, return garbage or hang on demand),
# and the real jq stands in for gh's built-in filter, so the jq program inside watch.sh is exercised too.
# No network. Run: bash watch.test.sh   Exit 1 on any failure; prints SKIP and exits 0 when jq is missing.
set -u
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if ! command -v jq >/dev/null 2>&1; then echo "SKIP: watch tests need jq to stand in for gh's built-in filter"; exit 0; fi
t=$(mktemp -d); trap 'rm -rf "$t"' EXIT
pass=0; fail=0
ok() { if [ "$1" -eq 0 ]; then pass=$((pass + 1)); else fail=$((fail + 1)); echo "FAIL: $2"; fi; }

mkdir -p "$t/bin"
cat > "$t/bin/gh" <<'FAKE'
#!/usr/bin/env bash
# Fake gh: serves canned data from $FAKE_DIR. Understands only the calls watch.sh makes.
[ "${1:-}" = api ] || { echo "fake gh: unsupported: $*" >&2; exit 2; }
path=$2; shift 2
prog=.
while [ $# -gt 0 ]; do case "$1" in --jq) prog=$2; shift 2 ;; *) shift ;; esac; done
bump() { local f="$FAKE_DIR/n_$1" c=0; [ -f "$f" ] && c=$(cat "$f"); c=$((c + 1)); echo "$c" > "$f"; echo "$c"; }
# on <kind> <key> <call>: true when $FAKE_DIR/<kind>_<key> lists that call number
on() { [ -f "$FAKE_DIR/$1_$2" ] && grep -qx "$3" "$FAKE_DIR/$1_$2"; }
case "$path" in
  */issues/*/comments*)
    n=${path#*/issues/}; n=${n%%/*}
    since=""; page=1
    IFS='&' read -ra kvs <<< "${path#*\?}"
    for kv in "${kvs[@]}"; do case "$kv" in since=*) since=${kv#since=} ;; page=*) page=${kv#page=} ;; esac; done
    c=$(bump "c${n}p${page}")
    on fail "${n}_$page" "$c" && { echo "fake gh: injected failure" >&2; exit 1; }
    on hang "${n}_$page" "$c" && exec sleep 20
    on garbage "${n}_$page" "$c" && { echo "<html>proxy error</html>"; exit 0; }
    [ -f "$FAKE_DIR/comments_$n.json" ] || echo '[]' > "$FAKE_DIR/comments_$n.json"
    jq -c --arg since "$since" --argjson page "$page" --argjson ign "${FAKE_IGNORE_SINCE:-false}" \
      '[.[] | select($ign or .updated_at >= $since)] | .[($page - 1) * 100 : $page * 100]' "$FAKE_DIR/comments_$n.json" | jq -r "$prog"
    ;;
  */pulls/*)
    n=${path##*/}; c=$(bump "pull$n")
    on fail "pull$n" "$c" && { echo "fake gh: injected failure" >&2; exit 1; }
    f="$FAKE_DIR/pull_$n.$c.json"; [ -f "$f" ] || f="$FAKE_DIR/pull_$n.json"
    jq -r "$prog" "$f" ;;
  */commits/main*)
    c=$(bump main)
    on fail main "$c" && { echo "fake gh: injected failure" >&2; exit 1; }
    f="$FAKE_DIR/main.$c.json"; [ -f "$f" ] || f="$FAKE_DIR/main.json"
    jq -r "$prog" "$f" ;;
  *) echo "fake gh: unsupported path $path" >&2; exit 2 ;;
esac
FAKE
chmod +x "$t/bin/gh"

# newcase: a fresh fake backend with PR 299 open at one head and main at one sha.
newcase() {
  rm -rf "$t/fake"; mkdir -p "$t/fake"
  echo '{"head":{"sha":"1111111aaaa"},"state":"open","merged":false}' > "$t/fake/pull_299.json"
  echo '{"sha":"aaaaaaa0000"}' > "$t/fake/main.json"
}
# gen <n> <count> <first-id>: <count> comments on #n, all created and updated 2020-01-02
gen() {
  jq -n --argjson c "$2" --argjson f "$3" \
    '[range($c) | {id: ($f + .), created_at: "2020-01-02T00:00:00Z", updated_at: "2020-01-02T00:00:00Z", body: "comment \(.)"}]' > "$t/fake/comments_$1.json"
}
# patch <n> <jq filter>: edit the canned comments of #n
patch() { jq "$2" "$t/fake/comments_$1.json" > "$t/fake/tmp" && mv "$t/fake/tmp" "$t/fake/comments_$1.json"; }
# runw <cycles>: run watch.sh on issue 244 and PR 299 against the fake; stdout in $t/out, stderr in $t/err
runw() {
  local cycles=$1
  PATH="$t/bin:$PATH" FAKE_DIR="$t/fake" WATCH_INTERVAL=0 WATCH_MAX_CYCLES="$cycles" WATCH_SINCE=2020-01-01T00:00:00Z \
    WATCH_BOARD_ID=900 WINGMAN_REPO=test/repo timeout 120 bash "$here/watch.sh" 244 299 > "$t/out" 2> "$t/err"
}
count() { grep -c -- "$1" "$t/out"; }
lineno() { grep -n -m1 -- "$1" "$t/out" | cut -d: -f1; }

# 1. a comment posted while the fetch fails is printed once after recovery (the cursor must not move on failure)
newcase; gen 244 1 1001; echo 1 > "$t/fake/fail_244_1"
runw 3; ok $? "run 1 exits 0"
[ "$(count '^#244 comment 1001 ')" -eq 1 ]; ok $? "failed fetch, then recovery: the early comment is printed exactly once"
[ "$(count '^WATCH FAILING the comments of #244')" -eq 1 ]; ok $? "one WATCH FAILING line"
[ "$(count '^WATCH RECOVERED the comments of #244 after 1 failed tries')" -eq 1 ]; ok $? "one WATCH RECOVERED line"
grep -q '^armed 2020-01-01T00:00:00Z issue=244 prs=299 main=aaaaaaa' "$t/out"; ok $? "armed line"

# 2. 150 comments span two pages; the board comment and a COWORK comment are filtered but still counted for paging
newcase; gen 244 150 2001; patch 244 '(.[4].body) = "COWORK 21:00 CDT: note" | (.[9].id) = 900'
runw 2
[ "$(count '^#244 comment ')" -eq 148 ]; ok $? "two pages: 148 peer comments printed (150 minus the board comment and a COWORK comment)"
[ "$(grep '^#244 comment ' "$t/out" | awk '{print $3}' | sort -u | wc -l | tr -d ' ')" -eq 148 ]; ok $? "two pages: no id printed twice"
[ -f "$t/fake/n_c244p2" ]; ok $? "page 2 was requested although page 1 had only 98 peer comments (paging follows the raw count)"
! grep -q '^WATCH' "$t/out"; ok $? "no failure noise on a clean run"

# 3. page 2 fails on the first try: nothing from page 1 is printed until the whole fetch succeeds, nothing is lost or doubled
newcase; gen 244 150 2001; patch 244 '(.[4].body) = "COWORK 21:00 CDT: note" | (.[9].id) = 900'; echo 1 > "$t/fake/fail_244_2"
runw 3
[ "$(count '^#244 comment ')" -eq 148 ]; ok $? "page-2 failure then retry: 148 comments, none lost, none doubled"
[ "$(lineno '^WATCH FAILING the comments of #244')" -lt "$(lineno '^#244 comment ')" ]; ok $? "nothing printed from the failed fetch (buffered until every page succeeded)"
[ "$(count '^WATCH RECOVERED the comments of #244')" -eq 1 ]; ok $? "recovery reported once"

# 4. the fake ignores since (every fetch returns everything): each comment still prints once
newcase; gen 244 150 2001; patch 244 '(.[4].body) = "COWORK 21:00 CDT: note" | (.[9].id) = 900'
FAKE_IGNORE_SINCE=true runw 3
[ "$(count '^#244 comment ')" -eq 148 ]; ok $? "repeated fetches of the same comments: each printed once"

# 5. a malformed response (no COUNT line) is a failure, not 'no comments'
newcase; gen 244 1 1001; echo 1 > "$t/fake/garbage_244_1"
runw 3
[ "$(count '^WATCH FAILING the comments of #244')" -eq 1 ] && [ "$(count '^#244 comment 1001 ')" -eq 1 ]; ok $? "garbage response: reported as a failure, comment printed after recovery"

# 6. an edit is reported with the update time
newcase; gen 244 1 1001; patch 244 '(.[0].updated_at) = "2020-01-03T00:00:00Z"'
runw 1
grep -q '^#244 comment 1001 2020-01-02T00:00:00Z edited 2020-01-03T00:00:00Z comment 0' "$t/out"; ok $? "an edited comment prints created and updated times"

# 7. PR and main events
newcase
echo '{"head":{"sha":"2222222bbbb"},"state":"open","merged":false}' > "$t/fake/pull_299.2.json"
echo '{"head":{"sha":"2222222bbbb"},"state":"closed","merged":true}' > "$t/fake/pull_299.3.json"
echo '{"sha":"bbbbbbb1111"}' > "$t/fake/main.2.json"
runw 2
grep -q '^#299 head moved 1111111 -> 2222222$' "$t/out"; ok $? "head move reported"
grep -q '^#299 closed merged=true$' "$t/out"; ok $? "close and merge reported"
grep -q '^main moved aaaaaaa -> bbbbbbb$' "$t/out"; ok $? "main move reported"
[ "$(count '^#299 closed')" -eq 1 ]; ok $? "close reported once"

# 8. a failing PR or main request is reported and its recovery too
newcase; echo 2 > "$t/fake/fail_pull299"; echo 2 > "$t/fake/fail_main"
runw 3
[ "$(count '^WATCH FAILING the state of #299')" -eq 1 ] && [ "$(count '^WATCH RECOVERED the state of #299')" -eq 1 ]; ok $? "PR state failure and recovery reported"
[ "$(count '^WATCH FAILING the main head')" -eq 1 ] && [ "$(count '^WATCH RECOVERED the main head')" -eq 1 ]; ok $? "main failure and recovery reported"

# 9. a hung request is cut off and retried
newcase; gen 244 1 1001; echo 1 > "$t/fake/hang_244_1"
WINGMAN_GH_TIMEOUT=1 runw 2
[ "$(count '^WATCH FAILING the comments of #244')" -eq 1 ] && [ "$(count '^#244 comment 1001 ')" -eq 1 ]; ok $? "hung request: cut off by the timeout, retried, comment printed once"

# 10. bad arguments stop at once
bash "$here/watch.sh" abc >/dev/null 2>&1; [ $? -eq 2 ]; ok $? "a non-numeric issue is refused"
PATH="$t/bin:$PATH" FAKE_DIR="$t/fake" WATCH_SINCE=yesterday bash "$here/watch.sh" 244 >/dev/null 2>&1; [ $? -eq 2 ]; ok $? "a bad WATCH_SINCE is refused"

echo "watch tests: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
