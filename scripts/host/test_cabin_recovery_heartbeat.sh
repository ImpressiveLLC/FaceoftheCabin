#!/usr/bin/env bash
# Tests for cabin-recovery-heartbeat: the heartbeat state machine, the probes, the boot check, notification and
# dead-man behaviour, with stub docker/curl/systemctl on PATH and a scratch state folder.
#   bash scripts/host/test_cabin_recovery_heartbeat.sh
set -uo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
SCRIPT="$HERE/cabin-recovery-heartbeat"
W=$(mktemp -d)
trap 'rm -rf -- "${W:?}"' EXIT
PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "  ok   $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  FAIL $1"; }
check(){ if eval "$2"; then ok "$1"; else bad "$1  [$2]"; fi; }

PY=$(command -v python3 || command -v python || true)
json_ok() { [ -n "$PY" ] && "$PY" -c 'import json,sys; json.load(open(sys.argv[1]))' "$1" 2>/dev/null; }

mkdir -p "$W/bin" "$W/c" "$W/units" "$W/state"
cat >"$W/bin/docker" <<'EOF'
#!/usr/bin/env bash
case "$1" in
  inspect) f="$W/c/${@: -1}"; [ -f "$f" ] && cat "$f" || exit 1 ;;
  exec) shift
    case "$*" in
      *pg_isready*) exit "$(cat "$W/pg_rc" 2>/dev/null || echo 0)" ;;
      *psql*) cat "$W/psql_out" ;;
      *'zigbee2mqtt/+/availability'*) cat "$W/avail" 2>/dev/null ;;
      *'zigbee2mqtt/bridge/state'*) cat "$W/z2m_state" 2>/dev/null ;;
      *'$SYS/broker/uptime'*) cat "$W/mqtt_uptime" 2>/dev/null ;;
    esac ;;
esac
EOF
cat >"$W/bin/curl" <<'EOF'
#!/usr/bin/env bash
echo "$*" >>"$W/argv.log"; url="${@: -1}"; title=""; prio=""; body=""; wfmt=0; auth=no
args=("$@")
for ((i=0;i<${#args[@]};i++)); do
  case "${args[$i]}" in
    -H) h="${args[$((i+1))]}"; case "$h" in Title:*) title="${h#Title: }";; Priority:*) prio="${h#Priority: }";; esac ;;
    -d) body="${args[$((i+1))]}" ;;
    -w) wfmt=1 ;;
    -K) if grep -q 'Authorization: Bearer' ; then auth=yes; fi ;;
  esac
  case "${args[$i]}" in "Authorization: Bearer "*) auth=yes ;; esac
done
case "$url" in
  https://ntfy.test/*) rc=$(cat "$W/ntfy_rc" 2>/dev/null || echo 0)
     [ "$rc" = 0 ] && printf '%s|%s|%s\n' "$prio" "$title" "$body" >>"$W/ntfy.log"; exit "$rc" ;;
  https://dead.test/*) echo "$url auth=$auth" >>"$W/deadman.log"; exit 0 ;;
  */actuator/health) cat "$W/health" 2>/dev/null || exit 22 ;;
  */api/stats) cat "$W/frigate_stats" 2>/dev/null || exit 22 ;;
  *) key=$(printf '%s' "$url" | tr -c 'a-z0-9' _)
     if [ "$wfmt" = 1 ]; then cat "$W/code_$key" 2>/dev/null || echo 200; else echo ok; fi ;;
esac
EOF
cat >"$W/bin/systemctl" <<'EOF'
#!/usr/bin/env bash
case "$1" in
  is-active) f="$W/units/$2"; if [ -f "$f" ]; then cat "$f"; else echo inactive; exit 3; fi ;;
  list-jobs) cat "$W/jobs" 2>/dev/null ;;
esac
EOF
cat >"$W/bin/timedatectl" <<'EOF'
#!/usr/bin/env bash
cat "$W/ntp" 2>/dev/null || echo yes
EOF
cat >"$W/bin/df" <<'EOF'
#!/usr/bin/env bash
m="${@: -1}"; key=$(printf '%s' "$m" | tr -c 'a-z0-9' _)
printf 'Use%%\n %s%%\n' "$(cat "$W/df_$key" 2>/dev/null || echo 40)"
EOF
cat >"$W/bin/ping" <<'EOF'
#!/usr/bin/env bash
t="${@: -1}"; key=$(printf '%s' "$t" | tr -c 'a-z0-9' _)
[ -f "$W/ping_all_down" ] || [ -f "$W/ping_down_$key" ] && exit 1
exit 0
EOF
cat >"$W/bin/getent" <<'EOF'
#!/usr/bin/env bash
[ -f "$W/dns_down" ] && exit 2
echo "104.16.0.1 $2"
EOF
cat >"$W/bin/ip" <<'EOF'
#!/usr/bin/env bash
[ -f "$W/no_route" ] || echo "default via 192.168.2.1 dev eno1 proto dhcp"
EOF
cat >"$W/bin/tailscale" <<'EOF'
#!/usr/bin/env bash
case "$1" in
  status) [ -f "$W/ts_dead" ] && exit 1; cat "$W/ts_status" ;;
  debug) cat "$W/ts_prefs" ;;
  ping) exit "$(cat "$W/tsping_rc" 2>/dev/null || echo 0)" ;;
