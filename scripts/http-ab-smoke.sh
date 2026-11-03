#!/usr/bin/env bash
# http-ab-smoke.sh: HTTP smoke checks against one running Keycloak, printed as
# name=result lines. Run it against stock upstream and against Locke (KC_CACHE
# unset, and KC_CACHE=redis), then diff the outputs: any difference is a finding.
#
#   ./scripts/http-ab-smoke.sh http://localhost:8081 [realm] > upstream.txt
#   ./scripts/http-ab-smoke.sh http://localhost:8080 [realm] > locke.txt
#   diff upstream.txt locke.txt
#
# Exits 2 with a setup=FAILED line if the realm, clients or users cannot be
# created, so a broken setup never produces two matching but meaningless outputs.
# The realm is deleted and recreated on every run.
#
# Needs curl and jq, and a master admin of admin/admin
# (KC_BOOTSTRAP_ADMIN_USERNAME / KC_BOOTSTRAP_ADMIN_PASSWORD).
# Browser flows use a curl cookie jar on purpose: Python's cookiejar drops
# AUTH_SESSION_ID and yields a bogus cookie_not_found.
# The clear-cache checks only compare status codes; they cannot tell whether a
# clear actually evicted anything.
set -uo pipefail
B=${1:?base url}; B=${B%/}; R=${2:-smoke}; W=$(mktemp -d); CB=http://localhost/cb
trap 'rm -rf "$W"' EXIT

C() { curl -s --connect-timeout 5 --max-time 30 "$@"; }
st() { C -o /dev/null -w '%{http_code}' "$@"; }
fail() { echo "setup=FAILED:$1:$2"; exit 2; }

A=$(C -d client_id=admin-cli -d username=admin -d password=admin -d grant_type=password \
  "$B/realms/master/protocol/openid-connect/token" | jq -r '.access_token // empty')
[ -n "$A" ] || fail admin_token none
H=(-H "Authorization: Bearer $A" -H 'Content-Type: application/json')

code=$(st "${H[@]}" -X DELETE "$B/admin/realms/$R")
case "$code" in 204|404) ;; *) fail delete_realm "$code" ;; esac
code=$(st "${H[@]}" -X POST "$B/admin/realms" \
  -d "{\"realm\":\"$R\",\"enabled\":true,\"sslRequired\":\"NONE\",\"bruteForceProtected\":true,\"failureFactor\":2}")
[ "$code" = 201 ] || fail create_realm "$code"
for c in pub pub2; do
  code=$(st "${H[@]}" -X POST "$B/admin/realms/$R/clients" \
    -d "{\"clientId\":\"$c\",\"publicClient\":true,\"directAccessGrantsEnabled\":true,\"standardFlowEnabled\":true,\"redirectUris\":[\"$CB\"]}")
  [ "$code" = 201 ] || fail "create_client_$c" "$code"
done
# Usernames need 3+ characters.
for u in usr bfu; do
  code=$(st "${H[@]}" -X POST "$B/admin/realms/$R/users" \
    -d "{\"username\":\"$u\",\"enabled\":true,\"email\":\"$u@x.io\",\"firstName\":\"F\",\"lastName\":\"L\",\"emailVerified\":true,\"credentials\":[{\"type\":\"password\",\"value\":\"pw\",\"temporary\":false}]}")
  [ "$code" = 201 ] || fail "create_user_$u" "$code"
done
echo "setup=ok"

T=$B/realms/$R/protocol/openid-connect
pw_grant() { C -d client_id="$1" -d username="$2" -d password="$3" -d grant_type=password -d scope=openid "$T/token"; }

echo "admin_users=$(C "${H[@]}" "$B/admin/realms/$R/users" | jq 'if type == "array" then length else "ERROR" end')"

P=$(pw_grant pub usr pw)
echo "password_grant=$(echo "$P" | jq -r 'has("access_token")')"
echo "refresh=$(st -d client_id=pub -d grant_type=refresh_token -d refresh_token="$(echo "$P" | jq -r .refresh_token)" "$T/token")"
echo "userinfo=$(st -H "Authorization: Bearer $(echo "$P" | jq -r .access_token)" "$T/userinfo")"

