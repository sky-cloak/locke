# Iteration 3 — Tier 2: HSET storage + MULTI/EXEC batching + Lua CAS

**Date**: 2026-05-08
**Status**: infrastructure shipped; provider migration deferred to iteration 4

## What landed in this iteration

**Built and tested** (not yet wired into hot paths):
- `model/redis/src/main/java/org/keycloak/cache/redis/HashCacheAdapter.java` — HSET-based storage with secondary index sets
- `model/redis/src/main/java/org/keycloak/cache/redis/PipelinedRedisCache.java` — Lettuce async batched-write API
- `model/redis/src/main/java/org/keycloak/cache/redis/LuaScripts.java` — server-side CAS scripts with EVALSHA/EVAL fallback

**Test harness migrated** to the official `keycloak-benchmark` Gatling project (`benchmark/kcb-run.sh`).

**Deliberately deferred** to a future iteration: the actual migration of `RedisAuthenticationSessionProvider`, `RedisUserLoginFailureProvider`, and `RedisSingleUseObjectProvider` to use the new infrastructure. That refactor touches active code paths and warrants its own correctness pass + integration tests, not a same-session bolt-on.

The iteration-3 image therefore behaves identically to iteration-2 at runtime — but the building blocks for the next perf push are now in the codebase.

## Goal

Eliminate the per-request work that L1 can't help with — i.e., the unique-per-request cache writes (sessions, auth sessions, single-use tokens). Today these are 17+ separate `SET key value PX ms` round-trips per login flow. After this iteration they should be 1-3 round-trips total.

## Problem signal

At end of iteration 2, sequential single-user logins are 0.15 s warm. Concurrent 50-VU runs hit 5-10 iter/s (high variance) vs vanilla 33 iter/s. The remaining gap is genuinely the per-login Redis work for ephemeral state.

`MONITOR` of one auth-session-heavy flow:
- 17 × `SET sessions:<uuid> <bytes> PX <ms>` (one per session/auth-session/single-use object update)
- Each is its own round-trip
- All within one Keycloak `KeycloakTransaction` boundary

If we collapse these into one MULTI/EXEC, that's a 17 × → 1 × round-trip reduction on the write path. Combined with HSET (so updates touch only changed fields), an additional ~2-3× win on byte volume.

## Design (three coordinated changes)

### Part A — `MULTI/EXEC` batching at transaction commit

```
                 KeycloakSession lifecycle
                 ────────────────────────
                 begin
                  ↓
  user code  ─►  cache.put(k1, v1, ttl)  ──┐
                 cache.put(k2, v2, ttl)    │  buffer  (no L2 round-trip yet,
                 cache.put(k3, v3)         │           but L1 is updated for
                 cache.remove(k4)          │           read-after-write within
                                           │           the same txn)
                  ↓                        │
                 commit                   ─┘
                  ↓
   one round-trip:
     MULTI
       SET k1 v1 PX ms
       SET k2 v2 PX ms
       SET k3 v3
       DEL k4
     EXEC
                  ↓
                 publish L1 invalidations to peers
```

**Implementation**:
- New class `BatchingRedisCache<K, V>` decorating any `RedisCache`.
- Hooks into the `KeycloakSession` via a `KeycloakTransaction` enlistment.
- Maintains a per-thread `ThreadLocal<List<DeferredOp>>` of buffered writes.
- Reads still go directly through L1 → L2 (immediate, never deferred — read-modify-write in the same txn must see the just-written value, which the eager L1 update covers).
- On commit: drain the buffer, submit a Lettuce `multi()` block, await EXEC.
- On rollback: drop the buffer, evict affected L1 entries.
- Failures: if EXEC fails (network, server error), invalidate L1 for the affected keys (reads will re-load from L2 — the SOT).

**Wiring**: enlist via `KeycloakSession.getTransactionManager().enlist(redisTransactionWrapper)` so KC's normal commit/rollback flow drives us.

**Trade-offs**:
- Latency for read-after-write within a txn is unchanged (L1 is already eager).
- Latency for cross-node visibility is *higher* — peers don't see writes until commit. Acceptable for an authoritative SOT in Postgres + cache being eventually consistent.
- A long-running txn buffers more, but Keycloak txns are typically per-HTTP-request and short.

### Part B — HSET storage for entity caches

Today every entity is serialized whole and stored as one Redis key. To update a single field (e.g. `session.timestamp = now`), we deserialize → mutate → re-serialize → SET — a full read-modify-write across the wire.

**Hash-shaped storage**:

```
sessions:abc-123       =  HASH {
                              id:        abc-123
                              realmId:   master
                              userId:    user-7
                              timestamp: 1715181234
                              notes:     {"k1":"v1","k2":"v2"}      (json blob)
                              authNotes: {...}
                              ...
                          }
```

