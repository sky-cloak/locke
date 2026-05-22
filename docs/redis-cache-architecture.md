# Redis Cache Backend for Keycloak — Architecture & Rebase Guide

**Branch**: `feature/redis`
**Last updated**: 2026-05-08 (after iteration 6)
**Maintainer**: Skycloak (skycloak.io)

---

## Table of contents

1. [What this is](#what-this-is)
2. [High-level architecture](#high-level-architecture)
3. [Module layout & dependency graph](#module-layout--dependency-graph)
4. [Provider activation — how `cache=redis` actually wires in](#provider-activation--how-cacheredis-actually-wires-in)
5. [Cache layers (L1/L2/L3) and the request hot path](#cache-layers-l1l2l3-and-the-request-hot-path)
6. [Marshalling / serialization](#marshalling--serialization)
7. [Configuration surface](#configuration-surface)
8. [Observability](#observability)
9. [What's iteration-driven design vs upstream contract](#whats-iteration-driven-design-vs-upstream-contract)
10. **[Rebase guide — file-by-file effort](#rebase-guide--file-by-file-effort)**
11. [Lessons learned (durable knowledge for future maintainers)](#lessons-learned-durable-knowledge-for-future-maintainers)
12. [References](#references)

---

## What this is

This branch replaces Keycloak's Infinispan distributed cache with **Redis (or any wire-compatible drop-in like Valkey/DragonflyDB)** as an alternative cache backend. When `--cache=redis` is set, all cache-related SPIs are served by Redis-backed providers; without that flag, Keycloak runs unchanged with its default embedded Infinispan.

**Goal**: managed-cloud operability. Infinispan in production Kubernetes has no managed-service offering and is "a full-scale engineering project" to run reliably ([Palark blog](https://blog.palark.com/ha-keycloak-infinispan-kubernetes/)). Redis is available as managed service on every major cloud (AWS ElastiCache/MemoryDB, Azure Cache for Redis, GCP Memorystore).

**Goal not**: replace Infinispan with anything that requires forking Keycloak. The current implementation is a fork because four upstream surfaces lock the cache mechanism (closed `Mechanism` enum, hardcoded `CACHE_REDIS_*` options, immutable `PropertyMappers.GROUPINGS`, build-step `indexRedisCache`). See [Implementation Approach](#whats-iteration-driven-design-vs-upstream-contract) for what would need upstream changes.

---

## High-level architecture

```
                    ┌──────────────────────────────────────────────────────────┐
                    │                  HTTP request                            │
                    │              (login / refresh / etc.)                    │
                    └────────────────────────┬─────────────────────────────────┘
                                             │
                                             ▼
                    ┌──────────────────────────────────────────────────────────┐
                    │           Keycloak SPI dispatch (per session)            │
                    │  RealmProvider, UserProvider, ClientProvider,            │
                    │  AuthenticationSessionProvider, SingleUseObjectProvider, │
                    │  UserLoginFailureProvider, UserSessionProvider,          │
                    │  CacheRealmProvider, UserCache, AuthCacheStoreFactory…   │
                    └────────────────────────┬─────────────────────────────────┘
                                             │
                                             ▼
                    ┌──────────────────────────────────────────────────────────┐
                    │  Cache adapter layer (model/redis)                       │
                    │  ┌────────────────────────────────────────────────────┐  │
                    │  │  L1: Caffeine (in-JVM, ~100 ns)                    │  │
                    │  │     • Realms / users / clients / keys / authz      │  │
                    │  │     • Single-flight via LoadingCache.get(k, ldr)   │  │
                    │  │     • Negative caching with sentinel               │  │
                    │  │     • W-TinyLFU eviction, 10 K entries / cache TTL │  │
                    │  └────────────────────────────────────────────────────┘  │
                    │     ↑ invalidation                                       │
                    │     │ Redis pub/sub channel `kc:l1:invalidate`           │
                    │     │ (L1InvalidationBus, fire-and-forget,               │
                    │     │  TTL-bounded staleness on missed message)          │
                    │     ↓                                                    │
                    │  ┌────────────────────────────────────────────────────┐  │
                    │  │  L2: Lettuce → Redis / Valkey (network, ~0.1-1 ms) │  │
                    │  │     • LettuceCacheAdapter (opaque-blob SET PX)     │  │
                    │  │     • HashCacheAdapter (HSET / HGETALL field-shape)│  │
                    │  │     • PipelinedRedisCache (1-RT batched writes)    │  │
                    │  │     • LuaScripts (server-side CAS, set-if-newer)   │  │
                    │  │     • Redisson for distributed locks + pub/sub     │  │
                    │  └────────────────────────────────────────────────────┘  │
                    └────────────────────────┬─────────────────────────────────┘
                                             │ source of truth
                                             ▼
                    ┌──────────────────────────────────────────────────────────┐
                    │  L3: PostgreSQL (KC26 persistent sessions default)       │
                    │     • UserSessionPersisterProvider (JPA-backed)          │
                    │     • Redis is purely a cache / coordination layer       │
                    └──────────────────────────────────────────────────────────┘
```

**Architectural distinctives** vs alternatives:
- **L1 in Caffeine, not Infinispan local cache.** PhaseTwo's competing extension keeps Infinispan's local cache as L1 to avoid a fork. We replace it entirely — operational simplicity is the whole point.
- **User sessions live in JPA (KC26 persistent sessions), not in Redis.** A Redis incident degrades performance, doesn't drop logins. PhaseTwo keeps sessions in Redis only — Redis-down = mass logout.
- **Selective L1.** Caches with unique-per-request keys (auth sessions, single-use tokens) bypass L1 entirely — every entry is read once, written once, so the publish-on-write cost would outweigh any hit benefit. Read-mostly caches (realms, users, clients) have L1 attached.

---

## Module layout & dependency graph

```
keycloak/                        (root multi-module Maven project, version 999.0.0-SNAPSHOT)
├── model/
│   ├── infinispan/              (upstream — DEFAULT cache backend; we add isSupported guards)
│   ├── jpa/                     (upstream — DB layer; we depend on it for L3)
│   └── redis/                   (NEW — entire module is ours; 166 files, ~21 KLOC)
│       └── src/main/java/org/keycloak/
│           ├── cache/redis/             (cache adapter layer: Lettuce, Hash, Pipeline, Lua, L1)
│           ├── cluster/redis/           (cluster coordination: pub/sub, distributed locks)
│           ├── connections/redis/       (Lettuce client management, pool, connection SPI)
│           ├── marshalling/redis/       (RedisModelSchema — Protostream registration)
│           ├── models/cache/redis/      (RealmCacheSession + adapters + 25+ events)
│           │   └── authorization/       (AuthZ store cache + events)
│           ├── models/sessions/redis/   (auth session, login failure, single-use, user session)
│           └── serialization/redis/     (ProtobufRedisSerializer)
├── quarkus/
│   ├── config-api/               (CachingOptions enum — TOUCHED, see rebase guide)
│   ├── deployment/               (KeycloakProcessor — TOUCHED, indexRedisCache build step)
│   └── runtime/                  (CachingPropertyMappers — TOUCHED, KC_CACHE → kc.cache mapping)
└── benchmark/                    (NEW — kcb harness, compose stacks, k6 script)
    ├── compose/                  (1-pod + 3-pod stacks for vanilla and Skycloak)
    ├── kcb/                      (clone of keycloak-benchmark, locally built)
    ├── docker/                   (Dockerfile to build images from dist tarball)
    └── k6/                       (legacy custom load script — superseded by kcb)
```

### Module boundaries

| Boundary | Direction | Notes |
|---|---|---|
| `model/redis` → `model/infinispan` | Provided | Compile-time only. Reuses `Marshalling` constants and Protostream annotations — does NOT use Infinispan's runtime classes. |
| `model/redis` → `model/jpa` | Compile | User session provider delegates to JPA persister. |
| `model/redis` → `server-spi-private` | Provided | All KC SPI interfaces. |
| `quarkus/deployment` → `model/redis` | Build-time | `indexRedisCache` step indexes the JAR into Quarkus when `cache=redis`. |
| `quarkus/runtime` → `model/redis` | Runtime | Property mappers + factory loading. |

The **Redis module is self-contained**. Removing the 13 upstream-touched files (they only add guards/options) would leave the Redis module functional but unreachable.

---

## Provider activation — how `cache=redis` actually wires in

**This is the path that broke silently for 6 months until iteration 6 caught it.** Read carefully.

### The chain

```
User sets KC_CACHE=redis
  ↓ (Quarkus property mapping)
kc.cache=redis is bound at build time
  ↓ (KeycloakProcessor.indexRedisCache, BUILD step)
Quarkus indexes keycloak-model-redis.jar — its classes become discoverable
  ↓ (KeycloakProcessor.loadFactories at end of BUILD)
For each (SPI, factoryId): if (factoryId already seen) → compareFactories,
                          else                          → add to map.
                          THEN  → if (factory implements EnvironmentDependentProviderFactory
                                       AND !isSupported(scope)) → drop
  ↓
Filtered factory list is baked into the deployment.
  ↓ (Runtime: ProviderManager hands out factories on session.getProvider)
Provider is created.
```

### The bug we hit

If two factories have the same `(SPI, factoryId)`, **`compareFactories` runs FIRST** and silently picks one based on `order()` and internal-flag. Only the SURVIVOR is then checked against `isSupported`. The loser is gone.

For three of our factories — `RedisCacheRealmProviderFactory`, `RedisUserCacheProviderFactory`, `RedisCacheStoreFactoryProviderFactory` — `getId()` was originally `"default"`, the same as their Infinispan counterparts. ProviderManager picked Infinispan; `RedisCacheRealmProviderFactory.isSupported` was never consulted. Realm/user/authz cache silently ran on Infinispan even with `KC_CACHE=redis`.

**Fix** (iteration 6): Redis cache factories return `getId() = "redis"`. Distinct ids let both factories survive dedup; `isSupported` then correctly disables the wrong one based on `cache=redis`.

**Regression test**: `RedisProviderFactoryIdsTest` asserts every Redis factory's `getId()` is `"redis"` (not `"default"`).

### Final SPI activation table

When `cache=redis`:

| SPI | Redis factory (active) | Infinispan factory (filtered out) |
|---|---|---|
| `connectionsRedis` (new) | `DefaultRedisConnectionProviderFactory` | n/a |
| `cluster` | `RedisClusterProviderFactory` | `InfinispanClusterProviderFactory` |
| `realmCache` | `RedisCacheRealmProviderFactory` (id=`redis`) | `InfinispanCacheRealmProviderFactory` (id=`default`) |
| `userCache` | `RedisUserCacheProviderFactory` (id=`redis`) | `InfinispanUserCacheProviderFactory` (id=`default`) |
| `cachedStore` (authZ) | `RedisCacheStoreFactoryProviderFactory` (id=`redis`) | `InfinispanCacheStoreFactoryProviderFactory` (id=`default`) |
| `authenticationSessions` | `RedisAuthenticationSessionProviderFactory` (id=`redis`) | `InfinispanAuthenticationSessionProviderFactory` (id=`infinispan`) |
| `loginFailures` | `RedisUserLoginFailureProviderFactory` (id=`redis`) | `InfinispanUserLoginFailureProviderFactory` |
| `singleUseObject` | `RedisSingleUseObjectProviderFactory` (id=`redis`) | `InfinispanSingleUseObjectProviderFactory` |
| `userSessions` | `RedisUserSessionProviderFactory` (id=`redis`, delegates to JPA) | `InfinispanUserSessionProviderFactory` |
| `stickySessionEncoder` | `RedisStickySessionEncoderProviderFactory` (id=`redis`) | `InfinispanStickySessionEncoderProviderFactory` |

When `cache=ispn` (default) or unset: all Redis factories' `isSupported()` returns false; default Infinispan path is preserved unchanged. **Verified iteration 6** — same `999.0.0-redis` image runs cleanly without `KC_CACHE` set.

---

## Cache layers (L1/L2/L3) and the request hot path

### L1 — Caffeine in-JVM cache (iteration 2)

- **`L1RedisCache<K, V>`** decorates any `RedisCache<K, V>` (the underlying L2 adapter).
- Caffeine cache keyed by `base64(L2 redis-key bytes)` — so the same string travels on the invalidation channel.
- **Single-flight** via `Cache.get(key, loader)`: concurrent misses for the same key collapse to one L2 lookup. Stampede-safe.
- **Negative caching** sentinel: when L2 returns null, L1 caches a marker so repeated misses don't hammer L2.
- Default config: 10 000 entries / cache, 60 s TTL. Tunable via `KC_SPI_CONNECTIONS_REDIS_DEFAULT_L1_MAX_ENTRIES` / `L1_TTL_SECONDS`.

### L1 invalidation bus (iteration 2)

- **`L1InvalidationBus`** opens two pub/sub Lettuce connections (sub + pub) at startup.
- Channel: `kc:l1:invalidate`. Message format: `<nodeId>|<cacheName>|<l1Key>` (or `<nodeId>|<cacheName>|*` for full flush).
- Self-message filtering by `nodeId` (random UUID per JVM).
- **Fire-and-forget**: if Redis disconnects mid-burst, peer L1s carry stale entries until 60 s TTL. Postgres SOT remains correct.
- `noOp()` factory available for single-node deployments and unit tests (no Redis pub/sub needed).

### Selective L1 (iteration 2)

`DefaultRedisConnectionProvider.shouldSkipL1(name)` bypasses the L1 wrapper for caches with unique-per-request keys:

```
sessions, clientSessions, offlineSessions, offlineClientSessions,
authenticationSessions, actionTokens, loginFailures, work
```

For these, `L1` only adds publish overhead with no hit benefit. Confirmed by iteration 2 bench: full-L1 was slower than no-L1 on these caches.

### L2 — Lettuce / Redis (iterations 1, 4, 5)

Two cache adapters:

| Adapter | Wire shape | When to use |
|---|---|---|
| `LettuceCacheAdapter` | One `SET key value` per entity (opaque blob) | Read-mostly entities (realms, users, clients) — fits with L1 in front |
| `HashCacheAdapter` | `HSET key field value` per entity field | Entities with field-level mutations (auth sessions, login failures) — partial updates avoid re-writing the whole tree |

Pool config (iteration 1):
- `min=16, max=64` — tuned for ~50 concurrent VUs at saturation
- `testOnBorrow=false, testOnReturn=false` — Lettuce auto-reconnects; PING-on-borrow added 2 RT/op for no real safety
- `testWhileIdle + 30 s eviction` — cheap defense against dead idle connections
- `maxWait=2 s` — fail-fast on saturation
- `prewarmPool` at startup — pays HELLO + CLIENT SETINFO upfront, not in user request paths

Atomic ops (iteration 5):
- `HSET + EXPIRE` and `HDEL + EXPIRE` are pipelined on async API → 1 RT each (was 2 RT each via sync calls — silent regression caught in iteration 5).
- `cmd.set(key, value, SetArgs.Builder.px(ttl))` — single SET PX command, not GETSET + PEXPIRE.

### L3 — PostgreSQL (KC26 default)

- User sessions persist via JPA's `UserSessionPersisterProvider`. Redis is a *cache* over them, not their store.
- `AutoPersistingClientSessionAdapter` (`model/redis/.../sessions/redis/`) wraps the JPA client-session adapter to flush note mutations through `persister.createClientSession()` — fixes the issuer-NPE bug in lightweight access tokens (`security-admin-console` uses them).

---

## Marshalling / serialization

Two serialization paths in use:

### 1. Protostream (iteration 6 fix)

- **`RedisModelSchema`** is a `@ProtoSchema` interface listing every redis-package class with `@ProtoTypeId`.
- The annotation processor generates `RedisModelSchemaImpl` at compile time.
- `ProtobufRedisSerializer` registers `RedisModelSchema.INSTANCE` directly, bypassing the global `ServiceFinder` lookup.
- **Why bypass?** The Infinispan and Redis schemas use the same `@ProtoTypeId` numbers (parallel implementations of the same events). They MUST never coexist in one `SerializationContext`.

### 2. Java native serialization

- Used by `LettuceCacheAdapter` (opaque-blob SET) and inside `HashCacheAdapter` for individual hash field bytes.
- `NonExistentItem` and other Revisioned entities implement `Serializable`. **Iteration 6 caught a missing `implements Serializable` on `NonExistentItem`** that crashed startup once the realm cache was activated.
- A future iteration could replace native serialization with Protostream throughout for speed and security.

---

## Configuration surface

### CLI / env (set at build time)

```
KC_CACHE=redis                 # required — activates the whole Redis path
KC_CACHE_REDIS_URL=redis://host:6379
                                # required — Redis connection
                                #   redis://       (standalone)
                                #   redis-sentinel://h1:p1,h2:p2?sentinelMasterId=master
                                #   redis-cluster://h1:p1,h2:p2,h3:p3
```

### Per-SPI runtime tunables (`KC_SPI_<spi>_<factory>_<key>`)

```
KC_SPI_CONNECTIONS_REDIS_DEFAULT_L1_ENABLED=true       # default true; set false for legacy single-node
KC_SPI_CONNECTIONS_REDIS_DEFAULT_L1_MAX_ENTRIES=10000  # per-cache size
KC_SPI_CONNECTIONS_REDIS_DEFAULT_L1_TTL_SECONDS=60     # safety net for missed pub/sub
```

Pool sizes are currently compile-time defaults (`16` / `64`). Add tunables here if you need site-specific tuning.

### Standard Keycloak config

Anything NOT cache-specific is unchanged from upstream Keycloak. `KC_DB_*`, `KC_HOSTNAME`, `KC_HEALTH_ENABLED=true`, `KC_METRICS_ENABLED=true` work normally.

---

## Observability

(iteration 6) `RedisMetrics` publishes to Micrometer's `Metrics.globalRegistry` (the same registry KC's existing `keycloak_user_events_*` metrics use). Surfaces at the `/metrics` management endpoint when `KC_METRICS_ENABLED=true`.

| Meter | Tags | What it measures |
|---|---|---|
| `keycloak_redis_l2_ops_total` | `cache, op` | Redis op count per cache + op type (get / set_px / hset / hgetall / getdel / hset_multi) |
| `keycloak_redis_l2_duration_seconds` | `cache, op` | Per-op latency (50/95/99 percentiles) |
| `keycloak_redis_lua_invocations_total` | `script` | Lua script call count |
| `keycloak_redis_lua_duration_seconds` | `script` | Lua script execution time |
| `keycloak_redis_pipeline_batches_total` | — | Number of pipeline batches executed |
| `keycloak_redis_pipeline_batch_size` | — | Distribution of ops per batch |
| `keycloak_redis_l1_invalidations_published_total` | — | Pub/sub invalidations sent |
| `keycloak_redis_l1_invalidations_received_total` | — | Pub/sub invalidations received from peers |
| `cache_*` (Caffeine) | `cache` (= `keycloak_redis_l1.<name>`) | L1 hit / miss / size / eviction rates |

Useful queries documented in [iteration-6 doc](redis-iterations/iteration-6-prometheus-metrics.md).

---

## What's iteration-driven design vs upstream contract

The distinction matters for rebase: upstream may change either layer, but the impact is different.

### Upstream contract — assumptions we depend on

These are inherited from Keycloak. If upstream changes them, we MUST adapt:

- **`Provider` SPI lifecycle** — `init(Config.Scope)`, `create(KeycloakSession)`, `close()`.
- **`EnvironmentDependentProviderFactory.isSupported(Config.Scope)`** — the build-time activation check.
- **`Config.Scope.root().get("cache")`** — how we read the `cache` setting at build time. (Does NOT use `Config.getProvider("cache")` — that path returns null.)
- **`KeycloakSession.getProvider(Class<T>)`** — how providers are looked up at runtime.
- **The `Marshalling` class** in `model/infinispan` — provides `@ProtoTypeId` constants we share with Infinispan's schema. If KC removes/renames these, our `@ProtoTypeId` annotations need updating.
- **`KeycloakSession.getTransactionManager()`** — used by some providers; we don't enlist a transaction wrapper yet (iteration 7 candidate).
- **JPA `UserSessionPersisterProvider`** — our user-session provider delegates here.

### Iteration-driven design — our own choices

These can change without affecting rebase risk:

- L1 in Caffeine vs Infinispan local cache (iteration 2)
- Selective L1 skip-list (iteration 2)
- Hash storage shape for auth sessions (iteration 5)
- Pool tuning numbers (iteration 1)
- Pipelining strategy (iteration 5)
- Metric names (iteration 6)
- Schema split (Infinispan vs Redis schema files in same module — iteration 6)

---

## Rebase guide — file-by-file effort

The branch has **~13 upstream files modified + ~166 new files in `model/redis/`**. New files never conflict; modifications carry varying risk.

### Risk categories

- **🟢 LOW** — small, mechanical change, rarely touched upstream
- **🟡 MEDIUM** — file changes occasionally; conflict resolvable in <30 min
- **🔴 HIGH** — file changes frequently or major refactors expected; budget 1-4 hours
- **🟣 INTRINSIC** — change shape can vary; needs domain understanding

### Upstream files modified (the rebase-sensitive set)

| File | Risk | What we add | Typical conflict pattern | Time budget per rebase |
|---|---|---|---|---|
| `model/infinispan/.../InfinispanCacheRealmProviderFactory.java` | 🟢 | `implements EnvironmentDependentProviderFactory` + `isSupported()` returning `!"redis".equals(cache)` | Method position drift if class is reordered | 5 min |
| `model/infinispan/.../InfinispanUserCacheProviderFactory.java` | 🟢 | Same pattern as above | Same | 5 min |
| `model/infinispan/.../InfinispanCacheStoreFactoryProviderFactory.java` (authZ) | 🟢 | Same pattern | Same | 5 min |
| `model/infinispan/.../InfinispanAuthenticationSessionProviderFactory.java` | 🟡 | isSupported() check ALSO requires `InfinispanUtils.isEmbeddedInfinispan()`. Don't drop that. | KC26 added the embedded check; future KC may add more | 10 min |
| `model/infinispan/.../InfinispanSingleUseObjectProviderFactory.java` | 🟢 | isSupported guard | rare | 5 min |
| `model/infinispan/.../InfinispanStickySessionEncoderProviderFactory.java` | 🟢 | isSupported guard | rare | 5 min |
| `model/infinispan/.../InfinispanUserLoginFailureProviderFactory.java` | 🟢 | isSupported guard | rare | 5 min |
| `model/infinispan/.../InfinispanUserSessionProviderFactory.java` | 🟡 | isSupported guard. KC's session-cache logic changes meaningfully across versions. | Method body around guard may move | 15 min |
| `model/infinispan/.../InfinispanClusterProviderFactory.java` | 🟢 | isSupported guard | rare | 5 min |
| `model/infinispan/.../DefaultInfinispanConnectionProviderFactory.java` | 🟡 | isSupported guard. Connection setup paths change per KC release. | Method body re-arrangements | 10 min |
| `model/infinispan/.../crl/InfinispanCacheCrlProviderFactory.java`, `InfinispanCrlStorageProviderFactory.java` | 🟢 | isSupported guards (CRL caching) | added in KC26.x | 5 min each |
| `model/infinispan/.../keys/InfinispanCachePublicKeyProviderFactory.java`, `InfinispanPublicKeyStorageProviderFactory.java` | 🟢 | isSupported guards (key caching) | added in KC26.x | 5 min each |
| `model/infinispan/.../models/cache/infinispan/idp/InfinispanIdentityProviderStorageProviderFactory.java` | 🟢 | isSupported guard | KC26.x | 5 min |
| `model/infinispan/.../models/cache/infinispan/organization/InfinispanOrganizationProviderFactory.java` | 🟢 | isSupported guard | KC26.x | 5 min |
| `model/infinispan/.../models/cache/infinispan/CacheManager.java` | 🟡 | A small change here (we removed something or added a hook) | Cache invalidation logic changes between KC versions | 15 min |
| `model/infinispan/.../spi/infinispan/impl/embedded/DefaultCacheEmbeddedConfigProviderFactory.java` | 🟢 | isSupported guard | rare | 5 min |
| **`quarkus/config-api/.../CachingOptions.java`** | 🟡 | Adds `redis` value to `Mechanism` enum + `CACHE_REDIS_URL` option family + sets `CACHE.buildTime(true)` | KC's caching options change every minor (new Infinispan features) | 20 min |
| **`quarkus/runtime/.../CachingPropertyMappers.java`** | 🟡 | Maps `KC_CACHE=redis` and `KC_CACHE_REDIS_URL` to SPI properties; adds `redis` enabled-when conditions | Refactored in KC26; may move again | 30 min |
| **`quarkus/deployment/.../KeycloakProcessor.java`** | 🔴 | `indexRedisCache` build step + `disableClusterHealthCheck` gate for Redis | Quarkus build steps change every Quarkus bump (~6mo cadence) | 1 hour |
| `quarkus/runtime/.../DatabaseCompatibilityMetadataProvider.java` | 🟡 | Small additions for Redis cache compatibility metadata | Unclear; minor file | 15 min |
| `quarkus/runtime/.../KeycloakClusterReadyHealthCheckProducer.java` | 🟢 | One-line guard so the cluster health check is skipped for Redis | rare | 5 min |
| `quarkus/runtime/pom.xml` | 🟢 | Adds `keycloak-model-redis` runtime dep | rare | 5 min |
| `model/pom.xml` | 🟢 | Adds `<module>redis</module>` | rare | 2 min |
| Root `pom.xml` | 🟢 | Adds `keycloak-model-redis` to `<dependencyManagement>` | rare | 2 min |
| `quarkus/dist/.../tests/CacheRedisDistTest.java` | 🟡 | New test in upstream test suite for Redis cache | rare; we may need to update if the test framework changes | 15 min |

### Net-new files (no rebase risk)

| Path | Files | LOC | Notes |
|---|---|---|---|
| `model/redis/src/main/java/` | 116 | ~16 K | Entire Redis module |
| `model/redis/src/test/java/` | 50 | ~3.5 K | 51 unit tests including iter-6 regression suite |
| `model/redis/src/main/resources/META-INF/services/` | 11 | ~50 | SPI registrations |
| `benchmark/` | n/a | ~3 K | kcb harness, compose, k6 |
| `docs/redis-cache-architecture.md` (this doc) | 1 | ~600 | |
| `docs/redis-iterations/*.md` | 7 | ~3 K | Iteration history |

**These never conflict.** They live in their own module / directories.

### Total rebase time estimate per upstream release

| Upstream release type | Realistic time budget | Why |
|---|---|---|
| **Patch release** (26.x.y → 26.x.y+1) | **15-30 min** | Usually only Infinispan factories touched, mechanical re-apply. |
| **Minor release** (26.x → 26.x+1) | **2-4 hours** | + CachingOptions / CachingPropertyMappers churn, possibly KeycloakProcessor changes, possibly new abstract methods on `UserModel` / `GroupModel` / `PolicyStore` / SPI interfaces (we hit 3 of these in the last rebase). |
| **Major release** (26 → 27) | **1-3 days** | + likely Quarkus version bump in KeycloakProcessor, possibly cache layer refactor, possibly Marshalling.java refactor (our `@ProtoTypeId` constants depend on it), possibly persistence-layer changes. |

### What the iteration work added vs original branch

The iterations 1-6 (May 2026) added ~3000 LOC of cache adapter improvements + ~3500 LOC of tests + 7 docs. **None of it touches upstream files.** All iteration work is rebase-neutral.

The original branch (March 2026) modified 13 upstream files. That's still the rebase boundary.

### Step-by-step rebase procedure

```bash
# 1. Squash iteration commits to keep history clean
git rebase -i HEAD~30   # squash to 4-6 logical commits

# 2. Pin to a Keycloak release tag (not HEAD of main)
git fetch origin --tags
git rebase 26.4.0       # or whatever target tag

# 3. Resolve conflicts file by file using risk table above.
#    Most are 5-min isSupported guards. Watch for:
#    - CachingOptions.java if Mechanism enum was reordered
#    - KeycloakProcessor.java if Quarkus build steps changed
#    - New abstract methods on model interfaces (compile errors will surface)

# 4. Build + test
./mvnw -pl model/redis -am -DskipTests compile
./mvnw -pl model/redis test                    # 51 tests must pass
./mvnw -pl quarkus/dist -am -DskipTests install

# 5. Build the docker image and smoke-test
cp quarkus/dist/target/keycloak-999.0.0-SNAPSHOT.tar.gz benchmark/docker/
cd benchmark/docker && docker build -t localhost:5011/keycloak:999.0.0-redis-postrebase .
cd .. && ./run-scenario.sh A 18080 5 30s   # vanilla baseline
        ./run-scenario.sh B 18081 5 30s   # Skycloak

# 6. Run kcb against 1-pod and 3-pod stacks (existing compose files).
#    Numbers should be in the same regime as iteration 6 (97% throughput parity at 3-pod).

# 7. If new abstract methods appeared on SPI interfaces, port them.
#    The Infinispan adapters in model/infinispan/ are the reference implementations
#    to mirror.
```

### Files most likely to break per upstream release

Cite this list during rebase as a "where to look first":

1. `quarkus/deployment/.../KeycloakProcessor.java` — Quarkus build step churn
2. `quarkus/config-api/.../CachingOptions.java` — new Infinispan/Redis options
3. `quarkus/runtime/.../CachingPropertyMappers.java` — property mapper restructuring
4. `model/infinispan/.../models/cache/infinispan/CacheManager.java` — invalidation logic evolves
5. New abstract methods on `RealmModel` / `UserModel` / `ClientModel` / `GroupModel` / `PolicyStore` — surface as compile errors in our adapters in `model/redis/.../models/cache/redis/`. Reference implementation: the Infinispan adapter for the same model.

---

## Lessons learned (durable knowledge for future maintainers)

These bit us. Don't make a future rebaser learn them again.

1. **`getId()` MUST differ between Redis and Infinispan factories.** ProviderManager dedups by `(SPI, factoryId)` BEFORE `isSupported()` runs. Same id = silent override. *(Iteration 6.)*
2. **`Config.getProvider("cache")` always returns null.** Use `Config.Scope.root().get("cache")` to read `kc.cache`. *(Original branch.)*
3. **`testOnBorrow=true` on a Lettuce pool adds 1 round-trip per cache op.** Don't enable it. Use `testWhileIdle` for cheap defense. *(Iteration 1.)*
4. **Lettuce `.sync()` does NOT pipeline.** Two sequential sync calls = two round-trips. To get 1 RT for paired ops (HSET + EXPIRE), use `.async()` + `LettuceFutures.awaitAll`. *(Iteration 5.)*
5. **A single shared Lettuce connection causes head-of-line blocking under concurrent sync load.** Keep the pool. *(Iteration 1.)*
6. **`@ProtoTypeId` annotations need a `@ProtoSchema` interface to register them.** Without it, classes are unmarshallable at runtime. *(Iteration 6.)*
7. **Infinispan and Redis Protostream schemas use the same `@ProtoTypeId` numbers** (parallel implementations). Never load both into the same `SerializationContext`. Each backend's serializer registers only its own. *(Iteration 6.)*
8. **`KC_CACHE` is `buildTime(true)`** — readable via `config.root().get("cache")` at build time, but does NOT show up in `kc.sh show-config` runtime output. Don't be confused by absence in show-config. *(Iteration 6.)*
9. **`PersistentAuthenticatedClientSessionAdapter.setNote()` doesn't flush to JPA.** Use `AutoPersistingClientSessionAdapter` to wrap, otherwise the issuer claim is lost on lightweight access tokens (security-admin-console breaks). *(Original branch.)*
10. **`nginx ip_hash` from a single-IP test client gives you a 1-pod test through nginx.** Use `least_conn` for benchmarking; `ip_hash` for production with diverse client IPs. *(Iteration 4.)*
11. **`KC_CACHE_STACK` works only via env var, not `--cache-stack` flag** in start-dev (the flag is gated by `--cache=ispn`). *(Iteration 4.)*
12. **nginx `Host: $host` strips port.** KC then renders form action URLs without port → POST goes to port 80 → `ECONNREFUSED`. Use `Host: $http_host`. *(Iteration 4.)*

---

## References

### Internal docs

- [Iteration history](redis-iterations/README.md)
- [Iter 1: pool tuning](redis-iterations/iteration-1-pool-and-set-px.md)
- [Iter 2: Caffeine L1](redis-iterations/iteration-2-l1-cache.md)
- [Iter 3: Tier 2 infra (HSET, Pipeline, Lua)](redis-iterations/iteration-3-tier-2-design.md)
- [Iter 4: provider migration](redis-iterations/iteration-4-provider-migration.md)
- [Iter 5: auth-session HSET](redis-iterations/iteration-5-auth-session-hset.md)
- [Iter 6: Prometheus + provider activation fix](redis-iterations/iteration-6-prometheus-metrics.md)
- [Benchmark RESULTS](../benchmark/RESULTS.md)

### Upstream Keycloak

- [CONTRIBUTING](https://github.com/keycloak/keycloak/blob/main/CONTRIBUTING.md)
- [Distributed caches](https://www.keycloak.org/server/caching)
- [KC26 persistent sessions](https://www.keycloak.org/2024/12/storing-sessions-in-kc26)

### Discussions / context

- [Discussion #37137 — Redis Cache Support](https://github.com/keycloak/keycloak/discussions/37137)
- [Issue #24849 — Redis cache support (34+ upvotes)](https://github.com/keycloak/keycloak/issues/24849)
- [PhaseTwo extension repo](https://github.com/p2-inc/keycloak-redis-cache)
