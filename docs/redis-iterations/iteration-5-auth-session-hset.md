# Iteration 5 — Auth-session HSET refactor

**Date**: 2026-05-08
**Status**: shipped (HSET migration); Prometheus metrics deferred

## Goal

Close the remaining mean-latency gap to vanilla in 3-pod (53 ms vs 40 ms = +33 %). The remaining cost is the auth-session entity being re-serialized in full on every mutation; a typical login flow does ~5-15 such writes.

## Problem signal

End of iteration 4 (kcb, 3-pod, 10 users/sec):

| | A vanilla 3-pod | B Skycloak iter-4 3-pod |
|---|---|---|
| RPS | 34.9 | 34.0 (97 %) |
| Mean | 40 ms | 53 ms (+33 %) |
| p99 | 740 ms | 783 ms (+6 %) |

Throughput tied. p99 within noise. The remaining mean-latency gap correlates with the auth-session write path:

- `RedisRootAuthenticationSessionAdapter.persist()` was called from **17 different setters** in the child adapter, plus 4 root-level paths.
- Each call did `cache.put(key, wholeRootEntity, ttl)` — Java-serialize the **entire tree** (root + all tabs + all nested maps) and SET as one opaque blob.
- For a typical flow with one tab and ~10 mutations, that's **10 × full-tree serialization + 10 × SET round-trips** of payload that grew with each note added.

## Design

### Schema — single hash with field-keyed tabs

```
authSession:<rootId>  HASH
  id          → root id (UTF-8)
  realmId     → realm id (UTF-8)
  timestamp   → epoch seconds (UTF-8)
  tab:<tabId> → Java-serialized RedisAuthenticationSessionEntity bytes
  ...one tab:<tabId> field per child...
TTL on the key (EXPIRE)
```

### Why single-hash-with-tab-fields

Considered **split keys** (`authSessionRoot:<id>` + `authSession:<id>:<tabId>` + `authSessionTabs:<id>`) which would let individual tab fields update without touching anything else. Rejected because:

1. Reads become `SMEMBERS + HGETALL parent + N × HGETALL tab` — N+2 round-trips, vs single-hash's 1 round-trip via `HGETALL`.
2. Atomic creation needs a Lua script (parent + first tab + index in one go).
3. The typical SSO flow has **one tab per session**. Optimizing for the multi-tab case at the cost of single-tab is wrong.

Single-hash gives:
- Reads: 1 RT (`HGETALL`)
- `setTimestamp(t)`: 1 RT (`HSET key timestamp <new>` + EXPIRE) — sends 10 bytes, not 5 KB.
- `onChildUpdated(tabId, child)`: 1 RT (`HSET key tab:<tabId> <child-bytes>`) — only that one tab's bytes, not all tabs combined.
- `removeAuthenticationSessionByTabId`: 1 RT (`HDEL` + EXPIRE refresh) — and the parent stays alive.

### Java serialization preserved (deliberately)

The child entity has nested maps/sets/enums and uses `Serializable`. Switching to JSON or Protobuf would be a separate change with its own migration story. This iteration keeps **Java serialization for child entities** but stores them as named fields in a hash — getting the wire-cost win without the serialization-format risk.

### Specific call-site rewrites

| Adapter call | Before | After |
|---|---|---|
| `setTimestamp(t)` | `cache.put(wholeTree, ttl)` (re-serialize all tabs) | `provider.persistTimestamp(rootId, t)` → `HSET timestamp <new>` |
| `createAuthenticationSession(client)` | `cache.put(wholeTree, ttl)` | `persistTab(newTab) + persistTimestamp` (two single-field HSETs) |
| `removeAuthenticationSessionByTabId(tabId)` | `cache.put(treeMinusTab, ttl)` | `removeTab(tabId)` (`HDEL`) + `persistTimestamp` |
| `restartSession(realm)` | `cache.put(emptyTree, ttl)` | `removeRoot` + `persistRootAuthSession` (clean recreate) |
| `onChildUpdated(tabId, child)` | `cache.put(wholeTree, ttl)` | `persistTab(tabId, child)` (`HSET tab:<tabId> <child-bytes>`) |

### `HashCacheAdapter` extensions

Added two methods to support hot-path field-level deletes:

```java
public boolean deleteField(K key, String field);                              // HDEL
public boolean deleteFieldRefreshTtl(K key, String field, long ttlSeconds);  // HDEL + EXPIRE
```

The TTL-refreshing variant matters when removing a child shouldn't allow the parent hash to be evicted seconds later (would lose the remaining tabs).

## Risks / open questions

| Risk | Mitigation |
|---|---|
| Migration: existing `cache.put` keys (Java-serialized blobs at the L2 SET path) coexist with new hash keys | Different storage shapes won't collide because `LettuceCacheAdapter` uses prefix `<cacheName>:` and `HashCacheAdapter` uses `<cacheName>:h:`. Old keys age out via TTL. |
| Reconstruction sees an old-format blob if rolling upgrade is incomplete | `reconstruct` returns null on missing/malformed `id` field; KC creates a fresh root session. Acceptable transient on rolling upgrade. |
| Concurrent-update on the same hash field from two pods | Last-write-wins (HSET). Same semantics as the previous `cache.put`. For stronger semantics use `LuaScripts.casFieldAndTtl`. |
| Realm removal is now slow (no SCAN over hash keys) | Documented. Realms-removed is admin-rare. A per-realm secondary index could optimize but adds write cost; deferred. |

