# Redis Session Providers — Implementation Plan

**Branch**: `feature/redis`
**Date**: 2026-03-05
**Status**: Planning

---

## Problem Statement

When `--cache=redis` is set, 5 Infinispan session provider factories still activate because their `isSupported()` checks call `InfinispanUtils.isEmbeddedInfinispan()`, which returns `true` for Redis (it only checks for remote Infinispan / multi-site). These providers then fail at runtime because `InfinispanConnectionProvider` is disabled.

**Affected SPIs (no Redis implementation exists):**

| SPI | Infinispan Factory | Data Persistence | Complexity |
|-----|--------------------|------------------|------------|
| `UserSessionProvider` | `InfinispanUserSessionProviderFactory` | JPA (source of truth) + cache | High |
| `AuthenticationSessionProvider` | `InfinispanAuthenticationSessionProviderFactory` | Cache-only (transient) | Medium |
| `UserLoginFailureProvider` | `InfinispanUserLoginFailureProviderFactory` | Cache-only | Low |
| `SingleUseObjectProvider` | `InfinispanSingleUseObjectProviderFactory` | Cache-only + optional JPA for revoked tokens | Low |
| `StickySessionEncoderProvider` | `InfinispanStickySessionEncoderProviderFactory` | None (routing logic) | Low |

---

## Architecture Decisions

### AD-1: UserSessionProvider — Leverage PersistentUserSessionProvider directly

**Decision**: Do NOT rewrite `PersistentUserSessionProvider`. Instead, create a `RedisUserSessionProviderFactory` that instantiates the SAME `PersistentUserSessionProvider` class but with a `null` cache (no-cache mode).

**Rationale**: In KC26, `PersistentUserSessionProvider` already supports optional caching. The `asyncCommit()` method in `PersistentSessionsChangelogBasedTransaction` checks `if (c.cache() != null)` before writing to cache. When cache is null, it only writes to JPA. The JPA persister (`UserSessionPersisterProvider`) is the source of truth and works independently of any cache.

**Trade-off**: No Redis session caching means slightly higher DB load for session lookups. This is acceptable because:
1. KC26 persistent sessions are designed for DB-first operation
2. Session reads are already optimized with DB indexes
3. Adding a Redis cache layer for sessions can be Phase 2 (optional optimization)
4. The Infinispan cache for sessions was primarily for pre-KC26 volatile sessions

**Alternative considered**: Implementing a full `RedisCacheHolder` abstraction to replace `CacheHolder<K,V>`. This would require reimplementing `SessionEntityWrapper`, `InfinispanChangesUtils.runOperationInCluster()`, and the entire changelog transaction layer — ~2000 lines of deeply Infinispan-coupled code. Not justified when JPA already serves as the source of truth.

### AD-2: AuthenticationSessionProvider — Redis as primary store

