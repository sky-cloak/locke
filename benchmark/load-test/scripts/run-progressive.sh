#!/usr/bin/env bash
# run-progressive.sh — for ONE stack, run the progressive realm sweep
#
# Usage:
#   ./run-progressive.sh <stack> [scale-points] [users-per-realm] [clients] [idps]
#
# Example:
#   ./run-progressive.sh A "1 10 100 1000" 10 5 2
#
# Assumes the stack is already up. Use run-all-stacks.sh to manage spin-up/down.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STACK="${1:?stack name (A|A3|B|B3|C)}"
SCALES="${2:-1 10 100 1000}"
USERS_PER_REALM="${3:-10}"
CLIENTS_PER_REALM="${4:-5}"
IDPS_PER_REALM="${5:-2}"

case "$STACK" in
  A)  PORT=18080 ;;
  A3) PORT=18082 ;;
  B)  PORT=18081 ;;
  B3) PORT=18084 ;;
  C)  PORT=18083 ;;
  *)  echo "unknown stack: $STACK" >&2; exit 1 ;;
esac

RESULTS_DIR="$SCRIPT_DIR/../results/$(date +%Y-%m-%d)/${STACK}"
mkdir -p "$RESULTS_DIR"

echo "[run] stack=$STACK port=$PORT scales=[$SCALES] results=$RESULTS_DIR"

# Wait for KC
for i in $(seq 1 60); do
  if curl -sf --max-time 5 "http://localhost:$PORT/realms/master/.well-known/openid-configuration" > /dev/null; then
    echo "[run] $STACK ready on :$PORT"
    break
  fi
  sleep 5
done

# Fix sslRequired=NONE on master realm so HTTP admin works from Docker Desktop VM
KC_CONTAINER=$(docker ps --format '{{.Names}}' | grep -E "keycloak(-1|1-1)$" | head -1)
if [[ -n "$KC_CONTAINER" ]]; then
  echo "[run] disabling sslRequired on master realm via $KC_CONTAINER..."
  docker exec "$KC_CONTAINER" /opt/keycloak/bin/kcadm.sh config credentials \
    --server http://localhost:8080 --realm master --user admin --password admin 2>&1 | tail -1
  docker exec "$KC_CONTAINER" /opt/keycloak/bin/kcadm.sh update realms/master -s sslRequired=NONE 2>&1 | tail -1
fi

# Initial baseline capture (0 realms beyond master)
"$SCRIPT_DIR/capture-metrics.sh" "$STACK" "baseline" "$RESULTS_DIR"

PREV=0
for N in $SCALES; do
  echo ""
  echo "=== [run] $STACK: scaling to $N realms ==="
  STARTED=$(date +%s)

  if (( N > PREV )); then
    DELTA_START=$((PREV + 1))
    "$SCRIPT_DIR/provision-realms.sh" "http://localhost:$PORT" admin admin \
      "$DELTA_START" "$N" "$USERS_PER_REALM" "$CLIENTS_PER_REALM" "$IDPS_PER_REALM" \
      | tee "$RESULTS_DIR/provision-${N}.log"
  fi

  PROVISION_DURATION=$(($(date +%s) - STARTED))
  echo "[run] $STACK: provisioning to $N took ${PROVISION_DURATION}s"

  echo "[run] $STACK: settling 15s..."
  sleep 15

  # Snapshot metrics
  "$SCRIPT_DIR/capture-metrics.sh" "$STACK" "realms-${N}" "$RESULTS_DIR"

  # Run kcb scenario if available (skip if not — provisioning + memory is the primary signal)
  if [[ -f "$SCRIPT_DIR/../../kcb-run.sh" ]]; then
    echo "[run] $STACK: running 30s kcb against last-created realm..."
    (
      cd "$SCRIPT_DIR/../../"
      timeout 60 ./kcb-run.sh AuthorizationCode "$PORT" 5 30 \
        -DenvironmentRealm="realm-$(printf '%05d' "$N")" \
        > "$RESULTS_DIR/kcb-${N}.log" 2>&1 || echo "[run] kcb at scale $N had issues, see log"
    )
  fi

  PREV=$N
done

echo ""
echo "[run] $STACK COMPLETE — results in $RESULTS_DIR"
ls -la "$RESULTS_DIR"
