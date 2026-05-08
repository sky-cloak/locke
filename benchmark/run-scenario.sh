#!/usr/bin/env bash
# Run one benchmark scenario end-to-end:
#   1. up the compose stack
#   2. wait for keycloak healthcheck
#   3. warm up
#   4. run k6 load test, capture JSON summary
#   5. tear down
set -euo pipefail

SCENARIO="${1:?usage: run-scenario.sh <A|B|C|D> <port> [vus] [duration]}"
PORT="${2:?}"
VUS="${3:-50}"
DURATION="${4:-3m}"

cd "$(dirname "$0")"
COMPOSE_FILE="compose/${SCENARIO}-*.yml"
COMPOSE_FILE=$(ls $COMPOSE_FILE | head -1)
RESULTS_DIR="results/${SCENARIO}-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$RESULTS_DIR"

echo "==> Scenario $SCENARIO  | compose=$COMPOSE_FILE  | port=$PORT  | vus=$VUS  | duration=$DURATION"
echo "==> Results -> $RESULTS_DIR"

# Tear any prior run of THIS scenario only (other scenarios may share images but compose project names differ)
docker compose -f "$COMPOSE_FILE" down -v --remove-orphans >/dev/null 2>&1 || true

echo "==> Bringing up stack..."
docker compose -f "$COMPOSE_FILE" up -d

echo "==> Waiting for keycloak (max 4 min)..."
for i in $(seq 1 80); do
    if curl -fsS "http://localhost:${PORT}/realms/master" >/dev/null 2>&1; then
        echo "    keycloak ready after ${i}*3s"
        break
    fi
    sleep 3
    if [ "$i" = "80" ]; then
        echo "!!! keycloak failed to come up. Logs:"
        docker compose -f "$COMPOSE_FILE" logs --tail=50 keycloak
        docker compose -f "$COMPOSE_FILE" down -v --remove-orphans
        exit 1
    fi
done

# Wait for the imported realm too
for i in $(seq 1 30); do
    if curl -fsS "http://localhost:${PORT}/realms/bench/.well-known/openid-configuration" >/dev/null 2>&1; then
        echo "    bench realm ready"
        break
    fi
    sleep 2
done

# Capture container info
docker compose -f "$COMPOSE_FILE" ps > "$RESULTS_DIR/containers.txt"
docker stats --no-stream > "$RESULTS_DIR/stats-pre.txt" 2>&1 || true

echo "==> Warmup (30s, 5 VUs)..."
BASE_URL="http://localhost:${PORT}" VUS=5 DURATION=30s \
    k6 run --quiet --no-summary k6/auth-flow.js >/dev/null 2>&1 || true

echo "==> Cooldown 5s..."
sleep 5

echo "==> Load test (${VUS} VUs, ${DURATION})..."
BASE_URL="http://localhost:${PORT}" VUS="$VUS" DURATION="$DURATION" \
    k6 run --summary-export="$RESULTS_DIR/summary.json" \
           --out "json=$RESULTS_DIR/raw.json" \
           k6/auth-flow.js | tee "$RESULTS_DIR/k6-output.txt"

docker stats --no-stream > "$RESULTS_DIR/stats-post.txt" 2>&1 || true
docker compose -f "$COMPOSE_FILE" logs --tail=200 keycloak > "$RESULTS_DIR/kc-logs.txt" 2>&1 || true

echo "==> Tearing down stack..."
docker compose -f "$COMPOSE_FILE" down -v --remove-orphans

echo "==> Done. Results in: $RESULTS_DIR"
