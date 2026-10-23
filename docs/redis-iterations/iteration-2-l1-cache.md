# Iteration 2 — Caffeine L1 + pub/sub invalidation

**Date**: 2026-05-08
**Status**: shipped
**Files added**:
- `model/redis/src/main/java/org/keycloak/cache/redis/L1RedisCache.java`
- `model/redis/src/main/java/org/keycloak/cache/redis/L1InvalidationBus.java`

**Files changed**:
- `model/redis/pom.xml` (+`com.github.ben-manes.caffeine:caffeine:3.1.8`)
- `model/redis/src/main/java/org/keycloak/connections/redis/DefaultRedisConnectionProvider.java`
- `model/redis/src/main/java/org/keycloak/connections/redis/DefaultRedisConnectionProviderFactory.java`
- `model/redis/src/main/java/org/keycloak/connections/redis/RedisClientManager.java` (`getStandaloneClient()`)
- `model/redis/src/main/java/org/keycloak/cache/redis/LettuceCacheAdapter.java` (`toRedisKey` made public)

## Goal

Eliminate the network round-trip for the hottest cache reads (realm, client, user). Vanilla Keycloak's embedded Infinispan does these as in-JVM Java method calls (zero network); we should approach the same for the read-mostly path.

## Problem signal

After iteration 1, B was at ~17 % of vanilla throughput at 50 VUs. The remaining gap was per-request work that couldn't shrink further without restructuring the data model — but **the same realm/client/user are looked up over and over**, suggesting an L1 cache would absorb most reads.

PhaseTwo's design kept Infinispan's local cache as their L1. We can't (we're trying to eliminate Infinispan entirely), so we built our own L1 with Caffeine — the in-JVM cache library with W-TinyLFU eviction and best-in-class hit rates.

## Design

### Two-tier topology

```
┌──────────────────────────────────────────────────────────────┐
│  L1: Caffeine (in-JVM, ~100 ns reads, 10 K entries / cache)  │
│   ↑ invalidation: Redis pub/sub on `kc:l1:invalidate`        │
├──────────────────────────────────────────────────────────────┤
│  L2: Lettuce → Redis (network, ~200 µs reads, source of      │
│       truth for distributed state)                           │
├──────────────────────────────────────────────────────────────┤
│  L3: PostgreSQL (KC26 persistent sessions, durable SOT)      │
└──────────────────────────────────────────────────────────────┘
```

### `L1RedisCache<K, V>` — decorator over any `RedisCache<K, V>`

- `Caffeine<String, Object>` keyed by base64(L2 redis key bytes). Using the L2 key bytes guarantees no collisions across caches sharing one bus.
- `get(k)` calls `Cache.get(l1Key, loader)` which is **single-flight**: concurrent misses for the same key collapse to one L2 lookup. Stampede-safe.
- `put(k, v[, ttl])`: write through to L2 first (SOT), then update L1 locally, then publish invalidation to peers.
- `remove(k)`: delete from L2, evict from L1, publish.
- `clear()`: clear both, publish wildcard.
- **Negative caching**: when L2 returns null we store a `NEGATIVE` sentinel so subsequent misses don't hammer L2.
- 60 s default TTL — short enough that a missed pub/sub message heals quickly, long enough for real hit benefit.

### `L1InvalidationBus` — Redis pub/sub for cross-node L1 evictions

```
   Node A                                         Node B
   ──────                                         ──────
   put(k, v)                                      get(k)         [L1 hit, stale]
       │                                              ↑
       │ write L2  ────────────────►  Redis  ────────┤
       │ update L1                                    │
       │ publish "<nodeA>|<cache>|<l1Key>" ──────►  Pub/sub  ──► Node B subscribed
       ▼                                                              │
   reply OK                                                            ▼
                                                                evict L1 entry
```

- Two dedicated Lettuce pub/sub connections (one for sub, one for pub) per JVM. Pub/sub connections can't run normal commands, hence the separation.
- Self-message filtering by `nodeId` (random UUID per JVM) so a writer doesn't evict its own freshly-written value.
- Wildcard `FLUSH_KEY = "*"` flushes the whole peer L1 — used by `clear()`.
- **Fire-and-forget**: a node disconnected during a publish will miss invalidations. The 60 s L1 TTL is the safety net. For 5-nines, NATS JetStream with durable consumers is the future upgrade.

### Selective L1 — skip ephemeral caches

Caches with **unique-per-request keys** (auth sessions, single-use tokens, login failures) get **zero L1 hit benefit** — every entry is read once and written once. They DO pay the publish overhead per write. So they bypass the L1 wrapper entirely:

```java
// DefaultRedisConnectionProvider
private static final Set<String> L1_SKIP_PREFIXES = Set.of(
    "sessions", "clientSessions", "offlineSessions", "offlineClientSessions",
    "authenticationSessions", "actionTokens", "loginFailures", "work"
);
```

Read-mostly caches (realms, users, clients, keys, authorization) keep the L1 wrapper.

This is a key correction: full L1 (everywhere) was actually slightly slower than no L1 because the publishes outweighed the hits on ephemeral caches. Selective L1 was the win.

## Risks / open questions

- **Pub/sub message loss** — if Redis disconnects mid-burst, peer L1s carry stale entries until 60 s TTL. SOT in Postgres remains correct; cache staleness is bounded.
- **Cache key cardinality** — bounded to 10 K entries per cache by `maximumSize`. Hot working set fits comfortably; cold tails get evicted.
- **Cross-region** — pub/sub doesn't cross AWS regions. For multi-region we need a different invalidation channel (SNS+SQS, NATS, or Kafka).
- **Tail latency on misses** — single-flight means concurrent miss-on-same-key blocks all but one thread until L2 returns. Caffeine 3.x mitigates segment-locking but doesn't eliminate it.

## Measurements

Sequential single-user logins (where L1 hit rate is ~100% after warmup):

| Login # | Time |
|---|---|
| 1 (cold) | 0.55 s |
| 2 | 0.19 s |
| 3 | 0.15 s |
| 4 | 0.14 s |
| 5 | 0.16 s |

**Cold → warm = 3.6 × speedup** on the same flow. Caffeine is doing exactly what it should.

Concurrent benchmarks (5 VUs / 90 s — clean signal):

| | iter/s | login p99 | refresh p99 | logout p99 |
|---|---|---|---|---|
| Vanilla A | 11.7 | 535 ms | 96 ms | 30 ms |
| B iter-1 (pool only) | 8.5 | 388 ms | 165 ms | 70 ms |
| **B iter-2 (selective L1)** | **9.6** | **254 ms** | 116 ms | **44 ms** |
| **% of vanilla** | **82 %** | 2.1 × | 1.2 × | 1.5 × |

At 5 VUs we're now at **82 % of vanilla's throughput** and within 2 × on login p99. That's the realistic single-node ceiling without restructuring storage.

## What's next

The remaining latency gap to vanilla is dominated by **per-request unique-key work** (auth sessions, single-use tokens) where L1 can't help. The path forward:

1. **HSET partial updates** — store entities as Redis hashes, mutate fields atomically with one round-trip. Cuts per-session work from ~17 SETs to ~3 HSETs.
2. **MULTI/EXEC at transaction commit** — collapse remaining writes into one network round-trip per request.
3. **Lua CAS** — atomic compare-and-set on hash fields, replacing optimistic-lock dances over the wire.

Together these form Iteration 3.
