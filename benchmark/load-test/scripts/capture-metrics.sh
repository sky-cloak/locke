#!/usr/bin/env bash
# capture-metrics.sh — snapshot JVM heap, cache memory, and runtime stats for a stack
#
# Usage:
#   ./capture-metrics.sh <stack-name> <scale-label> <output-dir>
#
# Detects whether the stack uses Redis or Infinispan and captures appropriately.
set -euo pipefail

STACK="${1:?stack name (A|A3|B|B3|C)}"
SCALE="${2:?scale label (e.g. realms-100)}"
OUT_DIR="${3:?output directory}"

mkdir -p "$OUT_DIR"
OUT_FILE="$OUT_DIR/metrics-${STACK}-${SCALE}.json"
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

# Find compose project name + KC container
case "$STACK" in
  A)  PROJECT="bench-a-ispn-embedded";   KC_CONTAINER="${PROJECT}-keycloak-1";   PORT=18080 ;;
  A3) PROJECT="bench-a3-ispn-3pod";      KC_CONTAINER="${PROJECT}-keycloak1-1";  PORT=18082 ;;
  B)  PROJECT="bench-b-redis-colocated"; KC_CONTAINER="${PROJECT}-keycloak-1";   PORT=18081 ;;
  B3) PROJECT="bench-b3-redis-3pod";     KC_CONTAINER="${PROJECT}-keycloak1-1";  PORT=18084 ;;
  C)  PROJECT="bench-c-redis-remote";    KC_CONTAINER="${PROJECT}-keycloak-1";   PORT=18083 ;;
  *)  echo "unknown stack: $STACK" >&2; exit 1 ;;
esac

# Memory via docker stats (no-stream)
read -r KC_MEM_USAGE KC_MEM_PCT <<< $(docker stats --no-stream --format "{{.MemUsage}}|{{.MemPerc}}" "$KC_CONTAINER" 2>/dev/null | tr '|' ' ' || echo "0 0%")
KC_MEM_USAGE=${KC_MEM_USAGE%% *}
KC_MEM_PCT=${KC_MEM_PCT%\%}

# Redis-specific
REDIS_USED_MEMORY=0
REDIS_USED_MEMORY_HUMAN="0"
REDIS_KEYS=0
if [[ "$STACK" == "B" || "$STACK" == "B3" || "$STACK" == "C" ]]; then
  REDIS_CONTAINER="${PROJECT}-redis-1"
  if docker ps --format '{{.Names}}' | grep -q "^${REDIS_CONTAINER}$"; then
    INFO=$(docker exec "$REDIS_CONTAINER" redis-cli INFO memory 2>/dev/null || echo "")
    REDIS_USED_MEMORY=$(echo "$INFO" | grep -E "^used_memory:" | awk -F: '{print $2}' | tr -d '\r' || echo 0)
    REDIS_USED_MEMORY_HUMAN=$(echo "$INFO" | grep -E "^used_memory_human:" | awk -F: '{print $2}' | tr -d '\r' || echo "0")
    REDIS_KEYS=$(docker exec "$REDIS_CONTAINER" redis-cli DBSIZE 2>/dev/null | tr -d '\r' || echo 0)
  fi
fi

# Realm count via Admin REST
TOKEN=$(curl -s --max-time 10 \
  -d "client_id=admin-cli&username=admin&password=admin&grant_type=password" \
  "http://localhost:$PORT/realms/master/protocol/openid-connect/token" \
  2>/dev/null | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p' | head -1)
REALM_COUNT=0
if [[ -n "$TOKEN" ]]; then
  REALM_COUNT=$(curl -s --max-time 10 -H "Authorization: Bearer $TOKEN" \
    "http://localhost:$PORT/admin/realms?briefRepresentation=true" 2>/dev/null \
    | grep -oE '"realm":"[^"]*"' | wc -l | tr -d ' \n' || echo 0)
fi
REALM_COUNT=${REALM_COUNT:-0}

cat > "$OUT_FILE" <<EOF
{
  "timestamp": "$TIMESTAMP",
  "stack": "$STACK",
  "scale": "$SCALE",
  "realms_observed": $REALM_COUNT,
  "kc_mem_usage": "$KC_MEM_USAGE",
  "kc_mem_pct": "$KC_MEM_PCT",
  "redis_used_memory_bytes": $REDIS_USED_MEMORY,
  "redis_used_memory_human": "$REDIS_USED_MEMORY_HUMAN",
  "redis_keys": $REDIS_KEYS
}
EOF

echo "[metrics] $STACK $SCALE -> $OUT_FILE"
cat "$OUT_FILE"
