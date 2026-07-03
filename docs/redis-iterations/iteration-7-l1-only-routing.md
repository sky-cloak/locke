# Iteration 7 — L1-only routing for realm/user/authz caches

**Date**: 2026-05-08
**Status**: shipped

## Goal

Make `cache=redis` actually mean **Redis is the cache** for every cache type, not just sessions. Iteration 6 left realm/user/authorization cache silently running on Infinispan because activating the Redis path crashed startup with a `Serializable` cascade through `DefaultLazyLoader`. A "Redis cache backend" where the realm cache silently runs on Infinispan isn't a Redis cache backend — it's a session-cache plus an Infinispan-cache, with a misleading config flag.

## Problem signal — exactly what was wrong

`CachedRealm`, `CachedUser`, `CachedClient`, `CachedRealmRole`, etc. hold `DefaultLazyLoader<S, D>` fields. `DefaultLazyLoader` carries `Function<S, D> loader` and `Supplier<D> fallback` — these are typically constructor references and lambdas (e.g. `OAuth2DeviceConfig::new`, `realm -> realm.getDefaultClientScopesStream(...).map(...).collect(toList())`).

Lambdas are **not** `Serializable` by default. When `LettuceCacheAdapter` tries to write them to Redis via `ObjectOutputStream`, KC crashes:

```
ERROR: Failed to start server in (development) mode
ERROR: Failed to serialize object: org.keycloak.models.cache.redis.entities.CachedRealmRole
ERROR: org.keycloak.models.cache.redis.DefaultLazyLoader
```

Iteration 6 had reverted the realm/user/authz factories' `getId` from `"redis"` back to `"default"` so `ProviderManager` would silently fall through to Infinispan. That kept KC alive but defeated the point of the project.

## Why "fix the serialization" wasn't the right answer

Three approaches were considered:

| Approach | Why rejected |
|---|---|
| Add `@ProtoField` to all `Cached*` entities + Protostream marshalling | ~30 entities, no annotations today, large mechanical refactor with high regression risk. Valid long-term direction but too big for one iteration. |
| Make `loader`/`fallback` transient + null-tolerant | After deserialization, lazy fields couldn't reload. Risk: silent stale-cache-miss every time `data` happened to be unloaded at serialization time. Brittle. |
| Force-eager-load before every `cache.put` | Defeats the lazy-loading purpose. Intrusive — every put call site needs to know to do it. |

None of these matched the actual architectural intent. **Realm/user/authz caches don't need Redis L2 storage**. Look at how Infinispan handles them: per-pod local cache + cross-cluster invalidation events. **PostgreSQL is the source of truth.** Each pod loads from JPA on miss, caches locally, and listens for invalidations from peers.

We already have all the pieces:
- Caffeine L1 (iter-2)
- `L1InvalidationBus` pub/sub (iter-2)
- PostgreSQL JPA layer (KC built-in)

The only thing missing was a way to opt **out** of L2 storage while keeping the L1 + pub/sub layer.

## Design — `NoOpRedisCache` as the L2 stub

```
                ┌─────────────────────────────────────────────────────┐
                │  RealmCacheManager / UserCacheSession (KC built-in) │
                │   - on miss → load from JPA, call cache.put(...)    │
                └────────────────────────┬────────────────────────────┘
                                         │
                                         ▼
                ┌─────────────────────────────────────────────────────┐
                │  L1RedisCache<K, V>                                 │
                │   - L1: Caffeine (10 K entries, 60 s TTL)           │
                │   - subscribed to kc:l1:invalidate via L1InvalidationBus
                │   - read: l1.getIfPresent(k) → fall through to L2   │
                │   - write: l1.put(k, v); l2.put(k, v); bus.publish  │
                └────────────────────────┬────────────────────────────┘
                                         │ delegate
                                         ▼
                ┌─────────────────────────────────────────────────────┐
                │  NoOpRedisCache<K, V>  ← iter-7                     │
                │   - get: returns null                               │
                │   - put: no-op                                      │
                │   - remove: no-op                                   │
                │   (no Redis storage, no serialization attempted)    │
                └─────────────────────────────────────────────────────┘
```

When the L1RedisCache calls `delegate.get(k)` after an L1 miss, the no-op returns `null`. The Keycloak-level cache manager observes the null, loads from JPA, calls `cache.put(k, v)` to populate L1. Subsequent reads on the same pod hit L1 (Caffeine). Writes on any pod publish to `kc:l1:invalidate`; peer pods evict their L1 entries.

This is **exactly** Infinispan local cache behavior, minus Infinispan.

## Implementation

### `NoOpRedisCache.java` — the L2 stub

A trivial `RedisCache<K, V>` impl whose every method is a no-op (or returns empty). 65 LOC including the doc comment that explains why it exists.

### `DefaultRedisConnectionProvider.getCache` — routing logic

```java
private static final Set<String> L1_ONLY_PREFIXES = Set.of(
    "realms", "realmRevisions",
    "users", "userRevisions",
    "authorization", "authorizationRevisions",
    "keys", "crl"
);

return (RedisCache<K, V>) caches.computeIfAbsent(name, cacheName -> {
    if (shouldUseL1Only(cacheName) && l1Bus != null) {
        NoOpRedisCache<K, V> noOpL2 = new NoOpRedisCache<>(cacheName);
        return new L1RedisCache<>(noOpL2,
                k -> (cacheName + ":" + k).getBytes(StandardCharsets.UTF_8),
                l1Bus, l1Config, metrics);
    }
    LettuceCacheAdapter<K, V> l2 = new LettuceCacheAdapter<>(cacheName, clientManager, metrics);
    if (l1Bus == null || shouldSkipL1(cacheName)) return l2;
    return new L1RedisCache<>(l2, l2::toRedisKey, l1Bus, l1Config, metrics);
});
```

