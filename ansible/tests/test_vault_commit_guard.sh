#!/bin/bash
# D25: tests the vault-commit step of ansible/playbooks/rotate-secrets.yml.
#
#   bash ansible/tests/test_vault_commit_guard.sh
#
# The shell block under test is EXTRACTED FROM THE PLAYBOOK (not copied), so
# what is tested is what ships. It runs against a throwaway origin and clone;
# the real vault, the real origin and the real Postgres password are never
# touched, and the playbook itself is not run.

set -u
HERE=$(cd "$(dirname "$0")" && pwd)
PLAYBOOK="$HERE/../playbooks/rotate-secrets.yml"
VAULT=ansible/group_vars/cabin/vault.yml
T=$(mktemp -d)
trap 'rm -rf "$T"' EXIT

PASS=0; FAIL=0
ok()  { PASS=$((PASS + 1)); echo "  PASS  $1"; }
bad() { FAIL=$((FAIL + 1)); echo "  FAIL  $1"; }
check() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 (expected '$2', got '$3')"; fi; }

# --- pull the step's shell block out of the playbook -----------------------------
PYBIN=$(command -v python3 || command -v python) || { echo "FAIL: needs python"; exit 1; }
cat > "$T/extract.py" <<'PY'
import re, sys
text = open(sys.argv[1], encoding="utf-8").read().replace("\r\n", "\n")
m = re.search(r"- name: Commit the re-encrypted vault and push it to main\n.*?cmd: \|\n(.*?)\n\s+chdir:", text, re.S)
assert m, "could not find the vault commit step in the playbook"
body = m.group(1)
indent = min(len(l) - len(l.lstrip()) for l in body.split("\n") if l.strip())
print("\n".join(l[indent:] for l in body.split("\n")).replace("{{ vault_repo_relpath }}", sys.argv[2]))
PY
"$PYBIN" "$T/extract.py" "$PLAYBOOK" "$VAULT" > "$T/step.sh"

[ -s "$T/step.sh" ] || { echo "FAIL: could not extract the step"; exit 1; }
git config --global --get user.name >/dev/null 2>&1 || export GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@t GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@t

# a fresh origin (bare) + a clone with an edited vault and an unrelated uncommitted edit
fresh() {
    rm -rf "$T/origin.git" "$T/work"
    git init -q --bare -b main "$T/origin.git"
    git clone -q "$T/origin.git" "$T/work" 2>/dev/null
    ( cd "$T/work" && mkdir -p "$(dirname "$VAULT")" && echo "v1" > "$VAULT" && echo "readme" > README.md \
        && git add -A && git commit -q -m init && git push -q origin HEAD:main )
}
last_commit_files() { git -C "$T/origin.git" diff-tree --no-commit-id --name-only -r main~1 main; }

echo "== a normal rotation pushes exactly one vault-only commit that names the run"
fresh
( cd "$T/work" && echo "v2-rotated" > "$VAULT" && echo "stray edit" >> README.md )
before=$(git -C "$T/origin.git" rev-list --count main)
( cd "$T/work" && GITHUB_RUN_ID=424242 bash "$T/step.sh" ) > "$T/out.txt" 2>&1; rc=$?
after=$(git -C "$T/origin.git" rev-list --count main)
check "the step succeeds" "0" "$rc"
check "exactly one commit was pushed" "$((before + 1))" "$after"
check "it changed only the vault file" "$VAULT" "$(last_commit_files)"
msg=$(git -C "$T/origin.git" log -1 --format=%B main)
case "$msg" in *"workflow run 424242"*) ok "the message names the run" ;; *) bad "the message names the run (got: $msg)" ;; esac
case "$msg" in *"vault: rotate vault_postgres_password"*) ok "the message names the secret" ;; *) bad "the message names the secret" ;; esac
check "the stray README edit was not pushed" "readme" "$(git -C "$T/origin.git" show main:README.md)"

echo "== outside Actions the run reads 'manual'"
fresh
( cd "$T/work" && echo "v2" > "$VAULT" )
( cd "$T/work" && env -u GITHUB_RUN_ID bash "$T/step.sh" ) > /dev/null 2>&1
case "$(git -C "$T/origin.git" log -1 --format=%B main)" in *"workflow run manual"*) ok "message says 'workflow run manual'" ;; *) bad "message says 'workflow run manual'" ;; esac

echo "== a step edited to include a second path is REFUSED and pushes nothing"
fresh
( cd "$T/work" && echo "v2" > "$VAULT" )
# simulate the bug the guard exists for: the step also stages README.md
cat > "$T/contaminate.py" <<'PY'
import re, sys
s = open(sys.argv[1], encoding="utf-8").read()
s2 = re.sub(r"^( *)tree=\$\(git write-tree\)",
            lambda m: m.group(1) + "git update-index --cacheinfo 100644,$(git hash-object -w README.md),README.md\n" + m.group(0),
            s, count=1, flags=re.M)
assert s2 != s, "could not contaminate the step"
open(sys.argv[2], "w", encoding="utf-8", newline="\n").write(s2)
PY
"$PYBIN" "$T/contaminate.py" "$T/step.sh" "$T/bad_step.sh" || bad "could not build the contaminated step"
before=$(git -C "$T/origin.git" rev-list --count main)
( cd "$T/work" && echo "changed readme" > README.md && bash "$T/bad_step.sh" ) > "$T/bad_out.txt" 2>&1; rc=$?
after=$(git -C "$T/origin.git" rev-list --count main)
check "the contaminated step fails" "1" "$rc"
check "nothing was pushed" "$before" "$after"
grep -q "more than the vault file" "$T/bad_out.txt" && ok "it says why" || bad "it says why"

echo "== an unchanged vault pushes nothing and succeeds"
fresh
before=$(git -C "$T/origin.git" rev-list --count main)
( cd "$T/work" && bash "$T/step.sh" ) > "$T/same_out.txt" 2>&1; rc=$?
after=$(git -C "$T/origin.git" rev-list --count main)
check "succeeds" "0" "$rc"
check "no commit pushed" "$before" "$after"
grep -q "nothing to push" "$T/same_out.txt" && ok "it says nothing to push" || bad "it says nothing to push"

echo
echo "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
