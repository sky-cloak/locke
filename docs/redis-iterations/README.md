# Redis Cache Backend — Iteration History

This directory captures the architecture of each significant iteration of the
Skycloak Redis cache backend on `feature/redis`. Each numbered file is a
point-in-time snapshot: what changed, why, what was measured, what came next.

## Index

| # | Title | Headline | Status |
|---|---|---|---|
| 1 | [Pool tuning + atomic SET PX](iteration-1-pool-and-set-px.md) | Removed PING-on-borrow, pre-warmed pool, folded GETSET+PEXPIRE → SET PX | shipped |
| 2 | [Caffeine L1 + pub/sub invalidation](iteration-2-l1-cache.md) | Added in-JVM L1 cache layer with cross-node Redis pub/sub eviction | shipped |
| 3 | [Tier 2 infrastructure + kcb harness](iteration-3-tier-2-design.md) | HashCacheAdapter, PipelinedRedisCache, LuaScripts (foundation); migrated test harness to official keycloak-benchmark Gatling | shipped (infra); provider migration deferred to iter 4 |
| 4 | [Provider migration onto Tier 2 infra](iteration-4-provider-migration.md) | LoginFailureProvider on HSET (field-level updates); SingleUseObject on GETDEL (atomic 1-RT remove); auth-session deferred for tree refactor | shipped (2 of 3) |
| 5 | [Auth-session HSET refactor + pipelining](iteration-5-auth-session-hset.md) | Auth-session entity stored as one hash; field-level updates on setTimestamp/onChildUpdated; pipelined HSET+EXPIRE = 1 RT (fixed a 2-RT regression); 1-pod mean 1376 → 655 ms | shipped |
| 6 | [Prometheus metrics surface + provider activation fix](iteration-6-prometheus-metrics.md) | RedisMetrics holder; `keycloak_redis_*` family at `/metrics`; **diagnosed + fixed the silent realm-cache disablement** (Redis cache factories' `getId` collided with Infinispan's "default" → ProviderManager dropped them before `isSupported` ran); also **fixed the WrapperClusterEvent marshaller error flood** by adding `RedisModelSchema` | shipped |

## How to read these

Each iteration doc has the same structure:

1. **Goal** — one sentence on what the iteration was supposed to do.
2. **Problem signal** — what pre-iteration measurement motivated the change.
3. **Design** — what was added/changed and why; key files; key trade-offs.
4. **Risks / open questions** — what could break.
5. **Measurements** — what improved (or didn't) post-shipping.
6. **What's next** — the gap surfaced by this iteration's data.

## Single source of truth

The over-arching architecture story lives at
[`docs/redis-cache-architecture.md`](../redis-cache-architecture.md). It points
back here for the per-iteration deep dives.

The point-in-time benchmark report lives at
[`benchmark/RESULTS.md`](../../benchmark/RESULTS.md).
