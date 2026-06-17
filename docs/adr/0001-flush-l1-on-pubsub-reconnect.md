# 1. Flush L1 on pub/sub reconnect

Status: Accepted (2026-06-16)

## Context

L1 (Caffeine in-JVM) coherence across Locke pods relies on fire-and-forget Redis
pub/sub invalidation over the `kc:l1:invalidate` channel. A pod that loses its pub/sub
connection (a Redis-HA failover, a network partition) misses every invalidation
published during the gap. Redis pub/sub does not buffer or replay, so when the pod
reconnects its L1 can keep serving stale realm / client / role / user data until the L1
TTL (60s) expires. For an identity system, stale config (a rotated client secret, a
revoked role) is a correctness problem, not just a performance one.

## Decision

On pub/sub re-subscription after a reconnect, flush the local L1 (evict all). Lettuce
re-subscribes automatically on reconnect and fires the `subscribed(channel, count)`
callback each time; the first call is normal startup, the second and later calls mean
"I just reconnected." On those, the bus flushes the local L1. A reconnected node's L1 is
suspect, so flushing trades a brief cold-cache refetch for correctness.

## Alternatives considered

- Accept bounded staleness until the L1 TTL. Rejected: leaves up-to-60s stale auth data
  after every Redis failover.
- Durable replay via NATS JetStream. Deferred: a larger architectural addition.
  Flush-on-reconnect is a cheap stopgap that closes most of the gap now and composes with
  durable replay later.

## Consequences

- Brief per-pod cold-cache refetch (from Redis / DB) after a reconnect; correctness
  (no stale reads) is restored immediately rather than after the TTL.
- Applies wherever L1 is active: standalone, sentinel, and cluster (once L1-in-cluster
  lands).
- NATS durable replay can later supersede this with no-miss semantics; the flush remains a
  safe fallback.