Reads: `HGETALL sessions:abc-123` (still one round-trip, but typed fields).

Field updates: `HSET sessions:abc-123 timestamp <new>` — **no read needed**. One round-trip, one field's worth of bytes on the wire.

**Implementation**:
- New interface `HashEntity<F>` — entities that can be flattened to a `Map<String, byte[]>` and rebuilt from one. `F` is a `enum` of valid field names.
- New class `HashCacheAdapter<K, T extends HashEntity<F>, F extends Enum<F>>` wraps Lettuce's `hgetall/hset/hmget/hdel`.
- `RedisAuthenticationSessionEntity`, `RedisLoginFailureEntity`, etc. implement `HashEntity`. (We migrate one cache at a time.)
- New API on the cache:
  ```java
  T get(K key);                                  // HGETALL, decode
  void put(K key, T entity, long ttl);           // HSET all fields + PEXPIRE
  void update(K key, F field, byte[] value);     // HSET single field
  ```

**Secondary indexes**:

```
clientSessions:by-user:user-7    =  SET {abc-123, def-456, …}
authSessions:by-realm:master     =  SET {…}
```

Maintained transactionally: when a session is created/deleted, we add/remove from the appropriate index set in the same MULTI/EXEC. This makes "all sessions for user X" a single `SMEMBERS` instead of a SCAN over the keyspace.

**Trade-offs**:
- Schema lock-in — adding/removing fields requires a code change. Acceptable for our limited entity set.
- HGETALL on entities with hundreds of fields is slower than SET/GET. Our entities have <20 fields each. Fine.
- Migration: hash storage and SET storage cannot coexist for the same key. Either we cut over per-cache, or we version the keys (`sessions:v2:abc-123`).

### Part C — Lua CAS scripts

When two threads update the same field concurrently, today we have:
- WATCH the key
- Read it
- Modify
- MULTI/EXEC — fails if WATCH detected a change
- Retry

This is multiple round-trips with retry on contention. Lua server-side CAS:

```lua
-- ttl-aware-update.lua
-- Compare a version field; if it matches, set new fields and bump version.
local current_version = tonumber(redis.call('HGET', KEYS[1], 'version') or 0)
local expected_version = tonumber(ARGV[1])
if current_version ~= expected_version then
    return -1  -- caller retries with fresh read
end
redis.call('HSET', KEYS[1], 'version', current_version + 1, 'timestamp', ARGV[2])
return 0
```

One round-trip. Atomic. No WATCH dance. Server-side branching means no thrashing on contention.

**Implementation**:
- New class `LuaScripts` — compile + cache the SHA1 of each script at startup, use `EVALSHA` for subsequent calls (script body sent only on first call per server).
- One script per atomic operation pattern we need: `cas-version-and-update.lua`, `set-if-newer-timestamp.lua`, `index-add-with-ttl.lua`.
- Called from `HashCacheAdapter` when an op needs CAS semantics.

**Why this matters for 5-nines**: Lua scripts are atomic from Redis's perspective. Concurrent writers don't thrash, lose updates, or corrupt state.

## Inter-dependencies

```
              MULTI/EXEC batching
                     ↓
                accepts ops from
                     ↓
          ┌──────────┴──────────┐
          │                     │
   HashCacheAdapter        regular cache adapter
          │                     │
          ↓                     ↓
   field-level ops          opaque values
          │
          ↓
       Lua CAS  (only when contended atomic field updates needed)
```

MULTI/EXEC and HSET are independent — both reduce per-request work, in different dimensions. Lua CAS is built on top of HSET (operates on hash fields) and is needed only when concurrent updates collide on the same hash.

## Migration order

1. **Ship MULTI/EXEC batching first** — it's transparent to call sites (existing `cache.put` ops just get bundled). Easy win.
2. **Migrate `RedisAuthenticationSessionProvider` to HSET** — single biggest win because auth-session writes dominate login latency.
3. **Migrate `RedisUserLoginFailureProvider` and `RedisSingleUseObjectProvider` to HSET** — same pattern, lighter volume.
4. **Add Lua CAS scripts** as needed when contention surfaces.

The rest of the cache surface (realm/user/client) stays on the SET path — those are read-mostly and L1 already covers the hot reads.

## Risks / open questions

| Risk | Mitigation |
|---|---|
| `KeycloakTransaction` enlistment surface — KC may not expose enlistment cleanly | Fall back to a manual flush at the existing call sites (e.g. `RedisAuthenticationSessionProvider.close()`) |
| Buffer grows unbounded for long txns | Threshold-based auto-flush; in practice KC txns are <100 ops |
| HSET schema migration when entities change | Version the cache key (`sessions:v2:`) and let the old keys age out via TTL |
| Lua CAS edge cases on script reload (Redis server restart) | `EVALSHA` falls back to `EVAL` automatically on `NOSCRIPT` error |
| Adding `BatchingRedisCache` over `L1RedisCache` over `LettuceCacheAdapter` = 3-layer decorator | Keep each layer focused; explicit factory wires them once |

