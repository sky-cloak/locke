#!/bin/sh
# run-kcb.sh <app> <users-per-sec> [measurement_s]
# Runs the keycloak-benchmark Gatling AuthorizationCode scenario against http://<app>:8080
# (realm bench-kcb, 100 users, client-0) and prints a single machine-parseable line:
#   KCBRESULT ups=<N> rps=<X> p95=<ms> p99=<ms> failed=<pct>% req=[OK=<n> KO=<n>]
# The Gatling uber-jar lives at /kcb.jar (baked into the loadgen image).
APP="$1"; UPS="$2"; MEAS="${3:-60}"
RF="/tmp/kcb-${APP}-${UPS}-$(date +%s)"
mkdir -p "$RF"
java -server -Xmx3g \
  -Dserver-url=http://${APP}:8080 \
  -Drealm-name=bench-kcb \
  -Dusername-prefix=user- \
  -Dusers-per-realm=100 \
  -Dclient-id=client-0 \
  -Dclient-secret=client-0-secret \
  -Dclient-redirect-uri=http://${APP}:8080/realms/bench-kcb/account/ \
  -Dscope="openid profile" \
  -Dusers-per-sec=${UPS} \
  -Dmeasurement=${MEAS} \
  -Dramp-up=5 -Dwarm-up=5 \
  -Dlogout-percentage=60 \
  -Dnumber-of-refreshes=5 \
  -Drefresh-period=30 \
  -Drefresh-token-period=30 \
  -Dsla-error-percentage=1 \
  -cp /kcb.jar io.gatling.app.Gatling -rf "$RF" -s keycloak.scenario.authentication.AuthorizationCode \
  > "$RF/console.log" 2>&1
ST=$(find "$RF" -name stats.json -path '*/js/*' 2>/dev/null | head -1)
if [ -z "$ST" ]; then
  echo "KCBRESULT ups=$UPS ERROR=no-stats"
  tail -25 "$RF/console.log"
  exit 1
fi
# aggregate "All Requests" stats
jq -r --arg ups "$UPS" '.stats |
  (.numberOfRequests.total) as $t |
  (if $t==0 then 0 else (.numberOfRequests.ko/$t*10000|round/100) end) as $fp |
  "KCBRESULT ups=\($ups) rps=\(.meanNumberOfRequestsPerSecond.total) p95=\(.percentiles3.total) p99=\(.percentiles4.total) failed=\($fp)% req=[OK=\(.numberOfRequests.ok) KO=\(.numberOfRequests.ko)]"' "$ST"
# per-operation p99 (request-level), for the latency breakdown table
jq -r '.contents[]? | .stats | "KCBOP name=\"\(.name)\" p99=\(.percentiles4.total) ok=\(.numberOfRequests.ok) ko=\(.numberOfRequests.ko)"' "$ST" 2>/dev/null