**Decision**: Implement `RedisAuthenticationSessionProvider` with Redis as the sole store (matching Infinispan's cache-only pattern). Auth sessions are transient login flows (code exchanges, SAML assertions) with short TTLs (typically 5 minutes).

**Implementation**: Store `RootAuthenticationSessionEntity` serialized in Redis with TTL. Use Redis hash for the root session and its child authentication sessions.

### AD-3: UserLoginFailureProvider — Redis as primary store

**Decision**: Implement `RedisUserLoginFailureProvider` with Redis as the sole store. Login failure counters are ephemeral brute-force protection data.

**Implementation**: Store as Redis hash with key `loginFailure:{realmId}:{userId}`. Use Redis atomic operations for incrementing counters.

### AD-4: SingleUseObjectProvider — Redis with TTL

**Decision**: Implement `RedisSingleUseObjectProvider` using Redis `SET` with `NX` (set-if-not-exists) for single-use guarantee and `EX` for automatic expiration.

**Implementation**: Each action token becomes a Redis key with TTL. The `put()` uses `SETNX`, `get()` reads, `remove()` does an atomic get-and-delete via a Lua `GET`+`DEL` script (equivalent to `GETDEL` but runs on Redis 6.0; see adr/0003). Revoked token persistence delegates to `SingleUseObjectPersisterProvider` (existing JPA implementation).

### AD-5: StickySessionEncoderProvider — No topology routing

**Decision**: Implement `RedisStickySessionEncoderProvider` that returns the current node's name as the route, without topology-aware routing.

**Rationale**: Infinispan's sticky session routing uses `DistributionManager.getCacheTopology()` to route requests to the node that owns the session data in the distributed cache. With Redis, there is no distributed cache ownership — all nodes read from the same Redis instance. Any node can serve any session. Sticky routing is only needed for load balancer affinity, which is handled by returning a stable node identifier.

---

## Implementation Plan

### Phase 5.1: Fix Provider Selection (Prerequisite)

**Goal**: Prevent Infinispan session factories from activating when `--cache=redis`.

**Files to modify:**

1. `model/infinispan/.../InfinispanUserSessionProviderFactory.java`
   - Change `isSupported()` to: `return InfinispanUtils.isEmbeddedInfinispan() && !"redis".equals(getProviderType());`
   - Or simpler: add `&& !"redis".equals(Config.getProvider("cache"))` check

2. `model/infinispan/.../InfinispanAuthenticationSessionProviderFactory.java` — same fix

3. `model/infinispan/.../InfinispanUserLoginFailureProviderFactory.java` — same fix

4. `model/infinispan/.../InfinispanSingleUseObjectProviderFactory.java` — same fix

5. `model/infinispan/.../InfinispanStickySessionEncoderProviderFactory.java` — same fix

6. `model/infinispan/.../ExpirationTaskFactory.java` — add `EnvironmentDependentProviderFactory` implementation

7. `model/redis/.../RedisClusterProviderFactory.java` — add `EnvironmentDependentProviderFactory` if not already present

**Pattern** (same as existing Redis cache factories):
```java
@Override
public boolean isSupported(Config.Scope config) {
    return !"redis".equals(Config.getProvider("cache"));
}
```

### Phase 5.2: RedisUserSessionProviderFactory

**Goal**: Provide a `UserSessionProvider` when `--cache=redis` that delegates entirely to JPA persistent sessions.

**New files:**
- `model/redis/src/main/java/org/keycloak/models/sessions/redis/RedisUserSessionProviderFactory.java`

**Approach**: This factory creates a `PersistentUserSessionProvider` with null cache holders. The `PersistentUserSessionProvider` already exists in `model/infinispan` and handles this gracefully.

However, `PersistentUserSessionProvider` is in the `model/infinispan` module and imports Infinispan types. We have two sub-options:

**Option A — Wrapper factory (preferred):**
Create `RedisUserSessionProviderFactory` that delegates to `InfinispanUserSessionProviderFactory` internals but provides null caches. Since `PersistentSessionsChangelogBasedTransaction` checks `if (c.cache() != null)`, passing null cache holders means only JPA writes happen.

**Option B — JPA-only provider:**
Create a new `RedisUserSessionProvider` that directly uses `UserSessionPersisterProvider` for all operations, bypassing the changelog transaction layer entirely. Simpler but duplicates session-to-model mapping logic.

**Recommended**: Option B — a thin JPA-delegating provider. It avoids depending on Infinispan module internals and is cleaner long-term.

```java
public class RedisUserSessionProviderFactory implements UserSessionProviderFactory<RedisUserSessionProvider>,
                                                        EnvironmentDependentProviderFactory {
    @Override
    public RedisUserSessionProvider create(KeycloakSession session) {
        return new RedisUserSessionProvider(session);
    }

    @Override
    public boolean isSupported(Config.Scope config) {
        return "redis".equals(Config.getProvider("cache"));
    }
}
```

**`RedisUserSessionProvider`** delegates to:
- `UserSessionPersisterProvider` for CRUD (already handles all DB operations)
- `session.getProvider(UserSessionPersisterProvider.class)` for queries

**Key methods (~25) grouped by complexity:**

| Method Group | Count | Implementation |
|-------------|-------|----------------|
| Create session | 2 | `persister.createUserSession()` + model adapter |
| Get session by ID | 3 | `persister.loadUserSession()` |
| Stream sessions (by user, client, broker) | 6 | `persister.loadUserSessionsStream()` with filters |
| Remove session(s) | 4 | `persister.removeUserSession()` |
| Offline session CRUD | 5 | Same as above with `offline=true` flag |
| Stats (active count, client stats) | 3 | `persister` count queries |
| Lifecycle (close, migrate) | 2 | No-op or delegate |

### Phase 5.3: RedisAuthenticationSessionProvider

**Goal**: Store authentication sessions (login flows in progress) in Redis.

**New files:**
- `model/redis/src/main/java/org/keycloak/models/sessions/redis/RedisAuthenticationSessionProviderFactory.java`
- `model/redis/src/main/java/org/keycloak/models/sessions/redis/RedisAuthenticationSessionProvider.java`

**Data model in Redis:**
```
Key:    authSession:{rootSessionId}
Type:   Hash
Fields: {
  "realmId": "...",
  "timestamp": "...",
  "tabId1": "{serialized AuthenticationSessionEntity}",
  "tabId2": "{serialized AuthenticationSessionEntity}",
  ...
}
TTL:    realm.getAccessCodeLifespan() (typically 300 seconds)
```

**Key methods:**

| Method | Redis Operation |
|--------|----------------|
| `createRootAuthenticationSession(realm)` | `HSET` + `EXPIRE` |
| `getRootAuthenticationSession(realm, id)` | `HGETALL` |
| `removeRootAuthenticationSession(realm, session)` | `DEL` |
| `removeAllExpired()` | Handled by Redis TTL (no-op) |
| `onRealmRemovedEvent(realm)` | `SCAN` + `DEL` matching realm keys |
| `updateNonlocalSessionAuthNotes(event)` | `HSET` to update specific tab |

**Cluster events**: Use `RedisClusterProvider` to broadcast auth note updates across nodes (same pattern as existing realm/user cache invalidation).

### Phase 5.4: RedisUserLoginFailureProvider

**Goal**: Store brute-force login failure counters in Redis.

**New files:**
- `model/redis/src/main/java/org/keycloak/models/sessions/redis/RedisUserLoginFailureProviderFactory.java`
- `model/redis/src/main/java/org/keycloak/models/sessions/redis/RedisUserLoginFailureProvider.java`

**Data model in Redis:**
```
Key:    loginFailure:{realmId}:{userId}
Type:   Hash
Fields: {
  "numFailures": "5",
  "lastFailure": "1709654321000",
  "lastIPFailure": "192.168.1.1",
  "numTemporaryLockouts": "1"
}
TTL:    realm.getMaxDeltaTimeSeconds() (brute force window)
```

**Key methods:**

| Method | Redis Operation |
|--------|----------------|
| `getUserLoginFailure(realm, userId)` | `HGETALL` |
| `addUserLoginFailure(realm, userId)` | `HSET` + `EXPIRE` |
| `removeUserLoginFailure(realm, userId)` | `DEL` |
| `removeAllUserLoginFailures(realm)` | `SCAN` + `DEL` matching `loginFailure:{realmId}:*` |

**Adapter**: `RedisUserLoginFailureModel` wraps the Redis hash and provides `incrementFailures()` via `HINCRBY`.

### Phase 5.5: RedisSingleUseObjectProvider

**Goal**: Store action tokens and single-use objects in Redis with automatic expiration.

**New files:**
- `model/redis/src/main/java/org/keycloak/models/sessions/redis/RedisSingleUseObjectProviderFactory.java`
- `model/redis/src/main/java/org/keycloak/models/sessions/redis/RedisSingleUseObjectProvider.java`

**Data model in Redis:**
```
Key:    singleUse:{tokenId}
Type:   Hash (stores the notes map)
TTL:    lifespanSeconds (from the token)
```

**Key methods:**

| Method | Redis Operation |
|--------|----------------|
| `put(key, lifespan, notes)` | `HSET` + `EXPIRE` |
| `get(key)` | `HGETALL` |
| `remove(key)` | Lua script: `HGETALL` + `DEL` atomically |
| `replace(key, notes)` | `DEL` + `HSET` + `EXPIRE` |
| `putIfAbsent(key, lifespan)` | `SET key 1 NX EX lifespan` (returns true if set) |

**Revoked tokens**: When `persistRevokedTokens` config is true, also call `SingleUseObjectPersisterProvider.put()` for revoked token persistence to DB (same as Infinispan impl).

### Phase 5.6: RedisStickySessionEncoderProvider

**Goal**: Provide session routing info for load balancers.

**New files:**
- `model/redis/src/main/java/org/keycloak/models/sessions/redis/RedisStickySessionEncoderProviderFactory.java`
- `model/redis/src/main/java/org/keycloak/models/sessions/redis/RedisStickySessionEncoderProvider.java`

**Implementation**: Since Redis is a shared external store (all nodes see the same data), there's no need for topology-aware routing. The encoder simply appends the current node's name to the session ID for basic load balancer affinity.

```java
public class RedisStickySessionEncoderProvider implements StickySessionEncoderProvider {
    private final String myNodeName;

    @Override
    public String encodeSessionId(String sessionId) {
        return sessionId + "." + myNodeName;
    }

    @Override
    public String decodeSessionId(String encodedSessionId) {
        int idx = encodedSessionId.indexOf('.');
        return idx == -1 ? encodedSessionId : encodedSessionId.substring(0, idx);
    }

    @Override
    public boolean shouldAttachRoute() {
        return true;
    }
}
```

---

## META-INF/services Registration

Add to `model/redis/src/main/resources/META-INF/services/`:

| Service File | Implementation Class |
|-------------|---------------------|
| `org.keycloak.models.UserSessionProviderFactory` | `org.keycloak.models.sessions.redis.RedisUserSessionProviderFactory` |
| `org.keycloak.sessions.AuthenticationSessionProviderFactory` | `org.keycloak.models.sessions.redis.RedisAuthenticationSessionProviderFactory` |
| `org.keycloak.models.UserLoginFailureProviderFactory` | `org.keycloak.models.sessions.redis.RedisUserLoginFailureProviderFactory` |
| `org.keycloak.models.SingleUseObjectProviderFactory` | `org.keycloak.models.sessions.redis.RedisSingleUseObjectProviderFactory` |
| `org.keycloak.sessions.StickySessionEncoderProviderFactory` | `org.keycloak.models.sessions.redis.RedisStickySessionEncoderProviderFactory` |

---

## Test Plan

### Unit Tests (model/redis)

| Test Class | What It Tests |
|-----------|---------------|
| `RedisAuthenticationSessionProviderTest` | Create, get, remove root auth sessions; TTL expiration; realm removal cleanup |
| `RedisUserLoginFailureProviderTest` | Add failure, increment, get, remove; realm-wide cleanup |
| `RedisSingleUseObjectProviderTest` | Put, get, remove; putIfAbsent atomicity; TTL expiration; revoked token persistence |
| `RedisStickySessionEncoderProviderTest` | Encode/decode round-trip; route extraction |

### Integration Tests (quarkus/tests/integration)

Add to `CacheRedisDistTest.java`:

| Test Method | What It Tests |
|------------|---------------|
| `testRedisSessionProvidersActivated` | With `--cache=redis`, verify Redis session providers are selected (log messages) |
| `testInfinispanSessionProvidersDisabled` | With `--cache=redis`, verify no Infinispan session provider errors |
| `testRedisStartDevFullStack` | `start-dev --cache=redis --cache-redis-url=...` boots without errors (build-level) |

### Functional Tests (requires running Redis)

| Test | What It Tests |
|------|---------------|
| Login flow | User can log in, session created in DB, auth session cleaned up |
| Brute force | Login failures tracked, lockout enforced, counters reset |
| Action tokens | Password reset, email verification tokens work once |
| Session listing | Admin console shows active sessions |
| Session logout | Logout removes session from DB |
| Node restart | Sessions survive Keycloak restart (DB-backed) |

---

## Implementation Order

1. **Phase 5.1** — Fix Infinispan `isSupported()` + `RedisClusterProviderFactory` (prerequisite, ~30 min)
2. **Phase 5.6** — `RedisStickySessionEncoderProvider` (simplest, ~30 min)
3. **Phase 5.4** — `RedisUserLoginFailureProvider` (simple, ~1-2 hours)
4. **Phase 5.5** — `RedisSingleUseObjectProvider` (simple, ~1-2 hours)
5. **Phase 5.3** — `RedisAuthenticationSessionProvider` (medium, ~2-3 hours)
6. **Phase 5.2** — `RedisUserSessionProvider` (complex, ~3-4 hours)
7. **Tests** — Unit + integration tests (~2-3 hours)
8. **Documentation** — Update architecture doc + status doc (~1 hour)

---

## Risk Assessment

| Risk | Impact | Mitigation |
|------|--------|------------|
| `PersistentUserSessionProvider` has hidden Infinispan dependencies | Blocks AD-1 | Option B (JPA-only provider) avoids this entirely |
| Auth session data loss on Redis restart | Users must re-login (same as Infinispan restart) | Document this; auth sessions are intentionally transient |
| Login failure counters lost on Redis restart | Brute force counters reset (same as Infinispan) | Acceptable; counters are ephemeral protection |
| Redis SCAN for realm removal is O(N) | Slow on large datasets | Use key prefixes for efficient scanning; consider Redis keyspace notifications |
| Serialization compatibility | Proto schemas may not cover session entities | Use JSON serialization for session data (simpler than Protobuf for transient data) |

---

## Files Summary

**New files to create (10):**
```
model/redis/src/main/java/org/keycloak/models/sessions/redis/
  RedisUserSessionProviderFactory.java
  RedisUserSessionProvider.java
  RedisAuthenticationSessionProviderFactory.java
  RedisAuthenticationSessionProvider.java
  RedisUserLoginFailureProviderFactory.java
  RedisUserLoginFailureProvider.java
  RedisSingleUseObjectProviderFactory.java
  RedisSingleUseObjectProvider.java
  RedisStickySessionEncoderProviderFactory.java
  RedisStickySessionEncoderProvider.java
```

**New META-INF/services files (5):**
```
model/redis/src/main/resources/META-INF/services/
  org.keycloak.models.UserSessionProviderFactory
  org.keycloak.sessions.AuthenticationSessionProviderFactory
  org.keycloak.models.UserLoginFailureProviderFactory
  org.keycloak.models.SingleUseObjectProviderFactory
  org.keycloak.sessions.StickySessionEncoderProviderFactory
```

**Existing files to modify (6-8):**
```
model/infinispan/.../InfinispanUserSessionProviderFactory.java
model/infinispan/.../InfinispanAuthenticationSessionProviderFactory.java
model/infinispan/.../InfinispanUserLoginFailureProviderFactory.java
model/infinispan/.../InfinispanSingleUseObjectProviderFactory.java
model/infinispan/.../InfinispanStickySessionEncoderProviderFactory.java
model/infinispan/.../ExpirationTaskFactory.java (if applicable)
model/redis/.../RedisClusterProviderFactory.java (if missing EnvironmentDependentProviderFactory)
quarkus/tests/integration/.../CacheRedisDistTest.java
```

**Documentation updates:**
```
docs/redis-cache-architecture.md (add session providers section)
REDIS_IMPLEMENTATION_COMPLETE_STATUS.md (update status)
```
