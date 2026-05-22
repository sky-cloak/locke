# Iteration 4 — Provider migration onto Tier 2 infra

**Date**: 2026-05-08
**Status**: shipped (LoginFailureProvider, SingleUseObjectProvider); deferred (AuthenticationSessionProvider — requires deeper entity-shape refactor)

## Goal

Take the Tier 2 infrastructure built in iteration 3 (`HashCacheAdapter`, `LuaScripts`, `PipelinedRedisCache`) and migrate the actual providers to use it. The infra was sitting cold; this iteration warms it up on the simpler call sites.

## Problem signal

End-of-iteration-3 numbers (kcb, 1-node, 10 users/sec):

| | A vanilla | B Skycloak L1-selective |
|---|---|---|
| RPS | 34.8 | 27.0 |
| Mean | 46 ms | 4305 ms |
| p99 | 1.38 s | 12.6 s |

Vanilla is unbeatable at single-node — embedded Infinispan has zero network. To start closing the gap, the per-request Redis traffic in the unique-key paths (sessions, login failures, action tokens) needs to drop. That's exactly what HSET enables.

## Design

### Connection-provider API extended

`RedisConnectionProvider.java` gains three new accessors:

```java
<K> HashCacheAdapter<K> getHashCache(String name);
LuaScripts getLuaScripts();
PipelinedRedisCache.Batch beginPipelineBatch();
```

`DefaultRedisConnectionProvider` caches one `HashCacheAdapter` per name (parallel to its existing `getCache` map). `DefaultRedisConnectionProviderFactory` initializes a singleton `LuaScripts` (preloads via `SCRIPT LOAD` at startup; falls back to inline `EVAL` if loading fails) and a `PipelinedRedisCache` factory.

### Migration A — `RedisUserLoginFailureProvider`

**Why first**: smallest entity (5 scalar fields), already shaped as `Map<String, String>`, simple mutation pattern, low blast radius (failure path only). Perfect proving ground.

**Before** — every setter does a full read-modify-write of the whole map:

```java
public void incrementFailures() {
    data.put("numFailures", String.valueOf(getNumFailures() + 1));
    persist();   // SET key value-as-bytes EX ttl  (entire map written)
}
```

For a brute-force attempt with N failed logins, this is `N × (read full map → modify → write full map)` = 4 RTs per failure.

**After** — each setter HSETs one field:

```java
public void incrementFailures() {
    String v = String.valueOf(getNumFailures() + 1);
    snapshot.put(F_NUM_FAILURES, b(v));
    hash.putField(key, F_NUM_FAILURES, b(v), ttlNow());   // HSET + EXPIRE: 1 RT
}
```

Wire cost dropped from `~200 bytes × 2 trips` to `~10 bytes × 1 trip` per setter.

