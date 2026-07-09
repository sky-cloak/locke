#!/usr/bin/env bash
# op-upgrade2.sh <app> <old-26.3.5-image> <new-26.6.1-image> <other-app-to-park>
# Clean, hang-proof rolling version upgrade UNDER LOAD.
#  * real DB reset (scale all KC to 0, terminate connections, drop/create) so the OLD
#    version owns a fresh schema -> a genuine 26.3.5 -> 26.6.1 migration path.
#  * load is a series of short, timeout-guarded windows; the image flip fires mid-stream.
#    A hung window (e.g. Infinispan JGroups-version stall) costs one 25s window, not the run.
# Prints a per-window timeline so the upgrade's impact + recovery is visible.
set -uo pipefail
APP="$1"; OLD="$2"; NEW="$3"; OTHER="$4"; NS=locke-bench
echo "############ CLEAN UPGRADE $APP : $OLD -> $NEW ############ $(date -u +%H:%M:%SZ)"
# --- full reset ---
kubectl scale sts/$APP sts/$OTHER -n $NS --replicas=0 >/dev/null 2>&1
i=0; until [ "$(kubectl get pods -n $NS -l "app in (kc-a3,kc-b3)" --no-headers 2>/dev/null | wc -l | tr -d ' ')" = "0" ] || [ $i -ge 30 ]; do sleep 5; i=$((i+1)); done
kubectl exec -n $NS deploy/postgres -- psql -U keycloak -d postgres -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='keycloak' AND pid<>pg_backend_pid();" >/dev/null 2>&1
kubectl exec -n $NS deploy/postgres -- psql -U keycloak -d postgres -c "DROP DATABASE IF EXISTS keycloak;" >/dev/null 2>&1
kubectl exec -n $NS deploy/postgres -- psql -U keycloak -d postgres -c "CREATE DATABASE keycloak OWNER keycloak;" >/dev/null 2>&1
kubectl exec -n $NS deploy/redis -- redis-cli flushall >/dev/null 2>&1 || true
echo "[reset] fresh keycloak DB; both stacks parked"
# --- deploy OLD ---
kubectl set image sts/$APP keycloak="$OLD" -n $NS >/dev/null 2>&1
kubectl scale sts/$APP -n $NS --replicas=3 >/dev/null 2>&1
kubectl delete pod -n $NS -l app=$APP --force --grace-period=0 >/dev/null 2>&1
i=0; until [ "$(kubectl get sts $APP -n $NS -o jsonpath='{.status.readyReplicas}' 2>/dev/null)" = "3" ] || [ $i -ge 48 ]; do sleep 10; i=$((i+1)); done
echo "[old] $APP ready=$(kubectl get sts $APP -n $NS -o jsonpath='{.status.readyReplicas}')/3"
kubectl logs ${APP}-0 -n $NS 2>/dev/null | grep -iE "Keycloak 26|Profile prod" | tail -1
for p in ${APP}-0 ${APP}-1 ${APP}-2; do echo "  before: $p $(kubectl exec -n $NS $p -- sh -c '/opt/keycloak/bin/kc.sh --version 2>/dev/null | head -1' 2>/dev/null)"; done
# import realm
kubectl exec -n $NS loadgen -- sh -c "TOK=\$(curl -s -d 'client_id=admin-cli&username=admin&password=admin&grant_type=password' http://$APP:8080/realms/master/protocol/openid-connect/token | sed -E 's/.*\"access_token\":\"([^\"]+)\".*/\1/'); curl -s -o /dev/null -w '  realm_import=%{http_code}\n' -X POST -H \"Authorization: Bearer \$TOK\" -H 'Content-Type: application/json' -d @/realm.json http://$APP:8080/admin/realms" 2>&1 | tail -1
# --- windowed load with mid-stream flip ---
echo "[load] windowed 40ups/25s; flip at ~t+60s"
START=$(date +%s); flip=0; T0=0
while [ $(( $(date +%s) - START )) -lt 360 ]; do
  el=$(( $(date +%s) - START ))
  if [ $el -ge 60 ] && [ $flip -eq 0 ]; then
    echo "  >>> [t+${el}s $(date -u +%H:%M:%SZ)] TRIGGER rolling upgrade -> $NEW"
    kubectl set image sts/$APP keycloak="$NEW" -n $NS >/dev/null 2>&1; flip=1; T0=$(date +%s)
  fi
  upd=$(kubectl get sts $APP -n $NS -o jsonpath='{.status.updatedReplicas}/{.status.readyReplicas}' 2>/dev/null)
  printf "  [t+%ss upd/rdy=%s] " "$el" "$upd"
  timeout 70 kubectl exec -n $NS loadgen -- sh /run-kcb.sh "$APP" 40 25 2>&1 | grep -E 'KCBRESULT' || echo "WINDOW-HANG/TIMEOUT (disruption)"
  kubectl exec -n $NS loadgen -- sh -c 'pkill -9 -f io.gatling.app.Gatling 2>/dev/null; pkill -9 -f run-kcb.sh 2>/dev/null' >/dev/null 2>&1
  # stop ~90s after rollout completes
  if [ $flip -eq 1 ] && [ "$upd" = "3/3" ]; then echo "  [rollout complete at t+${el}s; window ${el}]"; break; fi
done
echo "[after] rollout status:"; kubectl rollout status sts/$APP -n $NS --timeout=120s 2>&1 | tail -1
for p in ${APP}-0 ${APP}-1 ${APP}-2; do echo "  after: $p $(kubectl exec -n $NS $p -- sh -c '/opt/keycloak/bin/kc.sh --version 2>/dev/null | head -1' 2>/dev/null)"; done
[ $T0 -ne 0 ] && echo "[upgrade-window: trigger->all-updated = $(( $(date +%s) - T0 ))s]"
echo "############ $APP CLEAN UPGRADE DONE ############ $(date -u +%H:%M:%SZ)"
