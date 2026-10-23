# Iteration 6 — Prometheus metrics for the Redis cache path

**Date**: 2026-05-08
**Status**: shipped (framework + L2 ops); partial verification (realm-cache L1 path not exercised in start-dev, see open question)

## Goal

Make the Redis cache backend observable via the standard Keycloak `/metrics` Prometheus endpoint. Without this, every previous "perf change" required a custom test harness; iteration 5's pipelining bug (silent 2× round-trips) wouldn't have shipped if we'd seen the round-trip count in production.

## Problem signal

End of iteration 5, three open observability gaps:

1. **L1 cache hit rate** — we believed it was high (sequential single-user logins went 0.55 s → 0.15 s) but had no continuous measurement.
2. **HSET vs SET ratio** — we believed iter-5's HSET migration shifted writes away from full-tree blobs; no metric to confirm.
3. **Pipeline efficacy** — we had a 2× regression because two `.sync()` calls don't pipeline; we caught it with `redis-cli MONITOR` but a Prometheus counter would have surfaced it sooner.

## Design

### One holder, one global registry

`RedisMetrics` (`model/redis/.../cache/redis/RedisMetrics.java`) is the single source of truth for the Redis cache path's Micrometer meters. It binds to `Metrics.globalRegistry` — the same registry Keycloak's existing `keycloak_user_events_*` and Caffeine `cache_*` meters use.

Lazy registration via `ConcurrentHashMap.computeIfAbsent` so meters are built on first use, not at provider startup. Counters/timers are tagged by cache name and op type so a single Prometheus query can decompose the call mix.

### Wired into four cache-path components

| Component | What it records |
|---|---|
| `LettuceCacheAdapter` | `keycloak_redis_l2_ops_total{cache, op="get|set_px|getdel|..."}` |
| `HashCacheAdapter` | `keycloak_redis_l2_ops_total{cache, op="hgetall|hset|hset_multi|hget"}` + `keycloak_redis_l2_duration_seconds{cache, op="hgetall"}` |
| `LuaScripts` | `keycloak_redis_lua_invocations_total{script}` + `keycloak_redis_lua_duration_seconds{script}` |
| `PipelinedRedisCache.Batch` | `keycloak_redis_pipeline_batches_total` + `keycloak_redis_pipeline_batch_size{operations}` (DistributionSummary) |
| `L1RedisCache` | Caffeine's stock `cache_*{cache="keycloak_redis_l1.<name>"}` family (gets / puts / hits / misses / evictions / size) via `CaffeineStatsCounter` |
| `L1InvalidationBus` | `keycloak_redis_l1_invalidations_published_total` + `keycloak_redis_l1_invalidations_received_total` |

### Verification — what's confirmed working

After 20 password-grant logins through the iter-6 image (`localhost:5011/keycloak:999.0.0-redis-iter6`), the management endpoint at `:9000/metrics` returns:

```
# TYPE keycloak_redis_l2_ops counter
keycloak_redis_l2_ops_total{cache="authenticationSessions",op="hset_multi"} 40.0
keycloak_redis_l2_ops_total{cache="authenticationSessions",op="hset"}       280.0
# TYPE keycloak_redis_pipeline_batches counter
keycloak_redis_pipeline_batches_total 0.0
# TYPE keycloak_redis_l1_invalidations_published counter
keycloak_redis_l1_invalidations_published_total 0.0
```

Math check: 280 single-field HSETs / 20 logins = **14 HSETs per login on the hot path**, with 2 multi-field HSETs (createRoot + 1 other cold path). This **directly validates iteration 5's claim** that auth-session writes are now field-level.

### Useful Prometheus queries

```promql
# Hot-path HSET rate (iter-5 win)
sum(rate(keycloak_redis_l2_ops_total{op="hset"}[5m])) by (cache)

# vs whole-entity HSET rate (cold paths only)
sum(rate(keycloak_redis_l2_ops_total{op="hset_multi"}[5m])) by (cache)

# L1 hit rate (once realm/user cache is exercised)
sum(rate(cache_gets_total{cache=~"keycloak_redis_l1.*",result="hit"}[5m]))
  / sum(rate(cache_gets_total{cache=~"keycloak_redis_l1.*"}[5m]))

# Lua script p99 latency
histogram_quantile(0.99,
  sum by (le, script) (rate(keycloak_redis_lua_duration_seconds_bucket[5m])))

# Pipeline efficacy — bigger batches = better
histogram_quantile(0.5,
  sum by (le) (rate(keycloak_redis_pipeline_batch_size_bucket[5m])))
```

