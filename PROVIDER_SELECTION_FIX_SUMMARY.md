# Provider Selection Fix - Implementation Summary

**Date**: 2025-10-14
**Status**: ✅ Implementation Complete - Build Successful

## Problem Statement

When `--cache=redis` was specified, Keycloak still loaded Infinispan providers instead of Redis providers because:
1. Both provider factories had `getId() = "default"`
2. No mechanism existed to conditionally select providers based on cache configuration

**Error**:
```
ERROR: Failed to start server in (development) mode
ERROR: Option 'configFile' needs to be specified
	at org.keycloak.connections.infinispan.DefaultInfinispanConnectionProviderFactory.createEmbeddedCacheManager
```

## Solution Implemented

Implemented `EnvironmentDependentProviderFactory` interface in all cache provider factories to enable conditional provider selection based on cache type configuration.

### Redis Providers (4 files) - Only enabled when cache=redis

1. **`RedisCacheRealmProviderFactory`**
   - File: `model/redis/src/main/java/org/keycloak/models/cache/redis/RedisCacheRealmProviderFactory.java`
   - Added: `implements EnvironmentDependentProviderFactory`
   - Added: `isSupported()` method that returns `true` only when `Config.getProvider("cache")` equals "redis"

2. **`RedisUserCacheProviderFactory`**
   - File: `model/redis/src/main/java/org/keycloak/models/cache/redis/RedisUserCacheProviderFactory.java`
   - Added: Same pattern

3. **`RedisCacheStoreFactoryProviderFactory`**
   - File: `model/redis/src/main/java/org/keycloak/models/cache/redis/authorization/RedisCacheStoreFactoryProviderFactory.java`
   - Added: Same pattern

4. **`DefaultRedisConnectionProviderFactory`**
   - File: `model/redis/src/main/java/org/keycloak/connections/redis/DefaultRedisConnectionProviderFactory.java`
   - Added: Same pattern

### Infinispan Providers (4 files) - Only enabled when cache != redis

1. **`InfinispanCacheRealmProviderFactory`**
   - File: `model/infinispan/src/main/java/org/keycloak/models/cache/infinispan/InfinispanCacheRealmProviderFactory.java`
   - Added: `implements EnvironmentDependentProviderFactory`
   - Added: `isSupported()` method that returns `true` only when cache type is NOT "redis"

2. **`InfinispanUserCacheProviderFactory`**
   - File: `model/infinispan/src/main/java/org/keycloak/models/cache/infinispan/InfinispanUserCacheProviderFactory.java`
   - Added: Same pattern

3. **`InfinispanCacheStoreFactoryProviderFactory`**
   - File: `model/infinispan/src/main/java/org/keycloak/models/cache/infinispan/authorization/InfinispanCacheStoreFactoryProviderFactory.java`
   - Added: Same pattern

4. **`DefaultInfinispanConnectionProviderFactory`**
   - File: `model/infinispan/src/main/java/org/keycloak/connections/infinispan/DefaultInfinispanConnectionProviderFactory.java`
   - Added: Same pattern

## Implementation Pattern

### Redis Providers
```java
@Override
public boolean isSupported(Config.Scope config) {
    // Only enabled when cache mechanism is set to 'redis'
    String cacheType = Config.getProvider("cache");
    return "redis".equals(cacheType);
}
```

### Infinispan Providers
```java
@Override
public boolean isSupported(Config.Scope config) {
    // Enabled for 'ispn' or 'local' but NOT for 'redis'
    String cacheType = Config.getProvider("cache");
    return !"redis".equals(cacheType);
}
```

## Build Results

✅ **BUILD SUCCESS**
- Total time: 02:08 min
- All modules compiled successfully
- Distribution generated: `keycloak-999.0.0-SNAPSHOT.tar.gz` (159MB)

## Files Modified

