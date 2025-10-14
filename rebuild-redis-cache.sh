#!/bin/bash
#
# Redis Cache Implementation Rebuild Script
# Automates mechanical porting of adapter files from Infinispan to Redis
#
# Usage: ./rebuild-redis-cache.sh [phase]
# Phases: 3.1 (realm), 3.2 (user), 3.3 (authz), all
#

set -e  # Exit on error

INFINISPAN_CACHE="model/infinispan/src/main/java/org/keycloak/models/cache/infinispan"
REDIS_CACHE="model/redis/src/main/java/org/keycloak/models/cache/redis"

echo "======================================"
echo "Redis Cache Rebuild Script"
echo "======================================"
echo ""

# Create directory structure
mkdir -p "$REDIS_CACHE"
echo "✓ Created directory: $REDIS_CACHE"

# Phase selection
PHASE=${1:-all}

if [ "$PHASE" = "3.1" ] || [ "$PHASE" = "all" ]; then
    echo ""
    echo "Phase 3.1: Copying Realm Cache adapters..."
    echo "----------------------------------------"

    # Adapter files to copy
    ADAPTERS=(
        "RealmAdapter"
        "ClientAdapter"
        "ClientScopeAdapter"
        "RoleAdapter"
        "GroupAdapter"
    )

    for adapter in "${ADAPTERS[@]}"; do
        if [ -f "$INFINISPAN_CACHE/${adapter}.java" ]; then
            cp "$INFINISPAN_CACHE/${adapter}.java" "$REDIS_CACHE/${adapter}.java"
            echo "  ✓ Copied ${adapter}.java"

            # Replace package declaration
            sed -i '' 's/package org\.keycloak\.models\.cache\.infinispan/package org.keycloak.models.cache.redis/g' \
                "$REDIS_CACHE/${adapter}.java"

            # Replace Infinispan imports
            sed -i '' 's/import org\.keycloak\.models\.cache\.infinispan/import org.keycloak.models.cache.redis/g' \
                "$REDIS_CACHE/${adapter}.java"

            echo "    → Updated package and imports"
        else
            echo "  ✗ WARNING: ${adapter}.java not found in Infinispan"
        fi
    done

    echo ""
    echo "Phase 3.1: Manual files needed (cannot be automated):"
    echo "  - RedisCacheRealmProvider.java"
    echo "  - RedisCacheRealmProviderFactory.java"
    echo "  - RealmCacheManager.java"
    echo "  - RealmCacheSession.java"
    echo "  - IdentityProviderAdapter.java"
    echo "  - AuthenticationFlowAdapter.java"
    echo "  - META-INF/services/org.keycloak.models.cache.CacheRealmProviderFactory"
fi

if [ "$PHASE" = "3.2" ] || [ "$PHASE" = "all" ]; then
    echo ""
    echo "Phase 3.2: Copying User Cache adapters..."
    echo "----------------------------------------"

    USER_ADAPTERS=(
        "UserAdapter"
    )

    for adapter in "${USER_ADAPTERS[@]}"; do
        if [ -f "$INFINISPAN_CACHE/${adapter}.java" ]; then
            cp "$INFINISPAN_CACHE/${adapter}.java" "$REDIS_CACHE/${adapter}.java"
            echo "  ✓ Copied ${adapter}.java"

            sed -i '' 's/package org\.keycloak\.models\.cache\.infinispan/package org.keycloak.models.cache.redis/g' \
                "$REDIS_CACHE/${adapter}.java"
            sed -i '' 's/import org\.keycloak\.models\.cache\.infinispan/import org.keycloak.models.cache.redis/g' \
                "$REDIS_CACHE/${adapter}.java"

            echo "    → Updated package and imports"
        fi
    done

    echo ""
    echo "Phase 3.2: Manual files needed:"
    echo "  - RedisUserCacheProvider.java"
    echo "  - RedisUserCacheProviderFactory.java"
    echo "  - UserCacheManager.java"
    echo "  - UserCacheSession.java"
    echo "  - META-INF/services/org.keycloak.models.cache.UserCacheProviderFactory"
fi

if [ "$PHASE" = "3.3" ] || [ "$PHASE" = "all" ]; then
    echo ""
    echo "Phase 3.3: Authorization Cache"
    echo "----------------------------------------"
    echo "  Note: Authorization adapters are in a subdirectory"

    AUTHZ_DIR="$REDIS_CACHE/authorization"
    mkdir -p "$AUTHZ_DIR"

    AUTHZ_INFINISPAN="model/infinispan/src/main/java/org/keycloak/models/cache/infinispan/authorization"

    AUTHZ_ADAPTERS=(
        "ResourceServerAdapter"
        "ResourceAdapter"
        "ScopeAdapter"
        "PolicyAdapter"
        "PermissionTicketAdapter"
    )

    for adapter in "${AUTHZ_ADAPTERS[@]}"; do
        if [ -f "$AUTHZ_INFINISPAN/${adapter}.java" ]; then
            cp "$AUTHZ_INFINISPAN/${adapter}.java" "$AUTHZ_DIR/${adapter}.java"
            echo "  ✓ Copied authorization/${adapter}.java"

            sed -i '' 's/package org\.keycloak\.models\.cache\.infinispan\.authorization/package org.keycloak.models.cache.redis.authorization/g' \
                "$AUTHZ_DIR/${adapter}.java"
            sed -i '' 's/import org\.keycloak\.models\.cache\.infinispan/import org.keycloak.models.cache.redis/g' \
                "$AUTHZ_DIR/${adapter}.java"

            echo "    → Updated package and imports"
        fi
    done

    echo ""
    echo "Phase 3.3: Manual files needed:"
    echo "  - RedisCachedStoreProvider.java"
    echo "  - RedisCachedStoreProviderFactory.java"
    echo "  - AuthorizationCacheSession.java"
    echo "  - META-INF/services/org.keycloak.models.cache.authorization.CachedStoreProviderFactory"
fi

echo ""
echo "======================================"
echo "Script complete!"
echo "======================================"
echo ""
echo "Next steps:"
echo "1. Review copied files for compilation errors"
echo "2. Create manual files (providers, factories, managers)"
echo "3. Update Infinispan-specific code to use Redis APIs"
echo "4. Compile: ./mvnw compile -f model/redis/pom.xml -DskipTests"
echo "5. Commit: git add model/redis/ && git commit -m 'Phase 3.X: Add adapters'"
echo ""
echo "Common replacements needed:"
echo "  - InfinispanCache → RedisCache"
echo "  - Cache<K,V> cache → RedisCache<K,V> cache"
echo "  - cache.get() → cache.get() (same API)"
echo "  - cache.put() → cache.put() (same API)"
echo ""
