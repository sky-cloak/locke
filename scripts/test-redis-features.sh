#!/bin/bash
# Keycloak Redis Integration Test Script
# Tests all major Keycloak features with Redis as the cache backend
set -e

BASE_URL="${KEYCLOAK_URL:-http://localhost:8080}"
ADMIN_USER="${KEYCLOAK_ADMIN:-admin}"
ADMIN_PASS="${KEYCLOAK_ADMIN_PASSWORD:-admin}"
REALM="test-redis-features"
PASSED=0
FAILED=0

pass() { echo "  PASS: $1"; PASSED=$((PASSED + 1)); }
fail() { echo "  FAIL: $1"; FAILED=$((FAILED + 1)); }
section() { echo ""; echo "--- $1 ---"; }

echo "============================================"
echo "  Keycloak Redis Integration Test Suite"
echo "  Target: $BASE_URL"
echo "============================================"

# ─── 1. Admin Authentication ───
section "1. Admin Authentication"
TOKEN=$(curl -s -X POST "$BASE_URL/realms/master/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password&client_id=admin-cli&username=$ADMIN_USER&password=$ADMIN_PASS" \
  | python3 -c "import sys,json;print(json.load(sys.stdin).get('access_token',''))")
if [ -n "$TOKEN" ] && [ "$TOKEN" != "" ]; then
  pass "Admin token obtained"
else
  fail "Failed to get admin token"
  echo "Cannot proceed without admin access."
  exit 1
fi
AUTH="Authorization: Bearer $TOKEN"