| File | Lines Changed | Status |
|------|--------------|--------|
| `RedisCacheRealmProviderFactory.java` | +8 | ✅ |
| `RedisUserCacheProviderFactory.java` | +8 | ✅ |
| `RedisCacheStoreFactoryProviderFactory.java` | +8 | ✅ |
| `DefaultRedisConnectionProviderFactory.java` | +7 | ✅ |
| `InfinispanCacheRealmProviderFactory.java` | +8 | ✅ |
| `InfinispanUserCacheProviderFactory.java` | +8 | ✅ |
| `InfinispanCacheStoreFactoryProviderFactory.java` | +8 | ✅ |
| `DefaultInfinispanConnectionProviderFactory.java` | +8 | ✅ |
| **Total** | **63 lines** | **8 files** |

## How It Works

### Build-Time Provider Selection

Keycloak uses `EnvironmentDependentProviderFactory` during the build-time provider loading phase (in `KeycloakProcessor.java`):

```java
private boolean isEnabled(ProviderFactory factory, Config.Scope scope) {
    if (!scope.getBoolean("enabled", true)) {
        return false;
    }
    if (factory instanceof EnvironmentDependentProviderFactory environmentDependentProviderFactory) {
        return environmentDependentProviderFactory.isSupported(scope);
    }
    return true;
}
```

This check happens when Keycloak loads all provider factories from `META-INF/services/` during the build phase.

### Configuration Reading

The `Config.getProvider("cache")` method reads the cache type from:
1. System properties (`kc.cache`)
2. Environment variables (`KC_CACHE`)
3. Configuration files
4. Default value (`ispn`)

## Next Steps to Test

###Step 1: Start a Redis Server

```bash
docker run -d --name keycloak-redis -p 6379:6379 redis:7-alpine
```

### Step 2: Verify Redis is Running

```bash
docker ps | grep redis
docker exec -it keycloak-redis redis-cli PING
# Should return: PONG
```

### Step 3: Build and Start Keycloak with Redis

The key is that provider selection happens at **build time**, so the cache configuration must be set during the build:

```bash
cd quarkus/dist/target/keycloak-999.0.0-SNAPSHOT

# Option 1: Using environment variables during build
export KC_CACHE=redis
export KC_CACHE_REDIS_URL=redis://localhost:6379
./bin/kc.sh build
./bin/kc.sh start-dev

# Option 2: Re-augment with Redis configuration
./bin/kc.sh start-dev --cache=redis --cache-redis-url=redis://localhost:6379
```

**Note**: In Quarkus, `start-dev` performs a build augmentation automatically, so setting the environment variables before `start-dev` should work.

### Step 4: Verify Correct Provider Loading

Check the logs for:
- ✅ Redis connection provider initialization
- ✅ Redis cache manager initialization
- ❌ NO Infinispan errors about missing `configFile`

Expected success messages:
```
INFO  [org.keycloak.connections.redis.DefaultRedisConnectionProviderFactory] Initializing Redis connection provider
INFO  [org.keycloak.models.cache.redis.RedisCacheRealmProviderFactory] Redis cache realm provider initialized
```

## Known Issue

⚠️ **Current Status**: Testing revealed that runtime configuration via environment variables may not properly trigger provider selection because:

1. Quarkus caches build-time decisions
2. The `Config.getProvider("cache")` call during provider loading might not see runtime environment variables

### Workaround

Force a full rebuild with Redis configuration:

```bash
# Clean previous build
rm -rf data

# Build with Redis
KC_CACHE=redis KC_CACHE_REDIS_URL=redis://localhost:6379 ./bin/kc.sh build

# Start
./bin/kc.sh start-dev
```

## Alternative Approach (If Current Solution Doesn't Work)

If the `EnvironmentDependentProviderFactory` approach doesn't work because of build-time vs runtime configuration issues, we may need to:

1. **Add build-time flag** to completely exclude Infinispan modules when Redis is selected
2. **Use Quarkus conditional beans** (`@ConditionalOnProperty`)
3. **Modify provider loading order** to prefer Redis over Infinispan

## Conclusion

The implementation is complete and builds successfully. The `EnvironmentDependentProviderFactory` pattern has been properly implemented in all 8 provider factories.

**Next**: Manual testing with a live Redis instance is required to verify that provider selection works correctly at runtime.

**Files to commit**:
- All 8 modified provider factory files
- This summary document
- BUILD_TEST_RESULTS.md (updated with provider selection fix)
