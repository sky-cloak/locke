#!/usr/bin/env bash
# resilience.sh <app> <ups> <killAtSec> <pod...>   run a 150s load, kill pod(s) mid-run
APP="$1"; UPS="$2"; KILLAT="$3"; shift 3; KILLS="$*"
DIR="benchmark/k8s/results/$(date +%F)/resilience"; mkdir -p "$DIR"
TAG="${APP}-$(echo $KILLS | wc -w | tr -d ' ')down"
OUT="$DIR/$TAG.out"
echo "[resil] $APP ups=$UPS kill@${KILLAT}s pods=[$KILLS]"
kubectl exec -n locke-bench loadgen -- sh /run-kcb.sh "$APP" "$UPS" 150 > "$OUT" 2>&1 &
P=$!
sleep "$KILLAT"
for pod in $KILLS; do echo "[resil] $(date +%T) KILL $pod"; kubectl delete pod "$pod" -n locke-bench --grace-period=0 --force >/dev/null 2>&1; done
echo "[resil] waiting for load to finish..."; wait $P
echo "=== RESULT $TAG (killed [$KILLS] at T+${KILLAT}s during ${UPS}ups/150s) ==="
grep KCBRESULT "$OUT" || tail -5 "$OUT"
