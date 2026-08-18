#!/usr/bin/env bash
# provision-realms.sh — bulk-create realms via Keycloak Admin REST API.
#
# v2: ONE full-representation POST per realm (realm + clients + IdPs + users
# inline) instead of ~18 calls. Plus passwordPolicy=hashIterations(1) so user
# credential hashing is near-instant (this is a load test, not production).
# Result: ~10-18x faster, and closer to how real deployments import realms.
#
# Usage:
#   ./provision-realms.sh <kc-base-url> <admin-user> <admin-pass> <start-idx> <end-idx> [users] [clients] [idps]
set -euo pipefail

KC_URL="${1:?KC base URL, e.g. http://localhost:18080}"
ADMIN_USER="${2:-admin}"
ADMIN_PASS="${3:-admin}"
START="${4:?start realm index (1-based)}"
END="${5:?end realm index (inclusive)}"
USERS_PER_REALM="${6:-10}"
CLIENTS_PER_REALM="${7:-5}"
IDPS_PER_REALM="${8:-2}"

CONCURRENCY="${CONCURRENCY:-4}"
LOG_DIR="${LOG_DIR:-/tmp/locke-loadtest-$$}"
mkdir -p "$LOG_DIR"

echo "[provision] target=$KC_URL realms=$START..$END users=$USERS_PER_REALM clients=$CLIENTS_PER_REALM idps=$IDPS_PER_REALM concurrency=$CONCURRENCY (single-POST mode)"
START_TIME=$(date +%s)

get_token() {
  curl -s --fail \
    -d "client_id=admin-cli&username=$ADMIN_USER&password=$ADMIN_PASS&grant_type=password" \
    "$KC_URL/realms/master/protocol/openid-connect/token" \
    | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p'
}

TOKEN="$(get_token)"
if [[ -z "$TOKEN" ]]; then
  echo "[provision] ERROR: failed to obtain admin token from $KC_URL"
  exit 1
fi
echo "$TOKEN" > "$LOG_DIR/.token"
( while true; do sleep 30; T="$(get_token)"; [[ -n "$T" ]] && echo "$T" > "$LOG_DIR/.token"; done ) &
REFRESHER=$!
trap 'kill $REFRESHER 2>/dev/null || true' EXIT

create_realm() {
  local idx="$1"
  local name="realm-$(printf '%05d' "$idx")"
  local tok; tok="$(cat "$LOG_DIR/.token")"

  # Build the entire realm (clients + IdPs + users inline) in ONE JSON document.
  local body
  body=$(jq -n \
    --arg realm "$name" \
    --argjson nusers "$USERS_PER_REALM" \
    --argjson nclients "$CLIENTS_PER_REALM" \
    --argjson nidps "$IDPS_PER_REALM" '
    {
      realm: $realm,
      enabled: true,
      sslRequired: "none",
      loginWithEmailAllowed: true,
      passwordPolicy: "hashIterations(1)",
      roles: { realm: [ { name: "role-1" } ] },
      clients: [ range(1; $nclients+1) as $i | {
        clientId: ("client-app-" + ($i|tostring)),
        enabled: true,
        publicClient: ($i % 2 == 0),
        secret: ("secret-" + ($i|tostring)),
        directAccessGrantsEnabled: true,
        standardFlowEnabled: true,
        redirectUris: ["*"],
        webOrigins: ["*"]
      } ],
      identityProviders: [ range(1; $nidps+1) as $i |
        (if $i % 2 == 0 then "saml" else "oidc" end) as $p | {
          alias: ("idp-" + $p + "-" + ($i|tostring)),
          providerId: $p,
          enabled: true,
          trustEmail: false,
          storeToken: false,
          config: { clientId: "stub", clientSecret: "stub",
                    authorizationUrl: "https://example.com/auth",
                    tokenUrl: "https://example.com/token" }
      } ],
      users: [ range(1; $nusers+1) as $i | {
        username: ("user-" + ($i|tostring)),
        enabled: true,
        emailVerified: true,
        email: ("user-" + ($i|tostring) + "@" + $realm + ".test"),
        firstName: "User",
        lastName: ($i|tostring),
        credentials: [ { type: "password", value: ("user-" + ($i|tostring) + "-pass"), temporary: false } ]
      } ]
    }')

  local code
  code=$(curl -s -o /dev/null -w "%{http_code}" -X POST \
    -H "Authorization: Bearer $tok" \
    -H "Content-Type: application/json" \
    "$KC_URL/admin/realms" \
    --data-binary @- <<<"$body")

  if [[ "$code" == "201" || "$code" == "409" ]]; then
    echo "$idx done" >> "$LOG_DIR/progress.log"
  else
    echo "[provision] ERROR realm $name: HTTP $code" >&2
  fi
}

export -f create_realm get_token
export KC_URL ADMIN_USER ADMIN_PASS USERS_PER_REALM CLIENTS_PER_REALM IDPS_PER_REALM LOG_DIR

seq "$START" "$END" | xargs -n 1 -P "$CONCURRENCY" -I {} bash -c 'create_realm "$@"' _ {}

END_TIME=$(date +%s)
ELAPSED=$((END_TIME - START_TIME)); [[ $ELAPSED -lt 1 ]] && ELAPSED=1
COUNT=$((END - START + 1))
echo "[provision] DONE: $COUNT realms in ${ELAPSED}s ($(awk "BEGIN{printf \"%.2f\", $COUNT/$ELAPSED}") realms/s)"
echo "{\"realms\":$COUNT,\"elapsed_s\":$ELAPSED,\"rate_per_s\":$(awk "BEGIN{printf \"%.3f\", $COUNT/$ELAPSED}")}"