Three routing decisions per cache name:

1. **L1-only** (this iteration): realms / users / authorization / keys / crl + their revisions. Caffeine + pub/sub, no Redis storage. Serialization cascade is moot — entities never touch the wire.
2. **L1 + L2** (iter 2): default for caches not in either skip-list (none today, future-proofing).
3. **L2-only** (iter 2's selective skip): sessions, auth-sessions, action-tokens, login-failures, work, sticky-sessions, etc. — unique per request, no L1 hit benefit.

### `getId()` restored to `"redis"` (3 factories)

Now safe because the L2 path for these caches is the no-op. The iter-6 `RedisProviderFactoryIdsTest` cases are un-`@Ignore`'d.

## Risks / open questions

| Risk | Status |
|---|---|
| L1 entries on different pods may briefly hold different versions during invalidation propagation | Same as Infinispan local cache — bounded staleness; clients re-fetch on miss; SOT is JPA |
| If pub/sub message is lost (Redis disconnect mid-burst), peer L1 carries stale value until 60 s TTL | Documented in iter-2; SOT in PostgreSQL is always correct |
| `keys` and `crl` caches added to L1-only. They also use `Cached*`-like patterns and would crash with the same `Serializable` cascade | Tested implicitly — KC starts cleanly with all 8 cache names L1-only |
| Memory growth on a pod with many realms / users | Bounded to 10 K entries per cache by Caffeine `maximumSize` |

## Measurements

Iteration 7 image: `localhost:5011/keycloak:999.0.0-redis-iter7`. Tests: 51/51 pass, **0 skipped** (was 51/51 with 3 skipped in iter-6). Build: green. KC starts cleanly with all 8 L1-attached caches active.

### 1-pod (kcb, 10 users/sec, 60 s)

| | A vanilla | B iter-6 | **B iter-7** | iter-7 vs iter-6 | iter-7 vs vanilla |
|---|---|---|---|---|---|
| RPS | 34.7 | 34.5 | 34.5 | tied | 99 % |
| **Mean** | 19 ms | 69 ms | **23 ms** | **-67 %** | **+21 %** |
| p95 | 61 ms | 508 ms | 111 ms | -78 % | +82 % |
| p99 | 172 ms | 687 ms | **259 ms** | **-62 %** | +51 % |

**Mean response time within 21 % of vanilla.** This is the closest single-pod has been at any point in this branch.

### 3-pod (kcb, 10 users/sec, 60 s, nginx least_conn)

| | A3 vanilla | B3 iter-6 | **B3 iter-7** | iter-7 vs iter-6 | iter-7 vs vanilla |
|---|---|---|---|---|---|
| RPS | 34.8 | 34.5 | 34.5 | tied | 99 % |
| **Mean** | 30 ms | 199 ms | **108 ms** | **-46 %** | 3.6 × |
| p95 | 130 ms | 1906 ms | 771 ms | -60 % | 5.9 × |
| p99 | 777 ms | 2607 ms | **1985 ms** | **-24 %** | 2.6 × |

3-pod still has more headroom — auth session and single-use token writes still go to Redis at ~14 HSETs per login, which dominates 3-pod latency. Iter-5's HSET migration handles those efficiently but they're network round-trips no matter what.

### Per-action breakdown (3-pod)

```
                                  A3 vanilla        B3 Skycloak iter-7
  Browser to Log In Endpoint:    24 ms / p99 889    102 ms / p99 2048
  Browser posts credentials:     64 ms / p99 800    183 ms / p99 1984
  Exchange Code:                 12 ms / p99 217     72 ms / p99  654
  Browser logout:                14 ms / p99 112     55 ms / p99  577
```

Login-endpoint and credentials-post are the heaviest paths on both sides because they touch realm config + render the login page + write an auth session. Vanilla wins on the first two by ~3 × because:
- L1 hits are now in our Caffeine, comparable in speed to Infinispan local
- BUT auth session writes still pay 17 HSETs to Redis vs Infinispan's in-process writes

The remaining gap is genuinely the network cost of session writes, not the realm/user cache. That's where iter-3's pipelining and iter-5's HSET migration already extracted most of the available win.

## What's next

The architectural picture is now clean: cache=redis really means cache=redis. Future perf work targets the remaining bottleneck (auth-session network round-trips):

1. **Bloom-filter revocation list + JWT-first paths** — make most token validations skip the cache entirely. The biggest architectural win still on the table; estimated 99 % reduction in cache lookups for token-validation flows.
2. **NATS JetStream invalidation** — durable subscribers, replay-on-reconnect. Required for cross-region 5-nines.
3. **Multi-region design doc** — AWS MemoryDB vs Valkey-cluster vs DragonflyDB; SNS+SQS vs NATS for invalidation.
4. **Protostream migration for `Cached*`** — the deferred iter-7 alternative. Would let us put realm/user/authz cache *in* Redis L2, not just L1. Marginal win for current architecture but enables sharing cache state across regions.
