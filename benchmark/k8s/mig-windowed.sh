#!/usr/bin/env bash
# mig-windowed.sh — quantify the Keycloak->Locke cutover window with SHORT, hang-guarded load
# windows (no `timeout` cmd; uses bg + sleep-kill guard, which works on macOS). Rolls kc-mig back
# to vanilla first, then applies Locke mid-stream and prints a per-window OK/fail/HANG timeline.
set -uo pipefail
NS=locke-bench; APP=kc-mig
win(){ # one short load window; force-kills a hung gatling after 45s
  kubectl exec -n $NS loadgen -- sh /run-kcb.sh "$APP" 50 15 > /tmp/win.out 2>&1 & local rp=$!
  ( sleep 45; kubectl exec -n $NS loadgen -- sh -c 'pkill -9 -f io.gatling 2>/dev/null' >/dev/null 2>&1 ) & local g=$!
  wait $rp 2>/dev/null; kill $g 2>/dev/null
  grep -E 'KCBRESULT' /tmp/win.out | sed 's/req=.*//' || echo "HANG (no result this window)"
  kubectl exec -n $NS loadgen -- sh -c 'pkill -9 -f io.gatling 2>/dev/null' >/dev/null 2>&1
}
echo "### roll kc-mig BACK to vanilla/Infinispan ### $(date -u +%H:%M:%SZ)"
kubectl apply -f benchmark/k8s/mig-vanilla.yaml >/dev/null 2>&1
kubectl rollout status sts/$APP -n $NS --timeout=240s 2>&1 | tail -1
for p in ${APP}-0 ${APP}-1 ${APP}-2; do echo "  $p img=$(kubectl get pod $p -n $NS -o jsonpath='{.spec.containers[0].image}' 2>/dev/null | sed 's#.*/##')"; done
echo "### WINDOWED MIGRATION: apply Locke at window 3 ###"
START=$(date +%s); flip=0
for w in $(seq 1 16); do
  if [ $w -eq 3 ]; then echo ">>> [$(date -u +%H:%M:%SZ)] APPLY mig-locke.yaml (Infinispan -> Redis)"; kubectl apply -f benchmark/k8s/mig-locke.yaml >/dev/null 2>&1; flip=1; fi
  ur=$(kubectl get sts $APP -n $NS -o jsonpath='{.status.updatedReplicas}/{.status.readyReplicas}' 2>/dev/null)
  printf "[w%-2s t+%ss upd/rdy=%s] " "$w" "$(( $(date +%s)-START ))" "$ur"
  win
  if [ $flip -eq 1 ] && [ "$ur" = "3/3" ] && [ $w -ge 9 ]; then echo "(stable on Locke; stopping)"; break; fi
done
echo "MIGWIN-DONE $(date -u +%H:%M:%SZ)"
