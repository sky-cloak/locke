#!/bin/bash
# Keycloak Redis Load Test Script
# Exercises core Keycloak features under concurrent load
#
# Usage: ./load-test-redis.sh [NUM_USERS] [CONCURRENT] [ITERATIONS]
#   NUM_USERS:  Number of test users to create (default: 50)
#   CONCURRENT: Number of concurrent requests (default: 10)
#   ITERATIONS: Number of login iterations per user (default: 5)

set -e

BASE_URL="${KEYCLOAK_URL:-http://localhost:8080}"
ADMIN_USER="${KEYCLOAK_ADMIN:-admin}"
ADMIN_PASS="${KEYCLOAK_ADMIN_PASSWORD:-admin}"
REALM="load-test"
NUM_USERS="${1:-50}"
CONCURRENT="${2:-10}"
ITERATIONS="${3:-5}"

TOTAL_REQUESTS=0
TOTAL_ERRORS=0
START_TIME=$(date +%s)

log() { echo "[$(date +%H:%M:%S)] $1"; }

# ─── Setup ───
log "============================================"
log "  Keycloak Redis Load Test"
log "  Users: $NUM_USERS | Concurrency: $CONCURRENT | Iterations: $ITERATIONS"
log "  Target: $BASE_URL"
log "============================================"

# Get admin token
log "Getting admin token..."
TOKEN=$(curl -s -X POST "$BASE_URL/realms/master/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password&client_id=admin-cli&username=$ADMIN_USER&password=$ADMIN_PASS" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")
AUTH="Authorization: Bearer $TOKEN"

# Clean up previous run
curl -s -o /dev/null -X DELETE "$BASE_URL/admin/realms/$REALM" -H "$AUTH" 2>/dev/null || true

# Create realm
log "Creating test realm..."
curl -s -o /dev/null -w "" -X POST "$BASE_URL/admin/realms" \
  -H "$AUTH" -H "Content-Type: application/json" \
  -d "{\"realm\":\"$REALM\",\"enabled\":true,\"sslRequired\":\"none\"}"

# Create clients
log "Creating test clients..."
curl -s -o /dev/null -X POST "$BASE_URL/admin/realms/$REALM/clients" \
  -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"clientId":"load-test-public","enabled":true,"publicClient":true,"directAccessGrantsEnabled":true,"redirectUris":["*"]}'

curl -s -o /dev/null -X POST "$BASE_URL/admin/realms/$REALM/clients" \
  -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"clientId":"load-test-confidential","enabled":true,"publicClient":false,"secret":"load-test-secret","serviceAccountsEnabled":true,"directAccessGrantsEnabled":true,"redirectUris":["*"]}'

# Create roles
curl -s -o /dev/null -X POST "$BASE_URL/admin/realms/$REALM/roles" \
  -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"name":"load-test-role"}'

# Create groups
curl -s -o /dev/null -X POST "$BASE_URL/admin/realms/$REALM/groups" \
  -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"name":"load-test-group"}'

# ─── Create Users ───
log "Creating $NUM_USERS test users..."
USER_CREATE_START=$(date +%s%N)
BATCH_SIZE=$CONCURRENT
for batch_start in $(seq 1 $BATCH_SIZE $NUM_USERS); do
  batch_end=$((batch_start + BATCH_SIZE - 1))
  if [ $batch_end -gt $NUM_USERS ]; then batch_end=$NUM_USERS; fi

  for i in $(seq $batch_start $batch_end); do
    curl -s -o /dev/null -X POST "$BASE_URL/admin/realms/$REALM/users" \
      -H "$AUTH" -H "Content-Type: application/json" \
      -d "{\"username\":\"loaduser$i\",\"enabled\":true,\"email\":\"loaduser$i@test.com\",\"firstName\":\"Load\",\"lastName\":\"User$i\",\"credentials\":[{\"type\":\"password\",\"value\":\"loadpass$i\",\"temporary\":false}]}" &
  done
  wait
done
USER_CREATE_END=$(date +%s%N)
USER_CREATE_MS=$(( (USER_CREATE_END - USER_CREATE_START) / 1000000 ))
log "Created $NUM_USERS users in ${USER_CREATE_MS}ms ($(( NUM_USERS * 1000 / (USER_CREATE_MS + 1) )) users/sec)"

