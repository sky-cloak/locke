# Iteration 1 — Lettuce pool tuning + atomic `SET … PX`

**Date**: 2026-05-08
**Status**: shipped
**Files changed**:
- `model/redis/src/main/java/org/keycloak/connections/redis/RedisClientManager.java`
- `model/redis/src/main/java/org/keycloak/connections/redis/RedisConnectionConfig.java`
- `model/redis/src/main/java/org/keycloak/cache/redis/LettuceCacheAdapter.java`
- `model/redis/src/test/.../RedisConnectionConfigTest.java` (defaults)
- `model/redis/pom.xml` (surefire excludes for env-only tests)

## Goal

Make per-cache-op Redis traffic cheap and stop the load-induced thrashing pattern observed in the original branch.

## Problem signal

`redis-cli MONITOR` on one login showed:

| Source of traffic | Count per login | % of total |
|---|---|---|
| HELLO + 2× CLIENT SETINFO (handshake on each new TCP conn) | 48 | **55 %** |
| Real cache work (GETSET + PEXPIRE) | 34 | 39 % |
| Misc (Redisson lock, ping) | 5 | 6 % |
| **Total** | **87** | |

18 unique TCP source ports per login = 18 fresh Lettuce connections opened. The pool was sized `min=5/max=20`, with `testOnBorrow=true` AND `testOnReturn=true` — every cache op did `PING → real op → PING` (3 round-trips of work for 1 round-trip of value).

The 50-VU benchmark **regressed** from 8.4 → 5.9 iter/s as load climbed (10 → 50 VUs). Classic resource thrashing — pool starvation + per-borrow PING magnifying contention.

## Design

### A. Connection pool

`buildPoolConfig()` now produces:

```java
poolConfig.setMaxTotal(64);                                   // was 20
poolConfig.setMinIdle(16);                                    // was 5
poolConfig.setMaxIdle(64);                                    // was unset
poolConfig.setTestOnBorrow(false);                            // was true (PING)
poolConfig.setTestOnReturn(false);                            // was true (PING)
poolConfig.setTestWhileIdle(true);                            // cheap defense
poolConfig.setMinEvictableIdleDuration(Duration.ofSeconds(30));
poolConfig.setTimeBetweenEvictionRuns(Duration.ofSeconds(15));
poolConfig.setBlockWhenExhausted(true);
poolConfig.setMaxWait(Duration.ofMillis(2000));               // fail-fast
poolConfig.setJmxEnabled(false);
```

### B. Pool pre-warming

```java
private void prewarmPool() {
    for (int i = 0; i < config.getPoolMinSize(); i++) {
        borrowed[i] = connectionPool.borrowObject();   // forces creation
    }
    /* return all */
}
```

Pays HELLO + SETINFO upfront at server startup, instead of paying it for the first 16 cache ops in production traffic.

### C. Atomic `SET … PX`

`LettuceCacheAdapter.put(k, v, ttl)` was:

```java
byte[] old = cmd.getset(redisKey, serialize(value));   // 1 RT
cmd.pexpire(redisKey, unit.toMillis(ttl));             // 1 RT
return deserialize(old);
```

Now:

```java
cmd.set(toRedisKey(key), serialize(value), SetArgs.Builder.px(unit.toMillis(ttl)));  // 1 RT
return null;   // callers in this codebase don't use the old value
```

Audit confirmed all 6 call sites of `cache.put(k, v, ttl)` discard the return value. Half the round-trips for free.

### D. Considered but rejected: shared connection

A single shared Lettuce connection sounds appealing because Lettuce's `StatefulRedisConnection` is thread-safe. But the **`.sync()` API serializes commands per connection** — under 50 VUs of concurrent load this becomes a head-of-line bottleneck. Verified empirically: shared-connection iter/s = 5.96, pool iter/s = 8.1 at the same load. Reverted.

The pool of N connections is what gives N-way parallelism for `.sync()` calls. To eliminate the pool, you'd need to switch every call site to `.async()` (different signature, large refactor). Not a Tier-1 change.

## Risks / open questions

- **`testWhileIdle` runs PING on idle eviction** — small steady-state cost. Worth it for graceful handling of long-idle connections vs Redis-side disconnects.
- **`maxWait=2 s`** could surface as visible failures under DoS. Tunable per environment.
- **`prewarmPool` adds startup latency** (~200ms locally). Acceptable; happens once.

## Measurements

Single login, MONITOR after iteration:

| Source | Count |
|---|---|
| Real work (SET) | 17 |
| Misc | 2 |
| **Total** | **19** (was 87) |

87 → 19 = **4.5 × less Redis traffic per login.**

Throughput at 50 VUs / 3 min: **5.9 → 8.1 iter/s (+37 %)**. Login p99: **11.4 s → 7.7 s (-32 %)**.

The **monotonic scaling pattern** is restored: at iteration 1, throughput rises from 10 → 50 VUs (8.4 → 8.1, near-flat) instead of regressing.

## What's next

Per-request work is now down to ~17 SET ops + a handful of GET/DEL. To go further we need either:

1. Fewer ops per request (HSET partial updates → 1 op per entity instead of N).
2. Bundled ops per request (MULTI/EXEC at transaction commit).
3. A faster path for hot-key reads (Caffeine L1 — Iteration 2).

Iteration 2 picks (3); iteration 3 picks (1) and (2).
