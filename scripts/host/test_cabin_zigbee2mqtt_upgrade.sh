#!/usr/bin/env bash
# Tests for cabin-zigbee2mqtt-upgrade with a stub `docker` (no real containers touched).
# Run: bash scripts/host/test_cabin_zigbee2mqtt_upgrade.sh
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
SCRIPT="$HERE/cabin-zigbee2mqtt-upgrade"
fail=0
check() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: expected '$2' got '$3'"; fail=1; fi; }

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin" "$TMP/data"; echo cfg > "$TMP/data/configuration.yaml"
: > "$TMP/compose.yml"

# The stub: STUB_NEW_ID is the image id after a pull; STUB_HEALTH is what the container reports
# after a recreate on the new image. Calls are logged to $TMP/calls.
cat > "$TMP/bin/docker" <<'EOF'
#!/usr/bin/env bash
echo "$*" >> "$STUB_DIR/calls"
case "$*" in
  "inspect zigbee2mqtt") exit 0 ;;
  *"{{.Config.Image}}"*) echo koenkk/zigbee2mqtt:latest ;;
  *"{{.Image}}"*) cat "$STUB_DIR/current_id" ;;
  *"com.docker.compose.project.config_files"*) echo "$STUB_DIR/compose.yml" ;;
  *"com.docker.compose.project\"}}"*) echo stack ;;
  *"com.docker.compose.service"*) echo zigbee2mqtt ;;
  *"State.Health"*) cat "$STUB_DIR/health" ;;
  "image inspect"*) echo "$STUB_NEW_ID" ;;
  "tag "*) exit 0 ;;
  "compose "*"pull"*) exit 0 ;;
  "compose "*"up"*)
    if [ "$STUB_NEW_ID" != "$(cat "$STUB_DIR/current_id")" ]; then
      cp "$STUB_DIR/health_after" "$STUB_DIR/health"; echo migrated > "$Z2M_DATA_DIR/configuration.yaml"
    fi
    echo "$STUB_NEW_ID" > "$STUB_DIR/current_id"; exit 0 ;;
  "compose "*"stop"*) exit 0 ;;
  *"cat /app/package.json"*) echo '"version": "2.0.0"' ;;
  *mosquitto_sub*) exit 2 ;;
esac
EOF
chmod +x "$TMP/bin/docker"
export PATH="$TMP/bin:$PATH" STUB_DIR="$TMP" Z2M_DATA_DIR="$TMP/data" COMPOSE_FILE="$TMP/compose.yml" HEALTH_WAIT=5

reset() { echo sha256:old > "$TMP/current_id"; echo healthy > "$TMP/health"; : > "$TMP/calls"; rm -rf "$TMP"/data.bak-*; echo orig > "$TMP/data/configuration.yaml"; }

# 1. dry run changes nothing
reset; export STUB_NEW_ID=sha256:new; echo healthy > "$TMP/health_after"
bash "$SCRIPT" >/dev/null 2>&1; rc=$?
check "dry run exits 0" 0 "$rc"
check "dry run takes no backup" 0 "$(ls -d "$TMP"/data.bak-* 2>/dev/null | wc -l | tr -d ' ')"
check "dry run never pulls or recreates" 0 "$(grep -cE 'pull|up -d' "$TMP/calls")"

# 2. already latest: backup made, nothing recreated
reset; export STUB_NEW_ID=sha256:old
bash "$SCRIPT" --apply >/dev/null 2>&1; rc=$?
check "same image exits 0" 0 "$rc"
check "same image does not recreate" 0 "$(grep -c 'up -d' "$TMP/calls")"

# 3. healthy upgrade
reset; export STUB_NEW_ID=sha256:new; echo healthy > "$TMP/health_after"
bash "$SCRIPT" --apply >/dev/null 2>&1; rc=$?
check "upgrade exits 0" 0 "$rc"
check "upgrade keeps the backup" 1 "$(ls -d "$TMP"/data.bak-* | wc -l | tr -d ' ')"
check "upgrade recreates once" 1 "$(grep -c 'up -d' "$TMP/calls")"

# 4. unhealthy after upgrade: rolls back (second recreate) and exits 1, data restored
reset; export STUB_NEW_ID=sha256:new; echo unhealthy > "$TMP/health_after"
bash "$SCRIPT" --apply >/dev/null 2>&1; rc=$?
check "failed upgrade exits 1" 1 "$rc"
check "failed upgrade recreates twice (upgrade, rollback)" 2 "$(grep -c 'up -d' "$TMP/calls")"
check "failed upgrade restores the data copy" orig "$(cat "$TMP/data/configuration.yaml")"

exit $fail
