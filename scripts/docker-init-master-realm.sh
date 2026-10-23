#!/bin/sh
# Wait for Keycloak to be ready, then set master realm sslRequired=NONE for Docker dev
set -e

KC_URL="${KC_URL:-http://localhost:8080}"
KC_ADMIN="${KC_BOOTSTRAP_ADMIN_USERNAME:-admin}"
KC_PASS="${KC_BOOTSTRAP_ADMIN_PASSWORD:-admin}"

echo "Waiting for Keycloak to be ready at $KC_URL ..."
i=0
while [ $i -lt 120 ]; do
  if wget -q --spider "${KC_URL}/realms/master" 2>/dev/null; then
    echo "Keycloak is ready (after ${i}s)"
    break
  fi
  i=$((i + 1))
  if [ "$i" -eq 120 ]; then
    echo "ERROR: Keycloak not ready after 120s"
    exit 1
  fi
  sleep 1
done

# Set master realm sslRequired=NONE so HTTP works through Docker port mapping
echo "Setting master realm sslRequired=NONE ..."
/opt/keycloak/bin/kcadm.sh config credentials --server "$KC_URL" --realm master --user "$KC_ADMIN" --password "$KC_PASS"
/opt/keycloak/bin/kcadm.sh update realms/master -s sslRequired=NONE --server "$KC_URL" --realm master
echo "Master realm SSL requirement disabled for development."
