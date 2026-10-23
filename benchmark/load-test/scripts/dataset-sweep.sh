#!/usr/bin/env bash
# dataset-sweep.sh — progressive realm sweep for ONE stack using the
# keycloak-benchmark dataset provider (server-side bulk create). Assumes the
# stack is already up WITH the dataset-override.yml overlay.
#
# Usage: ./dataset-sweep.sh <stack> "<cumulative-scales>"
#   ./dataset-sweep.sh A "100 500 1000 2000"
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
STACK="${1:?stack A|B}"
SCALES="${2:-100 500 1000 2000}"
CLIENTS="${CLIENTS_PER_REALM:-5}"
USERS="${USERS_PER_REALM:-10}"

case "$STACK" in
  A) PORT=18080 ;;
  B) PORT=18081 ;;
  *) echo "unsupported stack $STACK (dataset sweep covers A,B)"; exit 1 ;;
esac

RESULTS_DIR="$SCRIPT_DIR/../results/$(date +%Y-%m-%d)/${STACK}-dataset"
mkdir -p "$RESULTS_DIR"
BASE="http://localhost:$PORT"
echo "[ds] stack=$STACK port=$PORT scales=[$SCALES] -> $RESULTS_DIR"

# wait ready
for i in $(seq 1 60); do
  curl -sf --max-time 5 "$BASE/realms/master" >/dev/null 2>&1 && { echo "[ds] $STACK ready"; break; }
  sleep 5
done

# sslRequired=NONE so HTTP admin + dataset endpoint work from Docker
KC=$(docker ps --format '{{.Names}}' | grep -E "keycloak(-1|1-1)$" | head -1)
docker exec "$KC" /opt/keycloak/bin/kcadm.sh config credentials \
  --server http://localhost:8080 --realm master --user admin --password admin >/dev/null 2>&1 || true
docker exec "$KC" /opt/keycloak/bin/kcadm.sh update realms/master -s sslRequired=NONE >/dev/null 2>&1 || true

# dataset provider present?
code=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/realms/master/dataset/status")
if [[ "$code" != "200" ]]; then
  echo "[ds] FATAL: dataset provider not responding (HTTP $code). Is the override mounted?"; exit 1
fi
echo "[ds] dataset provider OK"

"$SCRIPT_DIR/capture-metrics.sh" "$STACK" "baseline" "$RESULTS_DIR" >/dev/null 2>&1 || true

prev=0
for N in $SCALES; do
  delta=$((N - prev))
  [[ $delta -le 0 ]] && { prev=$N; continue; }
  pfx="s${N}_"
  echo ""
  echo "=== [ds] $STACK: +$delta realms (prefix $pfx) -> cumulative $N ==="
  t0=$(date +%s)
  start_code=$(curl -s -o "$RESULTS_DIR/create-$N.json" -w "%{http_code}" \
    "$BASE/realms/master/dataset/create-realms?count=$delta&realm-prefix=$pfx&clients-per-realm=$CLIENTS&users-per-realm=$USERS")
  echo "[ds] create-realms start HTTP $start_code"

  # poll until idle. generous ceiling: delta*8s + 600s
  ceil=$(( delta * 8 + 600 ))
  while :; do
    s=$(curl -s --max-time 10 "$BASE/realms/master/dataset/status" 2>/dev/null || echo "")
    if echo "$s" | grep -qiE "no task in progress"; then break; fi
    el=$(( $(date +%s) - t0 ))
    if (( el > ceil )); then echo "[ds] WARN: timeout ${el}s on scale $N (ceil ${ceil}s), moving on"; break; fi
    sleep 10
  done
  prov=$(( $(date +%s) - t0 ))
  echo "[ds] $STACK reached ~$N realms in ${prov}s ($(awk "BEGIN{printf \"%.3f\", $delta/$prov}") r/s for this batch)"
  echo "{\"scale\":$N,\"delta\":$delta,\"provision_s\":$prov,\"rate_per_s\":$(awk "BEGIN{printf \"%.3f\", $delta/$prov}")}" \
    > "$RESULTS_DIR/provision-$N.json"

  echo "[ds] settle 20s, then capture metrics"
  sleep 20
  "$SCRIPT_DIR/capture-metrics.sh" "$STACK" "realms-$N" "$RESULTS_DIR"
  prev=$N
done

echo ""
echo "[ds] $STACK COMPLETE -> $RESULTS_DIR"
ls -1 "$RESULTS_DIR"
