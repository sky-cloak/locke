#!/usr/bin/env bash
# clustered-throughput-sweep.sh — SKYCF-426.
# Real 3-pod clustered throughput: A3 (Infinispan/JGroups) vs B3 (Redis pub/sub),
# both behind one nginx least_conn LB. This is the config the single-instance
# realm sweep could NOT measure: it makes Infinispan pay JGroups N×N + session
# replication, and makes Redis pay pub/sub + shared-store round-trips.
#
# Runs each stack sequentially (one 3-pod stack fits in ~7 GB; we don't run both
# at once). Verifies A3 actually forms a 3-member cluster before trusting its
# numbers. Sweeps a few Gatling load points and reports the B/A parity ratio.
#
#   ./clustered-throughput-sweep.sh "5 20 40" 60
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BENCH="$(cd "$SCRIPT_DIR/../.." && pwd)"
COMPOSE="$BENCH/compose"
LOADS="${1:-5 20 40}"
MEAS="${2:-60}"
DATE=$(date +%Y-%m-%d)
OUT="$BENCH/load-test/results/$DATE/clustered-426"
mkdir -p "$OUT"
SUMMARY="$OUT/summary.tsv"
echo -e "stack\tload_ups\tthroughput_rps\terrors\trun_dir" > "$SUMMARY"

log(){ echo "[426] $*"; }

# Pull mean req/s out of Gatling's stats.json for the newest kcb result dir.
extract_rps(){
  # Parse Gatling's own console summary in gatling-stdout.log:
  #   "> mean requests/sec   17.67 (OK=17.67  KO=-  )"  and the failed-events count.
  local newest log rps err
  newest=$(ls -dt "$BENCH"/results/kcb-AuthorizationCode-"$1"-* 2>/dev/null | head -1) || return 1
  [ -z "$newest" ] && return 1
  log="$newest/gatling-stdout.log"
  if [ -f "$log" ]; then
    rps=$(grep "mean requests/sec" "$log" | grep -oE "[0-9]+\.[0-9]+" | head -1)
    err=$(grep -E "^> failed" "$log" | grep -oE "[0-9]+" | head -1)
    echo "${rps:-NA}|${err:-0}|$newest"
  else
    echo "NA|NA|$newest"
  fi
}

verify_cluster(){
  # A3 only: confirm a 3-member JGroups view formed. ISPN000094 logs the view.
  local proj="$1"
  log "verifying 3-node cluster for $proj ..."
  for i in $(seq 1 24); do
    local view
    # Infinispan logs: "ISPN000094: Received new cluster view ... (3) [node-a, node-b, node-c]"
    # Node IDs are container-hostname based, so match on the "(N) [" member count.
    view=$(docker compose -p "$proj" logs keycloak1 2>/dev/null | grep -E "ISPN000094.*Received new cluster view" | tail -1 || true)
    if echo "$view" | grep -qE "\(3\) \["; then
      log "cluster OK (3 members): $view"; return 0
    fi
    sleep 5
  done
  log "WARN: could not confirm 3-member view from logs (continuing; check $OUT/a3-cluster.log)"
  docker compose -p "$proj" logs keycloak1 2>/dev/null | grep -E "ISPN000094|ISPN000093" > "$OUT/a3-cluster.log" || true
  return 0
}

run_stack(){
  local stack="$1" file="$2" port="$3" proj="$4" verify="$5"
  log "=== STACK $stack ($file) port $port ==="
  docker compose -f "$COMPOSE/$file" down -v --remove-orphans >/dev/null 2>&1 || true
  docker compose -f "$COMPOSE/$file" up -d
  # wait for LB to serve master realm (cluster nodes behind it)
  local ready=0
  for i in $(seq 1 96); do
    if curl -fsS --max-time 5 "http://localhost:$port/realms/master" >/dev/null 2>&1; then
      log "$stack LB ready after ${i}x5s"; ready=1; break
    fi
    sleep 5
  done
  [ "$ready" = 1 ] || { log "FATAL: $stack not ready"; docker compose -f "$COMPOSE/$file" logs --tail=40 keycloak1 > "$OUT/$stack-boot-fail.log" 2>&1; docker compose -f "$COMPOSE/$file" down -v >/dev/null 2>&1; return 1; }
  # bench-kcb realm imported?
  for i in $(seq 1 30); do
    curl -fsS --max-time 5 "http://localhost:$port/realms/bench-kcb/.well-known/openid-configuration" >/dev/null 2>&1 && { log "bench-kcb realm ready"; break; }
    sleep 3
  done
  [ "$verify" = "yes" ] && verify_cluster "$proj"
  docker compose -f "$COMPOSE/$file" ps > "$OUT/$stack-containers.txt"

  for ups in $LOADS; do
    log "$stack load=$ups ups, ${MEAS}s ..."
    "$BENCH/kcb-run.sh" AuthorizationCode "$port" "$ups" "$MEAS" || log "WARN: gatling run returned nonzero for $stack@$ups"
    local r; r=$(extract_rps "$port" || echo "NA|NA|?")
    echo -e "$stack\t$ups\t$(echo "$r" | cut -d'|' -f1)\t$(echo "$r" | cut -d'|' -f2)\t$(echo "$r" | cut -d'|' -f3)" >> "$SUMMARY"
    log "$stack@$ups -> $(echo "$r" | cut -d'|' -f1) rps, $(echo "$r" | cut -d'|' -f2) errors"
  done
  docker compose -f "$COMPOSE/$file" down -v --remove-orphans >/dev/null 2>&1 || true
  log "$stack torn down"
}

run_stack A3 A3-ispn-3pod.yml 18083 bench-a3-ispn-3pod yes
run_stack B3 B3-redis-3pod.yml 18084 bench-b3-redis-3pod no

log "=== PARITY (B3/A3) ==="
{
  echo ""
  echo "load_ups  A3_rps  B3_rps  parity_B/A"
  for ups in $LOADS; do
    a=$(awk -F'\t' -v u="$ups" '$1=="A3"&&$2==u{print $3}' "$SUMMARY")
    b=$(awk -F'\t' -v u="$ups" '$1=="B3"&&$2==u{print $3}' "$SUMMARY")
    par=$(awk -v a="$a" -v b="$b" 'BEGIN{ if(a+0>0 && b!="NA" && a!="NA") printf "%.1f%%", 100*b/a; else print "NA" }')
    printf "%-8s  %-7s %-7s %s\n" "$ups" "$a" "$b" "$par"
  done
} | tee "$OUT/parity.txt"

log "done -> $OUT"
cat "$SUMMARY"