# Browser authorize with a cookie jar. Prints an authorization code; LOGIN_FORM
# for a 200 that shows the login form (when $3 != login); REDIRECT_ERROR:<error>
# for a redirect without a code; otherwise HTTP_<status>.
authz() {
  local c=$1 j=$2 code loc act
  code=$(C -c "$j" -b "$j" -D "$W/h" -o "$W/body" -w '%{http_code}' \
    "$T/auth?client_id=$c&response_type=code&scope=openid&redirect_uri=$CB")
  loc=$(grep -i '^location:' "$W/h" | tr -d '\r' | sed 's/^[Ll]ocation: //')
  if [ -n "$loc" ]; then
    redirect_result "$loc"; return
  fi
  if [ "$code" != 200 ] || ! grep -q 'id="kc-form-login"' "$W/body"; then echo "HTTP_$code"; return; fi
  if [ "${3:-}" != login ]; then echo LOGIN_FORM; return; fi
  act=$(grep -o 'action="[^"]*"' "$W/body" | head -1 | sed 's/action="//;s/"$//;s/&amp;/\&/g')
  C -c "$j" -b "$j" -D "$W/h2" -o /dev/null --data-urlencode username=usr --data-urlencode password=pw "$act"
  loc=$(grep -i '^location:' "$W/h2" | tr -d '\r' | sed 's/^[Ll]ocation: //')
  if [ -n "$loc" ]; then redirect_result "$loc"; else echo "NO_REDIRECT_AFTER_LOGIN"; fi
}
redirect_result() {
  local code err
  code=$(echo "$1" | sed -n 's/.*[?&]code=\([^&]*\).*/\1/p')
  if [ -n "$code" ]; then echo "$code"; return; fi
  err=$(echo "$1" | sed -n 's/.*[?&]error=\([^&]*\).*/\1/p')
  echo "REDIRECT_ERROR:${err:-unknown}"
}
is_code() { [ -n "$1" ] && ! [[ "$1" =~ ^(LOGIN_FORM|HTTP_|REDIRECT_ERROR|NO_REDIRECT) ]]; }
state() { if is_code "$1"; then echo code; else echo "${1:-EMPTY}"; fi; }
code2tok() { C -d client_id="$1" -d grant_type=authorization_code -d code="$2" -d redirect_uri=$CB "$T/token"; }

J=$W/jar
C1=$(authz pub "$J" login)
echo "authcode_login=$(state "$C1")"
T1=$(code2tok pub "$C1")
echo "authcode_tokens=$(echo "$T1" | jq -r 'has("refresh_token")')"
C2=$(authz pub2 "$J")
echo "sso_second_client=$(state "$C2")"
T2=$(code2tok pub2 "$C2")
RT1=$(echo "$T1" | jq -r .refresh_token); RT2=$(echo "$T2" | jq -r .refresh_token)

# RFC 7009: revoking one client's token must kill that token but keep the other
# client and SSO alive; revoking the last client must end SSO.
echo "revoke_pub=$(st -d client_id=pub -d token="$RT1" -d token_type_hint=refresh_token "$T/revoke")"
echo "revoked_reuse=$(st -d client_id=pub -d grant_type=refresh_token -d refresh_token="$RT1" "$T/token")"
N=$(C -d client_id=pub2 -d grant_type=refresh_token -d refresh_token="$RT2" "$T/token")
echo "other_client_refresh=$(echo "$N" | jq -r 'if has("access_token") then 200 else .error end')"
C3=$(authz pub2 "$J")
echo "sso_after_partial_revoke=$(state "$C3")"
RT3=$(code2tok pub2 "$C3" | jq -r .refresh_token)
st -d client_id=pub2 -d token="$RT3" -d token_type_hint=refresh_token "$T/revoke" >/dev/null
echo "sso_after_last_revoke=$(state "$(authz pub2 "$J")")"

L=$(pw_grant pub usr pw | jq -r .refresh_token)
echo "logout=$(st -d client_id=pub -d refresh_token="$L" "$T/logout")"
echo "refresh_after_logout=$(st -d client_id=pub -d grant_type=refresh_token -d refresh_token="$L" "$T/token")"

# Brute force (failureFactor 2): after 3 bad passwords the right one is refused,
# and an admin clear of all lockouts must actually reset the user.
for _ in 1 2 3; do pw_grant pub bfu bad >/dev/null; done
echo "bruteforce_locked=$(pw_grant pub bfu pw | jq -r '.error // "NOT_LOCKED"')"
BID=$(C "${H[@]}" "$B/admin/realms/$R/users?username=bfu&exact=true" | jq -r '.[0].id')
BF=$B/admin/realms/$R/attack-detection/brute-force/users
echo "bruteforce_status=$(C "${H[@]}" "$BF/$BID" | jq -c '{disabled,numFailures}')"
echo "bruteforce_clear_all=$(st "${H[@]}" -X DELETE "$BF")"
echo "bruteforce_after_clear=$(C "${H[@]}" "$BF/$BID" | jq -c '{disabled,numFailures}')"

PID=$(C "${H[@]}" "$B/admin/realms/$R/clients?clientId=pub" | jq -r '.[0].id')
echo "client_sessions=$(C "${H[@]}" "$B/admin/realms/$R/clients/$PID/user-sessions?first=0&max=10" | jq 'if type == "array" then length else "ERROR" end')"
echo "clear_keys_cache=$(st "${H[@]}" -X POST "$B/admin/realms/$R/clear-keys-cache")"
echo "clear_realm_cache=$(st "${H[@]}" -X POST "$B/admin/realms/$R/clear-realm-cache")"
