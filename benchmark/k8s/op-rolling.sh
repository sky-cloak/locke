#!/usr/bin/env bash
# op-rolling.sh <app>  : sustained login load while a rolling restart cycles all 3 pods
APP="$1"; DIR="benchmark/k8s/results/$(date +%F)/op-rolling"; mkdir -p "$DIR/logs"
echo "[roll] $APP: login load 180s; rollout restart at T+30"
kubectl exec -n locke-bench loadgen -- sh /run-kcb.sh "$APP" 60 180 > "/tmp/roll_$APP.out" 2>&1 &
P=$!; sleep 30
echo "[roll] $(date +%T) rollout restart $APP"; t0=$(date +%s)
kubectl rollout restart statefulset/$APP -n locke-bench >/dev/null 2>&1
kubectl rollout status statefulset/$APP -n locke-bench --timeout=200s >/dev/null 2>&1
echo "[roll] rollout completed in $(( $(date +%s)-t0 ))s"
wait $P
echo "=== $APP rolling-restart result ==="; grep KCBRESULT "/tmp/roll_$APP.out"
for p in ${APP}-0 ${APP}-1 ${APP}-2; do kubectl logs $p -n locke-bench --since=240s > "$DIR/logs/$APP-$p.log" 2>&1; done