# ─── Load Test: Password Grant Login ───
log ""
log "=== Test 1: Password Grant Login ($NUM_USERS users x $ITERATIONS iterations) ==="
TMPDIR=$(mktemp -d)
LOGIN_START=$(date +%s%N)
LOGIN_COUNT=0
LOGIN_ERRORS=0

for iter in $(seq 1 $ITERATIONS); do
  for batch_start in $(seq 1 $CONCURRENT $NUM_USERS); do
    batch_end=$((batch_start + CONCURRENT - 1))
    if [ $batch_end -gt $NUM_USERS ]; then batch_end=$NUM_USERS; fi

    for i in $(seq $batch_start $batch_end); do
      (
        RESULT=$(curl -s -o /dev/null -w "%{http_code}:%{time_total}" -X POST "$BASE_URL/realms/$REALM/protocol/openid-connect/token" \
          -H "Content-Type: application/x-www-form-urlencoded" \
          -d "grant_type=password&client_id=load-test-public&username=loaduser$i&password=loadpass$i")
        echo "$RESULT" >> "$TMPDIR/login_results.txt"
      ) &
    done
    wait
  done
done

LOGIN_END=$(date +%s%N)
LOGIN_MS=$(( (LOGIN_END - LOGIN_START) / 1000000 ))
LOGIN_TOTAL=$(wc -l < "$TMPDIR/login_results.txt" | tr -d ' ')
LOGIN_SUCCESS=$(grep -c "^200:" "$TMPDIR/login_results.txt" || echo 0)
LOGIN_ERRORS=$((LOGIN_TOTAL - LOGIN_SUCCESS))
AVG_TIME=$(awk -F: '{sum+=$2; n++} END {printf "%.0f", sum/n*1000}' "$TMPDIR/login_results.txt")
P95_TIME=$(sort -t: -k2 -n "$TMPDIR/login_results.txt" | awk -F: -v p=$((LOGIN_TOTAL * 95 / 100)) 'NR==p {printf "%.0f", $2*1000}')
TOTAL_REQUESTS=$((TOTAL_REQUESTS + LOGIN_TOTAL))
TOTAL_ERRORS=$((TOTAL_ERRORS + LOGIN_ERRORS))

log "  Requests: $LOGIN_TOTAL | Success: $LOGIN_SUCCESS | Errors: $LOGIN_ERRORS"
log "  Duration: ${LOGIN_MS}ms | RPS: $(( LOGIN_TOTAL * 1000 / (LOGIN_MS + 1) ))"
log "  Avg latency: ${AVG_TIME}ms | P95: ${P95_TIME}ms"

# ─── Load Test: Token Refresh ───
log ""
log "=== Test 2: Token Refresh ($NUM_USERS users) ==="
> "$TMPDIR/refresh_results.txt"
REFRESH_START=$(date +%s%N)

for batch_start in $(seq 1 $CONCURRENT $NUM_USERS); do
  batch_end=$((batch_start + CONCURRENT - 1))
  if [ $batch_end -gt $NUM_USERS ]; then batch_end=$NUM_USERS; fi

  for i in $(seq $batch_start $batch_end); do
    (
      # Get token + refresh token
      TOKEN_DATA=$(curl -s -X POST "$BASE_URL/realms/$REALM/protocol/openid-connect/token" \
        -H "Content-Type: application/x-www-form-urlencoded" \
        -d "grant_type=password&client_id=load-test-public&username=loaduser$i&password=loadpass$i")
      RT=$(echo "$TOKEN_DATA" | python3 -c "import sys,json;print(json.load(sys.stdin).get('refresh_token',''))" 2>/dev/null)
      if [ -n "$RT" ]; then
        RESULT=$(curl -s -o /dev/null -w "%{http_code}:%{time_total}" -X POST "$BASE_URL/realms/$REALM/protocol/openid-connect/token" \
          -H "Content-Type: application/x-www-form-urlencoded" \
          -d "grant_type=refresh_token&client_id=load-test-public&refresh_token=$RT")
        echo "$RESULT" >> "$TMPDIR/refresh_results.txt"
      else
        echo "500:0" >> "$TMPDIR/refresh_results.txt"
      fi
    ) &
  done
  wait
done

