#!/bin/sh
# write-load.sh <app> <ups> <duration_s> [users_per_sec]
# Runs INSIDE the loadgen pod. Drives the Gatling auth load AND a concurrent admin-write stream:
#   - creates users continuously (DB write + user-cache churn)
#   - updates the realm every second (forces realm-cache INVALIDATION across all nodes -> exercises
#     Locke's Redis pub/sub invalidation vs Infinispan's JGroups invalidation)
# Prints the auth KCBRESULT plus write counts, so auth p99/throughput can be compared to the
# no-write baseline.
APP="$1"; UPS="$2"; DUR="${3:-150}"; UPERSEC="${4:-10}"
PFX="w$(date +%s)"
tok(){ curl -s -d 'client_id=admin-cli&username=admin&password=admin&grant_type=password' \
        "http://$APP:8080/realms/master/protocol/openid-connect/token" \
        | sed -E 's/.*"access_token":"([^"]+)".*/\1/'; }
TOK=$(tok); LAST=$(date +%s)
# auth load in the background
sh /run-kcb.sh "$APP" "$UPS" "$DUR" > /tmp/authload.out 2>&1 &
AP=$!
END=$(( $(date +%s) + DUR )); n=0; cre=0; cerr=0; rup=0; rerr=0
while [ "$(date +%s)" -lt "$END" ]; do
  [ $(( $(date +%s) - LAST )) -gt 40 ] && { TOK=$(tok); LAST=$(date +%s); }
  i=0
  while [ "$i" -lt "$UPERSEC" ]; do
    n=$((n+1))
    c=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $TOK" \
        -H 'Content-Type: application/json' \
        -d "{\"username\":\"${PFX}-$n\",\"enabled\":true,\"credentials\":[{\"type\":\"password\",\"value\":\"p\",\"temporary\":false}]}" \
        "http://$APP:8080/admin/realms/bench-kcb/users")
    [ "$c" = "201" ] && cre=$((cre+1)) || cerr=$((cerr+1))
    i=$((i+1))
  done
  # realm update -> invalidates the realm cache on every node
  c=$(curl -s -o /dev/null -w '%{http_code}' -X PUT -H "Authorization: Bearer $TOK" \
      -H 'Content-Type: application/json' \
      -d "{\"realm\":\"bench-kcb\",\"displayName\":\"bench-$n\"}" \
      "http://$APP:8080/admin/realms/bench-kcb")
  [ "$c" = "204" ] && rup=$((rup+1)) || rerr=$((rerr+1))
  sleep 1
done
wait $AP
echo "WRITELOAD users_created=$cre user_errors=$cerr realm_updates=$rup realm_errors=$rerr"
grep KCBRESULT /tmp/authload.out