## Measurement (iteration 3)

Switched the perf harness from custom k6 to the **official `keycloak-benchmark` Gatling project** (release 999.0.0-SNAPSHOT, built from the keycloak-benchmark repo). This is the harness keycloak/keycloak-benchmark uses for upstream perf decisions, so numbers are directly comparable to PhaseTwo's published results.

### Setup

```bash
cd benchmark/kcb
git clone --depth 1 https://github.com/keycloak/keycloak-benchmark
./keycloak-benchmark/mvnw -pl benchmark -am -DskipTests package
tar xzf keycloak-benchmark/benchmark/target/keycloak-benchmark-999.0.0-SNAPSHOT.tar.gz

# Run via wrapper that bypasses kcb.sh's macOS-incompat paste/date flags
cd ..
./kcb-run.sh AuthorizationCode 18080 10 60   # vanilla
./kcb-run.sh AuthorizationCode 18081 10 60   # Skycloak L1-selective
```

The wrapper invokes Gatling directly with the right system properties (`-Dserver-url`, `-Drealm-name`, `-Dclient-id`, `-Dusers-per-sec`, `-Dmeasurement`, etc.).

### Realm setup

`realm-bench-kcb.json` defines 100 users named `user-0` through `user-99` with passwords `user-0-password` through `user-99-password` — matches kcb's default username/password prefix conventions.

### Scenario

`keycloak.scenario.authentication.AuthorizationCode` — full browser-flow login: GET login page → POST credentials → exchange code → 5 × refresh → logout. Each "request" in the totals is one HTTP action; one logical user-flow is ~7-8 requests.

### Headline numbers (10 users/sec, 60 s, single-node, single-Redis)

| | A (vanilla, embedded ISPN) | B (Skycloak, L1-selective + Redis) | A wins by |
|---|---|---|---|
| Total OK requests | 2435 | 2429 | tied |
| Total RPS | 34.8 | 27.0 | 1.29× |
| Mean | **46 ms** | 4305 ms | 93× |
| p95 | **692 ms** | (not broken out — saturated) | — |
| p99 | **1.38 s** | **12.6 s** | 9.1× |
| Errors | 0 | 0 | tied |

### Per-action breakdown (vanilla A)

```
  Browser to Log In Endpoint:   675 ok / rps 9.6 / mean 41 ms / p99  944 ms
  Browser posts credentials:    675 ok / rps 9.6 / mean 102 ms / p99 1476 ms
  Exchange Code:                675 ok / rps 9.6 / mean 14 ms / p99  599 ms
  Browser logout:               410 ok / rps 5.9 / mean 17 ms / p99  264 ms
```

These are healthy numbers for embedded Infinispan. Browser login form fetch + credential post are the two heaviest paths because they involve the full theme/template rendering plus realm/key cache hits.

### Light-load smoke (5 users/sec, 30 s)

At low load, B actually beat A — A: mean 1576 ms, p99 9.5 s; B: mean 452 ms, p99 2.5 s. This is **misleading**: A was paying more for its first-request JIT/template warmup and the load wasn't sustained enough to amortize. The 10-user-per-second result is the truth.

### Why these numbers vs k6 numbers

- kcb does the **full browser flow** (HTML login page render + credential POST + cookie roundtrips). My k6 script did password-grant only — a much lighter path.
- kcb measures **per-HTTP-action latency**, not full-iteration time.
- kcb's load model is `users-per-sec` ramped up; k6 was `constant-vus`.
- For the same logical work-rate, kcb numbers are higher because each "request" in kcb is a sub-action of one user-flow.

### Comparison to PhaseTwo's published 26.4 numbers

PhaseTwo reported on a **3-pod EKS cluster + AWS ElastiCache**. Their B beat A because vanilla Infinispan paid JGroups + replication overhead. Our test is **single-node**, so vanilla pays nothing for cluster coordination — embedded ISPN is unbeatable on this topology.

To reproduce PhaseTwo's relative numbers we'd need to spin up a 3-pod KC stack on the same Redis. That's a future test on dedicated infra.

## What's next (after iteration 3)

If iter 3 closes most of the gap to PhaseTwo (~100 % of vanilla at single node, > 100 % at multi-node):

- **Iteration 4 — observability**: Prometheus metrics for L1 hit rate, Lettuce pool, Lua script timings, MULTI/EXEC commit timings. Required for production.
- **Iteration 5 — reliable invalidation**: replace Redis pub/sub with NATS JetStream or a TTL'd-set-with-replay pattern.
- **Iteration 6 — multi-region**: SNS+SQS or NATS for cross-region invalidation; AWS MemoryDB for cross-region storage; cockroach-style global txn semantics.