### Configuration

Metrics activate when KC's standard `KC_METRICS_ENABLED=true` is set. The Redis layer adds zero new config flags — the meters are always bound to `Metrics.globalRegistry` and surface only when the management endpoint is enabled.

## Risks / open questions

| Risk | Mitigation / note |
|---|---|
| `RedisMetrics` registers even when no metrics endpoint is configured | Counters are cheap (~100 ns increment); if `Metrics.globalRegistry` has no reporters bound, the data is collected and discarded. No measurable cost. |
| Metric cardinality grows with cache count | We have 11 cache names, ~8 op types each — bounded. No risk of high-cardinality labels. |
| **Open question**: L1 cache stats don't appear for `realms`/`users`/`authorization` in start-dev | The `RedisCacheRealmProviderFactory.lazyInit` calls `getCache("realms")` once on first request, which should create the L1RedisCache and register Caffeine stats. In testing with `start-dev` and password grants, no `realms` cache labels surfaced. Either (a) the realm cache lookup short-circuits before reaching `RealmCacheSession` in start-dev, (b) the `lazyInit` doesn't fire on first password-grant, or (c) there's a thread/race in the registration. Needs deeper trace; not blocking the framework. Production use with `start --optimized` may behave differently. |

## Follow-up fix (same iteration): Protostream marshaller flood

After the iter-6 `getId` fix activated the realm-cache code path, KC's logs flooded with errors from `RedisPubSubEventManager`:

```
ERROR Failed to publish events to channel keycloak:events:REALM_INVALIDATION_EVENTS:
java.lang.IllegalArgumentException: No marshaller registered for object of Java type
org.keycloak.cluster.redis.WrapperClusterEvent
```

Root cause: `ProtobufRedisSerializer` was iterating `Marshalling.getSchemas()` — the global `ServiceFinder` over `SerializationContextInitializer` — but the only such initializer in the classpath was the Infinispan-package one (`KeycloakModelSchema`). The 44 redis-package classes carrying `@ProtoTypeId` had no schema registering them, so `SerializationContext.canMarshall(WrapperClusterEvent)` was always false. Every cluster-event publish threw at runtime.

Fix:

1. New file `model/redis/.../marshalling/redis/RedisModelSchema.java` — a `@ProtoSchema` interface listing every redis-package Protostream class (parallel to Infinispan's `KeycloakModelSchema`). The annotation processor generates `RedisModelSchemaImpl` at compile time.
2. `ProtobufRedisSerializer` now registers `RedisModelSchema.INSTANCE` directly instead of going through `Marshalling.getSchemas()`. This avoids loading the Infinispan schema (which has the same `@ProtoTypeId` numbers — they would collide if both were in one context). Each backend's serializer uses only its own schema.

Verified post-fix:
- `docker logs ... | grep "No marshaller registered" | wc -l` → **0**
- `docker logs ... | grep "ERROR.*RedisPubSub" | wc -l` → **0**

New regression tests in `ProtobufSerializationTest.java`:
- `wrapperClusterEvent_isMarshallable`
- `clearCacheEvent_isMarshallable`
- `testSerializeAndDeserialize_LockEntry` updated to use redis-package `LockEntry` (the Infinispan one is no longer in the Redis serializer's context — that's the correct behavior).

## What didn't ship

- **Per-Redis-server metrics** (Lettuce client pool stats — active/idle/wait/created counts). PhaseTwo ships these as `vendor_jedis_*`. Future iteration; not on the critical perf path.
- **Histogram buckets tuned for sub-millisecond latencies.** The default Micrometer buckets work but resolution at <1 ms is poor. Would need explicit `serviceLevelObjectives(0.0001, 0.0005, 0.001, ...)`. Future tuning iteration.

## What's next (iter 7+)

The perf curve is now mostly architectural. Three credible directions:

1. **Multi-region invalidation via NATS JetStream** — durable subscribers, replay-on-reconnect; fixes the fire-and-forget pub/sub weakness flagged in iteration 2. Required for cross-region 5-nines.
2. **Bloom-filter revocation list + JWT-first paths** — biggest architectural win still on the table. Reduces the 14 HSETs/login (now visible in metrics) by routing most token validations through a local bloom filter that skips the cache entirely.
3. **Multi-region design doc** — design before code. AWS MemoryDB vs Valkey-cluster vs DragonflyDB for cross-region L2; SNS+SQS vs NATS for invalidation; cockroach-style global txn for SOT.
