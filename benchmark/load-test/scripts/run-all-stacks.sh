#!/usr/bin/env bash
# run-all-stacks.sh — top-level orchestrator for the progressive realm matrix
#
# Spins up each stack (A, A3, B, B3, C), runs the progressive sweep, tears down.
# Captures everything to benchmark/load-test/results/<date>/<stack>/
#
# Usage:
#   ./run-all-stacks.sh                       # all stacks, all scales
#   STACKS="A B" ./run-all-stacks.sh          # subset of stacks
#   SCALES="1 10 100" ./run-all-stacks.sh     # smaller sweep
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_DIR="$(cd "$SCRIPT_DIR/../../compose" && pwd)"
STACKS="${STACKS:-A A3 B B3 C}"
SCALES="${SCALES:-1 10 100 1000}"
USERS_PER_REALM="${USERS_PER_REALM:-10}"
CLIENTS_PER_REALM="${CLIENTS_PER_REALM:-5}"
IDPS_PER_REALM="${IDPS_PER_REALM:-2}"

declare -A COMPOSE_FILES=(
  [A]="A-ispn-embedded.yml"
  [A3]="A3-ispn-3pod.yml"
  [B]="B-redis-colocated.yml"
  [B3]="B3-redis-3pod.yml"
  [C]="C-redis-remote.yml"
)

for STACK in $STACKS; do
  COMPOSE_FILE="${COMPOSE_FILES[$STACK]:-}"
  if [[ -z "$COMPOSE_FILE" ]]; then
    echo "skipping unknown stack: $STACK" >&2
    continue
  fi

  echo ""
  echo "############################################"
  echo "# STACK $STACK ($COMPOSE_FILE)"
  echo "############################################"

  echo "[matrix] spinning up $STACK..."
  docker compose -f "$COMPOSE_DIR/$COMPOSE_FILE" down -v 2>/dev/null || true
  docker compose -f "$COMPOSE_DIR/$COMPOSE_FILE" up -d

  # Wait up to 3 min for healthy
  echo "[matrix] waiting for $STACK to become healthy..."
  for i in $(seq 1 36); do
    if docker compose -f "$COMPOSE_DIR/$COMPOSE_FILE" ps --format '{{.Health}}' 2>/dev/null | grep -q healthy; then
      echo "[matrix] $STACK healthy after ${i}*5=$((i*5))s"
      break
    fi
    sleep 5
  done

  # Run progressive sweep
  "$SCRIPT_DIR/run-progressive.sh" "$STACK" "$SCALES" "$USERS_PER_REALM" "$CLIENTS_PER_REALM" "$IDPS_PER_REALM" \
    || echo "[matrix] WARNING: $STACK sweep had errors (continuing)"

  # Tear down to free resources before next stack
  echo "[matrix] tearing down $STACK..."
  docker compose -f "$COMPOSE_DIR/$COMPOSE_FILE" down -v 2>/dev/null || true
done

echo ""
echo "############################################"
echo "# MATRIX COMPLETE"
echo "############################################"
DATE=$(date +%Y-%m-%d)
echo "Results: $SCRIPT_DIR/../results/$DATE/"
find "$SCRIPT_DIR/../results/$DATE/" -name "*.json" | sort
