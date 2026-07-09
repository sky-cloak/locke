#!/usr/bin/env bash
# upg-windowed.sh <old-image> <new-image> <label>
# Clean cross-version Locke upgrade under load, macOS-safe (bg + sleep-kill guard, no `timeout`).
# Resets the DB so the OLD version owns a fresh schema, deploys OLD on kc-mig (Locke/redis env),
# then rolls to NEW mid-stream under short windowed load. Prints a per-window OK/HANG timeline
# plus per-pod versions before/after so the upgrade's impact + success is visible.
set -uo pipefail
NS=locke-bench; APP=kc-mig; OLD="$1"; NEW="$2"; LABEL="${3:-upgrade}"
win(){ kubectl exec -n $NS loadgen -- sh /run-kcb.sh "$APP" 50 15 > /tmp/uwin.out 2>&1 & local rp=$!
  ( sleep 45; kubectl exec -n $NS loadgen -- sh -c 'pkill -9 -f io.gatling 2>/dev/null' >/dev/null 2>&1 ) & local g=$!
  wait $rp 2>/dev/null; kill $g 2>/dev/null
  grep -E 'KCBRESULT' /tmp/uwin.out | sed 's/req=.*//' || echo "HANG (no result this window)"
  kubectl exec -n $NS loadgen -- sh -c 'pkill -9 -f io.gatling 2>/dev/null' >/dev/null 2>&1; }
vers(){ for p in ${APP}-0 ${APP}-1 ${APP}-2; do echo "  $p ver=$(kubectl exec -n $NS $p -- sh -c '/opt/keycloak/bin/kc.sh --version 2>/dev/null|head -1' 2>/dev/null|sed 's/Keycloak - Version //')"; done; }
echo "############ UPGRADE [$LABEL]: $OLD -> $NEW ############ $(date -u +%H:%M:%SZ)"
# --- reset DB so OLD owns a fresh schema ---
kubectl scale sts/$APP -n $NS --replicas=0 >/dev/null 2>&1
i=0; until [ "$(kubectl get pods -n $NS -l app=$APP --no-headers 2>/dev/null|wc -l|tr -d ' ')" = "0" ] || [ $i -ge 30 ]; do sleep 5; i=$((i+1)); done
kubectl exec -n $NS deploy/postgres -- psql -U keycloak -d postgres -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='keycloak' AND pid<>pg_backend_pid();" >/dev/null 2>&1
kubectl exec -n $NS deploy/postgres -- psql -U keycloak -d postgres -c "DROP DATABASE IF EXISTS keycloak;" >/dev/null 2>&1
kubectl exec -n $NS deploy/postgres -- psql -U keycloak -d postgres -c "CREATE DATABASE keycloak OWNER keycloak;" >/dev/null 2>&1
kubectl exec -n $NS deploy/redis -- redis-cli flushall >/dev/null 2>&1 || true
echo "[reset] fresh keycloak DB"
# --- deploy OLD ---
kubectl set image sts/$APP keycloak="$OLD" -n $NS >/dev/null 2>&1
kubectl scale sts/$APP -n $NS --replicas=3 >/dev/null 2>&1
kubectl delete pod -n $NS -l app=$APP --force --grace-period=0 >/dev/null 2>&1
i=0; until [ "$(kubectl get sts $APP -n $NS -o jsonpath='{.status.readyReplicas}' 2>/dev/null)" = "3" ] || [ $i -ge 48 ]; do sleep 10; i=$((i+1)); done
echo "[old] $APP ready=$(kubectl get sts $APP -n $NS -o jsonpath='{.status.readyReplicas}')/3 on:"; vers
kubectl exec -n $NS loadgen -- sh -c "TOK=\$(curl -s -d 'client_id=admin-cli&username=admin&password=admin&grant_type=password' http://$APP:8080/realms/master/protocol/openid-connect/token | sed -E 's/.*\"access_token\":\"([^\"]+)\".*/\1/'); curl -s -o /dev/null -w '  realm_import=%{http_code}\n' -X POST -H \"Authorization: Bearer \$TOK\" -H 'Content-Type: application/json' -d @/realm.json http://$APP:8080/admin/realms" 2>&1 | tail -1
# --- windowed upgrade: apply NEW at window 3 ---
START=$(date +%s); flip=0
for w in $(seq 1 16); do
  if [ $w -eq 3 ]; then echo ">>> [$(date -u +%H:%M:%SZ)] SET IMAGE -> $NEW (rolling upgrade)"; kubectl set image sts/$APP keycloak="$NEW" -n $NS >/dev/null 2>&1; flip=1; fi
  ur=$(kubectl get sts $APP -n $NS -o jsonpath='{.status.updatedReplicas}/{.status.readyReplicas}' 2>/dev/null)
  printf "[w%-2s t+%ss upd/rdy=%s] " "$w" "$(( $(date +%s)-START ))" "$ur"
  win
  if [ $flip -eq 1 ] && [ "$ur" = "3/3" ] && [ $w -ge 9 ]; then echo "(stable on NEW; stopping)"; break; fi
done
echo "[after] versions:"; vers
echo "UPG-DONE[$LABEL] $(date -u +%H:%M:%SZ)"
