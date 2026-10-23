#!/usr/bin/env bash
# Thin wrapper around Gatling that uses keycloak-benchmark's scenarios but
# bypasses kcb.sh (which has GNU-vs-BSD incompat issues with paste/date on macOS
# and a flawed CLASSPATH discovery that misses Gatling).
#
# Usage:
#   ./kcb-run.sh <scenario> <port> <users-per-sec> <measurement-seconds> [extra -D opts]
#
# Example:
#   ./kcb-run.sh AuthorizationCode 18080  5 60
#   ./kcb-run.sh AuthorizationCode 18081  5 60
#
set -euo pipefail

SCENARIO="${1:?scenario class short name (e.g. AuthorizationCode)}"
PORT="${2:?port}"
USERS_PER_SEC="${3:-5}"
MEASUREMENT="${4:-60}"
shift 4 || true
EXTRA="$@"

cd "$(dirname "$0")"

KCB_DIR="kcb/keycloak-benchmark-999.0.0-SNAPSHOT"
JAR="$KCB_DIR/lib/keycloak-benchmark-999.0.0-SNAPSHOT.jar"
if [ ! -f "$JAR" ]; then
  echo "ERROR: $JAR not found. Build it: cd kcb/keycloak-benchmark && ./mvnw -pl benchmark -am -DskipTests package"
  exit 1
fi

# Use 0.0.0.0 not localhost — keycloak-benchmark refuses localhost in KC26 due
# to secure-cookie semantics (Gatling won't send them).
SERVER_URL="http://0.0.0.0:${PORT}"
REALM="bench-kcb"
CLIENT_ID="client-0"
CLIENT_SECRET="client-0-secret"
REDIRECT_URI="${SERVER_URL}/realms/${REALM}/account/"

OUT_DIR="results/kcb-${SCENARIO}-${PORT}-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT_DIR"

echo "==> kcb scenario=$SCENARIO  port=$PORT  users-per-sec=$USERS_PER_SEC  measurement=${MEASUREMENT}s"
echo "==> output -> $OUT_DIR"

# Dataset properties — match the realm-bench-kcb.json layout (100 users, 1 client)
java -server -Xmx1G \
  -Dgatling.charting.indicators.percentile3=99 \
  -Dgatling.charting.indicators.percentile4=99.9 \
  -Dgatling.core.runDescription="$SCENARIO @ $PORT" \
  -Dserver-url="$SERVER_URL" \
  -Drealm-name="$REALM" \
  -Dusername-prefix="user-" \
  -Dusers-per-realm=100 \
  -Dclient-id="$CLIENT_ID" \
  -Dclient-secret="$CLIENT_SECRET" \
  -Dclient-redirect-uri="$REDIRECT_URI" \
  -Dscope="openid profile" \
  -Dusers-per-sec="$USERS_PER_SEC" \
  -Dmeasurement="$MEASUREMENT" \
  -Dramp-up=5 \
  -Dwarm-up=5 \
  -Dlogout-percentage=60 \
  -Dnumber-of-refreshes=5 \
  -Drefresh-period=30 \
  -Drefresh-token-period=30 \
  -Dsla-error-percentage=1 \
  $EXTRA \
  -cp "$JAR" \
  io.gatling.app.Gatling \
    -rf "$OUT_DIR" \
    -s "keycloak.scenario.authentication.${SCENARIO}" \
    > "$OUT_DIR/gatling-stdout.log" 2>&1 || {
        echo "Gatling run failed; tail of output:"
        tail -40 "$OUT_DIR/gatling-stdout.log"
        exit 1
    }

# Find the per-run subdirectory Gatling created and surface its key numbers
RUN_DIR=$(ls -t "$OUT_DIR" | grep -v "\.log$" | head -1)
echo "==> done. detailed report:  $OUT_DIR/$RUN_DIR/index.html"
if [ -f "$OUT_DIR/$RUN_DIR/js/stats.json" ]; then
  python3 - "$OUT_DIR/$RUN_DIR/js/stats.json" <<'PY'
import json, sys
stats = json.load(open(sys.argv[1]))
def show(node, indent=0):
    if 'name' in node and 'stats' in node:
        s = node['stats']
        n = node['name']
        rps = s.get('meanNumberOfRequestsPerSecond', {}).get('total', 0)
        ko = s.get('numberOfRequests', {}).get('ko', 0)
        ok = s.get('numberOfRequests', {}).get('ok', 0)
        m = s.get('meanResponseTime', {}).get('total', 0)
        p95 = s.get('percentiles3', {}).get('total', 0)
        p99 = s.get('percentiles4', {}).get('total', 0)
        print(f"  {' '*indent}{n}: ok={ok} ko={ko} rps={rps:.1f} mean={m:.0f}ms p95={p95:.0f}ms p99={p99:.0f}ms")
show(stats)
for c in stats.get('contents', {}).values():
    show(c, 2)
PY
fi
