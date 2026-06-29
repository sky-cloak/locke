# 2. Classic cluster-wide PUBLISH for L1 invalidation in cluster mode

Status: Accepted (2026-06-16)

## Context

Enabling the L1 (Caffeine) cache in Redis Cluster mode, which until now was disabled
there (cluster reads paid a Redis round-trip), requires carrying
the single broadcast invalidation channel (`kc:l1:invalidate`) to every node. Redis
Cluster offers two pub/sub flavours: classic PUBLISH/SUBSCRIBE (propagated cluster-wide,
Redis 6+) and sharded SPUBLISH/SSUBSCRIBE (per-slot, Redis 7+).

## Decision

Use classic PUBLISH/SUBSCRIBE via Lettuce's `RedisClusterClient` pub/sub connection. One
broadcast channel, cluster-wide delivery, every node subscribes. This generalizes the
existing Lettuce-based `L1InvalidationBus` (which already serves standalone and sentinel)
to cluster with minimal change and keeps invalidation on the same client as cache ops.

## Alternatives considered

- Sharded pub/sub (SPUBLISH/SSUBSCRIBE). Rejected as the default: a single channel funnels
  all invalidation traffic to one shard's slot and every node must subscribe to that
  shard, it requires Redis 7+, and it is a poor fit for one broadcast channel. Kept as a
  possible future optimization if invalidation volume ever dominates.
- Redisson RTopic (Redisson is already cluster-connected for locks). Rejected: rewrites
  the working Lettuce-based bus and puts Redisson on the hot invalidation path.

## Consequences

- Each invalidation propagates to all cluster nodes over the cluster bus; overhead is
  proportional to the write/invalidation rate, which is modest for config-cache
  invalidation.
- Works on Redis 6+ and on ElastiCache cluster-mode-enabled.
- Combined with ADR 0001, a cluster shard failover flushes each pod's L1 on reconnect,
  so no stale reads survive the failover.
