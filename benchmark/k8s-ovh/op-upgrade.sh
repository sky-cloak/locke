#!/usr/bin/env bash
# op-upgrade.sh <app kc-a3|kc-b3> <new-image> [users] [total_s] [trigger_at_s]
# Rolling version upgrade UNDER LOAD. The StatefulSet must already be running the
# OLD version (26.3.5). We start sustained kcb login load, then at trigger_at_s we
# `kubectl set image` to the NEW version (26.6.1) which rolls the pods one at a time
# (ordinal high->low, OrderedReady). We measure failures/latency across the whole
# window so the cost of a live cluster upgrade is captured, and snapshot per-pod
# image + KC version + logs as evidence.
set -uo pipefail
APP="$1"; NEW="$2"; USERS="${3:-80}"; TOTAL="${4:-360}"; TRIG="${5:-40}"
NS=locke-bench
DIR="benchmark/k8s-ovh/results/$(date +%F)/upgrade-$APP"; mkdir -p "$DIR/logs"
echo "=== UPGRADE $APP -> $NEW | ${USERS}u for ${TOTAL}s, trigger at +${TRIG}s ==="
echo "--- before: pod images / versions ---" | tee "$DIR/before.txt"
kubectl get pods -n $NS -l app=$APP -o jsonpath='{range .items[*]}{.metadata.name}{"  "}{.spec.containers[0].image}{"\n"}{end}' | tee -a "$DIR/before.txt"
for p in $(kubectl get pods -n $NS -l app=$APP -o jsonpath='{.items[*].metadata.name}'); do
  v=$(kubectl exec -n $NS "$p" -- sh -c 'cat /opt/keycloak/version.txt 2>/dev/null || /opt/keycloak/bin/kc.sh --version 2>/dev/null | head -1'); echo "$p: $v" | tee -a "$DIR/before.txt"
done
# sustained load spanning the whole upgrade
kubectl exec -n $NS loadgen -- sh /run-kcb.sh "$APP" "$USERS" "$TOTAL" > "$DIR/kcb.out" 2>&1 &
LP=$!
echo "[t+0] load started (pid $LP). waiting ${TRIG}s before upgrade trigger..."
sleep "$TRIG"
echo "[t+${TRIG}] $(date +%T) TRIGGER upgrade: set image -> $NEW"
T0=$(date +%s)
kubectl set image statefulset/$APP keycloak="$NEW" -n $NS
# watch the rollout while load continues
( while kill -0 $LP 2>/dev/null; do
    rr=$(kubectl get sts $APP -n $NS -o jsonpath='{.status.updatedReplicas}/{.status.readyReplicas}' 2>/dev/null)
    echo "  [t+$(( $(date +%s)-T0+TRIG ))s] updated/ready=$rr" ; sleep 10
  done ) | tee "$DIR/rollout.log" &
wait $LP
ROLLDONE=$(date +%s)
echo "[done] load finished. rollout state:"
kubectl rollout status statefulset/$APP -n $NS --timeout=300s 2>&1 | tail -2 | tee "$DIR/rolloutstatus.txt"
echo "--- after: pod images / versions ---" | tee "$DIR/after.txt"
kubectl get pods -n $NS -l app=$APP -o jsonpath='{range .items[*]}{.metadata.name}{"  "}{.spec.containers[0].image}{"\n"}{end}' | tee -a "$DIR/after.txt"
for p in $(kubectl get pods -n $NS -l app=$APP -o jsonpath='{.items[*].metadata.name}'); do
  v=$(kubectl exec -n $NS "$p" -- sh -c 'cat /opt/keycloak/version.txt 2>/dev/null || /opt/keycloak/bin/kc.sh --version 2>/dev/null | head -1'); echo "$p: $v" | tee -a "$DIR/after.txt"
done
echo "=== RESULT ($APP upgrade) ==="; grep KCBRESULT "$DIR/kcb.out" | tee "$DIR/result.txt"
echo "=== upgrade-window seconds (trigger->all-ready): $(( ROLLDONE - T0 ))s ==="  | tee -a "$DIR/result.txt"
for p in $(kubectl get pods -n $NS -l app=$APP -o jsonpath='{.items[*].metadata.name}'); do kubectl logs $p -n $NS --tail=300 > "$DIR/logs/$p.log" 2>&1; done
echo "evidence -> $DIR"
