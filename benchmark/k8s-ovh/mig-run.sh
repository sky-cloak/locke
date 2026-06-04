#!/usr/bin/env bash
# mig-run.sh — same-version Keycloak->Locke migration under load (no timeout cmd; hang-guarded).
# kc-mig starts on vanilla 26.6.1 (Infinispan); at T+30 we apply mig-locke.yaml which rolls the
# StatefulSet to Locke 26.6.1 (Redis) — image + cache config change, SAME KC version (identical DB
# schema). Measures failures/latency through the cache-backend swap + confirms the end state.
set -uo pipefail
NS=locke-bench; APP=kc-mig
say(){ echo; echo "==== $* ==== $(date -u +%H:%M:%SZ)"; }
kcb(){ kubectl exec -n $NS loadgen -- sh /run-kcb.sh "$APP" "$1" "$2" 2>&1 | grep -E 'KCBRESULT|KCBOP'; }
vers(){ for p in ${APP}-0 ${APP}-1 ${APP}-2; do echo "  $p: img=$(kubectl get pod $p -n $NS -o jsonpath='{.spec.containers[0].image}' 2>/dev/null | sed 's#.*/##')"; done; }

say "BASELINE (vanilla/Infinispan 26.6.1)"; vers
kcb 60 40

say "MIGRATION under load: apply Locke (Redis) at T+30, sustained 200s load"
kubectl exec -n $NS loadgen -- sh /run-kcb.sh "$APP" 60 200 > /tmp/mig.out 2>&1 & RP=$!
( sleep 300; kubectl exec -n $NS loadgen -- sh -c 'pkill -9 -f io.gatling 2>/dev/null' >/dev/null 2>&1 ) & GUARD=$!
sleep 30
echo ">>> [$(date -u +%H:%M:%SZ)] apply mig-locke.yaml (roll Infinispan -> Redis)"
kubectl apply -f benchmark/k8s-ovh/mig-locke.yaml >/dev/null 2>&1
T0=$(date +%s)
( while kill -0 $RP 2>/dev/null; do
    echo "  [t+$(( $(date +%s)-T0+30 ))s upd/rdy=$(kubectl get sts $APP -n $NS -o jsonpath='{.status.updatedReplicas}/{.status.readyReplicas}' 2>/dev/null)]"; sleep 15
  done ) &
WATCH=$!
wait $RP 2>/dev/null; kill $GUARD $WATCH 2>/dev/null
echo "migration-window load result:"; grep -E 'KCBRESULT' /tmp/mig.out || echo "  HUNG/none (see /tmp/mig.out)"
kubectl rollout status sts/$APP -n $NS --timeout=180s 2>&1 | tail -1
say "END STATE (should be Locke/Redis 26.6.1)"; vers
kubectl logs ${APP}-0 -n $NS 2>/dev/null | grep -iE "Redis topology|Redisson client init|Profile prod" | tail -2

say "POST-MIGRATION confirm load (Locke)"
kubectl exec -n $NS loadgen -- sh -c 'pkill -9 -f io.gatling 2>/dev/null' >/dev/null 2>&1; sleep 5
kcb 60 40
echo "MIG-DONE $(date -u +%H:%M:%SZ)"
