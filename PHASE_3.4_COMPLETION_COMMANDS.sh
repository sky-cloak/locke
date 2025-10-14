#!/bin/bash

# Phase 3.4 Completion Commands
# Run these commands to complete the session provider implementation

set -e

REDIS_DIR="model/redis/src/main/java/org/keycloak/models/sessions/redis"
INFINISPAN_DIR="model/infinispan/src/main/java/org/keycloak/models/sessions/infinispan"

echo "=== Completing Phase 3.4: Session Providers ==="

# Step 1: Port Authentication Session Adapters
echo "Step 1: Porting Authentication Session Adapters..."

cp $INFINISPAN_DIR/RootAuthenticationSessionAdapter.java $REDIS_DIR/
cp $INFINISPAN_DIR/AuthenticationSessionAdapter.java $REDIS_DIR/

# Apply replacements to RootAuthenticationSessionAdapter
sed -i '' 's/package org\.keycloak\.models\.sessions\.infinispan/package org.keycloak.models.sessions.redis/g' $REDIS_DIR/RootAuthenticationSessionAdapter.java
sed -i '' 's/InfinispanAuthenticationSessionProvider/RedisAuthenticationSessionProvider/g' $REDIS_DIR/RootAuthenticationSessionAdapter.java
sed -i '' 's/import org\.keycloak\.models\.sessions\.infinispan\./import org.keycloak.models.sessions.redis./g' $REDIS_DIR/RootAuthenticationSessionAdapter.java

# Apply replacements to AuthenticationSessionAdapter
sed -i '' 's/package org\.keycloak\.models\.sessions\.infinispan/package org.keycloak.models.sessions.redis/g' $REDIS_DIR/AuthenticationSessionAdapter.java
sed -i '' 's/InfinispanAuthenticationSessionProvider/RedisAuthenticationSessionProvider/g' $REDIS_DIR/AuthenticationSessionAdapter.java
sed -i '' 's/import org\.keycloak\.models\.sessions\.infinispan\./import org.keycloak.models.sessions.redis./g' $REDIS_DIR/AuthenticationSessionAdapter.java

echo "✓ Authentication Session Adapters ported"

# Step 2: Add AUTH_SESSIONS_CACHE_NAME constant
echo "Step 2: Adding cache name constant..."

# This needs to be done manually in RedisConnectionProvider.java
# Add this line after line 52:
#     String AUTH_SESSIONS_CACHE_NAME = "authenticationSessions";

echo "⚠ Manual step required: Add AUTH_SESSIONS_CACHE_NAME constant to RedisConnectionProvider.java"

# Step 3: Create SPI registration file
echo "Step 3: Creating SPI registration..."

mkdir -p model/redis/src/main/resources/META-INF/services

cat > model/redis/src/main/resources/META-INF/services/org.keycloak.sessions.AuthenticationSessionProviderFactory <<'EOF'
#
# Copyright 2025 Red Hat, Inc. and/or its affiliates
# and other contributors as indicated by the @author tags.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

org.keycloak.models.sessions.redis.RedisAuthenticationSessionProviderFactory
EOF

echo "✓ SPI registration created"

# Step 4: Build to verify
echo "Step 4: Building..."

./mvnw clean compile -f model/redis/pom.xml -DskipTests

echo "✓ Build complete"

echo ""
echo "=== Authentication Session Provider Complete ==="
echo ""
echo "Next steps for User Session Provider:"
echo "1. Port RedisUserSessionProvider.java (largest file ~1000 lines)"
echo "2. Port RedisUserSessionProviderFactory.java"
echo "3. Port UserSessionAdapter.java and AuthenticatedClientSessionAdapter.java"
echo "4. Add 4 cache name constants to RedisConnectionProvider"
echo "5. Create SPI registration"
echo "6. Create tests"
echo ""
echo "See PHASE_3.4_IMPLEMENTATION_GUIDE.md for detailed instructions"
