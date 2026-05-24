#!/usr/bin/env bash
# realm-scale.sh <app> <total> <batch>  create realms via admin API, snapshot heap/redis per batch
APP="$1"; TOTAL="${2:-1000}"; BATCH="${3:-250}"
DIR="benchmark/k8s-ovh/results/$(date +%F)/realm-scale"; mkdir -p "$DIR"; OUT="$DIR/$APP.tsv"
echo -e "realms\theapMB_total\tredis_used\theapMB_perpod" > "$OUT"
mk() { kubectl exec -n locke-bench loadgen -- sh -c '
  T=$(curl -s -d "client_id=admin-cli&username=admin&password=admin&grant_type=password" http://'"$APP"':8080/realms/master/protocol/openid-connect/token | sed -E "s/.*\"access_token\":\"([^\"]+)\".*/\1/")
  for i in $(seq '"$1"' '"$2"'); do
    curl -s -o /dev/null -X POST -H "Authorization: Bearer $T" -H "Content-Type: application/json" \
      -d "{\"realm\":\"r-$i\",\"enabled\":true}" http://'"$APP"':8080/admin/realms
  done '; }
snap() {
  local n=$1; local hsum=0; local per=""
  for ip in $(kubectl get pods -n locke-bench -l app=$APP -o jsonpath='{.items[*].status.podIP}'); do
    h=$(kubectl exec -n locke-bench loadgen -- sh -c "curl -s http://$ip:9000/metrics" 2>/dev/null | awk '/^jvm_memory_used_bytes.*heap/{s+=$2} END{printf "%.0f", s/1048576}')
    hsum=$((hsum + ${h:-0})); per="$per ${h}"
  done
  local r=$(kubectl exec -n locke-bench deploy/redis -- redis-cli info memory 2>/dev/null | grep used_memory_human | cut -d: -f2 | tr -d '\r')
  echo -e "$n\t$hsum\t$r\t$per" | tee -a "$OUT"
}
snap 0
done=0
while [ $done -lt $TOTAL ]; do
  s=$((done+1)); e=$((done+BATCH)); [ $e -gt $TOTAL ] && e=$TOTAL
  mk $s $e; done=$e; sleep 3; snap $done
done
echo "DONE $APP realm-scale -> $OUT"; cat "$OUT"
