#!/usr/bin/env bash
# write-resil.sh <app> <killAtSec> <pod...>
APP="$1"; KILLAT="$2"; shift 2; KILLS="$*"
DIR="benchmark/k8s/results/$(date +%F)/writeresil"; mkdir -p "$DIR/logs"
TAG="${APP}-$(echo $KILLS|wc -w|tr -d ' ')down"; OUT="/tmp/wr_$TAG.log"
echo "[wr] $APP write-load 150s, kill [$KILLS] at T+${KILLAT}s into realm master"
kubectl exec -n locke-bench loadgen -- sh /writeloop.sh "$APP" master 150 "$OUT" &
P=$!; t0=$(date +%s)
sleep "$KILLAT"; KT=$(date +%s)
for pod in $KILLS; do echo "[wr] $(date +%T) KILL $pod"; kubectl delete pod "$pod" -n locke-bench --grace-period=0 --force >/dev/null 2>&1; done
wait $P
kubectl cp "locke-bench/loadgen:$OUT" "$DIR/$TAG.log" 2>/dev/null
# tally
awk -v kt="$KT" -v t0="$t0" '
 {tot++; split($3,a," "); code=$3; t=$4; if($3<200||$3>=300)fail++; if($1>=kt && $1<kt+30){w++; if($3<200||$3>=300)wf++; wl+=$4}}
 END{printf "  total=%d fail=%d (%.2f%%) | kill-window(30s): writes=%d fail=%d (%.1f%%) avg_lat=%.2fs\n",tot,fail,100*fail/tot,w,wf,(w?100*wf/w:0),(w?wl/w:0)}' "$DIR/$TAG.log"
for p in ${APP}-0 ${APP}-1 ${APP}-2; do kubectl logs $p -n locke-bench --since=200s > "$DIR/logs/$TAG-$p.log" 2>&1; done
echo "[wr] done $TAG -> $DIR/$TAG.log"
