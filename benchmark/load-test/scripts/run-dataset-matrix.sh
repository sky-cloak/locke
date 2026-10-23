#!/usr/bin/env bash
# run-dataset-matrix.sh — A vs B realm sweep via the dataset provider.
# Spins each stack up WITH dataset-override.yml, runs dataset-sweep.sh, tears down.
#
#   STACKS="A B" SCALES="100 500 1000 2000" ./run-dataset-matrix.sh
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE="$(cd "$SCRIPT_DIR/../../compose" && pwd)"
OVERLAY="$COMPOSE/dataset-override.yml"
STACKS="${STACKS:-A B}"
SCALES="${SCALES:-100 500 1000 2000}"
export CLIENTS_PER_REALM="${CLIENTS_PER_REALM:-5}"
export USERS_PER_REALM="${USERS_PER_REALM:-10}"

declare -A FILES=( [A]="A-ispn-embedded.yml" [B]="B-redis-colocated.yml" )

for STACK in $STACKS; do
  f="${FILES[$STACK]:-}"
  [[ -z "$f" ]] && { echo "skip unknown $STACK"; continue; }
  echo ""
  echo "############ STACK $STACK ($f + dataset overlay) ############"
  docker compose -f "$COMPOSE/$f" -f "$OVERLAY" down -v 2>/dev/null || true
  docker compose -f "$COMPOSE/$f" -f "$OVERLAY" up -d
  # wait healthy (up to 4 min — provider load adds time)
  for i in $(seq 1 48); do
    docker compose -f "$COMPOSE/$f" -f "$OVERLAY" ps --format '{{.Health}}' 2>/dev/null | grep -q healthy && { echo "[matrix] $STACK healthy ${i}x5s"; break; }
    sleep 5
  done
  "$SCRIPT_DIR/dataset-sweep.sh" "$STACK" "$SCALES" || echo "[matrix] WARN: $STACK sweep had errors (continuing)"
  docker compose -f "$COMPOSE/$f" -f "$OVERLAY" down -v 2>/dev/null || true
done

DATE=$(date +%Y-%m-%d)
echo ""
echo "############ MATRIX COMPLETE ############"
for STACK in $STACKS; do
  d="$SCRIPT_DIR/../results/$DATE/${STACK}-dataset"
  echo "-- $STACK --"
  for m in "$d"/metrics-*realms-*.json; do
    [ -f "$m" ] || continue
    printf "  %-30s " "$(basename "$m")"
    grep -oE '"kc_mem_usage": "[^"]*"|"redis_used_memory_human": "[^"]*"' "$m" | sed 's/.*: //' | tr '\n' ' '; echo
  done
  for p in "$d"/provision-*.json; do [ -f "$p" ] && echo "  $(basename "$p"): $(cat "$p")"; done
done