REFRESH_END=$(date +%s%N)
REFRESH_MS=$(( (REFRESH_END - REFRESH_START) / 1000000 ))
REFRESH_TOTAL=$(wc -l < "$TMPDIR/refresh_results.txt" | tr -d ' ')
REFRESH_SUCCESS=$(grep -c "^200:" "$TMPDIR/refresh_results.txt" || echo 0)
REFRESH_ERRORS=$((REFRESH_TOTAL - REFRESH_SUCCESS))
AVG_TIME=$(awk -F: '{sum+=$2; n++} END {printf "%.0f", sum/n*1000}' "$TMPDIR/refresh_results.txt")
TOTAL_REQUESTS=$((TOTAL_REQUESTS + REFRESH_TOTAL))
TOTAL_ERRORS=$((TOTAL_ERRORS + REFRESH_ERRORS))

log "  Requests: $REFRESH_TOTAL | Success: $REFRESH_SUCCESS | Errors: $REFRESH_ERRORS"
log "  Duration: ${REFRESH_MS}ms | RPS: $(( REFRESH_TOTAL * 1000 / (REFRESH_MS + 1) ))"
log "  Avg latency: ${AVG_TIME}ms"

# ─── Load Test: Client Credentials Grant ───
log ""
log "=== Test 3: Client Credentials Grant ($((NUM_USERS * 2)) requests) ==="
> "$TMPDIR/cc_results.txt"
CC_START=$(date +%s%N)
CC_TOTAL=$((NUM_USERS * 2))

for batch_start in $(seq 1 $CONCURRENT $CC_TOTAL); do
  batch_end=$((batch_start + CONCURRENT - 1))
  if [ $batch_end -gt $CC_TOTAL ]; then batch_end=$CC_TOTAL; fi

  for i in $(seq $batch_start $batch_end); do
    (
      RESULT=$(curl -s -o /dev/null -w "%{http_code}:%{time_total}" -X POST "$BASE_URL/realms/$REALM/protocol/openid-connect/token" \
        -H "Content-Type: application/x-www-form-urlencoded" \
        -d "grant_type=client_credentials&client_id=load-test-confidential&client_secret=load-test-secret")
      echo "$RESULT" >> "$TMPDIR/cc_results.txt"
    ) &
  done
  wait
done

CC_END=$(date +%s%N)
CC_MS=$(( (CC_END - CC_START) / 1000000 ))
CC_COUNT=$(wc -l < "$TMPDIR/cc_results.txt" | tr -d ' ')
CC_SUCCESS=$(grep -c "^200:" "$TMPDIR/cc_results.txt" || echo 0)
CC_ERRORS=$((CC_COUNT - CC_SUCCESS))
AVG_TIME=$(awk -F: '{sum+=$2; n++} END {printf "%.0f", sum/n*1000}' "$TMPDIR/cc_results.txt")
TOTAL_REQUESTS=$((TOTAL_REQUESTS + CC_COUNT))
TOTAL_ERRORS=$((TOTAL_ERRORS + CC_ERRORS))

log "  Requests: $CC_COUNT | Success: $CC_SUCCESS | Errors: $CC_ERRORS"
log "  Duration: ${CC_MS}ms | RPS: $(( CC_COUNT * 1000 / (CC_MS + 1) ))"
log "  Avg latency: ${AVG_TIME}ms"

# ─── Load Test: Admin API (User Lookup) ───
log ""
log "=== Test 4: Admin API - User Lookup ($NUM_USERS requests) ==="
> "$TMPDIR/admin_results.txt"

