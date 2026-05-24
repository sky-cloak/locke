#!/usr/bin/env bash
# run-sweep.sh <app kc-a3|kc-b3> <ups...>   (host = same as app)
APP="$1"; shift; UPS_LIST="$*"
DIR="benchmark/k8s-ovh/results/$(date +%F)/$APP"; mkdir -p "$DIR"
SUM="$DIR/summary.txt"; : > "$SUM"
for ups in $UPS_LIST; do
  echo "===== $APP @ $ups ups =====" | tee -a "$SUM"
  kubectl exec -n locke-bench loadgen -- sh /run-kcb.sh "$APP" "$ups" 60 2>&1 | grep KCBRESULT | tee -a "$SUM"
  bash benchmark/k8s-ovh/snapshot.sh "$APP" "after-$ups" 2>&1 | tee -a "$SUM"
  echo "" | tee -a "$SUM"
done
echo "DONE $APP -> $SUM"