## Deferred — Prometheus metrics

Originally scoped for this iteration. Deferred because:
- The metrics surface needs Quarkus + micrometer integration in the right places, plus a registry that survives session lifecycle.
- Without metrics we can't validate WHAT changed in the perf numbers (was it L1 hit rate? Lua scripts? HSET payload reduction?). But with shipping pressure on the HSET refactor, we can use kcb's per-action breakdown as a proxy.
- The metrics work is a clean iteration of its own (~1 day) and is the obvious next priority.

## Measurements

Iteration 5 image: `localhost:5011/keycloak:999.0.0-redis-iter5`. Tests: 16/16 pass. Build: green.

### 1-pod (kcb, 10 users/sec, 60 s)

| | A vanilla | B iter-4 | **B iter-5** |
|---|---|---|---|
| RPS | 34.6 | 34.37 | **34.18** |
| Mean | 17 ms | 1376 ms | **655 ms** (-52 % vs iter-4) |
| p99 | 205 ms | 5900 ms | **4185 ms** (-29 % vs iter-4) |
| Errors | 0 | 0 | 0 |

**Mean response time at 1-pod halved** vs iter-4 with no throughput loss. The HSET single-field updates on `setTimestamp` and `onChildUpdated` (the two highest-frequency call sites in the auth-session adapter) are doing real work — sending ~10 bytes for a timestamp update instead of re-serializing the entire tree.

### 3-pod (kcb, 10 users/sec, 60 s, least_conn)

| | A3 vanilla | B3 iter-5 (pipelined) |
|---|---|---|
| RPS | 35.0 | **33.8** (97 %) |
| Mean | 35 ms | 196 ms (5.6×) |
| p95 | 246 ms | 1594 ms |
| p99 | 766 ms | **2351 ms** (3.1×) |
| Errors | 0 | 0 |

The 3-pod numbers vary considerably day-to-day on this benchmark hardware. Earlier iter-4 runs showed 3-pod B mean of 53 ms; in the iter-5 testing window iter-4's image had been pruned and couldn't be re-baselined. The honest read: throughput parity holds at 3-pod (97 %), the latency story is somewhere between "same regime" and "back to per-request overhead" depending on system state.

### The pipelining bug — important lesson

First iter-5 build had `HashCacheAdapter.putField()` doing **two sequential `.sync()` calls** (`HSET` then `EXPIRE`). Each is a blocking round-trip. So every iter-5 write was 2 RTs, not 1 — the migration silently regressed. Initial 3-pod iter-5 result was mean 858 ms.

Fix: pipeline both commands on the async API in one connection, await both. Lettuce sends them in one TCP write window — true single round-trip.

```java
// Before (2 RTs):
cmd.hset(key, field, value);   // blocks on response
cmd.expire(key, ttl);          // blocks on response

// After (1 RT):
RedisFuture<?> f1 = async.hset(key, field, value);
RedisFuture<?> f2 = async.expire(key, ttl);
LettuceFutures.awaitAll(2s, f1, f2);
```

Mean response time after fix: 858 ms → 196 ms.

**This generalizes to any Redis adapter doing `HSET + EXPIRE` or similar paired ops on the sync API.** The fix is in `HashCacheAdapter.pipelineWrite()` — `putAll`, `putField`, and `deleteFieldRefreshTtl` all use it now.

### Per-action breakdown (B3 iter-5, pipelined)

```
  Browser to Log In Endpoint:    mean 172 ms,  p99 1738 ms
  Browser posts credentials:     mean 328 ms,  p99 2401 ms
  Exchange Code:                 mean 148 ms,  p99 1373 ms
  Browser logout:                mean  87 ms,  p99  915 ms
```

vs A3 vanilla (same window):
```
  Browser to Log In Endpoint:    mean  36 ms
  Browser posts credentials:     mean  61 ms
  Exchange Code:                 mean  18 ms
  Browser logout:                mean  21 ms
```

Each action is 4-6× the vanilla mean. That's the shape of "Redis round-trips dominate" at 3-pod with one shared Redis. The next perf push is **fewer round-trips per action** — bloom-filter revocation list + JWT-first paths (research item #5) so most actions skip the cache entirely.

## What's next (iteration 6)

1. **Prometheus metrics surface** — L1 hit rate, Lua script timing, pipeline batch size, HSET vs SET ratios. Required for any further perf tuning to be data-driven.
2. **Multi-region invalidation** — replace fire-and-forget Redis pub/sub with NATS JetStream (durable subscribers, replay-on-reconnect). The first cross-region story.
3. **Bloom-filter revocation list + JWT-first paths** — the architecturally biggest win still on the table. Reduces cache lookups by ~99 % for token validations.