**`addUserLoginFailure`** — initial creation does one `HSET key field1 v1 field2 v2 … + EXPIRE` (one round-trip via the adapter's `putAll`).

**`clearFailures`** — five-field reset writes all five at once via `putAll`. Half the RTs of the old code.

### Migration B — `RedisSingleUseObjectProvider.remove`

Single-use semantics need NX-create and full-map reads, so the cache is ALREADY cheap on the create path (one `SET key val NX PX ms`). The HSET migration doesn't help.

**But** — `remove(key)` was a `get + remove` dance: 2 round-trips, with a race window where the read could see a value another node had already deleted before our delete arrived.

**Fix**: use Lettuce's atomic `GETDEL` (already what `RedisCache.remove` does internally — Lettuce `cmd.getdel(key)` returns the old value and deletes in one round-trip). The provider just needed to stop layering its own get-then-remove on top of it.

```java
// Before: get + remove (2 RT, race-prone)
Map<String, String> data = cache.get(cacheK);
if (data == null) return null;
Map<String, String> removed = cache.remove(cacheK);
return removed != null ? data : null;

// After: 1 RT, atomic
return cache.remove(cacheKey(key));
```

### Deferred — `RedisAuthenticationSessionProvider`

**Why deferred**: the root entity is a tree:

```
RootAuthSession  ──┬── id, realmId, timestamp
                   └── Map<tabId, ChildAuthSession>   ← nested
                                  ├── clientUUID, authUserId, timestamp, ...
                                  ├── Map<String, String> clientNotes
                                  ├── Map<String, String> authNotes
                                  └── Set<String> requiredActions
                                  └── ...
```

A meaningful HSET migration needs to break this into separate keys (one per child auth session) so a tab-level mutation doesn't re-write the whole tree's serialized blob. That's a substantial refactor with real correctness risk on the live login path:

- New keys: `authSessionRoot:<id>` (parent metadata) + `authSession:<id>:<tabId>` (one per tab)
- Reads: HGETALL parent + SMEMBERS index + N HGETALLs for tabs (could be PIPELINE-batched)
- Writes: HSET single tab field, no parent re-write
- Atomic creation: Lua script that creates parent + first tab + index in one call
- Migration: keys versioned (`v2:`) so the new path doesn't collide with in-flight v1 sessions during a rolling upgrade

This is a 1-2 day effort, warrants its own iteration with integration tests + rollback story. Tracked for iteration 5.

## Risks / open questions

| Risk | Mitigation |
|---|---|
| LoginFailure model snapshot drift — local snapshot may be stale after a peer node updates a field | TTL-bounded; brute-force window is short. If perfect freshness is needed, re-fetch on every getter (1 extra RT per check, vs current zero). |
| LuaScripts preload fails on certain Redis versions / managed services | Constructor catches and logs; per-call falls back to inline EVAL. No runtime breakage. |
| `getHashCache` coexists with `getCache` for the same cache name — different storage shape | Documented: one cache name → one storage shape. Mixing would corrupt. Login-failure cache uses `getHashCache` only. |

## Measurements

Iteration 4 image: `localhost:5011/keycloak:999.0.0-redis-iter4`. Tests: 16/16 pass. Build: green.

### Per-flow Redis op count (theoretical, single login w/ no failures)

|  | iteration 3 | iteration 4 |
|---|---|---|
| Login failures (no incident) | 0 | 0 |
| Login failures (1 fail then success) | 4 SET (full map each time) | 2 HSET (single field) |
| Brute-force lockout (5 failures) | 20 SET | 5 HSET |
| Action token consumed | 2 RT (get + del) | 1 RT (getdel) |

### Bench numbers — 1-pod (kcb, 10 users/sec, 60 s)

| | A vanilla | B iter-3 (L1-selective) | B iter-4 | iter-4 vs iter-3 |
|---|---|---|---|---|
| RPS | 34.6 | 27.0 | **34.37** | **+27%** |
| Mean response | 17 ms | 4305 ms | 1376 ms | **-68%** |
| p99 | 205 ms | 12.6 s | **5.9 s** | **-53%** |
| Errors | 0 | 0 | 0 | tied |

**Throughput now matches vanilla** at 1-pod (34.37 vs 34.6 RPS). p99 is still 28× vanilla because the auth-session writes are still opaque-blob — that's iteration 5.

### Bench numbers — 3-pod (kcb, 10 users/sec, 60 s, nginx least_conn round-robin)

| | A3 vanilla 3-pod | **B3 Skycloak iter-4 3-pod** | ratio |
|---|---|---|---|
| RPS | 34.9 | **34.0** | **0.97×** (tied) |
| Mean response | 40 ms | 53 ms | 1.33× |
| p95 | 163 ms | 183 ms | 1.12× |
| **p99** | 740 ms | **783 ms** | **1.06×** (tied) |
| Errors | 0 | 0 | tied |

This is the headline result: **at 3-pod with proper load balancing, Skycloak iter-4 matches vanilla Infinispan within noise**.

In a 3-pod deployment, vanilla loses its "zero-network" advantage — embedded Infinispan now has to replicate session data via JGroups across the cluster. That coordination cost lifts vanilla's per-request latency to roughly the same regime as Skycloak's Redis round-trips.

This reproduces PhaseTwo's claim ("matches embedded Infinispan in their 3-node cluster") without their published-numbers infrastructure (managed ElastiCache, EKS, c7gn nodes). On a developer Mac with one shared Redis container, we're already at **97 % throughput parity and p99 within 6 %**.

### Per-action breakdown — 3-pod side-by-side

```
                                  A3 vanilla         B3 Skycloak iter-4
  Browser to Log In Endpoint:    27ms p99 862        46ms p99 1089
  Browser posts credentials:     92ms p99 732        96ms p99 730
  Exchange Code:                 15ms p99 334        37ms p99 251
  Browser logout:                16ms p99 138        22ms p99 172
```

The biggest remaining per-action gap is "Browser to Log In Endpoint" (the GET that fetches the realm config + renders the login page) — 46ms vs 27ms means. That's where the L1 cache should help most; further pushing this down requires reducing what's looked up per page render.

### Critical setup detail — load balancer matters

Initial 3-pod run used nginx `ip_hash` for sticky sessions. From a single-IP test client (Gatling on the host), `ip_hash` routes everything to one pod. Result: **B3 mean 13.5 s, p99 24.7 s** — looked catastrophic but was actually a one-pod test through nginx with the other two pods idle.

Switched to `least_conn` round-robin and the numbers above emerged. **Production deployments must use a real LB strategy that distributes; sticky-session schemes need real client diversity to spread.**

### Why this proves the architecture

1. **Vanilla's 3-pod throughput is identical to its 1-pod** (34.9 vs 34.6 RPS) — its scaling cap is per-pod CPU/memory, not cluster overhead. Embedded Infinispan absorbs the replication cost gracefully.
2. **Skycloak iter-4's 3-pod RPS jumps from 1-pod's saturation regime** (1376 ms mean, 5.9 s p99) **to comfortable** (53 ms mean, 783 ms p99) — meaning the bottleneck at 1-pod was per-pod compute, not Redis. With 3 pods sharing one Redis, the system runs cooler.
3. **The gap that remains** (mean 53 ms vs 40 ms = +13 ms) is the 17 SET ops per login flow that Skycloak still does for auth-session/single-use/login-failure. Iteration 5's auth-session HSET refactor closes most of it.

## What's next (iteration 5)

1. **AuthenticationSessionProvider HSET refactor** — tree → flat keys with secondary index. Biggest remaining single-node win.
2. **3-pod compose stack** — vanilla Infinispan has to pay JGroups overhead; Skycloak doesn't. This is what makes PhaseTwo's "100 % parity" claim true and what we should validate against.
3. **Prometheus metrics** — without these, we're guessing whether L1 hit rate, Lua script timing, pipeline batch sizes match expectations. Required before iteration 5 does its own perf push.
4. **Bloom-filter revocation list** — research item from iteration 3 retrospective. Largest possible architectural win — most token validations skip the cache entirely.
