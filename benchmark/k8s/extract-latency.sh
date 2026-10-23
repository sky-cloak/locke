#!/usr/bin/env bash
# extract-latency.sh <app kc-a3|kc-b3> <ups>  -> per-operation mean/p95/p99 (TSV)
APP="$1"; UPS="$2"; mkdir -p /tmp/gat
D=$(kubectl exec -n locke-bench loadgen -- sh -c "ls -dt /tmp/kcb-${APP}-${UPS}-*/ 2>/dev/null | head -1" | tr -d '\r')
SJ=$(kubectl exec -n locke-bench loadgen -- sh -c "ls -t ${D}*/js/stats.json 2>/dev/null | head -1" | tr -d '\r')
[ -z "$SJ" ] && { echo "no stats.json for $APP @ $UPS"; exit 1; }
kubectl cp "locke-bench/loadgen:${SJ}" "/tmp/gat/${APP}-${UPS}-stats.json" 2>/dev/null
python3 - "$APP" "$UPS" "/tmp/gat/${APP}-${UPS}-stats.json" <<'PY'
import json,sys
app,ups,f=sys.argv[1],sys.argv[2],sys.argv[3]
d=json.load(open(f))
def walk(n):
    s=n.get('stats',{}); nm=s.get('name')
    if nm:
        print(f"{app}\t{ups}\t{nm}\t{s['numberOfRequests']['total']}\t{s['meanResponseTime']['total']}\t{s['percentiles3']['total']}\t{s['percentiles4']['total']}")
    for v in n.get('contents',{}).values(): walk(v)
walk(d)
PY
