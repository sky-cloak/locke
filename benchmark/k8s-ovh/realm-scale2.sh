#!/usr/bin/env bash
# realm-scale2.sh <app kc-a3|kc-b3> <users-per-realm> <clients-per-realm> <step...>
# Bulk-creates POPULATED realms via the keycloak-benchmark dataset provider in
# cumulative steps; per step captures provisioning rate, heap/pod, redis mem, and
# admin-API latency (list-all-realms, single realm, user search). Dumps KC logs at end.
set -uo pipefail
APP="$1"; UPR="$2"; CPR="$3"; shift 3; STEPS="$*"
DIR="benchmark/k8s-ovh/results/$(date +%F)/realmscale-$APP"; mkdir -p "$DIR/logs"
TSV="$DIR/metrics.tsv"; echo -e "realms\tbatch_s\trate_realms_s\theapMB_total\theap_perpod\tredis_used\tlist_all_s\tsingle_s\tusersearch_s" > "$TSV"
IP=$(kubectl get pod ${APP}-0 -n locke-bench -o jsonpath='{.status.podIP}')
ex(){ kubectl exec -n locke-bench loadgen -- sh -c "$1"; }
tok(){ ex "curl -s -d 'client_id=admin-cli&username=admin&password=admin&grant_type=password' http://$IP:8080/realms/master/protocol/openid-connect/token | sed -E 's/.*\"access_token\":\"([^\"]+)\".*/\1/'"; }
heap(){ local s=0 per=""; for ip in $(kubectl get pods -n locke-bench -l app=$APP -o jsonpath='{.items[*].status.podIP}'); do
  kubectl exec -n locke-bench loadgen -- curl -s "http://$ip:9000/metrics" 2>/dev/null > /tmp/_heapm
  h=$(awk '/^jvm_memory_used_bytes\{/ && /heap/{x+=$2} END{printf "%.0f", x/1048576}' /tmp/_heapm)
  s=$((s+${h:-0})); per="$per ${h}"; done; echo "$s|$per"; }
redis(){ kubectl exec -n locke-bench deploy/redis -- redis-cli info memory 2>/dev/null | awk -F: '/used_memory_human/{print $2}' | tr -d '\r'; }
admin_t(){ local T=$1; ex "curl -s --max-time 90 -o /dev/null -w '%{time_total}' -H 'Authorization: Bearer $T' \"http://$IP:8080/admin/realms?briefRepresentation=true\""; }
single_t(){ local T=$1; ex "curl -s -o /dev/null -w '%{time_total}' -H 'Authorization: Bearer $T' http://$IP:8080/admin/realms/master"; }
usearch_t(){ local T=$1; ex "curl -s -o /dev/null -w '%{time_total}' -H 'Authorization: Bearer $T' 'http://$IP:8080/admin/realms/master/users?max=20'"; }
snap(){ local n=$1 bs=$2 rate=$3; local hp=$(heap); local T=$(tok)
  echo -e "$n\t$bs\t$rate\t${hp%%|*}\t${hp#*|}\t$(redis)\t$(admin_t $T)\t$(single_t $T)\t$(usearch_t $T)" | tee -a "$TSV"; }
snap 0 0 0
prev=0
for N in $STEPS; do
  delta=$((N-prev)); [ $delta -le 0 ] && { prev=$N; continue; }
  pfx="s${N}_"; t0=$(date +%s)
  ex "curl -s 'http://$IP:8080/realms/master/dataset/create-realms?count=$delta&realm-prefix=$pfx&users-per-realm=$UPR&clients-per-realm=$CPR'" >/dev/null 2>&1
  ceil=$((delta*4+300))
  while :; do s=$(ex "curl -s --max-time 10 http://$IP:8080/realms/master/dataset/status" 2>/dev/null); echo "$s" | grep -qi "No task in progress" && break; el=$(( $(date +%s)-t0 )); [ $el -gt $ceil ] && { echo "[warn] timeout step $N"; break; }; sleep 5; done
  bs=$(( $(date +%s)-t0 )); rate=$(awk "BEGIN{printf \"%.2f\", $delta/$bs}")
  echo "=== $APP reached $N realms in ${bs}s (${rate}/s) ==="
  sleep 5; snap $N $bs $rate; prev=$N
done
echo "=== capture KC logs as evidence ==="
for p in ${APP}-0 ${APP}-1 ${APP}-2; do kubectl logs $p -n locke-bench --tail=400 > "$DIR/logs/$p.log" 2>&1; done
echo "DONE $APP -> $TSV"; cat "$TSV"