esac
EOF
cat >"$W/bin/ss" <<'EOF'
#!/usr/bin/env bash
cat "$W/ss_out"
EOF
chmod +x "$W"/bin/*

reset_world() {
  rm -rf "$W/state" "$W/c" "$W/units" "$W/ntfy.log" "$W/deadman.log" "$W/report_seen" "$W/argv.log" "$W/hook.log" "$W/ntfy_rc" "$W/jobs" "$W/avail" "$W/pg_rc"
  rm -f "$W"/df_* "$W"/code_* "$W"/ping_* "$W"/dns_down "$W"/no_route "$W"/ts_dead "$W"/tsping_rc
  mkdir -p "$W/state" "$W/c" "$W/units"
  for c in cabin-backend cabin-postgres cabin-kafka mosquitto zigbee2mqtt frigate blinkbridge cloudflared homeassistant nodered \
           cabin-ui family-hub cabin-grafana uptime-kuma mediamtx; do echo 'true||0' >"$W/c/$c"; done
  for u in backup-home.timer backup-configs.timer backup-services.timer backup-data.timer cabin-storage-trim-agent.service blinkbridge-watchdog.timer; do
    echo active >"$W/units/$u"; done
  echo '{"status":"UP"}' >"$W/health"
  echo '{"state":"online"}' >"$W/z2m_state"
  echo 123456 >"$W/mqtt_uptime"
  echo '{"cameras":{"driveway":{"camera_fps":5.0,"process_fps":5.0},"porch":{"camera_fps":10.0}}}' >"$W/frigate_stats"
  echo '999000|1000100|1000290' >"$W/psql_out"
  echo yes >"$W/ntp"
  echo active >"$W/units/ssh"
  echo 'LISTEN 0 128 0.0.0.0:22 0.0.0.0:*' >"$W/ss_out"
  printf '{\n "BackendState": "Running",\n "Self": {\n  "Online": true\n },\n "Peer": {\n  "a": {\n   "Online": true\n  },\n  "b": {\n   "Online": false\n  }\n }\n}\n' >"$W/ts_status"
  echo '{"RunSSH": true}' >"$W/ts_prefs"
}

export W
export PATH="$W/bin:$PATH"
export HB_STATE_DIR="$W/state" HB_NTFY_URL=https://ntfy.test HB_NTFY_TOPIC=testtopic HB_PUBLIC_URLS=https://cabin.test/
export HB_BOOT_EPOCH=1000000 HB_NOW=1000300 HB_BOOT_RETRY=0 HB_BOOT_GRACE=0 HB_PROBE_TIMEOUT=5
export HB_MISS_LIMIT=5 HB_INTERVAL=60

run() { bash "$SCRIPT" "$@" 2>/dev/null; }
pushes() { cat "$W/ntfy.log" 2>/dev/null | grep -c . || true; }

echo "heartbeat state machine"
(
  source "$SCRIPT"
  HB_MISS_LIMIT=5; HB_RECOVER_OKS=2; HB_REPAGE_FIRST=3600; HB_REPAGE_EVERY=21600
  ev=""
  t=1000
  for i in 1 2 3 4; do hb_step kafka 1 $t; ev+="$STEP_EVENT,"; t=$((t+60)); done
  [ "$ev" = "none,none,none,none," ] && echo "T no-page-before-limit" || echo "F no-page-before-limit: $ev"
  hb_step kafka 1 $t; [ "$STEP_EVENT" = down ] && echo "T down-on-fifth" || echo "F down-on-fifth: $STEP_EVENT"
  t=$((t+60)); hb_step kafka 1 $t; [ "$STEP_EVENT" = none ] && echo "T no-repage-each-cycle" || echo "F no-repage-each-cycle: $STEP_EVENT"
  hb_step kafka 1 $((t+3600)); [ "$STEP_EVENT" = repage ] && echo "T repage-after-first-hour" || echo "F repage-after-first-hour: $STEP_EVENT"
  hb_step kafka 1 $((t+3660)); [ "$STEP_EVENT" = none ] && echo "T quiet-after-repage" || echo "F quiet-after-repage: $STEP_EVENT"
  hb_step kafka 1 $((t+3600+21600)); [ "$STEP_EVENT" = repage ] && echo "T later-repage-is-six-hours" || echo "F later-repage-is-six-hours: $STEP_EVENT"
  hb_step kafka 0 $((t+30000)); [ "$STEP_EVENT" = none ] && echo "T one-good-beat-is-not-recovered" || echo "F one-good-beat-is-not-recovered: $STEP_EVENT"
  hb_step kafka 0 $((t+30060)); [ "$STEP_EVENT" = recovered ] && echo "T recovered-after-two" || echo "F recovered-after-two: $STEP_EVENT"
  hb_step kafka 0 $((t+30120)); [ "$STEP_EVENT" = none ] && echo "T recovered-once" || echo "F recovered-once: $STEP_EVENT"
  # a blip never pages
  ev=""; t=5000
  for r in 1 1 1 0 1 1 1 0 1 1; do hb_step nodered $r $t; ev+="$STEP_EVENT,"; t=$((t+60)); done
  [ "$ev" = "none,none,none,none,none,none,none,none,none,none," ] && echo "T blips-do-not-page" || echo "F blips-do-not-page: $ev"
  # announced by the boot summary: no second page, but recovery is still reported
  mark_announced frigate 9000
  hb_step frigate 1 9060; [ "$STEP_EVENT" = none ] && echo "T announced-not-repaged" || echo "F announced-not-repaged: $STEP_EVENT"
  hb_step frigate 0 9120; hb_step frigate 0 9180; [ "$STEP_EVENT" = recovered ] && echo "T announced-then-recovered" || echo "F announced-then-recovered: $STEP_EVENT"
) 2>&1 | while IFS= read -r line; do
  case "$line" in T\ *) echo "  ok   ${line#T }" ;; F\ *) echo "  FAIL ${line#F }" ;; *) echo "  ??   $line" ;; esac
done > "$W/sm.out"
cat "$W/sm.out"
PASS=$((PASS + $(grep -c '^  ok' "$W/sm.out"))); FAIL=$((FAIL + $(grep -c '^  FAIL' "$W/sm.out")))
check "state machine ran all 12 checks" "[ \$(grep -c . $W/sm.out) -eq 12 ]"

echo "a down component pages once, not every cycle"
reset_world
echo 'false||0' >"$W/c/nodered"
for i in 1 2 3 4; do HB_NOW=$((1000300+i*60)) run once; done
check "four misses: no push" "[ \$(pushes) -eq 0 ]"
HB_NOW=$((1000300+5*60)) run once
check "fifth miss: exactly one urgent push naming the component" "[ \$(pushes) -eq 1 ] && grep -q '^urgent|Cabin: nodered is down|' $W/ntfy.log"
for i in 6 7 8 9; do HB_NOW=$((1000300+i*60)) run once; done
check "later cycles do not page again" "[ \$(pushes) -eq 1 ]"
check "state.json is valid JSON" "json_ok $W/state/state.json"
check "state.json lists the component as down" "grep -q '\"name\":\"nodered\",\"status\":\"down\"' $W/state/state.json"
check "state.json lists the others as ok" "grep -q '\"name\":\"backend\",\"status\":\"ok\"' $W/state/state.json"
check "state.json is world-readable" "[ \$(stat -c %a $W/state/state.json) = 644 ]"
echo 'true||0' >"$W/c/nodered"
HB_NOW=$((1000300+10*60)) run once
check "one good beat: no recovered notice yet" "[ \$(pushes) -eq 1 ]"
HB_NOW=$((1000300+11*60)) run once
check "second good beat: one recovered notice at normal priority" "[ \$(pushes) -eq 2 ] && tail -1 $W/ntfy.log | grep -q '^default|Cabin: nodered recovered|'"
echo 'false||0' >"$W/c/cabin-kafka"
for i in 12 13 14 15 16; do HB_NOW=$((1000300+i*60)) run once; done
BEFORE=$(pushes)
for i in 17 18; do HB_NOW=$((1000300+i*60)) run once; done
check "a fresh process per cycle keeps the counters: kafka paged once only" "[ \$BEFORE -eq 3 ] && [ \$(pushes) -eq 3 ] && grep -c 'kafka is down' $W/ntfy.log | grep -qx 1"
HB_NOW=$((1000300+16*60+3600)) run once
check "still down after an hour: one reminder" "grep -q 'Cabin: kafka STILL down' $W/ntfy.log"

echo "probes"
reset_world
probe() { ( source "$SCRIPT"; probe_component "$1"; echo "rc=$? $PROBE_DETAIL" ); }
check "all good: backend" "probe backend | grep -q '^rc=0'"
echo 'false||0' >"$W/c/cabin-backend"; check "backend container stopped" "probe backend | grep -q 'rc=1 container cabin-backend not running'"
echo 'true|unhealthy|0' >"$W/c/cabin-backend"; check "backend container unhealthy" "probe backend | grep -q 'rc=1 container cabin-backend unhealthy'"
rm "$W/c/cabin-backend"; check "backend container missing" "probe backend | grep -q 'rc=1 container cabin-backend not found'"
echo 'true|healthy|0' >"$W/c/cabin-backend"; echo '{"status":"DOWN"}' >"$W/health"
check "backend actuator DOWN" "probe backend | grep -q 'rc=1 actuator health is not UP'"
echo '{"status":"UP"}' >"$W/health"; echo 1 >"$W/pg_rc"
check "postgres not accepting" "probe postgres | grep -q 'rc=1 pg_isready'"
echo 0 >"$W/pg_rc"; check "postgres accepting" "probe postgres | grep -q '^rc=0'"
echo '{"state":"offline"}' >"$W/z2m_state"; check "zigbee bridge offline" "probe zigbee2mqtt | grep -q 'rc=1 bridge state'"
: >"$W/mqtt_uptime"; check "mosquitto silent" "probe mosquitto | grep -q 'rc=1 broker gave no uptime'"
echo 503 >"$W/code_http___127_0_0_1_1880_"; check "node-red answering 503" "probe nodered | grep -q 'rc=1 HTTP 503'"
echo 401 >"$W/code_http___127_0_0_1_8123_"; check "home assistant answering 401 counts as up" "probe homeassistant | grep -q '^rc=0'"
echo 000 >"$W/code_http___127_0_0_1_5000_api_version"; check "frigate not answering" "probe frigate | grep -q 'rc=1 HTTP 000'"

echo "boot check"
reset_world
: >"$W/state/clean-shutdown"; echo '{"status":"running"}' >"$W/state/state.json"
HB_ONCE=1 run run
check "all clear: INFO summary names the restart time, recovery time and gap" "grep -q 'Cabin restarted at 1970-01-12T13:46:40Z, recovered in 5 min, telemetry gap 18 min. All [0-9]* checks clear.' $W/state/state.json"
check "clean shutdown is recognised and the marker consumed" "grep -q '\"uncleanShutdown\":\"false\"' $W/state/state.json && [ ! -e $W/state/clean-shutdown ]"
reset_world
echo '{"status":"running"}' >"$W/state/state.json"
echo 'false||0' >"$W/c/cabin-kafka"; echo 'true||4' >"$W/c/frigate"; echo 85 >"$W/df__storage"
echo 'zigbee2mqtt/leak_mech_room/availability {"state":"offline"}' >"$W/avail"
echo '{"cameras":{"driveway":{"camera_fps":0.0},"porch":{"camera_fps":9.0}}}' >"$W/frigate_stats"
echo 530 >"$W/code_https___cabin_test_"
echo inactive >"$W/units/backup-data.timer"; rm "$W/units/cabin-storage-trim-agent.service"; echo no >"$W/ntp"
HB_ONCE=1 run run; rm -f "$W/ntfy.log"
run check >"$W/report.txt"; rc=$?
check "problems: the check exits non-zero" "[ $rc -ne 0 ]"
check "summary is CRITICAL and names kafka" "grep -q 'checks need attention: .*kafka (container cabin-kafka not running)' $W/report.txt"
check "unclean shutdown is stated" "grep -q 'after an unclean shutdown' $W/report.txt"
check "restart count flagged" "grep -q 'WARN   frigate-restarts .*restarted 4 times' $W/report.txt"
check "zigbee device offline named" "grep -q 'WARN   zigbee-devices .*leak_mech_room' $W/report.txt"
check "camera at 0 fps named" "grep -q 'WARN   frigate-cameras .*driveway' $W/report.txt"
check "public URL answering 530 (tunnel down) is a CRITICAL component" "grep -q 'CRIT   public-url .*530' $W/report.txt"
check "disk above the line is only a warning (the storage monitor owns that alert)" "grep -q 'WARN   disk/storage .*85% used' $W/report.txt && ! grep -q 'CRIT.*disk' $W/report.txt"
check "stopped backup timer, missing trim agent and unsynced clock named" "grep -q 'WARN   timer-backup-data' $W/report.txt && grep -q 'WARN   cabin-storage-trim-agent.service' $W/report.txt && grep -q 'WARN   ntp' $W/report.txt"
check "check without --notify sends nothing" "[ \$(pushes) -eq 0 ]"

echo "boot summary notification and de-duplication"
reset_world
echo '{"status":"running"}' >"$W/state/state.json"; echo 'false||0' >"$W/c/cabin-kafka"
run check --notify >/dev/null
check "one CRITICAL push for the whole boot" "[ \$(pushes) -eq 1 ] && grep -q '^urgent|Cabin restarted: PROBLEMS|' $W/ntfy.log"
for i in 1 2 3 4 5 6 7 8; do HB_NOW=$((1000300+i*60)) run once; done
check "the failing component is not paged a second time by the heartbeat loop" "[ \$(pushes) -eq 1 ]"
reset_world; : >"$W/state/clean-shutdown"; echo '{"status":"running"}' >"$W/state/state.json"
run check --notify >/dev/null
check "all clear: one INFO push" "[ \$(pushes) -eq 1 ] && grep -q '^default|Cabin restarted: all clear|' $W/ntfy.log"
reset_world; echo '{"status":"running"}' >"$W/state/state.json"; echo 'true||0' >"$W/c/family-hub"; rm "$W/c/cabin-ui"
run check --notify >/dev/null
check "only warnings: WARN push at high priority" "grep -q '^high|Cabin restarted: needs a look|' $W/ntfy.log"
reset_world; echo '{"status":"running"}' >"$W/state/state.json"; echo '999000|0|0' >"$W/psql_out"
run check >"$W/report.txt"
check "no events at all in 7 days is a warning, not a crash" "grep -q 'WARN   cabin-event-ingest' $W/report.txt"
echo 'garbage' >"$W/psql_out"; run check >"$W/report.txt"
check "an unreadable database is CRITICAL" "grep -q 'CRIT   cabin-event-ingest .*could not query' $W/report.txt"
echo "999000|0|$((1000300-4000))" >"$W/psql_out"; run check >"$W/report.txt"
check "ingest stale for over an hour is CRITICAL" "grep -q 'CRIT   cabin-event-ingest .*old' $W/report.txt"

echo "service start vs host boot"
reset_world
HB_BOOT_EPOCH=$((1000300-100000)) HB_ONCE=1 run run
check "service start on a long-running host sends no boot summary" "[ \$(pushes) -eq 0 ] && json_ok $W/state/state.json"
reset_world
HB_ONCE=1 run run
check "host boot sends the boot summary, then runs a heartbeat" "[ \$(pushes) -eq 1 ] && grep -q '\"boot\":{\"checkedAt\"' $W/state/state.json && grep -q '\"status\":\"running\"' $W/state/state.json"
check "boot block in state.json carries the severity and gap" "grep -q '\"severity\":\"info\"' $W/state/state.json && grep -q '\"telemetryGapSeconds\":1100' $W/state/state.json"
check "state.json with a boot block is valid JSON" "json_ok $W/state/state.json"

echo "stopping"
reset_world; echo '{"status":"running"}' >"$W/state/state.json"
echo 'shutdown.target stop waiting' >"$W/jobs"
run stopped
check "host shutdown: clean marker written, nobody paged" "[ -e $W/state/clean-shutdown ] && [ \$(pushes) -eq 0 ] && grep -q '\"status\":\"host-shutdown\"' $W/state/state.json"
reset_world; echo '{"status":"running"}' >"$W/state/state.json"
HB_DEADMAN_URL=https://dead.test/ping/abc SERVICE_RESULT=success run stopped
check "deliberate stop: CRITICAL death notice" "[ \$(pushes) -eq 1 ] && grep -q '^urgent|Cabin: heartbeat STOPPED on purpose|' $W/ntfy.log"
check "deliberate stop: dead-man /fail signalled" "grep -qx 'https://dead.test/ping/abc/fail auth=no' $W/deadman.log"
check "deliberate stop: state says stopped (the backend shows the banner from this)" "grep -q '\"status\":\"stopped\"' $W/state/state.json && [ ! -e $W/state/clean-shutdown ]"
HB_ONCE=1 HB_BOOT_EPOCH=$((1000300-100000)) run run
check "starting again after a deliberate stop says so" "grep -q 'heartbeat service running again' $W/ntfy.log"
reset_world; echo '{"status":"running"}' >"$W/state/state.json"
SERVICE_RESULT=exit-code run stopped
check "crash: WARN notice, no dead-man fail, systemd restarts it" "grep -q '^high|Cabin: heartbeat service crashed|' $W/ntfy.log"

echo "restart versus deliberate stop"
reset_world; echo '{"status":"running"}' >"$W/state/state.json"
echo '2210 cabin-recovery-heartbeat.service restart running' >"$W/jobs"
HB_DEADMAN_URL=https://dead.test/ping/abc SERVICE_RESULT=success run stopped
check "systemctl restart: no push, no dead-man /fail, state says restarting" "[ \$(pushes) -eq 0 ] && [ ! -s $W/deadman.log ] && grep -q '\"status\":\"restarting\"' $W/state/state.json"
for j in try-restart reload-or-restart; do
  reset_world; echo '{"status":"running"}' >"$W/state/state.json"; echo "77 cabin-recovery-heartbeat.service $j running" >"$W/jobs"
  SERVICE_RESULT=success run stopped
  check "$j job: no push" "[ \$(pushes) -eq 0 ]"
done
reset_world; echo '{"status":"running"}' >"$W/state/state.json"
echo '2211 cabin-recovery-heartbeat.service stop running' >"$W/jobs"
HB_DEADMAN_URL=https://dead.test/ping/abc SERVICE_RESULT=success run stopped
check "systemctl stop: still the CRITICAL death notice and /fail" "[ \$(pushes) -eq 1 ] && grep -q 'STOPPED on purpose' $W/ntfy.log && grep -q 'ping/abc/fail' $W/deadman.log"
reset_world; echo '{"status":"running"}' >"$W/state/state.json"
echo '2212 some-other.service restart running' >"$W/jobs"
SERVICE_RESULT=success run stopped
check "a restart job for a different unit does not hide a real stop" "[ \$(pushes) -eq 1 ]"

reset_world; echo '{"status":"running"}' >"$W/state/state.json"; echo '2213 cabin-recovery-heartbeat.service restart running' >"$W/jobs"
SERVICE_RESULT=exit-code run stopped
check "a crash during a queued restart still warns" "grep -q '^high|Cabin: heartbeat service crashed|' $W/ntfy.log"

echo "delivery problems"
reset_world; echo 'false||0' >"$W/c/nodered"; echo 7 >"$W/ntfy_rc"
for i in 1 2 3 4 5; do HB_NOW=$((1000300+i*60)) run once; done
check "ntfy unreachable: the notice is queued, not lost" "[ \$(ls $W/state/outbox | wc -l) -eq 1 ] && grep -q '\"lastResult\":\"failed\"' $W/state/state.json"
rm -f "$W/ntfy_rc"
HB_NOW=$((1000300+6*60)) run once
check "when ntfy is back the queued notice is delivered, marked delayed" "[ \$(ls $W/state/outbox | wc -l) -eq 0 ] && grep -q 'nodered is down (delayed' $W/ntfy.log"
reset_world; echo 'false||0' >"$W/c/nodered"
for i in 1 2 3 4 5; do HB_NTFY_TOPIC= HB_NOW=$((1000300+i*60)) run once; done
check "no topic configured: still works, and says so in state.json (the banner does not depend on ntfy)" "grep -q '\"ntfy\":\"not-configured\"' $W/state/state.json && grep -q '\"name\":\"nodered\",\"status\":\"down\"' $W/state/state.json"
cat >"$W/hook" <<EOF
#!/usr/bin/env bash
echo "\$1|\$2" >>"$W/hook.log"
EOF
chmod +x "$W/hook"
reset_world; echo 'false||0' >"$W/c/nodered"
for i in 1 2 3 4 5; do HB_NOTIFY_HOOK="$W/hook" HB_NOW=$((1000300+i*60)) run once; done
check "optional notify hook receives level and title" "grep -qx 'critical|Cabin: nodered is down' $W/hook.log"

echo "dead-man ping"
reset_world
HB_DEADMAN_URL=https://dead.test/ping/abc run once
check "each heartbeat cycle pings the dead-man URL" "[ \$(grep -c 'ping/abc' $W/deadman.log) -eq 1 ] && grep -q '\"deadman\":{\"configured\":true,\"lastResult\":\"ok\",\"platform\":\"not-configured\"}' $W/state/state.json"
run once
check "without a URL the dead-man is reported as not configured" "grep -q '\"deadman\":{\"configured\":false' $W/state/state.json"

echo "connectivity and ssh probes"
reset_world
check "all good: gateway, internet, dns, tailscale, public-url, sshd, tailscale-ssh" "for c in gateway internet dns tailscale public-url sshd tailscale-ssh; do probe \$c | grep -q '^rc=0' || return 1; done"
touch "$W/no_route"; check "no default route" "probe gateway | grep -q 'rc=1 no default route'"; rm "$W/no_route"
touch "$W/ping_down_192_168_2_1"; check "gateway not answering" "probe gateway | grep -q 'rc=1 gateway 192.168.2.1 does not answer'"; rm "$W/ping_down_192_168_2_1"
touch "$W/ping_down_1_1_1_1"; check "one ping target down is still up (reports the others)" "probe internet | grep -q '^rc=0 reachable via ping:9.9.9.9'"; rm "$W/ping_down_1_1_1_1"
touch "$W/ping_all_down"; check "ICMP blocked but HTTPS works: still up, says so" "probe internet | grep -q '^rc=0 reachable via https:'"
echo 000 >"$W/code_https___www_cloudflare_com_cdn_cgi_trace"; echo 000 >"$W/code_https___www_google_com_generate_204"
check "every ping and HTTPS target failing is down" "probe internet | grep -q 'rc=1 no ping or HTTPS target answers'"
rm "$W/ping_all_down" "$W"/code_https___www_*
touch "$W/dns_down"; check "dns not resolving" "probe dns | grep -q 'rc=1 could not resolve'"; rm "$W/dns_down"
touch "$W/ts_dead"; check "tailscaled not running" "probe tailscale | grep -q 'rc=1 tailscale status failed'"; rm "$W/ts_dead"
echo '{"BackendState": "Stopped"}' >"$W/ts_status"; check "tailscale stopped" "probe tailscale | grep -q 'rc=1 tailscale is not Running'"
printf '{\n "BackendState": "Running",\n "Self": {\n  "Online": true\n }\n}\n' >"$W/ts_status"; check "tailscale up with no peer online" "probe tailscale | grep -q 'rc=1 tailscale is up but no peer is online'"
reset_world; echo 1 >"$W/tsping_rc"
check "a listed tailscale peer that does not answer" "( export HB_TAILSCALE_PEERS=ilikethelights; source '$SCRIPT'; probe_component tailscale; echo rc=\$? \$PROBE_DETAIL ) | grep -q 'rc=1 peer ilikethelights'"
reset_world; echo 530 >"$W/code_https___cabin_test_"; check "public URL 530" "probe public-url | grep -q 'rc=1 HTTP 530'"
reset_world; echo inactive >"$W/units/ssh"; check "sshd not active" "probe sshd | grep -q 'rc=1 no OpenSSH service active'"
echo active >"$W/units/ssh"; echo 'LISTEN 0 128 0.0.0.0:80' >"$W/ss_out"; check "sshd active but not listening" "probe sshd | grep -q 'rc=1 ssh active but nothing listens on port 22'"
echo active >"$W/units/sshd"; echo inactive >"$W/units/ssh"; echo 'LISTEN 0 128 0.0.0.0:22' >"$W/ss_out"; check "either OpenSSH unit name counts" "probe sshd | grep -q '^rc=0'"
echo '{"RunSSH": false}' >"$W/ts_prefs"; check "tailscale ssh switched off" "probe tailscale-ssh | grep -q 'rc=1 Tailscale SSH is not enabled'"

echo "a parent outage silences its children"
reset_world
touch "$W/ping_down_1_1_1_1" "$W/ping_down_9_9_9_9" "$W/dns_down"; echo 000 >"$W/code_https___cabin_test_"
echo 000 >"$W/code_https___www_cloudflare_com_cdn_cgi_trace"; echo 000 >"$W/code_https___www_google_com_generate_204"
for i in 1 2 3 4 5; do HB_NOW=$((1000300+i*60)) run once; done
check "internet, dns and public-url all down: ONE page, naming the internet" "[ \$(pushes) -eq 1 ] && grep -q 'Cabin: internet is down' $W/ntfy.log"
check "the children are still shown as down in state.json" "grep -q '\"name\":\"dns\",\"status\":\"down\"' $W/state/state.json && grep -q '\"name\":\"public-url\",\"status\":\"down\"' $W/state/state.json"
rm "$W/ping_down_1_1_1_1" "$W/ping_down_9_9_9_9" "$W"/code_https___www_* "$W/code_https___cabin_test_"
for i in 6 7; do HB_NOW=$((1000300+i*60)) run once; done
check "internet back; dns still broken is announced on its own; public-url recovered silently" "[ \$(pushes) -eq 3 ] && grep -q 'Cabin: internet recovered' $W/ntfy.log && tail -1 $W/ntfy.log | grep -q 'Cabin: dns is down' && ! grep -q 'public-url' $W/ntfy.log"

echo "dead-man: two kinds and a token"
reset_world
HB_DEADMAN_URL=https://dead.test/ping/host HB_DEADMAN_PLATFORM_URL=https://dead.test/ping/platform HB_DEADMAN_TOKEN=s3cret-token HB_DEADMAN_TOKEN_HOSTS=dead.test run once
check "both kinds ping while the platform is healthy, with the bearer token" "grep -qx 'https://dead.test/ping/host auth=yes' $W/deadman.log && grep -qx 'https://dead.test/ping/platform auth=yes' $W/deadman.log"
check "the token never reaches the state file or the report" "! grep -rq s3cret-token $W/state"
check "the token is never on a command line (it goes through curl stdin)" "! grep -q s3cret-token $W/argv.log"
: >"$W/deadman.log"
HB_DEADMAN_URL=https://dead.test/ping/host HB_DEADMAN_TOKEN=s3cret-token run once
check "a host not named in HB_DEADMAN_TOKEN_HOSTS never receives the token" "grep -qx 'https://dead.test/ping/host auth=no' $W/deadman.log"
: >"$W/deadman.log"
HB_DEADMAN_URL=https://cabin-deadman.acct.workers.dev/ping/host run once
check "token host matching: a .workers.dev subdomain matches the default list" "( source '$SCRIPT'; HB_DEADMAN_TOKEN_HOSTS=.workers.dev; token_host_ok https://cabin-deadman.acct.workers.dev/ping/host && token_host_ok https://CABIN-DEADMAN.Acct.Workers.Dev:443/ping && ! token_host_ok https://hc-ping.com/abc && ! token_host_ok https://evilworkers.dev.example.com/x && ! token_host_ok https://workers.dev.evil.test/ping )"
check "token host matching: userinfo, backslash, plain http, odd ports and brackets are never sent" "( source '$SCRIPT'; HB_DEADMAN_TOKEN_HOSTS=.workers.dev; ! token_host_ok 'https://x.workers.dev:pw@evil.com/p' && ! token_host_ok 'https://evil.com\\\\@x.workers.dev/p' && ! token_host_ok 'https://evil.com\\\\x.workers.dev/p' && ! token_host_ok 'https://user@x.workers.dev/p' && ! token_host_ok 'http://x.workers.dev/p' && ! token_host_ok 'https://x.workers.dev:abc/p' && ! token_host_ok 'https://[::1]/p' && ! token_host_ok 'https:///p' && ! token_host_ok 'ftp://x.workers.dev/p' )"
check "token host list is not globbed" "( source '$SCRIPT'; cd '$W' && touch zzz.workers.dev; HB_DEADMAN_TOKEN_HOSTS='*.workers.dev'; ! token_host_ok https://anything.test/p )"
: >"$W/deadman.log"
HB_DEADMAN_URL=https://dead.test/ping/host HB_DEADMAN_TOKEN=$'bad\nheader = "x"' HB_DEADMAN_TOKEN_HOSTS=dead.test run once
check "a token with a control character is refused, the ping reports failed, nothing is sent" "[ ! -s $W/deadman.log ] && grep -q '\"lastResult\":\"failed\"' $W/state/state.json"
HB_DEADMAN_TOKEN=s3cret-token HB_DEADMAN_TOKEN_HOSTS=dead.test HB_DEADMAN_URL=https://dead.test/ping/host HB_DEADMAN_PLATFORM_URL=https://dead.test/ping/platform run once >/dev/null
: >"$W/deadman.log"
echo '{"state":"offline"}' >"$W/z2m_state"; : >"$W/deadman.log"
HB_DEADMAN_URL=https://dead.test/ping/host HB_DEADMAN_PLATFORM_URL=https://dead.test/ping/platform run once
check "Zigbee bridge offline: host-alive still pings, platform-healthy is withheld" "grep -qx 'https://dead.test/ping/host auth=no' $W/deadman.log && ! grep -q platform $W/deadman.log && grep -q '\"platform\":\"withheld\"' $W/state/state.json"
reset_world; echo 'false||0' >"$W/c/cabin-backend"; HB_DEADMAN_URL=https://dead.test/ping/host HB_DEADMAN_PLATFORM_URL=https://dead.test/ping/platform run once
check "backend down: platform-healthy withheld too" "! grep -q platform $W/deadman.log"
reset_world; : >"$W/state/clean-shutdown"; echo '{"status":"running"}' >"$W/state/state.json"
HB_DEADMAN_URL=https://dead.test/ping/host HB_DEADMAN_STOP_URL=https://dead.test/stop/host HB_DEADMAN_TOKEN=tok HB_DEADMAN_TOKEN_HOSTS=dead.test SERVICE_RESULT=success run stopped
check "stop uses HB_DEADMAN_STOP_URL with the token" "grep -qx 'https://dead.test/stop/host auth=yes' $W/deadman.log"

echo "report for the read-only monitoring user"
reset_world; echo '{"status":"running"}' >"$W/state/state.json"
run check --notify >/dev/null
check "report.txt is written next to state.json, world-readable, with the checks and summary" "[ \$(stat -c %a $W/state/report.txt) = 644 ] && grep -q 'Recovery check at' $W/state/report.txt && grep -q 'Cabin restarted at' $W/state/report.txt"

echo
echo "passed $PASS, failed $FAIL"
[ "$FAIL" -eq 0 ]
