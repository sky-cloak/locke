#!/usr/bin/env bash
# upgrade-run.sh — orchestrates the rolling version-upgrade test (26.3.5 -> 26.6.1)
# UNDER LOAD for one stack. Starts the StatefulSet on the 26.3.5 image against a FRESH
# database (26.3.5 cannot run on a schema already migrated to 26.6.1), imports the
# bench realm, then hands off to op-upgrade.sh which loads + flips the image to 26.6.1.
# Usage: upgrade-run.sh <kc-a3|kc-b3> <old26.3.5-image> <new26.6.1-image> <other-stack-to-park>
set -uo pipefail
APP="$1"; OLD="$2"; NEW="$3"; OTHER="$4"; NS=locke-bench
echo "############ UPGRADE TEST: $APP  ($OLD -> $NEW) ############"
# park the other stack so all 3 kc nodes are free + DB is exclusive
kubectl scale sts/$OTHER -n $NS --replicas=0 >/dev/null 2>&1
# fresh DB so 26.3.5 owns the schema
kubectl exec -n $NS deploy/postgres -- psql -U keycloak -d postgres -c "DROP DATABASE IF EXISTS keycloak;" >/dev/null 2>&1
kubectl exec -n $NS deploy/postgres -- psql -U keycloak -d postgres -c "CREATE DATABASE keycloak;" >/dev/null 2>&1
kubectl exec -n $NS deploy/redis -- redis-cli flushall >/dev/null 2>&1 || true
echo "[reset] fresh keycloak DB created; $OTHER parked"
# deploy old version
kubectl set image sts/$APP keycloak="$OLD" -n $NS >/dev/null 2>&1
kubectl scale sts/$APP -n $NS --replicas=3 >/dev/null 2>&1
kubectl delete pod -n $NS -l app=$APP --force --grace-period=0 >/dev/null 2>&1
i=0; until [ "$(kubectl get sts $APP -n $NS -o jsonpath='{.status.readyReplicas}' 2>/dev/null)" = "3" ] || [ $i -ge 42 ]; do sleep 10; i=$((i+1)); done
echo "[old] $APP ready on 26.3.5: $(kubectl get sts $APP -n $NS -o jsonpath='{.status.readyReplicas}')/3"
kubectl logs ${APP}-0 -n $NS 2>/dev/null | grep -iE "Keycloak 26|Profile prod|started in" | tail -2
# import realm
kubectl exec -n $NS loadgen -- sh -c "TOK=\$(curl -s -d 'client_id=admin-cli&username=admin&password=admin&grant_type=password' http://$APP:8080/realms/master/protocol/openid-connect/token | sed -E 's/.*\"access_token\":\"([^\"]+)\".*/\1/'); curl -s -o /dev/null -w 'realm_import=%{http_code}\n' -X POST -H \"Authorization: Bearer \$TOK\" -H 'Content-Type: application/json' -d @/realm.json http://$APP:8080/admin/realms" 2>&1 | tail -1
# run the under-load upgrade
bash benchmark/k8s/op-upgrade.sh "$APP" "$NEW" 80 360 40
echo "############ $APP UPGRADE TEST DONE ############"
