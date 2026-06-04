#!/usr/bin/env bash
# matrix-all.sh — full production benchmark, runs ENTIRELY in-cluster (runner pod).
# Phase A: vanilla (kc-a3) operational matrix.  Phase B: Locke (kc-b3) operational matrix.
# Phase C: rolling version upgrade 26.3.5 -> 26.6.1 under load, for BOTH stacks.
# Stacks run one at a time (the idle one is scaled to 0) so load numbers are clean.
# Everything is echoed to stdout (this pod's log) which is the durable evidence channel.
set -uo pipefail
NS=locke-bench
SC=/scripts
V_OLD=ghcr.io/sky-cloak/locke-bench-vanilla:26.3.5
V_NEW=ghcr.io/sky-cloak/locke-bench-vanilla:26.6.1
L_OLD=ghcr.io/sky-cloak/locke-bench-locke:26.3.5
L_NEW=ghcr.io/sky-cloak/locke-bench-locke:26.6.1
cd /work 2>/dev/null || cd /tmp

hr(){ echo; echo "==================== $* ===================="; echo "   $(date -u +%H:%M:%SZ)"; }
ready(){ local app=$1 n=${2:-3} i=0; until [ "$(kubectl get sts $app -n $NS -o jsonpath='{.status.readyReplicas}' 2>/dev/null)" = "$n" ] || [ $i -ge 60 ]; do sleep 10; i=$((i+1)); done; echo "[$app ready=$(kubectl get sts $app -n $NS -o jsonpath='{.status.readyReplicas}')/$n after $((i*10))s]"; }
imp(){ local app=$1; kubectl exec -n $NS loadgen -- sh -c "TOK=\$(curl -s -d 'client_id=admin-cli&username=admin&password=admin&grant_type=password' http://$app:8080/realms/master/protocol/openid-connect/token | sed -E 's/.*\"access_token\":\"([^\"]+)\".*/\1/'); curl -s -o /dev/null -w 'realm_import($app)=%{http_code}\n' -X POST -H \"Authorization: Bearer \$TOK\" -H 'Content-Type: application/json' -d @/realm.json http://$app:8080/admin/realms"; }
kcb(){ kubectl exec -n $NS loadgen -- sh /run-kcb.sh "$1" "$2" "$3" 2>&1 | grep -E 'KCBRESULT|KCBOP|ERROR'; }

matrix(){  # full operational matrix for one app; $2=1 to include redis-failure
  local app=$1 redis=${2:-0}
  hr "$app PARITY"
  for u in 80 160 250; do printf "u=%s " $u; kcb "$app" "$u" 60; done
  hr "$app NODE-FAIL 1-down"; bash $SC/resilience.sh "$app" 80 45 ${app}-1; ready "$app"
  hr "$app NODE-FAIL 2-down"; bash $SC/resilience.sh "$app" 80 45 ${app}-1 ${app}-2; ready "$app"
  hr "$app ROLLING RESTART"; bash $SC/op-rolling.sh "$app"; ready "$app"
  hr "$app SCALE 3->2->3"; bash $SC/op-scale.sh "$app"; ready "$app"
  if [ "$redis" = "1" ]; then
    hr "$app REDIS FAILURE under load"
    kubectl exec -n $NS loadgen -- sh /run-kcb.sh "$app" 60 90 > /tmp/rf.out 2>&1 &
    local RP=$!; sleep 30; echo "KILL redis $(date -u +%H:%M:%SZ)"
    kubectl delete pod -n $NS -l app=redis --grace-period=0 --force >/dev/null 2>&1
    wait $RP; grep -E 'KCBRESULT|ERROR' /tmp/rf.out
    kubectl logs -n $NS -l app=$app --since=120s 2>/dev/null | grep -iE 'connection refused|reconnect|redisson|unable' | tail -6
    local i=0; until kubectl get pod -n $NS -l app=redis -o jsonpath='{.items[0].status.phase}' 2>/dev/null | grep -q Running || [ $i -ge 18 ]; do sleep 5; i=$((i+1)); done
    echo "[redis back: $(kubectl get pod -n $NS -l app=redis -o jsonpath='{.items[0].status.phase}')]"
  fi
}

upgrade(){  # $1 app  $2 old-image  $3 new-image  $4 other-app-to-park
  local app=$1 old=$2 new=$3 other=$4
  hr "UPGRADE $app : $old -> $new (under load)"
  kubectl scale sts/$other -n $NS --replicas=0 >/dev/null 2>&1
  kubectl exec -n $NS deploy/postgres -- psql -U keycloak -d postgres -c "DROP DATABASE IF EXISTS keycloak;" >/dev/null 2>&1
  kubectl exec -n $NS deploy/postgres -- psql -U keycloak -d postgres -c "CREATE DATABASE keycloak;" >/dev/null 2>&1
  kubectl exec -n $NS deploy/redis -- redis-cli flushall >/dev/null 2>&1 || true
  kubectl set image sts/$app keycloak="$old" -n $NS >/dev/null 2>&1
  kubectl scale sts/$app -n $NS --replicas=3 >/dev/null 2>&1
  kubectl delete pod -n $NS -l app=$app --force --grace-period=0 >/dev/null 2>&1
  ready "$app"
  kubectl logs ${app}-0 -n $NS 2>/dev/null | grep -iE 'Keycloak 26|Profile prod' | tail -2
  imp "$app"
  bash $SC/op-upgrade.sh "$app" "$new" 80 360 40
}

hr "WAIT FOR STACKS + LOADGEN"
ready kc-a3; ready kc-b3
kubectl wait --for=condition=Ready pod/loadgen -n $NS --timeout=180s 2>&1 | tail -1

# ---- Phase A: vanilla (Infinispan) ----
hr "PHASE A — VANILLA (Infinispan)"
kubectl scale sts/kc-b3 -n $NS --replicas=0 >/dev/null 2>&1; sleep 20
imp kc-a3
matrix kc-a3 0

# ---- Phase B: Locke (Redis) ----
hr "PHASE B — LOCKE (Redis)"
kubectl scale sts/kc-a3 -n $NS --replicas=0 >/dev/null 2>&1
kubectl scale sts/kc-b3 -n $NS --replicas=3 >/dev/null 2>&1; ready kc-b3
imp kc-b3
matrix kc-b3 1

# ---- Phase C: rolling version upgrade under load ----
hr "PHASE C — ROLLING VERSION UPGRADE 26.3.5 -> 26.6.1"
upgrade kc-a3 "$V_OLD" "$V_NEW" kc-b3
upgrade kc-b3 "$L_OLD" "$L_NEW" kc-a3

hr "ALL DONE"
echo "MATRIX-ALL-COMPLETE $(date -u +%FT%H:%M:%SZ)"