# Refresh admin token
TOKEN=$(curl -s -X POST "$BASE_URL/realms/master/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password&client_id=admin-cli&username=$ADMIN_USER&password=$ADMIN_PASS" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")
AUTH="Authorization: Bearer $TOKEN"

ADMIN_START=$(date +%s%N)

for batch_start in $(seq 1 $CONCURRENT $NUM_USERS); do
  batch_end=$((batch_start + CONCURRENT - 1))
  if [ $batch_end -gt $NUM_USERS ]; then batch_end=$NUM_USERS; fi

  for i in $(seq $batch_start $batch_end); do
    (
      RESULT=$(curl -s -o /dev/null -w "%{http_code}:%{time_total}" \
        -H "$AUTH" "$BASE_URL/admin/realms/$REALM/users?username=loaduser$i")
      echo "$RESULT" >> "$TMPDIR/admin_results.txt"
    ) &
  done
  wait
done

ADMIN_END=$(date +%s%N)
ADMIN_MS=$(( (ADMIN_END - ADMIN_START) / 1000000 ))
ADMIN_COUNT=$(wc -l < "$TMPDIR/admin_results.txt" | tr -d ' ')
ADMIN_SUCCESS=$(grep -c "^200:" "$TMPDIR/admin_results.txt" || echo 0)
ADMIN_ERRORS=$((ADMIN_COUNT - ADMIN_SUCCESS))
AVG_TIME=$(awk -F: '{sum+=$2; n++} END {printf "%.0f", sum/n*1000}' "$TMPDIR/admin_results.txt")
TOTAL_REQUESTS=$((TOTAL_REQUESTS + ADMIN_COUNT))
TOTAL_ERRORS=$((TOTAL_ERRORS + ADMIN_ERRORS))

log "  Requests: $ADMIN_COUNT | Success: $ADMIN_SUCCESS | Errors: $ADMIN_ERRORS"
log "  Duration: ${ADMIN_MS}ms | RPS: $(( ADMIN_COUNT * 1000 / (ADMIN_MS + 1) ))"
log "  Avg latency: ${AVG_TIME}ms"

# ─── Load Test: UserInfo Endpoint ───
log ""
log "=== Test 5: UserInfo Endpoint ($NUM_USERS requests) ==="
> "$TMPDIR/userinfo_results.txt"
USERINFO_START=$(date +%s%N)

for batch_start in $(seq 1 $CONCURRENT $NUM_USERS); do
  batch_end=$((batch_start + CONCURRENT - 1))
  if [ $batch_end -gt $NUM_USERS ]; then batch_end=$NUM_USERS; fi

  for i in $(seq $batch_start $batch_end); do
    (
      UT=$(curl -s -X POST "$BASE_URL/realms/$REALM/protocol/openid-connect/token" \
        -H "Content-Type: application/x-www-form-urlencoded" \
        -d "grant_type=password&client_id=load-test-public&username=loaduser$i&password=loadpass$i&scope=openid" \
        | python3 -c "import sys,json;print(json.load(sys.stdin).get('access_token',''))" 2>/dev/null)
      if [ -n "$UT" ]; then
        RESULT=$(curl -s -o /dev/null -w "%{http_code}:%{time_total}" \
          -H "Authorization: Bearer $UT" "$BASE_URL/realms/$REALM/protocol/openid-connect/userinfo")
        echo "$RESULT" >> "$TMPDIR/userinfo_results.txt"
      else
        echo "500:0" >> "$TMPDIR/userinfo_results.txt"
      fi
    ) &
  done
  wait
done

USERINFO_END=$(date +%s%N)
USERINFO_MS=$(( (USERINFO_END - USERINFO_START) / 1000000 ))
USERINFO_COUNT=$(wc -l < "$TMPDIR/userinfo_results.txt" | tr -d ' ')
USERINFO_SUCCESS=$(grep -c "^200:" "$TMPDIR/userinfo_results.txt" 2>/dev/null || echo "0")
USERINFO_SUCCESS=$(echo "$USERINFO_SUCCESS" | tr -d '[:space:]')
USERINFO_ERRORS=$((USERINFO_COUNT - USERINFO_SUCCESS))
AVG_TIME=$(awk -F: '{sum+=$2; n++} END {printf "%.0f", sum/n*1000}' "$TMPDIR/userinfo_results.txt")
TOTAL_REQUESTS=$((TOTAL_REQUESTS + USERINFO_COUNT))
TOTAL_ERRORS=$((TOTAL_ERRORS + USERINFO_ERRORS))

log "  Requests: $USERINFO_COUNT | Success: $USERINFO_SUCCESS | Errors: $USERINFO_ERRORS"
log "  Duration: ${USERINFO_MS}ms | RPS: $(( USERINFO_COUNT * 1000 / (USERINFO_MS + 1) ))"
log "  Avg latency: ${AVG_TIME}ms"

# ─── Load Test: Mixed Workload ───
log ""
log "=== Test 6: Mixed Workload (login + refresh + introspect, $NUM_USERS users) ==="
> "$TMPDIR/mixed_results.txt"
MIXED_START=$(date +%s%N)

for batch_start in $(seq 1 $CONCURRENT $NUM_USERS); do
  batch_end=$((batch_start + CONCURRENT - 1))
  if [ $batch_end -gt $NUM_USERS ]; then batch_end=$NUM_USERS; fi

  for i in $(seq $batch_start $batch_end); do
    (
      # Login
      TOKEN_DATA=$(curl -s -X POST "$BASE_URL/realms/$REALM/protocol/openid-connect/token" \
        -H "Content-Type: application/x-www-form-urlencoded" \
        -d "grant_type=password&client_id=load-test-public&username=loaduser$i&password=loadpass$i")
      AT=$(echo "$TOKEN_DATA" | python3 -c "import sys,json;print(json.load(sys.stdin).get('access_token',''))" 2>/dev/null)
      RT=$(echo "$TOKEN_DATA" | python3 -c "import sys,json;print(json.load(sys.stdin).get('refresh_token',''))" 2>/dev/null)

      if [ -n "$AT" ] && [ "$AT" != "" ]; then
        echo "200:login" >> "$TMPDIR/mixed_results.txt"

        # Introspect
        INTRO_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/realms/$REALM/protocol/openid-connect/token/introspect" \
          -H "Content-Type: application/x-www-form-urlencoded" \
          -d "client_id=load-test-confidential&client_secret=load-test-secret&token=$AT")
        echo "$INTRO_CODE:introspect" >> "$TMPDIR/mixed_results.txt"

        # Refresh
        if [ -n "$RT" ]; then
          REFRESH_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$BASE_URL/realms/$REALM/protocol/openid-connect/token" \
            -H "Content-Type: application/x-www-form-urlencoded" \
            -d "grant_type=refresh_token&client_id=load-test-public&refresh_token=$RT")
          echo "$REFRESH_CODE:refresh" >> "$TMPDIR/mixed_results.txt"
        fi
      else
        echo "500:login" >> "$TMPDIR/mixed_results.txt"
      fi
    ) &
  done
  wait
done

MIXED_END=$(date +%s%N)
MIXED_MS=$(( (MIXED_END - MIXED_START) / 1000000 ))
MIXED_COUNT=$(wc -l < "$TMPDIR/mixed_results.txt" | tr -d ' ')
MIXED_SUCCESS=$(grep -c "^200:" "$TMPDIR/mixed_results.txt" || echo 0)
MIXED_ERRORS=$((MIXED_COUNT - MIXED_SUCCESS))
TOTAL_REQUESTS=$((TOTAL_REQUESTS + MIXED_COUNT))
TOTAL_ERRORS=$((TOTAL_ERRORS + MIXED_ERRORS))

log "  Operations: $MIXED_COUNT | Success: $MIXED_SUCCESS | Errors: $MIXED_ERRORS"
log "  Duration: ${MIXED_MS}ms | OPS: $(( MIXED_COUNT * 1000 / (MIXED_MS + 1) ))"

# ─── Cleanup ───
log ""
log "Cleaning up test realm..."
TOKEN=$(curl -s -X POST "$BASE_URL/realms/master/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password&client_id=admin-cli&username=$ADMIN_USER&password=$ADMIN_PASS" \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])")
curl -s -o /dev/null -X DELETE "$BASE_URL/admin/realms/$REALM" -H "Authorization: Bearer $TOKEN"