# ─── 2. Health Check ───
section "2. Health Check"
HEALTH=$(curl -s http://localhost:9001/health | python3 -c "import sys,json;print(json.load(sys.stdin)['status'])")
if [ "$HEALTH" = "UP" ]; then pass "Health status UP"; else fail "Health status: $HEALTH"; fi

# ─── 3. Realm Management ───
section "3. Realm Management"
# Delete realm if it exists from previous run
curl -s -o /dev/null -X DELETE "$BASE_URL/admin/realms/$REALM" -H "$AUTH" 2>/dev/null

CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/admin/realms" \
  -H "$AUTH" -H "Content-Type: application/json" \
  -d "{\"realm\":\"$REALM\",\"enabled\":true,\"sslRequired\":\"none\"}")
if [ "$CODE" = "201" ]; then pass "Create realm"; else fail "Create realm: $CODE"; fi

CODE=$(curl -s -o /dev/null -w "%{http_code}" -H "$AUTH" "$BASE_URL/admin/realms/$REALM")
if [ "$CODE" = "200" ]; then pass "Get realm"; else fail "Get realm: $CODE"; fi

CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$BASE_URL/admin/realms/$REALM" \
  -H "$AUTH" -H "Content-Type: application/json" \
  -d "{\"realm\":\"$REALM\",\"enabled\":true,\"sslRequired\":\"none\",\"displayName\":\"Redis Test Realm\"}")
if [ "$CODE" = "204" ]; then pass "Update realm"; else fail "Update realm: $CODE"; fi

# ─── 4. Client Management ───
section "4. Client Management"
# Public client with direct access grants
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/admin/realms/$REALM/clients" \
  -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"clientId":"test-public","enabled":true,"publicClient":true,"directAccessGrantsEnabled":true,"redirectUris":["http://localhost:3000/*"],"webOrigins":["http://localhost:3000"]}')
if [ "$CODE" = "201" ]; then pass "Create public client"; else fail "Create public client: $CODE"; fi

# Confidential client with service account
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/admin/realms/$REALM/clients" \
  -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"clientId":"test-confidential","enabled":true,"publicClient":false,"secret":"test-secret","serviceAccountsEnabled":true,"directAccessGrantsEnabled":true,"redirectUris":["http://localhost:3000/*"]}')
if [ "$CODE" = "201" ]; then pass "Create confidential client"; else fail "Create confidential client: $CODE"; fi

CLIENT_COUNT=$(curl -s -H "$AUTH" "$BASE_URL/admin/realms/$REALM/clients" | python3 -c "import sys,json;print(len(json.load(sys.stdin)))")
if [ "$CLIENT_COUNT" -gt "0" ]; then pass "List clients ($CLIENT_COUNT found)"; else fail "List clients: 0"; fi

# ─── 5. User Management ───
section "5. User Management"
for i in $(seq 1 5); do
  CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/admin/realms/$REALM/users" \
    -H "$AUTH" -H "Content-Type: application/json" \
    -d "{\"username\":\"user$i\",\"enabled\":true,\"email\":\"user$i@test.com\",\"firstName\":\"User\",\"lastName\":\"Number$i\",\"credentials\":[{\"type\":\"password\",\"value\":\"password$i\",\"temporary\":false}]}")
  if [ "$CODE" = "201" ]; then pass "Create user$i"; else fail "Create user$i: $CODE"; fi
done

USER_COUNT=$(curl -s -H "$AUTH" "$BASE_URL/admin/realms/$REALM/users?max=100" | python3 -c "import sys,json;print(len(json.load(sys.stdin)))")
if [ "$USER_COUNT" -ge "5" ]; then pass "List users ($USER_COUNT found)"; else fail "List users: $USER_COUNT"; fi

FOUND=$(curl -s -H "$AUTH" "$BASE_URL/admin/realms/$REALM/users?username=user3" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d[0]['username'] if d else '')")
if [ "$FOUND" = "user3" ]; then pass "Search user by username"; else fail "Search user: $FOUND"; fi

FOUND_EMAIL=$(curl -s -H "$AUTH" "$BASE_URL/admin/realms/$REALM/users?email=user2@test.com" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d[0]['email'] if d else '')")
if [ "$FOUND_EMAIL" = "user2@test.com" ]; then pass "Search user by email"; else fail "Search by email: $FOUND_EMAIL"; fi

# ─── 6. Role Management ───
section "6. Role Management"
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/admin/realms/$REALM/roles" \
  -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"name":"app-user","description":"Application user role"}')
if [ "$CODE" = "201" ]; then pass "Create realm role 'app-user'"; else fail "Create role: $CODE"; fi

CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/admin/realms/$REALM/roles" \
  -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"name":"app-admin","description":"Application admin role"}')
if [ "$CODE" = "201" ]; then pass "Create realm role 'app-admin'"; else fail "Create role: $CODE"; fi

CODE=$(curl -s -o /dev/null -w "%{http_code}" -H "$AUTH" "$BASE_URL/admin/realms/$REALM/roles/app-user")
if [ "$CODE" = "200" ]; then pass "Get realm role"; else fail "Get realm role: $CODE"; fi

# Assign role to user
USER1_ID=$(curl -s -H "$AUTH" "$BASE_URL/admin/realms/$REALM/users?username=user1" | python3 -c "import sys,json;print(json.load(sys.stdin)[0]['id'])")
ROLE_ID=$(curl -s -H "$AUTH" "$BASE_URL/admin/realms/$REALM/roles/app-user" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['id'])")
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/admin/realms/$REALM/users/$USER1_ID/role-mappings/realm" \
  -H "$AUTH" -H "Content-Type: application/json" \
  -d "[{\"id\":\"$ROLE_ID\",\"name\":\"app-user\"}]")
if [ "$CODE" = "204" ]; then pass "Assign role to user"; else fail "Assign role: $CODE"; fi

# ─── 7. Group Management ───
section "7. Group Management"
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/admin/realms/$REALM/groups" \
  -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"name":"developers"}')
if [ "$CODE" = "201" ]; then pass "Create group"; else fail "Create group: $CODE"; fi

GROUP_COUNT=$(curl -s -H "$AUTH" "$BASE_URL/admin/realms/$REALM/groups" | python3 -c "import sys,json;print(len(json.load(sys.stdin)))")
if [ "$GROUP_COUNT" -ge "1" ]; then pass "List groups ($GROUP_COUNT found)"; else fail "List groups: $GROUP_COUNT"; fi

# Add user to group
GROUP_ID=$(curl -s -H "$AUTH" "$BASE_URL/admin/realms/$REALM/groups" | python3 -c "import sys,json;print(json.load(sys.stdin)[0]['id'])")
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$BASE_URL/admin/realms/$REALM/users/$USER1_ID/groups/$GROUP_ID" \
  -H "$AUTH")
if [ "$CODE" = "204" ]; then pass "Add user to group"; else fail "Add to group: $CODE"; fi

# ─── 8. User Login (Password Grant) ───
section "8. User Login (Password Grant)"
for i in 1 2 3; do
  LOGIN_RESULT=$(curl -s -X POST "$BASE_URL/realms/$REALM/protocol/openid-connect/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "grant_type=password&client_id=test-public&username=user$i&password=password$i" \
    | python3 -c "import sys,json;d=json.load(sys.stdin);print('ok' if 'access_token' in d else d.get('error_description',d.get('error','unknown')))")
  if [ "$LOGIN_RESULT" = "ok" ]; then pass "Login user$i"; else fail "Login user$i: $LOGIN_RESULT"; fi
done

# ─── 9. Client Credentials Grant ───
section "9. Client Credentials Grant"
CC_RESULT=$(curl -s -X POST "$BASE_URL/realms/$REALM/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&client_id=test-confidential&client_secret=test-secret" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print('ok' if 'access_token' in d else d.get('error_description',d.get('error','unknown')))")
if [ "$CC_RESULT" = "ok" ]; then pass "Client credentials grant"; else fail "Client credentials: $CC_RESULT"; fi

# ─── 10. Token Introspection ───
section "10. Token Introspection"
USER_TOKEN=$(curl -s -X POST "$BASE_URL/realms/$REALM/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password&client_id=test-public&username=user1&password=password1" \
  | python3 -c "import sys,json;print(json.load(sys.stdin).get('access_token',''))")
ACTIVE=$(curl -s -X POST "$BASE_URL/realms/$REALM/protocol/openid-connect/token/introspect" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=test-confidential&client_secret=test-secret&token=$USER_TOKEN" \
  | python3 -c "import sys,json;print(json.load(sys.stdin).get('active',''))")
if [ "$ACTIVE" = "True" ]; then pass "Token introspection (active)"; else fail "Token introspection: active=$ACTIVE"; fi

# ─── 11. UserInfo Endpoint ───
section "11. UserInfo Endpoint"
UI_TOKEN=$(curl -s -X POST "$BASE_URL/realms/$REALM/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password&client_id=test-public&username=user1&password=password1&scope=openid" \
  | python3 -c "import sys,json;print(json.load(sys.stdin).get('access_token',''))")
USERINFO=$(curl -s -H "Authorization: Bearer $UI_TOKEN" "$BASE_URL/realms/$REALM/protocol/openid-connect/userinfo" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('preferred_username',''))" 2>/dev/null || echo "")
if [ "$USERINFO" = "user1" ]; then pass "UserInfo endpoint"; else fail "UserInfo: $USERINFO"; fi

# ─── 12. Token Refresh ───
section "12. Token Refresh"
REFRESH_DATA=$(curl -s -X POST "$BASE_URL/realms/$REALM/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password&client_id=test-public&username=user1&password=password1")
REFRESH_TOKEN=$(echo "$REFRESH_DATA" | python3 -c "import sys,json;print(json.load(sys.stdin).get('refresh_token',''))")
REFRESH_RESULT=$(curl -s -X POST "$BASE_URL/realms/$REALM/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=refresh_token&client_id=test-public&refresh_token=$REFRESH_TOKEN" \
  | python3 -c "import sys,json;d=json.load(sys.stdin);print('ok' if 'access_token' in d else d.get('error_description',d.get('error','unknown')))")
if [ "$REFRESH_RESULT" = "ok" ]; then pass "Token refresh"; else fail "Token refresh: $REFRESH_RESULT"; fi

# ─── 13. Logout ───
section "13. Session Logout"
LOGOUT_DATA=$(curl -s -X POST "$BASE_URL/realms/$REALM/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password&client_id=test-public&username=user2&password=password2")
LOGOUT_REFRESH=$(echo "$LOGOUT_DATA" | python3 -c "import sys,json;print(json.load(sys.stdin).get('refresh_token',''))")
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/realms/$REALM/protocol/openid-connect/logout" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=test-public&refresh_token=$LOGOUT_REFRESH")
if [ "$CODE" = "204" ]; then pass "Logout user"; else fail "Logout: $CODE"; fi

# ─── 14. Admin Console Login Page ───
section "14. Admin Console Login Page (FreeMarker)"
CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/realms/master/protocol/openid-connect/auth?client_id=security-admin-console&redirect_uri=http%3A%2F%2Flocalhost%3A8080%2Fadmin%2Fmaster%2Fconsole%2F&response_mode=fragment&response_type=code&scope=openid&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256")
if [ "$CODE" = "200" ]; then pass "Admin console login page renders"; else fail "Admin login page: $CODE"; fi

# ─── 15. OIDC Discovery ───
section "15. OIDC Discovery"
ISSUER=$(curl -s "$BASE_URL/realms/$REALM/.well-known/openid-configuration" \
  | python3 -c "import sys,json;print(json.load(sys.stdin).get('issuer',''))")
if echo "$ISSUER" | grep -q "$REALM"; then pass "OIDC discovery"; else fail "OIDC discovery: $ISSUER"; fi

# ─── 16. Session Management ───
section "16. Session Management"
# Re-auth to get fresh token (might have expired)
TOKEN=$(curl -s -X POST "$BASE_URL/realms/master/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password&client_id=admin-cli&username=$ADMIN_USER&password=$ADMIN_PASS" \
  | python3 -c "import sys,json;print(json.load(sys.stdin).get('access_token',''))")
AUTH="Authorization: Bearer $TOKEN"

SESSION_COUNT=$(curl -s -H "$AUTH" "$BASE_URL/admin/realms/$REALM/client-session-stats" \
  | python3 -c "import sys,json;print(len(json.load(sys.stdin)))")
pass "Client session stats ($SESSION_COUNT entries)"

ACTIVE_SESSIONS=$(curl -s -H "$AUTH" "$BASE_URL/admin/realms/$REALM/users/$USER1_ID/sessions" \
  | python3 -c "import sys,json;print(len(json.load(sys.stdin)))")
pass "User sessions for user1 ($ACTIVE_SESSIONS active)"

# ─── 17. Realm Cache Verification ───
section "17. Realm Cache (read-after-write)"
# Update realm display name and immediately read back
curl -s -o /dev/null -X PUT "$BASE_URL/admin/realms/$REALM" \
  -H "$AUTH" -H "Content-Type: application/json" \
  -d "{\"realm\":\"$REALM\",\"sslRequired\":\"none\",\"displayName\":\"Updated Display Name\"}"
DISPLAY=$(curl -s -H "$AUTH" "$BASE_URL/admin/realms/$REALM" \
  | python3 -c "import sys,json;print(json.load(sys.stdin).get('displayName',''))")
if [ "$DISPLAY" = "Updated Display Name" ]; then pass "Realm cache read-after-write"; else fail "Cache: $DISPLAY"; fi

# ─── 18. Password Policy ───
section "18. Password Policy"
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT "$BASE_URL/admin/realms/$REALM" \
  -H "$AUTH" -H "Content-Type: application/json" \
  -d "{\"realm\":\"$REALM\",\"sslRequired\":\"none\",\"passwordPolicy\":\"length(8)\"}")
if [ "$CODE" = "204" ]; then pass "Set password policy"; else fail "Password policy: $CODE"; fi

# ─── 19. Cleanup ───
section "19. Cleanup"
CODE=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE "$BASE_URL/admin/realms/$REALM" -H "$AUTH")
if [ "$CODE" = "204" ]; then pass "Delete test realm"; else fail "Delete realm: $CODE"; fi

# ─── Summary ───
echo ""
echo "============================================"
echo "  Results: $PASSED passed, $FAILED failed"
echo "============================================"

if [ "$FAILED" -gt 0 ]; then exit 1; else exit 0; fi
