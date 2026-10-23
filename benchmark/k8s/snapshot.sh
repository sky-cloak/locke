#!/usr/bin/env bash
# snapshot.sh <app-label kc-a3|kc-b3> <tag>  -> prints cpu/mem, redis mem, jvm heap, redis cache counters
APP="$1"; TAG="$2"
echo "## snapshot $APP $TAG"
kubectl top pods -n locke-bench --no-headers 2>/dev/null | grep -E "$APP|redis|postgres|loadgen" | awk '{printf "  top %-28s cpu=%s mem=%s\n",$1,$2,$3}'
echo "  redis_used=$(kubectl exec -n locke-bench deploy/redis -- redis-cli info memory 2>/dev/null | grep used_memory_human | cut -d: -f2 | tr -d '\r')"
for ip in $(kubectl get pods -n locke-bench -l app=$APP -o jsonpath='{.items[*].status.podIP}'); do
  m=$(kubectl exec -n locke-bench loadgen -- curl -s "http://$ip:9000/metrics" 2>/dev/null)
  heap=$(echo "$m" | awk '/^jvm_memory_used_bytes.*heap/{s+=$2} END{printf "%.0f", s/1048576}')
  hit=$(echo "$m" | grep -E '^keycloak_redis_l1_hit|^keycloak_redis_cache_hit' | awk '{s+=$2} END{print s+0}')
  miss=$(echo "$m" | grep -E '^keycloak_redis_l1_miss|^keycloak_redis_cache_miss' | awk '{s+=$2} END{print s+0}')
  echo "  $ip heapMB=$heap redis_hits=$hit redis_miss=$miss"
done