# Check for errors in Keycloak logs
log "Checking server logs for errors..."
ERROR_COUNT=$(docker compose -f docker-compose-redis.yml logs keycloak 2>&1 | grep -ci "exception\|NPE\|null.*pointer" | tr -d ' ' || echo 0)
if [ "$ERROR_COUNT" -gt 0 ]; then
  log "WARNING: Found $ERROR_COUNT error(s) in server logs"
else
  log "No errors in server logs"
fi

END_TIME=$(date +%s)
TOTAL_DURATION=$((END_TIME - START_TIME))

rm -rf "$TMPDIR"

# ─── Summary ───
log ""
log "============================================"
log "  Load Test Summary"
log "============================================"
log "  Total Requests:  $TOTAL_REQUESTS"
log "  Total Errors:    $TOTAL_ERRORS"
log "  Error Rate:      $(( TOTAL_ERRORS * 100 / (TOTAL_REQUESTS + 1) ))%"
log "  Total Duration:  ${TOTAL_DURATION}s"
log "  Overall RPS:     $(( TOTAL_REQUESTS / (TOTAL_DURATION + 1) ))"
log "============================================"

if [ "$TOTAL_ERRORS" -gt 0 ]; then
  log "RESULT: COMPLETED WITH ERRORS"
  exit 1
else
  log "RESULT: ALL PASSED"
  exit 0
fi
