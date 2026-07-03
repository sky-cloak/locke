#!/usr/bin/env bash
# run-matrix.sh — one-command Locke-vs-Infinispan benchmark matrix on an existing AKS bench rig.
#
# Wraps the manifests/scripts in this dir and bakes in the gotchas learned the hard way:
#   - realm is imported before any load (a missing realm reads as 100% auth failures, not an error)
#   - kc rollouts are force-recreated so a crash-looping pod can't wedge a StatefulSet rollout
#   - write-load.sh refreshes the admin token < its 60s lifespan (sustained writes)
#   - cluster mode is formed by pod IP (announce-ip); managed Redis OSS clustering policy is a
#     non-starter with Locke (Redisson READONLY) — use Enterprise/standalone for managed.
#
# Prereqs: kubectl context set, the lockekc/lockeinfra pools labelled (bench-role=kc/infra/loadgen),
# ns + ghcr-pull secret created, and azure-bench.yaml applied. See REPORT-azure.md for provisioning.
#
# Usage: run-matrix.sh [parity|write|resilience|topology|all]   (default: all)
set -u
NS=locke-bench
HERE="$(cd "$(dirname "$0")" && pwd)"
WHAT="${1:-all}"

log(){ echo "== $* =="; }

# Force a clean kc roll: the env is already set by the caller; delete pods so they pick up the
# latest spec immediately (avoids the wedged-rollout trap when a new config crash-loops).
kc_roll(){ # <sts>
  kubectl -n "$NS" delete pod -l app="$1" --grace-period=0 --force >/dev/null 2>&1
  for i in $(seq 1 24); do
    [ "$(kubectl -n "$NS" get sts "$1" -o jsonpath='{.status.readyReplicas}' 2>/dev/null)" = "3" ] && return 0
    sleep 15
  done
  echo "  WARN: $1 did not reach 3/3"; return 1
}

import_realm(){ # <app>
  kubectl exec -n "$NS" loadgen -- sh -c \
    "TOK=\$(curl -s -d 'client_id=admin-cli&username=admin&password=admin&grant_type=password' \
      http://$1:8080/realms/master/protocol/openid-connect/token | sed -E 's/.*\"access_token\":\"([^\"]+)\".*/\1/'); \
     curl -s -o /dev/null -w '  realm_import($1)=%{http_code}\n' -X POST -H \"Authorization: Bearer \$TOK\" \
      -H 'Content-Type: application/json' -d @/realm.json http://$1:8080/admin/realms"
}

parity(){ # <app>
  for u in 80 160 250; do
    printf '  %s @%s ups: ' "$1" "$u"
    kubectl exec -n "$NS" loadgen -- sh /run-kcb.sh "$1" "$u" 60 2>&1 | grep -oE 'KCBRESULT.*' || echo "FAILED"
  done
}

bring_up(){ # <app>  (scales the other stack to 0)
  local other; [ "$1" = kc-b3 ] && other=kc-a3 || other=kc-b3
  kubectl -n "$NS" scale sts "$other" --replicas=0 >/dev/null
  kubectl -n "$NS" scale sts "$1" --replicas=3 >/dev/null
  kc_roll "$1"; import_realm "$1"
}

case "$WHAT" in
  parity|all)
    log "PARITY: Locke (kc-b3)"; bring_up kc-b3; parity kc-b3
    log "PARITY: Infinispan (kc-a3)"; bring_up kc-a3; parity kc-a3 ;;
esac
case "$WHAT" in
  write|all)
    log "WRITE VARIANT (auth + user-create + realm-invalidation)"
    kubectl cp "$HERE/write-load.sh" "$NS/loadgen:/write-load.sh" 2>/dev/null
    bring_up kc-b3; echo "  Locke:";      kubectl exec -n "$NS" loadgen -- sh /write-load.sh kc-b3 160 150 10 2>&1 | grep -E 'WRITELOAD|KCBRESULT'
    bring_up kc-a3; echo "  Infinispan:"; kubectl exec -n "$NS" loadgen -- sh /write-load.sh kc-a3 160 150 10 2>&1 | grep -E 'WRITELOAD|KCBRESULT' ;;
esac
case "$WHAT" in
  resilience|all)
    log "RESILIENCE: kill a KC pod mid-load"
    bring_up kc-b3; bash "$HERE/resilience.sh" kc-b3 80 45 kc-b3-1 2>&1 | grep -E 'KCBRESULT|RESULT'
    bring_up kc-a3; bash "$HERE/resilience.sh" kc-a3 80 45 kc-a3-1 2>&1 | grep -E 'KCBRESULT|RESULT' ;;
esac
case "$WHAT" in
  topology|all)
    log "TOPOLOGY: Locke + 6-node Redis cluster (kill a primary mid-load)"
    kubectl apply -f "$HERE/redis-cluster.yaml" >/dev/null 2>&1
    for i in $(seq 1 30); do [ "$(kubectl -n "$NS" get sts rediscluster -o jsonpath='{.status.readyReplicas}' 2>/dev/null)" = "6" ] && break; sleep 10; done
    kubectl exec -n "$NS" rediscluster-0 -- sh -c 'redis-cli --cluster create $(for i in 0 1 2 3 4 5; do getent hosts rediscluster-$i.rediscluster | awk "{print \$1\":6379\"}"; done) --cluster-replicas 1 --cluster-yes' >/dev/null 2>&1
    SEEDS="redis-cluster://$(for i in 0 1 2 3 4 5; do printf 'rediscluster-%s.rediscluster:6379,' "$i"; done | sed 's/,$//')"
    kubectl -n "$NS" set env sts/kc-b3 KC_CACHE_REDIS_URL="$SEEDS" >/dev/null
    kubectl -n "$NS" scale sts kc-a3 --replicas=0 >/dev/null; kubectl -n "$NS" scale sts kc-b3 --replicas=3 >/dev/null; kc_roll kc-b3
    kubectl exec -n "$NS" loadgen -- sh /run-kcb.sh kc-b3 80 150 >/tmp/topo.out 2>&1 &
    sleep 45; echo "  killing primary rediscluster-0"; kubectl -n "$NS" delete pod rediscluster-0 --grace-period=0 --force >/dev/null 2>&1
    wait; grep -oE 'KCBRESULT.*' /tmp/topo.out ;;
esac
log "matrix '$WHAT' complete"
