#!/usr/bin/env bash
# op-scale.sh <app> : login load while scaling 3->2 (scale-in) then 2->3 (scale-out), time join
APP="$1"; DIR="benchmark/k8s/results/$(date +%F)/op-scale"; mkdir -p "$DIR/logs"
echo "[scale] $APP: login load 200s; scale 3->2 @T+30, 2->3 @T+90 (time the join)"
kubectl exec -n locke-bench loadgen -- sh /run-kcb.sh "$APP" 60 200 > "/tmp/scale_$APP.out" 2>&1 &
P=$!; sleep 30
echo "[scale] $(date +%T) scale-in 3->2"; kubectl scale statefulset/$APP -n locke-bench --replicas=2 >/dev/null 2>&1
sleep 60
echo "[scale] $(date +%T) scale-out 2->3 (timing join)"; t0=$(date +%s)
kubectl scale statefulset/$APP -n locke-bench --replicas=3 >/dev/null 2>&1
i=0; until [ "$(kubectl get sts $APP -n locke-bench -o jsonpath='{.status.readyReplicas}' 2>/dev/null)" = "3" ] || [ $i -ge 30 ]; do sleep 3; i=$((i+1)); done
echo "[scale] $APP new pod ready+joined in $(( $(date +%s)-t0 ))s"
wait $P
echo "=== $APP scale result ==="; grep KCBRESULT "/tmp/scale_$APP.out"
