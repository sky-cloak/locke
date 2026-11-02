#!/usr/bin/env bash
# http-ab-smoke.sh: HTTP smoke checks against one running Keycloak, printed as
# name=result lines. Run it against stock upstream and against Locke (KC_CACHE
# unset, and KC_CACHE=redis), then diff the outputs: any difference is a finding.
#
#   ./scripts/http-ab-smoke.sh http://localhost:8081 [realm] > upstream.txt
#   ./scripts/http-ab-smoke.sh http://localhost:8080 [realm] > locke.txt
#   diff upstream.txt locke.txt
#
# Needs curl and jq, and a master admin of admin/admin
# (KC_BOOTSTRAP_ADMIN_USERNAME / KC_BOOTSTRAP_ADMIN_PASSWORD). Creates the realm.
# Browser flows use a curl cookie jar on purpose: Python's cookiejar drops
# AUTH_SESSION_ID and yields a bogus cookie_not_found.
set -uo pipefail
B=${1:?base url}; R=${2:-smoke}; W=$(mktemp -d); CB=http://localhost/cb
trap 'rm -rf "$W"' EXIT

A=$(curl -s -d client_id=admin-cli -d username=admin -d password=admin -d grant_type=password \
  "$B/realms/master/protocol/openid-connect/token" | jq -r .access_token)
H=(-H "Authorization: Bearer $A" -H 'Content-Type: application/json')
curl -s -o /dev/null "${H[@]}" -X POST "$B/admin/realms" \
  -d "{\"realm\":\"$R\",\"enabled\":true,\"sslRequired\":\"NONE\",\"bruteForceProtected\":true,\"failureFactor\":2}"
for c in pub pub2; do
  curl -s -o /dev/null "${H[@]}" -X POST "$B/admin/realms/$R/clients" \
    -d "{\"clientId\":\"$c\",\"publicClient\":true,\"directAccessGrantsEnabled\":true,\"standardFlowEnabled\":true,\"redirectUris\":[\"$CB\"]}"
done
# Usernames need 3+ characters.
for u in usr bfu; do
  curl -s -o /dev/null "${H[@]}" -X POST "$B/admin/realms/$R/users" \
    -d "{\"username\":\"$u\",\"enabled\":true,\"email\":\"$u@x.io\",\"firstName\":\"F\",\"lastName\":\"L\",\"emailVerified\":true,\"credentials\":[{\"type\":\"password\",\"value\":\"pw\",\"temporary\":false}]}"
done

T=$B/realms/$R/protocol/openid-connect
st() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
pw_grant() { curl -s -d client_id="$1" -d username="$2" -d password="$3" -d grant_type=password "$T/token"; }

echo "admin_users=$(curl -s "${H[@]}" "$B/admin/realms/$R/users" | jq length)"

P=$(pw_grant pub usr pw)
echo "password_grant=$(echo "$P" | jq -r 'has("access_token")')"
echo "refresh=$(st -d client_id=pub -d grant_type=refresh_token -d refresh_token="$(echo "$P" | jq -r .refresh_token)" "$T/token")"
echo "userinfo=$(st -H "Authorization: Bearer $(echo "$P" | jq -r .access_token)" "$T/userinfo")"

# Browser authorize with a cookie jar. Prints the code, or LOGIN_FORM when the
# server asks for credentials and $3 != login.
authz() {
  local c=$1 j=$2 out loc act
  out=$(curl -s -c "$j" -b "$j" -D "$W/h" "$T/auth?client_id=$c&response_type=code&scope=openid&redirect_uri=$CB")
  loc=$(grep -i '^location:' "$W/h" | tr -d '\r' | sed 's/^[Ll]ocation: //')
  if [ -n "$loc" ]; then echo "$loc" | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p'; return; fi
  if [ "${3:-}" != login ]; then echo LOGIN_FORM; return; fi
  act=$(echo "$out" | grep -o 'action="[^"]*"' | head -1 | sed 's/action="//;s/"$//;s/&amp;/\&/g')
  curl -s -c "$j" -b "$j" -D "$W/h2" -o /dev/null --data-urlencode username=usr --data-urlencode password=pw "$act"
  grep -i '^location:' "$W/h2" | tr -d '\r' | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p'
}
code2tok() { curl -s -d client_id="$1" -d grant_type=authorization_code -d code="${2:-none}" -d redirect_uri=$CB "$T/token"; }

J=$W/jar
T1=$(code2tok pub "$(authz pub "$J" login)")
echo "authcode_login=$(echo "$T1" | jq -r 'has("refresh_token")')"
C2=$(authz pub2 "$J")
echo "sso_second_client=$([ "$C2" != LOGIN_FORM ] && [ -n "$C2" ] && echo yes || echo no)"
T2=$(code2tok pub2 "$C2")
RT1=$(echo "$T1" | jq -r .refresh_token); RT2=$(echo "$T2" | jq -r .refresh_token)

# RFC 7009: revoking one client's token must kill that token but keep the other
# client and SSO alive; revoking the last client must end SSO.
echo "revoke_pub=$(st -d client_id=pub -d token="$RT1" -d token_type_hint=refresh_token "$T/revoke")"
echo "revoked_reuse=$(st -d client_id=pub -d grant_type=refresh_token -d refresh_token="$RT1" "$T/token")"
N=$(curl -s -d client_id=pub2 -d grant_type=refresh_token -d refresh_token="$RT2" "$T/token")
echo "other_client_refresh=$(echo "$N" | jq -r 'if has("access_token") then 200 else .error end')"
C3=$(authz pub2 "$J")
echo "sso_after_partial_revoke=$([ "$C3" != LOGIN_FORM ] && echo yes || echo no)"
RT3=$(code2tok pub2 "$C3" | jq -r .refresh_token)
st -d client_id=pub2 -d token="$RT3" -d token_type_hint=refresh_token "$T/revoke" >/dev/null
echo "sso_after_last_revoke=$([ "$(authz pub2 "$J")" = LOGIN_FORM ] && echo login_form || echo still_sso)"

L=$(pw_grant pub usr pw | jq -r .refresh_token)
echo "logout=$(st -d client_id=pub -d refresh_token="$L" "$T/logout")"
echo "refresh_after_logout=$(st -d client_id=pub -d grant_type=refresh_token -d refresh_token="$L" "$T/token")"

# Brute force (failureFactor 2): after 3 bad passwords the right one is refused.
for _ in 1 2 3; do pw_grant pub bfu bad >/dev/null; done
echo "bruteforce_locked=$(pw_grant pub bfu pw | jq -r '.error // "NOT_LOCKED"')"
BID=$(curl -s "${H[@]}" "$B/admin/realms/$R/users?username=bfu&exact=true" | jq -r '.[0].id')
echo "bruteforce_status=$(curl -s "${H[@]}" "$B/admin/realms/$R/attack-detection/brute-force/users/$BID" | jq -c '{disabled,numFailures}')"

PID=$(curl -s "${H[@]}" "$B/admin/realms/$R/clients?clientId=pub" | jq -r '.[0].id')
echo "client_sessions=$(curl -s "${H[@]}" "$B/admin/realms/$R/clients/$PID/user-sessions?first=0&max=10" | jq length)"
echo "clear_keys_cache=$(st "${H[@]}" -X POST "$B/admin/realms/$R/clear-keys-cache")"
echo "clear_realm_cache=$(st "${H[@]}" -X POST "$B/admin/realms/$R/clear-realm-cache")"
